package theme

import (
	"image/color"
	"strings"

	"github.com/charmbracelet/lipgloss/v2"
)

// PillKind picks which background+foreground pair the pill uses.
type PillKind int

const (
	PillGreen PillKind = iota
	PillAmber
	PillRed
	PillBrand
	PillCyan
	PillMute
)

// pillColors returns the (bg, fg) pair for a pill kind.
func pillColors(kind PillKind) (color.Color, color.Color) {
	switch kind {
	case PillGreen:
		return pillGreenBg, Green
	case PillAmber:
		return pillAmberBg, Amber
	case PillRed:
		return pillRedBg, Red
	case PillBrand:
		return pillBrandBg, Brand
	case PillCyan:
		return pillCyanBg, Cyan
	default:
		return pillMuteBg, FgDim
	}
}

// Pill renders an inline label with a colored background and slightly
// rounded edges (half-block bumpers colored as the pill background — so the
// pill visually extends a half-cell on each side). In NoColor or ASCII mode
// the styling is dropped and the label becomes "[TEXT]".
func Pill(kind PillKind, text string) string {
	if NoColor || ASCII {
		return "[" + strings.ToUpper(text) + "]"
	}

	bg, fg := pillColors(kind)

	body := lipgloss.NewStyle().
		Foreground(fg).
		Background(bg).
		Bold(true).
		Render(" " + text + " ")
	// Powerline rounded caps ( / ) colored as the pill bg —
	// produce real half-circle edges. Requires a Nerd-Font-patched terminal
	// font; on plain fonts these may render as boxes (use SquarePill instead).
	left := lipgloss.NewStyle().Foreground(bg).Render("")
	right := lipgloss.NewStyle().Foreground(bg).Render("")
	return left + body + right
}

// SquarePill is the bare padded-background pill (square edges) — useful
// when even the half-block bumpers feel like too much.
func SquarePill(kind PillKind, text string) string {
	if NoColor || ASCII {
		return "[" + strings.ToUpper(text) + "]"
	}
	bg, fg := pillColors(kind)
	return lipgloss.NewStyle().
		Foreground(fg).
		Background(bg).
		Bold(true).
		Padding(0, 1).
		Render(text)
}

// StatusPill maps a backend status string to a pill of the right kind+dot.
// Recognizes the design statuses (UP/DRAIN/STARTING/DOWN/RUNNING/FAILED/etc.)
// and the controller's internal vocabulary (ONLINE/OFFLINE/UNREACHABLE/...).
func StatusPill(status string) string {
	up := strings.ToUpper(status)
	switch up {
	case "UP", "ONLINE", "RUNNING", "READY", "ENABLED", "HEALTHY", "ROLLOUT COMPLETE":
		return Pill(PillGreen, DotUp()+" "+up)
	case "DRAIN", "DRAINING", "STARTING", "PREPARING", "SCHEDULED", "PENDING":
		return Pill(PillAmber, DotDrain()+" "+up)
	case "DOWN", "OFFLINE", "UNREACHABLE", "CRASHED", "STOPPED", "ERROR", "DISABLED", "FAILED":
		return Pill(PillRed, DotUp()+" "+up)
	default:
		return Pill(PillMute, up)
	}
}
