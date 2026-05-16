package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setupHome(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	t.Setenv("USERPROFILE", dir)
	// Make sure env-var overrides from a prior test don't leak in.
	os.Unsetenv("PREXOR_CONTROLLER")
	os.Unsetenv("PREXOR_TOKEN")
	os.Unsetenv("PREXOR_CONTEXT")
	return dir
}

func TestLoad_ReturnsEmptyWhenFileMissing(t *testing.T) {
	setupHome(t)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v, want nil", err)
	}
	if cfg.CurrentContext != "" {
		t.Errorf("CurrentContext = %q, want empty", cfg.CurrentContext)
	}
	if len(cfg.Contexts) != 0 {
		t.Errorf("Contexts = %v, want empty", cfg.Contexts)
	}
}

func TestLoad_MigratesFlatLegacyShape(t *testing.T) {
	dir := setupHome(t)
	legacy := "controller: http://legacy.example.com\ntoken: legacy-token\naccent: cyan\n"
	if err := os.MkdirAll(filepath.Join(dir, ".prexorcloud"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".prexorcloud", "config.yml"), []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.CurrentContext != "default" {
		t.Errorf("CurrentContext = %q, want %q", cfg.CurrentContext, "default")
	}
	ctx, ok := cfg.Contexts["default"]
	if !ok {
		t.Fatalf("expected 'default' context, got %v", cfg.Contexts)
	}
	if ctx.Controller != "http://legacy.example.com" {
		t.Errorf("Controller = %q", ctx.Controller)
	}
	if ctx.Token != "legacy-token" {
		t.Errorf("Token = %q", ctx.Token)
	}
	if cfg.Accent != "cyan" {
		t.Errorf("Accent = %q, want cyan", cfg.Accent)
	}

	// Save and re-read: legacy fields should be gone from disk.
	if err := cfg.Save(); err != nil {
		t.Fatal(err)
	}
	written, err := os.ReadFile(Path())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(written), "\ncontroller: http://legacy.example.com") {
		t.Errorf("post-Save disk still has flat 'controller:' field:\n%s", string(written))
	}
	if !strings.Contains(string(written), "contexts:") {
		t.Errorf("post-Save disk missing 'contexts:' map:\n%s", string(written))
	}
}

func TestSaveLoad_Roundtrip(t *testing.T) {
	setupHome(t)
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod":    {Controller: "https://prod.example.com", Token: "prod-tok"},
			"staging": {Controller: "https://staging.example.com", Token: "stg-tok"},
		},
		Accent: "amber",
	}
	if err := cfg.Save(); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	loaded, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.CurrentContext != "prod" {
		t.Errorf("CurrentContext = %q", loaded.CurrentContext)
	}
	if loaded.Contexts["prod"].Controller != "https://prod.example.com" {
		t.Errorf("prod.Controller = %q", loaded.Contexts["prod"].Controller)
	}
	if loaded.Contexts["staging"].Token != "stg-tok" {
		t.Errorf("staging.Token = %q", loaded.Contexts["staging"].Token)
	}
	if loaded.Accent != "amber" {
		t.Errorf("Accent = %q", loaded.Accent)
	}
}

func TestResolve_PriorityOrder(t *testing.T) {
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod":    {Controller: "http://prod.host"},
			"staging": {Controller: "http://staging.host"},
		},
	}

	tests := []struct {
		name        string
		flagCtrl    string
		flagContext string
		envCtrl     string
		envContext  string
		want        string
	}{
		{"flag wins over everything", "http://flag.host", "staging", "http://env.host", "prod", "http://flag.host"},
		{"env controller wins over context", "", "staging", "http://env.host", "", "http://env.host"},
		{"flagContext picks the named context", "", "staging", "", "", "http://staging.host"},
		{"env context picks named context when no flag", "", "", "", "staging", "http://staging.host"},
		{"falls back to currentContext", "", "", "", "", "http://prod.host"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.envCtrl != "" {
				t.Setenv("PREXOR_CONTROLLER", tt.envCtrl)
			} else {
				os.Unsetenv("PREXOR_CONTROLLER")
			}
			if tt.envContext != "" {
				t.Setenv("PREXOR_CONTEXT", tt.envContext)
			} else {
				os.Unsetenv("PREXOR_CONTEXT")
			}

			got := cfg.Resolve(tt.flagCtrl, tt.flagContext)
			if got != tt.want {
				t.Errorf("Resolve(%q, %q) = %q, want %q", tt.flagCtrl, tt.flagContext, got, tt.want)
			}
		})
	}
}

