package cmd

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

// cluster subcommands cover the controller-to-controller cluster control
// plane (Phase 5 of cluster-join-plan.md). The CLI is a thin wrapper over
// /api/v1/cluster/* — see the controller-side route files for permission
// gating and audit behavior.

var clusterCmd = &cobra.Command{
	Use:   "cluster",
	Short: "Manage the controller cluster",
}

// --- Status ----------------------------------------------------------------

var clusterStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show cluster status (id, member count, active config version)",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var status map[string]any
		if err := client.Get("/api/v1/cluster", &status); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(status)
		}
		theme.PrintTitle("Cluster status")
		theme.PrintKV("Cluster ID", str(status, "clusterId"))
		theme.PrintKV("Members", fmt.Sprintf("%.0f", num(status, "memberCount")))
		theme.PrintKV("Active config version", fmt.Sprintf("%.0f", num(status, "activeConfigVersion")))
		theme.PrintKV("Created at", str(status, "createdAt"))
		fmt.Println()
		return nil
	},
}

// --- Members ---------------------------------------------------------------

var clusterMembersCmd = &cobra.Command{
	Use:   "members",
	Short: "List cluster controller members",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var resp map[string]any
		if err := client.Get("/api/v1/cluster/members", &resp); err != nil {
			return err
		}
		members, _ := resp["members"].([]any)
		if flagJSON {
			return theme.PrintJSON(members)
		}
		headers := []string{"NODE ID", "RAFT ADDR", "REST ADDR", "GRPC ADDR", "LABEL", "JOINED AT"}
		rows := make([][]string, 0, len(members))
		for _, raw := range members {
			m, _ := raw.(map[string]any)
			rows = append(rows, []string{
				theme.Code(str(m, "nodeId")),
				str(m, "raftAddr"),
				str(m, "restAddr"),
				str(m, "gRPCAddr"),
				str(m, "label"),
				theme.StyleMute().Render(str(m, "joinedAt")),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing cluster members",
			shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("members", len(members)))
		return nil
	},
}

var clusterEjectCmd = &cobra.Command{
	Use:   "eject <nodeId>",
	Short: "Force-remove a controller from the cluster (irreversible)",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		yes, _ := cmd.Flags().GetBool("yes")
		if !yes && !confirmDestructive(fmt.Sprintf("Force-eject controller %q? It will be removed from the Raft group.", args[0])) {
			theme.PrintWarn("Aborted.")
			return nil
		}
		reason, _ := cmd.Flags().GetString("reason")
		path := "/api/v1/cluster/members/" + args[0]
		if reason != "" {
			path += "?reason=" + reason
		}
		if err := client.Delete(path, nil); err != nil {
			return err
		}
		theme.PrintSuccess("Ejected controller " + args[0])
		return nil
	},
}

var clusterLeaveCmd = &cobra.Command{
	Use:   "leave",
	Short: "Have the targeted controller gracefully leave the cluster and shut down",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		yes, _ := cmd.Flags().GetBool("yes")
		if !yes && !confirmDestructive("Have this controller leave the cluster? It will shut down after the leave commits.") {
			theme.PrintWarn("Aborted.")
			return nil
		}
		var resp map[string]any
		if err := client.Post("/api/v1/cluster/leave", map[string]any{}, &resp); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		theme.PrintSuccess(fmt.Sprintf("Controller %s leaving cluster %s",
			str(resp, "nodeId"), str(resp, "clusterId")))
		return nil
	},
}

// --- Join tokens -----------------------------------------------------------

var clusterJoinTokenCmd = &cobra.Command{
	Use:   "join-token",
	Short: "Manage cluster join tokens (issued to new controllers)",
}

var clusterJoinTokenCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Issue a new cluster join token (prints the wire token ONCE)",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		ttlSeconds, _ := cmd.Flags().GetInt("ttl-seconds")
		label, _ := cmd.Flags().GetString("label")
		joinAddrs, _ := cmd.Flags().GetStringSlice("join-addr")
		if len(joinAddrs) == 0 {
			return fmt.Errorf("at least one --join-addr is required (gRPC host:port of an existing controller)")
		}
		body := map[string]any{
			"ttlSeconds": ttlSeconds,
			"joinAddrs":  joinAddrs,
		}
		if label != "" {
			body["label"] = label
		}
		var result map[string]any
		if err := client.Post("/api/v1/cluster/join-tokens", body, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintTitle("Cluster join token issued")
		theme.PrintKV("JTI", str(result, "jti"))
		theme.PrintKV("Token", str(result, "token"))
		theme.PrintKV("Expires at", str(result, "expiresAt"))
		fmt.Println()
		theme.PrintWarn("This is the only time the token is shown. Save it now.")
		return nil
	},
}

