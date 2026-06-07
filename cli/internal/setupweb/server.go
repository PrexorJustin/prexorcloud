// Package setupweb hosts the browser-based setup wizard. It binds an HTTP
// server (HTTPS in --public mode) on the operator's machine, serves an
// embedded single-page wizard, and routes install actions back through the
// existing internal/setup helpers — so the browser is purely a presentation
// layer over the same plumbing the CLI (cli/cmd/setup_*.go) drives.
//
// Two operating modes:
//
//   - Loopback (default): binds 127.0.0.1, plain HTTP, no auth. The OS keeps
//     the port unreachable from outside the host.
//   - Public (--public flag): binds a non-loopback address, generates an
//     ephemeral self-signed TLS cert + a 32-byte cryptographic token, and
//     gates every /api/* endpoint behind the token. The wizard URL prints
//     the token in a URL fragment (#token=…) so it never appears in server
//     logs / Referer headers, and the JS reads it back via location.hash.
//
// In either mode the server is short-lived: it shuts down when the wizard
// signals completion (POST /api/exit), after a successful install (single-
// use timer), after the idle timeout, or when the operator hits Ctrl+C in
// the terminal where prexorctl is running.
package setupweb

import (
	"context"
	"crypto/tls"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed static/*
var staticFS embed.FS

// Options drives a single wizard run.
type Options struct {
	// BindAddr is the host:port the wizard server binds to. Empty defaults
	// to 127.0.0.1:9100 in loopback mode and 0.0.0.0:9100 in --public mode.
	BindAddr string

	// Public toggles "exposed to the network" mode. When true, the server
	// generates a token + self-signed TLS cert and gates /api/* behind the
	// token. Required for any non-loopback bind — passing a public BindAddr
	// without Public=true returns an error.
	Public bool

	// PublicHost is the hostname (or IP) printed in the wizard URL when
	// Public=true. Empty means "auto-detect first non-loopback IPv4".
	// Operators on NAT or with a preferred DNS name should pass this.
	PublicHost string

	// OpenBrowser controls whether prexorctl tries to launch the system
	// default browser at the wizard URL. Disabled when running headless.
	OpenBrowser bool

	// IdleTimeout is the inactivity window after which the server shuts
	// itself down. Zero defaults to 30 minutes.
	IdleTimeout time.Duration

	// ManageFirewall controls whether the wizard tries to open the bind
	// port via ufw / firewall-cmd / iptables in --public mode and remove
	// the rule on shutdown. Defaults to true when Public is set; ignored
	// in loopback mode (loopback ports don't need firewall changes).
	// Operators behind cloud-level firewalls or with bespoke setups can
	// disable this and open the port through their own tooling.
	ManageFirewall bool

	// Logf sinks human-readable progress messages to the parent CLI's
	// stdout (so the operator sees install progress in the terminal even
	// while the browser is the primary UI).
	Logf func(format string, args ...any)

	// OnCliLogin is invoked after the wizard's CLI-login mode authenticates
	// successfully against a controller. The callback receives the
	// controller URL and bearer token; it lives in the cmd package and
	// persists them via Config.SetCurrentAuth + Config.Save so the same
	// prexorctl binary the wizard came from is immediately logged in.
	// nil disables /api/cli/login and tells the frontend to hide the
	// "CLI Login" mode card.
	OnCliLogin func(controller, token string) error
}

// server holds per-run state and is what the http.Handler closures close over.
// Not exported — callers go through Serve.
type server struct {
	token       string
	limiter     *rateLimiter
	logf        func(string, ...any)
	idleTimeout time.Duration

	mu               sync.Mutex
	lastActivity     time.Time
	installCompleted atomic.Bool

	exitOnce sync.Once
	exitCh   chan struct{}
}

func (s *server) signalExit() {
	s.exitOnce.Do(func() { close(s.exitCh) })
}

func (s *server) touch() {
	s.mu.Lock()
	s.lastActivity = time.Now()
	s.mu.Unlock()
}

func (s *server) idleSince() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.lastActivity)
}

// scheduleSingleUseExit gives the wizard 60 seconds after a successful install
// to render its "next steps" panel before the server tears itself down. The
// idea is that one prexorctl-setup run writes one install — re-running it for
// the second component is a fresh process.
func (s *server) scheduleSingleUseExit() {
	if !s.installCompleted.CompareAndSwap(false, true) {
		return
	}
	go func() {
		time.Sleep(60 * time.Second)
		s.logf("Install complete — wizard exiting (single-use).")
		s.signalExit()
	}()
}

// Serve boots the wizard server and blocks until exit. Returns nil on graceful
// shutdown, non-nil if bind / TLS / option validation fails.
func Serve(ctx context.Context, opts Options) error {
	if opts.Logf == nil {
		opts.Logf = func(string, ...any) {}
	}
	if opts.IdleTimeout == 0 {
		opts.IdleTimeout = 30 * time.Minute
	}
	if opts.BindAddr == "" {
		if opts.Public {
			opts.BindAddr = "0.0.0.0:9100"
		} else {
			opts.BindAddr = "127.0.0.1:9100"
		}
	}

	bindHost, _, err := net.SplitHostPort(opts.BindAddr)
	if err != nil {
		return fmt.Errorf("setupweb: invalid BindAddr %q: %w", opts.BindAddr, err)
	}
	bindIsLoopback := isLoopbackHost(bindHost)
	if !bindIsLoopback && !opts.Public {
		return fmt.Errorf(
			"setupweb: bind address %s is not loopback. Pass --public to acknowledge "+
				"that the wizard will be reachable from the network (token + TLS will be enabled).",
			opts.BindAddr)
	}
	if opts.Public && bindIsLoopback {
		// Reject this combination — --public means "expose to the network".
		// If the operator wanted loopback, they don't need --public.
		return fmt.Errorf("setupweb: --public is set but BindAddr %s is loopback. Drop --public, or bind to 0.0.0.0/your IP.", opts.BindAddr)
	}

	staticRoot, err := fs.Sub(staticFS, "static")
	if err != nil {
		return fmt.Errorf("setupweb: embed: %w", err)
	}

	s := &server{
		limiter:      newRateLimiter(),
		logf:         opts.Logf,
		idleTimeout:  opts.IdleTimeout,
		exitCh:       make(chan struct{}),
		lastActivity: time.Now(),
	}

	var tlsConfig *tls.Config
	scheme := "http"
	if opts.Public {
		token, err := generateToken()
		if err != nil {
			return fmt.Errorf("setupweb: generate token: %w", err)
		}
		s.token = token

		host := opts.PublicHost
		if host == "" {
			host = firstNonLoopbackIPv4()
		}
		if host == "" {
			host = bindHost
		}
		cert, err := newSelfSignedCert(host)
		if err != nil {
			return fmt.Errorf("setupweb: cert: %w", err)
		}
		fp, _ := fingerprintSHA256(cert)
		opts.Logf("")
		opts.Logf("⚠ Public bind on %s. The wizard exposes setup endpoints to the network.", opts.BindAddr)
		opts.Logf("  Protections in place:")
		opts.Logf("    • 32-byte cryptographic token in the URL fragment")
		opts.Logf("    • self-signed TLS cert (SHA-256: %s)", fp)
		opts.Logf("    • idle shutdown after %s", opts.IdleTimeout)
		opts.Logf("    • single-use: the wizard exits 60s after a successful install")
		opts.Logf("    • rate limiting: 10 failed token attempts → 60s lockout per IP")
		opts.Logf("  Make sure your firewall allows the port (e.g. `ufw allow 9100/tcp`).")
		opts.Logf("")
		tlsConfig = &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		}
		scheme = "https"

		if opts.ManageFirewall {
			port := portFromBindAddr(opts.BindAddr)
			if port > 0 {
				closer := openFirewallPort(port, opts.Logf)
				defer closer()
			}
		}
	}

	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(staticRoot)))

	authed := func(h http.HandlerFunc) http.HandlerFunc {
		base := requireToken(s.token, s.limiter, h)
		return func(w http.ResponseWriter, r *http.Request) {
			s.touch()
			base(w, r)
		}
	}

	infoFeatures.CliLogin = opts.OnCliLogin != nil

	mux.HandleFunc("/api/info", authed(handleInfo))
	mux.HandleFunc("/api/install/controller", authed(s.wrapInstall(handleInstallController(opts.Logf, s.scheduleSingleUseExit))))
	mux.HandleFunc("/api/install/daemon", authed(s.wrapInstall(handleInstallDaemon(opts.Logf, s.scheduleSingleUseExit))))
	mux.HandleFunc("/api/install/dashboard", authed(s.wrapInstall(handleInstallDashboard(opts.Logf, s.scheduleSingleUseExit))))
	if opts.OnCliLogin != nil {
		mux.HandleFunc("/api/cli/login", authed(handleCliLogin(opts.OnCliLogin, opts.Logf)))
	}
	mux.HandleFunc("/api/exit", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "exiting"})
		go func() {
			time.Sleep(150 * time.Millisecond)
			s.signalExit()
		}()
	}))

	srv := &http.Server{
		Addr:              opts.BindAddr,
		Handler:           mux,
		TLSConfig:         tlsConfig,
		ReadHeaderTimeout: 5 * time.Second,
	}

	ln, err := net.Listen("tcp", opts.BindAddr)
	if err != nil {
		return fmt.Errorf("setupweb: bind %s: %w", opts.BindAddr, err)
	}
	if tlsConfig != nil {
		ln = tls.NewListener(ln, tlsConfig)
	}

	wizardURL := buildWizardURL(scheme, opts, bindHost, s.token)
	opts.Logf("Setup wizard ready at %s", wizardURL)
	opts.Logf("(Ctrl+C in this terminal exits the wizard.)")

	if opts.OpenBrowser {
		if err := openBrowser(wizardURL); err != nil {
			opts.Logf("Could not open browser automatically (%v) — paste the URL above into your browser.", err)
		}
	}

	idleCtx, cancelIdle := context.WithCancel(ctx)
	defer cancelIdle()
	go s.watchIdle(idleCtx)

	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.Serve(ln) }()

	select {
	case <-s.exitCh:
		opts.Logf("Wizard signalled exit; shutting down.")
	case <-ctx.Done():
		opts.Logf("Setup cancelled.")
	case err := <-serveErr:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
	return nil
}

func (s *server) watchIdle(ctx context.Context) {
	tick := time.NewTicker(60 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			if s.idleSince() > s.idleTimeout {
				s.logf("Idle for %s — wizard exiting.", s.idleTimeout)
				s.signalExit()
				return
			}
		}
	}
}

// wrapInstall enforces the wizard's single-use contract: once an install has
// completed, further install POSTs get a 409. The single-use exit timer itself
// is started by the handler's emitter on success (finishOK → scheduleSingleUseExit),
// not by inspecting the HTTP status — a streamed install always returns 200 and
// carries its real ok/error in the terminal "done" event, so a failed stream
// must NOT tear the wizard down (the operator can fix and retry).
func (s *server) wrapInstall(inner http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.installCompleted.Load() {
			writeJSON(w, http.StatusConflict, map[string]string{
				"error": "this wizard already completed an install. Re-run prexorctl setup --browser for another run.",
			})
			return
		}
		inner(w, r)
	}
}

// buildWizardURL renders the URL the operator should open. In public mode the
// URL fragment carries the token (#token=...), which is never sent to the
// server in HTTP requests — JS reads it back from window.location.hash and
// puts it in an Authorization header on every API call.
func buildWizardURL(scheme string, opts Options, bindHost, token string) string {
	displayHost := opts.PublicHost
	if displayHost == "" {
		if isUnspecifiedHost(bindHost) {
			displayHost = firstNonLoopbackIPv4()
		}
		if displayHost == "" {
			displayHost = bindHost
		}
	}
	_, port, _ := net.SplitHostPort(opts.BindAddr)
	if displayHost == "" {
		displayHost = "127.0.0.1"
	}
	host := net.JoinHostPort(displayHost, port)
	u := url.URL{Scheme: scheme, Host: host, Path: "/"}
	if token != "" {
		u.Fragment = "token=" + token
	}
	return u.String()
}

// openBrowser tries to launch the system's default browser at url. Best-effort
// — failure is reported to the caller, who falls back to telling the operator
// to paste the URL manually.
//
// On Linux/BSD the lookup order is:
//  1. $BROWSER env var (POSIX convention, set by most desktop sessions and
//     respected on minimal compositors like Hyprland/sway where xdg-open
//     may not be configured).
//  2. xdg-open (xdg-utils).
//  3. A short list of common browsers as a last resort.
func openBrowser(url string) error {
	switch runtime.GOOS {
	case "darwin":
		return exec.Command("open", url).Start()
	case "windows":
		return exec.Command("cmd", "/c", "start", url).Start()
	}

	if browser := os.Getenv("BROWSER"); browser != "" {
		if err := exec.Command(browser, url).Start(); err == nil {
			return nil
		}
	}
	candidates := []string{"xdg-open", "wslview", "firefox", "google-chrome", "chromium", "brave"}
	for _, name := range candidates {
		if _, err := exec.LookPath(name); err != nil {
			continue
		}
		if err := exec.Command(name, url).Start(); err == nil {
			return nil
		}
	}
	return fmt.Errorf("no usable browser launcher (set $BROWSER or install xdg-utils)")
}

// suppress unused import in some build tags
var _ = strings.TrimSpace
