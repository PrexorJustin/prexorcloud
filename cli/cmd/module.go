package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/charmbracelet/huh"
	"github.com/prexorcloud/prexorctl/internal/scaffold"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var moduleCmd = &cobra.Command{
	Use:   "module",
	Short: "Manage modules",
}

var moduleListCmd = &cobra.Command{
	Use:   "list",
	Short: "List modules",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var modules []map[string]any
		if err := client.GetList("/api/v1/modules", nil, &modules); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(modules)
		}

		enabled, disabled := 0, 0
		headers := []string{"NAME", "ENABLED", "FRONTEND", "PLUGINS"}
		rows := make([][]string, 0, len(modules))
		for _, m := range modules {
			hasFrontend := "no"
			if m["frontend"] != nil {
				hasFrontend = "yes"
			}
			pluginCount := "0"
			if plugins, ok := m["plugins"].([]any); ok {
				pluginCount = fmt.Sprintf("%d", len(plugins))
			}
			isOn, _ := m["enabled"].(bool)
			pillKind := "DISABLED"
			if isOn {
				pillKind = "ENABLED"
				enabled++
			} else {
				disabled++
			}
			rows = append(rows, []string{
				theme.Code(str(m, "name")),
				theme.StatusPill(pillKind),
				theme.StyleDim().Render(hasFrontend),
				theme.Number(pluginCount),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing modules", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("modules", len(modules),
			fmt.Sprintf("%d enabled", enabled),
			fmt.Sprintf("%d disabled", disabled),
		))
		return nil
	},
}

var moduleUploadCmd = &cobra.Command{
	Use:   "upload <file.jar>",
	Short: "Upload and install a module",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		filePath := args[0]
		if !strings.HasSuffix(filePath, ".jar") {
			return fmt.Errorf("only .jar files are accepted")
		}

		client, err := requireAuth()
		if err != nil {
			return err
		}

		var result map[string]any
		if err := client.Upload("/api/v1/modules/platform/upload", filePath, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}

		theme.PrintSuccess(fmt.Sprintf("Module %q installed", str(result, "moduleId")))
		return nil
	},
}

var moduleDeleteCmd = &cobra.Command{
	Use:   "delete <name>",
	Short: "Remove a module",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		if err := client.Delete("/api/v1/modules/platform/"+args[0], nil); err != nil {
			return err
		}

		theme.PrintSuccess("Module " + args[0] + " removed")
		return nil
	},
}

var (
	moduleNewPackage       string
	moduleNewRepoRoot      string
	moduleNewStripComments bool
	moduleNewForce         bool
	moduleNewDry           bool
	moduleNewInteractive   bool
	moduleNewWizard        bool
	moduleNewBrowser       bool
	moduleNewTargets       []string
	moduleNewMcPlugin      []string
	moduleNewCapabilities  []string
	moduleNewRequires      []string
	moduleNewNoRest        bool
	moduleNewNoMongo       bool
	moduleNewNoFrontend    bool
	moduleNewNoPlugin      bool
)

