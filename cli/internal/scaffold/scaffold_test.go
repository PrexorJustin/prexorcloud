package scaffold

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeTemplate writes a minimal cloud-module-example layout under root and
// returns the repo root that scaffold.Generate will accept.
func fakeTemplate(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	mustMkdir(t, filepath.Join(root, "java", "cloud-modules", "example",
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "example"))
	mustMkdir(t, filepath.Join(root, "java", "cloud-modules", "example", "build"))                    // ignored
	mustMkdir(t, filepath.Join(root, "java", "cloud-modules", "example", "frontend", "node_modules")) // ignored

	// Plugin subprojects — one tiny file per target so we can assert which
	// of them survive a selective scaffold.
	for _, target := range []string{"paper", "folia", "velocity", "bedrock-geyser"} {
		mustWrite(t,
			filepath.Join(root, "java", "cloud-modules", "example",
				"plugin", target, "build.gradle.kts"),
			`plugins { id("prexorcloud.java21-api") }
`)
	}

	mustWrite(t, filepath.Join(root, "java", "cloud-modules", "example", "build.gradle.kts"),
		`plugins { id("prexorcloud.java21-api") }
// module: example-playtime
prexorcloudModule {
    extensionArtifacts.set(
        mapOf(
            "extensions/server/folia/example-playtime-folia.jar" to ":cloud-modules:example:plugin:folia",
            "extensions/proxy/velocity/example-playtime-velocity.jar" to ":cloud-modules:example:plugin:velocity",
            "extensions/server/paper/example-playtime-paper.jar" to ":cloud-modules:example:plugin:paper",
            "extensions/server/bedrock-geyser/example-playtime-bedrock-geyser.jar"
                to ":cloud-modules:example:plugin:bedrock-geyser",
        ),
    )
}
`)
	mustWrite(t, filepath.Join(root, "java", "cloud-modules", "example",
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "example",
		"ExamplePlaytimeModule.java"),
		`package me.prexorjustin.prexorcloud.modules.example;
// STEP 1: implement
public class ExamplePlaytimeModule {
    static String name() { return "examplePlaytime"; }
}
`)
	mustWrite(t, filepath.Join(root, "java", "cloud-modules", "example", "build", "should-be-ignored.txt"),
		`if you see this in the output the build/ filter is broken
`)

	// Minimal module.yaml carrying an extensions list with all template
	// platforms, so selective scaffolds can be asserted to prune it.
	mustWrite(t, filepath.Join(root, "java", "cloud-modules", "example", "src", "main", "module", "module.yaml"),
		`manifestVersion: 1
id: example-playtime
extensions:
  - id: example-playtime-folia
    target: server/folia
    activation: explicit-group-attach
  - id: example-playtime-paper
    target: server/paper
    activation: explicit-group-attach
  - id: example-playtime-velocity
    target: proxy/velocity
    activation: explicit-group-attach
  - id: example-playtime-bedrock-geyser
    target: server/bedrock-geyser
    activation: explicit-group-attach
`)

	mustWrite(t, filepath.Join(root, "java", "settings.gradle.kts"),
		`rootProject.name = "prexorcloud"
include(
    "cloud-controller",
    // ---- MODULES ---- //
    "cloud-modules:example",
)
`)
	return root
}

func mustMkdir(t *testing.T, p string) {
	t.Helper()
	if err := os.MkdirAll(p, 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", p, err)
	}
}

func mustWrite(t *testing.T, p, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(p), err)
	}
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatalf("write %s: %v", p, err)
	}
}

