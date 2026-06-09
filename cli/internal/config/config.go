// Package config owns the on-disk prexorctl configuration at
// ~/.prexorcloud/config.yml. The file holds a list of named contexts
// (controller URL + token), a pointer to the active one, and a couple of
// global preferences (accent). Multi-context shape lets one prexorctl
// install talk to multiple controllers (dev / staging / prod) without
// reauthenticating per switch.
package config

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Context names a single controller + credential pair. Tokens are stored as
// plaintext JWTs protected by 0600 file permissions per ADR 27.
type Context struct {
	Controller string `yaml:"controller"`
	Token      string `yaml:"token,omitempty"`
}

// Config is the in-memory view of ~/.prexorcloud/config.yml after migration.
// Pre-context (flat) configs are migrated transparently on Load.
type Config struct {
	CurrentContext string              `yaml:"currentContext,omitempty"`
	Contexts       map[string]*Context `yaml:"contexts,omitempty"`
	// Accent picks the brand accent family: purple (default), cyan, green, amber.
	Accent string `yaml:"accent,omitempty"`
}

// rawConfig is the disk representation. Accepts both the v1 flat shape
// (controller:/token: at root) and the v2 shape with contexts. The migrate()
// pass folds v1 into a single "default" context.
type rawConfig struct {
	CurrentContext string              `yaml:"currentContext,omitempty"`
	Contexts       map[string]*Context `yaml:"contexts,omitempty"`
	Accent         string              `yaml:"accent,omitempty"`
	// v1 flat fields — kept only for backward-compat read.
	Controller string `yaml:"controller,omitempty"`
	Token      string `yaml:"token,omitempty"`
}

const defaultContextName = "default"

func Dir() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".prexorcloud")
}

func Path() string {
	return filepath.Join(Dir(), "config.yml")
}

func Load() (*Config, error) {
	return loadFromPath(Path())
}

// LoadFrom reads the config from <home>/.prexorcloud/config.yml. Used when
// the invoking user differs from the process user (e.g. setup running under
// sudo needs to read/write the original user's config).
func LoadFrom(home string) (*Config, error) {
	return loadFromPath(filepath.Join(home, ".prexorcloud", "config.yml"))
}

func loadFromPath(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return &Config{Contexts: map[string]*Context{}}, nil
		}
		return nil, err
	}
	raw := &rawConfig{}
	if err := yaml.Unmarshal(data, raw); err != nil {
		return nil, err
	}
	return raw.migrate(), nil
}

func (r *rawConfig) migrate() *Config {
	cfg := &Config{
		CurrentContext: r.CurrentContext,
		Contexts:       r.Contexts,
		Accent:         r.Accent,
	}
	if len(cfg.Contexts) == 0 && r.Controller != "" {
		cfg.Contexts = map[string]*Context{
			defaultContextName: {Controller: r.Controller, Token: r.Token},
		}
		if cfg.CurrentContext == "" {
			cfg.CurrentContext = defaultContextName
		}
	}
	if cfg.Contexts == nil {
		cfg.Contexts = map[string]*Context{}
	}
	return cfg
}

func (c *Config) Save() error {
	return c.saveTo(Path(), 0, 0)
}

// SaveAs writes the config to <home>/.prexorcloud/config.yml. If uid > 0 the
// directory and file are chowned to (uid, gid) — used by setup running under
// sudo so the resulting config belongs to the invoking user, not root.
func (c *Config) SaveAs(home string, uid, gid int) error {
	return c.saveTo(filepath.Join(home, ".prexorcloud", "config.yml"), uid, gid)
}

func (c *Config) saveTo(path string, uid, gid int) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	data, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		return err
	}
	if uid > 0 {
		_ = os.Chown(dir, uid, gid)
		_ = os.Chown(path, uid, gid)
	}
	return nil
}

// ContextRef returns the active Context for this invocation. flagContext (set
// from --context) wins over PREXOR_CONTEXT, which wins over the stored
// currentContext. Returns nil if no context resolves.
func (c *Config) ContextRef(flagContext string) *Context {
	if c == nil {
		return nil
	}
	name := c.SelectedContextName(flagContext)
	if name == "" {
		return nil
	}
	return c.Contexts[name]
}

