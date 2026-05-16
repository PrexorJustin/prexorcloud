package cmd

import (
	"errors"
	"fmt"
	"os"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/config"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var (
	flagJSON       bool
	flagController string
	flagToken      string
	flagContext    string
	flagNoColor    bool
	flagASCII      bool
	flagVerbose    bool

	cfg *config.Config
)

// commandsAllowedBeforeLink lists the subcommands that work when no controller
// context exists. Everything else is gated behind a "no cluster connected"
// message so first-time users see exactly the two doors they have:
// install something (`setup`) or link to an existing cluster (`login`).
var commandsAllowedBeforeLink = map[string]bool{
	"setup":      true,
	"login":      true,
	"logout":     true,
	"version":    true,
	"help":       true,
	"completion": true,
	"context":    true, // viewing/listing contexts is harmless before any are populated
}

var rootCmd = &cobra.Command{
	Use:   "prexorctl",
	Short: "PrexorCloud CLI -- manage your Minecraft cloud cluster",
	Long:  "prexorctl is a command-line tool for managing PrexorCloud clusters.",
	PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
		var err error
		cfg, err = config.Load()
		if err != nil {
			return fmt.Errorf("load config: %w", err)
		}
		accent := theme.Accent(cfg.Accent)
		if accent == "" {
			accent = theme.AccentPurple
		}
		// JSON mode and non-tty stdout both imply no-color.
		noColor := flagNoColor || flagJSON
		if fi, err := os.Stdout.Stat(); err == nil && (fi.Mode()&os.ModeCharDevice) == 0 {
			noColor = true
		}
		theme.Init(accent, noColor, flagASCII)

		// Gate: until the CLI has at least one context, only allow the commands
		// that exist to fix that state. Resolves flag/env overrides too — passing
		// --controller/--token (or PREXOR_CONTROLLER) is a valid way to "use" a
		// cluster without writing a context first.
		if cfg.CurrentContextController() == "" && cfg.Resolve(flagController, flagContext) == "" {
			top := topLevelName(cmd)
			if top != "" && !commandsAllowedBeforeLink[top] {
				return fmt.Errorf(
					"no cluster connected — run 'prexorctl setup' to install a component, " +
						"or 'prexorctl login' to link this CLI to an existing controller")
			}
		}
		return nil
	},
	SilenceUsage:  true,
	SilenceErrors: true,
}

// topLevelName walks up to the immediate child of root, so subcommands like
// `prexorctl node list` resolve to "node" (the entry the allowlist keys on).
func topLevelName(cmd *cobra.Command) string {
	for cmd != nil && cmd.Parent() != nil {
		if cmd.Parent().Parent() == nil {
			return cmd.Name()
		}
		cmd = cmd.Parent()
	}
	return ""
}

// ExitCodeError lets command implementations return a typed error that
// the root handler maps to a specific os.Exit code, without sprinkling
// os.Exit() calls inside individual cobra runners. Use sparingly — most
// commands should just return a plain error and accept exit code 1.
//
// Convention:
//
//	0  success (no error returned)
//	1  generic failure (default for any error)
//	2  validation/diagnostic failure with non-fatal warnings (e.g.
//	   `prexorctl module doctor` with warnings only) — see module_doctor.go
//	   401/403/404+ from API responses are mapped via api.APIError.ExitCode()
type ExitCodeError struct {
	Code    int
	Message string
}

func (e *ExitCodeError) Error() string { return e.Message }

func Execute() error {
	err := rootCmd.Execute()
	if err != nil {
		theme.PrintError(err.Error())
		var exitErr *ExitCodeError
		if errors.As(err, &exitErr) {
			os.Exit(exitErr.Code)
		}
		var apiErr *api.APIError
		if errors.As(err, &apiErr) {
			os.Exit(apiErr.ExitCode())
		}
		os.Exit(1)
	}
	return nil
}

func init() {
	rootCmd.PersistentFlags().BoolVarP(&flagJSON, "json", "j", false, "Output in JSON format")
	rootCmd.PersistentFlags().StringVarP(&flagController, "controller", "c", "", "Override controller URL")
	rootCmd.PersistentFlags().StringVarP(&flagToken, "token", "t", "", "Override auth token")
	rootCmd.PersistentFlags().StringVar(&flagContext, "context", "", "Override active context for this invocation")
	rootCmd.PersistentFlags().BoolVar(&flagNoColor, "no-color", false, "Disable colored output")
	rootCmd.PersistentFlags().BoolVar(&flagASCII, "ascii", false, "Use ASCII glyphs only (no unicode box drawing, sparklines, etc.)")
	rootCmd.PersistentFlags().BoolVarP(&flagVerbose, "verbose", "v", false, "Show HTTP request/response details")

	// Check PREXOR_OUTPUT env for default JSON mode
	if os.Getenv("PREXOR_OUTPUT") == "json" {
		flagJSON = true
	}

	installHelp(rootCmd)

	rootCmd.AddCommand(
		setupCmd,
		stopCmd,
		loginCmd,
		logoutCmd,
		statusCmd,
		versionCmd,
		configCmd,
		contextCmd,
		nodeCmd,
		groupCmd,
		instanceCmd,
		tokenCmd,
		userCmd,
		roleCmd,
		crashCmd,
		templateCmd,
		moduleCmd,
		pluginCmd,
		logsCmd,
		diagnosticsCmd,
		shareCmd,
		deployCmd,
		backupCmd,
		restoreCmd,
		completionCmd,
	)
}

// newClient creates a configured API client, validating that controller and token are set.
func newClient() (*api.Client, error) {
	controller := cfg.Resolve(flagController, flagContext)
	if controller == "" {
		return nil, fmt.Errorf("no controller URL configured -- run 'prexorctl setup' or 'prexorctl context add <name> --controller <url>'")
	}
	token := cfg.ResolveToken(flagToken, flagContext)
	return api.New(controller, token, flagVerbose), nil
}

// requireAuth creates a client and verifies a token is available.
func requireAuth() (*api.Client, error) {
	c, err := newClient()
	if err != nil {
		return nil, err
	}
	if c.Token == "" {
		return nil, fmt.Errorf("not authenticated -- run 'prexorctl login'")
	}
	return c, nil
}
