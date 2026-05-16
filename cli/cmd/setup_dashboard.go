package cmd

import (
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"github.com/charmbracelet/huh"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/config"
	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

const dashboardInstallDir = "/opt/prexorcloud/dashboard"

func runDashboardSetup(installMode string) error {
	printSetupSection("Dependency Check")

	switch installMode {
	case installModeCompose:
		if err := ensureDockerCompose(); err != nil {
			return err
		}
	default:
		return fmt.Errorf("dashboard installs ship as a compose project — re-run with --install-mode=compose")
	}

	installDir := stringOrDefault(setupDashboardInstallDir, dashboardInstallDir)

	printSetupSection("Dashboard Configuration")
	cfg, adminPassword, err := promptDashboardConfig()
	if err != nil {
		return err
	}

	// Verify controller reachability + credentials BEFORE writing any files.
	// Hard-fail is intentional: a dashboard without a working controller link is
	// useless and silently broken setups are worse than no setup.
	printSetupSection("Controller Link")
	client := api.New(cfg.ControllerURL, "", flagVerbose)
	var loginResp struct {
		Token string `json:"token"`
	}
	if err := client.Post("/api/v1/auth/login", map[string]string{
		"username": setupDashboardAdminUser,
		"password": adminPassword,
	}, &loginResp); err != nil {
		return fmt.Errorf("controller login failed at %s (check controller URL + admin password): %w", cfg.ControllerURL, err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Authenticated against "+cfg.ControllerURL))

	// Register the dashboard's public origin in the controller's CORS allow-list
	// so the browser can actually call the API. Restart-required is surfaced so
	// the operator knows to bounce the controller after.
	authedClient := api.New(cfg.ControllerURL, loginResp.Token, flagVerbose)
	var corsResp struct {
		OK              bool     `json:"ok"`
		Changed         bool     `json:"changed"`
		RestartRequired bool     `json:"restartRequired"`
		AllowedOrigins  []string `json:"allowedOrigins"`
	}
	if err := authedClient.Patch("/api/v1/admin/cors/origins", map[string]string{
		"action": "add",
		"origin": cfg.PublicURL,
	}, &corsResp); err != nil {
		fmt.Printf("  %s CORS registration failed (%v). Add manually: edit controller.yml http.cors.allowedOrigins + add %q + restart controller.\n",
			styleSetupWarn.Render("!"), err, cfg.PublicURL)
	} else if corsResp.Changed {
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ Registered "+cfg.PublicURL+" in controller CORS allow-list (live, no restart needed)"))
	} else {
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ "+cfg.PublicURL+" already in CORS allow-list"))
	}

	// Save admin JWT as a local context so the operator can use `prexorctl` on
	// the dashboard VPS without a separate login.
	if msg, err := saveDashboardHostContext(cfg.ControllerURL, loginResp.Token); err != nil {
		fmt.Printf("  %s CLI context save failed: %v\n", styleSetupWarn.Render("!"), err)
	} else {
		fmt.Printf("  %s\n", styleSetupOK.Render(msg))
	}

	// ── Files ────────────────────────────────────────────────────────────────
	printSetupSection("Compose Project")
	if err := setup.CreateDashboardDirs(installDir); err != nil {
		return fmt.Errorf("create install dirs: %w", err)
	}
	if err := setup.WriteDashboardComposeProject(installDir, cfg); err != nil {
		return fmt.Errorf("write compose project: %w", err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Compose project written to "+installDir))

	printDashboardDone(installDir, cfg)
	return nil
}

func promptDashboardConfig() (setup.DashboardConfig, string, error) {
	cfg := setup.DashboardConfig{
		InstallDir:    stringOrDefault(setupDashboardInstallDir, dashboardInstallDir),
		PublicURL:     strings.TrimSpace(setupDashboardPublicURL),
		ListenPort:    stringOrDefault(setupDashboardListenPort, "80"),
		ControllerURL: strings.TrimSpace(setupDashboardControllerURL),
		TLSMode:       stringOrDefault(setupDashboardTLSMode, "none"),
	}
	adminPassword := strings.TrimSpace(setupDashboardAdminPassword)

	if setupNonInteractive {
		if cfg.PublicURL == "" {
			return cfg, "", errors.New("--dashboard-public-url is required in non-interactive mode")
		}
		if cfg.ControllerURL == "" {
			return cfg, "", errors.New("--dashboard-controller-url is required in non-interactive mode")
		}
		if adminPassword == "" {
			return cfg, "", errors.New("--dashboard-admin-password is required in non-interactive mode")
		}
		if err := validatePublicURL(cfg.PublicURL); err != nil {
			return cfg, "", err
		}
		if err := validatePublicURL(cfg.ControllerURL); err != nil {
			return cfg, "", err
		}
		return cfg, adminPassword, nil
	}

	err := huh.NewForm(huh.NewGroup(
		huh.NewInput().
			Title("Public URL the dashboard will be served at").
			Description("Example: https://dash.example.com (used for CORS, password-reset links, self-references)").
			Placeholder("https://dash.example.com").
			Value(&cfg.PublicURL).
			Validate(validatePublicURL),

		huh.NewInput().
			Title("Controller base URL").
			Description("REST API base, e.g. https://controller.example.com:8080").
			Placeholder("http://controller-host:8080").
			Value(&cfg.ControllerURL).
			Validate(validatePublicURL),

		huh.NewInput().
			Title("Controller admin password").
			Description("Used once to register this dashboard's CORS origin; the wizard discards it after.").
			EchoMode(huh.EchoModePassword).
			Value(&adminPassword).
			Validate(func(s string) error {
				if strings.TrimSpace(s) == "" {
					return errors.New("admin password is required")
				}
				return nil
			}),

		huh.NewInput().
			Title("Local host port").
			Description("Where the nginx container binds (80 / 443 / 3000).").
			Placeholder("80").
			Value(&cfg.ListenPort).
			Validate(validatePort),
	)).WithTheme(tui.HuhTheme()).Run()
	if err != nil {
		return cfg, "", err
	}

	// Best-effort reachability check — non-fatal here because the wizard will
	// hard-fail moments later when it tries to actually log in.
	if err := setup.DialTCPFromURI(cfg.ControllerURL, 5*time.Second); err != nil {
		fmt.Printf("\n  %s Controller not reachable at %s yet — continuing; login will be attempted next\n",
			styleSetupWarn.Render("⚠"),
			styleSetupCode.Render(cfg.ControllerURL),
		)
	}
	return cfg, adminPassword, nil
}

func saveDashboardHostContext(controllerURL, token string) (string, error) {
	homeDir, uid, gid, err := resolveInvokingUserHome()
	if err != nil {
		return "", err
	}
	cliCfg, err := config.LoadFrom(homeDir)
	if err != nil {
		return "", err
	}
	contextName := "dashboard"
	cliCfg.CurrentContext = contextName
	ctx := cliCfg.Upsert(contextName)
	ctx.Controller = controllerURL
	ctx.Token = token
	if err := cliCfg.SaveAs(homeDir, uid, gid); err != nil {
		return "", err
	}
	return "✓ CLI linked to cluster (context: " + contextName + ")", nil
}

func validatePublicURL(s string) error {
	s = strings.TrimSpace(s)
	if s == "" {
		return errors.New("URL is required")
	}
	u, err := url.Parse(s)
	if err != nil {
		return fmt.Errorf("not a valid URL: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return errors.New("URL must start with http:// or https://")
	}
	if u.Host == "" {
		return errors.New("URL must include a hostname")
	}
	return nil
}

func printDashboardDone(installDir string, cfg setup.DashboardConfig) {
	startCmd := "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " up -d"
	logCmd := "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " logs -f dashboard"

	var lines []string
	lines = append(lines, styleSetupOK.Render("✓ PrexorCloud Dashboard prepared"), "")
	lines = append(lines, "  "+styleSetupDim.Render("Start:")+"  "+styleSetupCode.Render(startCmd))
	lines = append(lines, "  "+styleSetupDim.Render("Logs: ")+"  "+styleSetupCode.Render(logCmd))
	lines = append(lines, "  "+styleSetupDim.Render("Open: ")+"  "+styleSetupCode.Render(cfg.PublicURL))

	fmt.Println(styleSetupSummary.Render(strings.Join(lines, "\n")))
	fmt.Println()
}
