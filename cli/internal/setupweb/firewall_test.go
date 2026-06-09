package setupweb

import (
	"fmt"
	"os"
	"strings"
	"testing"
)

func TestPortFromBindAddr(t *testing.T) {
	cases := []struct {
		in   string
		want int
	}{
		{"0.0.0.0:9100", 9100},
		{"127.0.0.1:9100", 9100},
		{"[::1]:9100", 9100},
		{":9100", 9100},
		{"localhost:8080", 8080},
		{"no-port", -1},
		{"", -1},
		{"host:notanumber", -1},
	}
	for _, c := range cases {
		if got := portFromBindAddr(c.in); got != c.want {
			t.Errorf("portFromBindAddr(%q) = %d, want %d", c.in, got, c.want)
		}
	}
}

func TestOpenFirewallPort_NonRootIsNoOp(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("test must run as a non-root user")
	}
	var logged []string
	logf := func(format string, args ...any) {
		logged = append(logged, fmt.Sprintf(format, args...))
	}
	closer := openFirewallPort(9100, logf)
	if closer == nil {
		t.Fatal("closer must never be nil")
	}
	closer() // must not panic / crash even when nothing was opened.

	joined := strings.Join(logged, "\n")
	if !strings.Contains(joined, "skipping") || !strings.Contains(joined, "root") {
		t.Errorf("non-root path should log a skip/root message, got:\n%s", joined)
	}
	// Manual-hint advice should be printed so the operator can act.
	if !strings.Contains(joined, "ufw allow") {
		t.Errorf("non-root path should print manual-open hint, got:\n%s", joined)
	}
}
