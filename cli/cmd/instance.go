package cmd

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var instanceCmd = &cobra.Command{
	Use:     "instance",
	Aliases: []string{"inst"},
	Short:   "Manage server instances",
}

var instanceListCmd = &cobra.Command{
	Use:   "list",
	Short: "List instances",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		group, _ := cmd.Flags().GetString("group")
		node, _ := cmd.Flags().GetString("node")
		state, _ := cmd.Flags().GetString("state")

		params := map[string]string{
			"group": group,
			"node":  node,
			"state": state,
		}

		var instances []map[string]any
		if err := client.GetList("/api/v1/services", params, &instances); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(instances)
		}

		running, other := 0, 0
		headers := []string{"ID", "GROUP", "NODE", "STATE", "PORT", "PLAYERS", "UPTIME"}
		rows := make([][]string, 0, len(instances))
		for _, inst := range instances {
			s := str(inst, "state")
			if s == "RUNNING" {
				running++
			} else {
				other++
			}
			rows = append(rows, []string{
				theme.Code(str(inst, "id")),
				theme.StyleDim().Render(str(inst, "group")),
				theme.StyleDim().Render(str(inst, "node")),
				theme.StatusPill(s),
				theme.Number(fmt.Sprintf("%.0f", num(inst, "port"))),
				theme.Number(fmt.Sprintf("%.0f", num(inst, "playerCount"))),
				theme.StyleMute().Render(formatUptime(num(inst, "uptimeMs"))),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing instances", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("instances", len(instances),
			fmt.Sprintf("%d running", running),
			fmt.Sprintf("%d other", other),
		))
		return nil
	},
}

var instanceInfoCmd = &cobra.Command{
	Use:   "info <id>",
	Short: "Show instance details",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var inst map[string]any
		if err := client.Get("/api/v1/services/"+args[0], &inst); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(inst)
		}

		fmt.Println()
		fmt.Printf("%s   %s   group %s %s node %s\n",
			theme.Heading(str(inst, "id")),
			theme.StatusPill(str(inst, "state")),
			theme.Code(str(inst, "group")),
			theme.Bullet(),
			theme.Code(str(inst, "node")),
		)
		fmt.Println(theme.Subtitle("Started at " + str(inst, "startedAt") + " — uptime " + formatUptime(num(inst, "uptimeMs"))))
		fmt.Println()

		body := kvBlock(
			"port", theme.Number(fmt.Sprintf("%.0f", num(inst, "port"))),
			"players", theme.Number(fmt.Sprintf("%.0f", num(inst, "playerCount"))),
			"memory", fmt.Sprintf("%.0f MB", num(inst, "memoryMb")),
			"uptime", theme.StyleDim().Render(formatUptime(num(inst, "uptimeMs"))),
		)
		fmt.Println(tui.Card("INSTANCE", body, 48))
		fmt.Println()
		return nil
	},
}

var instanceStartCmd = &cobra.Command{
	Use:   "start <group>",
	Short: "Start a new instance in a group",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var result map[string]any
		if err := client.Post("/api/v1/groups/"+args[0]+"/start", nil, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}

		count := str(result, "count")
		if count == "" {
			count = "1"
		}
		theme.PrintSuccess(fmt.Sprintf("%s instance(s) scheduled in group %s", count, args[0]))
		return nil
	},
}

var instanceStopCmd = &cobra.Command{
	Use:   "stop <id>",
	Short: "Stop an instance",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		force, _ := cmd.Flags().GetBool("force")
		path := "/api/v1/services/" + args[0] + "/stop"
		if force {
			path = "/api/v1/services/" + args[0] + "/force-stop"
		}

		if err := client.Post(path, nil, nil); err != nil {
			return err
		}

		if force {
			theme.PrintSuccess("Instance " + args[0] + " force-stopped")
		} else {
			theme.PrintSuccess("Instance " + args[0] + " stopping")
		}
		return nil
	},
}

var instanceExecCmd = &cobra.Command{
	Use:   "exec <id> <command...>",
	Short: "Send a command to an instance",
	Args:  cobra.MinimumNArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		command := strings.Join(args[1:], " ")
		body := map[string]string{"command": command}

		if err := client.Post("/api/v1/services/"+args[0]+"/command", body, nil); err != nil {
			return err
		}

		theme.PrintSuccess(fmt.Sprintf("Sent to %s: %s", args[0], command))
		return nil
	},
}

