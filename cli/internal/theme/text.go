package theme

import (
	"fmt"
	"os"
	"strings"

	"github.com/charmbracelet/lipgloss/v2"
)

// Style helpers — stateless, read live from the package-level palette so
// theme.Init() can swap accent colors before any rendering happens. In
// NoColor mode they return a plain style so Render() emits no escape codes.

// FG returns a lipgloss.Style with the given foreground, or a plain style
// when NoColor is on. Use this everywhere a foreground is wanted so the
// no-color mode strips ANSI cleanly.
func FG(c interface {
	RGBA() (uint32, uint32, uint32, uint32)
}) lipgloss.Style {
	if NoColor {
		return lipgloss.NewStyle()
	}
	return lipgloss.NewStyle().Foreground(c)
}

// BG returns a lipgloss.Style with the given background only — useful for
// pills and the status bar. NoColor strips it.
func BG(c interface {
	RGBA() (uint32, uint32, uint32, uint32)
}) lipgloss.Style {
	if NoColor {
		return lipgloss.NewStyle()
	}
	return lipgloss.NewStyle().Background(c)
}

func styleFG(c interface {
	RGBA() (uint32, uint32, uint32, uint32)
}) lipgloss.Style {
	return FG(c)
}

func StyleBrand() lipgloss.Style { return styleFG(Brand) }
func StyleDim() lipgloss.Style   { return styleFG(FgDim) }
func StyleMute() lipgloss.Style  { return styleFG(FgMute) }
func StyleFaint() lipgloss.Style { return styleFG(FgFaint) }
func StyleGreen() lipgloss.Style { return styleFG(Green) }
func StyleAmber() lipgloss.Style { return styleFG(Amber) }
func StyleRed() lipgloss.Style   { return styleFG(Red) }
func StyleCyan() lipgloss.Style  { return styleFG(Cyan) }
func StyleBlue() lipgloss.Style  { return styleFG(Blue) }

// Heading is a bold brand-colored title.
func Heading(s string) string {
	if NoColor {
		return lipgloss.NewStyle().Bold(true).Render(s)
	}
	return lipgloss.NewStyle().Foreground(Brand).Bold(true).Render(s)
}

// Subtitle is the dim metadata line under a heading.
func Subtitle(s string) string { return StyleDim().Render(s) }

// Hint is a footer key-hint or low-priority instruction.
func Hint(s string) string { return StyleMute().Render(s) }

// Code formats inline code / command names in brand color.
func Code(s string) string { return StyleBrand().Render(s) }

// Path formats file paths and URLs in blue.
func Path(s string) string { return StyleBlue().Render(s) }

// Number formats numeric values (counts, percentages, durations) in cyan.
func Number(s string) string { return StyleCyan().Render(s) }

// Diff helpers for the deploy plan view.
func Added(s string) string   { return StyleGreen().Render("+ " + s) }
func Updated(s string) string { return StyleAmber().Render(s) }
func Removed(s string) string { return StyleRed().Render("- " + s) }
func Unchanged(s string) string {
	return StyleFaint().Render("  " + s)
}

// HRule draws a horizontal rule using the dim divider color.
func HRule(width int) string {
	return StyleFaint().Render(strings.Repeat(BoxSharp().H, width))
}

// Stdout / stderr conveniences that match the existing output package's API
// surface so call sites migrate cleanly.

func PrintSuccess(msg string) {
	fmt.Fprintf(os.Stdout, "%s %s\n", StyleGreen().Render(Tick()), msg)
}

func PrintError(msg string) {
	fmt.Fprintf(os.Stderr, "%s %s\n", StyleRed().Render(Cross()), msg)
}

func PrintWarn(msg string) {
	fmt.Fprintf(os.Stderr, "%s %s\n", StyleAmber().Render(Warn()), msg)
}

func PrintTitle(msg string) {
	fmt.Fprintf(os.Stdout, "\n%s\n\n", Heading(msg))
}

func PrintKV(key, value string) {
	fmt.Fprintf(os.Stdout, "  %s %s\n", StyleMute().Render(key+":"), value)
}
