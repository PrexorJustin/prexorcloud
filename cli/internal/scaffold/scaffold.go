// Package scaffold generates new cloud-module directories from the
// `example` template under java/cloud-modules/. Mirrors the behaviour of
// scripts/new-module.mjs so that `prexorctl module new` is a drop-in
// replacement.
package scaffold

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
)

// Template tokens — kept identical to scripts/new-module.mjs so the
// example module continues to work as the source of truth.
const (
	templateNameKebab    = "example-playtime"
	templateNamePascal   = "ExamplePlaytime"
	templateNameCamel    = "examplePlaytime"
	templatePackageDot   = "me.prexorjustin.prexorcloud.modules.example"
	templatePackageSlash = "me/prexorjustin/prexorcloud/modules/example"
	// templateModuleDir is the source directory under java/cloud-modules/ that
	// holds the reference module the scaffolder copies from.
	templateModuleDir = "example"
	// templateModulePrefix used to be "cloud-module-" when modules lived under
	// java/cloud-module/cloud-module-<kebab>/. Since the Track B repo hygiene
	// pass (commit 139d559) modules live at java/cloud-modules/<kebab>/ with
	// no prefix. Kept as an empty constant + accepted by LocateModule for
	// callers that still pass the legacy "cloud-module-<kebab>" form.
	templateModulePrefix       = ""
	templateModuleLegacyPrefix = "cloud-module-"
	settingsAnchor             = "// ---- MODULES ---- //"
)

var (
	kebabPattern = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	stepLine     = regexp.MustCompile(`^\s*//\s*STEP\b`)
	stepBlock    = regexp.MustCompile(`(?s)/\*\*.*?\*/`)
	multiBlank   = regexp.MustCompile(`\n{3,}`)
)

// Skipped from the template walk — never carried into the generated module.
var (
	ignoreDirs  = map[string]struct{}{"build": {}, "node_modules": {}, "dist": {}, ".gradle": {}, ".idea": {}}
	ignoreFiles = map[string]struct{}{".DS_Store": {}}
)

// Options drives a single scaffold invocation.
type Options struct {
	RepoRoot      string // absolute path to the repo root (contains java/settings.gradle.kts)
	Name          string // kebab-case module name
	Package       string // dotted package; defaults to me.prexorjustin.prexorcloud.modules.<name>
	StripComments bool
	Force         bool
	Dry           bool
	// Targets selects which plugin/<target> subprojects to copy. Recognised
	// values (subset of the example template): "paper", "folia", "velocity".
	// Empty (zero value) means copy all targets — preserves the prior
	// behaviour for callers that haven't been updated to pick.
	Targets []string

	// Wizard-driven toggles. The legacy CLI path leaves these at their
	// zero values, in which case the example template's defaults survive
	// (with mongo, with rest, with frontend, no extra capabilities).
	//
	// Implementation status (Phase B v1):
	//   WithFrontend(false) → frontend/ dir + module.yaml frontend block stripped
	//   WithRest(false)     → rest/ dir + onRegisterRoutes override + .rest. imports stripped
	//   Provides/Requires   → module.yaml capabilities block rewritten
	//   WithMongo(false) is accepted but still leaves the template defaults in
	//   place (the Mongo wiring is too interconnected for safe string surgery).
	WithMongo    bool
	WithRest     bool
	WithFrontend bool
	Provides     []CapabilitySpec
	Requires     []CapabilityRequirement
}

// wizardDefaults reports whether the caller filled the wizard-only fields.
// When all are at their zero/empty state, Generate skips the wizard
// post-processing and behaves exactly like the legacy template-copy path.
func (o Options) wizardDefaults() bool {
	return !o.WithMongo && !o.WithRest && !o.WithFrontend && len(o.Provides) == 0 && len(o.Requires) == 0
}

// AllTargets returns the targets the example template currently provides.
// Used as the default for --interactive prompts.
func AllTargets() []string { return []string{"paper", "folia", "velocity"} }

// Result reports what the scaffolder did. Useful for tests + CLI output.
type Result struct {
	Kebab           string
	Pascal          string
	Camel           string
	PackageDot      string
	PackageSlash    string
	DestDir         string // absolute path to the generated module directory
	FilesWritten    int
	IncludesAdded   int // number of include() lines added to settings.gradle.kts
	SettingsPatched bool
}

