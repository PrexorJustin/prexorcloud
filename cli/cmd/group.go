package cmd

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/huh"
	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var (
	groupListFilter string
	groupListSort   string
	groupListWatch  bool
)

var groupCmd = &cobra.Command{
	Use:   "group",
	Short: "Manage server groups",
}

var groupListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all groups",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		render := func() error {
			var groups []api.GroupResponse
			if err := client.GetList("/api/v1/groups", nil, &groups); err != nil {
				return err
			}

			if flagJSON {
				return theme.PrintJSON(groups)
			}

			groups = filterAndSortGroups(groups, groupListFilter, groupListSort)

			cluster := shortHost(cfg.Resolve(flagController, flagContext))
			filterDisplay := groupListFilter
			if filterDisplay == "" {
				filterDisplay = "(none)"
			}
			sortDisplay := groupListSort
			if sortDisplay == "" {
				sortDisplay = "name"
			}

			fmt.Printf("%s %s  %s filter: %s  %s sort: %s\n\n",
				theme.Heading("Listing groups in cluster"),
				theme.Code(cluster),
				theme.Bullet(),
				theme.StyleDim().Render(filterDisplay),
				theme.Bullet(),
				theme.StyleDim().Render(sortDisplay),
			)

			fmt.Println(groupsListTable(groups))
			fmt.Println()
			fmt.Println(groupsListFooter(groups))
			return nil
		}

		if !groupListWatch {
			return render()
		}

		// --watch: re-render every 2s. Simple loop; clear screen between frames.
		for {
			fmt.Print("\033[2J\033[H") // clear screen, home cursor
			if err := render(); err != nil {
				return err
			}
			time.Sleep(2 * time.Second)
		}
	},
}

var groupInfoCmd = &cobra.Command{
	Use:   "info <name>",
	Short: "Show group details",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var group map[string]any
		if err := client.Get("/api/v1/groups/"+args[0], &group); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(group)
		}

		return runGroupInfo(cmd.Context(), client, args[0], group)
	},
}

var groupCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a new group",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		flags := cmd.Flags()
		name, _ := flags.GetString("name")
		platform, _ := flags.GetString("platform")
		platformVersion, _ := flags.GetString("platform-version")
		templates, _ := flags.GetStringSlice("template")
		scalingMode, _ := flags.GetString("scaling-mode")
		minInst, _ := flags.GetInt("min")
		maxInst, _ := flags.GetInt("max")
		memory, _ := flags.GetInt("memory")
		routing, _ := flags.GetString("routing")
		portStart, _ := flags.GetInt("port-start")
		portEnd, _ := flags.GetInt("port-end")

		body := map[string]any{
			"name":            name,
			"platform":        platform,
			"platformVersion": platformVersion,
			"jarFile":         "server.jar",
			"templates":       templates,
			"scalingMode":     scalingMode,
			"minInstances":    minInst,
			"maxInstances":    maxInst,
			"memoryMb":        memory,
			"routing":         routing,
			"portRange":       map[string]any{"start": portStart, "end": portEnd},
		}

		var result map[string]any
		if err := client.Post("/api/v1/groups", body, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Group '%s' created", name))
		return nil
	},
}

var groupUpdateCmd = &cobra.Command{
	Use:   "update <name>",
	Short: "Update a group",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		body := map[string]any{}
		flags := cmd.Flags()
		if flags.Changed("min") {
			v, _ := flags.GetInt("min")
			body["minInstances"] = v
		}
		if flags.Changed("max") {
			v, _ := flags.GetInt("max")
			body["maxInstances"] = v
		}
		if flags.Changed("memory") {
			v, _ := flags.GetInt("memory")
			body["memoryMb"] = v
		}
		if flags.Changed("routing") {
			v, _ := flags.GetString("routing")
			body["routing"] = v
		}
		if flags.Changed("scaling-mode") {
			v, _ := flags.GetString("scaling-mode")
			body["scalingMode"] = v
		}

		var result map[string]any
		if err := client.Patch("/api/v1/groups/"+args[0], body, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Group '%s' updated", args[0]))
		return nil
	},
}

var groupDeleteCmd = &cobra.Command{
	Use:   "delete <name>",
	Short: "Delete a group",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var confirm bool
		if err := huh.NewConfirm().
			Title(fmt.Sprintf("Delete group '%s'?", args[0])).
			Description("This will stop all running instances in the group. This action cannot be undone.").
			Value(&confirm).
			WithTheme(tui.HuhTheme()).
			Run(); err != nil {
			return err
		}
		if !confirm {
			fmt.Println("Cancelled.")
			return nil
		}

		if err := client.Delete("/api/v1/groups/"+args[0], nil); err != nil {
			return err
		}

		theme.PrintSuccess(fmt.Sprintf("Group '%s' deleted", args[0]))
		return nil
	},
}

