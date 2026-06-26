package cmd

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var deployCmd = &cobra.Command{
	Use:   "deploy <group>",
	Short: "Roll out a new deployment for a group",
	Long: `Trigger a rolling deployment that propagates the group's current template
chain and module composition to running instances.

Rollout flags map to the controller's deployment rollout config: strategy,
batch size, canary, health gate, auto-rollback, and timeouts. Omitted flags
fall back to the group's update-strategy defaults.`,
	Args: cobra.ExactArgs(1),
	RunE: runDeployTrigger,
}

func runDeployTrigger(cmd *cobra.Command, args []string) error {
	client, err := requireAuth()
	if err != nil {
		return err
	}
	group := args[0]

	body := buildDeployBody(cmd)
	var sendBody any
	if len(body) > 0 {
		sendBody = body
	}

	// --json: trigger and emit the deployment record, no TUI.
	if flagJSON {
		var result map[string]any
		if err := client.Post("/api/v1/groups/"+group+"/deploy", sendBody, &result); err != nil {
			return err
		}
		return theme.PrintJSON(result)
	}

	// PLAN preview built from the group's current config + the rollout knobs.
	var grp map[string]any
	_ = client.Get("/api/v1/groups/"+group, &grp)
	strategy, canary, batch := deployFlagValues(cmd)
	printDeployPlan(group, grp, strategy, canary, batch)

	yes, _ := cmd.Flags().GetBool("yes")
	if !yes {
		ok, err := confirmRollout()
		if err != nil {
			return err
		}
		if !ok {
			fmt.Println("  " + theme.StyleMute().Render("Cancelled."))
			return nil
		}
	}

	// Trigger the rollout.
	var result map[string]any
	if err := client.Post("/api/v1/groups/"+group+"/deploy", sendBody, &result); err != nil {
		return err
	}
	rev := int(num(result, "revision"))

	fmt.Println()

	// Live rollout view, polling the deployment record.
	image := str(grp, "platform") + "-" + str(grp, "platformVersion")
	m := tui.NewDeploy(tui.DeployConfig{
		Group:   group,
		Cluster: shortHost(cfg.Resolve(flagController, flagContext)),
		Version: cliVersion,
		Canary:  canary,
		Image:   image,
		Poll: func() (tui.DeployStatus, error) {
			var d map[string]any
			path := fmt.Sprintf("/api/v1/groups/%s/deployments/%d", group, rev)
			if err := client.Get(path, &d); err != nil {
				return tui.DeployStatus{}, err
			}
			return tui.DeployStatus{
				Revision:    int(num(d, "revision")),
				Strategy:    str(d, "strategy"),
				State:       str(d, "state"),
				Updated:     int(num(d, "updatedInstances")),
				Total:       int(num(d, "totalInstances")),
				CreatedAt:   str(d, "createdAt"),
				CompletedAt: str(d, "completedAt"),
			}, nil
		},
	})
	return m.Run()
}

// runDeployTrigger uses theme/tui only; the shared client comes from requireAuth.

// deployFlagValues resolves the effective strategy / canary / batch values for
// the PLAN preview (flags override; otherwise the group default applies).
func deployFlagValues(cmd *cobra.Command) (strategy string, canary, batch int) {
	strategy, _ = cmd.Flags().GetString("strategy")
	if strategy == "" {
		strategy = "rolling"
	}
	canary, _ = cmd.Flags().GetInt("canary-instances")
	batch, _ = cmd.Flags().GetInt("batch-size")
	return strategy, canary, batch
}

// printDeployPlan renders the design's PLAN block from the group's current
// config. The controller has no dry-run/diff endpoint, so the plan shows the
// rollout parameters and current target rather than a prev→next diff.
func printDeployPlan(group string, grp map[string]any, strategy string, canary, batch int) {
	fmt.Println()
	fmt.Println("  " + theme.StyleDim().Render("Reading deploy plan for group ") + theme.Code(group))
	fmt.Println()

	healthcheck := "on"
	planLine := fmt.Sprintf("  %s  group %s  %s strategy %s  %s canary %s  %s healthcheck %s",
		theme.StyleBrand().Bold(true).Render("PLAN"),
		theme.Code(group),
		theme.Bullet(), theme.Code(strings.ToLower(strategy)),
		theme.Bullet(), theme.Number(fmt.Sprintf("%d", canary)),
		theme.Bullet(), theme.Code(healthcheck),
	)
	fmt.Println(planLine)
	fmt.Println()

	image := str(grp, "platform") + "-" + str(grp, "platformVersion")
	templates := strOrDash("")
	if t, ok := grp["templates"].([]any); ok && len(t) > 0 {
		parts := make([]string, len(t))
		for i, v := range t {
			parts[i] = fmt.Sprintf("%v", v)
		}
		templates = strings.Join(parts, " "+theme.Arrow()+" ")
	}
	batchStr := "group default"
	if batch > 0 {
		batchStr = fmt.Sprintf("%d", batch)
	}
	bullet := func(k, v string) {
		fmt.Printf("    %s %s  %s\n", theme.StyleMute().Render(theme.Bullet()),
			theme.StyleDim().Render(padRight(k, 12)), v)
	}
	bullet("image", theme.FG(theme.Fg).Render(image))
	bullet("templates", theme.StyleBrand().Render(templates))
	bullet("batch size", theme.StyleCyan().Render(batchStr))
	bullet("scaling", theme.StyleDim().Render(str(grp, "scalingMode")))
	fmt.Println()
}

