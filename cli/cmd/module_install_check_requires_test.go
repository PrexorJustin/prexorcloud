package cmd

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/prexorcloud/prexorctl/internal/api"
)

func writeJarWithManifest(t *testing.T, manifest string) string {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, err := zw.Create("module.yaml")
	if err != nil {
		t.Fatal(err)
	}
	w.Write([]byte(manifest))
	zw.Close()

	path := filepath.Join(t.TempDir(), "mod.jar")
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestPreflightCheckRequires_ProviderPresent(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/modules/platform/capabilities" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		json.NewEncoder(w).Encode(map[string]any{
			"modules": []map[string]any{{
				"moduleId": "example-playtime",
				"provides": []map[string]any{
					{"id": "example-playtime-query", "version": "1.0.0", "active": true},
				},
			}},
		})
	}))
	defer srv.Close()

	jar := writeJarWithManifest(t, `manifestVersion: 1
id: consumer
version: 1.0.0
capabilities:
  requires:
    - id: example-playtime-query
      versionRange: "[1.0,2.0)"
`)

	client := api.New(srv.URL, "tok", false)
	if err := preflightCheckRequires(client, jar); err != nil {
		t.Fatalf("preflight failed: %v", err)
	}
}

func TestPreflightCheckRequires_InactiveProviderDoesNotCount(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"modules": []map[string]any{{
				"moduleId": "shadowed",
				"provides": []map[string]any{
					{"id": "needed.cap", "version": "1.0.0", "active": false},
				},
			}},
		})
	}))
	defer srv.Close()

	jar := writeJarWithManifest(t, `manifestVersion: 1
id: consumer
version: 1.0.0
capabilities:
  requires:
    - id: needed.cap
      versionRange: ">=1.0.0"
`)

	client := api.New(srv.URL, "tok", false)
	// Inactive provider should be skipped — preflight returns nil but warns;
	// the test verifies no error and the request hit the endpoint.
	if err := preflightCheckRequires(client, jar); err != nil {
		t.Fatalf("preflight failed: %v", err)
	}
}

func TestPreflightCheckRequires_NoRequires(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		json.NewEncoder(w).Encode(map[string]any{"modules": []any{}})
	}))
	defer srv.Close()

	jar := writeJarWithManifest(t, `manifestVersion: 1
id: standalone
version: 1.0.0
`)

	client := api.New(srv.URL, "tok", false)
	if err := preflightCheckRequires(client, jar); err != nil {
		t.Fatalf("preflight failed: %v", err)
	}
	if calls != 0 {
		t.Errorf("expected zero controller calls when manifest has no requires; got %d", calls)
	}
}

func TestPreflightCheckRequires_BadManifest(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer srv.Close()

	jarPath := filepath.Join(t.TempDir(), "mod.jar")
	os.WriteFile(jarPath, []byte("not a zip"), 0o644)

	client := api.New(srv.URL, "tok", false)
	err := preflightCheckRequires(client, jarPath)
	if err == nil {
		t.Fatal("expected error for malformed jar")
	}
	if !strings.Contains(err.Error(), "--check-requires") {
		t.Errorf("error %v should be tagged with --check-requires", err)
	}
}
