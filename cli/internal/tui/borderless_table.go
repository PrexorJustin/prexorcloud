package tui

import (
	"strings"

	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// BorderlessTable renders a column-aligned table with no outer borders and a
// single horizontal rule under the header — the design's GROUPS layout in
// `status`. Each column's width is the max ANSI-aware width of its cells.
//
// Cells may already contain ANSI color codes; the column width logic uses
// lipgloss.Width to ignore escape sequences.
func BorderlessTable(headers []string, rows [][]string) string {
	if len(headers) == 0 {
		return ""
	}

	cols := len(headers)
	widths := make([]int, cols)
	for i, h := range headers {
		widths[i] = lipgloss.Width(h)
	}
	for _, row := range rows {
		for i := 0; i < cols && i < len(row); i++ {
			if w := lipgloss.Width(row[i]); w > widths[i] {
				widths[i] = w
			}
		}
	}

	gap := "   "

	var b strings.Builder

	// Header line.
	headerSt := theme.StyleDim().Bold(true)
	for i, h := range headers {
		if i > 0 {
			b.WriteString(gap)
		}
		b.WriteString(headerSt.Render(padRightWidth(h, widths[i])))
	}
	b.WriteString("\n")

	// Single horizontal rule under the header, sized to total width.
	total := 0
	for i, w := range widths {
		total += w
		if i > 0 {
			total += len(gap)
		}
	}
	b.WriteString(theme.StyleFaint().Render(strings.Repeat(theme.BoxSharp().H, total)))
	b.WriteString("\n")

	// Body rows.
	for _, row := range rows {
		for i := 0; i < cols; i++ {
			cell := ""
			if i < len(row) {
				cell = row[i]
			}
			if i > 0 {
				b.WriteString(gap)
			}
			b.WriteString(padRightWidth(cell, widths[i]))
		}
		b.WriteString("\n")
	}

	return strings.TrimRight(b.String(), "\n")
}

// padRightWidth pads s on the right with spaces to reach width cells,
// counting visible width (lipgloss.Width strips ANSI).
func padRightWidth(s string, width int) string {
	w := lipgloss.Width(s)
	if w >= width {
		return s
	}
	return s + strings.Repeat(" ", width-w)
}
