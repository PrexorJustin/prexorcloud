package tui

import (
	"image/color"
	"strings"

	"github.com/guptarohit/asciigraph"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// LineChart renders a multi-row ASCII line chart with axes for the given
// values. Used by the LIVE METRICS card on `prexorctl status`. Returns an
// empty string for empty input.
func LineChart(values []float64, height, width int, fg color.Color, caption string) string {
	if len(values) == 0 {
		return theme.StyleFaint().Render("(no data)")
	}
	if height <= 0 {
		height = 5
	}
	if width <= 0 {
		width = 40
	}

	opts := []asciigraph.Option{
		asciigraph.Height(height),
		asciigraph.Width(width),
		asciigraph.Precision(1),
	}
	if caption != "" {
		opts = append(opts, asciigraph.Caption(caption))
	}
	graph := asciigraph.Plot(values, opts...)
	return theme.FG(fg).Render(graph)
}

// BarChartRow renders a labeled bar showing `value` against `max`, useful
// for memory/CPU/load gauges. Width is the bar width in cells.
func BarChartRow(label string, value, max float64, width int, fg color.Color) string {
	if max <= 0 {
		max = 1
	}
	ratio := value / max
	bar := ProgressBar(width, ratio)
	return label + "  " + bar
}

// SmallSparkline is a thin wrapper over Sparkline kept for clarity at call
// sites where we explicitly want the inline 1-row variant (e.g. table cells).
func SmallSparkline(values []float64, width int, fg color.Color) string {
	return Sparkline(values, width, fg)
}

// padBlock right-pads each line of s to width with spaces, useful for
// stacking charts vertically with a consistent column width.
func padBlock(s string, width int) string {
	var b strings.Builder
	for _, line := range strings.Split(s, "\n") {
		w := len([]rune(line))
		if w < width {
			line += strings.Repeat(" ", width-w)
		}
		b.WriteString(line)
		b.WriteString("\n")
	}
	return b.String()
}

var _ = padBlock // exported convenience reserved for future use