// SelectedContextName returns the *name* that ContextRef would resolve.
// Useful for error messages and for `prexorctl context current`.
func (c *Config) SelectedContextName(flagContext string) string {
	if c == nil {
		return ""
	}
	if flagContext != "" {
		return flagContext
	}
	if env := os.Getenv("PREXOR_CONTEXT"); env != "" {
		return env
	}
	return c.CurrentContext
}

// Resolve returns the effective controller URL — flag > env > selected context.
func (c *Config) Resolve(flagController, flagContext string) string {
	if flagController != "" {
		return flagController
	}
	if env := os.Getenv("PREXOR_CONTROLLER"); env != "" {
		return env
	}
	if ctx := c.ContextRef(flagContext); ctx != nil {
		return ctx.Controller
	}
	return ""
}

// ResolveToken returns the effective token — flag > env > selected context.
func (c *Config) ResolveToken(flagToken, flagContext string) string {
	if flagToken != "" {
		return flagToken
	}
	if env := os.Getenv("PREXOR_TOKEN"); env != "" {
		return env
	}
	if ctx := c.ContextRef(flagContext); ctx != nil {
		return ctx.Token
	}
	return ""
}

// Upsert ensures a context with the given name exists and returns it.
func (c *Config) Upsert(name string) *Context {
	if c.Contexts == nil {
		c.Contexts = map[string]*Context{}
	}
	ctx, ok := c.Contexts[name]
	if !ok {
		ctx = &Context{}
		c.Contexts[name] = ctx
	}
	return ctx
}

// SetCurrentAuth writes controller + token into the current context, creating
// a "default" context (and setting currentContext to it) if none exists.
// Used by `prexorctl login` and any other write-from-credentials flow.
func (c *Config) SetCurrentAuth(controller, token string) {
	if c.CurrentContext == "" {
		c.CurrentContext = defaultContextName
	}
	ctx := c.Upsert(c.CurrentContext)
	ctx.Controller = controller
	ctx.Token = token
}

// CurrentContextController returns the stored controller URL for the active
// context without consulting flags or env. Returns "" if no context is set.
func (c *Config) CurrentContextController() string {
	if c == nil || c.CurrentContext == "" {
		return ""
	}
	if ctx, ok := c.Contexts[c.CurrentContext]; ok {
		return ctx.Controller
	}
	return ""
}

// CurrentContextToken returns the stored token for the active context without
// consulting flags or env. Returns "" if no context is set.
func (c *Config) CurrentContextToken() string {
	if c == nil || c.CurrentContext == "" {
		return ""
	}
	if ctx, ok := c.Contexts[c.CurrentContext]; ok {
		return ctx.Token
	}
	return ""
}

// Use sets the active context. Returns an error if the name is unknown.
func (c *Config) Use(name string) error {
	if _, ok := c.Contexts[name]; !ok {
		return fmt.Errorf("unknown context %q", name)
	}
	c.CurrentContext = name
	return nil
}

// Remove deletes a context. If it was the active one, currentContext is
// cleared. Returns an error if the context does not exist.
func (c *Config) Remove(name string) error {
	if _, ok := c.Contexts[name]; !ok {
		return fmt.Errorf("unknown context %q", name)
	}
	delete(c.Contexts, name)
	if c.CurrentContext == name {
		c.CurrentContext = ""
	}
	return nil
}

// ContextNames returns context names in stable (alphabetical) order, useful
// for tabular output where map iteration order would otherwise be random.
func (c *Config) ContextNames() []string {
	names := make([]string, 0, len(c.Contexts))
	for name := range c.Contexts {
		names = append(names, name)
	}
	// stable order without importing sort just for this — caller sorts if needed
	sortStrings(names)
	return names
}

func sortStrings(s []string) {
	// Insertion sort: contexts lists stay small (handful of entries); avoids
	// pulling in sort just for this. O(n^2) is irrelevant at this size.
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j-1] > s[j]; j-- {
			s[j-1], s[j] = s[j], s[j-1]
		}
	}
}