var groupMaintenanceCmd = &cobra.Command{
	Use:   "maintenance <name> <on|off>",
	Short: "Toggle maintenance mode",
	Args:  cobra.ExactArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		enabled := args[1] == "on" || args[1] == "true" || args[1] == "1"
		body := map[string]any{"maintenance": enabled}

		var result map[string]any
		if err := client.Patch("/api/v1/groups/"+args[0], body, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		state := "disabled"
		if enabled {
			state = "enabled"
		}
		theme.PrintSuccess(fmt.Sprintf("Maintenance %s for group '%s'", state, args[0]))
		return nil
	},
}

func init() {
	groupCreateCmd.Flags().String("name", "", "Group name (required)")
	groupCreateCmd.Flags().String("platform", "", "Platform (e.g., paper, velocity)")
	groupCreateCmd.Flags().String("platform-version", "", "Platform version")
	groupCreateCmd.Flags().StringSlice("template", nil, "Template names (ordered layers)")
	groupCreateCmd.Flags().String("scaling-mode", "DYNAMIC", "Scaling mode: STATIC, DYNAMIC, MANUAL")
	groupCreateCmd.Flags().Int("min", 1, "Minimum instances")
	groupCreateCmd.Flags().Int("max", 10, "Maximum instances")
	groupCreateCmd.Flags().Int("memory", 1024, "Memory per instance (MB)")
	groupCreateCmd.Flags().String("routing", "LOWEST_PLAYERS", "Routing strategy")
	groupCreateCmd.Flags().Int("port-start", 30000, "Port range start")
	groupCreateCmd.Flags().Int("port-end", 30100, "Port range end")
	_ = groupCreateCmd.MarkFlagRequired("name")
	_ = groupCreateCmd.MarkFlagRequired("platform")

	groupUpdateCmd.Flags().Int("min", 0, "Minimum instances")
	groupUpdateCmd.Flags().Int("max", 0, "Maximum instances")
	groupUpdateCmd.Flags().Int("memory", 0, "Memory per instance (MB)")
	groupUpdateCmd.Flags().String("routing", "", "Routing strategy")
	groupUpdateCmd.Flags().String("scaling-mode", "", "Scaling mode: STATIC, DYNAMIC, MANUAL")

	groupListCmd.Flags().StringVar(&groupListFilter, "filter", "", "Filter groups by substring match on name")
	groupListCmd.Flags().StringVar(&groupListSort, "sort", "name", "Sort by: name, players, instances")
	groupListCmd.Flags().BoolVar(&groupListWatch, "watch", false, "Re-render every 2s")

	groupCmd.AddCommand(groupListCmd, groupInfoCmd, groupCreateCmd, groupUpdateCmd, groupDeleteCmd, groupMaintenanceCmd)
}

// filterAndSortGroups applies --filter and --sort to a group list.
func filterAndSortGroups(groups []api.GroupResponse, filter, sortBy string) []api.GroupResponse {
	if filter != "" {
		out := groups[:0]
		for _, g := range groups {
			if strings.Contains(strings.ToLower(g.Name), strings.ToLower(filter)) {
				out = append(out, g)
			}
		}
		groups = out
	}
	switch sortBy {
	case "players":
		sortGroupsBy(groups, func(a, b api.GroupResponse) bool { return a.TotalPlayers > b.TotalPlayers })
	case "instances":
		sortGroupsBy(groups, func(a, b api.GroupResponse) bool { return a.RunningInstances > b.RunningInstances })
	default:
		sortGroupsBy(groups, func(a, b api.GroupResponse) bool { return a.Name < b.Name })
	}
	return groups
}

func sortGroupsBy(groups []api.GroupResponse, less func(a, b api.GroupResponse) bool) {
	for i := 1; i < len(groups); i++ {
		for j := i; j > 0 && less(groups[j], groups[j-1]); j-- {
			groups[j], groups[j-1] = groups[j-1], groups[j]
		}
	}
}

func groupStatus(g api.GroupResponse) string {
	if g.Maintenance {
		return "DRAIN"
	}
	if g.RunningInstances == 0 && g.MinInstances > 0 {
		return "DOWN"
	}
	return "UP"
}

func groupsListTable(groups []api.GroupResponse) string {
	headers := []string{"GROUP", "TYPE", "STATUS", "INSTANCES", "PLAYERS", "VERSION", "UPDATED"}
	rows := make([][]string, 0, len(groups))
	for _, g := range groups {
		typ := "GAME"
		if g.Static {
			typ = "STATIC"
		}
		rows = append(rows, []string{
			theme.Code(g.Name),
			theme.StyleDim().Render(typ),
			theme.StatusPill(groupStatus(g)),
			fmt.Sprintf("%d/%d", g.RunningInstances, g.MaxInstances),
			theme.Number(fmt.Sprintf("%d", g.TotalPlayers)),
			theme.StyleDim().Render(g.Platform + "-" + g.PlatformVersion),
			theme.StyleMute().Render("just now"),
		})
	}
	return tui.Table(headers, rows)
}

func groupsListFooter(groups []api.GroupResponse) string {
	up, drain, down := 0, 0, 0
	for _, g := range groups {
		switch groupStatus(g) {
		case "UP":
			up++
		case "DRAIN":
			drain++
		case "DOWN":
			down++
		}
	}
	return theme.Hint(fmt.Sprintf("%d groups  %s  %d up  %s  %d draining  %s  %d down",
		len(groups), theme.Bullet(), up, theme.Bullet(), drain, theme.Bullet(), down))
}

