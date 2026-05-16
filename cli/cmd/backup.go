package cmd

import (
	"fmt"
	"time"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var backupCmd = &cobra.Command{
	Use:   "backup",
	Short: "Manage controller backups (create, list, verify, prune)",
}

var backupCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new controller backup bundle",
	Long: `Trigger the controller to dump Mongo, Redis, and on-disk security/template/module
state into a new bundle under the controller-side backup directory.

The CLI does not transport the bundle. Bundles live next to the controller
on disk; copy them off-host (e.g. with restic / rclone) for off-site
retention.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var manifest map[string]any
		if err := client.Post("/api/v1/backups", nil, &manifest); err != nil {
			return fmt.Errorf("create backup: %w", err)
		}
		if flagJSON {
			return theme.PrintJSON(manifest)
		}
		theme.PrintSuccess(fmt.Sprintf("Backup %s created (%s, %.0f mongo docs, %.0f redis keys, %.0f files)",
			str(manifest, "id"),
			humanBytes(int64(num(manifest, "sizeBytes"))),
			num(manifest, "mongoDocumentCount"),
			num(manifest, "redisKeyCount"),
			num(manifest, "fileCount")))
		return nil
	},
}

var backupListCmd = &cobra.Command{
	Use:   "list",
	Short: "List backups stored on the controller",
	Args:  cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var resp map[string]any
		if err := client.Get("/api/v1/backups", &resp); err != nil {
			return fmt.Errorf("list backups: %w", err)
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		items, _ := resp["items"].([]any)
		theme.PrintKV("Directory", str(resp, "directory"))
		theme.PrintKV("Retention", fmt.Sprintf("%.0f", num(resp, "retentionCount")))
		if len(items) == 0 {
			theme.PrintWarn("No backups yet — run `prexorctl backup create`.")
			return nil
		}
		headers := []string{"ID", "Created", "Size", "Mongo Docs", "Redis Keys", "Files"}
		var rows [][]string
		for _, raw := range items {
			m, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			rows = append(rows, []string{
				theme.Code(str(m, "id")),
				theme.StyleMute().Render(time.UnixMilli(int64(num(m, "createdAtMs"))).UTC().Format("2006-01-02 15:04:05Z")),
				theme.Number(humanBytes(int64(num(m, "sizeBytes")))),
				theme.Number(fmt.Sprintf("%.0f", num(m, "mongoDocumentCount"))),
				theme.Number(fmt.Sprintf("%.0f", num(m, "redisKeyCount"))),
				theme.Number(fmt.Sprintf("%.0f", num(m, "fileCount"))),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing controller backups", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("backups", len(items),
			fmt.Sprintf("retain %.0f", num(resp, "retentionCount")),
		))
		return nil
	},
}

var backupVerifyCmd = &cobra.Command{
	Use:   "verify <id>",
	Short: "Verify a backup bundle is restorable",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var resp map[string]any
		if err := client.Post("/api/v1/backups/"+args[0]+"/verify", nil, &resp); err != nil {
			return fmt.Errorf("verify backup: %w", err)
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		valid, _ := resp["valid"].(bool)
		if valid {
			theme.PrintSuccess(fmt.Sprintf("Backup %s is valid", args[0]))
			return nil
		}
		theme.PrintError(fmt.Sprintf("Backup %s is INVALID", args[0]))
		printList("Missing files", resp["missingFiles"])
		printList("Missing directories", resp["missingDirectories"])
		printList("Missing mongo collections", resp["missingMongoCollections"])
		printList("Missing mongo prefixes", resp["missingMongoCollectionPrefixes"])
		printList("Missing redis prefixes", resp["missingRedisPrefixes"])
		printList("Empty required files", resp["emptyRequiredFiles"])
		return fmt.Errorf("backup verification failed")
	},
}

var backupPruneCmd = &cobra.Command{
	Use:   "prune",
	Short: "Delete old backups beyond the retention count",
	Args:  cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		keep, _ := cmd.Flags().GetInt("keep")
		path := "/api/v1/backups/prune"
		if keep > 0 {
			path += fmt.Sprintf("?keep=%d", keep)
		}
		var resp map[string]any
		if err := client.Post(path, nil, &resp); err != nil {
			return fmt.Errorf("prune backups: %w", err)
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		removed, _ := resp["removed"].([]any)
		if len(removed) == 0 {
			theme.PrintSuccess("No backups pruned (within retention)")
			return nil
		}
		theme.PrintSuccess(fmt.Sprintf("Pruned %d backup(s)", len(removed)))
		for _, id := range removed {
			fmt.Println("  - " + fmt.Sprintf("%v", id))
		}
		return nil
	},
}

var backupDeleteCmd = &cobra.Command{
	Use:     "delete <id>",
	Aliases: []string{"rm"},
	Short:   "Delete a single backup by id",
	Args:    cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		if err := client.Delete("/api/v1/backups/"+args[0], nil); err != nil {
			return fmt.Errorf("delete backup: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Backup %s deleted", args[0]))
		return nil
	},
}

func printList(label string, raw any) {
	items, ok := raw.([]any)
	if !ok || len(items) == 0 {
		return
	}
	theme.PrintKV(label, "")
	for _, item := range items {
		fmt.Printf("  - %v\n", item)
	}
}

func init() {
	backupPruneCmd.Flags().Int("keep", 0, "Keep this many recent backups (default: server retentionCount)")
	backupCmd.AddCommand(backupCreateCmd, backupListCmd, backupVerifyCmd, backupPruneCmd, backupDeleteCmd)
}
