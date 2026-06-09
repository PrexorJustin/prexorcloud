package tui

import (
	"github.com/charmbracelet/lipgloss/v2"
	"github.com/charmbracelet/lipgloss/v2/table"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// Table renders a sharp-bordered table with the design's dim header style.
func Table(headers []string, rows [][]string) string {
	headerSt := theme.FG(theme.FgDim).Bold(true).Padding(0, 1)
	cellSt := theme.FG(theme.Fg).Padding(0, 1)
	zebraSt := theme.FG(theme.FgDim).Padding(0, 1)
	borderSt := theme.FG(theme.FgFaint)

	border := lipgloss.NormalBorder()
	if theme.ASCII {
		border = lipgloss.Border{
			Top: "-", Bottom: "-", Left: "|", Right: "|",
			TopLeft: "+", TopRight: "+", BottomLeft: "+", BottomRight: "+",
			MiddleLeft: "+", MiddleRight: "+", Middle: "+", MiddleTop: "+", MiddleBottom: "+",
		}
	}

	t := table.New().
		Border(border).
		BorderStyle(borderSt).
		StyleFunc(func(row, col int) lipgloss.Style {
			switch {
			case row == table.HeaderRow:
				return headerSt
			case row%2 == 0:
				return zebraSt
			default:
				return cellSt
			}
		}).
		Headers(headers...).
		Rows(rows...)

	return t.String()
}

// PrintTable writes a Table to stdout with a trailing newline.
func PrintTable(headers []string, rows [][]string) {
	lipgloss.Println(Table(headers, rows))
}
