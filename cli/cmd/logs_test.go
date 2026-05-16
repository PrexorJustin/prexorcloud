package cmd

import (
	"strings"
	"testing"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

func TestFormatLogRecord_LevelAndMessage(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false) // no-color: assert plain text

	out := formatLogRecord(logsRecord{
		Ts:      1715680800000,
		Level:   "warn",
		Logger:  "me.prexor.Scheduler",
		Message: "tick spike 72ms",
	})
	for _, want := range []string{"WARN", "me.prexor.Scheduler", "tick spike 72ms"} {
		if !strings.Contains(out, want) {
			t.Errorf("formatLogRecord missing %q in %q", want, out)
		}
	}
}

func TestFormatLogRecord_ThrowableIndented(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)

	out := formatLogRecord(logsRecord{
		Level:     "ERROR",
		Logger:    "x",
		Message:   "boom",
		Throwable: "java.lang.RuntimeException: boom\nat x.y(z.java:1)",
	})
	if !strings.Contains(out, "\n    java.lang.RuntimeException: boom") {
		t.Errorf("expected indented throwable, got %q", out)
	}
	if !strings.Contains(out, "\n    at x.y(z.java:1)") {
		t.Errorf("expected indented stack frame, got %q", out)
	}
	// One message line plus two throwable lines.
	if got := strings.Count(out, "\n"); got != 2 {
		t.Errorf("throwable line count: got %d newlines, want 2", got)
	}
}

func TestFormatConsoleLine_Levels(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)

	// In no-color mode the styled output equals the input — we're really
	// asserting the classifier doesn't panic or mangle the text.
	cases := []string{
		"[12:00:00] [INFO]  Server thread/INFO:  joined the game",
		"[12:00:01] [WARN]  GameProfiler/WARN: tick took 63ms",
		"[12:00:02] [ERROR] Server thread/ERROR: kaboom",
		"a plain unmatched line",
	}
	for _, in := range cases {
		if got := formatConsoleLine(in); got != in {
			t.Errorf("formatConsoleLine(%q) = %q, want unchanged in no-color mode", in, got)
		}
	}
}

func TestLogsHeader_Shape(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)

	h := logsHeader("controller", "INFO")
	if len(h) != 4 {
		t.Fatalf("logsHeader lines = %d, want 4", len(h))
	}
	if !strings.Contains(h[0], "TAILING") || !strings.Contains(h[0], "controller") {
		t.Errorf("header line 0 = %q", h[0])
	}
	if !strings.Contains(h[2], "filter") || !strings.Contains(h[2], "pause") {
		t.Errorf("hint line = %q", h[2])
	}
}
