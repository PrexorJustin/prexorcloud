package setupweb

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"time"
)

// FirewallCloser undoes whatever openFirewallPort did. It must be safe to
// call exactly once and never blocks for more than a second.
type FirewallCloser func()

// noopCloser is returned when there's nothing to undo (no tool, not root,
// disabled by flag).
func noopCloser() {}

// firewallTool names the supported front-ends, in detection priority order.
var firewallTools = []string{"ufw", "firewall-cmd", "iptables"}

// openFirewallPort tries to add an inbound TCP allow rule for the wizard's
// port. The first supported tool on PATH wins. Returns a closer that removes
// the rule on Serve() exit.
//
// Behaviour when we can't manage the firewall (not root, no supported tool,
// command failure) is intentionally non-fatal: the wizard still binds and
// runs, and we surface a "open the port yourself" hint via logf. The
// alternative — refusing to start — would punish operators behind cloud-
// level firewalls who'll open the port through their provider's console
// regardless of what's on the box.
func openFirewallPort(port int, logf func(string, ...any)) FirewallCloser {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	if os.Geteuid() != 0 {
		logf("Firewall: skipping (need root to manage firewall rules; if 9100 is blocked, open it yourself)")
		hintManualOpen(port, logf)
		return noopCloser
	}

	for _, tool := range firewallTools {
		if _, err := exec.LookPath(tool); err != nil {
			continue
		}
		closer, err := openWithTool(tool, port, logf)
		if err == nil {
			return closer
		}
		logf("Firewall: %s available but `%s` failed: %v — trying next tool", tool, tool, err)
	}

	logf("Firewall: no supported tool succeeded (looked for %v)", firewallTools)
	hintManualOpen(port, logf)
	return noopCloser
}

func openWithTool(tool string, port int, logf func(string, ...any)) (FirewallCloser, error) {
	p := strconv.Itoa(port)
	switch tool {
	case "ufw":
		if err := runFw("ufw", "allow", p+"/tcp"); err != nil {
			return nil, err
		}
		logf("Firewall: opened TCP %d via ufw.", port)
		return func() {
			if err := runFw("ufw", "delete", "allow", p+"/tcp"); err != nil {
				logf("Firewall: failed to close TCP %d via ufw: %v — remove manually with `ufw delete allow %d/tcp`", port, err, port)
				return
			}
			logf("Firewall: closed TCP %d via ufw.", port)
		}, nil

	case "firewall-cmd":
		// Runtime-only: we explicitly do NOT pass --permanent. The rule
		// disappears on the next firewalld reload — which is what we
		// want for a single-use setup wizard.
		if err := runFw("firewall-cmd", "--add-port="+p+"/tcp"); err != nil {
			return nil, err
		}
		logf("Firewall: opened TCP %d via firewalld (runtime).", port)
		return func() {
			if err := runFw("firewall-cmd", "--remove-port="+p+"/tcp"); err != nil {
				logf("Firewall: failed to close TCP %d via firewalld: %v — remove manually with `firewall-cmd --remove-port=%d/tcp`", port, err, port)
				return
			}
			logf("Firewall: closed TCP %d via firewalld.", port)
		}, nil

	case "iptables":
		// -I prepends so the rule wins over later DROP/REJECT chains.
		// We close with -D + the same matcher to undo exactly this rule.
		args := []string{"-I", "INPUT", "-p", "tcp", "--dport", p, "-j", "ACCEPT"}
		if err := runFw("iptables", args...); err != nil {
			return nil, err
		}
		logf("Firewall: opened TCP %d via iptables.", port)
		return func() {
			closeArgs := append([]string{"-D"}, args[1:]...)
			if err := runFw("iptables", closeArgs...); err != nil {
				logf("Firewall: failed to close TCP %d via iptables: %v — remove manually with `iptables -D INPUT -p tcp --dport %d -j ACCEPT`", port, err, port)
				return
			}
			logf("Firewall: closed TCP %d via iptables.", port)
		}, nil
	}
	return nil, fmt.Errorf("unknown tool %q", tool)
}

// runFw executes a firewall command with a tight 5-second deadline so a
// hung firewalld dbus call can't stall the wizard's startup.
func runFw(name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		// Surface the tool's own message — empty stdout/stderr is "exit 1"
		// which is useless on its own.
		if len(out) > 0 {
			return fmt.Errorf("%w: %s", err, string(out))
		}
		return err
	}
	return nil
}

func hintManualOpen(port int, logf func(string, ...any)) {
	logf("If your browser can't reach the wizard, open the port manually:")
	logf("  ufw allow %d/tcp                          # Debian/Ubuntu w/ ufw", port)
	logf("  firewall-cmd --add-port=%d/tcp            # Fedora/RHEL/Rocky w/ firewalld", port)
	logf("  iptables -I INPUT -p tcp --dport %d -j ACCEPT  # raw iptables", port)
	logf("Cloud VPSes (Hetzner / DO / AWS / GCP) often have a separate firewall — check the provider console too.")
}

// portFromBindAddr extracts the numeric port from a host:port string.
// Returns -1 if parse fails.
func portFromBindAddr(bindAddr string) int {
	for i := len(bindAddr) - 1; i >= 0; i-- {
		if bindAddr[i] == ':' {
			n, err := strconv.Atoi(bindAddr[i+1:])
			if err != nil {
				return -1
			}
			return n
		}
	}
	return -1
}

// errNoFirewallTool is returned when no supported firewall tool is installed.
// Exported so tests can match on it without coupling to wording.
var errNoFirewallTool = errors.New("no supported firewall tool found")