// Generate walks the template at <repo>/java/cloud-modules/example
// and emits a new module at <repo>/java/cloud-modules/<name>,
// applying token replacement to file contents and path segments. It also
// patches java/settings.gradle.kts immediately after the `// ---- MODULES ---- //`
// anchor with the new include() lines.
func Generate(opts Options) (*Result, error) {
	if !kebabPattern.MatchString(opts.Name) {
		return nil, fmt.Errorf("invalid name %q — use kebab-case: ^[a-z][a-z0-9-]*$", opts.Name)
	}
	if opts.RepoRoot == "" {
		return nil, fmt.Errorf("RepoRoot must be set")
	}

	kebab := opts.Name
	pascal := toPascal(kebab)
	camel := toCamel(kebab)
	pkgDot := opts.Package
	if pkgDot == "" {
		pkgDot = "me.prexorjustin.prexorcloud.modules." + strings.ToLower(camel)
	}
	pkgSlash := strings.ReplaceAll(pkgDot, ".", "/")

	templateRoot := filepath.Join(opts.RepoRoot, "java", "cloud-modules", templateModuleDir)
	destRoot := filepath.Join(opts.RepoRoot, "java", "cloud-modules", kebab)
	settingsFile := filepath.Join(opts.RepoRoot, "java", "settings.gradle.kts")

	if st, err := os.Stat(templateRoot); err != nil || !st.IsDir() {
		return nil, fmt.Errorf("template not found at %s", templateRoot)
	}
	if _, err := os.Stat(settingsFile); err != nil {
		return nil, fmt.Errorf("settings.gradle.kts not found at %s", settingsFile)
	}
	if st, err := os.Stat(destRoot); err == nil && st.IsDir() && !opts.Force {
		return nil, fmt.Errorf("destination exists: %s (pass --force to overwrite)", destRoot)
	}

	replace := makeReplacer(replacement{kebab, pascal, camel, pkgDot, pkgSlash})

	res := &Result{
		Kebab:        kebab,
		Pascal:       pascal,
		Camel:        camel,
		PackageDot:   pkgDot,
		PackageSlash: pkgSlash,
		DestDir:      destRoot,
	}

	if err := walkTemplate(templateRoot, destRoot, replace, opts, res); err != nil {
		return nil, err
	}
	if !opts.wizardDefaults() {
		if err := applyWizardOverrides(destRoot, opts); err != nil {
			return nil, err
		}
	}
	// Prune the module.yaml `extensions:` list to the selected --mc-plugin/Targets
	// platforms. walkTemplate already drops the unselected plugin/<target> dirs;
	// without this the manifest would still advertise every template platform.
	if err := filterManifestExtensions(destRoot, opts.Targets, opts.Dry); err != nil {
		return nil, err
	}
	if err := patchSettings(settingsFile, kebab, opts.Dry, opts.Targets, res); err != nil {
		return nil, err
	}
	return res, nil
}

// filterManifestExtensions rewrites the generated module.yaml so its top-level
// `extensions:` list only contains the selected targets. No-op when the caller
// didn't pick targets (keep == nil), on dry runs, or when the template carries
// no manifest.
func filterManifestExtensions(destRoot string, targets []string, dry bool) error {
	if dry {
		return nil
	}
	keep := normaliseTargets(targets)
	if keep == nil {
		return nil
	}
	manifestPath := filepath.Join(destRoot, "src", "main", "module", "module.yaml")
	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // template without a manifest — nothing to prune
		}
		return fmt.Errorf("read generated module.yaml: %w", err)
	}
	pruned := pruneExtensionsBlock(string(raw), keep)
	if pruned == string(raw) {
		return nil
	}
	if err := os.WriteFile(manifestPath, []byte(pruned), 0o644); err != nil {
		return fmt.Errorf("write generated module.yaml: %w", err)
	}
	return nil
}

