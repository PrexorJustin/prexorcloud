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
	mustMkdir(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example",
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "example"))
	mustMkdir(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example", "build"))                    // ignored
	mustMkdir(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example", "frontend", "node_modules")) // ignored

	// Plugin subprojects — one tiny file per target so we can assert which
	// of them survive a selective scaffold.
	for _, target := range []string{"paper", "folia", "velocity"} {
		mustWrite(t,
			filepath.Join(root, "java", "cloud-module", "cloud-module-example",
				"plugin", target, "build.gradle.kts"),
			`plugins { id("prexorcloud.java21-api") }
`)
	}

	mustWrite(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example", "build.gradle.kts"),
		`plugins { id("prexorcloud.java21-api") }
// module: example-playtime
`)
	mustWrite(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example",
		"src", "main", "java", "me", "prexorjustin", "prexorcloud", "modules", "example",
		"ExamplePlaytimeModule.java"),
		`package me.prexorjustin.prexorcloud.modules.example;
// STEP 1: implement
public class ExamplePlaytimeModule {
    static String name() { return "examplePlaytime"; }
}
`)
	mustWrite(t, filepath.Join(root, "java", "cloud-module", "cloud-module-example", "build", "should-be-ignored.txt"),
		`if you see this in the output the build/ filter is broken
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

	dest := filepath.Join(root, "java", "cloud-module", "cloud-module-stats-aggregator")
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
	} {
		if !strings.Contains(settings, expected) {
			t.Errorf("settings missing %s:\n%s", expected, settings)
		}
	}
	if !res.SettingsPatched || res.IncludesAdded != 4 {
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
	if _, err := os.Stat(filepath.Join(root, "java", "cloud-module", "cloud-module-dry-run-mod")); err == nil {
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
	dest := filepath.Join(root, "java", "cloud-module", "cloud-module-no-teaching")
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
	dest := filepath.Join(root, "java", "cloud-module", "cloud-module-stats-aggregator")
	if _, err := os.Stat(filepath.Join(dest,
		"src", "main", "java", "io", "example", "cloudmod", "stats", "StatsAggregatorModule.java")); err != nil {
		t.Errorf("expected file at custom package path: %v", err)
	}
}

func TestFindRepoRoot(t *testing.T) {
	root := fakeTemplate(t)
	deep := filepath.Join(root, "java", "cloud-module", "cloud-module-example", "src", "main")
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

	dest := filepath.Join(root, "java", "cloud-module", "cloud-module-skinny-mod")
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
}

func TestGenerateAllTargetsByDefault(t *testing.T) {
	root := fakeTemplate(t)
	if _, err := Generate(Options{RepoRoot: root, Name: "fat-mod"}); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	dest := filepath.Join(root, "java", "cloud-module", "cloud-module-fat-mod")
	for _, target := range []string{"paper", "folia", "velocity"} {
		if _, err := os.Stat(filepath.Join(dest, "plugin", target, "build.gradle.kts")); err != nil {
			t.Errorf("plugin/%s missing: %v", target, err)
		}
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
