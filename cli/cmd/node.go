package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var nodeCmd = &cobra.Command{
	Use:   "node",
	Short: "Manage cluster nodes",
}

var nodeListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all nodes",
	RunE: func(cmd *cobra.Command, args []string) error {
		state, _ := cmd.Flags().GetString("state")
		params := map[string]string{}
		if state != "" {
			params["state"] = state
		}

		var nodes []map[string]any
		_, done, err := fetchList("/api/v1/nodes", params, &nodes)
		if err != nil || done {
			return err
		}

		online, draining, offline := 0, 0, 0
		headers := []string{"ID", "STATUS", "CPU", "MEMORY", "INSTANCES", "CONNECTED SINCE"}
		rows := make([][]string, 0, len(nodes))
		for _, n := range nodes {
			s := str(n, "status")
			switch s {
			case "ONLINE":
				online++
			case "DRAINING":
				draining++
			default:
				offline++
			}
			rows = append(rows, []string{
				theme.Code(str(n, "nodeId")),
				theme.StatusPill(s),
				theme.Number(fmt.Sprintf("%.0f%%", num(n, "cpuUsage"))),
				fmt.Sprintf("%.0f/%.0f MB", num(n, "usedMemoryMb"), num(n, "totalMemoryMb")),
				theme.Number(fmt.Sprintf("%.0f", num(n, "instanceCount"))),
				theme.StyleMute().Render(str(n, "connectedSince")),
			})
		}

		fmt.Println(tui.ListHeader("Listing nodes in cluster",
			shortHost(cfg.Resolve(flagController, flagContext)), state, ""))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("nodes", len(nodes),
			fmt.Sprintf("%d online", online),
			fmt.Sprintf("%d draining", draining),
			fmt.Sprintf("%d offline", offline),
		))
		return nil
	},
}

var nodeInfoCmd = &cobra.Command{
	Use:   "info <id>",
	Short: "Show node details",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var node map[string]any
		if err := client.Get("/api/v1/nodes/"+args[0], &node); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(node)
		}

		fmt.Println()
		fmt.Printf("%s   %s\n",
			theme.Heading(str(node, "nodeId")),
			theme.StatusPill(str(node, "status")),
		)
		fmt.Println(theme.Subtitle("Connected since " + str(node, "connectedSince")))
		fmt.Println()

		resourceBody := kvBlock(
			"cpu", theme.Number(fmt.Sprintf("%.1f%%", num(node, "cpuUsage"))),
			"memory", fmt.Sprintf("%.0f/%.0f MB", num(node, "usedMemoryMb"), num(node, "totalMemoryMb")),
			"free disk", fmt.Sprintf("%.0f MB", num(node, "freeDiskMb")),
			"instances", theme.Number(fmt.Sprintf("%.0f", num(node, "instanceCount"))),
		)
		fmt.Println(tui.Card("RESOURCES", resourceBody, 48))

		if instances, ok := node["instances"].([]any); ok && len(instances) > 0 {
			fmt.Println()
			fmt.Println(theme.Subtitle("RUNNING INSTANCES"))
			headers := []string{"ID", "GROUP", "STATE", "PORT", "PLAYERS"}
			rows := make([][]string, 0, len(instances))
			for _, raw := range instances {
				if inst, ok := raw.(map[string]any); ok {
					rows = append(rows, []string{
						theme.Code(str(inst, "id")),
						theme.StyleDim().Render(str(inst, "group")),
						theme.StatusPill(str(inst, "state")),
						theme.Number(fmt.Sprintf("%.0f", num(inst, "port"))),
						theme.Number(fmt.Sprintf("%.0f", num(inst, "playerCount"))),
					})
				}
			}
			tui.PrintTable(headers, rows)
		}
		fmt.Println()
		return nil
	},
}

var nodeDrainCmd = &cobra.Command{
	Use:   "drain <id>",
	Short: "Mark node as draining",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		if err := client.Post("/api/v1/nodes/"+args[0]+"/drain", nil, nil); err != nil {
			return err
		}
		theme.PrintSuccess("Node " + args[0] + " set to DRAINING")
		return nil
	},
}

var nodeUndrainCmd = &cobra.Command{
	Use:   "undrain <id>",
	Short: "Remove draining mark from node",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		if err := client.Post("/api/v1/nodes/"+args[0]+"/undrain", nil, nil); err != nil {
			return err
		}
		theme.PrintSuccess("Node " + args[0] + " set to ONLINE")
		return nil
	},
}

func init() {
	nodeListCmd.Flags().String("state", "", "Filter by state (ONLINE, DRAINING, UNREACHABLE, OFFLINE)")
	nodeCmd.AddCommand(nodeListCmd, nodeInfoCmd, nodeDrainCmd, nodeUndrainCmd)
}
