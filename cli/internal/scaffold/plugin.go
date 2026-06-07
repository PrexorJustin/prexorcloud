// Plugin scaffolder for the standalone @CloudPlugin path (a.k.a. Path A in
// docs/API_OVERHAUL.md). Mirrors module new in shape — patches
// settings.gradle.kts, applies token replacement to a generated tree — but
// emits a single Gradle subproject under java/cloud-plugin/cloud-plugin-<name>
// containing one source file and one build script. No module.yaml, no
// frontend, no per-platform variants beyond the chosen one.

package scaffold

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	pluginSettingsAnchor = "// ---- PLUGINS ---- //"
	pluginDirPrefix      = "cloud-plugin-"
)

// PluginPlatform names the supported platform-plugin targets. Names match
// `-Acloud.platform=<name>` understood by CloudPluginProcessor and the
// `prexorcloud.plugin-<name>` build-logic convention plugin id.
type PluginPlatform string

const (
	PluginPaper      PluginPlatform = "paper"
	PluginSpigot     PluginPlatform = "spigot"
	PluginFolia      PluginPlatform = "folia"
	PluginVelocity   PluginPlatform = "velocity"
	PluginBungeeCord PluginPlatform = "bungeecord"
)

// PluginOptions drives a single plugin scaffold invocation.
type PluginOptions struct {
	RepoRoot    string
	Name        string         // kebab-case; without the cloud-plugin- prefix
	Package     string         // dotted; default = me.prexorjustin.prexorcloud.plugins.<camel>
	Platform    PluginPlatform // one of the constants above
	MCVersion   string         // "1.20" or "1.21" for paper; ignored on other platforms
	Description string
	Author      string
	Force       bool
	Dry         bool
}

// PluginResult reports what the scaffolder did.
type PluginResult struct {
	Kebab            string
	Pascal           string
	PackageDot       string
	PackageSlash     string
	DestDir          string
	Platform         PluginPlatform
	ConventionPlugin string
	FilesWritten     int
	IncludesAdded    int
	SettingsPatched  bool
}

// GeneratePlugin emits a Path-A plugin subproject under
// java/cloud-plugin/cloud-plugin-<name>/ and patches java/settings.gradle.kts
// after the `// ---- PLUGINS ---- //` anchor.
func GeneratePlugin(opts PluginOptions) (*PluginResult, error) {
	if !kebabPattern.MatchString(opts.Name) {
		return nil, fmt.Errorf("invalid name %q — use kebab-case: ^[a-z][a-z0-9-]*$", opts.Name)
	}
	if opts.RepoRoot == "" {
		return nil, fmt.Errorf("RepoRoot must be set")
	}
	convention, err := pluginConvention(opts.Platform, opts.MCVersion)
	if err != nil {
		return nil, err
	}

	kebab := opts.Name
	pascal := toPascal(kebab)
	camel := toCamel(kebab)
	pkgDot := opts.Package
	if pkgDot == "" {
		pkgDot = "me.prexorjustin.prexorcloud.plugins." + strings.ToLower(camel)
	}
	pkgSlash := strings.ReplaceAll(pkgDot, ".", "/")

	destRoot := filepath.Join(opts.RepoRoot, "java", "cloud-plugin", pluginDirPrefix+kebab)
	settingsFile := filepath.Join(opts.RepoRoot, "java", "settings.gradle.kts")

	if _, err := os.Stat(settingsFile); err != nil {
		return nil, fmt.Errorf("settings.gradle.kts not found at %s", settingsFile)
	}
	if st, err := os.Stat(destRoot); err == nil && st.IsDir() && !opts.Force {
		return nil, fmt.Errorf("destination exists: %s (pass --force to overwrite)", destRoot)
	}

	res := &PluginResult{
		Kebab:            kebab,
		Pascal:           pascal,
		PackageDot:       pkgDot,
		PackageSlash:     pkgSlash,
		DestDir:          destRoot,
		Platform:         opts.Platform,
		ConventionPlugin: convention,
	}

	files := renderPluginFiles(opts, pkgDot, pkgSlash, pascal, convention)
	for relPath, contents := range files {
		full := filepath.Join(destRoot, relPath)
		if !opts.Dry {
			if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
				return nil, err
			}
			if err := os.WriteFile(full, []byte(contents), 0o644); err != nil {
				return nil, err
			}
		}
		res.FilesWritten++
	}

	if err := patchPluginSettings(settingsFile, kebab, opts.Dry, res); err != nil {
		return nil, err
	}
	return res, nil
}

func pluginConvention(platform PluginPlatform, mcVersion string) (string, error) {
	switch platform {
	case PluginPaper:
		switch mcVersion {
		case "", "1.20":
			return "prexorcloud.plugin-paper", nil
		case "1.21":
			return "prexorcloud.plugin-paper-1-21", nil
		default:
			return "", fmt.Errorf("--mc-version=%s not supported for paper (use 1.20 or 1.21)", mcVersion)
		}
	case PluginSpigot:
		return "prexorcloud.plugin-spigot", nil
	case PluginFolia:
		return "prexorcloud.plugin-folia", nil
	case PluginVelocity:
		return "prexorcloud.plugin-velocity", nil
	case PluginBungeeCord:
		return "prexorcloud.plugin-bungeecord", nil
	default:
		return "", fmt.Errorf("unknown --platform=%q (paper|spigot|folia|velocity|bungeecord)", platform)
	}
}

