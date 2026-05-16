package setup

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestVerifyNativeControllerInstallHealthyService(t *testing.T) {
	dir := t.TempDir()
	mustWriteFile(t, filepath.Join(dir, "PrexorCloudController.jar"))
	mustWriteFile(t, filepath.Join(dir, "config", "controller.yml"))

	report := verifyNativeControllerInstall(dir, ControllerConfig{
		HTTPPort: "8080",
	}, ControllerVerificationOptions{
		LocalMongo:        true,
		LocalRedis:        true,
		ServiceRegistered: true,
	}, controllerVerifierDeps{
		fileStat: os.Stat,
		tcpCheck: func(addr string, timeout time.Duration) error { return nil },
		systemctl: func(args ...string) (string, error) {
			if len(args) >= 2 && args[0] == "is-enabled" {
				return "enabled", nil
			}
			if len(args) >= 2 && args[0] == "is-active" {
				return "active", nil
			}
			return "", nil
		},
		httpHealth: func(url string, timeout time.Duration) error { return nil },
	})

	if report.HasIssues() {
		t.Fatalf("report unexpectedly has issues: %#v", report)
	}
	if len(report.Checks) != 6 {
		t.Fatalf("len(report.Checks) = %d, want 6", len(report.Checks))
	}
}

func TestVerifyNativeControllerInstallMongoFailureProvidesRecovery(t *testing.T) {
	dir := t.TempDir()
	mustWriteFile(t, filepath.Join(dir, "PrexorCloudController.jar"))
	mustWriteFile(t, filepath.Join(dir, "config", "controller.yml"))

	report := verifyNativeControllerInstall(dir, ControllerConfig{}, ControllerVerificationOptions{
		LocalMongo: true,
	}, controllerVerifierDeps{
		fileStat: os.Stat,
		tcpCheck: func(addr string, timeout time.Duration) error {
			if addr == "127.0.0.1:27017" {
				return errors.New("connection refused")
			}
			return nil
		},
		systemctl:  func(args ...string) (string, error) { return "", nil },
		httpHealth: func(url string, timeout time.Duration) error { return nil },
	})

	check := report.Checks[2]
	if check.Status != VerificationFail {
		t.Fatalf("mongo check status = %s, want fail", check.Status)
	}
	if !strings.Contains(check.Detail, "connection refused") {
		t.Fatalf("mongo check detail = %q", check.Detail)
	}
	if len(check.Recovery) == 0 {
		t.Fatal("mongo check recovery unexpectedly empty")
	}
}

func TestVerifyNativeControllerInstallServiceRegisteredButNotStartedWarns(t *testing.T) {
	dir := t.TempDir()
	mustWriteFile(t, filepath.Join(dir, "PrexorCloudController.jar"))
	mustWriteFile(t, filepath.Join(dir, "config", "controller.yml"))

	report := verifyNativeControllerInstall(dir, ControllerConfig{
		HTTPPort: "18080",
	}, ControllerVerificationOptions{
		ServiceRegistered: true,
	}, controllerVerifierDeps{
		fileStat: os.Stat,
		tcpCheck: func(addr string, timeout time.Duration) error { return nil },
		systemctl: func(args ...string) (string, error) {
			if len(args) >= 2 && args[0] == "is-enabled" {
				return "enabled", nil
			}
			if len(args) >= 2 && args[0] == "is-active" {
				return "inactive", errors.New("exit status 3")
			}
			return "", nil
		},
		httpHealth: func(url string, timeout time.Duration) error { return nil },
	})

	check := report.Checks[len(report.Checks)-1]
	if check.Status != VerificationWarn {
		t.Fatalf("runtime check status = %s, want warn", check.Status)
	}
	if !strings.Contains(check.Detail, "not running yet") {
		t.Fatalf("runtime check detail = %q", check.Detail)
	}
	if len(check.Recovery) == 0 {
		t.Fatal("runtime check recovery unexpectedly empty")
	}
}

func TestStartAndValidateControllerServiceHealthy(t *testing.T) {
	check := startAndValidateControllerService(ControllerConfig{
		HTTPPort: "18080",
	}, 2*time.Second, controllerVerifierDeps{
		systemctl: func(args ...string) (string, error) { return "", nil },
		httpHealth: func(url string, timeout time.Duration) error {
			return nil
		},
		sleep: func(time.Duration) {},
	})

	if check.Status != VerificationOK {
		t.Fatalf("startup check status = %s, want ok", check.Status)
	}
}

