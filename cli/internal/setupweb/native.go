package setupweb

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/prexorcloud/prexorctl/internal/setup"
)

// Install-mode wire values shared by the controller/daemon/dashboard handlers.
const (
	installModeCompose = "compose"
	installModeNative  = "native"
)

// installSupported reports whether this host can run the server-side
// components (controller / daemon / dashboard) at all, in any install mode.
// PrexorCloud's runtime story is Linux-only: native uses systemd + the
// package manager, and the compose path pulls Linux container images that
// we don't test or sign off on under Docker Desktop. macOS and Windows are
// client-only — `prexorctl login` against an existing cluster works there,
// but `setup` of any component does not.
//
// Both the browser wizard (/api/info → Mode screen gating) and the CLI's
// TTY/non-interactive setup paths consult this; on a false result the
// wizard hides every install-card except CLI-login, and `prexorctl setup`
// refuses with a redirect to `prexorctl login`.
func installSupported() (bool, string) {
	if runtime.GOOS != "linux" {
		return false, fmt.Sprintf(
			"Server-side components run on Linux. This host is %s — use `prexorctl login` to sign this CLI into an existing cluster.",
			runtime.GOOS)
	}
	return true, ""
}

// nativeInstallAvailable reports whether this host can perform a native
// (systemd + package-manager) install from the browser wizard, and if not,
// why. Two hard requirements:
//
//   - Linux. Native installs assume systemd/OpenRC + a supported package
//     manager; macOS/Windows have neither. (installSupported also catches
//     this — the OS check is duplicated here so the message names the
//     specific Native blocker.)
//   - root. Writing /etc/systemd/system, importing apt keys, and running
//     `systemctl` all need uid 0. The install one-liner elevates the wizard
//     automatically (direct exec when SSH'd in as root, else sudo); a wizard
//     launched unprivileged can only do the compose path.
func nativeInstallAvailable() (bool, string) {
	if runtime.GOOS != "linux" {
		return false, fmt.Sprintf(
			"native installs are only supported on Linux; this host runs %s. Use the Docker (compose) path instead.",
			runtime.GOOS)
	}
	if os.Geteuid() != 0 {
		return false, "native installs need root to write systemd units and run the package manager. " +
			"Re-run the install one-liner (it elevates automatically), or launch `sudo prexorctl setup --browser`."
	}
	return true, ""
}

