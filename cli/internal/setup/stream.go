package setup

import (
	"bytes"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
)

// The browser setup wizard wants to show the operator the *real* console
// output of the package-manager / systemctl / cosign commands this package
// runs on the VPS, live, as they execute. The CLI path, by contrast, only
// wants that output surfaced when a command fails (it keeps the terminal
// quiet on success).
//
// Rather than thread an io.Writer through every Install* signature (and every
// cobra call site), the package exposes a single process-wide "command sink".
// runCmd tees each command's combined stdout+stderr to both an internal
// capture buffer (so the existing "%w\n%s" error context is preserved) and
// the sink. The sink defaults to io.Discard, so CLI behaviour is unchanged;
// the wizard points it at a streaming writer for the duration of one install.
//
// The setup wizard is single-shot per process (one prexorctl-setup run writes
// one install), so a process-global is safe in practice. The mutex only
// guards against parallel tests racing on the global.
var (
	cmdSinkMu sync.Mutex
	cmdSink   io.Writer = io.Discard
)

// SetCommandSink redirects the live combined output of commands run by this
// package to w, and returns a restore func that resets the sink to its prior
// value. Passing nil is equivalent to io.Discard. Always pair with a deferred
// restore() so the global doesn't leak past the caller's scope.
func SetCommandSink(w io.Writer) (restore func()) {
	if w == nil {
		w = io.Discard
	}
	cmdSinkMu.Lock()
	prev := cmdSink
	cmdSink = w
	cmdSinkMu.Unlock()
	return func() {
		cmdSinkMu.Lock()
		cmdSink = prev
		cmdSinkMu.Unlock()
	}
}

func currentSink() io.Writer {
	cmdSinkMu.Lock()
	defer cmdSinkMu.Unlock()
	return cmdSink
}

// runCmd runs cmd, teeing its combined stdout+stderr to both an internal
// buffer and the live command sink, and returns the captured bytes. It is a
// drop-in replacement for cmd.CombinedOutput(): callers keep wrapping the
// returned bytes into their error context, while the wizard sees the output
// stream by line as the command produces it.
//
// The command line itself is echoed to the sink first (shell-style "$ …") so
// the streamed log reads like a real terminal session.
func runCmd(cmd *exec.Cmd) ([]byte, error) {
	sink := currentSink()
	if sink != io.Discard {
		fmt.Fprintf(sink, "$ %s\n", strings.Join(cmd.Args, " "))
	}
	var buf bytes.Buffer
	w := io.MultiWriter(&buf, sink)
	cmd.Stdout = w
	cmd.Stderr = w
	err := cmd.Run()
	return buf.Bytes(), err
}

// sinkPrintf writes a synthetic progress line to the live command sink only
// (never to the capture buffer). No-op when no sink is attached. Use for
// narration around steps that don't shell out — e.g. a download URL.
func sinkPrintf(format string, args ...any) {
	sink := currentSink()
	if sink == io.Discard {
		return
	}
	fmt.Fprintf(sink, format, args...)
}