func TestResolveToken_PriorityOrder(t *testing.T) {
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod":    {Controller: "u", Token: "prod-tok"},
			"staging": {Controller: "u", Token: "stg-tok"},
		},
	}
	tests := []struct {
		name        string
		flagTok     string
		flagContext string
		envTok      string
		want        string
	}{
		{"flag wins", "flag-tok", "staging", "env-tok", "flag-tok"},
		{"env wins over context", "", "staging", "env-tok", "env-tok"},
		{"flagContext token used when no flag/env", "", "staging", "", "stg-tok"},
		{"falls back to currentContext token", "", "", "", "prod-tok"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.envTok != "" {
				t.Setenv("PREXOR_TOKEN", tt.envTok)
			} else {
				os.Unsetenv("PREXOR_TOKEN")
			}
			got := cfg.ResolveToken(tt.flagTok, tt.flagContext)
			if got != tt.want {
				t.Errorf("ResolveToken(%q, %q) = %q, want %q", tt.flagTok, tt.flagContext, got, tt.want)
			}
		})
	}
}

func TestSetCurrentAuth_CreatesDefaultContext(t *testing.T) {
	cfg := &Config{}
	cfg.SetCurrentAuth("http://example.com", "tok-123")
	if cfg.CurrentContext != "default" {
		t.Errorf("CurrentContext = %q, want default", cfg.CurrentContext)
	}
	if cfg.Contexts["default"].Controller != "http://example.com" {
		t.Errorf("default.Controller = %q", cfg.Contexts["default"].Controller)
	}
	if cfg.Contexts["default"].Token != "tok-123" {
		t.Errorf("default.Token = %q", cfg.Contexts["default"].Token)
	}
}

func TestSetCurrentAuth_UpdatesExistingContext(t *testing.T) {
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod": {Controller: "http://old", Token: "old-tok"},
		},
	}
	cfg.SetCurrentAuth("http://new", "new-tok")
	if cfg.Contexts["prod"].Controller != "http://new" {
		t.Errorf("Controller not updated")
	}
	if cfg.Contexts["prod"].Token != "new-tok" {
		t.Errorf("Token not updated")
	}
}

func TestUse_UnknownContext(t *testing.T) {
	cfg := &Config{Contexts: map[string]*Context{"prod": {Controller: "u"}}}
	if err := cfg.Use("missing"); err == nil {
		t.Errorf("Use(missing) returned nil error")
	}
	if err := cfg.Use("prod"); err != nil {
		t.Errorf("Use(prod) error = %v", err)
	}
	if cfg.CurrentContext != "prod" {
		t.Errorf("CurrentContext = %q", cfg.CurrentContext)
	}
}

func TestRemove_ClearsCurrentWhenRemovingActive(t *testing.T) {
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod":    {Controller: "u"},
			"staging": {Controller: "u"},
		},
	}
	if err := cfg.Remove("prod"); err != nil {
		t.Fatal(err)
	}
	if cfg.CurrentContext != "" {
		t.Errorf("CurrentContext = %q, want empty after removing active", cfg.CurrentContext)
	}
	if _, ok := cfg.Contexts["prod"]; ok {
		t.Errorf("prod still in Contexts")
	}
}

func TestRemove_LeavesCurrentWhenRemovingOther(t *testing.T) {
	cfg := &Config{
		CurrentContext: "prod",
		Contexts: map[string]*Context{
			"prod":    {Controller: "u"},
			"staging": {Controller: "u"},
		},
	}
	if err := cfg.Remove("staging"); err != nil {
		t.Fatal(err)
	}
	if cfg.CurrentContext != "prod" {
		t.Errorf("CurrentContext = %q, want prod", cfg.CurrentContext)
	}
}

func TestContextNames_Sorted(t *testing.T) {
	cfg := &Config{Contexts: map[string]*Context{
		"prod":    {Controller: "u"},
		"alpha":   {Controller: "u"},
		"staging": {Controller: "u"},
	}}
	names := cfg.ContextNames()
	want := []string{"alpha", "prod", "staging"}
	if len(names) != len(want) {
		t.Fatalf("got %v, want %v", names, want)
	}
	for i, n := range names {
		if n != want[i] {
			t.Errorf("names[%d] = %q, want %q", i, n, want[i])
		}
	}
}
