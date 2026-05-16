package cmd

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	osuser "os/user"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/prexorcloud/prexorctl/internal/setupweb"
)

var (
	setupBrowser          bool
	setupBrowserAddr      string
	setupBrowserOpen      bool
	setupBrowserPublic    bool
	setupBrowserHost      string
	setupBrowserIdle      time.Duration
	setupBrowserFirewall  bool
	setupBrowserSSHTunnel bool
)

// runBrowserSetup launches the loopback (or token-authed public) wizard
// server. It returns when the wizard sends POST /api/exit, the operator
// hits Ctrl+C, the single-use install timer fires, or idle-timeout elapses.
func runBrowserSetup() error {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	// Auto-detect the SSH-tunnel case: when the operator hasn't picked --public
	// or --ssh-tunnel explicitly, but $SSH_CONNECTION is set on a headless box,
	// they're plainly running prexorctl from inside an SSH session on a remote
	// VPS — the right answer is the no-TLS, no-warning tunnelled flow, not a
	// hung "waiting for browser" on a host with no desktop session.
	autoTunneled := false
	if !setupBrowserSSHTunnel && !setupBrowserPublic && inSSHSession() && isHeadless() {
		setupBrowserSSHTunnel = true
		autoTunneled = true
	}

	// --ssh-tunnel is the "no TLS, no warning" remote-VPS mode: bind loopback,
	// skip the local xdg-open attempt, and print the laptop-side `ssh -L`
	// command the operator should run. Mutually exclusive with --public, which
	// it overrides (the operator clearly meant the tunnelled flow).
	addr := setupBrowserAddr
	public := setupBrowserPublic
	openBrowser := setupBrowserOpen
	if setupBrowserSSHTunnel {
		public = false
		openBrowser = false
		if addr == "" {
			addr = "127.0.0.1:9100"
		}
	}
	if addr == "" {
		if public {
			addr = "0.0.0.0:9100"
		} else {
			addr = "127.0.0.1:9100"
		}
	}

	printSetupHeader("--browser")
	fmt.Println()
	fmt.Println("  Launching the browser-based setup wizard.")
	fmt.Println("  Same install pipeline as 'prexorctl setup' — the browser is just the UI.")
	fmt.Println()

	if setupBrowserSSHTunnel {
		if autoTunneled {
			fmt.Println("  Detected an SSH session with no local browser — using SSH-tunnel mode.")
			fmt.Println("  (Pass --public to expose the wizard on the network instead; not recommended.)")
			fmt.Println()
		}
		printSSHTunnelInstructions(os.Stdout, addr)
	}

	return setupweb.Serve(ctx, setupweb.Options{
		BindAddr:       addr,
		Public:         public,
		PublicHost:     setupBrowserHost,
		OpenBrowser:    openBrowser,
		IdleTimeout:    setupBrowserIdle,
		ManageFirewall: setupBrowserFirewall,
		Logf: func(format string, args ...any) {
			fmt.Printf("  "+format+"\n", args...)
		},
		OnCliLogin: persistCliLogin,
	})
}

// persistCliLogin is wired into the wizard's CLI-login mode (the fifth Step 1
// card). The wizard authenticates against the controller server-side and
// hands us {controller, token} on success; we store it in the prexorctl
// config so the same binary the wizard came from is logged in. When
// prexorctl runs under sudo, the config is chowned to SUDO_USER so the
// invoking operator can use it without root.
func persistCliLogin(controller, token string) error {
	cfg.SetCurrentAuth(controller, token)
	home, uid, _, err := resolveInvokingUserHome()
	if err == nil && uid > 0 {
		gid := 0
		if u, err := osuser.Lookup(os.Getenv("SUDO_USER")); err == nil {
			if g, err := strconv.Atoi(u.Gid); err == nil {
				gid = g
			}
		}
		return cfg.SaveAs(home, uid, gid)
	}
	return cfg.Save()
}

// printSSHTunnelInstructions emits the laptop-side `ssh -L` command the
// operator should run so their browser can reach the loopback-bound wizard.
// The SSH user is derived from $SUDO_USER (correct when prexorctl was sudo'd)
// or $USER as a fallback; the host is the server IP from $SSH_CONNECTION
// (the address that already accepted their SSH login, so no DNS guessing).
func printSSHTunnelInstructions(w *os.File, bindAddr string) {
	port := portFromAddr(bindAddr)

	sshUser := os.Getenv("SUDO_USER")
	if sshUser == "" {
		sshUser = os.Getenv("USER")
	}
	if sshUser == "" {
		if u, err := osuser.Current(); err == nil {
			sshUser = u.Username
		}
	}

	var serverIP string
	if parts := strings.Fields(os.Getenv("SSH_CONNECTION")); len(parts) >= 3 {
		serverIP = parts[2]
	}

	target := "<your-vps-host>"
	if sshUser != "" && serverIP != "" {
		target = sshUser + "@" + serverIP
	} else if serverIP != "" {
		target = serverIP
	}

	fmt.Fprintln(w, "  ─── SSH-tunnel mode (no TLS, no browser warning) ──────────────────────")
	fmt.Fprintln(w, "  Run this in a NEW terminal on your laptop (leave it open):")
	fmt.Fprintln(w)
	fmt.Fprintf(w, "    ssh -L %d:127.0.0.1:%d %s\n", port, port, target)
	fmt.Fprintln(w)
	fmt.Fprintln(w, "  Then open the URL printed below in your laptop browser.")
	fmt.Fprintln(w, "  ────────────────────────────────────────────────────────────────────────")
	fmt.Fprintln(w)
}

// portFromAddr returns the numeric port from a host:port string, or 9100 as
// the documented default. Tolerant of malformed input — used only for printing.
func portFromAddr(addr string) int {
	if i := strings.LastIndex(addr, ":"); i >= 0 {
		var p int
		if _, err := fmt.Sscanf(addr[i+1:], "%d", &p); err == nil && p > 0 {
			return p
		}
	}
	return 9100
}
