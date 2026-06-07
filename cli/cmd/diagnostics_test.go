package cmd

import (
	"archive/tar"
	"compress/gzip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteBundle_IncludesExpectedEntriesAndRedactsNothingClientSide(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "bundle.tar.gz")

	diag := map[string]any{
		"controllerId":  "ctrl-1",
		"generatedAtMs": float64(123),
		"version":       map[string]any{"version": "0.1.0"},
		"readiness":     map[string]any{"ready": true},
		"overview":      map[string]any{"nodes": float64(2)},
		"settings":      map[string]any{"nodeCount": float64(2)},
		"redactedConfig": map[string]any{
			"security": map[string]any{"jwtSecret": "***REDACTED***"},
		},
		"redisKeyspace": map[string]any{"enabled": true},
		"leases": []any{
			map[string]any{"resource": "scheduler", "holder": "ctrl-1", "token": float64(7), "ttlSeconds": float64(20)},
		},
	}
	logs := map[string]any{
		"records": []any{
			map[string]any{
				"ts":      float64(1700000000000),
				"level":   "WARN",
				"logger":  "x.y.Z",
				"message": "something happened",
			},
			map[string]any{
				"ts":        float64(1700000001000),
				"level":     "ERROR",
				"logger":    "x.y.Z",
				"message":   "boom",
				"throwable": "java.lang.RuntimeException: kaboom\n\tat ...\n",
			},
		},
	}

	path, err := writeBundle(out, diag, logs)
	if err != nil {
		t.Fatalf("writeBundle: %v", err)
	}
	if path != out {
		t.Fatalf("expected %s got %s", out, path)
	}

	entries := readTarGz(t, path)
	mustContain := []string{
		"manifest.json",
		"readiness.json",
		"overview.json",
		"settings.json",
		"config.json",
		"redis.json",
		"leases.json",
		"logs.txt",
	}
	for _, name := range mustContain {
		if _, ok := entries[name]; !ok {
			t.Errorf("missing bundle entry: %s", name)
		}
	}

	var manifest map[string]any
	if err := json.Unmarshal(entries["manifest.json"], &manifest); err != nil {
		t.Fatalf("manifest: %v", err)
	}
	if manifest["controllerId"] != "ctrl-1" {
		t.Errorf("manifest controllerId = %v", manifest["controllerId"])
	}
	if manifest["bundleVersion"] != float64(1) {
		t.Errorf("manifest bundleVersion = %v", manifest["bundleVersion"])
	}

	logsTxt := string(entries["logs.txt"])
	if !strings.Contains(logsTxt, "WARN") || !strings.Contains(logsTxt, "something happened") {
		t.Errorf("logs.txt missing WARN line: %s", logsTxt)
	}
	if !strings.Contains(logsTxt, "ERROR") || !strings.Contains(logsTxt, "boom") {
		t.Errorf("logs.txt missing ERROR line: %s", logsTxt)
	}
	if !strings.Contains(logsTxt, "kaboom") {
		t.Errorf("logs.txt missing throwable: %s", logsTxt)
	}
}

func TestWriteBundle_SkipsLogsEntryWhenLogsNil(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "bundle.tar.gz")
	diag := map[string]any{
		"controllerId":   "ctrl-1",
		"generatedAtMs":  float64(0),
		"version":        map[string]any{},
		"readiness":      map[string]any{},
		"overview":       map[string]any{},
		"settings":       map[string]any{},
		"redactedConfig": map[string]any{},
		"redisKeyspace":  map[string]any{},
		"leases":         []any{},
	}
	if _, err := writeBundle(out, diag, nil); err != nil {
		t.Fatalf("writeBundle: %v", err)
	}
	entries := readTarGz(t, out)
	if _, ok := entries["logs.txt"]; ok {
		t.Errorf("logs.txt must not be present when logs are nil")
	}
}

func TestWriteBundle_DefaultOutPathIncludesTimestamp(t *testing.T) {
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(cwd)

	diag := map[string]any{
		"controllerId":   "c",
		"generatedAtMs":  float64(0),
		"version":        map[string]any{},
		"readiness":      map[string]any{},
		"overview":       map[string]any{},
		"settings":       map[string]any{},
		"redactedConfig": map[string]any{},
		"redisKeyspace":  map[string]any{},
		"leases":         []any{},
	}
	path, err := writeBundle("", diag, nil)
	if err != nil {
		t.Fatalf("writeBundle: %v", err)
	}
	if !strings.HasPrefix(filepath.Base(path), "prexorctl-diag-") {
		t.Errorf("expected default path prefix, got %s", path)
	}
	if !strings.HasSuffix(path, ".tar.gz") {
		t.Errorf("expected .tar.gz suffix, got %s", path)
	}
}

func readTarGz(t *testing.T, path string) map[string][]byte {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	out := map[string][]byte{}
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(tr)
		if err != nil {
			t.Fatal(err)
		}
		out[hdr.Name] = body
	}
	return out
}

func TestHumanBytes(t *testing.T) {
	cases := []struct {
		in   int64
		want string
	}{
		{0, "0 B"},
		{1023, "1023 B"},
		{1024, "1.0 KB"},
		{1024 * 1024, "1.0 MB"},
		{int64(2.5 * 1024 * 1024), "2.5 MB"},
	}
	for _, c := range cases {
		got := humanBytes(c.in)
		if got != c.want {
			t.Errorf("humanBytes(%d) = %s want %s", c.in, got, c.want)
		}
	}
}