// pruneExtensionsBlock filters the top-level `extensions:` list down to entries
// whose `target:` last path segment (e.g. "paper" from "server/paper") is in
// keep. Entries with no recognisable target are left in place (defensive).
// Returns the text unchanged when there is no extensions block.
func pruneExtensionsBlock(text string, keep map[string]bool) string {
	if keep == nil {
		return text
	}
	lines := strings.Split(text, "\n")
	out := make([]string, 0, len(lines))
	i := 0
	for i < len(lines) {
		if lines[i] != "extensions:" {
			out = append(out, lines[i])
			i++
			continue
		}
		out = append(out, lines[i]) // the "extensions:" header
		i++
		// The block body runs until the next top-level (unindented) key or EOF.
		start := i
		for i < len(lines) {
			l := lines[i]
			if l != "" && !strings.HasPrefix(l, " ") && !strings.HasPrefix(l, "\t") {
				break
			}
			i++
		}
		out = append(out, pruneExtensionItems(lines[start:i], keep)...)
	}
	return strings.Join(out, "\n")
}

// pruneExtensionItems splits a block body into YAML list items (each starting at
// a "  - " line) and keeps only the selected ones, preserving any leading lines
// and a single file-final blank line.
func pruneExtensionItems(body []string, keep map[string]bool) []string {
	trailingBlank := len(body) > 0 && strings.TrimSpace(body[len(body)-1]) == ""
	end := len(body)
	for end > 0 && strings.TrimSpace(body[end-1]) == "" {
		end--
	}

	var prefix []string
	var items [][]string
	var cur []string
	for _, l := range body[:end] {
		if strings.HasPrefix(l, "  - ") {
			if cur != nil {
				items = append(items, cur)
			}
			cur = []string{l}
		} else if cur != nil {
			cur = append(cur, l)
		} else {
			prefix = append(prefix, l)
		}
	}
	if cur != nil {
		items = append(items, cur)
	}

	out := append([]string{}, prefix...)
	for _, item := range items {
		if keepExtensionItem(item, keep) {
			out = append(out, item...)
		}
	}
	if trailingBlank {
		out = append(out, "")
	}
	return out
}

func keepExtensionItem(item []string, keep map[string]bool) bool {
	for _, l := range item {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "target:") {
			target := strings.TrimSpace(strings.TrimPrefix(t, "target:"))
			short := target
			if idx := strings.LastIndex(target, "/"); idx >= 0 {
				short = target[idx+1:]
			}
			return keep[short]
		}
	}
	return true // no target line — keep defensively
}

// applyWizardOverrides post-processes the freshly-written module so it matches
// the user's wizard answers. The walkTemplate pass produces the example as-is
// (minus unselected plugin targets); this pass strips/edits files based on the
// wizard's higher-level toggles.
//
// Implemented overrides:
//   - WithFrontend == false:     delete frontend/ and the module.yaml `frontend:` block
//   - WithRest == false:         delete rest/ and drop the onRegisterRoutes override + imports
//   - Provides / Requires        rewrite module.yaml capabilities: block from wizard input
//
// Deferred:
//   - WithMongo == false:        rip out data/, repository, requireMongoStorage(), module.yaml storage block
//     (the example's repository → queryService → capability/health chain is
//     too interconnected for safe string surgery — needs a template restructure)
func applyWizardOverrides(destRoot string, opts Options) error {
	if opts.Dry {
		// Dry runs don't touch the filesystem and the template wasn't actually
		// written, so there's nothing to edit. Skip silently.
		return nil
	}
	manifestPath := filepath.Join(destRoot, "src", "main", "module", "module.yaml")
	manifest, err := os.ReadFile(manifestPath)
	if err != nil {
		return fmt.Errorf("read generated module.yaml: %w", err)
	}
	text := string(manifest)

	if !opts.WithFrontend {
		frontendDir := filepath.Join(destRoot, "frontend")
		if err := os.RemoveAll(frontendDir); err != nil {
			return fmt.Errorf("remove frontend/: %w", err)
		}
		text = stripManifestBlock(text, "frontend:")
	}

	if !opts.WithRest {
		if err := stripRest(destRoot); err != nil {
			return fmt.Errorf("strip rest/: %w", err)
		}
	}

	if len(opts.Provides) > 0 || len(opts.Requires) > 0 {
		text = rewriteCapabilities(text, opts.Provides, opts.Requires)
	}

	if err := os.WriteFile(manifestPath, []byte(text), 0o644); err != nil {
		return fmt.Errorf("write generated module.yaml: %w", err)
	}
	return nil
}