// installControllerNative installs the controller as a host systemd service:
// it ensures the JRE, installs local MongoDB/Redis when the operator chose
// local storage, registers + starts the unit, and waits for the health
// endpoint. The controller JAR + config have already been written by the
// caller (the steps are identical to the compose path). cfg is used for the
// startup health probe (HTTP port) and the unit's service dependencies.
func installControllerNative(
	say func(string, ...any),
	installDir string,
	httpPort string,
	cfg setup.ControllerConfig,
	mongoLocal, redisLocal bool,
	enableOnBoot, startNow bool,
) (nextSteps []string, code string, err error) {
	distro, err := setup.DetectDistro()
	if err != nil {
		return nil, errDepInstall, err
	}
	say("Detected %s (%s, %s).", distro.ID, distro.PackageMgr, distro.ServiceMgr)

	jrePath := setup.ManagedJREPath
	if v := setup.DetectJava(setup.ManagedJREPath); v != "" {
		say("Java %s already present.", v)
	} else {
		say("Installing Eclipse Temurin JRE 25…")
		if err := setup.InstallJRE(); err != nil {
			return nil, errDepInstall, fmt.Errorf("install JRE: %w", err)
		}
	}

	if mongoLocal {
		if v := setup.DetectMongoDB(); v != "" {
			say("MongoDB %s already present.", v)
		} else if !setup.MongoDBNative(distro) {
			return nil, errDepInstall, fmt.Errorf(
				"no native MongoDB package for %s — choose 'use existing URI' for MongoDB, or use the Docker path",
				distro.ID)
		} else {
			say("Installing MongoDB 8.0 (this can take a minute)…")
			if err := setup.InstallMongoDB(distro); err != nil {
				return nil, errDepInstall, fmt.Errorf("install MongoDB: %w", err)
			}
			if err := setup.DialTCPRetry("127.0.0.1:27017", 15*time.Second); err != nil {
				return nil, errDepInstall, fmt.Errorf("MongoDB installed but not reachable on 127.0.0.1:27017: %w", err)
			}
		}
	}

	if redisLocal {
		if v := setup.DetectRedis(); v != "" {
			say("Redis %s already present.", v)
		} else {
			say("Installing Redis…")
			if err := setup.InstallRedis(distro); err != nil {
				return nil, errDepInstall, fmt.Errorf("install Redis: %w", err)
			}
			if err := setup.DialTCPRetry("127.0.0.1:6379", 15*time.Second); err != nil {
				return nil, errDepInstall, fmt.Errorf("Redis installed but not reachable on 127.0.0.1:6379: %w", err)
			}
		}
	}

	svcOpts := setup.ControllerServiceOptions{LocalMongo: mongoLocal, LocalRedis: redisLocal}
	if enableOnBoot {
		say("Installing systemd unit %s (enabled on boot)…", setup.ControllerServiceName())
		if err := setup.RegisterControllerService(installDir, jrePath, svcOpts); err != nil {
			return nil, errServiceRegister, fmt.Errorf("register controller service: %w", err)
		}
	} else {
		say("Installing systemd unit %s (not enabled on boot)…", setup.ControllerServiceName())
		if err := setup.InstallControllerUnit(installDir, jrePath, svcOpts); err != nil {
			return nil, errServiceRegister, fmt.Errorf("install controller unit: %w", err)
		}
	}

	if !startNow {
		return []string{
			"systemctl start prexorcloud-controller",
			fmt.Sprintf("Open http://localhost:%s once the controller is healthy.", httpPort),
			"prexorctl login",
		}, "", nil
	}

	say("Starting controller and waiting for health…")
	// Cold JVM boot + first Mongo connect on a small/throttled VPS routinely
	// exceeds 90s; give it real headroom (the loop streams a heartbeat so a
	// slow start is visibly progressing, not hung).
	chk := setup.StartAndValidateControllerService(cfg, 4*time.Minute)
	say("%s: %s", chk.Name, chk.Detail)
	if chk.Status == setup.VerificationFail {
		return nil, errServiceRegister, fmt.Errorf("%s: %s", chk.Name, chk.Detail)
	}

	return []string{
		"systemctl status prexorcloud-controller --no-pager",
		fmt.Sprintf("Open http://localhost:%s once the controller is healthy.", httpPort),
		"prexorctl login                  # CLI is auto-linked",
	}, "", nil
}

// installDaemonNative installs the daemon as a host systemd service. The JAR +
// config have already been written by the caller.
func installDaemonNative(
	say func(string, ...any),
	installDir string,
	nodeID string,
	enableOnBoot, startNow bool,
) (nextSteps []string, code string, err error) {
	if _, err := setup.DetectDistro(); err != nil {
		return nil, errDepInstall, err
	}
	jrePath := setup.ManagedJREPath
	if v := setup.DetectJava(setup.ManagedJREPath); v != "" {
		say("Java %s already present.", v)
	} else {
		say("Installing Eclipse Temurin JRE 25…")
		if err := setup.InstallJRE(); err != nil {
			return nil, errDepInstall, fmt.Errorf("install JRE: %w", err)
		}
	}

	if enableOnBoot {
		say("Installing systemd unit %s (enabled on boot)…", setup.DaemonServiceName())
		if err := setup.RegisterDaemonService(installDir, jrePath); err != nil {
			return nil, errServiceRegister, fmt.Errorf("register daemon service: %w", err)
		}
	} else {
		say("Installing systemd unit %s (not enabled on boot)…", setup.DaemonServiceName())
		if err := setup.InstallDaemonUnit(installDir, jrePath); err != nil {
			return nil, errServiceRegister, fmt.Errorf("install daemon unit: %w", err)
		}
	}

	if startNow {
		say("Starting daemon…")
		if err := setup.StartService(setup.DaemonServiceName()); err != nil {
			return nil, errServiceRegister, fmt.Errorf("start daemon service: %w", err)
		}
	}

	statusStep := "systemctl status prexorcloud-daemon --no-pager"
	if !startNow {
		statusStep = "systemctl start prexorcloud-daemon"
	}
	return []string{
		statusStep,
		fmt.Sprintf("Confirm the new node appears in the controller's node list (joining as %s).", nodeID),
	}, "", nil
}

