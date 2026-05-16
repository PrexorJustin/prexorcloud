package tui

import (
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

// ProgressBar renders a static progress bar at the given fill ratio (0..1).
// Used by the deploy rollout view; the bubbletea model in deploy.go animates
// it by re-rendering with successive ratios.
func ProgressBar(width int, ratio float64) string {
	if width <= 0 {
		width = 24
	}
	if ratio < 0 {
		ratio = 0
	}
	if ratio > 1 {
		ratio = 1
	}

	filled := int(ratio * float64(width))
	if filled > width {
		filled = width
	}

	fill := theme.FG(theme.Brand).Render(strings.Repeat(string(theme.ProgressFill()), filled))
	empty := theme.FG(theme.FgFaint).Render(strings.Repeat(string(theme.ProgressEmpty()), width-filled))
	return fill + empty
}
