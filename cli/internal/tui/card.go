package tui

import (
	"strings"

	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// Card renders a rounded-border card with a title and a body block.
// Width is the inner content width; the rendered card is width+2 visually.
func Card(title, body string, width int) string {
	if width <= 0 {
		width = 60
	}
	bx := theme.BoxRound()

	titleSt := lipgloss.NewStyle().Foreground(theme.Fg).Bold(true)
	borderSt := theme.StyleFaint()

	// Top border with embedded title: "╭─ TITLE ──────╮"
	titleText := " " + titleSt.Render(title) + " "
	titleVisual := lipgloss.Width(titleText)
	dashLeft := 2
	dashRight := width - titleVisual - dashLeft
	if dashRight < 1 {
		dashRight = 1
	}
	top := borderSt.Render(bx.TL+strings.Repeat(bx.H, dashLeft)) +
		titleText +
		borderSt.Render(strings.Repeat(bx.H, dashRight)+bx.TR)

	// Body lines, padded to width.
	var lines []string
	for _, line := range strings.Split(body, "\n") {
		w := lipgloss.Width(line)
		if w < width {
			line += strings.Repeat(" ", width-w)
		}
		lines = append(lines, borderSt.Render(bx.V)+line+borderSt.Render(bx.V))
	}

	bottom := borderSt.Render(bx.BL + strings.Repeat(bx.H, width) + bx.BR)

	var b strings.Builder
	b.WriteString(top)
	b.WriteString("\n")
	for _, l := range lines {
		b.WriteString(l)
		b.WriteString("\n")
	}
	b.WriteString(bottom)
	return b.String()
}
