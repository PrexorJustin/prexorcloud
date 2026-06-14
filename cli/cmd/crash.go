package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var crashCmd = &cobra.Command{
	Use:   "crash",
	Short: "View crash reports",
}

var crashListCmd = &cobra.Command{
	Use:   "list",
	Short: "List crashes",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		group, _ := cmd.Flags().GetString("group")
		node, _ := cmd.Flags().GetString("node")
		since, _ := cmd.Flags().GetString("since")

		params := map[string]string{
			"group": group,
			"node":  node,
			"from":  since,
		}

		var crashes []map[string]any
		if err := client.GetList("/api/v1/crashes", params, &crashes); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(crashes)
		}

		headers := []string{"ID", "INSTANCE", "GROUP", "NODE", "EXIT", "CLASS", "CRASHED AT", "UPTIME"}
		rows := make([][]string, 0, len(crashes))
		for _, c := range crashes {
			exitCode := num(c, "exitCode")
			exitStr := fmt.Sprintf("%.0f", exitCode)
			if exitCode != 0 {
				exitStr = theme.StyleRed().Render(exitStr)
			} else {
				exitStr = theme.StyleDim().Render(exitStr)
			}
			rows = append(rows, []string{
				theme.StyleMute().Render(str(c, "id")),
				theme.Code(str(c, "instanceId")),
				theme.StyleDim().Render(str(c, "group")),
				theme.StyleDim().Render(str(c, "node")),
				exitStr,
				theme.Pill(theme.PillRed, str(c, "classification")),
				theme.StyleMute().Render(str(c, "crashedAt")),
				theme.StyleMute().Render(formatUptime(num(c, "uptimeMs"))),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing crash reports", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("crashes", len(crashes)))
		return nil
	},
}

var crashInfoCmd = &cobra.Command{
	Use:   "info [id]",
	Short: "Show crash details",
	Args:  cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		id, err := resolveArg(args, "crash id required (e.g. `prexorctl crash info <id>`)",
			func() (string, error) { return pickCrash(client, "Select a crash") })
		if err != nil {
			return err
		}

		share := readShareFlags(cmd)
		if share.enabled {
			return runShare(
				client,
				"/api/v1/crashes/"+id+"/share",
				share.toRequest("", "", 0),
				"crash "+id,
			)
		}

		var crash map[string]any
		if err := client.Get("/api/v1/crashes/"+id, &crash); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(crash)
		}

		fmt.Println()
		fmt.Printf("%s   %s\n",
			theme.Heading("Crash report "+str(crash, "id")),
			theme.Pill(theme.PillRed, str(crash, "classification")),
		)
		fmt.Println(theme.Subtitle("Crashed at " + str(crash, "crashedAt") + " — uptime " + formatUptime(num(crash, "uptimeMs"))))
		fmt.Println()

		body := kvBlock(
			"instance", theme.Code(str(crash, "instanceId")),
			"group", theme.StyleDim().Render(str(crash, "group")),
			"node", theme.StyleDim().Render(str(crash, "node")),
			"exit code", theme.Number(fmt.Sprintf("%.0f", num(crash, "exitCode"))),
			"uptime", theme.StyleDim().Render(formatUptime(num(crash, "uptimeMs"))),
		)
		fmt.Println(tui.Card("CONTEXT", body, 56))

		if logTail, ok := crash["logTail"].([]any); ok && len(logTail) > 0 {
			fmt.Println()
			fmt.Println(theme.Subtitle("LAST LOG LINES"))
			for _, line := range logTail {
				fmt.Printf("  %s\n", theme.StyleRed().Render(fmt.Sprintf("%v", line)))
			}
		}
		fmt.Println()
		return nil
	},
}

func init() {
	crashListCmd.Flags().String("group", "", "Filter by group")
	crashListCmd.Flags().String("node", "", "Filter by node")
	crashListCmd.Flags().String("since", "", "Show crashes since (ISO 8601)")

	registerShareFlags(crashInfoCmd)

	crashCmd.AddCommand(crashListCmd, crashInfoCmd)
}
