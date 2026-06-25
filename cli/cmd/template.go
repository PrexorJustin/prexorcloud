package cmd

import (
	"fmt"
	"strings"

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

var templateVarCmd = &cobra.Command{
	Use:   "var",
	Short: "Manage typed variable definitions for a template",
}

var templateVarListCmd = &cobra.Command{
	Use:   "list <template>",
	Short: "List typed variable definitions for a template",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var defs []map[string]any
		if err := client.Get("/api/v1/templates/"+args[0]+"/variables", &defs); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(defs)
		}

		headers := []string{"KEY", "TYPE", "SCOPE", "REQUIRED", "DEFAULT", "DESCRIPTION"}
		rows := make([][]string, 0, len(defs))
		for _, d := range defs {
			required := "no"
			if b, _ := d["required"].(bool); b {
				required = "yes"
			}
			rows = append(rows, []string{
				theme.Code(str(d, "key")),
				theme.StyleCyan().Render(str(d, "type")),
				theme.StyleDim().Render(str(d, "scope")),
				required,
				theme.StyleMute().Render(strOrDash(str(d, "defaultValue"))),
				theme.StyleDim().Render(str(d, "description")),
			})
		}
		fmt.Println(tui.SimpleListHeader(fmt.Sprintf("Variables of template %s", theme.Code(args[0])),
			shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("variables", len(defs)))
		return nil
	},
}

var templateVarSetCmd = &cobra.Command{
	Use:   "set <template>",
	Short: "Create or update a typed variable definition",
	Long: "Upsert a single typed variable definition on a template. Fetches the " +
		"template's current definitions, replaces (or appends) the one whose key " +
		"matches --key, then writes the full set back. A malformed definition is " +
		"rejected by the controller with a validation error.",
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		flags := cmd.Flags()
		key, _ := flags.GetString("key")
		typ, _ := flags.GetString("type")
		defaultVal, _ := flags.GetString("default")
		scope, _ := flags.GetString("scope")
		visibility, _ := flags.GetString("visibility")
		description, _ := flags.GetString("description")
		required, _ := flags.GetBool("required")

		def := map[string]any{
			"key":          key,
			"type":         strings.ToUpper(typ),
			"defaultValue": defaultVal,
			"required":     required,
			"scope":        strings.ToUpper(scope),
			"visibility":   strings.ToUpper(visibility),
			"description":  description,
		}

		// Build validation only from the constraints the operator actually
		// passed; omit it entirely otherwise so the controller keeps it null.
		validation := map[string]any{}
		if flags.Changed("regex") {
			v, _ := flags.GetString("regex")
			validation["regex"] = v
		}
		if flags.Changed("min") {
			v, _ := flags.GetInt("min")
			validation["min"] = v
		}
		if flags.Changed("max") {
			v, _ := flags.GetInt("max")
			validation["max"] = v
		}
		if flags.Changed("enum") {
			v, _ := flags.GetStringSlice("enum")
			validation["enumValues"] = v
		}
		if len(validation) > 0 {
			def["validation"] = validation
		}

		var defs []map[string]any
		if err := client.Get("/api/v1/templates/"+args[0]+"/variables", &defs); err != nil {
			return err
		}

		replaced := false
		for i, d := range defs {
			if str(d, "key") == key {
				defs[i] = def
				replaced = true
				break
			}
		}
		if !replaced {
			defs = append(defs, def)
		}

		if err := client.Put("/api/v1/templates/"+args[0]+"/variables", defs, nil); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(def)
		}
		action := "created"
		if replaced {
			action = "updated"
		}
		theme.PrintSuccess(fmt.Sprintf("Variable '%s' %s on template '%s'", key, action, args[0]))
		return nil
	},
}

var templateVarRmCmd = &cobra.Command{
	Use:   "rm <template> <key>",
	Short: "Remove a typed variable definition",
	Args:  cobra.ExactArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		template, key := args[0], args[1]

		var defs []map[string]any
		if err := client.Get("/api/v1/templates/"+template+"/variables", &defs); err != nil {
			return err
		}

		out := make([]map[string]any, 0, len(defs))
		found := false
		for _, d := range defs {
			if str(d, "key") == key {
				found = true
				continue
			}
			out = append(out, d)
		}
		if !found {
			return fmt.Errorf("template '%s' has no variable '%s'", template, key)
		}

		if err := client.Put("/api/v1/templates/"+template+"/variables", out, nil); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(map[string]string{"status": "removed", "template": template, "key": key})
		}
		theme.PrintSuccess(fmt.Sprintf("Variable '%s' removed from template '%s'", key, template))
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
	templateVarSetCmd.Flags().String("key", "", "Variable key (required)")
	templateVarSetCmd.Flags().String("type", "STRING", "Variable type: STRING, INT, BOOL, ENUM, SECRET")
	templateVarSetCmd.Flags().String("default", "", "Default value")
	templateVarSetCmd.Flags().String("scope", "INSTANCE", "Variable scope: TEMPLATE, GROUP, INSTANCE")
	templateVarSetCmd.Flags().Bool("required", false, "Mark the variable as required")
	templateVarSetCmd.Flags().String("description", "", "Human-readable description")
	templateVarSetCmd.Flags().String("visibility", "OPERATOR", "Visibility: ADMIN, OPERATOR")
	templateVarSetCmd.Flags().StringSlice("enum", nil, "Allowed values for ENUM type (comma-separated)")
	templateVarSetCmd.Flags().String("regex", "", "Validation regex (STRING type)")
	templateVarSetCmd.Flags().Int("min", 0, "Minimum value (INT type)")
	templateVarSetCmd.Flags().Int("max", 0, "Maximum value (INT type)")
	_ = templateVarSetCmd.MarkFlagRequired("key")

	templateVarCmd.AddCommand(templateVarListCmd, templateVarSetCmd, templateVarRmCmd)
	templateCmd.AddCommand(templateListCmd, templateVersionsCmd, templateRollbackCmd, templateVarCmd)
}