func TestGenerateBasic(t *testing.T) {
	root := fakeTemplate(t)
	res, err := Generate(Options{RepoRoot: root, Name: "stats-aggregator"})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if res.Pascal != "StatsAggregator" || res.Camel != "statsAggregator" {
		t.Fatalf("naming wrong: %+v", res)
	}
	if res.PackageDot != "me.prexorjustin.prexorcloud.modules.statsaggregator" {
		t.Fatalf("default package wrong: %s", res.PackageDot)
	}

	dest := filepath.Join(root, "java", "cloud-modules", "stats-aggregator")
	got := readFile(t, filepath.Join(dest,
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "statsaggregator",
		"StatsAggregatorModule.java"))
	if !strings.Contains(got, "package me.prexorjustin.prexorcloud.modules.statsaggregator;") {
		t.Errorf("package not rewritten:\n%s", got)
	}
	if !strings.Contains(got, "public class StatsAggregatorModule") {
		t.Errorf("Pascal token not rewritten:\n%s", got)
	}
	if !strings.Contains(got, `"statsAggregator"`) {
		t.Errorf("camel token not rewritten:\n%s", got)
	}
	if strings.Contains(got, "ExamplePlaytime") || strings.Contains(got, "examplePlaytime") {
		t.Errorf("template tokens leaked:\n%s", got)
	}
	if strings.Contains(got, "// STEP 1") == false {
		t.Errorf("expected teaching comment to remain without --strip-comments:\n%s", got)
	}

	build := readFile(t, filepath.Join(dest, "build.gradle.kts"))
	if !strings.Contains(build, "// module: stats-aggregator") {
		t.Errorf("kebab token not rewritten in build.gradle.kts:\n%s", build)
	}

	if _, err := os.Stat(filepath.Join(dest, "build")); err == nil {
		t.Errorf("build/ should be ignored")
	}
	if _, err := os.Stat(filepath.Join(dest, "frontend", "node_modules")); err == nil {
		t.Errorf("node_modules/ should be ignored")
	}

	settings := readFile(t, filepath.Join(root, "java", "settings.gradle.kts"))
	for _, expected := range []string{
		`"cloud-modules:stats-aggregator",`,
		`"cloud-modules:stats-aggregator:plugin:paper",`,
		`"cloud-modules:stats-aggregator:plugin:folia",`,
		`"cloud-modules:stats-aggregator:plugin:velocity",`,
		`"cloud-modules:stats-aggregator:plugin:bedrock-geyser",`,
	} {
		if !strings.Contains(settings, expected) {
			t.Errorf("settings missing %s:\n%s", expected, settings)
		}
	}
	if !res.SettingsPatched || res.IncludesAdded != 5 {
		t.Errorf("settings result wrong: %+v", res)
	}

	// Idempotent re-run: with --force, settings should not be patched twice.
	if _, err := Generate(Options{RepoRoot: root, Name: "stats-aggregator", Force: true}); err != nil {
		t.Fatalf("rerun: %v", err)
	}
	settings2 := readFile(t, filepath.Join(root, "java", "settings.gradle.kts"))
	if strings.Count(settings2, `"cloud-modules:stats-aggregator",`) != 1 {
		t.Errorf("settings re-patched on rerun:\n%s", settings2)
	}
}

func TestGenerateRefusesExisting(t *testing.T) {
	root := fakeTemplate(t)
	if _, err := Generate(Options{RepoRoot: root, Name: "mod-one"}); err != nil {
		t.Fatalf("first: %v", err)
	}
	_, err := Generate(Options{RepoRoot: root, Name: "mod-one"})
	if err == nil || !strings.Contains(err.Error(), "destination exists") {
		t.Fatalf("expected destination-exists error, got %v", err)
	}
}

func TestGenerateRejectsBadName(t *testing.T) {
	root := fakeTemplate(t)
	_, err := Generate(Options{RepoRoot: root, Name: "Bad_Name"})
	if err == nil || !strings.Contains(err.Error(), "kebab-case") {
		t.Fatalf("expected kebab-case error, got %v", err)
	}
}

func TestGenerateRejectsMissingAnchor(t *testing.T) {
	root := fakeTemplate(t)
	settings := filepath.Join(root, "java", "settings.gradle.kts")
	body, _ := os.ReadFile(settings)
	cleaned := strings.ReplaceAll(string(body), "// ---- MODULES ---- //\n", "")
	_ = os.WriteFile(settings, []byte(cleaned), 0o644)
	_, err := Generate(Options{RepoRoot: root, Name: "mod-anchored"})
	if err == nil || !strings.Contains(err.Error(), "anchor") {
		t.Fatalf("expected anchor error, got %v", err)
	}
}

func TestGenerateDryRun(t *testing.T) {
	root := fakeTemplate(t)
	res, err := Generate(Options{RepoRoot: root, Name: "dry-run-mod", Dry: true})
	if err != nil {
		t.Fatalf("dry: %v", err)
	}
	if res.FilesWritten == 0 {
		t.Fatalf("dry run still reports 0 files — counter should run")
	}
	if _, err := os.Stat(filepath.Join(root, "java", "cloud-modules", "dry-run-mod")); err == nil {
		t.Errorf("dry run should not have created the module dir")
	}
	settings := readFile(t, filepath.Join(root, "java", "settings.gradle.kts"))
	if strings.Contains(settings, "cloud-module-dry-run-mod") {
		t.Errorf("dry run should not have patched settings:\n%s", settings)
	}
}