// stripRest removes REST scaffolding from a freshly-generated module: the
// rest/ package(s) and, in the entrypoint, the `onRegisterRoutes` override (a
// no-op default on PlatformModule, so dropping it is safe) plus the now-dead
// `.rest.` imports. Mongo/capabilities/health wiring is untouched.
func stripRest(destRoot string) error {
	// Cover both src/main and src/test — the template ships a rest/ package and a
	// rest/PlaytimeRoutesTest, both of which must go or compileTestJava breaks.
	srcRoot := filepath.Join(destRoot, "src")

	// 1. Delete every rest/ package under the source tree.
	_ = filepath.WalkDir(srcRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && d.Name() == "rest" {
			_ = os.RemoveAll(path)
			return filepath.SkipDir
		}
		return nil
	})

	// 2. Strip the onRegisterRoutes override + rest imports from the entrypoint.
	return filepath.WalkDir(srcRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".java") {
			return err
		}
		raw, readErr := os.ReadFile(path)
		if readErr != nil {
			return readErr
		}
		src := string(raw)
		if !strings.Contains(src, "onRegisterRoutes(RouteRegistrar") {
			return nil
		}
		return os.WriteFile(path, []byte(stripOnRegisterRoutes(src)), 0o644)
	})
}

// stripOnRegisterRoutes removes `import ... .rest. ...` lines and the
// `onRegisterRoutes(RouteRegistrar ...)` method (including a preceding
// `@Override`) from a Java source string. Brace-matched; assumes the simple
// generated-template shape (no braces inside strings/comments in the method).
func stripOnRegisterRoutes(src string) string {
	// Drop rest imports.
	lines := strings.Split(src, "\n")
	kept := make([]string, 0, len(lines))
	for _, l := range lines {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "import ") && strings.Contains(t, ".rest.") {
			continue
		}
		kept = append(kept, l)
	}
	src = strings.Join(kept, "\n")

	idx := strings.Index(src, "public void onRegisterRoutes(")
	if idx < 0 {
		return src
	}
	start := strings.LastIndex(src[:idx], "\n") + 1 // start of the signature line
	if prevEnd := start - 1; prevEnd > 0 {
		prevStart := strings.LastIndex(src[:prevEnd], "\n") + 1
		if strings.TrimSpace(src[prevStart:prevEnd]) == "@Override" {
			start = prevStart
		}
	}
	braceOpen := strings.IndexByte(src[idx:], '{')
	if braceOpen < 0 {
		return src
	}
	braceOpen += idx
	depth := 0
	end := -1
	for i := braceOpen; i < len(src); i++ {
		if src[i] == '{' {
			depth++
		} else if src[i] == '}' {
			depth--
			if depth == 0 {
				end = i + 1
				break
			}
		}
	}
	if end < 0 {
		return src
	}
	result := src[:start] + src[end:]
	return multiBlank.ReplaceAllString(result, "\n\n")
}

// stripManifestBlock removes a top-level YAML block whose key matches `key`
// (e.g. "frontend:"). The block runs from the key line through every
// subsequent indented line; the following top-level key terminates it.
func stripManifestBlock(text, key string) string {
	lines := strings.Split(text, "\n")
	out := make([]string, 0, len(lines))
	skipping := false
	for _, line := range lines {
		if !skipping && strings.HasPrefix(line, key) {
			skipping = true
			continue
		}
		if skipping {
			if line == "" || strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t") {
				continue // still inside the block (indented or blank)
			}
			skipping = false
		}
		out = append(out, line)
	}
	return strings.Join(out, "\n")
}

