package cmd

import (
	"fmt"
	"time"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/spf13/cobra"
)

// Dynamic shell completions: resolve live resource names (groups, nodes,
// instances, catalog platforms/versions) from the cluster so `pc group delete
// <TAB>` suggests real names. Each candidate carries a "value\tdescription"
// pair — zsh and fish render the description as an aligned, informative menu;
// bash shows the value. Every helper fails silently (completion must never
// block the shell or print errors) and uses a short timeout so an unreachable
// controller degrades to no suggestions instead of a hang.

const completionTimeout = 3 * time.Second

// completionClient builds an authenticated client with a short timeout, or nil
// when no controller/token is configured (in which case completion yields
// nothing rather than erroring).
func completionClient() *api.Client {
	c, err := newClient()
	if err != nil || c.Token == "" {
		return nil
	}
	c.HTTPClient.Timeout = completionTimeout
	return c
}

// withDesc formats a "value\tdescription" completion candidate, omitting the
// tab when there is no description.
func withDesc(value, desc string) string {
	if desc == "" {
		return value
	}
	return value + "\t" + desc
}

func completeGroupNames(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) != 0 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	c := completionClient()
	if c == nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	var groups []api.GroupResponse
	if err := c.GetList("/api/v1/groups", nil, &groups); err != nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	out := make([]string, 0, len(groups))
	for _, g := range groups {
		desc := fmt.Sprintf("%s %s · %d/%d up", g.Platform, g.PlatformVersion, g.RunningInstances, g.MaxInstances)
		if g.Maintenance {
			desc += " · maintenance"
		}
		out = append(out, withDesc(g.Name, desc))
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

// completeGroupNameThenOnOff completes a group name at position 0 and on/off at
// position 1 (for `group maintenance <name> <on|off>`).
func completeGroupNameThenOnOff(cmd *cobra.Command, args []string, toComplete string) ([]string, cobra.ShellCompDirective) {
	if len(args) == 1 {
		return []string{"on\tenable maintenance mode", "off\tdisable maintenance mode"}, cobra.ShellCompDirectiveNoFileComp
	}
	return completeGroupNames(cmd, args, toComplete)
}

func completeNodeIDs(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) != 0 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	c := completionClient()
	if c == nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	var nodes []api.NodeResponse
	if err := c.GetList("/api/v1/nodes", nil, &nodes); err != nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	out := make([]string, 0, len(nodes))
	for _, n := range nodes {
		// The API returns the node id under "id"; "nodeId" is legacy/empty.
		id := n.ID
		if id == "" {
			id = n.NodeID
		}
		if id == "" {
			continue
		}
		desc := fmt.Sprintf("%s · %d instances", n.Status, n.InstanceCount)
		out = append(out, withDesc(id, desc))
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

func completeInstanceIDs(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) != 0 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	c := completionClient()
	if c == nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	var instances []api.InstanceResponse
	if err := c.GetList("/api/v1/services", nil, &instances); err != nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	out := make([]string, 0, len(instances))
	for _, i := range instances {
		if i.ID == "" {
			continue
		}
		desc := fmt.Sprintf("%s · %s", i.Group, i.State)
		if i.Port > 0 {
			desc += fmt.Sprintf(" · :%d", i.Port)
		}
		out = append(out, withDesc(i.ID, desc))
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

func completeCrashIDs(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) != 0 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	c := completionClient()
	if c == nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	var crashes []map[string]any
	if err := c.GetList("/api/v1/crashes", nil, &crashes); err != nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	out := make([]string, 0, len(crashes))
	for _, cr := range crashes {
		id := str(cr, "id")
		if id == "" {
			continue
		}
		desc := fmt.Sprintf("%s · %s", str(cr, "instanceId"), str(cr, "classification"))
		out = append(out, withDesc(id, desc))
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

// completeContextNames completes locally-configured context names — no API call.
func completeContextNames(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) != 0 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	out := make([]string, 0, len(cfg.Contexts))
	for name, ctx := range cfg.Contexts {
		desc := ""
		if ctx != nil {
			desc = ctx.Controller
		}
		if name == cfg.CurrentContext {
			if desc == "" {
				desc = "current"
			} else {
				desc += " (current)"
			}
		}
		out = append(out, withDesc(name, desc))
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

// completeCatalogPlatformThenVersion completes a platform at position 0 and one
// of that platform's versions at position 1.
func completeCatalogPlatformThenVersion(_ *cobra.Command, args []string, _ string) ([]string, cobra.ShellCompDirective) {
	if len(args) > 1 {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	c := completionClient()
	if c == nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	var entries []api.CatalogEntry
	if err := c.GetList("/api/v1/catalog", nil, &entries); err != nil {
		return nil, cobra.ShellCompDirectiveNoFileComp
	}

	if len(args) == 0 {
		order := make([]string, 0)
		count := map[string]int{}
		category := map[string]string{}
		for _, e := range entries {
			if _, seen := count[e.Platform]; !seen {
				order = append(order, e.Platform)
			}
			count[e.Platform]++
			category[e.Platform] = e.Category
		}
		out := make([]string, 0, len(order))
		for _, p := range order {
			out = append(out, withDesc(p, fmt.Sprintf("%s · %d version(s)", category[p], count[p])))
		}
		return out, cobra.ShellCompDirectiveNoFileComp
	}

	// args[0] is the platform — suggest its versions (platform match is
	// case-insensitive, mirroring how the controller resolves the catalog).
	out := make([]string, 0)
	for _, e := range entries {
		if equalFold(e.Platform, args[0]) {
			desc := ""
			if e.Recommended {
				desc = "recommended"
			}
			out = append(out, withDesc(e.Version, desc))
		}
	}
	return out, cobra.ShellCompDirectiveNoFileComp
}

func equalFold(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := 0; i < len(a); i++ {
		ca, cb := a[i], b[i]
		if 'A' <= ca && ca <= 'Z' {
			ca += 'a' - 'A'
		}
		if 'A' <= cb && cb <= 'Z' {
			cb += 'a' - 'A'
		}
		if ca != cb {
			return false
		}
	}
	return true
}

func init() {
	// Groups
	groupInfoCmd.ValidArgsFunction = completeGroupNames
	groupUpdateCmd.ValidArgsFunction = completeGroupNames
	groupDeleteCmd.ValidArgsFunction = completeGroupNames
	groupMaintenanceCmd.ValidArgsFunction = completeGroupNameThenOnOff

	// Nodes
	nodeInfoCmd.ValidArgsFunction = completeNodeIDs
	nodeDrainCmd.ValidArgsFunction = completeNodeIDs
	nodeUndrainCmd.ValidArgsFunction = completeNodeIDs

	// Instances (start takes a group name; the rest take instance IDs)
	instanceInfoCmd.ValidArgsFunction = completeInstanceIDs
	instanceStartCmd.ValidArgsFunction = completeGroupNames
	instanceStopCmd.ValidArgsFunction = completeInstanceIDs
	instanceExecCmd.ValidArgsFunction = completeInstanceIDs
	instanceConsoleCmd.ValidArgsFunction = completeInstanceIDs

	crashInfoCmd.ValidArgsFunction = completeCrashIDs

	// Contexts (local only)
	contextUseCmd.ValidArgsFunction = completeContextNames
	contextRemoveCmd.ValidArgsFunction = completeContextNames

	// Catalog
	catalogUpdateCmd.ValidArgsFunction = completeCatalogPlatformThenVersion
	catalogRecommendCmd.ValidArgsFunction = completeCatalogPlatformThenVersion
	catalogRemoveCmd.ValidArgsFunction = completeCatalogPlatformThenVersion
}
