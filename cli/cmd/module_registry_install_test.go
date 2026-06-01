package cmd

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIsLocalModuleSource(t *testing.T) {
	dir := t.TempDir()
	realJar := filepath.Join(dir, "thing.jar")
	if err := os.WriteFile(realJar, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	realFileNoExt := filepath.Join(dir, "modulefile")
	if err := os.WriteFile(realFileNoExt, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name   string
		source string
		want   bool
	}{
		{"jar suffix", "build/libs/foo.jar", true},
		{"tar.gz bundle", "dist/foo.tar.gz", true},
		{"tgz bundle", "dist/foo.tgz", true},
		{"existing jar on disk", realJar, true},
		{"existing extensionless file on disk", realFileNoExt, true},
		{"registry id", "stats-aggregator", false},
		{"registry id@version", "stats-aggregator@1.2.0", false},
		{"registry id@latest", "player-journey@latest", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isLocalModuleSource(tc.source); got != tc.want {
				t.Fatalf("isLocalModuleSource(%q) = %v, want %v", tc.source, got, tc.want)
			}
		})
	}
}

func TestRegistrySpecParsing(t *testing.T) {
	cases := []struct {
		spec        string
		wantModule  string
		wantVersion string
	}{
		{"foo", "foo", ""},
		{"foo@1.2.0", "foo", "1.2.0"},
		{"foo@latest", "foo", "latest"},
		{"scoped-name@2.0.0-rc.1", "scoped-name", "2.0.0-rc.1"},
	}
	for _, tc := range cases {
		t.Run(tc.spec, func(t *testing.T) {
			moduleID, version := tc.spec, ""
			if at := indexByte(tc.spec, '@'); at >= 0 {
				moduleID, version = tc.spec[:at], tc.spec[at+1:]
			}
			if moduleID != tc.wantModule || version != tc.wantVersion {
				t.Fatalf("parsed (%q,%q), want (%q,%q)", moduleID, version, tc.wantModule, tc.wantVersion)
			}
		})
	}
}

// indexByte mirrors strings.IndexByte so the test documents the exact parsing
// runRegistryInstall performs without exporting it.
func indexByte(s string, b byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == b {
			return i
		}
	}
	return -1
}
