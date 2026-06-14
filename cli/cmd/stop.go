package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/charmbracelet/huh"
	"github.com/spf13/cobra"

	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

var stopCmd = &cobra.Command{
	Use:   "stop [local|node|controller]",
	Short: "Stop PrexorCloud services — locally or across the fleet",
	Long: `Stop PrexorCloud services, on this host or anywhere in the fleet.

  prexorctl stop local            # this host's controller + daemon (Docker Compose or systemd)
  prexorctl stop node [id]        # remote-stop a daemon via the control plane (picker if no id)
  prexorctl stop controller       # remote-stop the controller you're connected to

With no subcommand in a terminal, a picker lets you choose what to stop. In a
script or pipe it falls back to stopping the local services, so existing
automation keeps working. Remote stops prompt for confirmation; pass -y/--yes to
skip (required in non-interactive shells).`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if interactive() {
			return runStopChooser(cmd, args)
		}
		return runStopLocal(cmd, args)
	},
}

// runStopChooser is the `group info`-style entry point for bare `stop` in a TTY.
func runStopChooser(cmd *cobra.Command, args []string) error {
	choice, err := pickOne("What do you want to stop?", []huh.Option[string]{
		huh.NewOption("Local services   — this host's controller + daemon (Docker or systemd)", "local"),
		huh.NewOption("Daemon node      — stop a node's daemon across the fleet", "node"),
		huh.NewOption("Controller       — stop the controller you're connected to", "controller"),
	})
	if err != nil {
		return err
	}
	switch choice {
	case "local":
		return runStopLocal(cmd, args)
	case "node":
		return stopNodeCmd.RunE(cmd, nil)
	case "controller":
		return stopControllerCmd.RunE(cmd, nil)
	}
	return nil
}

var stopLocalCmd = &cobra.Command{
	Use:   "local",
	Short: "Stop this host's local PrexorCloud services (Docker Compose or systemd)",
	Args:  cobra.NoArgs,
	RunE:  runStopLocal,
}

// localComponent is one locally-installed service and how it was deployed.
type localComponent struct {
	name       string // "controller" / "daemon"
	composeDir string // install dir if a docker-compose.yml is present (Docker mode)
	systemd    string // systemd unit name if installed natively
}

func (c localComponent) mode() string {
	switch {
	case c.composeDir != "":
		return "docker"
	case c.systemd != "":
		return "systemd"
	default:
		return ""
	}
}

func runStopLocal(_ *cobra.Command, _ []string) error {
	components := []localComponent{
		{name: "controller", composeDir: composeDirIfPresent(setup.ControllerComposeDir()), systemd: unitIfPresent(setup.ControllerServiceName())},
		{name: "daemon", composeDir: composeDirIfPresent(setup.DaemonComposeDir()), systemd: unitIfPresent(setup.DaemonServiceName())},
	}

	// systemctl needs root; Docker relies on the caller's docker access (fails clearly otherwise).
	needsRoot := false
	for _, c := range components {
		if c.mode() == "systemd" {
			needsRoot = true
		}
	}
	if needsRoot && os.Geteuid() != 0 {
		return fmt.Errorf("stopping native systemd services requires root -- run: sudo prexorctl stop local")
	}

	var stopped, missing []string
	for _, c := range components {
		switch c.mode() {
		case "docker":
			if err := setup.ComposeStop(c.composeDir); err != nil {
				return err
			}
			stopped = append(stopped, c.name+" (docker)")
		case "systemd":
			out, err := exec.Command("systemctl", "stop", c.systemd).CombinedOutput()
			if err != nil {
				return fmt.Errorf("systemctl stop %s: %w\n%s", c.systemd, err, out)
			}
			stopped = append(stopped, c.name+" (systemd)")
		default:
			missing = append(missing, c.name)
		}
	}

	for _, s := range stopped {
		theme.PrintSuccess("Stopped " + s)
	}
	for _, m := range missing {
		fmt.Printf("%s %s not installed here %s skipped\n",
			theme.StyleMute().Render(theme.Bullet()), m, theme.StyleMute().Render(theme.Arrow()))
	}
	if len(stopped) == 0 {
		return fmt.Errorf("no PrexorCloud services found on this host -- install via 'prexorctl setup' first")
	}
	return nil
}