var moduleNewCmd = &cobra.Command{
	Use:     "new [name]",
	Aliases: []string{"scaffold"},
	// Local-only: operates entirely on files under repo-root, never talks to
	// a controller. Opt out of the pre-link gate so a fresh contributor can
	// scaffold a module before linking the CLI to any cluster.
	Annotations: map[string]string{"local-only": "true"},
	Short:       "Scaffold a new cloud-module — full wizard or fast template copy",
	Long: `Generates a new cloud-module under java/cloud-modules/<name>/.

Three flavours, picked by flag:

  (default)            — Full wizard (TUI). Asks identity, storage, REST,
                         frontend, plugin yes/no, per-platform multi-version
                         strategy, capabilities. Calls the underlying scaffold
                         with the answers. The name argument is optional in
                         wizard mode (the wizard asks for it).

  --interactive        — Compact prompt: only asks which platform-plugin
                         targets to ship. Same as the wizard's targets step,
                         everything else uses example-template defaults.

  --targets paper,…    — Non-interactive subset. For scripts.
  --mc-plugin paper,…  — Alias for --targets, kept to match the docs.

  --all-defaults       — No prompts at all. Drops the full example template,
                         all targets emitted. Same shape as the historical
                         scripts/new-module.mjs behaviour.

Composable non-interactive flags (for scripting and CI):

  --capabilities id[@ver],…   Capabilities the new module provides.
  --requires id[@range],…     Capabilities the new module consumes.
  --no-rest                   Strip the rest/ package (warning until the
                              REST-removal pass lands; see scaffold.go).
  --no-mongo                  Strip the storage scaffold (same warning).
  --no-frontend               Strip the frontend/ Vue package.
  --no-plugin                 Generate a backend-only module (no plugin/).

Replaces scripts/new-module.mjs.`,
	Args: cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		root := moduleNewRepoRoot
		if root == "" {
			cwd, err := os.Getwd()
			if err != nil {
				return err
			}
			root, err = scaffold.FindRepoRoot(cwd)
			if err != nil {
				return fmt.Errorf("%w (pass --repo-root to override)", err)
			}
		}

		// --mc-plugin is an alias for --targets. If both are set, --targets wins
		// and we surface the ambiguity instead of silently picking one.
		targets := moduleNewTargets
		if len(moduleNewMcPlugin) > 0 {
			if len(targets) > 0 {
				return fmt.Errorf("--targets and --mc-plugin are aliases; pass only one")
			}
			targets = moduleNewMcPlugin
		}

		// Decide which flow to run. Order:
		//   --browser   -> not implemented yet (return clear error)
		//   --interactive (legacy, targets-only prompt) preserved for scripts
		//   --capabilities / --requires / --no-* (composable spec flags)
		//   --targets / --mc-plugin / --all-defaults (no prompts)
		//   default (no name OR wizard explicitly requested) -> full wizard
		if moduleNewBrowser {
			return fmt.Errorf("module new --browser: not implemented in this build (Phase B.2). " +
				"Use the default TUI wizard for now.")
		}

		// Legacy interactive mode = targets-only prompt. Not the wizard.
		if moduleNewInteractive {
			if len(args) == 0 {
				return fmt.Errorf("module new --interactive: name is required")
			}
			picked, err := promptModuleNewTargets()
			if err != nil {
				return err
			}
			return runScaffold(scaffold.Options{
				RepoRoot:      root,
				Name:          args[0],
				Package:       moduleNewPackage,
				StripComments: moduleNewStripComments,
				Force:         moduleNewForce,
				Dry:           moduleNewDry,
				Targets:       picked,
			})
		}

		// Composable non-interactive path: any of --capabilities / --requires /
		// --no-rest / --no-mongo / --no-frontend / --no-plugin builds a full
		// ModuleSpec from flags and runs the same applyWizardOverrides pass as
		// the TUI wizard. Requires a name argument.
		if hasComposableSpecFlags(cmd) {
			if len(args) == 0 {
				return fmt.Errorf("module new --capabilities/--requires/--no-*: name is required")
			}
			spec, err := buildSpecFromFlags(args[0], targets)
			if err != nil {
				return err
			}
			opts := spec.ApplyToOptions(root, scaffold.Options{
				Package:       moduleNewPackage,
				StripComments: moduleNewStripComments,
				Force:         moduleNewForce,
				Dry:           moduleNewDry,
			})
			return runScaffold(opts)
		}

		// Non-interactive paths: --targets / --mc-plugin / --all-defaults.
		if len(targets) > 0 || moduleNewWizard == false && len(args) == 1 && hasNonWizardFlags(cmd) {
			return runScaffold(scaffold.Options{
				RepoRoot:      root,
				Name:          args[0],
				Package:       moduleNewPackage,
				StripComments: moduleNewStripComments,
				Force:         moduleNewForce,
				Dry:           moduleNewDry,
				Targets:       targets,
			})
		}

		// Default: run the full wizard.
		initialName := ""
		if len(args) > 0 {
			initialName = args[0]
		}
		spec, err := runModuleNewWizard(initialName)
		if err != nil {
			return err
		}
		printSpecSummary(spec, root)
		opts := spec.ApplyToOptions(root, scaffold.Options{
			Package:       moduleNewPackage,
			StripComments: moduleNewStripComments,
			Force:         moduleNewForce,
			Dry:           moduleNewDry,
		})
		return runScaffold(opts)
	},
}

