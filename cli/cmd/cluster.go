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

// --- Recover ---------------------------------------------------------------

var clusterRecoverCmd = &cobra.Command{
	Use:   "recover",
	Short: "Recover a degraded cluster (quorum-preserved shrink or catastrophic reset)",
	Long: "Walks the operator through cluster recovery scenarios.\n\n" +
		"Quorum-preserved (≤ floor((N-1)/2) failures): force-ejects dead peers from the\n" +
		"member list via the cluster API. Repeats prexorctl cluster eject under the hood.\n\n" +
		"Catastrophic (quorum lost): prints the offline single-survivor reset procedure.\n" +
		"This is destructive filesystem surgery on a stopped controller — see\n" +
		"docs/runbooks/recover-cluster.md for the canonical playbook.",
	RunE: func(cmd *cobra.Command, args []string) error {
		ejectIDs, _ := cmd.Flags().GetStringSlice("eject")
		catastrophic, _ := cmd.Flags().GetBool("i-have-only-survivor")
		yes, _ := cmd.Flags().GetBool("yes")

		if catastrophic {
			return printCatastrophicPlaybook()
		}

		// Quorum-preserved path. Either --eject was supplied, or we ask
		// interactively which peers to drop.
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var resp map[string]any
		if err := client.Get("/api/v1/cluster/members", &resp); err != nil {
			return fmt.Errorf("could not list cluster members (is quorum lost? rerun with --i-have-only-survivor): %w", err)
		}
		members, _ := resp["members"].([]any)
		if len(members) == 0 {
			return fmt.Errorf("cluster has no members — controller may not yet be bootstrapped")
		}
		if len(ejectIDs) == 0 {
			// Interactive prompt: print the list and ask.
			theme.PrintTitle("Cluster members")
			for _, raw := range members {
				m, _ := raw.(map[string]any)
				fmt.Printf("  %s   raft=%s   last-seen=%s\n",
					theme.Code(str(m, "nodeId")),
					str(m, "raftAddr"),
					theme.StyleMute().Render(str(m, "lastSeen")))
			}
			fmt.Println()
			fmt.Print("  " + theme.StyleDim().Render("Enter dead nodeIds to eject (comma-separated, blank to cancel): "))
			reader := bufio.NewReader(os.Stdin)
			line, _ := reader.ReadString('\n')
			line = strings.TrimSpace(line)
			if line == "" {
				theme.PrintWarn("Aborted — no peers ejected.")
				return nil
			}
			for _, raw := range strings.Split(line, ",") {
				if id := strings.TrimSpace(raw); id != "" {
					ejectIDs = append(ejectIDs, id)
				}
			}
		}
		if !yes && !confirmDestructive(
			fmt.Sprintf("Force-eject %d peer(s)? %v", len(ejectIDs), ejectIDs)) {
			theme.PrintWarn("Aborted.")
			return nil
		}
		var failed []string
		for _, id := range ejectIDs {
			if err := client.Delete("/api/v1/cluster/members/"+id+"?reason=cluster+recover", nil); err != nil {
				theme.PrintError(fmt.Sprintf("eject %s: %v", id, err))
				failed = append(failed, id)
				continue
			}
			theme.PrintSuccess("Ejected " + id)
		}
		if len(failed) > 0 {
			return fmt.Errorf("eject failed for: %v", failed)
		}
		fmt.Println()
		theme.PrintWarn("Consider rotating the cluster seed: prexorctl cluster seed rotate")
		return nil
	},
}

// printCatastrophicPlaybook prints the offline single-survivor reset procedure.
// We deliberately do NOT automate the filesystem surgery — it operates on a
// stopped controller, requires per-install path knowledge, and gets the
// operator into a destructive state. The playbook is the canonical reference;
// this CLI command surfaces it so an on-call who's never read the doc still
// finds the right sequence at 3am.
func printCatastrophicPlaybook() error {
	if flagJSON {
		return theme.PrintJSON(map[string]any{
			"scenario": "catastrophic",
			"playbook": "docs/runbooks/recover-cluster.md",
			"steps": []string{
				"stop prexorcloud-controller on the survivor",
				"back up data/raft/ and config/security/cluster/",
				"preserve <raftDir>/<groupId>/sm/, rename current+log+raft-meta to .broken-<ts>",
				"start prexorcloud-controller",
				"verify: prexorctl cluster status, prexorctl cluster members (count == 1)",
				"rotate seed: prexorctl cluster seed rotate",
				"grow back to HA via prexorctl cluster join-token create",
			},
		})
	}
	theme.PrintTitle("Catastrophic recovery — single-survivor reset")
	fmt.Println("  This is destructive filesystem surgery. Read the playbook before continuing:")
	fmt.Println("    " + theme.Code("docs/runbooks/recover-cluster.md"))
	fmt.Println()
	fmt.Println("  Summary:")
	fmt.Println("    1. Stop the controller on the survivor.")
	fmt.Println("    2. Back up data/raft/ and config/security/cluster/.")
	fmt.Println("    3. Under data/raft/<groupId>/, preserve sm/ and rename current,")
	fmt.Println("       log_inprogress, raft-meta* to .broken-<ts> sidecars.")
	fmt.Println("    4. Start the controller — it boots as a single-member group,")
	fmt.Println("       the state machine replays from the preserved snapshot.")
	fmt.Println("    5. Verify: " + theme.Code("prexorctl cluster status") + " and " +
		theme.Code("prexorctl cluster members") + " (count == 1).")
	fmt.Println("    6. " + theme.Code("prexorctl cluster seed rotate") +
		" — invalidate any in-flight join tokens.")
	fmt.Println("    7. Issue fresh join tokens to grow back to HA.")
	fmt.Println()
	theme.PrintWarn("Anything in flight that hadn't replicated to the survivor is lost.")
	return nil
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

	clusterRecoverCmd.Flags().StringSlice("eject", nil,
		"Comma-separated dead nodeIds to eject (skips the interactive prompt)")
	clusterRecoverCmd.Flags().Bool("i-have-only-survivor", false,
		"Print the catastrophic single-survivor reset playbook (quorum is lost)")
	clusterRecoverCmd.Flags().Bool("yes", false, "Skip the interactive confirmation")

	clusterCmd.AddCommand(
		clusterStatusCmd,
		clusterMembersCmd,
		clusterEjectCmd,
		clusterLeaveCmd,
		clusterJoinTokenCmd,
		clusterSeedCmd,
		clusterRecoverCmd,
	)

	rootCmd.AddCommand(clusterCmd)
}
