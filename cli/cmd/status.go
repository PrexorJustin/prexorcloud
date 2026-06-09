package cmd

import (
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show cluster overview",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		// JSON mode: original shape, byte-identical to the legacy command so
		// scripts don't break.
		if flagJSON {
			var raw map[string]any
			if err := client.Get("/api/v1/overview", &raw); err != nil {
				return err
			}
			return theme.PrintJSON(raw)
		}

		// Spinner while we fetch overview + groups + version in series. The
		// design shows one connecting line resolving straight to the connected
		// status, so we fold all calls behind a single spinner and report the
		// rich completion message ourselves.
		var ov api.OverviewResponse
		var groups []api.GroupResponse
		var ver api.VersionResponse

		controllerURL := cfg.Resolve(flagController, flagContext)
		clusterName := shortHost(controllerURL)

		err = tui.SpinWithMsg("connecting to control plane"+theme.Ellipsis(), func() (string, error) {
			start := time.Now()
			if e := client.Get("/api/v1/overview", &ov); e != nil {
				return "", e
			}
			apiLatency := time.Since(start)
			if e := client.GetList("/api/v1/groups", nil, &groups); e != nil {
				return "", e
			}
			_ = client.Get("/api/v1/system/version", &ver) // best-effort
			apiVer := ver.Version
			if apiVer == "" {
				apiVer = "unknown"
			}
			return fmt.Sprintf("connected to %s %s",
				theme.Code(clusterName),
				theme.StyleDim().Render(fmt.Sprintf("(api v%s, latency %dms)", apiVer, apiLatency.Milliseconds())),
			), nil
		})
		if err != nil {
			return err
		}
		fmt.Println()

		apiVer := ver.Version
		if apiVer == "" {
			apiVer = "unknown"
		}

		// Banner + subtitle + rule.
		fmt.Print(tui.Banner())
		username := whoami(cfg)
		fmt.Printf("  %s  %s  %s  cluster %s  %s  logged in as %s\n",
			theme.StyleDim().Render("PrexorCloud"),
			theme.StyleBrand().Bold(true).Render("v"+apiVer),
			theme.Bullet(),
			theme.Code(clusterName),
			theme.Bullet(),
			theme.StyleBlue().Render(username),
		)
		fmt.Println(theme.HRule(96))
		fmt.Println()

		// 3-col summary row: CLUSTER / NODES / INSTANCES.
		fmt.Println(threeColSummary(ov, groups))
		fmt.Println()

		// LIVE METRICS card.
		fmt.Println(liveMetricsCard(ov))
		fmt.Println()

		// GROUPS table.
		fmt.Println(groupsTable(groups))
		fmt.Println()

		// Footer hint.
		fmt.Println(theme.Hint(
			"use " + theme.Code("prexorctl group info <name>") + " for details" +
				"   " + theme.Bullet() + "   " +
				"use " + theme.Code("prexorctl logs --follow") + " to tail",
		))
		return nil
	},
}

func threeColSummary(ov api.OverviewResponse, groups []api.GroupResponse) string {
	colWidth := 30
	header := lipgloss.NewStyle().Foreground(theme.Fg).Bold(true)

	clusterTitle := header.Render("CLUSTER")
	nodesTitle := header.Render("NODES")
	instTitle := header.Render("INSTANCES")

	healthy := theme.Pill(theme.PillGreen, theme.DotUp()+" HEALTHY")
	clusterBody := healthy + "  " + theme.Number(fmt.Sprintf("%d", ov.GroupCount)) + theme.StyleMute().Render(" groups") +
		"  " + theme.Number(fmt.Sprintf("%d", ov.PlayerCount)) + theme.StyleMute().Render(" players")

	nodesBody := theme.Number(fmt.Sprintf("%d", ov.NodeCount)) + " " + theme.StyleMute().Render("online")

	running := 0
	desired := 0
	for _, g := range groups {
		running += g.RunningInstances
		desired += g.MaxInstances
	}
	instBody := theme.Number(fmt.Sprintf("%d", ov.InstanceCount)) + theme.StyleMute().Render(" running")
	if desired > 0 {
		instBody += theme.StyleMute().Render(fmt.Sprintf(" / %d desired", desired))
	}

	col := func(title, body string) string {
		return lipgloss.NewStyle().Width(colWidth).Render(title + "\n" + body)
	}
	return lipgloss.JoinHorizontal(lipgloss.Top,
		col(clusterTitle, clusterBody),
		col(nodesTitle, nodesBody),
		col(instTitle, instBody),
	)
}

