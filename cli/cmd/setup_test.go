package cmd

import (
	"os"
	"strings"
	"testing"
)

func TestSelectSetupComponentNonInteractive(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupComponent = "controller"

	component, err := selectSetupComponent()
	if err != nil {
		t.Fatalf("selectSetupComponent() error = %v", err)
	}
	if component != "controller" {
		t.Fatalf("component = %q, want controller", component)
	}
}

func TestSelectSetupComponentNonInteractiveRequiresComponent(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupComponent = ""

	_, err := selectSetupComponent()
	if err == nil {
		t.Fatal("selectSetupComponent() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "--component=controller") || !strings.Contains(err.Error(), "--component=daemon") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestSelectSetupInstallModeNonInteractiveDefaultsNative(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true

	mode, err := selectSetupInstallMode()
	if err != nil {
		t.Fatalf("selectSetupInstallMode() error = %v", err)
	}
	if mode != installModeNative {
		t.Fatalf("mode = %q, want %q", mode, installModeNative)
	}
}

func TestSelectSetupInstallModeNonInteractiveUsesCompose(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupInstallMode = installModeCompose

	mode, err := selectSetupInstallMode()
	if err != nil {
		t.Fatalf("selectSetupInstallMode() error = %v", err)
	}
	if mode != installModeCompose {
		t.Fatalf("mode = %q, want %q", mode, installModeCompose)
	}
}

// On macOS/Windows, `prexorctl setup` must refuse before asking for
// component/install-mode and point the operator at the actually-working
// path (`prexorctl login`). The browser-setup path bypasses this gate —
// the wizard's Mode screen does the equivalent UI-side gating — but every
// TTY/non-interactive path goes through refuseSetupOnUnsupportedOS first.
func TestRefuseSetupOnUnsupportedOSRedirectsToLogin(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupRuntimeGOOS = "darwin"

	err := refuseSetupOnUnsupportedOS()
	if err == nil {
		t.Fatal("refuseSetupOnUnsupportedOS() unexpectedly succeeded on darwin")
	}
	if !strings.Contains(err.Error(), "prexorctl login") {
		t.Fatalf("error should redirect to `prexorctl login`: %v", err)
	}
	if !strings.Contains(err.Error(), "darwin") {
		t.Fatalf("error should name the OS so the operator knows why: %v", err)
	}
}

func TestRefuseSetupOnUnsupportedOSAllowsLinux(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupRuntimeGOOS = "linux"

	if err := refuseSetupOnUnsupportedOS(); err != nil {
		t.Fatalf("refuseSetupOnUnsupportedOS() on linux returned %v, want nil", err)
	}
}

func TestResolveServiceRegistrationNonInteractiveDefaultsDisabled(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true

	register, err := resolveServiceRegistration()
	if err != nil {
		t.Fatalf("resolveServiceRegistration() error = %v", err)
	}
	if register {
		t.Fatal("resolveServiceRegistration() = true, want false")
	}
}

func TestResolveServiceRegistrationNonInteractiveEnable(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupServiceMode = serviceModeEnable

	register, err := resolveServiceRegistration()
	if err != nil {
		t.Fatalf("resolveServiceRegistration() error = %v", err)
	}
	if !register {
		t.Fatal("resolveServiceRegistration() = false, want true")
	}
}

func TestValidateSetupModeCompatibilityRejectsSystemdFlagsForCompose(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupServiceMode = serviceModeEnable

	err := validateSetupModeCompatibility(installModeCompose)
	if err == nil {
		t.Fatal("validateSetupModeCompatibility() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "--install-mode=compose") {
		t.Fatalf("unexpected error: %v", err)
	}
}

// Regression: previously `runSetup` was guarded by `!isHeadless()`, which
// silently dropped --public on every SSH'd VPS (headless Linux). The install
// script auto-passes --ssh-tunnel (preferred) or --public when it detects
// SSH-without-DISPLAY, so the gate must honour both even when isHeadless()
// would otherwise be true.
func TestBrowserSetupRequested(t *testing.T) {
	cases := []struct {
		name                                                         string
		browser, nonInteractive, public, sshTunnel, headless, inSSH  bool
		want                                                         bool
	}{
		{"--ssh-tunnel overrides headless (SSH VPS path, recommended)", true, false, false, true, true, true, true},
		{"--public overrides headless (SSH VPS path, fallback)",        true, false, true, false, true, true, true},
		{"SSH'd headless box without flags auto-picks browser path",    true, false, false, false, true, true, true},
		{"loopback blocked when headless and not SSH'd",                true, false, false, false, true, false, false},
		{"loopback works on a desktop host",                            true, false, false, false, false, false, true},
		{"public works on a desktop host too",                          true, false, true, false, false, false, true},
		{"ssh-tunnel works on a desktop host too",                      true, false, false, true, false, false, true},
		{"--no-browser always blocks",                                  false, false, true, true, false, false, false},
		{"--non-interactive always blocks",                             true, true, true, true, false, false, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := browserSetupRequested(c.browser, c.nonInteractive, c.public, c.sshTunnel, c.headless, c.inSSH); got != c.want {
				t.Fatalf("browserSetupRequested(browser=%v, nonInteractive=%v, public=%v, sshTunnel=%v, headless=%v, inSSH=%v) = %v, want %v",
					c.browser, c.nonInteractive, c.public, c.sshTunnel, c.headless, c.inSSH, got, c.want)
			}
		})
	}
}

func TestInSSHSession(t *testing.T) {
	cases := []struct {
		name string
		env  string
		want bool
	}{
		{"unset", "", false},
		{"empty", "", false},
		{"real four-field value", "203.0.113.5 54321 198.51.100.10 22", true},
		{"three-field copy-paste", "203.0.113.5 54321 22", false},
		{"junk", "yes", false},
	}
	prev, hadPrev := os.LookupEnv("SSH_CONNECTION")
	t.Cleanup(func() {
		if hadPrev {
			_ = os.Setenv("SSH_CONNECTION", prev)
		} else {
			_ = os.Unsetenv("SSH_CONNECTION")
		}
	})
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if c.env == "" {
				_ = os.Unsetenv("SSH_CONNECTION")
			} else {
				_ = os.Setenv("SSH_CONNECTION", c.env)
			}
			if got := inSSHSession(); got != c.want {
				t.Fatalf("inSSHSession() with SSH_CONNECTION=%q = %v, want %v", c.env, got, c.want)
			}
		})
	}
}

