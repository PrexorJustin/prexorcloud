package tui

import (
	"image/color"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

// Sparkline renders a sequence of values as a fixed-width 8-level block ramp.
// Width = number of cells; if len(values) > width the most recent values win.
// Empty data renders as a row of low-tier glyphs in dim color (so the column
// width stays stable in tables).
func Sparkline(values []float64, width int, fg color.Color) string {
	ramp := theme.SparklineRamp()
	if width <= 0 {
		width = 24
	}

	if len(values) == 0 {
		return theme.FG(theme.FgFaint).Render(strings.Repeat(string(ramp[0]), width))
	}

	// Slice the last `width` values.
	if len(values) > width {
		values = values[len(values)-width:]
	}

	// Find max for per-row scaling.
	max := values[0]
	for _, v := range values {
		if v > max {
			max = v
		}
	}
	if max <= 0 {
		max = 1
	}

	var b strings.Builder
	for _, v := range values {
		idx := int((v / max) * float64(len(ramp)-1))
		if idx < 0 {
			idx = 0
		}
		if idx >= len(ramp) {
			idx = len(ramp) - 1
		}
		b.WriteRune(ramp[idx])
	}
	// Pad on the left if we have fewer points than width, so the line is right-anchored.
	pad := width - len(values)
	if pad > 0 {
		b.Reset()
		b.WriteString(strings.Repeat(string(ramp[0]), pad))
		for _, v := range values {
			idx := int((v / max) * float64(len(ramp)-1))
			if idx < 0 {
				idx = 0
			}
			if idx >= len(ramp) {
				idx = len(ramp) - 1
			}
			b.WriteRune(ramp[idx])
		}
	}

	return theme.FG(fg).Render(b.String())
}
