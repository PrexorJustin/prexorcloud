package cmd

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/huh"

	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

func selectSetupComponent() (string, error) {
	if setupNonInteractive {
		component := strings.ToLower(strings.TrimSpace(setupComponent))
		if err := validateSetupComponent(component); err != nil {
			return "", err
		}
		return component, nil
	}

	var component string
	if err := huh.NewSelect[string]().
		Title("What would you like to install?").
		Options(
			huh.NewOption("Controller  (MongoDB + Redis + Java 25)", "controller"),
			huh.NewOption("Daemon      (Java 25 + running Controller)", "daemon"),
			huh.NewOption("Dashboard   (Nuxt SPA + nginx; needs a running Controller)", "dashboard"),
		).
		Value(&component).
		WithTheme(tui.HuhTheme()).
		Run(); err != nil {
		return "", err
	}
	return component, nil
}

func selectSetupInstallMode() (string, error) {
	if setupNonInteractive {
		mode := strings.ToLower(strings.TrimSpace(setupInstallMode))
		if mode == "" {
			mode = installModeNative
		}
		if err := validateSetupInstallMode(mode); err != nil {
			return "", err
		}
		return mode, nil
	}

	var mode string
	if err := huh.NewSelect[string]().
		Title("How should this component run?").
		Options(
			huh.NewOption("Native host install  (download JAR, install dependencies, optional systemd)", installModeNative),
			huh.NewOption("Docker Compose project  (download JAR, generate compose stack)", installModeCompose),
		).
		Value(&mode).
		WithTheme(tui.HuhTheme()).
		Run(); err != nil {
		return "", err
	}
	return mode, nil
}

func confirmSetup(title string, defaultValue bool) (bool, error) {
	if setupNonInteractive {
		return defaultValue, nil
	}

	var ok bool
	if err := huh.NewConfirm().
		Title(title).
		Value(&ok).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return false, err
	}
	return ok, nil
}

// promptDependencySource asks the user whether a dependency should be
// installed locally or an existing remote instance should be used.
func promptDependencySource(name, configured, defaultValue string) (string, error) {
	if setupNonInteractive {
		source := strings.ToLower(strings.TrimSpace(configured))
		if source == "" {
			source = defaultValue
		}
		if err := validateDependencySource(name, source); err != nil {
			return "", err
		}
		return source, nil
	}

	var source string
	if err := huh.NewSelect[string]().
		Title(fmt.Sprintf("%s — install locally or connect to existing instance?", name)).
		Options(
			huh.NewOption("Install locally on this host", "local"),
			huh.NewOption("Connect to existing remote instance", "remote"),
		).
		Value(&source).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return "", err
	}
	return source, nil
}

// promptRemoteURI asks the user for a URI and validates that it is
// reachable via TCP before returning.
func promptRemoteURI(name, placeholder, configured string) (string, error) {
	if setupNonInteractive {
		uri := strings.TrimSpace(configured)
		if uri == "" {
			return "", fmt.Errorf("%s URI cannot be empty in non-interactive mode", name)
		}
		if err := setup.DialTCPFromURI(uri, 5*time.Second); err != nil {
			return "", fmt.Errorf("cannot reach %s: %w", name, err)
		}
		return uri, nil
	}

	var uri string
	if err := huh.NewInput().
		Title(fmt.Sprintf("%s URI", name)).
		Placeholder(placeholder).
		Value(&uri).
		Validate(func(s string) error {
			if s == "" {
				return fmt.Errorf("%s URI cannot be empty", name)
			}
			if err := setup.DialTCPFromURI(s, 5*time.Second); err != nil {
				return fmt.Errorf("cannot reach %s: %w", name, err)
			}
			return nil
		}).WithTheme(tui.HuhTheme()).Run(); err != nil {
		return "", err
	}
	return uri, nil
}

// ensureDependency checks for a dependency and calls installFn if missing.
func ensureDependency(name string, detect func() string, installFn func() error) error {
	version := detect()
	if version != "" {
		fmt.Printf("  %s  %-18s %s\n",
			styleSetupOK.Render("✓"),
			name,
			styleSetupDim.Render(version),
		)
		return nil
	}

	fmt.Printf("  %s  %-18s %s\n",
		styleSetupErr.Render("✗"),
		name,
		styleSetupErr.Render("not found"),
	)

	if err := installFn(); err != nil {
		return err
	}

	version = detect()
	if version == "" {
		return fmt.Errorf("%s install appeared to succeed but is still not detectable — check system logs", name)
	}
	fmt.Printf("  %s  %-18s %s\n",
		styleSetupOK.Render("✓"),
		name,
		styleSetupDim.Render(version),
	)
	return nil
}

