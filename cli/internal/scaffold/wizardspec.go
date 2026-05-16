package scaffold

// ModuleSpec is the single source of truth driving wizard-flavoured scaffold
// generation. The TUI in `prexorctl module new` (and the future --browser flow)
// fill this struct, then call ApplyToOptions to convert it to a scaffold
// invocation.
//
// Each field maps to one user-facing question. Adding a question = adding a
// field here + a prompt + (where applicable) a generation toggle.
type ModuleSpec struct {
	// Required identity
	Name    string // kebab-case, no `cloud-module-` prefix
	Package string // dotted, optional — empty = derive from Name

	// Storage & API surface
	WithMongo    bool // module.yaml `storage.mongo: true` and a backend repository
	WithRest     bool // generate the rest/ package and route registrar
	WithFrontend bool // generate the frontend/ Vue package

	// Plugins
	WithPlugin    bool           // master toggle: any platform plugin at all?
	PluginTargets []PluginTarget // platforms to ship — empty when WithPlugin=false

	// Capabilities — written into module.yaml
	Provides []CapabilitySpec
	Requires []CapabilityRequirement
}

// PluginTarget = one platform plugin variant the user wants to ship.
type PluginTarget struct {
	// Platform: paper, folia, velocity, bungeecord, bedrock-geyser
	Platform string

	// MultiVersionStrategy chooses how this target handles MC version drift.
	// Values:
	//   "single"     — one subproject, no version dispatch (current example/paper, /velocity)
	//   "for-version" — one subproject with @ForVersion nested adapters (current example/folia)
	//   "jar-split"  — one subproject per chosen MC version, separate JARs
	MultiVersionStrategy string

	// MCVersions is meaningful only for "jar-split" — list of MC version pins
	// e.g. ["1.20", "1.21"]. Each becomes its own subproject (plugin/<platform>/v1_XX/).
	MCVersions []string

	// UsePaperweight, only meaningful for `paper`+`jar-split`: scaffold each
	// subproject with the paperweight-userdev convention plugin (Phase E only —
	// currently always false from the wizard).
	UsePaperweight bool
}

// CapabilitySpec = a capability this module provides.
type CapabilitySpec struct {
	ID      string // e.g. "tablist-active-renderer"
	Version string // semver, e.g. "1.0.0"
}

// CapabilityRequirement = a capability this module needs from another.
type CapabilityRequirement struct {
	ID           string // e.g. "prexor.player.journey"
	VersionRange string // semver range, e.g. "[1.0,2.0)"
}

// PlatformsKept returns the unique set of platform names from PluginTargets,
// suitable for passing to scaffold.Options.Targets.
func (s ModuleSpec) PlatformsKept() []string {
	if !s.WithPlugin {
		return []string{}
	}
	seen := map[string]struct{}{}
	out := make([]string, 0, len(s.PluginTargets))
	for _, t := range s.PluginTargets {
		if _, dup := seen[t.Platform]; dup {
			continue
		}
		seen[t.Platform] = struct{}{}
		out = append(out, t.Platform)
	}
	return out
}

// ApplyToOptions converts a ModuleSpec into the scaffold.Options consumed by
// Generate. Spec-only fields (WithMongo, WithRest, WithFrontend, capabilities)
// flow through dedicated Options fields so the engine can post-process the
// emitted module.
func (s ModuleSpec) ApplyToOptions(repoRoot string, base Options) Options {
	base.RepoRoot = repoRoot
	base.Name = s.Name
	if s.Package != "" {
		base.Package = s.Package
	}
	base.Targets = s.PlatformsKept()
	base.WithMongo = s.WithMongo
	base.WithRest = s.WithRest
	base.WithFrontend = s.WithFrontend
	base.Provides = append([]CapabilitySpec(nil), s.Provides...)
	base.Requires = append([]CapabilityRequirement(nil), s.Requires...)
	return base
}
