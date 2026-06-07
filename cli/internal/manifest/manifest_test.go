package manifest

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func writeJar(t *testing.T, dir string, entries map[string][]byte) string {
	t.Helper()
	path := filepath.Join(dir, "mod.jar")
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, data := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	zw.Close()
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestReadFromJar_Roundtrip(t *testing.T) {
	jar := writeJar(t, t.TempDir(), map[string][]byte{
		"module.yaml": []byte(`manifestVersion: 1
id: example-playtime-consumer
version: 1.0.0
backend:
  entrypoint: com.example.Mod
capabilities:
  requires:
    - id: example-playtime-query
      versionRange: "[1.0,2.0)"
  provides:
    - id: my.thing
      version: 1.0.0
`),
	})

	m, err := ReadFromJar(jar)
	if err != nil {
		t.Fatal(err)
	}
	if m.ID != "example-playtime-consumer" {
		t.Errorf("id = %q", m.ID)
	}
	if m.ManifestVersion != 1 {
		t.Errorf("manifestVersion = %d", m.ManifestVersion)
	}
	if len(m.Capabilities.Requires) != 1 || m.Capabilities.Requires[0].ID != "example-playtime-query" {
		t.Errorf("requires = %+v", m.Capabilities.Requires)
	}
	if len(m.Capabilities.Provides) != 1 || m.Capabilities.Provides[0].Version != "1.0.0" {
		t.Errorf("provides = %+v", m.Capabilities.Provides)
	}
}

func TestReadFromJar_NoManifest(t *testing.T) {
	jar := writeJar(t, t.TempDir(), map[string][]byte{
		"META-INF/MANIFEST.MF": []byte("Manifest-Version: 1.0\n"),
	})
	_, err := ReadFromJar(jar)
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestReadFromJar_RejectsUnknownManifestVersion(t *testing.T) {
	jar := writeJar(t, t.TempDir(), map[string][]byte{
		"module.yaml": []byte("manifestVersion: 99\nid: x\n"),
	})
	_, err := ReadFromJar(jar)
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestReadFromJar_RejectsMissingId(t *testing.T) {
	jar := writeJar(t, t.TempDir(), map[string][]byte{
		"module.yaml": []byte("manifestVersion: 1\n"),
	})
	_, err := ReadFromJar(jar)
	if err == nil {
		t.Fatal("expected error")
	}
}
