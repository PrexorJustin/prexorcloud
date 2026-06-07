package tui

import (
	"strings"
	"testing"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

func TestTable_RendersCells(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)
	out := Table(
		[]string{"Name", "Status", "Players"},
		[][]string{
			{"lobby-a1b2", "RUNNING", "42"},
			{"game-c3d4", "STARTING", "0"},
		},
	)
	for _, want := range []string{"Name", "lobby-a1b2", "STARTING", "42"} {
		if !strings.Contains(out, want) {
			t.Errorf("Table() output missing %q\ngot:\n%s", want, out)
		}
	}
}

func TestTable_EmptyRows(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)
	// Should not panic with zero rows.
	if out := Table([]string{"Col"}, [][]string{}); !strings.Contains(out, "Col") {
		t.Errorf("Table() with empty rows should still render the header, got: %q", out)
	}
}

func TestPrintTable_DoesNotPanic(t *testing.T) {
	theme.Init(theme.AccentPurple, true, false)
	PrintTable([]string{"Col"}, [][]string{{"v"}})
}