func composeDirIfPresent(dir string) string {
	if setup.HasComposeProject(dir) {
		return dir
	}
	return ""
}

func unitIfPresent(unit string) string {
	if systemdUnitExists(unit) {
		return unit
	}
	return ""
}

var stopNodeCmd = &cobra.Command{
	Use:   "node [id]",
	Short: "Immediately stop a daemon node across the fleet (via the controller)",
	Long: "Sends a shutdown command straight to the node's daemon — no drain. Its instances stop and " +
		"the scheduler reschedules them onto other nodes if capacity allows. For a graceful, " +
		"instance-preserving stop use `prexorctl node drain` instead.",
	Args: cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		id, err := resolveArg(args, "node id required (e.g. `prexorctl stop node <id>`)",
			func() (string, error) { return pickNode(client, "Select a node to stop") })
		if err != nil {
			return err
		}

		ok, err := confirmStop(cmd,
			fmt.Sprintf("Stop daemon node '%s'?", id),
			"Its running instances are stopped immediately; the scheduler reschedules them onto other nodes if capacity allows.")
		if err != nil {
			return err
		}
		if !ok {
			fmt.Println("Cancelled.")
			return nil
		}

		var result map[string]any
		if err := client.Post("/api/v1/nodes/"+id+"/shutdown", nil, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Shutdown command sent to node '%s'", id))
		return nil
	},
}

var stopControllerCmd = &cobra.Command{
	Use:   "controller",
	Short: "Stop the controller you're connected to",
	Long: "Gracefully stops the controller this context points at. In an HA cluster the remaining peers " +
		"re-elect a leader; a lone controller goes fully offline. Note: if the controller runs under a " +
		"restart-always supervisor (e.g. Docker `restart: unless-stopped`) it will be restarted — stop " +
		"the container/unit instead for a permanent stop.",
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		target := shortHost(cfg.Resolve(flagController, flagContext))

		ok, err := confirmStop(cmd,
			fmt.Sprintf("Stop the controller at %s?", target),
			"This controller process exits. In an HA cluster peers re-elect a leader; a single controller goes fully offline.")
		if err != nil {
			return err
		}
		if !ok {
			fmt.Println("Cancelled.")
			return nil
		}

		var result map[string]any
		if err := client.Post("/api/v1/system/shutdown", nil, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Controller shutdown initiated (%s)", target))
		return nil
	},
}

// confirmStop gates a destructive remote stop. --yes skips it; in a non-interactive
// shell without --yes it refuses (so a stray scripted `stop` can't take down the fleet).
func confirmStop(cmd *cobra.Command, title, desc string) (bool, error) {
	if yes, _ := cmd.Flags().GetBool("yes"); yes {
		return true, nil
	}
	if !interactive() {
		return false, fmt.Errorf("refusing to stop without confirmation in a non-interactive shell — pass --yes")
	}
	var ok bool
	if err := huh.NewConfirm().
		Title(title).
		Description(desc).
		Value(&ok).
		WithTheme(tui.HuhTheme()).
		Run(); err != nil {
		return false, err
	}
	return ok, nil
}

// systemdUnitExists returns true when systemd knows about a unit file with the
// given name (independent of its active/inactive state).
func systemdUnitExists(name string) bool {
	out, err := exec.Command("systemctl", "list-unit-files", name+".service", "--no-legend").CombinedOutput()
	if err != nil {
		return false
	}
	return strings.Contains(string(out), name+".service")
}

func init() {
	stopCmd.PersistentFlags().BoolP("yes", "y", false, "Skip the confirmation prompt for remote stops")
	stopCmd.AddCommand(stopLocalCmd, stopNodeCmd, stopControllerCmd)
}