func TestStripComments(t *testing.T) {
	root := fakeTemplate(t)
	if _, err := Generate(Options{RepoRoot: root, Name: "no-teaching", StripComments: true}); err != nil {
		t.Fatalf("strip: %v", err)
	}
	dest := filepath.Join(root, "java", "cloud-modules", "no-teaching")
	got := readFile(t, filepath.Join(dest,
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "noteaching",
		"NoTeachingModule.java"))
	if strings.Contains(got, "STEP 1") {
		t.Errorf("teaching comment leaked through --strip-comments:\n%s", got)
	}
}

func TestCustomPackage(t *testing.T) {
	root := fakeTemplate(t)
	res, err := Generate(Options{
		RepoRoot: root,
		Name:     "stats-aggregator",
		Package:  "io.example.cloudmod.stats",
	})
	if err != nil {
		t.Fatalf("custom pkg: %v", err)
	}
	if res.PackageDot != "io.example.cloudmod.stats" || res.PackageSlash != "io/example/cloudmod/stats" {
		t.Fatalf("custom package not honoured: %+v", res)
	}
	dest := filepath.Join(root, "java", "cloud-modules", "stats-aggregator")
	if _, err := os.Stat(filepath.Join(dest,
		"src", "main", "java", "io", "example", "cloudmod", "stats", "StatsAggregatorModule.java")); err != nil {
		t.Errorf("expected file at custom package path: %v", err)
	}
}

func TestFindRepoRoot(t *testing.T) {
	root := fakeTemplate(t)
	deep := filepath.Join(root, "java", "cloud-modules", "example", "src", "main")
	got, err := FindRepoRoot(deep)
	if err != nil {
		t.Fatalf("FindRepoRoot: %v", err)
	}
	gotResolved, _ := filepath.EvalSymlinks(got)
	rootResolved, _ := filepath.EvalSymlinks(root)
	if gotResolved != rootResolved {
		t.Errorf("expected %s, got %s", rootResolved, gotResolved)
	}

	nope := t.TempDir()
	if _, err := FindRepoRoot(nope); err == nil {
		t.Errorf("expected error walking up from %s", nope)
	}
}

func TestGenerateSelectiveTargets(t *testing.T) {
	root := fakeTemplate(t)
	res, err := Generate(Options{
		RepoRoot: root,
		Name:     "skinny-mod",
		Targets:  []string{"paper", "folia"}, // velocity intentionally dropped
	})
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}

	dest := filepath.Join(root, "java", "cloud-modules", "skinny-mod")
	for _, kept := range []string{"paper", "folia"} {
		path := filepath.Join(dest, "plugin", kept, "build.gradle.kts")
		if _, err := os.Stat(path); err != nil {
			t.Errorf("expected plugin/%s to be generated, got %v", kept, err)
		}
	}
	if _, err := os.Stat(filepath.Join(dest, "plugin", "velocity")); err == nil {
		t.Errorf("velocity subdir should not have been generated")
	}

	// settings.gradle.kts must NOT include the velocity subproject.
	settings, _ := os.ReadFile(filepath.Join(root, "java", "settings.gradle.kts"))
	if strings.Contains(string(settings), `cloud-modules:skinny-mod:plugin:velocity`) {
		t.Errorf("settings.gradle.kts should not include the velocity subproject")
	}
	for _, kept := range []string{"paper", "folia"} {
		want := `cloud-modules:skinny-mod:plugin:` + kept
		if !strings.Contains(string(settings), want) {
			t.Errorf("settings.gradle.kts missing include %q", want)
		}
	}
	if !res.SettingsPatched {
		t.Error("expected settings.gradle.kts to be patched")
	}

	// The generated module.yaml extensions: list must only advertise the
	// selected platforms — paper + folia, never velocity or bedrock-geyser.
	manifest := readFile(t, filepath.Join(dest, "src", "main", "module", "module.yaml"))
	for _, want := range []string{"target: server/paper", "target: server/folia"} {
		if !strings.Contains(manifest, want) {
			t.Errorf("module.yaml should keep %q:\n%s", want, manifest)
		}
	}
	for _, unwanted := range []string{"target: proxy/velocity", "target: server/bedrock-geyser"} {
		if strings.Contains(manifest, unwanted) {
			t.Errorf("module.yaml should have pruned %q:\n%s", unwanted, manifest)
		}
	}

	// build.gradle.kts extensionArtifacts must be pruned in lockstep with the
	// manifest — keep paper + folia, drop velocity + bedrock-geyser.
	build := readFile(t, filepath.Join(dest, "build.gradle.kts"))
	for _, want := range []string{":plugin:paper", ":plugin:folia"} {
		if !strings.Contains(build, want) {
			t.Errorf("build.gradle.kts should keep %q:\n%s", want, build)
		}
	}
	for _, gone := range []string{":plugin:velocity", ":plugin:bedrock-geyser"} {
		if strings.Contains(build, gone) {
			t.Errorf("build.gradle.kts should have pruned %q:\n%s", gone, build)
		}
	}
}