// confirmRollout shows the `[y/N]` prompt and reads the user's answer.
func confirmRollout() (bool, error) {
	fmt.Print("  " + theme.StyleDim().Render("Confirm rollout? ") + theme.StyleMute().Render("[y/N] "))
	reader := bufio.NewReader(os.Stdin)
	line, err := reader.ReadString('\n')
	if err != nil && line == "" {
		return false, nil
	}
	ans := strings.ToLower(strings.TrimSpace(line))
	return ans == "y" || ans == "yes", nil
}

func buildDeployBody(cmd *cobra.Command) map[string]any {
	body := map[string]any{}
	flags := cmd.Flags()

	if flags.Changed("strategy") {
		v, _ := flags.GetString("strategy")
		body["strategy"] = v
	}
	if flags.Changed("batch-size") {
		v, _ := flags.GetInt("batch-size")
		body["batchSize"] = v
	}
	if flags.Changed("canary-instances") {
		v, _ := flags.GetInt("canary-instances")
		body["canaryInstances"] = v
	}
	if flags.Changed("canary-percent") {
		v, _ := flags.GetInt("canary-percent")
		body["canaryPercent"] = v
	}
	if flags.Changed("health-gate") {
		v, _ := flags.GetBool("health-gate")
		body["healthGateEnabled"] = v
	}
	if flags.Changed("auto-rollback") {
		v, _ := flags.GetBool("auto-rollback")
		body["autoRollbackOnFailure"] = v
	}
	if flags.Changed("promotion-timeout") {
		v, _ := flags.GetInt64("promotion-timeout")
		body["promotionTimeoutSeconds"] = v
	}
	if flags.Changed("min-healthy") {
		v, _ := flags.GetInt64("min-healthy")
		body["minHealthySeconds"] = v
	}
	if flags.Changed("min-healthy-tps") {
		v, _ := flags.GetFloat64("min-healthy-tps")
		body["minHealthyTps"] = v
	}
	return body
}

var deployListCmd = &cobra.Command{
	Use:   "list <group>",
	Short: "List deployment history for a group",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		page, _ := cmd.Flags().GetInt("page")
		pageSize, _ := cmd.Flags().GetInt("page-size")
		params := map[string]string{
			"page":     strconv.Itoa(page),
			"pageSize": strconv.Itoa(pageSize),
		}

		var resp map[string]any
		if err := client.GetWithQuery("/api/v1/groups/"+args[0]+"/deployments", params, &resp); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(resp)
		}

		items, _ := resp["items"].([]any)
		if len(items) == 0 {
			fmt.Println("No deployments found.")
			return nil
		}

		headers := []string{"REV", "STATE", "STRATEGY", "TRIGGER", "PROGRESS", "CREATED"}
		rows := make([][]string, 0, len(items))
		for _, raw := range items {
			d, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			updated := num(d, "updatedInstances")
			total := num(d, "totalInstances")
			progress := fmt.Sprintf("%.0f/%.0f", updated, total)
			ratio := 0.0
			if total > 0 {
				ratio = updated / total
			}
			progress += " " + tui.ProgressBar(12, ratio)
			rows = append(rows, []string{
				theme.Number(fmt.Sprintf("r%.0f", num(d, "revision"))),
				theme.StatusPill(str(d, "state")),
				theme.StyleDim().Render(str(d, "strategy")),
				theme.StyleDim().Render(str(d, "trigger")),
				progress,
				theme.StyleMute().Render(str(d, "createdAt")),
			})
		}
		tui.PrintTable(headers, rows)
		return nil
	},
}

var deployShowCmd = &cobra.Command{
	Use:   "show <group> <rev>",
	Short: "Show details of a single deployment",
	Args:  cobra.ExactArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var d map[string]any
		path := fmt.Sprintf("/api/v1/groups/%s/deployments/%s", args[0], args[1])
		if err := client.Get(path, &d); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(d)
		}

		theme.PrintTitle(fmt.Sprintf("Deployment r%.0f for %s", num(d, "revision"), str(d, "groupName")))
		theme.PrintKV("State", theme.StatusPill(str(d, "state")))
		theme.PrintKV("Strategy", str(d, "strategy"))
		theme.PrintKV("Trigger", str(d, "trigger"))
		theme.PrintKV("Progress", fmt.Sprintf("%.0f/%.0f instances", num(d, "updatedInstances"), num(d, "totalInstances")))
		theme.PrintKV("Created", str(d, "createdAt"))
		if v := str(d, "completedAt"); v != "-" && v != "" {
			theme.PrintKV("Completed", v)
		}
		if v := str(d, "rollbackOf"); v != "-" && v != "" {
			theme.PrintKV("Rollback Of", v)
		}

		if rollout, ok := d["rollout"].(map[string]any); ok {
			theme.PrintTitle("Rollout")
			theme.PrintKV("Batch Size", fmt.Sprintf("%.0f", num(rollout, "batchSize")))
			theme.PrintKV("Canary Instances", fmt.Sprintf("%.0f", num(rollout, "canaryInstances")))
			theme.PrintKV("Health Gate", fmt.Sprintf("%v", rollout["healthGateEnabled"]))
			theme.PrintKV("Auto-Rollback", fmt.Sprintf("%v", rollout["autoRollbackOnFailure"]))
			theme.PrintKV("Promotion Timeout", fmt.Sprintf("%.0fs", num(rollout, "promotionTimeoutSeconds")))
			theme.PrintKV("Min Healthy", fmt.Sprintf("%.0fs", num(rollout, "minHealthySeconds")))
			theme.PrintKV("Min Healthy TPS", fmt.Sprintf("%.1f", num(rollout, "minHealthyTps")))
			theme.PrintKV("Replacement Timeout", fmt.Sprintf("%.0fs", num(rollout, "replacementTimeoutSeconds")))
		}
		return nil
	},
}