func TestValidateSetupPlatformSupportAllowsComposeOnWindows(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupRuntimeGOOS = "windows"

	if err := validateSetupPlatformSupport(installModeCompose); err != nil {
		t.Fatalf("validateSetupPlatformSupport() error = %v", err)
	}
}

func TestValidateSetupPlatformSupportRejectsNativeOnWindows(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupRuntimeGOOS = "windows"

	err := validateSetupPlatformSupport(installModeNative)
	if err == nil {
		t.Fatal("validateSetupPlatformSupport() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "--install-mode=compose") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestResolveControllerStartupValidationNonInteractiveDefaultsEnabledWhenRegistered(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true

	validate, err := resolveControllerStartupValidation(true)
	if err != nil {
		t.Fatalf("resolveControllerStartupValidation() error = %v", err)
	}
	if !validate {
		t.Fatal("resolveControllerStartupValidation() = false, want true")
	}
}

func TestResolveControllerStartupValidationNonInteractiveDisable(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupStartupValidationMode = startupValidationDisable

	validate, err := resolveControllerStartupValidation(true)
	if err != nil {
		t.Fatalf("resolveControllerStartupValidation() error = %v", err)
	}
	if validate {
		t.Fatal("resolveControllerStartupValidation() = true, want false")
	}
}

func TestResolveControllerStartupValidationSkipsWithoutServiceRegistration(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true

	validate, err := resolveControllerStartupValidation(false)
	if err != nil {
		t.Fatalf("resolveControllerStartupValidation() error = %v", err)
	}
	if validate {
		t.Fatal("resolveControllerStartupValidation() = true, want false")
	}
}

func TestResolveEnableOnBootAndStartNowNonInteractive(t *testing.T) {
	cases := []struct {
		name     string
		boot     string
		start    string
		svcMode  string // legacy --service-mode fallback for boot
		valMode  string // legacy --startup-validation-mode fallback for start
		wantBoot bool
		wantNow  bool
	}{
		{name: "defaults enabled", wantBoot: true, wantNow: true},
		{name: "explicit disable", boot: lifecycleModeDisable, start: lifecycleModeDisable, wantBoot: false, wantNow: false},
		{name: "explicit enable", boot: lifecycleModeEnable, start: lifecycleModeEnable, wantBoot: true, wantNow: true},
		{name: "legacy service-mode disables boot", svcMode: serviceModeDisable, wantBoot: false, wantNow: true},
		{name: "legacy validation-mode disables start", valMode: startupValidationDisable, wantBoot: true, wantNow: false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			restore := saveSetupState()
			defer restore()
			setupNonInteractive = true
			setupBootMode = tc.boot
			setupStartMode = tc.start
			setupServiceMode = tc.svcMode
			setupStartupValidationMode = tc.valMode

			boot, err := resolveEnableOnBoot("controller")
			if err != nil {
				t.Fatalf("resolveEnableOnBoot() error = %v", err)
			}
			now, err := resolveStartNow("controller")
			if err != nil {
				t.Fatalf("resolveStartNow() error = %v", err)
			}
			if boot != tc.wantBoot || now != tc.wantNow {
				t.Fatalf("boot=%v now=%v, want boot=%v now=%v", boot, now, tc.wantBoot, tc.wantNow)
			}
		})
	}
}

func TestResolveLifecyclePromptModeRejectedInNonInteractive(t *testing.T) {
	restore := saveSetupState()
	defer restore()
	setupNonInteractive = true
	setupBootMode = lifecycleModePrompt

	if _, err := resolveEnableOnBoot("daemon"); err == nil {
		t.Fatal("resolveEnableOnBoot() with --boot-mode=prompt in non-interactive should error")
	}
}

func TestPromptControllerConfigNonInteractiveUsesFlags(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupControllerHTTPPort = "18080"
	setupControllerGRPCPort = "19090"
	setupControllerCORSOrigin = "https://dashboard.example.com"

	cfg, err := promptControllerConfig("mongodb://mongo.internal:27017", "redis://redis.internal:6379")
	if err != nil {
		t.Fatalf("promptControllerConfig() error = %v", err)
	}

	if cfg.RuntimeProfile != "production" {
		t.Fatalf("RuntimeProfile = %q, want production", cfg.RuntimeProfile)
	}
	if cfg.HTTPPort != "18080" {
		t.Fatalf("HTTPPort = %q, want 18080", cfg.HTTPPort)
	}
	if cfg.GRPCPort != "19090" {
		t.Fatalf("GRPCPort = %q, want 19090", cfg.GRPCPort)
	}
	if len(cfg.CORSOrigins) != 1 || cfg.CORSOrigins[0] != "https://dashboard.example.com" {
		t.Fatalf("CORSOrigins = %#v", cfg.CORSOrigins)
	}
}

func TestPromptDaemonConfigNonInteractiveRequiresControllerHostAndJoinToken(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupDaemonControllerHost = ""
	setupDaemonJoinToken = ""

	_, err := promptDaemonConfig()
	if err == nil {
		t.Fatal("promptDaemonConfig() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "controller host is required") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestPromptDaemonConfigNonInteractiveUsesFlags(t *testing.T) {
	restore := saveSetupState()
	defer restore()

	setupNonInteractive = true
	setupDaemonNodeID = "node-a"
	setupDaemonControllerHost = "127.0.0.1"
	setupDaemonControllerGRPC = "23000"
	setupDaemonJoinToken = "pxt_test"

	cfg, err := promptDaemonConfig()
	if err != nil {
		t.Fatalf("promptDaemonConfig() error = %v", err)
	}

	if cfg.NodeID != "node-a" {
		t.Fatalf("NodeID = %q, want node-a", cfg.NodeID)
	}
	if cfg.ControllerHost != "127.0.0.1" {
		t.Fatalf("ControllerHost = %q, want 127.0.0.1", cfg.ControllerHost)
	}
	if cfg.GRPCPort != "23000" {
		t.Fatalf("GRPCPort = %q, want 23000", cfg.GRPCPort)
	}
	if cfg.JoinToken != "pxt_test" {
		t.Fatalf("JoinToken = %q, want pxt_test", cfg.JoinToken)
	}
}

func saveSetupState() func() {
	nonInteractive := setupNonInteractive
	component := setupComponent
	installMode := setupInstallMode
	serviceMode := setupServiceMode
	startupValidationMode := setupStartupValidationMode
	bootMode := setupBootMode
	startMode := setupStartMode
	runtimeGOOS := setupRuntimeGOOS
	controllerInstall := setupControllerInstallDir
	controllerMongoMode := setupControllerMongoMode
	controllerMongoURI := setupControllerMongoURI
	controllerRedisMode := setupControllerRedisMode
	controllerRedisURI := setupControllerRedisURI
	controllerHTTPPort := setupControllerHTTPPort
	controllerGRPCPort := setupControllerGRPCPort
	controllerCORSOrigin := setupControllerCORSOrigin
	daemonInstall := setupDaemonInstallDir
	daemonNodeID := setupDaemonNodeID
	daemonControllerHost := setupDaemonControllerHost
	daemonControllerGRPC := setupDaemonControllerGRPC
	daemonJoinToken := setupDaemonJoinToken

	return func() {
		setupNonInteractive = nonInteractive
		setupComponent = component
		setupInstallMode = installMode
		setupServiceMode = serviceMode
		setupStartupValidationMode = startupValidationMode
		setupBootMode = bootMode
		setupStartMode = startMode
		setupRuntimeGOOS = runtimeGOOS
		setupControllerInstallDir = controllerInstall
		setupControllerMongoMode = controllerMongoMode
		setupControllerMongoURI = controllerMongoURI
		setupControllerRedisMode = controllerRedisMode
		setupControllerRedisURI = controllerRedisURI
		setupControllerHTTPPort = controllerHTTPPort
		setupControllerGRPCPort = controllerGRPCPort
		setupControllerCORSOrigin = controllerCORSOrigin
		setupDaemonInstallDir = daemonInstall
		setupDaemonNodeID = daemonNodeID
		setupDaemonControllerHost = daemonControllerHost
		setupDaemonControllerGRPC = daemonControllerGRPC
		setupDaemonJoinToken = daemonJoinToken
	}
}
