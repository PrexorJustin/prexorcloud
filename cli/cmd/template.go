package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var templateCmd = &cobra.Command{
	Use:   "template",
	Short: "Manage templates",
}

var templateListCmd = &cobra.Command{
	Use:   "list",
	Short: "List templates",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var templates []map[string]any
		if err := client.GetList("/api/v1/templates", nil, &templates); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(templates)
		}

		headers := []string{"NAME", "HASH", "SIZE", "DESCRIPTION"}
		rows := make([][]string, 0, len(templates))
		for _, t := range templates {
			hash := str(t, "hash")
			if len(hash) > 8 {
				hash = hash[:8]
			}
			rows = append(rows, []string{
				theme.Code(str(t, "name")),
				theme.StyleMute().Render(hash),
				theme.Number(formatBytes(num(t, "sizeBytes"))),
				theme.StyleDim().Render(str(t, "description")),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing templates", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("templates", len(templates)))
		return nil
	},
}

var templateVersionsCmd = &cobra.Command{
	Use:   "versions <name>",
	Short: "Show version history for a template",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var versions []map[string]any
		if err := client.Get("/api/v1/templates/"+args[0]+"/versions", &versions); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(versions)
		}

		headers := []string{"#", "HASH", "SIZE", "CREATED"}
		rows := make([][]string, 0, len(versions))
		for i, v := range versions {
			hash := str(v, "hash")
			if len(hash) > 8 {
				hash = hash[:8]
			}
			rows = append(rows, []string{
				theme.Number(fmt.Sprintf("%d", len(versions)-i)),
				theme.StyleMute().Render(hash),
				theme.Number(formatBytes(num(v, "sizeBytes"))),
				theme.StyleDim().Render(str(v, "createdAt")),
			})
		}
		fmt.Println(tui.SimpleListHeader(fmt.Sprintf("Versions of template %s", theme.Code(args[0])),
			shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("versions", len(versions)))
		return nil
	},
}

var templateRollbackCmd = &cobra.Command{
	Use:   "rollback <name>",
	Short: "Rollback a template to its previous version",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		if err := client.Post("/api/v1/templates/"+args[0]+"/rollback", nil, nil); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(map[string]string{"status": "rolled_back", "template": args[0]})
		}
		theme.PrintSuccess(fmt.Sprintf("Template '%s' rolled back", args[0]))
		return nil
	},
}

func formatBytes(b float64) string {
	if b == 0 {
		return "0 B"
	}
	if b < 1024 {
		return fmt.Sprintf("%.0f B", b)
	}
	if b < 1024*1024 {
		return fmt.Sprintf("%.1f KB", b/1024)
	}
	return fmt.Sprintf("%.1f MB", b/(1024*1024))
}

func init() {
	templateCmd.AddCommand(templateListCmd, templateVersionsCmd, templateRollbackCmd)
}