// rewriteCapabilities replaces the capabilities: block in module.yaml with
// one composed from the wizard's provides/requires inputs. If no capabilities
// block exists, appends one at the end.
func rewriteCapabilities(text string, provides []CapabilitySpec, requires []CapabilityRequirement) string {
	stripped := stripManifestBlock(text, "capabilities:")
	if !strings.HasSuffix(stripped, "\n") {
		stripped += "\n"
	}
	var b strings.Builder
	b.WriteString(stripped)
	b.WriteString("capabilities:\n")
	if len(provides) > 0 {
		b.WriteString("  provides:\n")
		for _, p := range provides {
			fmt.Fprintf(&b, "    - id: %s\n      version: %s\n", p.ID, p.Version)
		}
	}
	if len(requires) > 0 {
		b.WriteString("  requires:\n")
		for _, r := range requires {
			fmt.Fprintf(&b, "    - id: %s\n      versionRange: %q\n", r.ID, r.VersionRange)
		}
	}
	return b.String()
}

// normaliseTargets returns nil when caller didn't pick (preserve all-targets
// behaviour) or a set of accepted target names.
func normaliseTargets(targets []string) map[string]bool {
	if len(targets) == 0 {
		return nil
	}
	out := make(map[string]bool, len(targets))
	for _, t := range targets {
		out[strings.ToLower(strings.TrimSpace(t))] = true
	}
	return out
}

// isUnselectedPluginDir reports whether relPath points into a plugin/<target>
// subtree the caller did not pick. The example template has plugin/folia,
// plugin/paper, and plugin/velocity directly under the module root.
func isUnselectedPluginDir(relPath string, keep map[string]bool) bool {
	const pluginPrefix = "plugin/"
	if !strings.HasPrefix(relPath, pluginPrefix) {
		return false
	}
	rest := relPath[len(pluginPrefix):]
	target := rest
	if i := strings.Index(rest, "/"); i >= 0 {
		target = rest[:i]
	}
	if target == "" {
		return false
	}
	return !keep[target]
}

type replacement struct {
	kebab, pascal, camel, pkgDot, pkgSlash string
}

// makeReplacer returns a function that applies all five token replacements
// in deterministic order. Order matters: pkgSlash is replaced before kebab/
// camel/pascal because the slash form would otherwise be partially clobbered.
func makeReplacer(r replacement) func(string) string {
	return func(in string) string {
		out := in
		out = strings.ReplaceAll(out, templatePackageSlash, r.pkgSlash)
		out = strings.ReplaceAll(out, templatePackageDot, r.pkgDot)
		out = strings.ReplaceAll(out, templateNameKebab, r.kebab)
		out = strings.ReplaceAll(out, templateNamePascal, r.pascal)
		out = strings.ReplaceAll(out, templateNameCamel, r.camel)
		return out
	}
}

func walkTemplate(templateRoot, destRoot string, replace func(string) string, opts Options, res *Result) error {
	keepTargets := normaliseTargets(opts.Targets)
	return filepath.WalkDir(templateRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == templateRoot {
			return nil
		}
		rel, err := filepath.Rel(templateRoot, path)
		if err != nil {
			return err
		}
		// filepath.Rel uses the OS separator; normalise to forward slash so
		// token replacement against the slash-form package matches.
		relPosix := filepath.ToSlash(rel)

		if d.IsDir() {
			if _, skip := ignoreDirs[d.Name()]; skip {
				return filepath.SkipDir
			}
			if keepTargets != nil && isUnselectedPluginDir(relPosix, keepTargets) {
				return filepath.SkipDir
			}
			outDir := filepath.Join(destRoot, filepath.FromSlash(replace(relPosix)))
			if !opts.Dry {
				if err := os.MkdirAll(outDir, 0o755); err != nil {
					return err
				}
			}
			return nil
		}
		if _, skip := ignoreFiles[d.Name()]; skip {
			return nil
		}

		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		outPath := filepath.Join(destRoot, filepath.FromSlash(replace(relPosix)))
		var out []byte
		if isBinary(raw) {
			out = raw
		} else {
			text := replace(string(raw))
			if opts.StripComments {
				text = stripTeachingComments(text)
			}
			out = []byte(text)
		}
		if !opts.Dry {
			if err := os.MkdirAll(filepath.Dir(outPath), 0o755); err != nil {
				return err
			}
			if err := os.WriteFile(outPath, out, 0o644); err != nil {
				return err
			}
		}
		res.FilesWritten++
		return nil
	})
}