func fetchRelease() (setup.Release, error) {
	var rel setup.Release
	err := tui.SpinWith(fmt.Sprintf("Fetching latest release (%s)...", setup.ReleaseSource()), func() error {
		var e error
		rel, e = setup.FetchLatestRelease(setup.GithubRepo)
		return e
	})
	return rel, err
}

func validatePort(s string) error {
	var p int
	if _, err := fmt.Sscan(s, &p); err != nil || p < 1 || p > 65535 {
		return fmt.Errorf("must be a valid port number (1–65535)")
	}
	return nil
}

func validateSetupComponent(component string) error {
	switch component {
	case "controller", "daemon", "dashboard":
		return nil
	case "":
		return errors.New("non-interactive setup requires --component=controller, --component=daemon, or --component=dashboard")
	default:
		return fmt.Errorf("invalid setup component %q: use controller, daemon, or dashboard", component)
	}
}

func validateSetupInstallMode(mode string) error {
	switch mode {
	case installModeNative, installModeCompose:
		return nil
	case "":
		return errors.New("setup install mode cannot be empty")
	default:
		return fmt.Errorf("invalid install mode %q: use %s or %s", mode, installModeNative, installModeCompose)
	}
}

func validateSetupModeCompatibility(installMode string) error {
	mode := strings.ToLower(strings.TrimSpace(setupServiceMode))
	if installMode != installModeCompose {
		return nil
	}
	switch mode {
	case "", serviceModeDisable:
		return nil
	case serviceModePrompt, serviceModeEnable:
		return fmt.Errorf("--service-mode=%s cannot be used with --install-mode=%s", mode, installModeCompose)
	default:
		return fmt.Errorf("invalid service mode %q: use %q, %q, or omit it", mode, serviceModeEnable, serviceModeDisable)
	}
}

// refuseSetupOnUnsupportedOS hard-stops `prexorctl setup` on macOS/Windows.
// PrexorCloud's server-side components (controller / daemon / dashboard)
// only run on Linux — native uses systemd + the package manager, and the
// compose path pulls Linux container images we don't test under Docker
// Desktop. The CLI ships for macOS/Windows so operators can run `prexorctl
// login` and the client verbs against a Linux cluster; `setup` itself has
// no target there. The message names the OS and points to the working
// path so the operator isn't left guessing.
//
// Browser-mode setup bypasses this — the wizard's Mode screen does the
// equivalent gating in-UI (only the CLI-login card is selectable on
// non-Linux), so the browser path can still be useful on a macOS/Windows
// laptop pointed at a remote cluster.
func refuseSetupOnUnsupportedOS() error {
	if setupRuntimeGOOS == "linux" {
		return nil
	}
	return fmt.Errorf(
		"`prexorctl setup` installs server-side components, which only run on Linux (this host is %s). "+
			"Use `prexorctl login` to sign this CLI into an existing cluster instead.",
		setupRuntimeGOOS,
	)
}

func validateSetupPlatformSupport(installMode string) error {
	if installMode == installModeCompose {
		return nil
	}
	if setupRuntimeGOOS == "linux" {
		return nil
	}
	return fmt.Errorf(
		"native setup is currently supported only on Linux; use --install-mode=%s on %s",
		installModeCompose,
		setupRuntimeGOOS,
	)
}

func validateDependencySource(name, source string) error {
	switch source {
	case "local", "remote":
		return nil
	case "":
		return fmt.Errorf("%s source cannot be empty", name)
	default:
		return fmt.Errorf("invalid %s source %q: use local or remote", name, source)
	}
}

func stringOrDefault(value, fallback string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return fallback
	}
	return value
}

// ── Print helpers ─────────────────────────────────────────────────────────────

func printSetupHeader(version string) {
	ver := ""
	if version != "" {
		ver = "  " + styleSetupVersion.Render("v"+version)
	}
	fmt.Println()
	fmt.Println(styleSetupHeader.Render("  PrexorCloud Setup" + ver))
	fmt.Println()
}

func printSetupSection(title string) {
	fmt.Printf("\n  %s %s\n\n",
		styleSetupDivider.Render("──"),
		styleSetupSection.Render(title),
	)
}