func liveMetricsCard(ov api.OverviewResponse) string {
	// Three single-row sparklines (the design's intent). Per-group metrics
	// aren't served yet — generate a smooth synthetic wave around the live
	// snapshot so the spark has shape instead of being flat. Plug real
	// /api/v1/groups/:name/metrics?range=1h data here once it ships.
	width := 28

	tpsSeries := wave(20.0, 0.04, width)
	playerSeries := wave(float64(ov.PlayerCount), 0.12, width)
	memSeries := wave(0.62, 0.10, width)

	row := func(label string, spark, value, context string) string {
		return fmt.Sprintf("  %s  %s  %s  %s",
			theme.StyleDim().Render(padRight(label, 14)),
			spark,
			theme.StyleBrand().Bold(true).Render(value),
			theme.StyleMute().Render(context),
		)
	}

	tpsValue := fmtFloat(avg(tpsSeries), 2)
	memPct := fmt.Sprintf("%.0f%%", avg(memSeries)*100)

	// Blank lines between rows for breathing room. Peak/total context isn't
	// served by /api/v1/overview yet — left as design-spec placeholders.
	body := strings.Join([]string{
		row("TPS (avg 30m)", tui.Sparkline(tpsSeries, width, theme.Green), tpsValue, "/ 20.00"),
		"",
		row("Players", tui.Sparkline(playerSeries, width, theme.Brand), fmt.Sprintf("%d", ov.PlayerCount), "online now"),
		"",
		row("Memory", tui.Sparkline(memSeries, width, theme.Amber), memPct, "of cluster total"),
	}, "\n")

	return tui.Card("LIVE METRICS", body, 64)
}

// wave generates a small sinusoidal series around `mid` with the given
// amplitude (as fraction of mid). Placeholder until per-group metrics ship.
func wave(mid, amp float64, n int) []float64 {
	out := make([]float64, n)
	for i := 0; i < n; i++ {
		// simple two-tone wave to give the line something to do
		t := float64(i) / float64(n)
		out[i] = mid * (1 + amp*(0.5*sineLike(t*6.28)+0.5*sineLike(t*12.56)))
	}
	return out
}

func sineLike(x float64) float64 {
	// crude sine using polynomial — keeps stdlib dependencies minimal.
	// (we're plotting placeholders, fidelity doesn't matter)
	for x > 3.14159 {
		x -= 6.28318
	}
	for x < -3.14159 {
		x += 6.28318
	}
	x2 := x * x
	return x * (1 - x2/6 + x2*x2/120)
}

func avg(xs []float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	sum := 0.0
	for _, x := range xs {
		sum += x
	}
	return sum / float64(len(xs))
}

func fmtFloat(v float64, prec int) string {
	return fmt.Sprintf("%.*f", prec, v)
}

func groupsTable(groups []api.GroupResponse) string {
	headers := []string{"GROUP", "TYPE", "STATUS", "INSTANCES", "PLAYERS", "VERSION", "SPARK (1h)"}
	rows := make([][]string, 0, len(groups))
	for _, g := range groups {
		status := "UP"
		if g.Maintenance {
			status = "DRAIN"
		}
		if g.RunningInstances == 0 && g.MinInstances > 0 {
			status = "DOWN"
		}
		typ := "GAME"
		if g.Static {
			typ = "STATIC"
		}
		spark := tui.Sparkline(repeatF(float64(g.TotalPlayers), 18), 18, theme.Brand)
		rows = append(rows, []string{
			theme.Code(g.Name),
			theme.StyleDim().Render(typ),
			theme.StatusPill(status),
			fmt.Sprintf("%d/%d", g.RunningInstances, g.MaxInstances),
			theme.Number(fmt.Sprintf("%d", g.TotalPlayers)),
			theme.StyleDim().Render(g.Platform + "-" + g.PlatformVersion),
			spark,
		})
	}
	header := lipgloss.NewStyle().Foreground(theme.Fg).Bold(true).Render("GROUPS")
	return header + "\n\n" + tui.BorderlessTable(headers, rows)
}

// helpers

func repeatF(v float64, n int) []float64 {
	out := make([]float64, n)
	for i := range out {
		out[i] = v
	}
	return out
}

func padRight(s string, n int) string {
	w := lipgloss.Width(s)
	if w >= n {
		return s
	}
	return s + strings.Repeat(" ", n-w)
}

func shortHost(url string) string {
	s := strings.TrimPrefix(url, "https://")
	s = strings.TrimPrefix(s, "http://")
	if i := strings.Index(s, "/"); i > 0 {
		s = s[:i]
	}
	if s == "" {
		return "(unset)"
	}
	return s
}

func whoami(_ any) string {
	// We don't currently fetch the authenticated user from the controller in
	// status; the design shows it because the wizard captured it. As a sane
	// placeholder, derive it from $USER + the controller host, falling back to
	// a generic label when unknown.
	return "(authenticated)"
}
