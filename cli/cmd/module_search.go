package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var moduleSearchCmd = &cobra.Command{
	Use:   "search [query]",
	Short: "Browse modules available in the configured registries",
	Long: `List the modules offered by the controller's configured registries
(modules.registries), optionally filtered by a case-insensitive substring of
the module id or its tags.

Install one with:  prexorctl module install <id>[@<version>]`,
	Args: cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		params := map[string]string{}
		if len(args) == 1 {
			params["q"] = args[0]
		}

		var resp struct {
			Registries []string         `json:"registries"`
			Modules    []map[string]any `json:"modules"`
		}
		if err := client.GetWithQuery("/api/v1/modules/platform/registry", params, &resp); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(resp)
		}

		if len(resp.Registries) == 0 {
			theme.PrintWarn("No registries configured. Set modules.registries in controller.yml.")
			return nil
		}

		headers := []string{"MODULE", "VERSION", "REGISTRY", "INSTALLED", "TAGS"}
		rows := make([][]string, 0, len(resp.Modules))
		for _, m := range resp.Modules {
			installed := theme.StyleDim().Render("—")
			if isOn, _ := m["installed"].(bool); isOn {
				installed = theme.StatusPill("ENABLED")
				if iv := str(m, "installedVersion"); iv != "" {
					installed = fmt.Sprintf("%s (%s)", theme.StatusPill("ENABLED"), iv)
				}
			}
			tags := ""
			if t, ok := m["tags"].([]any); ok {
				for i, tag := range t {
					if i > 0 {
						tags += ", "
					}
					tags += fmt.Sprintf("%v", tag)
				}
			}
			signed := str(m, "registryName")
			if s, _ := m["signed"].(bool); !s {
				signed += theme.StyleDim().Render(" (unsigned)")
			}
			rows = append(rows, []string{
				theme.Code(str(m, "moduleId")),
				theme.Number(str(m, "version")),
				signed,
				installed,
				theme.StyleDim().Render(tags),
			})
		}

		fmt.Println(tui.SimpleListHeader("Registry modules", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("modules", len(resp.Modules),
			fmt.Sprintf("%d registr%s", len(resp.Registries), plural(len(resp.Registries), "y", "ies"))))
		return nil
	},
}

func plural(n int, one, many string) string {
	if n == 1 {
		return one
	}
	return many
}

func init() {
	moduleCmd.AddCommand(moduleSearchCmd)
}
