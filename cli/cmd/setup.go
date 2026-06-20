package cmd

import (
	"fmt"
	"os"
	"runtime"
	"strings"

	"github.com/charmbracelet/lipgloss"
	"github.com/spf13/cobra"
)

const (
	controllerInstallDir = "/opt/prexorcloud/controller"
	daemonInstallDir     = "/opt/prexorcloud/daemon"

	installModeNative  = "native"
	installModeCompose = "compose"

	serviceModePrompt  = "prompt"
	serviceModeEnable  = "enable"
	serviceModeDisable = "disable"

	startupValidationPrompt  = "prompt"
	startupValidationEnable  = "enable"
	startupValidationDisable = "disable"

	// boot/start prompt modes — shared by --boot-mode and --start-mode.
	lifecycleModePrompt  = "prompt"
	lifecycleModeEnable  = "enable"
	lifecycleModeDisable = "disable"
)

// ── Styles ────────────────────────────────────────────────────────────────────

// Theme tokens — kept as lipgloss v1 (huh and old setup live on v1) to match the
// new brand-purple palette from the design handoff.
var (
	styleSetupHeader = lipgloss.NewStyle().
				Bold(true).
				Foreground(lipgloss.Color("#c77dff")).
				BorderStyle(lipgloss.RoundedBorder()).
				BorderForeground(lipgloss.Color("#c77dff")).
				Padding(0, 2)

	styleSetupVersion = lipgloss.NewStyle().
				Foreground(lipgloss.Color("#9b95ad"))

	styleSetupSection = lipgloss.NewStyle().
				Bold(true)

	styleSetupDivider = lipgloss.NewStyle().
				Foreground(lipgloss.Color("#4a455a"))

	styleSetupOK = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#6dd58c")).
			Bold(true)

	styleSetupErr = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#ff6b6b")).
			Bold(true)

	styleSetupWarn = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#ffc56e"))

	styleSetupDim = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#6a6478"))

	styleSetupSummary = lipgloss.NewStyle().
				BorderStyle(lipgloss.RoundedBorder()).
				BorderForeground(lipgloss.Color("#6dd58c")).
				Padding(1, 3).
				MarginTop(1)

	styleSetupCode = lipgloss.NewStyle().
			Foreground(lipgloss.Color("#6fd6e0"))

	setupNonInteractive            bool
	setupComponent                 string
	setupInstallMode               string
	setupServiceMode               string
	setupStartupValidationMode     string
	setupBootMode                  string
	setupStartMode                 string
	setupControllerInstallDir      string
	setupControllerMongoMode       string
	setupControllerMongoURI        string
	setupControllerRedisMode       string
	setupControllerRedisURI        string
	setupControllerHTTPPort        string
	setupControllerGRPCPort        string
	setupControllerCORSOrigin      string
	setupDaemonInstallDir          string
	setupDaemonNodeID              string
	setupDaemonControllerHost      string
	setupDaemonControllerGRPC      string
	setupDaemonControllerHTTP      string
	setupDaemonControllerEndpoints string
	setupDaemonJoinToken           string

	setupDashboardInstallDir    string
	setupDashboardPublicURL     string
	setupDashboardServeMode     string
	setupDashboardTLSMode       string
	setupDashboardTLSEmail      string
	setupDashboardControllerURL string
	setupDashboardAdminUser     string
	setupDashboardAdminPassword string
	setupDashboardListenPort    string
	setupRuntimeGOOS            = runtime.GOOS
)

// ── Command ───────────────────────────────────────────────────────────────────

var setupCmd = &cobra.Command{
	Use:   "setup",
	Short: "Install and configure the Controller, Daemon, or Dashboard",
	Long: `Interactive setup flow for PrexorCloud components.

Opens a browser wizard by default (loopback only, 127.0.0.1:9100) that asks
every required field with smart defaults. Headless hosts (no DISPLAY/BROWSER,
containers, CI) automatically fall back to the TTY form. Pass --no-browser to
force TTY mode.

The wizard installs one component at a time: Controller (on the cluster's
control-plane VPS), Daemon (on each worker VPS), or Dashboard (on its own
VPS or alongside the controller). The CLI auto-links to the controller after
install — no separate 'prexorctl login' needed on the same host.

Native installs that provision packages or register systemd units must be
run as root. Compose mode runs as the invoking user.`,
	// Override parent PersistentPreRunE — setup runs before any controller exists.
	PersistentPreRunE: func(cmd *cobra.Command, _ []string) error {
		// --no-browser is sugar for --browser=false. Flip the underlying flag
		// here so runSetup only has to consult setupBrowser.
		if v, err := cmd.Flags().GetBool("no-browser"); err == nil && v {
			setupBrowser = false
		}
		return nil
	},
	RunE: runSetup,
}