func TestGenerateAllTargetsByDefault(t *testing.T) {
	root := fakeTemplate(t)
	if _, err := Generate(Options{RepoRoot: root, Name: "fat-mod"}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	dest := filepath.Join(root, "java", "cloud-modules", "fat-mod")
	for _, target := range []string{"paper", "folia", "velocity", "bedrock-geyser"} {
		if _, err := os.Stat(filepath.Join(dest, "plugin", target, "build.gradle.kts")); err != nil {
			t.Errorf("plugin/%s missing: %v", target, err)
		}
	}
	// No target selection → the extensions list is left intact (all platforms).
	manifest := readFile(t, filepath.Join(dest, "src", "main", "module", "module.yaml"))
	for _, want := range []string{"server/paper", "server/folia", "proxy/velocity", "server/bedrock-geyser"} {
		if !strings.Contains(manifest, "target: "+want) {
			t.Errorf("default scaffold should keep all extensions, missing %q:\n%s", want, manifest)
		}
	}
}

func TestStripOnRegisterRoutes(t *testing.T) {
	src := `package me.prexorjustin.prexorcloud.modules.demo.platform;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.demo.config.Config;
import me.prexorjustin.prexorcloud.modules.demo.rest.PlaytimeRoutes;

public final class DemoModule implements PlatformModule {

    @Override
    public void onLoad(ModuleContext context) {
        // keep me
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        new PlaytimeRoutes(repository, Config.defaults()).register(registrar);
    }

    @Override
    public void onStart(ModuleContext context) {
        // keep me too
    }
}
`
	out := stripOnRegisterRoutes(src)
	if strings.Contains(out, "onRegisterRoutes") {
		t.Errorf("onRegisterRoutes method not removed:\n%s", out)
	}
	if strings.Contains(out, ".rest.") {
		t.Errorf("rest imports not removed:\n%s", out)
	}
	if strings.Contains(out, "RouteRegistrar") {
		t.Errorf("RouteRegistrar reference survived:\n%s", out)
	}
	for _, want := range []string{"onLoad", "onStart", "config.Config", "implements PlatformModule"} {
		if !strings.Contains(out, want) {
			t.Errorf("expected %q to survive the strip:\n%s", want, out)
		}
	}
}

func TestGenerateStripsRest(t *testing.T) {
	root := fakeTemplate(t)
	// Enrich the fixture: a rest/ package + an entrypoint with onRegisterRoutes.
	pkgDir := filepath.Join(root, "java", "cloud-modules", "example",
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "example")
	mustWrite(t, filepath.Join(pkgDir, "rest", "PlaytimeRoutes.java"),
		"package me.prexorjustin.prexorcloud.modules.example.rest;\npublic final class PlaytimeRoutes {}\n")
	mustWrite(t, filepath.Join(pkgDir, "platform", "ExamplePlaytimeModule.java"),
		`package me.prexorjustin.prexorcloud.modules.example.platform;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.example.rest.PlaytimeRoutes;

public final class ExamplePlaytimeModule implements PlatformModule {
    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        new PlaytimeRoutes().register(registrar);
    }
}
`)

	// WithFrontend:true keeps the wizard path active (so applyWizardOverrides runs)
	// without stripping anything else; WithRest:false triggers the rest strip.
	if _, err := Generate(Options{
		RepoRoot:     root,
		Name:         "rest-stripped",
		WithFrontend: true,
		WithRest:     false,
	}); err != nil {
		t.Fatalf("Generate: %v", err)
	}

	dest := filepath.Join(root, "java", "cloud-modules", "rest-stripped")
	if _, err := os.Stat(filepath.Join(dest, "src", "main", "java", "me", "prexorjustin", "prexorcloud",
		"modules", "reststripped", "rest")); err == nil {
		t.Errorf("rest/ package should have been removed")
	}
	// "ExamplePlaytimeModule" → token-renamed to "RestStrippedModule".
	entry := readFile(t, filepath.Join(dest, "src", "main", "java", "me", "prexorjustin", "prexorcloud",
		"modules", "reststripped", "platform", "RestStrippedModule.java"))
	if strings.Contains(entry, "onRegisterRoutes") {
		t.Errorf("onRegisterRoutes not stripped from entrypoint:\n%s", entry)
	}
	if strings.Contains(entry, ".rest.") || strings.Contains(entry, "RouteRegistrar") {
		t.Errorf("rest imports not stripped from entrypoint:\n%s", entry)
	}
}

func TestPruneExtensionsBlock(t *testing.T) {
	manifest := `id: m
extensions:
  - id: m-paper
    target: server/paper
  - id: m-velocity
    target: proxy/velocity
storage:
  mongo: true
`
	pruned := pruneExtensionsBlock(manifest, map[string]bool{"paper": true})
	if !strings.Contains(pruned, "target: server/paper") {
		t.Errorf("kept target missing:\n%s", pruned)
	}
	if strings.Contains(pruned, "proxy/velocity") {
		t.Errorf("velocity should be pruned:\n%s", pruned)
	}
	// The following top-level block must survive untouched.
	if !strings.Contains(pruned, "storage:\n  mongo: true") {
		t.Errorf("block after extensions was clobbered:\n%s", pruned)
	}

	// nil keep (no selection) is a no-op.
	if got := pruneExtensionsBlock(manifest, nil); got != manifest {
		t.Errorf("nil keep should be a no-op")
	}
}

func TestPruneExtensionArtifacts(t *testing.T) {
	build := `prexorcloudModule {
    archiveName.set("m")
    extensionArtifacts.set(
        mapOf(
            "extensions/server/folia/m-folia.jar" to ":cloud-modules:m:plugin:folia",
            "extensions/proxy/velocity/m-velocity.jar" to ":cloud-modules:m:plugin:velocity",
            "extensions/server/paper/m-paper.jar" to ":cloud-modules:m:plugin:paper",
            "extensions/server/bedrock-geyser/m-bedrock-geyser.jar"
                to ":cloud-modules:m:plugin:bedrock-geyser",
        ),
    )
}
`
	pruned := pruneExtensionArtifacts(build, map[string]bool{"paper": true})
	if !strings.Contains(pruned, ":plugin:paper") {
		t.Errorf("paper artifact dropped:\n%s", pruned)
	}
	for _, gone := range []string{":plugin:folia", ":plugin:velocity", ":plugin:bedrock-geyser", "bedrock-geyser.jar"} {
		if strings.Contains(pruned, gone) {
			t.Errorf("%s should have been pruned:\n%s", gone, pruned)
		}
	}
	// Structure survives.
	for _, want := range []string{"extensionArtifacts.set(", "mapOf(", "archiveName.set"} {
		if !strings.Contains(pruned, want) {
			t.Errorf("structural line %q lost:\n%s", want, pruned)
		}
	}
	if got := pruneExtensionArtifacts(build, nil); got != build {
		t.Errorf("nil keep should be a no-op")
	}
}

func TestIsUnselectedPluginDir(t *testing.T) {
	keep := map[string]bool{"paper": true}
	cases := []struct {
		path string
		want bool
	}{
		{"plugin/paper", false},
		{"plugin/paper/v1_20", false},
		{"plugin/folia", true},
		{"plugin/velocity/src", true},
		{"src/main/java", false},
		{"plugin", false},
	}
	for _, c := range cases {
		if got := isUnselectedPluginDir(c.path, keep); got != c.want {
			t.Errorf("isUnselectedPluginDir(%q) = %v, want %v", c.path, got, c.want)
		}
	}
}

func TestToPascalCamel(t *testing.T) {
	cases := []struct{ in, pascal, camel string }{
		{"stats-aggregator", "StatsAggregator", "statsAggregator"},
		{"foo", "Foo", "foo"},
		{"a-b-c", "ABC", "aBC"},
	}
	for _, c := range cases {
		if got := toPascal(c.in); got != c.pascal {
			t.Errorf("toPascal(%q) = %q, want %q", c.in, got, c.pascal)
		}
		if got := toCamel(c.in); got != c.camel {
			t.Errorf("toCamel(%q) = %q, want %q", c.in, got, c.camel)
		}
	}
}

func readFile(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read %s: %v", p, err)
	}
	return string(b)
}
