package cmd

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/charmbracelet/huh"
	"github.com/prexorcloud/prexorctl/internal/scaffold"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

// runModuleNewWizard runs the full-fat interactive wizard for `prexorctl
// module new` and returns a populated ModuleSpec ready to feed
// scaffold.Generate. Asks: identity, storage, REST, frontend, plugin yes/no,
// per-platform multi-version strategy, capabilities.
//
// Implementation status (Phase B v1): the wizard ASKS every question and
// builds a complete spec, but the underlying generator only honours the
// targets selection, the frontend toggle, and capabilities today. Storage
// removal, REST removal, JAR-split per-version subprojects, and the NMS
// (paperweight) track are accepted by the spec but emit a "not yet
// implemented in the generator — example defaults will be used" notice when
// chosen. The questions stay because they're the desired UX and because
// follow-up turns will land the implementation behind them.
func runModuleNewWizard(initialName string) (*scaffold.ModuleSpec, error) {
	spec := &scaffold.ModuleSpec{
		Name:         initialName,
		WithMongo:    true,
		WithRest:     true,
		WithFrontend: true,
		WithPlugin:   true,
	}

	// ── Identity ──────────────────────────────────────────────────────────
	if err := huh.NewForm(huh.NewGroup(
		huh.NewInput().
			Title("Module id (kebab-case, no `cloud-module-` prefix)").
			Description("Becomes the directory cloud-module-<id>, the Java package suffix, and module.yaml id.").
			Value(&spec.Name).
			Validate(validateKebab),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return nil, err
	}

	// ── Backend shape ─────────────────────────────────────────────────────
	// NOTE: WithMongo + WithRest are recorded in the spec but the false-path
	// strip-out is not implemented in the generator yet — the example
	// defaults always land. We tell the user about the limitation in the
	// prompt descriptions AND in printWizardImplStatus() after the run.
	// WithFrontend strips correctly today.
	if err := huh.NewForm(huh.NewGroup(
		huh.NewConfirm().
			Title("Persist to MongoDB?").
			Description("[scaffold limitation] The generated module always includes the storage scaffold today. "+
				"Selecting No is recorded in the spec but the strip-out lands in a follow-up turn. "+
				"You can manually delete data/ and the storage block in module.yaml after generation.").
			Value(&spec.WithMongo),
		huh.NewConfirm().
			Title("Expose REST routes?").
			Description("[scaffold limitation] The generated module always includes the rest/ package today. "+
				"Selecting No is recorded in the spec but the strip-out lands in a follow-up turn. "+
				"You can manually delete rest/ and the route registrar after generation.").
			Value(&spec.WithRest),
		huh.NewConfirm().
			Title("Ship a Vue dashboard frontend?").
			Description("Adds frontend/ (Vue 3 + Nuxt module-sdk) and the module.yaml frontend block. "+
				"Backend-only modules pick No. [implemented]").
			Value(&spec.WithFrontend),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return nil, err
	}

	// ── Plugin? ───────────────────────────────────────────────────────────
	if err := huh.NewForm(huh.NewGroup(
		huh.NewConfirm().
			Title("Ship in-game plugin variants?").
			Description("Choose No for backend-only modules (REST + frontend only). " +
				"Choose Yes to ship plugins on Paper / Folia / Velocity / BungeeCord.").
			Value(&spec.WithPlugin),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return nil, err
	}

	if spec.WithPlugin {
		if err := promptPluginTargets(spec); err != nil {
			return nil, err
		}
	}

	// ── Capabilities ──────────────────────────────────────────────────────
	if err := promptCapabilities(spec); err != nil {
		return nil, err
	}

	return spec, nil
}

func promptPluginTargets(spec *scaffold.ModuleSpec) error {
	platforms := []string{}
	options := []huh.Option[string]{
		huh.NewOption("Paper (server) — covers Purpur, Pufferfish, Leaf, etc.", "paper").Selected(true),
		huh.NewOption("Folia (server)", "folia"),
		huh.NewOption("Spigot (server, vanilla Bukkit only)", "spigot"),
		huh.NewOption("Velocity (proxy)", "velocity"),
		huh.NewOption("BungeeCord (proxy) — also covers Waterfall", "bungeecord"),
		// Bedrock listed for UX completeness; generator support is Phase 3.1.
		huh.NewOption("Bedrock / Geyser (server)", "bedrock-geyser"),
	}
	if err := huh.NewForm(huh.NewGroup(
		huh.NewMultiSelect[string]().
			Title("Which platform-plugin targets?").
			Description("Pick one or more. Each becomes a plugin/<platform>/ subproject.").
			Options(options...).
			Value(&platforms).
			Validate(func(picked []string) error {
				if len(picked) == 0 {
					return fmt.Errorf("pick at least one — or go back and answer No to 'Ship plugin variants'")
				}
				return nil
			}),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return err
	}

	for _, platform := range platforms {
		target := scaffold.PluginTarget{Platform: platform, MultiVersionStrategy: "single"}
		if platform == "paper" || platform == "folia" {
			if err := promptMultiVersionStrategy(&target); err != nil {
				return err
			}
		}
		spec.PluginTargets = append(spec.PluginTargets, target)
	}
	return nil
}

func promptMultiVersionStrategy(target *scaffold.PluginTarget) error {
	strategy := "single"
	if err := huh.NewForm(huh.NewGroup(
		huh.NewSelect[string]().
			Title(fmt.Sprintf("%s — multi-version strategy", target.Platform)).
			Description("Choose how this target handles MC version drift.").
			Options(
				huh.NewOption("Single version (one subproject, no dispatch)", "single"),
				huh.NewOption("@ForVersion intra-jar dispatch (recommended for moderate drift)", "for-version"),
				huh.NewOption("JAR-split: one subproject per MC version (NMS / heavy drift) — Phase E", "jar-split"),
			).
			Value(&strategy),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return err
	}
	target.MultiVersionStrategy = strategy

	if strategy == "jar-split" {
		versions := []string{"1.20", "1.21"}
		if err := huh.NewForm(huh.NewGroup(
			huh.NewMultiSelect[string]().
				Title(fmt.Sprintf("%s — which MC versions?", target.Platform)).
				Description("Each version gets its own plugin/<platform>/v1_XX subproject. (JAR-split scaffolding lands in Phase E; selection is recorded.)").
				Options(
					huh.NewOption("1.18", "1.18"),
					huh.NewOption("1.19", "1.19"),
					huh.NewOption("1.20", "1.20").Selected(true),
					huh.NewOption("1.21", "1.21").Selected(true),
				).
				Value(&versions),
		)).WithTheme(tui.HuhTheme()).Run(); err != nil {
			return err
		}
		target.MCVersions = versions

		if target.Platform == "paper" {
			usePaperweight := false
			if err := huh.NewForm(huh.NewGroup(
				huh.NewConfirm().
					Title("Use paperweight-userdev (real NMS, Mojang mappings)?").
					Description("Pick Yes for genuinely binary-incompatible cross-version code. (Generator wiring lands in Phase E.)").
					Value(&usePaperweight),
			)).WithTheme(tui.HuhTheme()).Run(); err != nil {
				return err
			}
			target.UsePaperweight = usePaperweight
		}
	}
	return nil
}

func promptCapabilities(spec *scaffold.ModuleSpec) error {
	addProvides := false
	addRequires := false
	if err := huh.NewForm(huh.NewGroup(
		huh.NewConfirm().
			Title("Provide capabilities?").
			Description("Other modules can require these. Comma-separated id list, see capability registry docs.").
			Value(&addProvides),
		huh.NewConfirm().
			Title("Require capabilities?").
			Description("Lists capabilities this module needs from another module before it can start.").
			Value(&addRequires),
	)).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return err
	}

	if addProvides {
		var raw string
		if err := huh.NewForm(huh.NewGroup(
			huh.NewInput().
				Title("Provides — `id@version, id@version, ...`").
				Description(`Example: "tablist-active-renderer@1.0.0, my-leaderboard@1.0.0"`).
				Value(&raw),
		)).WithTheme(tui.HuhTheme()).Run(); err != nil {
			return err
		}
		for _, item := range splitAndTrim(raw, ",") {
			id, version, ok := strings.Cut(item, "@")
			if !ok {
				return fmt.Errorf("provides entry %q must be `id@version`", item)
			}
			spec.Provides = append(spec.Provides, scaffold.CapabilitySpec{
				ID: strings.TrimSpace(id), Version: strings.TrimSpace(version),
			})
		}
	}

	if addRequires {
		var raw string
		if err := huh.NewForm(huh.NewGroup(
			huh.NewInput().
				Title("Requires — `id@range, id@range, ...`").
				Description(`Example: "prexor.player.journey@[1.0,2.0)"`).
				Value(&raw),
		)).WithTheme(tui.HuhTheme()).Run(); err != nil {
			return err
		}
		for _, item := range splitAndTrim(raw, ",") {
			id, rng, ok := strings.Cut(item, "@")
			if !ok {
				return fmt.Errorf("requires entry %q must be `id@range`", item)
			}
			spec.Requires = append(spec.Requires, scaffold.CapabilityRequirement{
				ID: strings.TrimSpace(id), VersionRange: strings.TrimSpace(rng),
			})
		}
	}
	return nil
}

// validateKebab matches the regex used by scaffold.Generate so the wizard
// fails fast on bad names.
func validateKebab(name string) error {
	if name == "" {
		return fmt.Errorf("required")
	}
	if name[0] < 'a' || name[0] > 'z' {
		return fmt.Errorf("must start with [a-z]")
	}
	for _, r := range name {
		if !(r >= 'a' && r <= 'z') && !(r >= '0' && r <= '9') && r != '-' {
			return fmt.Errorf("only [a-z0-9-]")
		}
	}
	return nil
}

func splitAndTrim(s, sep string) []string {
	parts := strings.Split(s, sep)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// printSpecSummary writes a compact summary of what the wizard will generate.
// Used after the questions complete and before scaffold.Generate runs.
func printSpecSummary(spec *scaffold.ModuleSpec, repoRoot string) {
	fmt.Println()
	fmt.Println("→ wizard summary")
	fmt.Printf("  id:          %s\n", spec.Name)
	fmt.Printf("  storage:     %s\n", yesNo(spec.WithMongo))
	fmt.Printf("  rest:        %s\n", yesNo(spec.WithRest))
	fmt.Printf("  frontend:    %s\n", yesNo(spec.WithFrontend))
	fmt.Printf("  plugin:      %s\n", yesNo(spec.WithPlugin))
	if spec.WithPlugin {
		for _, t := range spec.PluginTargets {
			extra := t.MultiVersionStrategy
			if t.MultiVersionStrategy == "jar-split" {
				extra += " on " + strings.Join(t.MCVersions, ",")
				if t.UsePaperweight {
					extra += " (paperweight)"
				}
			}
			fmt.Printf("    - %s: %s\n", t.Platform, extra)
		}
	}
	if len(spec.Provides) > 0 {
		fmt.Printf("  provides:    %d capability(ies)\n", len(spec.Provides))
	}
	if len(spec.Requires) > 0 {
		fmt.Printf("  requires:    %d capability(ies)\n", len(spec.Requires))
	}
	fmt.Println()

	gaps := unsupportedToggleNotices(spec)
	if len(gaps) > 0 {
		fmt.Println("⚠  some choices fall back to template defaults until the generator catches up:")
		for _, g := range gaps {
			fmt.Println("   - " + g)
		}
		fmt.Println()
	}
}

// unsupportedToggleNotices returns user-facing strings describing wizard
// choices that the current generator can't honour (Phase B v1). Returning
// these instead of refusing the run keeps the wizard usable + transparent.
func unsupportedToggleNotices(spec *scaffold.ModuleSpec) []string {
	var notices []string
	if !spec.WithMongo {
		notices = append(notices, "storage=No: Mongo wiring stays in place; remove src/main/java/.../data/ by hand for now")
	}
	if !spec.WithRest {
		notices = append(notices, "rest=No: rest/ package stays in place; remove src/main/java/.../rest/ by hand for now")
	}
	for _, t := range spec.PluginTargets {
		switch t.Platform {
		case "bedrock-geyser":
			notices = append(notices, "bedrock-geyser: no template subdir yet — Phase 3.1 of the master plan")
		case "spigot", "bungeecord":
			notices = append(notices,
				t.Platform+": convention plugin exists (prexorcloud.plugin-"+t.Platform+
					"); the example template doesn't ship a subdir for this platform yet, "+
					"so the generator emits a stub build.gradle.kts referencing the convention plugin")
		}
		if t.MultiVersionStrategy == "jar-split" {
			notices = append(notices, t.Platform+"+jar-split: per-version subprojects ship in a follow-up generator pass")
		}
	}
	return notices
}

func yesNo(b bool) string {
	return strconv.FormatBool(b)
}
