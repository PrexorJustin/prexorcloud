package setup

import (
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// crashLoopAbortThreshold is the number of unit restarts the wizard tolerates
// before declaring "the JVM is in a crash loop, stop waiting." The default
// systemd unit has RestartSec=5, so a JVM that aborts at config-validation
// time (~1.5s) cycles roughly every 6.5s. Three restarts ≈ 20s of real time
// — long enough to be confident the failure is reproducible, short enough
// that the operator gets a clear error well inside the 4-minute health
// deadline.
//
// Without this, a crash-looping JVM silently consumed the entire health
// timeout while the heartbeat printed "waiting for controller health
// (15s/240s)…" — exactly the failure mode that produced 115,000 systemd
// restart-counter entries on the install host that motivated this check.
const crashLoopAbortThreshold = 3

type VerificationStatus string

const (
	VerificationOK   VerificationStatus = "ok"
	VerificationWarn VerificationStatus = "warn"
	VerificationFail VerificationStatus = "fail"
)

type VerificationCheck struct {
	Name     string
	Status   VerificationStatus
	Detail   string
	Recovery []string
}

type ControllerVerificationOptions struct {
	LocalMongo        bool
	LocalRedis        bool
	ServiceRegistered bool
}

type ControllerVerificationReport struct {
	Checks []VerificationCheck
}

func (r ControllerVerificationReport) HasIssues() bool {
	for _, check := range r.Checks {
		if check.Status != VerificationOK {
			return true
		}
	}
	return false
}

type controllerVerifierDeps struct {
	fileStat   func(string) (os.FileInfo, error)
	tcpCheck   func(string, time.Duration) error
	systemctl  func(args ...string) (string, error)
	httpHealth func(url string, timeout time.Duration) error
	sleep      func(time.Duration)
	// restartCount returns the current value of systemd's NRestarts counter
	// for the controller unit. nil-tolerant — when omitted (existing test
	// literals, CLI path that already had a stable v1.0 wait loop), crash-
	// loop detection silently disables itself and the loop falls back to
	// timeout-only behaviour.
	restartCount func() (int, error)
	// journalTail captures the most recent n lines of the controller unit's
	// journal so the failure detail contains the actual JVM stderr (the line
	// that says, e.g., "modules.signing.trustRoot must be configured…"),
	// not just a generic "did not become healthy" timeout. Nil-tolerant.
	journalTail func(n int) (string, error)
}

func defaultControllerVerifierDeps() controllerVerifierDeps {
	return controllerVerifierDeps{
		fileStat: os.Stat,
		tcpCheck: DialTCP,
		systemctl: func(args ...string) (string, error) {
			out, err := exec.Command("systemctl", args...).CombinedOutput()
			return strings.TrimSpace(string(out)), err
		},
		httpHealth:   checkControllerHealthEndpoint,
		sleep:        time.Sleep,
		restartCount: func() (int, error) { return systemctlRestartCount(controllerServiceName) },
		journalTail:  func(n int) (string, error) { return journalctlTail(controllerServiceName, n) },
	}
}

// systemctlRestartCount queries systemd for the unit's NRestarts counter via
// `systemctl show -p NRestarts --value`. Returns 0 when the unit was never
// started in this boot, or when systemd doesn't expose the property (very old
// systemd) — both cases just disable crash-loop detection rather than failing
// the install.
func systemctlRestartCount(unit string) (int, error) {
	out, err := exec.Command("systemctl", "show", unit, "-p", "NRestarts", "--value").Output()
	if err != nil {
		return 0, err
	}
	s := strings.TrimSpace(string(out))
	if s == "" {
		return 0, nil
	}
	return strconv.Atoi(s)
}

// journalctlTail returns the most recent n journal lines for the unit, using
// --output=cat so we get the bare log lines without journald's "Started …"
// chrome (the operator wants the JVM's own error, not 50 lines of systemd
// noise).
func journalctlTail(unit string, n int) (string, error) {
	out, err := exec.Command("journalctl", "-u", unit, "-n", strconv.Itoa(n), "--no-pager", "--output=cat").CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

func StartAndValidateControllerService(cfg ControllerConfig, timeout time.Duration) VerificationCheck {
	return startAndValidateControllerService(cfg, timeout, defaultControllerVerifierDeps())
}

func VerifyNativeControllerInstall(
	installDir string,
	cfg ControllerConfig,
	opts ControllerVerificationOptions,
) ControllerVerificationReport {
	return verifyNativeControllerInstall(installDir, cfg, opts, defaultControllerVerifierDeps())
}

func verifyNativeControllerInstall(
	installDir string,
	cfg ControllerConfig,
	opts ControllerVerificationOptions,
	deps controllerVerifierDeps,
) ControllerVerificationReport {
	report := ControllerVerificationReport{
		Checks: []VerificationCheck{
			verifyRequiredFile(deps, "Controller JAR", filepath.Join(installDir, "PrexorCloudController.jar"),
				"Re-run `prexorctl setup --component=controller` to download the controller JAR again."),
			verifyRequiredFile(deps, "Controller config", filepath.Join(installDir, "config", "controller.yml"),
				"Re-run `prexorctl setup --component=controller` to regenerate `controller.yml`."),
		},
	}

	if opts.LocalMongo {
		report.Checks = append(report.Checks, verifyTCPService(
			deps,
			"MongoDB",
			"127.0.0.1:27017",
			"reachable on localhost:27017",
			[]string{
				"Run `systemctl status mongod --no-pager`.",
				"Run `journalctl -u mongod -n 50 --no-pager`.",
				"Run `ss -ltnp | grep 27017`.",
			},
		))
	}

	if opts.LocalRedis {
		report.Checks = append(report.Checks, verifyTCPService(
			deps,
			"Redis",
			"127.0.0.1:6379",
			"reachable on localhost:6379",
			[]string{
				"Run `systemctl --type=service | grep redis` to confirm the Redis unit name.",
				"Run `journalctl -u redis-server -n 50 --no-pager` or `journalctl -u redis -n 50 --no-pager`.",
				"Run `ss -ltnp | grep 6379`.",
			},
		))
	}

	if opts.ServiceRegistered {
		report.Checks = append(report.Checks, verifySystemdEnabled(
			deps,
			controllerServiceName,
			[]string{
				"Run `systemctl daemon-reload`.",
				"Run `systemctl enable prexorcloud-controller`.",
				"Run `systemctl status prexorcloud-controller --no-pager`.",
			},
		))
		report.Checks = append(report.Checks, verifyControllerRuntime(
			deps,
			cfg,
			controllerServiceName,
		))
	}

	return report
}

func verifyRequiredFile(
	deps controllerVerifierDeps,
	name string,
	path string,
	recovery string,
) VerificationCheck {
	if _, err := deps.fileStat(path); err != nil {
		return VerificationCheck{
			Name:   name,
			Status: VerificationFail,
			Detail: fmt.Sprintf("missing: %s", path),
			Recovery: []string{
				recovery,
			},
		}
	}
	return VerificationCheck{
		Name:   name,
		Status: VerificationOK,
		Detail: path,
	}
}

func verifyTCPService(
	deps controllerVerifierDeps,
	name string,
	addr string,
	detail string,
	recovery []string,
) VerificationCheck {
	if err := deps.tcpCheck(addr, 5*time.Second); err != nil {
		return VerificationCheck{
			Name:     name,
			Status:   VerificationFail,
			Detail:   fmt.Sprintf("not reachable at %s: %v", addr, err),
			Recovery: recovery,
		}
	}
	return VerificationCheck{
		Name:   name,
		Status: VerificationOK,
		Detail: detail,
	}
}

func verifySystemdEnabled(
	deps controllerVerifierDeps,
	serviceName string,
	recovery []string,
) VerificationCheck {
	out, err := deps.systemctl("is-enabled", serviceName)
	if err != nil {
		return VerificationCheck{
			Name:     "Controller service registration",
			Status:   VerificationFail,
			Detail:   fmt.Sprintf("systemd unit is not enabled: %s", strings.TrimSpace(out)),
			Recovery: recovery,
		}
	}
	return VerificationCheck{
		Name:   "Controller service registration",
		Status: VerificationOK,
		Detail: fmt.Sprintf("%s is enabled", serviceName),
	}
}

func verifyControllerRuntime(
	deps controllerVerifierDeps,
	cfg ControllerConfig,
	serviceName string,
) VerificationCheck {
	out, err := deps.systemctl("is-active", serviceName)
	if err != nil {
		return VerificationCheck{
			Name:   "Controller runtime",
			Status: VerificationWarn,
			Detail: fmt.Sprintf("%s is not running yet (%s)", serviceName, strings.TrimSpace(out)),
			Recovery: []string{
				"Run `systemctl start prexorcloud-controller`.",
				fmt.Sprintf("Run `curl -fsS http://127.0.0.1:%s/api/v1/system/health` after startup.", cfg.HTTPPort),
				"Run `journalctl -u prexorcloud-controller -n 100 --no-pager` if startup fails.",
			},
		}
	}

	url := fmt.Sprintf("http://127.0.0.1:%s/api/v1/system/health", cfg.HTTPPort)
	if err := deps.httpHealth(url, 5*time.Second); err != nil {
		return VerificationCheck{
			Name:   "Controller runtime",
			Status: VerificationFail,
			Detail: fmt.Sprintf("service is active but health check failed: %v", err),
			Recovery: []string{
				fmt.Sprintf("Run `curl -fsS %s`.", url),
				"Run `journalctl -u prexorcloud-controller -n 100 --no-pager`.",
				"Verify MongoDB and Redis are reachable from the controller host.",
			},
		}
	}

	return VerificationCheck{
		Name:   "Controller runtime",
		Status: VerificationOK,
		Detail: fmt.Sprintf("healthy at %s", url),
	}
}

func startAndValidateControllerService(
	cfg ControllerConfig,
	timeout time.Duration,
	deps controllerVerifierDeps,
) VerificationCheck {
	out, err := deps.systemctl("start", controllerServiceName)
	if err != nil {
		detail := fmt.Sprintf("failed to start %s", controllerServiceName)
		if strings.TrimSpace(out) != "" {
			detail += ": " + strings.TrimSpace(out)
		}
		return VerificationCheck{
			Name:   "Startup validation",
			Status: VerificationFail,
			Detail: detail,
			Recovery: []string{
				"Run `systemctl status prexorcloud-controller --no-pager`.",
				"Run `journalctl -u prexorcloud-controller -n 100 --no-pager`.",
				"Verify MongoDB and Redis are reachable before retrying startup.",
			},
		}
	}

	url := fmt.Sprintf("http://127.0.0.1:%s/api/v1/system/health", cfg.HTTPPort)
	start := time.Now()
	deadline := start.Add(timeout)
	lastTick := start

	// Baseline the systemd restart counter so crashLoopDetected compares
	// against this start, not lifetime restarts. A test/dev install dir that
	// already has 50 restarts in its history should still be allowed 3 more
	// before we declare a fresh crash loop.
	baselineRestarts := -1
	if deps.restartCount != nil {
		if n, err := deps.restartCount(); err == nil {
			baselineRestarts = n
		}
	}

	for {
		if err := deps.httpHealth(url, 5*time.Second); err == nil {
			return VerificationCheck{
				Name:   "Startup validation",
				Status: VerificationOK,
				Detail: fmt.Sprintf("controller started and is healthy at %s", url),
			}
		}
		if time.Now().After(deadline) {
			break
		}

		// Crash-loop detector — runs every iteration (cheap: one
		// `systemctl show -p NRestarts` property read). Once the unit has
		// been re-spawned past the threshold we know waiting longer is
		// pointless: the JVM is consistently failing to come up. Bail with
		// the actual journal output as the failure detail, because that is
		// the operator's one-shot answer to "what does the JVM hate about
		// my config" (a config-validator rejection, a port already bound, a
		// missing Mongo cred, a JVM flag the bundled JRE doesn't accept).
		if check, looping := detectCrashLoop(deps, baselineRestarts, url, time.Since(start)); looping {
			return check
		}
		// Heartbeat — stays gated on a 5s wall-clock tick so the wizard
		// console doesn't get a "waiting…" line every second.
		if now := time.Now(); now.Sub(lastTick) >= 5*time.Second {
			sinkPrintf("  …waiting for controller health (%ds/%ds)\n",
				int(now.Sub(start).Seconds()), int(timeout.Seconds()))
			lastTick = now
		}
		if deps.sleep != nil {
			deps.sleep(1 * time.Second)
		}
	}

	return VerificationCheck{
		Name:   "Startup validation",
		Status: VerificationFail,
		Detail: fmt.Sprintf("controller did not become healthy at %s within %s", url, timeout.Round(time.Second)),
		Recovery: []string{
			fmt.Sprintf("Run `curl -fsS %s` to inspect the health endpoint.", url),
			"Run `systemctl status prexorcloud-controller --no-pager`.",
			"Run `journalctl -u prexorcloud-controller -n 100 --no-pager`.",
		},
	}
}

// detectCrashLoop asks systemd how many times it has restarted the controller
// since the wait loop started. If the unit has been restarted more than
// crashLoopAbortThreshold times, the JVM is failing deterministically — no
// amount of additional waiting will help — and we return a failure check
// whose Detail carries the most recent journal output so the operator sees
// the actual JVM error (typically the config-validator's specific complaint)
// instead of a generic timeout.
//
// Returns (failure, true) when a crash loop is detected. Returns (_, false)
// when restartCount is not wired (CLI path / older test deps) or the counter
// hasn't reached the threshold yet — the caller continues waiting normally.
func detectCrashLoop(
	deps controllerVerifierDeps,
	baseline int,
	url string,
	elapsed time.Duration,
) (VerificationCheck, bool) {
	if deps.restartCount == nil || baseline < 0 {
		return VerificationCheck{}, false
	}
	current, err := deps.restartCount()
	if err != nil {
		return VerificationCheck{}, false
	}
	restartsSinceStart := current - baseline
	if restartsSinceStart < crashLoopAbortThreshold {
		return VerificationCheck{}, false
	}

	detail := fmt.Sprintf(
		"controller crash-looped %d times within %s of startup; the JVM is exiting before opening %s",
		restartsSinceStart, elapsed.Round(time.Second), url)
	if deps.journalTail != nil {
		if log, jerr := deps.journalTail(40); jerr == nil && log != "" {
			detail += "\n--- last journal lines ---\n" + log
		}
	}
	return VerificationCheck{
		Name:   "Startup validation",
		Status: VerificationFail,
		Detail: detail,
		Recovery: []string{
			"Read the journal lines above — the last ERROR line is almost always the actual cause.",
			"Run `journalctl -u prexorcloud-controller -n 100 --no-pager` for full context.",
			"Fix the issue in /opt/prexorcloud/controller/config/controller.yml and re-run the wizard.",
		},
	}, true
}

func checkControllerHealthEndpoint(url string, timeout time.Duration) error {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}
