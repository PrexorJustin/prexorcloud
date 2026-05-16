package theme

import (
	"bytes"
	"io"
	"os"
	"strings"
	"testing"
)

func captureStderr(t *testing.T, fn func()) string {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	old := os.Stderr
	os.Stderr = w

	fn()

	w.Close()
	os.Stderr = old
	var buf bytes.Buffer
	io.Copy(&buf, r)
	r.Close()
	return buf.String()
}

func TestPrintSuccess_WritesToStdout(t *testing.T) {
	Init(AccentPurple, true, false)
	out := captureStdout(t, func() { PrintSuccess("operation completed") })
	if !strings.Contains(out, "operation completed") {
		t.Errorf("PrintSuccess() = %q, want it to contain %q", out, "operation completed")
	}
}

func TestPrintError_WritesToStderr(t *testing.T) {
	Init(AccentPurple, true, false)
	errOut := captureStderr(t, func() { PrintError("something failed") })
	if !strings.Contains(errOut, "something failed") {
		t.Errorf("PrintError() stderr = %q, want it to contain %q", errOut, "something failed")
	}
}

func TestPrintWarn_WritesToStderr(t *testing.T) {
	Init(AccentPurple, true, false)
	errOut := captureStderr(t, func() { PrintWarn("be careful") })
	if !strings.Contains(errOut, "be careful") {
		t.Errorf("PrintWarn() stderr = %q, want it to contain %q", errOut, "be careful")
	}
}

func TestPrintTitle_WritesToStdout(t *testing.T) {
	Init(AccentPurple, true, false)
	out := captureStdout(t, func() { PrintTitle("System Status") })
	if !strings.Contains(out, "System Status") {
		t.Errorf("PrintTitle() = %q, want it to contain title text", out)
	}
}

func TestPrintKV_WritesToStdout(t *testing.T) {
	Init(AccentPurple, true, false)
	out := captureStdout(t, func() { PrintKV("Version", "1.0.0") })
	if !strings.Contains(out, "Version") || !strings.Contains(out, "1.0.0") {
		t.Errorf("PrintKV() = %q, want it to contain key and value", out)
	}
}

func TestStatusPill_KnownStatuses(t *testing.T) {
	Init(AccentPurple, true, false)
	for _, status := range []string{
		"RUNNING", "ONLINE", "OFFLINE", "STARTING", "STOPPED", "CRASHED", "UNKNOWN_STATUS",
	} {
		t.Run(status, func(t *testing.T) {
			if got := StatusPill(status); !strings.Contains(got, status) {
				t.Errorf("StatusPill(%q) = %q, should contain %q", status, got, status)
			}
		})
	}
}
