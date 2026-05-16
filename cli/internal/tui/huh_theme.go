package tui

import (
	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// HuhTheme returns a *huh.Theme matching the design handoff: brand-colored
// title and selector, dim subtitle, focused field with brand left-border,
// muted blurred fields, brand-deep prompt indicator. Note this uses lipgloss
// v1 (huh's dependency); the rest of the CLI uses v2.
func HuhTheme() *huh.Theme {
	t := huh.ThemeBase()

	brand := lipgloss.Color("#c77dff")
	brandDp := lipgloss.Color("#7b3fe4")
	cyan := lipgloss.Color("#6fd6e0")
	dim := lipgloss.Color("#9b95ad")
	mute := lipgloss.Color("#6a6478")
	red := lipgloss.Color("#ff6b6b")
	if theme.NoColor {
		brand = lipgloss.Color("")
		brandDp = lipgloss.Color("")
		cyan = lipgloss.Color("")
		dim = lipgloss.Color("")
		mute = lipgloss.Color("")
		red = lipgloss.Color("")
	}

	// Focused: thin brand left-border, brand title, quieter prompt.
	t.Focused.Base = t.Focused.Base.
		BorderStyle(lipgloss.NormalBorder()).
		BorderLeft(true).
		BorderTop(false).
		BorderRight(false).
		BorderBottom(false).
		BorderForeground(brand).
		PaddingLeft(1)
	t.Focused.Title = t.Focused.Title.Foreground(brand).Bold(true)
	t.Focused.NoteTitle = t.Focused.NoteTitle.Foreground(brand).Bold(true)
	t.Focused.Description = t.Focused.Description.Foreground(dim)
	t.Focused.SelectSelector = t.Focused.SelectSelector.Foreground(brand).SetString("› ")
	t.Focused.MultiSelectSelector = t.Focused.MultiSelectSelector.Foreground(brand).SetString("› ")
	t.Focused.SelectedOption = t.Focused.SelectedOption.Foreground(brand)
	t.Focused.SelectedPrefix = t.Focused.SelectedPrefix.Foreground(brand).SetString("[x] ")
	t.Focused.UnselectedPrefix = t.Focused.UnselectedPrefix.Foreground(mute).SetString("[ ] ")
	t.Focused.UnselectedOption = t.Focused.UnselectedOption.Foreground(dim)
	t.Focused.FocusedButton = t.Focused.FocusedButton.
		Foreground(lipgloss.Color("#ffffff")).
		Background(brandDp).
		Bold(true).
		Padding(0, 2)
	t.Focused.BlurredButton = t.Focused.BlurredButton.
		Foreground(dim).
		Padding(0, 2)
	t.Focused.TextInput.Cursor = t.Focused.TextInput.Cursor.Foreground(brand)
	t.Focused.TextInput.Prompt = t.Focused.TextInput.Prompt.Foreground(brand).SetString("› ")
	t.Focused.TextInput.Text = t.Focused.TextInput.Text.Foreground(lipgloss.Color("#e7e3f2"))
	t.Focused.TextInput.Placeholder = t.Focused.TextInput.Placeholder.Foreground(mute)
	t.Focused.ErrorIndicator = t.Focused.ErrorIndicator.Foreground(red).SetString(" x")
	t.Focused.ErrorMessage = t.Focused.ErrorMessage.Foreground(red)

	// Blurred: muted everything, no left border.
	t.Blurred.Base = t.Blurred.Base.PaddingLeft(2)
	t.Blurred.Title = t.Blurred.Title.Foreground(mute)
	t.Blurred.NoteTitle = t.Blurred.NoteTitle.Foreground(mute)
	t.Blurred.Description = t.Blurred.Description.Foreground(mute)
	t.Blurred.SelectSelector = t.Blurred.SelectSelector.SetString("  ")
	t.Blurred.MultiSelectSelector = t.Blurred.MultiSelectSelector.SetString("  ")
	t.Blurred.SelectedOption = t.Blurred.SelectedOption.Foreground(mute)
	t.Blurred.UnselectedOption = t.Blurred.UnselectedOption.Foreground(mute)

	// Help bar (cyan numeric accent).
	t.Help.Ellipsis = t.Help.Ellipsis.Foreground(mute)
	t.Help.ShortKey = t.Help.ShortKey.Foreground(cyan)
	t.Help.ShortDesc = t.Help.ShortDesc.Foreground(dim)
	t.Help.ShortSeparator = t.Help.ShortSeparator.Foreground(mute)
	t.Help.FullKey = t.Help.FullKey.Foreground(cyan)
	t.Help.FullDesc = t.Help.FullDesc.Foreground(dim)
	t.Help.FullSeparator = t.Help.FullSeparator.Foreground(mute)

	return t
}