func runSetup(cmd *cobra.Command, _ []string) error {
	// Browser is the default — it asks every field with smart defaults instead of
	// piling them into a terminal form. Headless hosts (no DISPLAY/$BROWSER,
	// container, $CI) fall back to TTY automatically. --no-browser forces TTY
	// for operators who prefer it; --browser=true is implied by the new default.
	//
	if browserSetupRequested(setupBrowser, setupNonInteractive, setupBrowserPublic, setupBrowserSSHTunnel, isHeadless(), inSSHSession()) {
		return runBrowserSetup()
	}

	// `setup` installs server-side components, which only run on Linux. The
	// CLI itself ships for macOS/Windows so operators can run `prexorctl
	// login`, `module install`, etc. against a Linux cluster — but there's
	// no install target here, and no TTY-side CLI-login flow either (that
	// lives in the browser wizard's CLI mode). Refuse early with the redirect
	// before we start asking for component / install-mode.
	if err := refuseSetupOnUnsupportedOS(); err != nil {
		return err
	}

	printSetupHeader(cmd.Root().Version)

	component, err := selectSetupComponent()
	if err != nil {
		return err
	}

	installMode, err := selectSetupInstallMode()
	if err != nil {
		return err
	}
	if err := validateSetupModeCompatibility(installMode); err != nil {
		return err
	}
	if err := validateSetupPlatformSupport(installMode); err != nil {
		return err
	}

	switch component {
	case "controller":
		return runControllerSetup(installMode)
	case "daemon":
		return runDaemonSetup(installMode)
	case "dashboard":
		return runDashboardSetup(installMode)
	default:
		return fmt.Errorf("invalid selection")
	}
}

// browserSetupRequested decides whether `prexorctl setup` should hand off
// to the browser wizard (runBrowserSetup) instead of the TTY prompts.
//
// --public and --ssh-tunnel are explicit headless overrides: the operator is
// saying "I know there's no local desktop browser; bind a wizard server and
// I'll open it from another machine — either via a public HTTPS bind with a
// token (--public) or over an SSH port-forward (--ssh-tunnel)." Without these
// branches, every SSH'd-into VPS would silently fall back to TTY even when
// the operator asked for the wizard — and install.sh's SSH-detected
// auto-args would do nothing.
//
// `inSSH` opts the SSH'd-headless-box case into the browser path too: when
// the operator's already inside an SSH session, the right default is the
// tunnelled wizard (runBrowserSetup flips on --ssh-tunnel automatically),
// not a silent fall-through to TTY prompts that lose the polished UI.
func browserSetupRequested(browser, nonInteractive, public, sshTunnel, headless, inSSH bool) bool {
	if !browser || nonInteractive {
		return false
	}
	if public || sshTunnel || !headless {
		return true
	}
	return inSSH
}

// inSSHSession reports whether the current process is sitting inside an SSH
// session on the local box. The check is "did sshd inherit a SSH_CONNECTION
// into our environment" — the variable is set by the OpenSSH server on the
// remote machine when a client logs in and carries `<client-ip> <client-port>
// <server-ip> <server-port>`, so a four-field value is a strong signal. We
// don't trust SSH_CLIENT alone — it's set by older sshd versions and isn't
// universally populated.
func inSSHSession() bool {
	c := os.Getenv("SSH_CONNECTION")
	if c == "" {
		return false
	}
	// A real SSH_CONNECTION has 4 space-separated fields. Anything else is
	// a leftover env var someone copied around — don't take it as a signal.
	parts := strings.Fields(c)
	return len(parts) == 4
}