var clusterJoinTokenListCmd = &cobra.Command{
	Use:   "list",
	Short: "List outstanding cluster join tokens",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var resp map[string]any
		if err := client.Get("/api/v1/cluster/join-tokens", &resp); err != nil {
			return err
		}
		tokens, _ := resp["tokens"].([]any)
		if flagJSON {
			return theme.PrintJSON(tokens)
		}
		headers := []string{"JTI", "LABEL", "STATUS", "CREATED AT", "EXPIRES AT"}
		rows := make([][]string, 0, len(tokens))
		for _, raw := range tokens {
			t, _ := raw.(map[string]any)
			rows = append(rows, []string{
				theme.StyleMute().Render(str(t, "jti")),
				str(t, "label"),
				theme.StatusPill(str(t, "status")),
				theme.StyleDim().Render(str(t, "createdAt")),
				theme.StyleDim().Render(str(t, "expiresAt")),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing cluster join tokens",
			shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("tokens", len(tokens)))
		return nil
	},
}

var clusterJoinTokenRevokeCmd = &cobra.Command{
	Use:   "revoke <jti>",
	Short: "Revoke an outstanding cluster join token",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		if err := client.Delete("/api/v1/cluster/join-tokens/"+args[0], nil); err != nil {
			return err
		}
		theme.PrintSuccess("Revoked join token " + args[0])
		return nil
	},
}

// --- Seed rotate -----------------------------------------------------------

var clusterSeedCmd = &cobra.Command{
	Use:   "seed",
	Short: "Manage the cluster seed secret (HMAC key for join tokens)",
}

var clusterSeedRotateCmd = &cobra.Command{
	Use:   "rotate",
	Short: "Rotate the cluster seed secret (invalidates outstanding tokens)",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		yes, _ := cmd.Flags().GetBool("yes")
		if !yes && !confirmDestructive(
			"Rotate the cluster seed? Every outstanding join token will become invalid.") {
			theme.PrintWarn("Aborted.")
			return nil
		}
		var resp map[string]any
		if err := client.Post("/api/v1/cluster/seed/rotate", map[string]any{}, &resp); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		theme.PrintSuccess(fmt.Sprintf("Seed rotated for cluster %s by %s at %s",
			str(resp, "clusterId"), str(resp, "rotatedBy"), str(resp, "rotatedAt")))
		return nil
	},
}

// --- Helpers ---------------------------------------------------------------

// confirmDestructive is a small y/N prompt for the cluster subcommands. We
// don't pull in huh here — the surface is plain text and shipping a TUI for
// a one-shot confirm is overkill.
func confirmDestructive(prompt string) bool {
	fmt.Print("  " + theme.StyleDim().Render(prompt+" ") + theme.StyleMute().Render("[y/N] "))
	reader := bufio.NewReader(os.Stdin)
	line, err := reader.ReadString('\n')
	if err != nil && line == "" {
		return false
	}
	ans := strings.ToLower(strings.TrimSpace(line))
	return ans == "y" || ans == "yes"
}

func init() {
	clusterEjectCmd.Flags().Bool("yes", false, "Skip the interactive confirmation")
	clusterEjectCmd.Flags().String("reason", "", "Audit reason recorded with the ejection")

	clusterLeaveCmd.Flags().Bool("yes", false, "Skip the interactive confirmation")

	clusterJoinTokenCreateCmd.Flags().Int("ttl-seconds", 24*60*60, "Token TTL in seconds")
	clusterJoinTokenCreateCmd.Flags().String("label", "", "Human-readable label (e.g. controller-2)")
	clusterJoinTokenCreateCmd.Flags().StringSlice("join-addr",
		nil, "Existing controller gRPC host:port; repeat for multiple")
	clusterJoinTokenCmd.AddCommand(
		clusterJoinTokenCreateCmd,
		clusterJoinTokenListCmd,
		clusterJoinTokenRevokeCmd,
	)

	clusterSeedRotateCmd.Flags().Bool("yes", false, "Skip the interactive confirmation")
	clusterSeedCmd.AddCommand(clusterSeedRotateCmd)

	clusterCmd.AddCommand(
		clusterStatusCmd,
		clusterMembersCmd,
		clusterEjectCmd,
		clusterLeaveCmd,
		clusterJoinTokenCmd,
		clusterSeedCmd,
	)

	rootCmd.AddCommand(clusterCmd)
}