var deploySaveCmd = &cobra.Command{
	Use:   "save <instance>",
	Short: "Deploy-back: capture a running instance's config into a new template version",
	Long: `Reads the running instance's config files and writes them as a new version of the
target template, so future instances that compose it inherit that state. Config files
only — binary and world data are skipped (and reported).`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		template, _ := cmd.Flags().GetString("to-template")
		if template == "" {
			return fmt.Errorf("--to-template is required")
		}
		body := map[string]any{"template": template}
		if prefix, _ := cmd.Flags().GetString("path-prefix"); prefix != "" {
			body["pathPrefix"] = prefix
		}

		var result map[string]any
		if err := client.Post("/api/v1/services/"+args[0]+"/save-to-template", body, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		hash := str(result, "hash")
		if len(hash) > 8 {
			hash = hash[:8]
		}
		theme.PrintSuccess(fmt.Sprintf("Saved %s → template %q (%d files, hash %s)",
			args[0], str(result, "template"), int(num(result, "filesWritten")), hash))
		if skipped, ok := result["skipped"].([]any); ok && len(skipped) > 0 {
			fmt.Printf("  %d file(s) skipped (binary/oversize)\n", len(skipped))
		}
		return nil
	},
}

func newDeployActionCmd(action, summary, helpTail string) *cobra.Command {
	return &cobra.Command{
		Use:   action + " <group> <rev>",
		Short: summary,
		Long:  summary + ". " + helpTail,
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := requireAuth()
			if err != nil {
				return err
			}

			path := fmt.Sprintf("/api/v1/groups/%s/deployments/%s/%s", args[0], args[1], action)
			var result map[string]any
			if err := client.Post(path, nil, &result); err != nil {
				return err
			}

			if flagJSON {
				return theme.PrintJSON(result)
			}
			theme.PrintSuccess(fmt.Sprintf("Deployment r%s for %q: %s", args[1], args[0], action))
			return nil
		},
	}
}

func init() {
	deployCmd.Flags().String("strategy", "", "Rollout strategy: rolling or canary (canary fills safe defaults; overrides the group default)")
	deployCmd.Flags().Int("batch-size", 0, "Instances rolled per batch (>=1)")
	deployCmd.Flags().Int("canary-instances", 0, "Number of canary instances (>=0)")
	deployCmd.Flags().Int("canary-percent", 0, "Canary percentage (0-100, mutually exclusive with --canary-instances)")
	deployCmd.Flags().Bool("health-gate", false, "Require canary health gate before promoting the rollout")
	deployCmd.Flags().Bool("auto-rollback", false, "Roll back automatically on rollout failure")
	deployCmd.Flags().Int64("promotion-timeout", 0, "Promotion timeout in seconds (>=1)")
	deployCmd.Flags().Int64("min-healthy", 0, "Minimum healthy seconds before advancing a batch (>=0)")
	deployCmd.Flags().Float64("min-healthy-tps", 0, "Min 1-minute TPS for an updated instance; below it (after min-healthy) the wave fails (0 = off)")
	deployCmd.Flags().BoolP("yes", "y", false, "Skip the rollout confirmation prompt")

	deployListCmd.Flags().Int("page", 1, "Page number (1-based)")
	deployListCmd.Flags().Int("page-size", 50, "Page size (max 100)")

	deploySaveCmd.Flags().String("to-template", "", "Target template to write the new version into (required)")
	deploySaveCmd.Flags().String("path-prefix", "", "Only capture files under this relative path prefix")

	deployCmd.AddCommand(
		deployListCmd,
		deployShowCmd,
		deploySaveCmd,
		newDeployActionCmd("pause", "Pause an in-progress deployment", "Useful while investigating a misbehaving rollout."),
		newDeployActionCmd("resume", "Resume a paused deployment", "Continues the rolling restart from where it left off."),
		newDeployActionCmd("rollback", "Roll back a deployment", "Restores the group to the most recent succeeded deployment's config and re-deploys it (linked via rollbackOf). No-op when there is no prior good revision."),
	)
}