func TestStartAndValidateControllerServiceStartFailure(t *testing.T) {
	check := startAndValidateControllerService(ControllerConfig{
		HTTPPort: "18080",
	}, 2*time.Second, controllerVerifierDeps{
		systemctl: func(args ...string) (string, error) { return "job failed", errors.New("exit status 1") },
		httpHealth: func(url string, timeout time.Duration) error {
			return nil
		},
		sleep: func(time.Duration) {},
	})

	if check.Status != VerificationFail {
		t.Fatalf("startup check status = %s, want fail", check.Status)
	}
	if !strings.Contains(check.Detail, "failed to start") {
		t.Fatalf("startup check detail = %q", check.Detail)
	}
}

func TestStartAndValidateControllerServiceHealthTimeout(t *testing.T) {
	check := startAndValidateControllerService(ControllerConfig{
		HTTPPort: "18080",
	}, 0, controllerVerifierDeps{
		systemctl: func(args ...string) (string, error) { return "", nil },
		httpHealth: func(url string, timeout time.Duration) error {
			return errors.New("connection refused")
		},
		sleep: func(time.Duration) {},
	})

	if check.Status != VerificationFail {
		t.Fatalf("startup check status = %s, want fail", check.Status)
	}
	if !strings.Contains(check.Detail, "did not become healthy") {
		t.Fatalf("startup check detail = %q", check.Detail)
	}
}

// TestStartAndValidateControllerServiceCrashLoopBailsEarly is the regression
// guard for the 115,000-restart bug: a JVM that crashes immediately because
// its config fails validation. The wait loop must see NRestarts climb and
// bail with the actual journal output, not wait out the full 4-minute
// timeout while the operator watches "waiting for controller health…" tick
// upward.
func TestStartAndValidateControllerServiceCrashLoopBailsEarly(t *testing.T) {
	// Returns 0 once (baseline), then climbs past the threshold so the next
	// heartbeat tick trips the detector.
	restartCalls := 0
	restartSeq := []int{0, crashLoopAbortThreshold + 1}
	check := startAndValidateControllerService(ControllerConfig{
		HTTPPort: "18080",
	}, 30*time.Second, controllerVerifierDeps{
		systemctl: func(args ...string) (string, error) { return "", nil },
		httpHealth: func(url string, timeout time.Duration) error {
			return errors.New("connection refused")
		},
		// Sleep advances the loop's wall clock past the 5s heartbeat
		// interval so the crash-loop check actually runs (it's gated on
		// "every 5s, not every iteration").
		sleep: func(d time.Duration) { time.Sleep(0) },
		restartCount: func() (int, error) {
			n := restartSeq[restartCalls]
			if restartCalls < len(restartSeq)-1 {
				restartCalls++
			}
			return n, nil
		},
		journalTail: func(n int) (string, error) {
			return "modules.signing.trustRoot must be configured when runtime.profile=production", nil
		},
	})

	if check.Status != VerificationFail {
		t.Fatalf("status = %s, want fail", check.Status)
	}
	if !strings.Contains(check.Detail, "crash-looped") {
		t.Errorf("expected detail to mention crash loop, got: %s", check.Detail)
	}
	if !strings.Contains(check.Detail, "modules.signing.trustRoot") {
		t.Errorf("expected detail to include captured journal output, got: %s", check.Detail)
	}
}

// TestStartAndValidateControllerServiceCrashLoopRequiresThreshold confirms a
// single restart inside the wait window doesn't trigger the detector — that
// would false-positive on the legitimate "systemd retried once because Mongo
// was 200ms late to listen" case.
func TestStartAndValidateControllerServiceCrashLoopRequiresThreshold(t *testing.T) {
	check := startAndValidateControllerService(ControllerConfig{
		HTTPPort: "18080",
	}, 0, controllerVerifierDeps{
		systemctl:  func(args ...string) (string, error) { return "", nil },
		httpHealth: func(url string, timeout time.Duration) error { return errors.New("not yet") },
		sleep:      func(time.Duration) {},
		restartCount: func() (int, error) {
			return crashLoopAbortThreshold - 1, nil
		},
		journalTail: func(n int) (string, error) { return "should-not-be-called", nil },
	})
	if !strings.Contains(check.Detail, "did not become healthy") {
		t.Fatalf("expected normal timeout, got: %s", check.Detail)
	}
}

func mustWriteFile(t *testing.T, path string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatalf("mkdir %s: %v", path, err)
	}
	if err := os.WriteFile(path, []byte("ok"), 0644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}
