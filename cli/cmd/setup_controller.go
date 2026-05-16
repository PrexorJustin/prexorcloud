package cmd

import (
	"errors"
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/charmbracelet/huh"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/config"
	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

// ── Controller setup ──────────────────────────────────────────────────────────

func runControllerSetup(installMode string) error {
	printSetupSection("Dependency Check")

	switch installMode {
	case installModeCompose:
		if err := ensureDockerCompose(); err != nil {
			return err
		}
	default:
		if os.Getuid() != 0 {
			return fmt.Errorf("native setup must be run as root — try: sudo prexorctl setup")
		}
		if err := ensureJava(); err != nil {
			return err
		}
	}

	mongoURI, mongoLocal, err := resolveMongoDB(installMode)
	if err != nil {
		return err
	}
	redisURI, redisLocal, err := resolveRedis(installMode)
	if err != nil {
		return err
	}

	installDir := stringOrDefault(setupControllerInstallDir, controllerInstallDir)

	// ── Release download ──────────────────────────────────────────────────────
	printSetupSection("Release Download")

	rel, err := fetchRelease()
	if err != nil {
		return err
	}

	asset, err := setup.FindAssetPrefix(rel, "cloud-controller-")
	if err != nil {
		return err
	}

	fmt.Printf("\n  Found: %s  (%s)\n\n",
		styleSetupOK.Render(rel.TagName),
		styleSetupDim.Render(setup.HumanBytes(asset.Size)),
	)

	doDownload, err := confirmSetup(
		fmt.Sprintf("Download PrexorCloudController.jar (%s)?", setup.HumanBytes(asset.Size)),
		true,
	)
	if err != nil {
		return err
	}
	if !doDownload {
		return errors.New("download cancelled")
	}

	if err := setup.CreateControllerDirs(installDir); err != nil {
		return fmt.Errorf("failed to create install directories: %w", err)
	}

	jarDest := filepath.Join(installDir, "PrexorCloudController.jar")
	fmt.Println()
	if err := setup.DownloadAndVerifyAsset(rel, asset, jarDest, setup.CosignIdentityRegexJars, true); err != nil {
		return fmt.Errorf("download + verify failed: %w", err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Downloaded + verified at "+jarDest))

	// ── Configuration ─────────────────────────────────────────────────────────
	printSetupSection("Controller Configuration")

	cfg, err := promptControllerConfig(mongoURI, redisURI)
	if err != nil {
		return err
	}
	if err := setup.WriteControllerConfig(installDir, cfg); err != nil {
		return fmt.Errorf("failed to write controller.yml: %w", err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Configuration written"))

	if msg, err := autoConfigureCLI(cfg); err != nil {
		fmt.Printf("  %s CLI auto-config failed: %v\n", styleSetupWarn.Render("!"), err)
	} else if msg != "" {
		fmt.Printf("  %s\n", styleSetupOK.Render(msg))
	}

	if installMode == installModeCompose {
		printSetupSection("Docker Compose Project")
		if err := setup.WriteControllerComposeProject(
			installDir,
			cfg,
			setup.ControllerComposeProjectOptions{
				LocalMongo: mongoLocal,
				LocalRedis: redisLocal,
			},
		); err != nil {
			return fmt.Errorf("failed to write docker-compose.yml: %w", err)
		}
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ Compose project written"))
		printControllerDone(installDir, installMode, false)
		return nil
	}

	// ── Service registration ──────────────────────────────────────────────────
	registered, err := promptServiceRegistration(func() error {
		return setup.RegisterControllerService(
			installDir,
			setup.ManagedJREPath,
			setup.ControllerServiceOptions{
				LocalMongo: mongoLocal,
				LocalRedis: redisLocal,
			},
		)
	})
	if err != nil {
		return err
	}

	startupValidated := false
	if installMode == installModeNative {
		validateStartup, err := resolveControllerStartupValidation(registered)
		if err != nil {
			return err
		}
		if validateStartup {
			printSetupSection("Startup Validation")
			check := setup.StartAndValidateControllerService(cfg, 45*time.Second)
			printControllerVerification(setup.ControllerVerificationReport{Checks: []setup.VerificationCheck{check}})
			startupValidated = check.Status == setup.VerificationOK
		}
	}

	if installMode == installModeNative {
		printSetupSection("Post-Install Verification")
		report := setup.VerifyNativeControllerInstall(
			installDir,
			cfg,
			setup.ControllerVerificationOptions{
				LocalMongo:        mongoLocal,
				LocalRedis:        redisLocal,
				ServiceRegistered: registered,
			},
		)
		if startupValidated {
			report.Checks = append([]setup.VerificationCheck{{
				Name:   "Startup validation",
				Status: setup.VerificationOK,
				Detail: "controller booted and passed live health validation",
			}}, report.Checks...)
		}
		printControllerVerification(report)
	}

	// Auto-login the local CLI once the controller is up. Only possible after
	// startup validation actually ran (so the bootstrap password file has been
	// written and the API is reachable). Compose mode leaves the controller
	// stopped — operator runs `prexorctl login` themselves after `docker
	// compose up -d`. printControllerDone hints both paths.
	if startupValidated {
		if msg, err := autoLoginAfterControllerReady(installDir, cfg); err != nil {
			fmt.Printf("  %s Auto-login skipped: %v\n", styleSetupWarn.Render("!"), err)
		} else if msg != "" {
			fmt.Printf("  %s\n", styleSetupOK.Render(msg))
		}
	}

	printControllerDone(installDir, installMode, registered)
	return nil
}

// promptControllerConfig asks the user for ports and CORS origin, reusing
// the Mongo/Redis URIs already resolved earlier in the setup flow.
func promptControllerConfig(mongoURI, redisURI string) (setup.ControllerConfig, error) {
	cfg := setup.ControllerConfig{
		HTTPPort:       stringOrDefault(setupControllerHTTPPort, fmt.Sprintf("%d", setup.DefaultControllerHTTPPort)),
		GRPCPort:       stringOrDefault(setupControllerGRPCPort, fmt.Sprintf("%d", setup.DefaultControllerGRPCPort)),
		RuntimeProfile: "production",
		MongoURI:       mongoURI,
		RedisURI:       redisURI,
		CORSOrigins:    []string{stringOrDefault(setupControllerCORSOrigin, "http://localhost:3000")},
	}
	corsOrigin := cfg.CORSOrigins[0]

	if setupNonInteractive {
		if err := validatePort(cfg.HTTPPort); err != nil {
			return setup.ControllerConfig{}, fmt.Errorf("invalid controller HTTP port: %w", err)
		}
		if err := validatePort(cfg.GRPCPort); err != nil {
			return setup.ControllerConfig{}, fmt.Errorf("invalid controller gRPC port: %w", err)
		}
		if strings.TrimSpace(corsOrigin) == "" {
			return setup.ControllerConfig{}, fmt.Errorf("controller CORS origin cannot be empty")
		}
		return cfg, nil
	}

	err := huh.NewForm(huh.NewGroup(
		huh.NewInput().
			Title("HTTP port").
			Placeholder("8080").
			Value(&cfg.HTTPPort).
			Validate(validatePort),

		huh.NewInput().
			Title("gRPC port").
			Placeholder(fmt.Sprintf("%d", setup.DefaultControllerGRPCPort)).
			Value(&cfg.GRPCPort).
			Validate(validatePort),

		huh.NewInput().
			Title("Dashboard CORS origin").
			Placeholder("http://localhost:3000").
			Value(&corsOrigin),
	)).WithTheme(tui.HuhTheme()).Run()

	if err != nil {
		return setup.ControllerConfig{}, err
	}
	cfg.CORSOrigins = []string{corsOrigin}
	return cfg, nil
}

func printControllerVerification(report setup.ControllerVerificationReport) {
	if len(report.Checks) == 0 {
		fmt.Printf("  %s No verification checks were run\n", styleSetupWarn.Render("!"))
		return
	}

	for _, check := range report.Checks {
		icon := styleSetupOK.Render("✓")
		detail := styleSetupDim.Render(check.Detail)
		switch check.Status {
		case setup.VerificationWarn:
			icon = styleSetupWarn.Render("!")
			detail = styleSetupWarn.Render(check.Detail)
		case setup.VerificationFail:
			icon = styleSetupErr.Render("✗")
			detail = styleSetupErr.Render(check.Detail)
		}

		fmt.Printf("  %s  %-28s %s\n", icon, check.Name, detail)
		for _, recovery := range check.Recovery {
			fmt.Printf("      %s\n", styleSetupCode.Render(recovery))
		}
	}
}

func printControllerDone(installDir, installMode string, hasService bool) {
	startCmd := "java --enable-preview -jar " + installDir + "/PrexorCloudController.jar"
	logCmd := ""
	if installMode == installModeCompose {
		startCmd = "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " up -d"
		logCmd = "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " logs -f controller"
	}
	if hasService {
		startCmd = "systemctl start prexorcloud-controller"
		logCmd = "journalctl -u prexorcloud-controller -f"
	}

	var lines []string
	lines = append(lines, styleSetupOK.Render("✓ PrexorCloud Controller installed"), "")
	lines = append(lines, "  "+styleSetupDim.Render("Start:")+"  "+styleSetupCode.Render(startCmd))
	if logCmd != "" {
		lines = append(lines, "  "+styleSetupDim.Render("Logs: ")+"  "+styleSetupCode.Render(logCmd))
	}
	lines = append(lines,
		"  "+styleSetupDim.Render("Login:")+"  "+styleSetupCode.Render("prexorctl login"),
		"",
		"  "+styleSetupDim.Render("Initial admin password (generated on first start):"),
		"    "+styleSetupCode.Render("cat "+installDir+"/config/.initial-admin-password"),
	)

	fmt.Println(styleSetupSummary.Render(strings.Join(lines, "\n")))
	fmt.Println()
}

// autoConfigureCLI writes the controller URL to the invoking user's CLI config
// (~/.prexorcloud/config.yml). When setup runs under sudo, SUDO_USER is honored
// so the resulting file belongs to the original user, not root. Returns a
// human-readable status message on success.
func autoConfigureCLI(cfg setup.ControllerConfig) (string, error) {
	homeDir, uid, gid, err := resolveInvokingUserHome()
	if err != nil {
		return "", err
	}

	port := setup.DefaultControllerHTTPPort
	if v, perr := strconv.Atoi(cfg.HTTPPort); perr == nil && v > 0 {
		port = v
	}
	url := fmt.Sprintf("http://localhost:%d", port)

	cliCfg, err := config.LoadFrom(homeDir)
	if err != nil {
		return "", err
	}
	if cliCfg.CurrentContext == "" {
		cliCfg.CurrentContext = "default"
	}
	cliCfg.Upsert(cliCfg.CurrentContext).Controller = url

	if err := cliCfg.SaveAs(homeDir, uid, gid); err != nil {
		return "", err
	}
	return "✓ CLI configured (" + url + ") -- run 'prexorctl login' to authenticate", nil
}

// autoLoginAfterControllerReady waits for the controller's first-boot artifacts
// (admin user + bootstrap password file) to appear, then exchanges them for a
// JWT and saves it as the current CLI context's token. End state: `prexorctl
// status` / `node list` / etc. work immediately, no separate `prexorctl login`
// step. The bootstrap password file is left on disk — the controller deletes it
// itself once the admin user changes their password from the dashboard.
func autoLoginAfterControllerReady(installDir string, cfg setup.ControllerConfig) (string, error) {
	port := setup.DefaultControllerHTTPPort
	if v, perr := strconv.Atoi(cfg.HTTPPort); perr == nil && v > 0 {
		port = v
	}
	url := fmt.Sprintf("http://localhost:%d", port)

	passwordFile := filepath.Join(installDir, "config", ".initial-admin-password")
	password, err := readBootstrapPassword(passwordFile, 30*time.Second)
	if err != nil {
		return "", err
	}

	client := api.New(url, "", flagVerbose)
	var resp struct {
		Token string `json:"token"`
	}
	if err := client.Post("/api/v1/auth/login", map[string]string{
		"username": "admin",
		"password": password,
	}, &resp); err != nil {
		return "", fmt.Errorf("login: %w", err)
	}

	homeDir, uid, gid, err := resolveInvokingUserHome()
	if err != nil {
		return "", err
	}
	cliCfg, err := config.LoadFrom(homeDir)
	if err != nil {
		return "", err
	}
	cliCfg.SetCurrentAuth(url, resp.Token)
	if err := cliCfg.SaveAs(homeDir, uid, gid); err != nil {
		return "", err
	}
	return "✓ CLI logged in as admin (context: " + cliCfg.CurrentContext + ")", nil
}

// readBootstrapPassword polls for the .initial-admin-password file the
// controller writes on first start, returning its contents when it appears.
// Times out if the file never shows up — typically because users already exist
// (returning install) or because the controller failed to come up cleanly.
func readBootstrapPassword(path string, timeout time.Duration) (string, error) {
	deadline := time.Now().Add(timeout)
	for {
		data, err := os.ReadFile(path)
		if err == nil {
			pw := strings.TrimSpace(string(data))
			if pw != "" {
				return pw, nil
			}
		}
		if time.Now().After(deadline) {
			return "", fmt.Errorf("%s did not appear within %s (admin user may already exist — run 'prexorctl login' manually)", path, timeout)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// resolveInvokingUserHome returns the home directory of the user that invoked
// the current process, honoring SUDO_USER when running under sudo. Returns
// (home, uid, gid, err); uid is 0 when no chown is needed.
func resolveInvokingUserHome() (string, int, int, error) {
	if sudoUser := os.Getenv("SUDO_USER"); sudoUser != "" && os.Geteuid() == 0 {
		u, err := user.Lookup(sudoUser)
		if err != nil {
			return "", 0, 0, fmt.Errorf("lookup SUDO_USER %q: %w", sudoUser, err)
		}
		uid, err := strconv.Atoi(u.Uid)
		if err != nil {
			return "", 0, 0, err
		}
		gid, err := strconv.Atoi(u.Gid)
		if err != nil {
			return "", 0, 0, err
		}
		return u.HomeDir, uid, gid, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", 0, 0, err
	}
	return home, 0, 0, nil
}
