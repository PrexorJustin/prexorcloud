package cmd

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// buildDoctorJar produces a temp jar with the given module.yaml and zip
// entries (path → content). Convenience for the doctor tests.
func buildDoctorJar(t *testing.T, manifestYAML string, entries map[string][]byte) string {
	t.Helper()
	dir := t.TempDir()
	jarPath := filepath.Join(dir, "mod.jar")
	f, err := os.Create(jarPath)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	zw := zip.NewWriter(f)

	mw, err := zw.Create("META-INF/prexor/module.yaml")
	if err != nil {
		t.Fatal(err)
	}
	mw.Write([]byte(manifestYAML))

	for name, data := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		w.Write(data)
	}
	zw.Close()
	return jarPath
}

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func TestDoctor_CleanJar(t *testing.T) {
	pluginBytes := []byte("fake plugin classfile")
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.MyModule
capabilities:
  provides:
    - id: my-cap
      version: 1.0.0
extensions:
  - id: example-folia
    target: server/folia
    activation: explicit-group-attach
    variants:
      - id: example-folia
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/folia/example-folia.jar
        sha256: ` + sha256Hex(pluginBytes) + `
        installPath: plugins/
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/MyModule.class":                []byte("classfile"),
		"extensions/server/folia/example-folia.jar": pluginBytes,
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if len(r.errors) != 0 {
		t.Errorf("clean jar produced errors: %v", r.errors)
	}
	// Expect 1 warning: missing signature sidecar (acceptable in tests).
	if len(r.warnings) != 1 || !strings.Contains(r.warnings[0], "signature sidecar") {
		t.Errorf("warnings = %v, want only the missing-sidecar warning", r.warnings)
	}
}

func TestDoctor_MissingEntrypoint(t *testing.T) {
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.NotPresent
`
	jar := buildDoctorJar(t, manifestYAML, nil)

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if len(r.errors) == 0 || !contains(r.errors, "backend.entrypoint") {
		t.Errorf("expected backend.entrypoint error, got %v", r.errors)
	}
}

func TestDoctor_Sha256Mismatch(t *testing.T) {
	pluginBytes := []byte("real bytes")
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.M
extensions:
  - id: example-folia
    target: server/folia
    activation: explicit-group-attach
    variants:
      - id: example-folia
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/folia/example-folia.jar
        sha256: ` + strings.Repeat("a", 64) + `
        installPath: plugins/
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class":                       []byte("c"),
		"extensions/server/folia/example-folia.jar": pluginBytes,
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if !contains(r.errors, "sha256 mismatch") {
		t.Errorf("expected sha256 mismatch error, got %v", r.errors)
	}
}

func TestDoctor_AutoSha256IsWarning(t *testing.T) {
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.M
extensions:
  - id: example-folia
    target: server/folia
    activation: explicit-group-attach
    variants:
      - id: example-folia
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/folia/example-folia.jar
        sha256: AUTO
        installPath: plugins/
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class":                       []byte("c"),
		"extensions/server/folia/example-folia.jar": []byte("any"),
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if len(r.errors) != 0 {
		t.Errorf("AUTO sha256 should not be an error, got %v", r.errors)
	}
	if !contains(r.warnings, "AUTO") {
		t.Errorf("expected AUTO sha256 warning, got %v", r.warnings)
	}
}

func TestDoctor_MissingExtensionArtifact(t *testing.T) {
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.M
extensions:
  - id: example-folia
    target: server/folia
    activation: explicit-group-attach
    variants:
      - id: example-folia
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/folia/missing.jar
        sha256: ` + strings.Repeat("a", 64) + `
        installPath: plugins/
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class": []byte("c"),
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if !contains(r.errors, "not found in jar") {
		t.Errorf("expected missing-artifact error, got %v", r.errors)
	}
}

func TestDoctor_SignatureSidecarPresent(t *testing.T) {
	manifestYAML := `manifestVersion: 1
id: example-mod
version: 1.0.0
backend:
  entrypoint: com.example.M
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class": []byte("c"),
	})
	os.WriteFile(jar+".cosign.bundle", []byte("bundle"), 0o644)

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	for _, w := range r.warnings {
		if strings.Contains(w, "signature sidecar") {
			t.Errorf("did not expect signature warning, got %q", w)
		}
	}
}

func TestDoctor_ManifestVersion2_DeprecatedProvideWarns(t *testing.T) {
	manifestYAML := `manifestVersion: 2
id: profiles
version: 2.5.0
backend:
  entrypoint: com.example.M
capabilities:
  provides:
    - id: player-profile
      version: 1.4.0
      deprecatedSince: 1.3.0
      removedIn: 2.0.0
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class": []byte("c"),
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if len(r.errors) != 0 {
		t.Fatalf("clean v2 manifest produced errors: %v", r.errors)
	}
	if !contains(r.warnings, "deprecated since 1.3.0") {
		t.Errorf("expected deprecation warning, got %v", r.warnings)
	}
	if !contains(r.warnings, "removed in 2.0.0") {
		t.Errorf("expected removedIn in warning, got %v", r.warnings)
	}
}

func TestDoctor_DeprecatedSinceOnV1IsError(t *testing.T) {
	// manifest.parse() catches this — surfaces as a manifest read error,
	// not a doctor capability check.
	manifestYAML := `manifestVersion: 1
id: profiles
version: 1.0.0
backend:
  entrypoint: com.example.M
capabilities:
  provides:
    - id: cap
      version: 1.0.0
      deprecatedSince: 1.0.0
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class": []byte("c"),
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if !contains(r.errors, "deprecatedSince") || !contains(r.errors, "manifestVersion >= 2") {
		t.Errorf("expected v1 deprecation rejection, got errors=%v warnings=%v", r.errors, r.warnings)
	}
}

func TestDoctor_RemovedInWithoutDeprecatedSinceIsError(t *testing.T) {
	manifestYAML := `manifestVersion: 2
id: profiles
version: 1.0.0
backend:
  entrypoint: com.example.M
capabilities:
  provides:
    - id: cap
      version: 1.0.0
      removedIn: 2.0.0
`
	jar := buildDoctorJar(t, manifestYAML, map[string][]byte{
		"com/example/M.class": []byte("c"),
	})

	r := &doctorReport{}
	doctorValidateJar(jar, r)

	if !contains(r.errors, "removedIn requires deprecatedSince") {
		t.Errorf("expected removedIn-without-deprecatedSince error, got %v", r.errors)
	}
}

func contains(haystack []string, needle string) bool {
	for _, s := range haystack {
		if strings.Contains(s, needle) {
			return true
		}
	}
	return false
}
