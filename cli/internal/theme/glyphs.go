package theme

// Glyph returns the unicode glyph or its ASCII fallback when ASCII mode is on.
func Glyph(unicode, ascii string) string {
	if ASCII {
		return ascii
	}
	return unicode
}

// Status dots and marks.
func DotUp() string      { return Glyph("●", "*") }
func DotDrain() string   { return Glyph("◐", "o") }
func Tick() string       { return Glyph("✓", "v") }
func Cross() string      { return Glyph("✗", "x") }
func Warn() string       { return Glyph("⚠", "!") }
func BrandGlyph() string { return Glyph("▲", "^") }
func PauseGlyph() string { return Glyph("⏸", "||") }
func PlayGlyph() string  { return Glyph("⏵", ">") }
func Bullet() string     { return Glyph("•", "-") }
func Arrow() string      { return Glyph("→", "->") }
func Ellipsis() string   { return Glyph("…", "...") }

// SpinnerFrames returns the active spinner frames. ~90ms per frame.
func SpinnerFrames() []string {
	if ASCII {
		return []string{"\\", "|", "/", "-"}
	}
	return []string{"⣾", "⣽", "⣻", "⢿", "⡿", "⣟", "⣯", "⣷"}
}

// SparklineRamp returns the 8-level block ramp for sparklines.
func SparklineRamp() []rune {
	if ASCII {
		return []rune{'.', '.', ':', ':', '|', '|', '#', '#'}
	}
	return []rune{'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'}
}

// Progress bar fill/empty characters.
func ProgressFill() rune {
	if ASCII {
		return '#'
	}
	return '█'
}

func ProgressEmpty() rune {
	if ASCII {
		return '-'
	}
	return '░'
}

// Box drawing — sharp (tables) and rounded (cards).

type BoxChars struct {
	TL, TR, BL, BR string
	H, V           string
	TT, TB, ML, MR string
	Cross          string
}

func BoxSharp() BoxChars {
	if ASCII {
		return BoxChars{TL: "+", TR: "+", BL: "+", BR: "+", H: "-", V: "|", TT: "+", TB: "+", ML: "+", MR: "+", Cross: "+"}
	}
	return BoxChars{TL: "┌", TR: "┐", BL: "└", BR: "┘", H: "─", V: "│", TT: "┬", TB: "┴", ML: "├", MR: "┤", Cross: "┼"}
}

func BoxRound() BoxChars {
	if ASCII {
		return BoxSharp()
	}
	return BoxChars{TL: "╭", TR: "╮", BL: "╰", BR: "╯", H: "─", V: "│", TT: "┬", TB: "┴", ML: "├", MR: "┤", Cross: "┼"}
}
