package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var restoreCmd = &cobra.Command{
	Use:   "restore <id>",
	Short: "Restore the controller from a backup bundle",
	Long: `Apply the named backup bundle. By default both the on-disk filesystem
and the live Mongo + Redis-protocol stores are restored. Use --no-files
or --no-data to scope the restore.

The restore is rejected if the bundle fails verification — run
'prexorctl backup verify <id>' first to see the gap.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		body := buildRestoreBody(cmd, args[0])
		dryRun, _ := body["dryRun"].(bool)
		var resp map[string]any
		if err := client.Post("/api/v1/restore", body, &resp); err != nil {
			return fmt.Errorf("restore: %w", err)
		}
		if flagJSON {
			return theme.PrintJSON(resp)
		}
		mode := "applied"
		if dryRun {
			mode = "dry-run"
		}
		theme.PrintSuccess(fmt.Sprintf("Restore %s (%s)", args[0], mode))
		if fs, ok := resp["filesystem"].(map[string]any); ok {
			theme.PrintKV("Filesystem entries", fmt.Sprintf("%.0f", num(fs, "entryCount")))
			if rb, _ := fs["rollbackRoot"].(string); rb != "" {
				theme.PrintKV("Rollback snapshot", rb)
			}
		}
		if ds, ok := resp["datastores"].(map[string]any); ok {
			theme.PrintKV("Mongo collections", fmt.Sprintf("%.0f", num(ds, "mongoCollections")))
			theme.PrintKV("Mongo prefix groups", fmt.Sprintf("%.0f", num(ds, "mongoPrefixGroups")))
			theme.PrintKV("Redis prefixes", fmt.Sprintf("%.0f", num(ds, "redisPrefixes")))
		}
		return nil
	},
}

func buildRestoreBody(cmd *cobra.Command, id string) map[string]any {
	dryRun, _ := cmd.Flags().GetBool("dry-run")
	filesystem, _ := cmd.Flags().GetBool("filesystem")
	datastores, _ := cmd.Flags().GetBool("datastores")
	return map[string]any{
		"id":         id,
		"dryRun":     dryRun,
		"filesystem": filesystem,
		"datastores": datastores,
	}
}

func init() {
	restoreCmd.Flags().Bool("dry-run", false, "Validate the bundle and report the planned changes without writing")
	restoreCmd.Flags().Bool("filesystem", true, "Restore the on-disk filesystem entries")
	restoreCmd.Flags().Bool("datastores", true, "Restore Mongo and Redis state")
}
