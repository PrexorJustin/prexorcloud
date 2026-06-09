package tui

import (
	"strings"
	"testing"
	"time"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

func TestProgressBar_FillRatios(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false) // no-color: bare glyphs

	cases := []struct {
		ratio       float64
		wantFilled  int
		wantEmptied int
	}{
		{0.0, 0, 20},
		{0.5, 10, 10},
		{1.0, 20, 0},
		{-1.0, 0, 20}, // clamped
		{2.0, 20, 0},  // clamped
	}
	for _, c := range cases {
		out := ProgressBar(20, c.ratio)
		gotFilled := strings.Count(out, string(theme.ProgressFill()))
		gotEmpty := strings.Count(out, string(theme.ProgressEmpty()))
		if gotFilled != c.wantFilled || gotEmpty != c.wantEmptied {
			t.Errorf("ProgressBar(20, %v): filled=%d empty=%d, want filled=%d empty=%d",
				c.ratio, gotFilled, gotEmpty, c.wantFilled, c.wantEmptied)
		}
	}
}

func TestSparkline_RampBoundaries(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)
	ramp := theme.SparklineRamp()

	// A ramp from 0..max should hit both the lowest and highest glyphs.
	out := Sparkline([]float64{0, 1, 2, 3, 4, 5, 6, 7}, 8, theme.Green)
	if !strings.ContainsRune(out, ramp[0]) {
		t.Errorf("expected lowest ramp glyph %q in %q", ramp[0], out)
	}
	if !strings.ContainsRune(out, ramp[len(ramp)-1]) {
		t.Errorf("expected highest ramp glyph %q in %q", ramp[len(ramp)-1], out)
	}

	// Empty input renders a stable-width row of the lowest glyph.
	empty := Sparkline(nil, 6, theme.Green)
	if got := len([]rune(empty)); got != 6 {
		t.Errorf("empty sparkline width = %d, want 6", got)
	}
}

func TestDeployStatus_Terminal(t *testing.T) {
	cases := map[string]bool{
		"IN_PROGRESS": false,
		"PAUSED":      false,
		"PENDING":     false,
		"COMPLETED":   true,
		"FAILED":      true,
		"ROLLED_BACK": true,
		"cancelled":   true, // case-insensitive
	}
	for state, want := range cases {
		if got := (DeployStatus{State: state}).Terminal(); got != want {
			t.Errorf("Terminal(%q) = %v, want %v", state, got, want)
		}
	}
}

func TestRatioOf(t *testing.T) {
	cases := []struct {
		n, d int
		want float64
	}{
		{0, 10, 0},
		{5, 10, 0.5},
		{10, 10, 1},
		{15, 10, 1}, // clamped
		{5, 0, 0},   // zero denominator
		{-1, 10, 0}, // clamped low
	}
	for _, c := range cases {
		if got := ratioOf(c.n, c.d); got != c.want {
			t.Errorf("ratioOf(%d, %d) = %v, want %v", c.n, c.d, got, c.want)
		}
	}
}

func TestFmtElapsed(t *testing.T) {
	cases := map[time.Duration]string{
		3 * time.Second:                 "3s",
		90 * time.Second:                "1m 30s",
		(2*time.Minute + 5*time.Second): "2m 05s",
	}
	for d, want := range cases {
		if got := fmtElapsed(d); got != want {
			t.Errorf("fmtElapsed(%v) = %q, want %q", d, got, want)
		}
	}
}

func TestParseDuration(t *testing.T) {
	d, ok := parseDuration("2026-05-14T10:00:00Z", "2026-05-14T10:03:18Z")
	if !ok || d != 3*time.Minute+18*time.Second {
		t.Errorf("parseDuration = %v, %v; want 3m18s, true", d, ok)
	}
	if _, ok := parseDuration("not-a-date", "2026-05-14T10:00:00Z"); ok {
		t.Error("parseDuration should fail on a malformed timestamp")
	}
}

func TestStripANSI(t *testing.T) {
	theme.Init(theme.AccentPurple, false, false) // colored — produces escapes
	styled := theme.StyleBrand().Render("hello") + theme.StyleCyan().Render("world")
	if got := stripANSI(styled); got != "helloworld" {
		t.Errorf("stripANSI = %q, want %q", got, "helloworld")
	}
}

func TestPadCell(t *testing.T) {
	if got := padCell("ab", 5); got != "ab   " {
		t.Errorf("padCell short = %q", got)
	}
	if got := padCell("abcdef", 3); got != "abc" {
		t.Errorf("padCell truncate = %q", got)
	}
}