func patchSettings(settingsFile, kebab string, dry bool, targets []string, res *Result) error {
	raw, err := os.ReadFile(settingsFile)
	if err != nil {
		return err
	}
	text := string(raw)
	if !strings.Contains(text, settingsAnchor) {
		return fmt.Errorf(
			"settings.gradle.kts is missing the anchor line %q. Add it under the include( block before re-running — this scaffolder refuses to invent it.",
			settingsAnchor)
	}
	includes := []string{
		fmt.Sprintf("    \"cloud-modules:%s\",", kebab),
	}
	keep := normaliseTargets(targets)
	for _, t := range []string{"paper", "folia", "velocity"} {
		if keep != nil && !keep[t] {
			continue
		}
		includes = append(includes,
			fmt.Sprintf("    \"cloud-modules:%s:plugin:%s\",", kebab, t))
	}
	allPresent := true
	for _, line := range includes {
		if !strings.Contains(text, strings.TrimSpace(line)) {
			allPresent = false
			break
		}
	}
	if allPresent {
		return nil
	}
	idx := strings.Index(text, settingsAnchor)
	afterAnchor := strings.Index(text[idx:], "\n")
	if afterAnchor < 0 {
		return fmt.Errorf("settings.gradle.kts: anchor line is not newline-terminated")
	}
	insertAt := idx + afterAnchor + 1
	patched := text[:insertAt] + strings.Join(includes, "\n") + "\n" + text[insertAt:]
	if !dry {
		if err := os.WriteFile(settingsFile, []byte(patched), 0o644); err != nil {
			return err
		}
	}
	res.IncludesAdded = len(includes)
	res.SettingsPatched = true
	return nil
}

// FindRepoRoot walks up from start looking for java/settings.gradle.kts. The
// scaffolder requires a repo checkout; this saves users from passing --repo-root.
func FindRepoRoot(start string) (string, error) {
	dir, err := filepath.Abs(start)
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "java", "settings.gradle.kts")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("could not locate java/settings.gradle.kts upwards from %s", start)
		}
		dir = parent
	}
}

// toPascal converts kebab-case (or snake_case) to PascalCase.
func toPascal(kebab string) string {
	parts := strings.FieldsFunc(kebab, func(r rune) bool { return r == '-' || r == '_' })
	var b strings.Builder
	for _, p := range parts {
		if p == "" {
			continue
		}
		runes := []rune(strings.ToLower(p))
		runes[0] = unicode.ToUpper(runes[0])
		b.WriteString(string(runes))
	}
	return b.String()
}

func toCamel(kebab string) string {
	p := toPascal(kebab)
	if p == "" {
		return ""
	}
	runes := []rune(p)
	runes[0] = unicode.ToLower(runes[0])
	return string(runes)
}

// isBinary returns true if the file contains a NUL byte. Matches the JS
// scaffolder's heuristic; the example template has no binaries today.
func isBinary(b []byte) bool {
	for _, c := range b {
		if c == 0 {
			return true
		}
	}
	return false
}

// stripTeachingComments removes `// STEP ...` line comments and `/** STEP ... */`
// block comments from generated source files. Used by `--strip-comments`.
func stripTeachingComments(source string) string {
	lines := strings.Split(source, "\n")
	kept := make([]string, 0, len(lines))
	for _, line := range lines {
		if stepLine.MatchString(line) {
			continue
		}
		kept = append(kept, line)
	}
	out := strings.Join(kept, "\n")

	out = stepBlock.ReplaceAllStringFunc(out, func(block string) string {
		for _, raw := range strings.Split(block, "\n") {
			trimmed := strings.TrimSpace(raw)
			trimmed = strings.TrimPrefix(trimmed, "/**")
			trimmed = strings.TrimPrefix(trimmed, "*/")
			trimmed = strings.TrimPrefix(trimmed, "*")
			trimmed = strings.TrimSpace(trimmed)
			if trimmed == "" {
				continue
			}
			if strings.HasPrefix(trimmed, "STEP") {
				return ""
			}
			return block
		}
		return block
	})
	out = multiBlank.ReplaceAllString(out, "\n\n")
	return out
}
