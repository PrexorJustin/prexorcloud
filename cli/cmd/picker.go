package cmd

import (
	"errors"
	"fmt"
	"os"

	"github.com/charmbracelet/huh"
	"github.com/mattn/go-isatty"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

// Interactive resource pickers. When a command that needs a resource name is
// run without one in a real terminal, we pop a styled huh selector (same theme
// as the setup wizard) listing live resources instead of erroring. In a
// non-TTY (scripts, pipes) or --json mode we return a usage error so automation
// still fails fast and predictably.

// interactive reports whether a picker can be shown.
func interactive() bool {
	if flagJSON {
		return false
	}
	return isatty.IsTerminal(os.Stdin.Fd()) && isatty.IsTerminal(os.Stdout.Fd())
}

// pickOne shows a styled single-select and returns the chosen value.
func pickOne(title string, options []huh.Option[string]) (string, error) {
	var choice string
	if err := huh.NewSelect[string]().
		Title(title).
		Options(options...).
		Value(&choice).
		WithTheme(tui.HuhTheme()).
		Run(); err != nil {
		return "", err
	}
	return choice, nil
}

// resolveArg returns args[0] when present; otherwise it runs pick interactively
// (TTY only). Outside a TTY it returns the usage error unchanged.
func resolveArg(args []string, usage string, pick func() (string, error)) (string, error) {
	if len(args) > 0 {
		return args[0], nil
	}
	if !interactive() {
		return "", errors.New(usage)
	}
	return pick()
}

func pickGroup(c *api.Client, title string) (string, error) {
	var groups []api.GroupResponse
	if err := c.GetList("/api/v1/groups", nil, &groups); err != nil {
		return "", err
	}
	if len(groups) == 0 {
		return "", errors.New("no groups exist yet")
	}
	opts := make([]huh.Option[string], 0, len(groups))
	for _, g := range groups {
		label := fmt.Sprintf("%-24s %s %s · %d/%d up", g.Name, g.Platform, g.PlatformVersion, g.RunningInstances, g.MaxInstances)
		if g.Maintenance {
			label += " · maintenance"
		}
		opts = append(opts, huh.NewOption(label, g.Name))
	}
	return pickOne(title, opts)
}

func pickNode(c *api.Client, title string) (string, error) {
	var nodes []api.NodeResponse
	if err := c.GetList("/api/v1/nodes", nil, &nodes); err != nil {
		return "", err
	}
	opts := make([]huh.Option[string], 0, len(nodes))
	for _, n := range nodes {
		id := n.ID
		if id == "" {
			id = n.NodeID
		}
		if id == "" {
			continue
		}
		opts = append(opts, huh.NewOption(fmt.Sprintf("%-24s %s · %d instances", id, n.Status, n.InstanceCount), id))
	}
	if len(opts) == 0 {
		return "", errors.New("no nodes registered")
	}
	return pickOne(title, opts)
}

func pickInstance(c *api.Client, title string) (string, error) {
	var instances []api.InstanceResponse
	if err := c.GetList("/api/v1/services", nil, &instances); err != nil {
		return "", err
	}
	opts := make([]huh.Option[string], 0, len(instances))
	for _, i := range instances {
		if i.ID == "" {
			continue
		}
		label := fmt.Sprintf("%-20s %s · %s", i.ID, i.Group, i.State)
		if i.Port > 0 {
			label += fmt.Sprintf(" · :%d", i.Port)
		}
		opts = append(opts, huh.NewOption(label, i.ID))
	}
	if len(opts) == 0 {
		return "", errors.New("no instances running")
	}
	return pickOne(title, opts)
}

func pickCrash(c *api.Client, title string) (string, error) {
	var crashes []map[string]any
	if err := c.GetList("/api/v1/crashes", nil, &crashes); err != nil {
		return "", err
	}
	opts := make([]huh.Option[string], 0, len(crashes))
	for _, cr := range crashes {
		id := str(cr, "id")
		if id == "" {
			continue
		}
		label := fmt.Sprintf("%-20s %s · %s", str(cr, "instanceId"), str(cr, "classification"), str(cr, "crashedAt"))
		opts = append(opts, huh.NewOption(label, id))
	}
	if len(opts) == 0 {
		return "", errors.New("no crashes recorded")
	}
	return pickOne(title, opts)
}

func pickContext(title string) (string, error) {
	opts := make([]huh.Option[string], 0, len(cfg.Contexts))
	for name, ctx := range cfg.Contexts {
		label := name
		if ctx != nil && ctx.Controller != "" {
			label = fmt.Sprintf("%-16s %s", name, ctx.Controller)
		}
		if name == cfg.CurrentContext {
			label += " (current)"
		}
		opts = append(opts, huh.NewOption(label, name))
	}
	if len(opts) == 0 {
		return "", errors.New("no contexts configured")
	}
	return pickOne(title, opts)
}

func pickCatalogVersion(c *api.Client, platform, title string) (string, error) {
	var entries []api.CatalogEntry
	if err := c.GetList("/api/v1/catalog", nil, &entries); err != nil {
		return "", err
	}
	opts := make([]huh.Option[string], 0)
	for _, e := range entries {
		if !equalFold(e.Platform, platform) {
			continue
		}
		label := e.Version
		if e.Recommended {
			label += "  (recommended)"
		}
		opts = append(opts, huh.NewOption(label, e.Version))
	}
	if len(opts) == 0 {
		return "", fmt.Errorf("no versions for platform %q", platform)
	}
	return pickOne(title, opts)
}

func pickCatalogPlatform(c *api.Client, title string) (string, error) {
	var entries []api.CatalogEntry
	if err := c.GetList("/api/v1/catalog", nil, &entries); err != nil {
		return "", err
	}
	order := make([]string, 0)
	seen := map[string]bool{}
	for _, e := range entries {
		if !seen[e.Platform] {
			seen[e.Platform] = true
			order = append(order, e.Platform)
		}
	}
	if len(order) == 0 {
		return "", errors.New("catalog is empty")
	}
	opts := make([]huh.Option[string], 0, len(order))
	for _, p := range order {
		opts = append(opts, huh.NewOption(p, p))
	}
	return pickOne(title, opts)
}