// hasNonWizardFlags reports whether the user passed any of the legacy
// non-wizard flags that imply they want the fast template-copy path rather
// than the wizard.
func hasNonWizardFlags(cmd *cobra.Command) bool {
	for _, n := range []string{"all-defaults", "targets", "mc-plugin"} {
		if f := cmd.Flag(n); f != nil && f.Changed {
			return true
		}
	}
	return false
}

// hasComposableSpecFlags reports whether the user passed any of the
// non-interactive spec-building flags. These trigger the buildSpecFromFlags
// path instead of the legacy template-copy or wizard paths.
func hasComposableSpecFlags(cmd *cobra.Command) bool {
	for _, n := range []string{"capabilities", "requires", "no-rest", "no-mongo", "no-frontend", "no-plugin"} {
		if f := cmd.Flag(n); f != nil && f.Changed {
			return true
		}
	}
	return false
}

// buildSpecFromFlags assembles a scaffold.ModuleSpec from the composable
// CLI flags. Defaults mirror the wizard: REST, Mongo, frontend, and plugin
// are all on unless the user passed --no-X. The --capabilities and
// --requires flags accept "id" or "id@version" entries (range for requires).
func buildSpecFromFlags(name string, targets []string) (*scaffold.ModuleSpec, error) {
	spec := &scaffold.ModuleSpec{
		Name:         name,
		WithMongo:    !moduleNewNoMongo,
		WithRest:     !moduleNewNoRest,
		WithFrontend: !moduleNewNoFrontend,
		WithPlugin:   !moduleNewNoPlugin,
	}
	if spec.WithPlugin {
		// Fall back to AllTargets when --no-plugin wasn't set and no targets
		// were given — mirrors the wizard's "ship every platform by default"
		// path. An explicit --no-plugin forces an empty target set.
		if len(targets) == 0 {
			targets = scaffold.AllTargets()
		}
		spec.PluginTargets = make([]scaffold.PluginTarget, 0, len(targets))
		for _, t := range targets {
			spec.PluginTargets = append(spec.PluginTargets, scaffold.PluginTarget{
				Platform:             t,
				MultiVersionStrategy: "single",
			})
		}
	}
	for _, raw := range moduleNewCapabilities {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		id, version, _ := strings.Cut(raw, "@")
		if version == "" {
			version = "1.0.0"
		}
		spec.Provides = append(spec.Provides, scaffold.CapabilitySpec{ID: id, Version: version})
	}
	for _, raw := range moduleNewRequires {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		id, vrange, _ := strings.Cut(raw, "@")
		if vrange == "" {
			vrange = "[1.0,2.0)"
		}
		spec.Requires = append(spec.Requires, scaffold.CapabilityRequirement{ID: id, VersionRange: vrange})
	}
	return spec, nil
}

func runScaffold(opts scaffold.Options) error {
	res, err := scaffold.Generate(opts)
	if err != nil {
		return err
	}
	fmt.Printf("→ new module: %s\n", res.Kebab)
	fmt.Printf("  package:  %s\n", res.PackageDot)
	fmt.Printf("  dest:     %s\n", res.DestDir)
	if moduleNewDry {
		fmt.Println("  (dry run — no files written)")
	}
	fmt.Printf("  %d files\n", res.FilesWritten)
	if res.SettingsPatched {
		fmt.Printf("  patched settings.gradle.kts (+%d includes)\n", res.IncludesAdded)
	} else {
		fmt.Println("  settings.gradle.kts already contains the new includes — skipped")
	}
	fmt.Println()
	fmt.Println("next:")
	fmt.Printf("  cd java && ./gradlew :cloud-modules:%s:build\n", res.Kebab)
	fmt.Println("  (run pnpm install from the dashboard workspace to pick up the new frontend package)")
	return nil
}

