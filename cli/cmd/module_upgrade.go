package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var (
	moduleUpgradeRegistry string
	moduleUpgradeAll      bool
)

var moduleUpgradeCmd = &cobra.Command{
	Use:   "upgrade [id]",
	Short: "Upgrade an installed module to the newest version a registry offers",
	Long: `Upgrade a platform module to the newest version advertised by a configured
registry (modules.registries) — a convenience over "install <id>@latest".

The controller's registry catalog reports, per module, the latest available
version and the currently installed version. "upgrade" installs the newer one
(pinned to that exact version) so the controller re-verifies its sha256 and
signature against its own trust root, exactly like a fresh install.

  prexorctl module upgrade stats-aggregator   # one module
  prexorctl module upgrade --all               # every module with a newer version

A module that is already up to date is left untouched. Use --registry to pin
one of the configured registries.`,
	Args: cobra.MaximumNArgs(1),
	RunE: runModuleUpgrade,
}

// registryCatalogEntry is the upgrade-relevant subset of a registry catalog row.
type registryCatalogEntry struct {
	moduleID         string
	version          string // newest version the registry advertises
	installed        bool
	installedVersion string
}

// upgradeDecision classifies a single module's upgrade state.
type upgradeDecision int

const (
	upgradeNotInRegistry upgradeDecision = iota
	upgradeNotInstalled
	upgradeUpToDate
	upgradeAvailable
)

// parseCatalogEntries projects the raw registry response rows onto the fields
// upgrade cares about. Pure so it can be unit-tested without a controller.
// Uses empty-string (not the "-" placeholder str() yields) for missing fields,
// so the "no advertised version" / "not yet installed" checks read cleanly.
func parseCatalogEntries(modules []map[string]any) []registryCatalogEntry {
	entries := make([]registryCatalogEntry, 0, len(modules))
	for _, m := range modules {
		installed, _ := m["installed"].(bool)
		entries = append(entries, registryCatalogEntry{
			moduleID:         mapString(m, "moduleId"),
			version:          mapString(m, "version"),
			installed:        installed,
			installedVersion: mapString(m, "installedVersion"),
		})
	}
	return entries
}

// mapString returns a string-typed map value, or "" when missing or not a string.
func mapString(m map[string]any, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

// decideUpgrade locates id in the catalog and classifies its upgrade state.
func decideUpgrade(catalog []registryCatalogEntry, id string) (registryCatalogEntry, upgradeDecision) {
	for _, e := range catalog {
		if e.moduleID != id {
			continue
		}
		switch {
		case !e.installed:
			return e, upgradeNotInstalled
		case e.version == "" || e.installedVersion == e.version:
			return e, upgradeUpToDate
		default:
			return e, upgradeAvailable
		}
	}
	return registryCatalogEntry{}, upgradeNotInRegistry
}

// selectUpgradable returns every installed module whose advertised version
// differs from the installed one — the work list for `upgrade --all`.
func selectUpgradable(catalog []registryCatalogEntry) []registryCatalogEntry {
	var pending []registryCatalogEntry
	for _, e := range catalog {
		if e.installed && e.version != "" && e.installedVersion != e.version {
			pending = append(pending, e)
		}
	}
	return pending
}

func fetchRegistryCatalog(client *api.Client) ([]registryCatalogEntry, []string, error) {
	var resp struct {
		Registries []string         `json:"registries"`
		Modules    []map[string]any `json:"modules"`
	}
	if err := client.GetWithQuery("/api/v1/modules/platform/registry", map[string]string{}, &resp); err != nil {
		return nil, nil, err
	}
	return parseCatalogEntries(resp.Modules), resp.Registries, nil
}

// installPinned asks the controller to (re)install the module at the catalog's
// advertised version. Same path as `module install`, so verification is identical.
func installPinned(client *api.Client, e registryCatalogEntry) (map[string]any, error) {
	body := map[string]any{"moduleId": e.moduleID, "version": e.version}
	if moduleUpgradeRegistry != "" {
		body["registryUrl"] = moduleUpgradeRegistry
	}
	var result map[string]any
	err := client.Post("/api/v1/modules/platform/registry/install", body, &result)
	return result, err
}

func runModuleUpgrade(cmd *cobra.Command, args []string) error {
	client, err := requireAuth()
	if err != nil {
		return err
	}
	if moduleUpgradeAll && len(args) > 0 {
		return fmt.Errorf("pass either a module id or --all, not both")
	}
	if !moduleUpgradeAll && len(args) == 0 {
		return fmt.Errorf("specify a module id to upgrade, or pass --all")
	}

	catalog, registries, err := fetchRegistryCatalog(client)
	if err != nil {
		return err
	}
	if len(registries) == 0 {
		theme.PrintWarn("No registries configured. Set modules.registries in controller.yml.")
		return nil
	}

	if moduleUpgradeAll {
		return upgradeAll(client, catalog)
	}
	return upgradeOne(client, catalog, args[0])
}

func upgradeOne(client *api.Client, catalog []registryCatalogEntry, id string) error {
	entry, decision := decideUpgrade(catalog, id)
	switch decision {
	case upgradeNotInRegistry:
		return fmt.Errorf("module %q is not offered by any configured registry", id)
	case upgradeNotInstalled:
		return fmt.Errorf("module %q is not installed — use `prexorctl module install %s`", id, id)
	case upgradeUpToDate:
		theme.PrintSuccess(fmt.Sprintf("Module %q is already up to date (%s).", id, entry.installedVersion))
		return nil
	}

	result, err := installPinned(client, entry)
	if err != nil {
		return err
	}
	if flagJSON {
		return theme.PrintJSON(result)
	}
	theme.PrintSuccess(fmt.Sprintf(
		"Module %q upgraded %s → %s.", entry.moduleID, entry.installedVersion, str(result, "version")))
	return nil
}

func upgradeAll(client *api.Client, catalog []registryCatalogEntry) error {
	pending := selectUpgradable(catalog)
	if len(pending) == 0 {
		if flagJSON {
			return theme.PrintJSON(map[string]any{"upgraded": []any{}})
		}
		theme.PrintSuccess("All installed modules are up to date.")
		return nil
	}

	results := make([]map[string]any, 0, len(pending))
	failed := 0
	for _, e := range pending {
		row := map[string]any{"moduleId": e.moduleID, "from": e.installedVersion, "to": e.version}
		if _, err := installPinned(client, e); err != nil {
			row["ok"] = false
			row["error"] = err.Error()
			failed++
			if !flagJSON {
				theme.PrintWarn(fmt.Sprintf("Module %q upgrade failed: %v", e.moduleID, err))
			}
		} else {
			row["ok"] = true
			if !flagJSON {
				theme.PrintSuccess(fmt.Sprintf("Module %q upgraded %s → %s.", e.moduleID, e.installedVersion, e.version))
			}
		}
		results = append(results, row)
	}

	if flagJSON {
		if err := theme.PrintJSON(map[string]any{"upgraded": results}); err != nil {
			return err
		}
	}
	if failed > 0 {
		return fmt.Errorf("%d of %d module upgrade(s) failed", failed, len(pending))
	}
	return nil
}

func init() {
	moduleUpgradeCmd.Flags().BoolVar(&moduleUpgradeAll, "all", false,
		"Upgrade every installed module that a configured registry offers a newer version for.")
	moduleUpgradeCmd.Flags().StringVar(&moduleUpgradeRegistry, "registry", "",
		"Pin one of the configured registry URLs instead of searching all of them.")
	moduleCmd.AddCommand(moduleUpgradeCmd)
}
