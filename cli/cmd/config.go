package cmd

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/config"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var configCmd = &cobra.Command{
	Use:   "config",
	Short: "View and manage CLI configuration",
}

var configViewCmd = &cobra.Command{
	Use:   "view",
	Short: "Show current configuration",
	RunE: func(cmd *cobra.Command, args []string) error {
		controller := cfg.CurrentContextController()
		token := cfg.CurrentContextToken()
		ctxName := cfg.CurrentContext

		if flagJSON {
			return theme.PrintJSON(map[string]string{
				"context":    ctxName,
				"controller": controller,
				"token":      maskToken(token),
				"configPath": config.Path(),
			})
		}

		fmt.Println()
		fmt.Println(theme.Heading("Configuration"))
		fmt.Println(theme.Subtitle("Stored on disk at " + theme.Path(config.Path())))
		fmt.Println()

		body := strings.Join([]string{
			padRight(theme.StyleMute().Render("context"), 14) + theme.StyleDim().Render(valueOrDefault(ctxName, "(none)")),
			padRight(theme.StyleMute().Render("controller"), 14) + theme.Path(valueOrDefault(controller, "(not set)")),
			padRight(theme.StyleMute().Render("token"), 14) + theme.StyleDim().Render(maskToken(token)),
			padRight(theme.StyleMute().Render("accent"), 14) + theme.StyleDim().Render(valueOrDefault(cfg.Accent, "purple")),
		}, "\n")
		fmt.Println(tui.Card("CLI CONFIG", body, 56))

		effective := cfg.Resolve(flagController, flagContext)
		if effective != controller && effective != "" {
			fmt.Println()
			fmt.Println(theme.Hint("Effective controller (from env/flag): " + theme.Path(effective)))
		}
		fmt.Println()
		return nil
	},
}

var configSetCmd = &cobra.Command{
	Use:   "set <key> <value>",
	Short: "Set a configuration value on the active context",
	Long:  "Set a value on the active context. Valid keys: controller, token, accent.\nUse `prexorctl context add/use` to manage which context is active.",
	Args:  cobra.ExactArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		key := strings.ToLower(args[0])
		value := args[1]

		switch key {
		case "controller":
			if err := validateControllerURL(value); err != nil {
				return err
			}
			if cfg.CurrentContext == "" {
				cfg.CurrentContext = "default"
			}
			cfg.Upsert(cfg.CurrentContext).Controller = value
		case "token":
			if cfg.CurrentContext == "" {
				cfg.CurrentContext = "default"
			}
			cfg.Upsert(cfg.CurrentContext).Token = value
		case "accent":
			cfg.Accent = value
		default:
			return fmt.Errorf("unknown config key: %s (valid: controller, token, accent)", key)
		}

		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Set %s = %s", key, maskIfToken(key, value)))
		return nil
	},
}

var configUnsetCmd = &cobra.Command{
	Use:   "unset <key>",
	Short: "Clear a configuration value on the active context",
	Long:  "Clear a value on the active context. Valid keys: controller, token, accent.",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		key := strings.ToLower(args[0])

		switch key {
		case "controller":
			if ctx := cfg.ContextRef(""); ctx != nil {
				ctx.Controller = ""
			}
		case "token":
			if ctx := cfg.ContextRef(""); ctx != nil {
				ctx.Token = ""
			}
		case "accent":
			cfg.Accent = ""
		default:
			return fmt.Errorf("unknown config key: %s (valid: controller, token, accent)", key)
		}

		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess(fmt.Sprintf("Unset %s", key))
		return nil
	},
}

func init() {
	configCmd.AddCommand(configViewCmd, configSetCmd, configUnsetCmd)
}

func validateControllerURL(rawURL string) error {
	if !strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://") {
		return fmt.Errorf("controller URL must start with http:// or https://")
	}
	if _, err := url.Parse(rawURL); err != nil {
		return fmt.Errorf("invalid URL: %w", err)
	}
	return nil
}

func maskToken(t string) string {
	if t == "" {
		return "(not set)"
	}
	if len(t) <= 10 {
		return "***"
	}
	return t[:6] + "..." + t[len(t)-4:]
}

func maskIfToken(key, value string) string {
	if key == "token" {
		return maskToken(value)
	}
	return value
}

func valueOrDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}
