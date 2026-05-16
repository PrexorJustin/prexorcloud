package scaffold

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakePluginRepo writes the minimum tree GeneratePlugin needs:
// java/settings.gradle.kts containing the // ---- PLUGINS ---- // anchor.
// No template directory exists for plugin scaffolding — every file is rendered
// from code.
func fakePluginRepo(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	mustWrite(t, filepath.Join(root, "java", "settings.gradle.kts"),
		`rootProject.name = "prexorcloud"
include(
    "cloud-controller",

    // ---- PLUGINS ---- //
)
`)
	return root
}

func TestGeneratePluginPaperDefault(t *testing.T) {
	root := fakePluginRepo(t)
	res, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "hello",
		Platform: PluginPaper,
	})
	if err != nil {
		t.Fatalf("GeneratePlugin: %v", err)
	}
	if res.ConventionPlugin != "prexorcloud.plugin-paper" {
		t.Errorf("expected paper-1.20 convention, got %q", res.ConventionPlugin)
	}
	if res.PackageDot != "me.prexorjustin.prexorcloud.plugins.hello" {
		t.Errorf("default package wrong: %q", res.PackageDot)
	}
	if res.Pascal != "Hello" {
		t.Errorf("Pascal wrong: %q", res.Pascal)
	}
	if !res.SettingsPatched {
		t.Error("settings.gradle.kts should have been patched")
	}

	build := readFile(t, filepath.Join(res.DestDir, "build.gradle.kts"))
	if !strings.Contains(build, `id("prexorcloud.plugin-paper")`) {
		t.Errorf("build.gradle.kts missing convention plugin id:\n%s", build)
	}

	src := readFile(t, filepath.Join(res.DestDir, "src", "main", "java",
		"me", "prexorjustin", "prexorcloud", "plugins", "hello", "HelloPlugin.java"))
	if !strings.Contains(src, "package me.prexorjustin.prexorcloud.plugins.hello;") {
		t.Errorf("source missing package decl:\n%s", src)
	}
	if !strings.Contains(src, "@CloudPlugin(") {
		t.Errorf("source missing @CloudPlugin annotation:\n%s", src)
	}
	if !strings.Contains(src, "extends CloudPluginBase") {
		t.Errorf("source missing CloudPluginBase superclass:\n%s", src)
	}

	settings := readFile(t, filepath.Join(root, "java", "settings.gradle.kts"))
	if !strings.Contains(settings, `"cloud-plugin:cloud-plugin-hello",`) {
		t.Errorf("settings.gradle.kts not patched with include():\n%s", settings)
	}
}

func TestGeneratePluginPaper121(t *testing.T) {
	root := fakePluginRepo(t)
	res, err := GeneratePlugin(PluginOptions{
		RepoRoot:  root,
		Name:      "modern",
		Platform:  PluginPaper,
		MCVersion: "1.21",
	})
	if err != nil {
		t.Fatalf("GeneratePlugin: %v", err)
	}
	if res.ConventionPlugin != "prexorcloud.plugin-paper-1-21" {
		t.Errorf("expected paper-1-21 convention, got %q", res.ConventionPlugin)
	}
}

func TestGeneratePluginPaperRejectsBadMCVersion(t *testing.T) {
	root := fakePluginRepo(t)
	_, err := GeneratePlugin(PluginOptions{
		RepoRoot:  root,
		Name:      "old",
		Platform:  PluginPaper,
		MCVersion: "1.16",
	})
	if err == nil {
		t.Fatal("expected error for unsupported mc-version on paper")
	}
	if !strings.Contains(err.Error(), "1.16") {
		t.Errorf("error should name the offending version: %v", err)
	}
}

func TestGeneratePluginVelocityAddsExclusion(t *testing.T) {
	root := fakePluginRepo(t)
	res, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "router",
		Platform: PluginVelocity,
	})
	if err != nil {
		t.Fatalf("GeneratePlugin: %v", err)
	}
	build := readFile(t, filepath.Join(res.DestDir, "build.gradle.kts"))
	if !strings.Contains(build, "configurations.named(\"annotationProcessor\")") {
		t.Errorf("velocity build should exclude velocity-api annotationProcessor:\n%s", build)
	}
}

func TestGeneratePluginRejectsKebabViolation(t *testing.T) {
	root := fakePluginRepo(t)
	_, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "Bad_Name",
		Platform: PluginPaper,
	})
	if err == nil {
		t.Fatal("expected kebab-case validation error")
	}
}

func TestGeneratePluginRejectsExistingDirWithoutForce(t *testing.T) {
	root := fakePluginRepo(t)
	dest := filepath.Join(root, "java", "cloud-plugin", "cloud-plugin-occupied")
	if err := os.MkdirAll(dest, 0o755); err != nil {
		t.Fatal(err)
	}
	_, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "occupied",
		Platform: PluginPaper,
	})
	if err == nil {
		t.Fatal("expected error when destination exists without --force")
	}
}

func TestGeneratePluginIdempotentSettingsPatch(t *testing.T) {
	root := fakePluginRepo(t)
	if _, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "twice",
		Platform: PluginPaper,
	}); err != nil {
		t.Fatalf("first generate: %v", err)
	}
	res, err := GeneratePlugin(PluginOptions{
		RepoRoot: root,
		Name:     "twice",
		Platform: PluginPaper,
		Force:    true,
	})
	if err != nil {
		t.Fatalf("second generate: %v", err)
	}
	if res.SettingsPatched {
		t.Error("second generate should detect the include is already present and skip the patch")
	}
	settings := readFile(t, filepath.Join(root, "java", "settings.gradle.kts"))
	if c := strings.Count(settings, `"cloud-plugin:cloud-plugin-twice",`); c != 1 {
		t.Errorf("expected exactly one include() line, found %d:\n%s", c, settings)
	}
}
