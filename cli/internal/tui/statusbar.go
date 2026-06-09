package tui

import (
	"strings"

	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// StatusBarCtx is what bubbletea models pass when rendering the bottom bar.
type StatusBarCtx struct {
	Command  string   // current command name, e.g. "group info bedwars"
	KeyHints []string // ["↑↓ navigate", "↵ select", "/ filter", "? help"]
	Cluster  string   // cluster name shown on the right
	Version  string   // CLI version shown on the right
}

// StatusBar renders the bottom status bar at the given total width.
//
//	▲ prexorctl   group info bedwars   ↑↓ navigate • ↵ select • / filter        ● prod-eu-west  v0.18.3
//
// Layout matches the design handoff: brand-deep leading segment, muted middle,
// cyan-dot cluster segment on the right.
func StatusBar(width int, ctx StatusBarCtx) string {
	if width <= 0 {
		width = 100
	}

	left := lipgloss.NewStyle().
		Foreground(lipgloss.Color("#ffffff")).
		Background(theme.BrandDp).
		Bold(true).
		Padding(0, 1).
		Render(theme.BrandGlyph() + " prexorctl")

	cmdSt := lipgloss.NewStyle().
		Foreground(theme.FgDim).
		Background(lipgloss.Color("#1c1925")).
		Padding(0, 1)

	hintSt := lipgloss.NewStyle().
		Foreground(theme.FgMute).
		Background(lipgloss.Color("#1c1925")).
		Padding(0, 1)

	rightSt := lipgloss.NewStyle().
		Foreground(theme.FgDim).
		Background(lipgloss.Color("#1c1925")).
		Padding(0, 1)

	cmdSeg := ""
	if ctx.Command != "" {
		cmdSeg = cmdSt.Render(ctx.Command)
	}

	hintSeg := ""
	if len(ctx.KeyHints) > 0 {
		hintSeg = hintSt.Render(strings.Join(ctx.KeyHints, "  "+theme.Bullet()+"  "))
	}

	rightInner := ""
	if ctx.Cluster != "" {
		rightInner += lipgloss.NewStyle().Foreground(theme.Cyan).Render(theme.DotUp()) + " " + ctx.Cluster
	}
	if ctx.Version != "" {
		if rightInner != "" {
			rightInner += "   "
		}
		rightInner += ctx.Version
	}
	right := ""
	if rightInner != "" {
		right = rightSt.Render(rightInner)
	}

	leftBlock := left + cmdSeg + hintSeg
	leftW := lipgloss.Width(leftBlock)
	rightW := lipgloss.Width(right)
	gap := width - leftW - rightW
	if gap < 1 {
		gap = 1
	}
	filler := lipgloss.NewStyle().Background(lipgloss.Color("#1c1925")).Render(strings.Repeat(" ", gap))

	return leftBlock + filler + right
}
