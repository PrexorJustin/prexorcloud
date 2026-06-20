package cmd

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/charmbracelet/huh"

	"github.com/prexorcloud/prexorctl/internal/config"
	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

// ── Daemon setup ──────────────────────────────────────────────────────────────

func runDaemonSetup(installMode string) error {
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
		// Daemon only needs Java — Temurin download is architecture-based, not package-manager-based.
		if err := ensureJava(); err != nil {
			return err
		}
	}

	installDir := stringOrDefault(setupDaemonInstallDir, daemonInstallDir)

	// ── Release download ──────────────────────────────────────────────────────
	printSetupSection("Release Download")

	rel, err := fetchRelease()
	if err != nil {
		return err
	}

	asset, err := setup.FindAssetPrefix(rel, "cloud-daemon-")
	if err != nil {
		return err
	}

	fmt.Printf("\n  Found: %s  (%s)\n\n",
		styleSetupOK.Render(rel.TagName),
		styleSetupDim.Render(setup.HumanBytes(asset.Size)),
	)

	doDownload, err := confirmSetup(
		fmt.Sprintf("Download PrexorCloudDaemon.jar (%s)?", setup.HumanBytes(asset.Size)),
		true,
	)
	if err != nil {
		return err
	}
	if !doDownload {
		return errors.New("download cancelled")
	}

	if err := setup.CreateDaemonDirs(installDir); err != nil {
		return fmt.Errorf("failed to create install directories: %w", err)
	}

	jarDest := filepath.Join(installDir, "PrexorCloudDaemon.jar")
	fmt.Println()
	if err := setup.DownloadAndVerifyAsset(rel, asset, jarDest, setup.CosignIdentityRegexJars, true); err != nil {
		return fmt.Errorf("download + verify failed: %w", err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Downloaded + verified at "+jarDest))

	// ── Configuration ─────────────────────────────────────────────────────────
	printSetupSection("Daemon Configuration")

	cfg, err := promptDaemonConfig()
	if err != nil {
		return err
	}
	if err := setup.WriteDaemonConfig(installDir, cfg); err != nil {
		return fmt.Errorf("failed to write daemon.yml: %w", err)
	}
	fmt.Printf("  %s\n", styleSetupOK.Render("✓ Configuration written"))

	// ── Bootstrap: redeem the join token now (instead of letting the daemon do
	// it on first start) so the CLI on this host also gets a context. The daemon
	// notices the cert files exist and skips its own bootstrap.
	printSetupSection("Cluster Enrolment")
	httpPort := stringOrDefault(setupDaemonControllerHTTP, "8080")
	controllerHTTPURLs := setup.ControllerHTTPURLs(cfg.ControllerHost, cfg.Endpoints, httpPort)
	exchange, err := setup.ExchangeJoinToken(controllerHTTPURLs, cfg.JoinToken, cfg.NodeID, installDir, 30*time.Second)
	if err != nil {
		fmt.Printf("  %s Bootstrap via REST failed (%v) — daemon will retry via gRPC on first start.\n",
			styleSetupWarn.Render("!"), err)
	} else {
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ Join token redeemed; certificate installed"))
		if exchange.CliToken != "" {
			// The CLI context points at the primary controller's REST URL (first in the list).
			if msg, err := saveDaemonHostContext(controllerHTTPURLs[0], cfg.NodeID, exchange.CliToken); err != nil {
				fmt.Printf("  %s CLI context save failed: %v\n", styleSetupWarn.Render("!"), err)
			} else {
				fmt.Printf("  %s\n", styleSetupOK.Render(msg))
			}
		}
	}

	if installMode == installModeCompose {
		printSetupSection("Docker Compose Project")

		enableOnBoot, err := resolveEnableOnBoot("daemon")
		if err != nil {
			return err
		}
		startNow, err := resolveStartNow("daemon")
		if err != nil {
			return err
		}
		restartPolicy := "no"
		if enableOnBoot {
			restartPolicy = "unless-stopped"
		}

		if err := setup.WriteDaemonComposeProjectWithRestart(installDir, restartPolicy); err != nil {
			return fmt.Errorf("failed to write docker-compose.yml: %w", err)
		}
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ Compose project written"))

		if startNow {
			if err := tui.SpinWith("Starting daemon (docker compose up -d)...", func() error {
				return setup.ComposeUp(installDir)
			}); err != nil {
				return err
			}
			fmt.Printf("  %s\n", styleSetupOK.Render("✓ Daemon started"))
		}

		printDaemonDone(installDir, installMode, false)
		return nil
	}

	// ── Service install ───────────────────────────────────────────────────────
	// Two questions: auto-start on boot (systemctl enable) and start now
	// (systemctl start). The systemd unit is installed if either is yes.
	enableOnBoot, err := resolveEnableOnBoot("daemon")
	if err != nil {
		return err
	}
	startNow, err := resolveStartNow("daemon")
	if err != nil {
		return err
	}

	registered := enableOnBoot || startNow
	if registered {
		action := "Installing systemd service..."
		if enableOnBoot {
			action = "Installing systemd service (enabled on boot)..."
		}
		if err := tui.SpinWith(action, func() error {
			if enableOnBoot {
				return setup.RegisterDaemonService(installDir, setup.ManagedJREPath)
			}
			return setup.InstallDaemonUnit(installDir, setup.ManagedJREPath)
		}); err != nil {
			return fmt.Errorf("failed to install service: %w", err)
		}
	}

	if startNow {
		if err := tui.SpinWith("Starting daemon...", func() error {
			return setup.StartService(setup.DaemonServiceName())
		}); err != nil {
			return err
		}
		fmt.Printf("  %s\n", styleSetupOK.Render("✓ Daemon started"))
	}

	printDaemonDone(installDir, installMode, registered)
	return nil
}

// saveDaemonHostContext stores the DAEMON_HOST JWT minted during join-token
// exchange in the invoking user's CLI config. Result: the operator can run
// `prexorctl status` / `node list` / etc. on the daemon's VPS without a
// separate `prexorctl login` step.
func saveDaemonHostContext(controllerURL, nodeID, cliToken string) (string, error) {
	homeDir, uid, gid, err := resolveInvokingUserHome()
	if err != nil {
		return "", err
	}
	cliCfg, err := config.LoadFrom(homeDir)
	if err != nil {
		return "", err
	}
	// Name the context after the node so multi-cluster operators can see at a
	// glance which daemon a given context belongs to. Falls back to "default"
	// only if there's nothing already configured.
	contextName := nodeID
	if contextName == "" {
		contextName = "default"
	}
	cliCfg.CurrentContext = contextName
	ctx := cliCfg.Upsert(contextName)
	ctx.Controller = controllerURL
	ctx.Token = cliToken
	if err := cliCfg.SaveAs(homeDir, uid, gid); err != nil {
		return "", err
	}
	return "✓ CLI linked to cluster (context: " + contextName + ")", nil
}

func promptDaemonConfig() (setup.DaemonConfig, error) {
	hostname, _ := os.Hostname()
	cfg := setup.DaemonConfig{
		NodeID:         stringOrDefault(setupDaemonNodeID, hostname),
		ControllerHost: strings.TrimSpace(setupDaemonControllerHost),
		GRPCPort:       stringOrDefault(setupDaemonControllerGRPC, fmt.Sprintf("%d", setup.DefaultDaemonControllerGRPCPort)),
		Endpoints:      splitCSV(setupDaemonControllerEndpoints),
		JoinToken:      strings.TrimSpace(setupDaemonJoinToken),
	}

	if setupNonInteractive {
		if strings.TrimSpace(cfg.NodeID) == "" {
			return setup.DaemonConfig{}, errors.New("daemon node ID cannot be empty in non-interactive mode")
		}
		if strings.TrimSpace(cfg.ControllerHost) == "" {
			return setup.DaemonConfig{}, errors.New("daemon controller host is required in non-interactive mode")
		}
		if err := validatePort(cfg.GRPCPort); err != nil {
			return setup.DaemonConfig{}, fmt.Errorf("invalid daemon controller gRPC port: %w", err)
		}
		if strings.TrimSpace(cfg.JoinToken) == "" {
			return setup.DaemonConfig{}, errors.New("daemon join token is required in non-interactive mode")
		}
	} else {
		endpointsInput := strings.Join(cfg.Endpoints, ", ")
		err := huh.NewForm(huh.NewGroup(
			huh.NewInput().
				Title("Node ID").
				Placeholder(hostname).
				Value(&cfg.NodeID).
				Validate(func(s string) error {
					if strings.TrimSpace(s) == "" {
						return errors.New("node ID cannot be empty")
					}
					return nil
				}),

			huh.NewInput().
				Title("Controller host (IP or hostname)").
				Placeholder("192.168.1.10").
				Value(&cfg.ControllerHost).
				Validate(func(s string) error {
					if strings.TrimSpace(s) == "" {
						return errors.New("controller host is required")
					}
					return nil
				}),

			huh.NewInput().
				Title("Controller gRPC port").
				Placeholder(fmt.Sprintf("%d", setup.DefaultDaemonControllerGRPCPort)).
				Value(&cfg.GRPCPort).
				Validate(validatePort),

			huh.NewInput().
				Title("Additional controller endpoints for HA (comma-separated host:port, optional)").
				Placeholder("controller-2:9090, controller-3:9090").
				Value(&endpointsInput),

			huh.NewInput().
				Title("Join token  (generate one with: prexorctl token create)").
				Placeholder("pxt_...").
				Value(&cfg.JoinToken).
				Validate(func(s string) error {
					if strings.TrimSpace(s) == "" {
						return errors.New("join token is required")
					}
					return nil
				}),
		)).WithTheme(tui.HuhTheme()).Run()
		if err != nil {
			return setup.DaemonConfig{}, err
		}
		cfg.Endpoints = splitCSV(endpointsInput)
	}

	// Best-effort reachability check — non-fatal.
	addr := fmt.Sprintf("%s:%s", cfg.ControllerHost, cfg.GRPCPort)
	if err := setup.DialTCP(addr, 5*time.Second); err != nil {
		fmt.Printf("\n  %s Cannot reach controller at %s — continuing anyway\n",
			styleSetupWarn.Render("⚠"),
			styleSetupCode.Render(addr),
		)
	}

	return cfg, nil
}

func printDaemonDone(installDir, installMode string, hasService bool) {
	startCmd := "java --enable-preview -jar " + installDir + "/PrexorCloudDaemon.jar"
	logCmd := ""
	if installMode == installModeCompose {
		startCmd = "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " up -d"
		logCmd = "docker compose -f " + filepath.Join(installDir, "docker-compose.yml") + " logs -f daemon"
	}
	if hasService {
		startCmd = "systemctl start prexorcloud-daemon"
		logCmd = "journalctl -u prexorcloud-daemon -f"
	}

	var lines []string
	lines = append(lines, styleSetupOK.Render("✓ PrexorCloud Daemon installed"), "")
	lines = append(lines, "  "+styleSetupDim.Render("Start:")+"  "+styleSetupCode.Render(startCmd))
	if logCmd != "" {
		lines = append(lines, "  "+styleSetupDim.Render("Logs: ")+"  "+styleSetupCode.Render(logCmd))
	}

	fmt.Println(styleSetupSummary.Render(strings.Join(lines, "\n")))
	fmt.Println()
}
