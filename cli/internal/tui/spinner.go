// Package tui holds reusable lipgloss + bubbletea components for prexorctl:
// status bar, table, card, banner, progress bar, sparkline, spinner, and the
// bubbletea models for log streams, group info, and deploy rollouts.
package tui

import (
	"fmt"
	"os"
	"time"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

// SpinWith runs fn while showing an animated spinner. On completion the
// spinner line is rewritten to a single ✓ / ✗ result. Replaces the legacy
// internal/setup/spinner.go.
func SpinWith(title string, fn func() error) error {
	return SpinWithMsg(title, func() (string, error) { return title, fn() })
}

// SpinWithMsg runs fn while showing an animated spinner labeled with title.
// fn returns the message to show on the resolved ✓ line — this lets a caller
// fold a multi-step fetch behind one spinner and report a richer completion
// line (e.g. "connected to prod-eu-west (api v0.18.3, latency 24ms)") without
// emitting a second line. On error the spinner resolves to ✗ <title>.
func SpinWithMsg(title string, fn func() (string, error)) error {
	frames := theme.SpinnerFrames()
	type result struct {
		msg string
		err error
	}
	done := make(chan result, 1)

	go func() {
		msg, err := fn()
		done <- result{msg, err}
	}()

	frame := theme.FG(theme.Brand)
	titleSt := theme.FG(theme.FgDim)

	i := 0
	ticker := time.NewTicker(90 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case r := <-done:
			// Clear the spinner frame and emit a final line.
			fmt.Fprint(os.Stdout, "\r\033[K")
			if r.err != nil {
				fmt.Fprintf(os.Stdout, "%s %s\n",
					theme.FG(theme.Red).Bold(true).Render(theme.Cross()),
					title,
				)
			} else {
				msg := r.msg
				if msg == "" {
					msg = title
				}
				fmt.Fprintf(os.Stdout, "%s %s\n",
					theme.FG(theme.Green).Bold(true).Render(theme.Tick()),
					msg,
				)
			}
			return r.err
		case <-ticker.C:
			fmt.Fprintf(os.Stdout, "\r%s %s",
				frame.Render(frames[i%len(frames)]),
				titleSt.Render(title),
			)
			i++
		}
	}
}