// renderPluginFiles returns relative-path → content for every file the
// scaffolder writes. Single source of truth so tests can iterate without
// touching the filesystem.
func renderPluginFiles(opts PluginOptions, pkgDot, pkgSlash, pascal, convention string) map[string]string {
	desc := opts.Description
	if desc == "" {
		desc = pascal + " — standalone PrexorCloud plugin."
	}
	authors := opts.Author
	if authors == "" {
		authors = "PrexorCloud"
	}

	build := renderPluginBuild(convention, opts.Platform, opts.Name)
	src := renderPluginSource(opts.Platform, pkgDot, pascal, desc, authors)

	srcPath := filepath.Join("src", "main", "java", filepath.FromSlash(pkgSlash), pascal+"Plugin.java")
	return map[string]string{
		"build.gradle.kts":        build,
		filepath.ToSlash(srcPath): src,
	}
}

func renderPluginBuild(convention string, platform PluginPlatform, kebab string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "// Standalone @CloudPlugin (Path A) — generated by `prexorctl plugin new`.\n")
	fmt.Fprintf(&b, "// One Gradle subproject, one source file. Build with:\n")
	fmt.Fprintf(&b, "//   cd java && ./gradlew :cloud-plugin:cloud-plugin-%s:shadowJar\n", kebab)
	fmt.Fprintf(&b, "// The shaded jar lands in build/libs/ and drops straight into a server's plugins/ folder.\n\n")
	fmt.Fprintf(&b, "plugins {\n    id(%q)\n}\n", convention)

	if platform == PluginVelocity {
		// Mirrors the example module's velocity plugin: velocity-api ships an
		// annotation processor that fights ours. Disable it.
		b.WriteString("\n")
		b.WriteString("// velocity-api ships an annotation processor that competes with\n")
		b.WriteString("// CloudPluginProcessor (which already writes a complete velocity-plugin.json).\n")
		b.WriteString("// Exclude it to keep compilation one-pass.\n")
		b.WriteString("configurations.named(\"annotationProcessor\") {\n")
		b.WriteString("    exclude(group = \"com.velocitypowered\", module = \"velocity-api\")\n")
		b.WriteString("}\n")
	}
	return b.String()
}

func renderPluginSource(platform PluginPlatform, pkgDot, pascal, desc, author string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "package %s;\n\n", pkgDot)
	b.WriteString("import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;\n")
	b.WriteString("import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;\n")
	b.WriteString("import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;\n\n")

	descEscaped := jsonEscape(desc)
	authorEscaped := jsonEscape(author)
	fmt.Fprintf(&b, "@CloudPlugin(\n")
	fmt.Fprintf(&b, "        name = %q,\n", pascal)
	fmt.Fprintf(&b, "        version = \"0.0.1\",\n")
	fmt.Fprintf(&b, "        description = \"%s\",\n", descEscaped)
	fmt.Fprintf(&b, "        authors = {\"%s\"})\n", authorEscaped)
	fmt.Fprintf(&b, "public final class %sPlugin extends CloudPluginBase {\n\n", pascal)
	b.WriteString("    @Override\n")
	b.WriteString("    public void onEnable(CloudPluginContext ctx) {\n")
	b.WriteString("        ctx.logger().info(\"PrexorCloud connected on instance \" + ctx.self().instanceId());\n")
	b.WriteString("    }\n")
	b.WriteString("}\n")

	_ = platform // platform-specific imports/listeners are intentionally omitted from the v1 scaffold; the user adds them.
	return b.String()
}

func jsonEscape(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`)
	return r.Replace(s)
}

// patchPluginSettings inserts the new plugin's include() line after the
// `// ---- PLUGINS ---- //` anchor, refusing to invent the anchor if it's
// missing.
func patchPluginSettings(settingsFile, kebab string, dry bool, res *PluginResult) error {
	raw, err := os.ReadFile(settingsFile)
	if err != nil {
		return err
	}
	text := string(raw)
	if !strings.Contains(text, pluginSettingsAnchor) {
		return fmt.Errorf(
			"settings.gradle.kts is missing the anchor line %q. Add it under the include( block before re-running.",
			pluginSettingsAnchor)
	}

	include := fmt.Sprintf("    \"cloud-plugin:cloud-plugin-%s\",", kebab)
	if strings.Contains(text, strings.TrimSpace(include)) {
		return nil
	}

	idx := strings.Index(text, pluginSettingsAnchor)
	afterAnchor := strings.Index(text[idx:], "\n")
	if afterAnchor < 0 {
		return fmt.Errorf("settings.gradle.kts: anchor line is not newline-terminated")
	}
	insertAt := idx + afterAnchor + 1
	patched := text[:insertAt] + include + "\n" + text[insertAt:]
	if !dry {
		if err := os.WriteFile(settingsFile, []byte(patched), 0o644); err != nil {
			return err
		}
	}
	res.IncludesAdded = 1
	res.SettingsPatched = true
	return nil
}