var instanceConsoleCmd = &cobra.Command{
	Use:   "console <id>",
	Short: "Attach to an instance's live console",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		return attachConsole(cmd.Context(), client, args[0])
	},
}

// attachConsole opens the live console view for an instance. Shared by
// `instance console` and the `↵ attach` action in the group-info view.
func attachConsole(parent context.Context, client *api.Client, id string) error {
	{
		// Best-effort instance lookup so the header can show node/group/state.
		var inst map[string]any
		_ = client.Get("/api/v1/services/"+id, &inst)
		node := str(inst, "node")
		group := str(inst, "group")
		state := str(inst, "state")
		if state == "" {
			state = "UNKNOWN"
		}

		ctx, cancel := context.WithCancel(parent)
		defer cancel()

		ls := tui.NewLogStream(tui.LogStreamConfig{
			Header:  consoleHeader(id, node, group, state),
			Command: "instance console " + id,
			Cluster: shortHost(cfg.Resolve(flagController, flagContext)),
			Version: cliVersion,
			Input:   true,
			Submit: func(line string) {
				_ = client.Post("/api/v1/services/"+id+"/command",
					map[string]string{"command": line}, nil)
			},
		})

		go func() {
			streamErr := client.SSEStream(ctx, "/api/v1/services/"+id+"/console",
				func(event, data string) bool {
					ls.Push(formatConsoleLine(data))
					return ctx.Err() == nil
				})
			if ctx.Err() != nil {
				streamErr = nil
			}
			ls.Close(streamErr)
		}()

		runErr := ls.Run()
		cancel()
		return runErr
	}
}

// consoleHeader builds the design's `▲ ATTACHED` header block.
func consoleHeader(id, node, group, state string) []string {
	meta := ""
	if node != "" {
		meta += "  " + theme.Bullet() + " node " + theme.Code(node)
	}
	if group != "" {
		meta += "  " + theme.Bullet() + " group " + theme.Code(group)
	}
	return []string{
		fmt.Sprintf("%s %s  to instance %s%s  %s %s",
			theme.StyleBrand().Render(theme.BrandGlyph()),
			theme.StyleBrand().Bold(true).Render("ATTACHED"),
			theme.Code(id),
			meta,
			theme.Bullet(),
			theme.StatusPill(state),
		),
		theme.HRule(72),
		theme.Hint("Streaming stdout/stderr in realtime. Type to send commands. " +
			theme.Code("Ctrl-Q") + " to detach."),
		"",
	}
}

func init() {
	instanceListCmd.Flags().String("group", "", "Filter by group")
	instanceListCmd.Flags().String("node", "", "Filter by node")
	instanceListCmd.Flags().String("state", "", "Filter by state")

	instanceStopCmd.Flags().Bool("force", false, "Force kill immediately")

	instanceCmd.AddCommand(instanceListCmd, instanceInfoCmd, instanceStartCmd, instanceStopCmd, instanceExecCmd, instanceConsoleCmd)
}

func formatUptime(ms float64) string {
	d := time.Duration(ms) * time.Millisecond
	if d < time.Minute {
		return fmt.Sprintf("%ds", int(d.Seconds()))
	}
	if d < time.Hour {
		return fmt.Sprintf("%dm%ds", int(d.Minutes()), int(d.Seconds())%60)
	}
	return fmt.Sprintf("%dh%dm", int(d.Hours()), int(d.Minutes())%60)
}

// formatConsoleLine applies the design's level coloring to a Minecraft-style
// log line ("[12:34:56] [INFO]  Server thread/INFO:  message"). Lines that
// don't match the pattern are passed through unchanged.
func formatConsoleLine(line string) string {
	upper := strings.ToUpper(line)
	switch {
	case strings.Contains(upper, "[ERROR]") || strings.Contains(upper, "/ERROR"):
		return theme.StyleRed().Render(line)
	case strings.Contains(upper, "[WARN]") || strings.Contains(upper, "/WARN"):
		return theme.StyleAmber().Render(line)
	case strings.Contains(upper, "[INFO]") || strings.Contains(upper, "/INFO"):
		return theme.StyleCyan().Render(line)
	default:
		return line
	}
}
