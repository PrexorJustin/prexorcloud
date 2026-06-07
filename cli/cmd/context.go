package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var contextCmd = &cobra.Command{
	Use:   "context",
	Short: "Manage prexorctl contexts (per-cluster controller + token)",
	Long: `A prexorctl context is a named (controller URL + token) pair stored in
~/.prexorcloud/config.yml. Use multiple contexts to point a single CLI install
at multiple clusters (prod, staging, etc.) and switch between them with
'prexorctl context use'. The global --context flag overrides the stored
default for a single invocation.`,
}

var contextListCmd = &cobra.Command{
	Use:     "list",
	Aliases: []string{"ls"},
	Short:   "List configured contexts",
	RunE: func(cmd *cobra.Command, args []string) error {
		names := cfg.ContextNames()

		if flagJSON {
			items := make([]map[string]any, 0, len(names))
			for _, name := range names {
				ctx := cfg.Contexts[name]
				items = append(items, map[string]any{
					"name":       name,
					"controller": ctx.Controller,
					"current":    name == cfg.CurrentContext,
				})
			}
			return theme.PrintJSON(items)
		}

		if len(names) == 0 {
			theme.PrintWarn("No contexts configured. Run 'prexorctl context add <name> --controller <url>'.")
			return nil
		}

		headers := []string{"", "NAME", "CONTROLLER"}
		rows := make([][]string, 0, len(names))
		for _, name := range names {
			marker := ""
			if name == cfg.CurrentContext {
				marker = theme.StyleBrand().Render("*")
			}
			rows = append(rows, []string{
				marker,
				theme.Code(name),
				theme.Path(cfg.Contexts[name].Controller),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing contexts", ""))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("contexts", len(names)))
		return nil
	},
}

var contextCurrentCmd = &cobra.Command{
	Use:   "current",
	Short: "Print the active context name",
	RunE: func(cmd *cobra.Command, args []string) error {
		name := cfg.SelectedContextName(flagContext)
		if name == "" {
			return fmt.Errorf("no active context — run 'prexorctl context use <name>'")
		}
		if flagJSON {
			return theme.PrintJSON(map[string]string{"name": name})
		}
		fmt.Println(name)
		return nil
	},
}

var contextUseCmd = &cobra.Command{
	Use:   "use <name>",
	Short: "Set the active context",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		name := args[0]
		if err := cfg.Use(name); err != nil {
			return err
		}
		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Switched to context %q", name))
		return nil
	},
}

var (
	contextAddController string
	contextAddToken      string
)

var contextAddCmd = &cobra.Command{
	Use:   "add <name>",
	Short: "Add a new context",
	Long: `Add a new context. --controller is required; --token is optional and may be
obtained later via 'prexorctl context use <name> && prexorctl login'.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		name := args[0]
		if contextAddController == "" {
			return fmt.Errorf("--controller is required")
		}
		if err := validateControllerURL(contextAddController); err != nil {
			return err
		}
		if _, exists := cfg.Contexts[name]; exists {
			return fmt.Errorf("context %q already exists — use 'prexorctl context remove %s' first", name, name)
		}
		ctx := cfg.Upsert(name)
		ctx.Controller = contextAddController
		ctx.Token = contextAddToken
		if cfg.CurrentContext == "" {
			cfg.CurrentContext = name
		}
		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Added context %q (%s)", name, contextAddController))
		return nil
	},
}

var contextRemoveForce bool

var contextRemoveCmd = &cobra.Command{
	Use:     "remove <name>",
	Aliases: []string{"rm", "delete"},
	Short:   "Remove a context",
	Args:    cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		name := args[0]
		if name == cfg.CurrentContext && !contextRemoveForce {
			return fmt.Errorf("%q is the current context — pass --force to remove it (currentContext will be cleared)", name)
		}
		if err := cfg.Remove(name); err != nil {
			return err
		}
		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Removed context %q", name))
		return nil
	},
}

func init() {
	contextAddCmd.Flags().StringVar(&contextAddController, "controller", "", "Controller URL (required)")
	contextAddCmd.Flags().StringVar(&contextAddToken, "token", "", "Auth token (optional — set later via 'prexorctl login')")
	contextRemoveCmd.Flags().BoolVar(&contextRemoveForce, "force", false, "Remove even if it is the current context")
	contextCmd.AddCommand(contextListCmd, contextCurrentCmd, contextUseCmd, contextAddCmd, contextRemoveCmd)
}
