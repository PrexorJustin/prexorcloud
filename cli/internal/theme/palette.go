// Package theme is the single source of truth for prexorctl's visual tokens:
// colors, glyphs, pill labels, and text helpers. All styling in the CLI flows
// through here so --no-color, --ascii, and accent-color swaps land in one place.
package theme

import (
	"image/color"
	"os"

	"github.com/charmbracelet/colorprofile"
	"github.com/charmbracelet/lipgloss/v2"
)

// Accent is the user-selected accent family. Default is purple; the design
// allows swapping the brand pair to cyan, green, or amber.
type Accent string

const (
	AccentPurple Accent = "purple"
	AccentCyan   Accent = "cyan"
	AccentGreen  Accent = "green"
	AccentAmber  Accent = "amber"
)

// Color tokens. Initialized to design defaults; Init() may swap brand pair
// based on accent.
var (
	Fg      color.Color = lipgloss.Color("#e7e3f2")
	FgDim   color.Color = lipgloss.Color("#9b95ad")
	FgMute  color.Color = lipgloss.Color("#6a6478")
	FgFaint color.Color = lipgloss.Color("#4a455a")
	Brand   color.Color = lipgloss.Color("#c77dff")
	BrandDp color.Color = lipgloss.Color("#7b3fe4")
	Magenta color.Color = lipgloss.Color("#ff5fb4")
	Green   color.Color = lipgloss.Color("#6dd58c")
	Amber   color.Color = lipgloss.Color("#ffc56e")
	Red     color.Color = lipgloss.Color("#ff6b6b")
	Cyan    color.Color = lipgloss.Color("#6fd6e0")
	Blue    color.Color = lipgloss.Color("#7aa2ff")

	// Pill backgrounds — paired with their fg in pill.go.
	pillGreenBg color.Color = lipgloss.Color("#1a3322")
	pillAmberBg color.Color = lipgloss.Color("#3a2c10")
	pillRedBg   color.Color = lipgloss.Color("#3a1818")
	pillBrandBg color.Color = lipgloss.Color("#2a1840")
	pillCyanBg  color.Color = lipgloss.Color("#11323a")
	pillMuteBg  color.Color = lipgloss.Color("#1c1925")
)

// Mode flags. Read from many places; written once by Init().
var (
	NoColor bool
	ASCII   bool
)

// Init configures the theme for the current run. Call once during root
// PersistentPreRun. Honors NO_COLOR env, --no-color and --ascii flags, and
// the user's accent preference.
func Init(accent Accent, noColorFlag, asciiFlag bool) {
	NoColor = noColorFlag || os.Getenv("NO_COLOR") != ""
	ASCII = asciiFlag

	if NoColor {
		lipgloss.Writer.Profile = colorprofile.NoTTY
	}

	switch accent {
	case AccentCyan:
		Brand = Cyan
		BrandDp = lipgloss.Color("#3aa0b0")
	case AccentGreen:
		Brand = Green
		BrandDp = lipgloss.Color("#3aa15a")
	case AccentAmber:
		Brand = Amber
		BrandDp = lipgloss.Color("#c08a3a")
	default:
		// purple — already set
	}
}