// runGroupInfo builds and runs the interactive group-info bubbletea view, then
// — if the user pressed ↵ — hands off to the instance console.
func runGroupInfo(ctx context.Context, client *api.Client, name string, group map[string]any) error {
	fetchInstances := func() ([]tui.GroupInstance, error) {
		var instances []api.InstanceResponse
		if err := client.Get("/api/v1/services?group="+name, &instances); err != nil {
			return nil, err
		}
		return toGroupInstances(instances), nil
	}

	insts, _ := fetchInstances()

	status := "UP"
	if m, _ := group["maintenance"].(bool); m {
		status = "DRAIN"
	}
	typ := "GAME"
	if s, _ := group["static"].(bool); s {
		typ = "STATIC"
	}

	templatesText := strOrDash("")
	if t, ok := group["templates"].([]any); ok && len(t) > 0 {
		parts := make([]string, len(t))
		for i, v := range t {
			parts[i] = fmt.Sprintf("%v", v)
		}
		templatesText = strings.Join(parts, " "+theme.Arrow()+" ")
	}

	cfgKV := [][2]string{
		{"image", theme.FG(theme.Fg).Render(str(group, "platform") + " " + str(group, "platformVersion"))},
		{"memory", theme.FG(theme.Fg).Render(fmt.Sprintf("%.0f MB", num(group, "memoryMb")))},
		{"routing", theme.FG(theme.Fg).Render(str(group, "routing"))},
	}
	scalingKV := [][2]string{
		{"mode", theme.StyleCyan().Render(str(group, "scalingMode"))},
		{"min replicas", theme.StyleCyan().Render(fmt.Sprintf("%.0f", num(group, "minInstances")))},
		{"max replicas", theme.StyleCyan().Render(fmt.Sprintf("%.0f", num(group, "maxInstances")))},
		{"max players", theme.StyleCyan().Render(fmt.Sprintf("%.0f", num(group, "maxPlayers")))},
	}
	templKV := [][2]string{
		{"templates", theme.StyleBrand().Render(templatesText)},
		{"update", theme.FG(theme.Fg).Render(strOrDash(str(group, "updateStrategy")))},
		{"parent", theme.StyleDim().Render(strOrDash(str(group, "parent")))},
	}

	m := tui.NewGroupInfo(tui.GroupInfoConfig{
		Name:      name,
		Type:      typ,
		Status:    status,
		Cluster:   shortHost(cfg.Resolve(flagController, flagContext)),
		Version:   cliVersion,
		ConfigKV:  cfgKV,
		ScalingKV: scalingKV,
		TemplKV:   templKV,
		Events:    groupInfoEvents(insts),
		Instances: insts,
		// `d` drain → graceful stop. `r` restart → force-stop; the group's
		// scheduler respawns the instance, which is the closest analog to a
		// restart given the controller exposes no per-instance restart verb.
		OnDrain: func(id string) error {
			return client.Post("/api/v1/services/"+id+"/stop", nil, nil)
		},
		OnRestart: func(id string) error {
			return client.Post("/api/v1/services/"+id+"/force-stop", nil, nil)
		},
		Refresh: fetchInstances,
	})

	attachID, err := m.Run()
	if err != nil {
		return err
	}
	if attachID != "" {
		return attachConsole(ctx, client, attachID)
	}
	return nil
}

// toGroupInstances maps API instance records onto the group-info table rows.
// TPS / MEM / MATCH aren't served per-instance yet, so they're dashed.
func toGroupInstances(instances []api.InstanceResponse) []tui.GroupInstance {
	out := make([]tui.GroupInstance, 0, len(instances))
	for _, in := range instances {
		out = append(out, tui.GroupInstance{
			Name:    in.ID,
			Node:    in.Node,
			Status:  in.State,
			Players: fmt.Sprintf("%d", in.PlayerCount),
			TPS:     theme.Glyph("—", "-"),
			Mem:     theme.Glyph("—", "-"),
			Uptime:  formatUptime(float64(in.UptimeMs)),
			Match:   theme.Glyph("—", "-"),
		})
	}
	return out
}

// groupInfoEvents synthesizes a RECENT EVENTS list from instance uptimes —
// the controller has no per-group event feed, so the freshest instances stand
// in for "what changed recently".
func groupInfoEvents(insts []tui.GroupInstance) []string {
	var events []string
	for _, in := range insts {
		if len(events) >= 4 {
			break
		}
		dot := theme.StyleGreen().Render(theme.DotUp())
		events = append(events, fmt.Sprintf("%s %s  %s %s %s",
			theme.StyleMute().Render(padRight(in.Uptime, 8)),
			dot,
			theme.StyleDim().Render("instance"),
			theme.Code(in.Name),
			theme.StyleDim().Render("running on "+in.Node),
		))
	}
	if len(events) == 0 {
		events = append(events, theme.StyleMute().Render("no instances running"))
	}
	return events
}