// isHeadless reports whether the current process can reasonably launch a desktop
// browser. False on hosts with no $DISPLAY/$BROWSER, inside containers, and in
// CI — in those cases setup falls back to the TTY form, avoiding a hung "waiting
// for browser to connect" prompt the operator can't fulfil.
func isHeadless() bool {
	if os.Getenv("CI") != "" || os.Getenv("PREXOR_NO_BROWSER") != "" {
		return true
	}
	if _, err := os.Stat("/.dockerenv"); err == nil {
		return true
	}
	if setupRuntimeGOOS == "linux" && os.Getenv("DISPLAY") == "" && os.Getenv("WAYLAND_DISPLAY") == "" && os.Getenv("BROWSER") == "" {
		return true
	}
	return false
}

func init() {
	setupCmd.Flags().BoolVar(&setupBrowser, "browser", true,
		"Open a loopback wizard in your default browser. The browser is the default UI; "+
			"pass --browser=false (or --no-browser) to fall back to the TTY prompts. "+
			"Headless hosts (no DISPLAY/BROWSER, containers, CI) fall back automatically.")
	setupCmd.Flags().Bool("no-browser", false,
		"Force the TTY prompt flow instead of the browser wizard. Shorthand for --browser=false.")
	setupCmd.Flags().StringVar(&setupBrowserAddr, "browser-addr", "",
		"host:port the wizard listens on. Empty defaults to 127.0.0.1:9100, "+
			"or 0.0.0.0:9100 when --public is set.")
	setupCmd.Flags().BoolVar(&setupBrowserOpen, "browser-open", true,
		"Try to launch the system default browser at the wizard URL. Disable on headless hosts.")
	setupCmd.Flags().BoolVar(&setupBrowserSSHTunnel, "ssh-tunnel", false,
		"Recommended for remote VPSes. Bind 127.0.0.1 (no TLS, no browser warning) and "+
			"print the laptop-side `ssh -L` command to run — the wizard traffic rides your "+
			"existing SSH tunnel. Auto-enabled when $SSH_CONNECTION is set on a headless box. "+
			"Overrides --public.")
	setupCmd.Flags().BoolVar(&setupBrowserPublic, "public", false,
		"Not recommended unless --ssh-tunnel doesn't fit. Bind the wizard to a non-loopback "+
			"address with TLS + token auth enabled, so you can paste a token-protected URL into "+
			"a remote browser. Triggers a self-signed-cert warning unless you front it with a "+
			"trusted certificate, and exposes the wizard's port to anyone who can reach the host "+
			"during the setup window.")
	setupCmd.Flags().StringVar(&setupBrowserHost, "public-host", "",
		"Hostname or IP printed in the wizard URL when --public is set. "+
			"Defaults to the first non-loopback IPv4 detected on this host. Override when the "+
			"machine sits behind NAT and you want the URL to use a public DNS name.")
	setupCmd.Flags().DurationVar(&setupBrowserIdle, "browser-idle-timeout", 0,
		"Auto-shutdown the wizard after this much inactivity. Empty = 30m default.")
	setupCmd.Flags().BoolVar(&setupBrowserFirewall, "manage-firewall", true,
		"In --public mode: try to open the wizard's port via ufw / firewall-cmd / iptables, "+
			"and remove the rule on shutdown. Disable when you'd rather manage the firewall "+
			"yourself or you're behind a cloud-provider firewall that prexorctl can't see.")
	setupCmd.Flags().BoolVar(&setupNonInteractive, "non-interactive", false, "Run setup without prompts using flags and defaults")
	setupCmd.Flags().StringVar(&setupComponent, "component", "", "Component to install in non-interactive mode: controller or daemon")
	setupCmd.Flags().StringVar(&setupInstallMode, "install-mode", "", "Install mode: native or compose")
	setupCmd.Flags().StringVar(&setupServiceMode, "service-mode", "", "Service registration mode: prompt, enable, or disable")
	setupCmd.Flags().StringVar(&setupStartupValidationMode, "startup-validation-mode", "", "Startup validation mode after native controller service registration: prompt, enable, or disable")
	setupCmd.Flags().StringVar(&setupBootMode, "boot-mode", "", "Auto-start on boot: prompt, enable, or disable. Native: systemctl enable / Docker: restart=unless-stopped")
	setupCmd.Flags().StringVar(&setupStartMode, "start-mode", "", "Start the component once setup finishes: prompt, enable, or disable. Native: systemctl start / Docker: docker compose up -d")

	setupCmd.Flags().StringVar(&setupControllerInstallDir, "controller-install-dir", controllerInstallDir, "Controller install directory")
	setupCmd.Flags().StringVar(&setupControllerMongoMode, "controller-mongo-mode", "", "Controller MongoDB source in non-interactive mode: local or remote")
	setupCmd.Flags().StringVar(&setupControllerMongoURI, "controller-mongo-uri", "", "Controller MongoDB URI for remote MongoDB in non-interactive mode")
	setupCmd.Flags().StringVar(&setupControllerRedisMode, "controller-redis-mode", "", "Controller Redis source in non-interactive mode: local or remote")
	setupCmd.Flags().StringVar(&setupControllerRedisURI, "controller-redis-uri", "", "Controller Redis URI for remote Redis in non-interactive mode")
	setupCmd.Flags().StringVar(&setupControllerHTTPPort, "controller-http-port", "", "Controller HTTP port in non-interactive mode")
	setupCmd.Flags().StringVar(&setupControllerGRPCPort, "controller-grpc-port", "", "Controller gRPC port in non-interactive mode")
	setupCmd.Flags().StringVar(&setupControllerCORSOrigin, "controller-cors-origin", "", "Controller dashboard CORS origin in non-interactive mode")

	setupCmd.Flags().StringVar(&setupDaemonInstallDir, "daemon-install-dir", daemonInstallDir, "Daemon install directory")
	setupCmd.Flags().StringVar(&setupDaemonNodeID, "daemon-node-id", "", "Daemon node ID in non-interactive mode")
	setupCmd.Flags().StringVar(&setupDaemonControllerHost, "daemon-controller-host", "", "Daemon controller host in non-interactive mode")
	setupCmd.Flags().StringVar(&setupDaemonControllerGRPC, "daemon-controller-grpc-port", "", "Daemon controller gRPC port in non-interactive mode")
	setupCmd.Flags().StringVar(&setupDaemonControllerHTTP, "daemon-controller-http-port", "", "Daemon controller HTTP port (for join-token redemption) in non-interactive mode; default 8080")
	setupCmd.Flags().StringVar(&setupDaemonControllerEndpoints, "daemon-controller-endpoints", "", "Additional controller endpoints for HA (comma-separated host:port) in non-interactive mode")
	setupCmd.Flags().StringVar(&setupDaemonJoinToken, "daemon-join-token", "", "Daemon join token in non-interactive mode")

	setupCmd.Flags().StringVar(&setupDashboardInstallDir, "dashboard-install-dir", "/opt/prexorcloud/dashboard", "Dashboard install directory")
	setupCmd.Flags().StringVar(&setupDashboardPublicURL, "dashboard-public-url", "", "Public URL the dashboard will be served at (e.g. https://dash.example.com)")
	setupCmd.Flags().StringVar(&setupDashboardServeMode, "dashboard-serve-mode", "nginx", "How to serve the bundle: nginx | systemd-nginx | behind-existing-proxy")
	setupCmd.Flags().StringVar(&setupDashboardTLSMode, "dashboard-tls-mode", "none", "TLS mode: none | letsencrypt | custom | terminated-upstream")
	setupCmd.Flags().StringVar(&setupDashboardTLSEmail, "dashboard-tls-email", "", "ACME registration email (letsencrypt mode only)")
	setupCmd.Flags().StringVar(&setupDashboardControllerURL, "dashboard-controller-url", "", "Controller base URL (e.g. https://controller.example.com:8080)")
	setupCmd.Flags().StringVar(&setupDashboardAdminUser, "dashboard-admin-user", "admin", "Controller admin username")
	setupCmd.Flags().StringVar(&setupDashboardAdminPassword, "dashboard-admin-password", "", "Controller admin password (used once to register CORS origin, then discarded)")
	setupCmd.Flags().StringVar(&setupDashboardListenPort, "dashboard-listen-port", "80", "Local port the dashboard listens on")
}
