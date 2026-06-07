package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var tokenCmd = &cobra.Command{
	Use:   "token",
	Short: "Manage node join tokens",
}

var tokenCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new join token",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		nodeID, _ := cmd.Flags().GetString("node")
		ttl, _ := cmd.Flags().GetString("ttl")

		body := map[string]any{}
		if nodeID != "" {
			body["nodeId"] = nodeID
		}
		if ttl != "" {
			body["ttl"] = ttl
		}

		var result map[string]any
		if err := client.Post("/api/v1/admin/tokens", body, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}

		theme.PrintTitle("Join Token Created")
		theme.PrintKV("Token ID", str(result, "tokenId"))
		theme.PrintKV("Join Token", str(result, "token"))
		theme.PrintKV("Node ID", str(result, "nodeId"))
		theme.PrintKV("Expires At", str(result, "expiresAt"))
		fmt.Println()
		return nil
	},
}

var tokenListCmd = &cobra.Command{
	Use:   "list",
	Short: "List join tokens",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var tokens []map[string]any
		if err := client.GetList("/api/v1/admin/tokens", nil, &tokens); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(tokens)
		}

		headers := []string{"TOKEN ID", "NODE", "EXPIRES AT", "STATUS"}
		rows := make([][]string, 0, len(tokens))
		for _, t := range tokens {
			rows = append(rows, []string{
				theme.StyleMute().Render(str(t, "tokenId")),
				theme.Code(str(t, "nodeId")),
				theme.StyleDim().Render(str(t, "expiresAt")),
				theme.StatusPill(str(t, "status")),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing join tokens", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("tokens", len(tokens)))
		return nil
	},
}

var tokenRevokeCmd = &cobra.Command{
	Use:   "revoke <id>",
	Short: "Revoke a join token",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		if err := client.Delete("/api/v1/admin/tokens/"+args[0], nil); err != nil {
			return err
		}

		theme.PrintSuccess("Token " + args[0] + " revoked")
		return nil
	},
}

func init() {
	tokenCreateCmd.Flags().String("node", "", "Node ID for the token")
	tokenCreateCmd.Flags().String("ttl", "1h", "Token time-to-live (e.g. 1h, 24h)")
	tokenCmd.AddCommand(tokenCreateCmd, tokenListCmd, tokenRevokeCmd)
}
