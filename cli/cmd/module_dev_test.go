package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/scaffold"
)

func writeJar(t *testing.T) (string, string) {
	t.Helper()
	dir := t.TempDir()
	jar := filepath.Join(dir, "demo.jar")
	if err := os.WriteFile(jar, []byte("PK\x03\x04 fake-jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir, jar
}

func TestUploadOrUpgrade_FreshInstall(t *testing.T) {
	_, jar := writeJar(t)

	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		if r.URL.Path != "/api/v1/modules/platform/upload" || r.Method != http.MethodPost {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(201)
		_ = json.NewEncoder(w).Encode(map[string]string{"moduleId": "demo"})
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo", JarPath: jar}

	id, err := uploadOrUpgrade(c, mod, "")
	if err != nil {
		t.Fatalf("uploadOrUpgrade: %v", err)
	}
	if id != "demo" {
		t.Errorf("id = %q, want demo", id)
	}
	if atomic.LoadInt32(&hits) != 1 {
		t.Errorf("hits = %d, want 1", hits)
	}
}

func TestUploadOrUpgrade_UpgradeWhenInstalled(t *testing.T) {
	_, jar := writeJar(t)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/modules/platform/demo/upgrade" {
			t.Errorf("path = %s, want /upgrade", r.URL.Path)
		}
		w.WriteHeader(200)
		_ = json.NewEncoder(w).Encode(map[string]string{"moduleId": "demo"})
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo", JarPath: jar}

	id, err := uploadOrUpgrade(c, mod, "demo")
	if err != nil {
		t.Fatalf("uploadOrUpgrade: %v", err)
	}
	if id != "demo" {
		t.Errorf("id = %q", id)
	}
}

func TestUploadOrUpgrade_FallsBackToInstallOn404(t *testing.T) {
	_, jar := writeJar(t)

	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.Path)
		if strings.HasSuffix(r.URL.Path, "/upgrade") {
			w.WriteHeader(404)
			_ = json.NewEncoder(w).Encode(map[string]string{"code": "NOT_FOUND", "message": "gone"})
			return
		}
		if r.URL.Path == "/api/v1/modules/platform/upload" {
			w.WriteHeader(201)
			_ = json.NewEncoder(w).Encode(map[string]string{"moduleId": "demo"})
			return
		}
		t.Errorf("unexpected path %s", r.URL.Path)
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo", JarPath: jar}

	id, err := uploadOrUpgrade(c, mod, "demo")
	if err != nil {
		t.Fatalf("uploadOrUpgrade: %v", err)
	}
	if id != "demo" {
		t.Errorf("id = %q", id)
	}
	if len(seen) != 2 || !strings.HasSuffix(seen[0], "/upgrade") || !strings.HasSuffix(seen[1], "/upload") {
		t.Errorf("call sequence = %v, want [upgrade, upload]", seen)
	}
}

func TestUploadOrUpgrade_PropagatesNon404Error(t *testing.T) {
	_, jar := writeJar(t)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		_ = json.NewEncoder(w).Encode(map[string]string{"code": "BOOM", "message": "boom"})
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo", JarPath: jar}

	if _, err := uploadOrUpgrade(c, mod, "demo"); err == nil {
		t.Fatal("expected error from 500 upgrade")
	}
}

func TestLookupInstalledModuleID_MatchesByJarFile(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := map[string]any{
			"modules": []map[string]any{
				{"moduleId": "other-1", "jarFile": "other.jar"},
				{"moduleId": "demo-42", "jarFile": "demo.jar"},
			},
		}
		_ = json.NewEncoder(w).Encode(body)
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo"}

	got := lookupInstalledModuleID(c, mod)
	if got != "demo-42" {
		t.Errorf("moduleId = %q, want demo-42", got)
	}
}

func TestLookupInstalledModuleID_ReturnsEmptyWhenAbsent(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"modules": []any{}})
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo"}

	if got := lookupInstalledModuleID(c, mod); got != "" {
		t.Errorf("moduleId = %q, want empty", got)
	}
}

func TestLookupInstalledModuleID_ReturnsEmptyOnAPIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	}))
	defer srv.Close()

	c := api.New(srv.URL, "tok", false)
	mod := &scaffold.Module{ArchiveName: "demo"}

	if got := lookupInstalledModuleID(c, mod); got != "" {
		t.Errorf("moduleId = %q, want empty on error", got)
	}
}