// installDashboardNative downloads + verifies the dashboard static bundle,
// extracts it to the install dir's web root, installs the chosen web server
// (nginx or Caddy), writes its vhost config, and reloads it.
func installDashboardNative(
	say func(string, ...any),
	installDir string,
	publicURL, listenPort, controllerURL string,
	webServer setup.WebServer,
	allowMissingCosign bool,
) (nextSteps []string, code string, err error) {
	distro, err := setup.DetectDistro()
	if err != nil {
		return nil, errDepInstall, err
	}

	say("Resolving latest dashboard bundle…")
	rel, err := setup.FetchLatestRelease(setup.GithubRepo)
	if err != nil {
		return nil, errReleaseFetch, fmt.Errorf("fetch latest release: %w", err)
	}
	asset, err := setup.FindAssetPrefix(rel, "dashboard-static-")
	if err != nil {
		return nil, errAssetMissing, fmt.Errorf("find dashboard bundle in release: %w", err)
	}
	say("Found %s (%s).", asset.Name, setup.HumanBytes(asset.Size))

	if err := setup.CreateDashboardDirs(installDir); err != nil {
		return nil, errInstallDir, fmt.Errorf("create install directories: %w", err)
	}
	tarPath := filepath.Join(installDir, "dashboard-static.tar.gz")
	if err := setup.DownloadAndVerifyAsset(rel, asset, tarPath, setup.CosignIdentityRegexJars, allowMissingCosign); err != nil {
		return nil, errDownload, fmt.Errorf("download + verify dashboard bundle: %w", err)
	}
	defer os.Remove(tarPath)
	if err := setup.ExtractDashboardBundle(tarPath, setup.DashboardWebRoot(installDir)); err != nil {
		return nil, errInstallDir, fmt.Errorf("extract dashboard bundle: %w", err)
	}
	say("Extracted dashboard bundle to %s.", setup.DashboardWebRoot(installDir))

	switch webServer {
	case setup.WebServerCaddy:
		say("Installing Caddy…")
		if err := setup.InstallCaddy(distro); err != nil {
			return nil, errDepInstall, fmt.Errorf("install Caddy: %w", err)
		}
	default:
		webServer = setup.WebServerNginx
		say("Installing nginx…")
		if err := setup.InstallNginx(distro); err != nil {
			return nil, errDepInstall, fmt.Errorf("install nginx: %w", err)
		}
	}

	if err := setup.ConfigureDashboardWebServer(setup.NativeDashboardConfig{
		InstallDir:    installDir,
		PublicURL:     publicURL,
		ListenPort:    listenPort,
		ControllerURL: controllerURL,
		WebServer:     webServer,
	}); err != nil {
		return nil, errConfigWrite, fmt.Errorf("configure %s: %w", webServer, err)
	}
	say("Configured %s to serve the dashboard and proxy /api/* to the controller.", webServer)

	steps := []string{
		fmt.Sprintf("systemctl status %s --no-pager", webServer),
		fmt.Sprintf("Open %s once the web server is healthy.", publicURL),
	}
	if webServer == setup.WebServerCaddy {
		steps = append(steps, "Caddy auto-provisions a TLS certificate on first request to an https:// hostname.")
	}
	return steps, "", nil
}
