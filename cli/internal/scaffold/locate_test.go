package scaffold

import (
	"path/filepath"
	"strings"
	"testing"
)

func writeModule(t *testing.T, root, kebab, archive string) {
	t.Helper()
	dir := filepath.Join(root, "java", "cloud-modules", ""+kebab)
	mustMkdir(t, dir)
	body := "plugins { id(\"prexorcloud.module\") }\n" +
		"prexorcloudModule {\n" +
		"    archiveName.set(\"" + archive + "\")\n" +
		"}\n"
	mustWrite(t, filepath.Join(dir, "build.gradle.kts"), body)
}

func TestLocateModule_Basic(t *testing.T) {
	root := t.TempDir()
	writeModule(t, root, "stats-aggregator", "stats-aggregator")

	mod, err := LocateModule(root, "stats-aggregator")
	if err != nil {
		t.Fatalf("LocateModule: %v", err)
	}
	if mod.Name != "stats-aggregator" {
		t.Errorf("Name = %q", mod.Name)
	}
	if mod.ArchiveName != "stats-aggregator" {
		t.Errorf("ArchiveName = %q", mod.ArchiveName)
	}
	if !strings.HasSuffix(mod.JarPath, "build/libs/stats-aggregator.jar") {
		t.Errorf("JarPath = %q", mod.JarPath)
	}
	if mod.GradleTask != ":cloud-modules:stats-aggregator" {
		t.Errorf("GradleTask = %q", mod.GradleTask)
	}
}

func TestLocateModule_AcceptsDirPrefix(t *testing.T) {
	root := t.TempDir()
	writeModule(t, root, "example", "example-playtime")

	mod, err := LocateModule(root, "cloud-module-example")
	if err != nil {
		t.Fatalf("LocateModule: %v", err)
	}
	if mod.Name != "example" {
		t.Errorf("Name = %q (prefix should be stripped)", mod.Name)
	}
	if mod.ArchiveName != "example-playtime" {
		t.Errorf("ArchiveName = %q", mod.ArchiveName)
	}
}

func TestLocateModule_ParsesArchiveNameWithExtraWhitespace(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "java", "cloud-modules", "spacey")
	mustMkdir(t, dir)
	mustWrite(t, filepath.Join(dir, "build.gradle.kts"),
		"prexorcloudModule {\n    archiveName.set(  \"spaced-out\"  )\n}\n")

	mod, err := LocateModule(root, "spacey")
	if err != nil {
		t.Fatalf("LocateModule: %v", err)
	}
	if mod.ArchiveName != "spaced-out" {
		t.Errorf("ArchiveName = %q", mod.ArchiveName)
	}
}

func TestLocateModule_RejectsInvalidName(t *testing.T) {
	root := t.TempDir()
	if _, err := LocateModule(root, "Bad_Name"); err == nil {
		t.Fatal("expected error for invalid kebab name")
	}
}

func TestLocateModule_MissingModule(t *testing.T) {
	root := t.TempDir()
	if _, err := LocateModule(root, "nonexistent"); err == nil {
		t.Fatal("expected error for missing module")
	}
}

func TestLocateModule_MissingArchiveName(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "java", "cloud-modules", "bare")
	mustMkdir(t, dir)
	mustWrite(t, filepath.Join(dir, "build.gradle.kts"), "plugins { id(\"prexorcloud.module\") }\n")

	_, err := LocateModule(root, "bare")
	if err == nil || !strings.Contains(err.Error(), "archiveName.set") {
		t.Fatalf("expected archiveName.set error, got %v", err)
	}
}