// promptModuleNewTargets runs an interactive multi-select for platform
// plugin targets. Returns the picked subset (lower-cased).
func promptModuleNewTargets() ([]string, error) {
	options := []huh.Option[string]{
		huh.NewOption("Paper (server)", "paper").Selected(true),
		huh.NewOption("Folia (server)", "folia").Selected(true),
		huh.NewOption("Velocity (proxy)", "velocity").Selected(true),
	}
	picked := []string{}
	err := huh.NewForm(huh.NewGroup(
		huh.NewMultiSelect[string]().
			Title("Which platform-plugin targets should ship in this module?").
			Description("Unticked targets are skipped: their plugin/<target> subdir is not generated, " +
				"and settings.gradle.kts won't include the subproject.").
			Options(options...).
			Value(&picked),
	)).WithTheme(tui.HuhTheme()).Run()
	if err != nil {
		return nil, err
	}
	if len(picked) == 0 {
		return nil, fmt.Errorf("module new: at least one target must be selected")
	}
	return picked, nil
}

func init() {
	moduleNewCmd.Flags().StringVar(&moduleNewPackage, "package", "",
		"Override the generated Java package (default: me.prexorjustin.prexorcloud.modules.<name>)")
	moduleNewCmd.Flags().StringVar(&moduleNewRepoRoot, "repo-root", "",
		"Path to the repo root (default: discovered upwards from the working directory)")
	moduleNewCmd.Flags().BoolVar(&moduleNewStripComments, "strip-comments", false,
		"Remove `// STEP …` and `/** STEP … */` teaching comments from generated sources")
	moduleNewCmd.Flags().BoolVar(&moduleNewForce, "force", false,
		"Overwrite an existing module directory instead of failing")
	moduleNewCmd.Flags().BoolVar(&moduleNewDry, "dry", false,
		"Walk the template and print what would happen without writing anything")
	moduleNewCmd.Flags().BoolVar(&moduleNewInteractive, "interactive", false,
		"Compact prompt: only asks which platform-plugin targets. Skips the full wizard.")
	moduleNewCmd.Flags().BoolVar(&moduleNewWizard, "wizard", false,
		"Force the full wizard even when --targets / --all-defaults are passed. (Wizard is the default already.)")
	moduleNewCmd.Flags().BoolVar(&moduleNewBrowser, "browser", false,
		"Open the wizard in a local browser (Phase B.2 — currently returns a clear error).")
	moduleNewCmd.Flags().StringSliceVar(&moduleNewTargets, "targets", nil,
		"Comma-separated subset of plugin targets (paper,folia,velocity,bungeecord,bedrock-geyser). "+
			"Skips the wizard.")
	moduleNewCmd.Flags().StringSliceVar(&moduleNewMcPlugin, "mc-plugin", nil,
		"Alias for --targets, kept to match the docs and the northstar plan.")
	moduleNewCmd.Flags().Bool("all-defaults", false,
		"Skip the wizard and emit the full example template (legacy behaviour).")
	// StringArrayVar (not Slice) — version ranges like "[1.0,2.0)" contain
	// commas, which StringSliceVar would split. Users pass the flag once per
	// capability instead.
	moduleNewCmd.Flags().StringArrayVar(&moduleNewCapabilities, "capabilities", nil,
		"Capability the module provides — \"id\" or \"id@version\" "+
			"(default version 1.0.0). Pass multiple times for multiple capabilities.")
	moduleNewCmd.Flags().StringArrayVar(&moduleNewRequires, "requires", nil,
		"Capability the module requires — \"id\" or \"id@range\" "+
			"(default range [1.0,2.0)). Pass multiple times for multiple requirements.")
	moduleNewCmd.Flags().BoolVar(&moduleNewNoRest, "no-rest", false,
		"Strip the rest/ package from the generated module. (Warning surfaces "+
			"until the REST-removal pass lands — see scaffold.go.)")
	moduleNewCmd.Flags().BoolVar(&moduleNewNoMongo, "no-mongo", false,
		"Strip the storage scaffold from the generated module. (Same warning.)")
	moduleNewCmd.Flags().BoolVar(&moduleNewNoFrontend, "no-frontend", false,
		"Strip the frontend/ Vue package — module is backend-only.")
	moduleNewCmd.Flags().BoolVar(&moduleNewNoPlugin, "no-plugin", false,
		"Skip every plugin/<target> subdir — module is backend-only (no in-game plugins).")

	moduleCmd.AddCommand(moduleListCmd, moduleUploadCmd, moduleDeleteCmd, moduleNewCmd)
}
