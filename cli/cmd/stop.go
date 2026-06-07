package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/spf13/cobra"

	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

var stopCmd = &cobra.Command{
	Use:   "stop",
	Short: "Stop the local PrexorCloud services (controller + daemon)",
	Long: "Stops any locally registered systemd units for the controller and daemon.\n" +
		"Requires root — run with sudo.",
	RunE: func(cmd *cobra.Command, args []string) error {
		if os.Geteuid() != 0 {
			return fmt.Errorf("requires root -- run: sudo prexorctl stop")
		}

		services := []string{
			setup.ControllerServiceName(),
			setup.DaemonServiceName(),
		}

		var stopped, missing []string
		for _, svc := range services {
			if !systemdUnitExists(svc) {
				missing = append(missing, svc)
				continue
			}
			out, err := exec.Command("systemctl", "stop", svc).CombinedOutput()
			if err != nil {
				return fmt.Errorf("systemctl stop %s: %w\n%s", svc, err, out)
			}
			stopped = append(stopped, svc)
		}

		for _, svc := range stopped {
			theme.PrintSuccess("Stopped " + svc)
		}
		for _, svc := range missing {
			fmt.Printf("%s %s not registered %s skipped\n",
				theme.StyleMute().Render(theme.Bullet()), svc, theme.StyleMute().Render(theme.Arrow()))
		}
		if len(stopped) == 0 {
			return fmt.Errorf("no PrexorCloud services found -- install via 'prexorctl setup' first")
		}
		return nil
	},
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
