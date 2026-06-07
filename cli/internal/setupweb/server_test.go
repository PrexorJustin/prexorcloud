package setupweb

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"runtime"
	"strings"
	"testing"
	"time"
)

// pickFreeAddr asks the kernel for an unused loopback port.
func pickFreeAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close()
	return addr
}

// startServer boots Serve in a goroutine and returns the URL plus a cancel
// func that triggers shutdown via context.
func startServer(t *testing.T) (baseURL string, stop func()) {
	t.Helper()
	addr := pickFreeAddr(t)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- Serve(ctx, Options{
			BindAddr:    addr,
			OpenBrowser: false,
			Logf:        func(string, ...any) {},
		})
	}()

	// Poll the listener until it's accepting.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	stop = func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("Serve did not return after cancel")
		}
	}
	return "http://" + addr, stop
}

func TestServer_ServesIndex(t *testing.T) {
	url, stop := startServer(t)
	defer stop()

	res, err := http.Get(url + "/")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	if res.StatusCode != 200 {
		t.Fatalf("status = %d", res.StatusCode)
	}
	if !strings.Contains(string(body), "PrexorCloud") {
		t.Errorf("index missing PrexorCloud branding: %s", string(body)[:200])
	}
}

func TestServer_InfoEndpoint(t *testing.T) {
	url, stop := startServer(t)
	defer stop()

	res, err := http.Get(url + "/api/info")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("status = %d", res.StatusCode)
	}
	var got infoResponse
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Defaults.ControllerHTTPPort != 8080 {
		t.Errorf("defaults.controllerHttpPort = %d", got.Defaults.ControllerHTTPPort)
	}
	if got.Platform.OS == "" {
		t.Errorf("platform.os should be set")
	}
}

func TestServer_ControllerInstallRejectsUnknownMode(t *testing.T) {
	url, stop := startServer(t)
	defer stop()

	body := []byte(`{"installMode":"frobnicate"}`)
	res, err := http.Post(url+"/api/install/controller", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", res.StatusCode)
	}
	var got installResponse
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got.Error, "frobnicate") {
		t.Errorf("error %q should name the unknown mode", got.Error)
	}
	if got.ErrorCode != errModeUnsupported {
		t.Errorf("errorCode = %q, want %q", got.ErrorCode, errModeUnsupported)
	}
	if got.DocsURL == "" {
		t.Errorf("docsUrl should be populated for MODE_UNSUPPORTED")
	}
}

// TestServer_ControllerInstallNativeGate covers the capability gate. When the
// host can't do native installs (non-Linux, or the test isn't running as root)
// the wizard must 422 with NATIVE_UNAVAILABLE rather than attempting anything.
// When it *can* (root on Linux — e.g. a CI container) we skip, because the
// request would kick off a real package-manager install.
func TestServer_ControllerInstallNativeGate(t *testing.T) {
	if ok, _ := nativeInstallAvailable(); ok {
		t.Skip("host can perform native installs (root on Linux); skipping to avoid a real install")
	}
	url, stop := startServer(t)
	defer stop()

	body := []byte(`{"installMode":"native"}`)
	res, err := http.Post(url+"/api/install/controller", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want 422", res.StatusCode)
	}
	var got installResponse
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.ErrorCode != errNativeUnavailable {
		t.Errorf("errorCode = %q, want %q", got.ErrorCode, errNativeUnavailable)
	}
	if got.Error == "" {
		t.Errorf("error should explain why native is unavailable")
	}
}

// TestNativeInstallAvailable pins the gate's invariant: the reason string is
// non-empty exactly when native is unavailable, and empty when it's allowed.
func TestNativeInstallAvailable(t *testing.T) {
	ok, reason := nativeInstallAvailable()
	if ok && reason != "" {
		t.Errorf("allowed but reason non-empty: %q", reason)
	}
	if !ok && reason == "" {
		t.Errorf("unavailable but reason empty")
	}
}

// TestInstallSupported pins the higher-level "can this host install server-side
// components at all" gate. Same invariant as nativeInstallAvailable, plus a
// strict Linux/non-Linux assertion so we don't silently start allowing
// installs on macOS/Windows again — that's the whole reason this gate exists.
func TestInstallSupported(t *testing.T) {
	ok, reason := installSupported()
	if ok && reason != "" {
		t.Errorf("supported but reason non-empty: %q", reason)
	}
	if !ok && reason == "" {
		t.Errorf("unsupported but reason empty")
	}
	if runtime.GOOS == "linux" && !ok {
		t.Errorf("installSupported() = false on linux; want true regardless of root")
	}
	if runtime.GOOS != "linux" && ok {
		t.Errorf("installSupported() = true on %s; want false (Linux-only)", runtime.GOOS)
	}
}

// TestServer_ControllerInstallRejectsCrashLoopingYaml is the
// regression guard for the v1.0 bug: the wizard's controller.yml emitted
// modules.signing.required:true (auto from production profile) without a
// trustRoot, which crash-looped the JVM 115,000 times on a real install
// host before the operator killed the wizard. The pre-flight validator
// must catch this and return 422 with the field + recovery hints — no
// side effects (cosign download, dir creation, systemd unit registration)
// should happen.
func TestServer_ControllerInstallRejectsCrashLoopingYaml(t *testing.T) {
	url, stop := startServer(t)
	defer stop()

	// Matches the YAML the v1.0 wizard wrote on the bug-report host —
	// production profile, signing required, no trust root.
	const buggyYAML = `runtime:
  profile: production
http:
  port: 8080
grpc:
  port: 9090
database:
  uri: 'mongodb://localhost:27017'
redis:
  uri: 'redis://localhost:6379'
modules:
  signing:
    required: true
    mode: KEYED
    allowUnsignedDevelopment: true
`
	payload, _ := json.Marshal(map[string]any{
		"installMode":  "compose",
		"mongoMode":    "local",
		"redisMode":    "local",
		"yamlOverride": buggyYAML,
	})
	res, err := http.Post(url+"/api/install/controller", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want 422 (the buggy YAML must be rejected pre-flight)", res.StatusCode)
	}
	var got installResponse
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.ErrorCode != errInvalidConfig {
		t.Errorf("errorCode = %q, want %q", got.ErrorCode, errInvalidConfig)
	}
	if len(got.ValidationErrors) != 1 {
		t.Fatalf("validationErrors len = %d, want 1: %#v", len(got.ValidationErrors), got.ValidationErrors)
	}
	v := got.ValidationErrors[0]
	if v.Field != "modules.signing.trustRoot" {
		t.Errorf("field = %q, want modules.signing.trustRoot", v.Field)
	}
	if !strings.Contains(v.Message, "production") {
		t.Errorf("message should mention production profile: %q", v.Message)
	}
	if len(v.Recovery) == 0 {
		t.Errorf("recovery hints should be populated")
	}
}

func TestServer_DaemonInstallRequiresNodeIDAndJoinToken(t *testing.T) {
	url, stop := startServer(t)
	defer stop()

	cases := []struct {
		body     string
		want     string
		wantCode string
	}{
		{`{"installMode":"compose","nodeId":"","joinToken":"x"}`, "nodeId", errNodeIDRequired},
		{`{"installMode":"compose","nodeId":"x","joinToken":""}`, "joinToken", errJoinTokenRequired},
	}
	for _, c := range cases {
		res, err := http.Post(url+"/api/install/daemon", "application/json", bytes.NewReader([]byte(c.body)))
		if err != nil {
			t.Fatal(err)
		}
		var got installResponse
		json.NewDecoder(res.Body).Decode(&got)
		res.Body.Close()
		if !strings.Contains(got.Error, c.want) {
			t.Errorf("body=%s → error=%q, want contains %q", c.body, got.Error, c.want)
		}
		if got.ErrorCode != c.wantCode {
			t.Errorf("body=%s → errorCode=%q, want %q", c.body, got.ErrorCode, c.wantCode)
		}
	}
}

func TestDocURLFor(t *testing.T) {
	// Every known error code must either resolve to a doc URL or to
	// "" (intentionally untyped). The frontend only renders the link
	// when the URL is non-empty, so an unknown code is safe — but a
	// known code without a curated URL is a regression we want to
	// catch.
	curated := []string{
		errModeUnsupported, errMongoURI, errRedisURI, errInvalidMode,
		errNodeIDRequired, errJoinTokenRequired,
		errReleaseFetch, errAssetMissing, errDownload,
		errInstallDir, errConfigWrite, errComposeWrite,
		errNativeUnavailable, errDepInstall, errServiceRegister,
	}
	for _, code := range curated {
		if docURLFor(code) == "" {
			t.Errorf("docURLFor(%q) returned empty; every catalogued error code should map to a doc page", code)
		}
	}
	if docURLFor("UNKNOWN") != "" {
		t.Errorf("docURLFor returned a URL for an unknown code; that path should be empty")
	}
}

func TestServer_ExitEndpointShutsDown(t *testing.T) {
	addr := pickFreeAddr(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- Serve(ctx, Options{BindAddr: addr, OpenBrowser: false, Logf: func(string, ...any) {}})
	}()

	// Wait for listener.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	res, err := http.Post("http://"+addr+"/api/exit", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Serve returned error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Serve did not return after /api/exit")
	}
}

func TestResolveStorageURI(t *testing.T) {
	cases := []struct {
		mode, explicit, def string
		wantLocal           bool
		wantURI             string
		wantErr             bool
		wantCode            string
	}{
		{"local", "", "mongodb://mongo:27017", true, "mongodb://mongo:27017", false, ""},
		{"", "", "redis://redis:6379", true, "redis://redis:6379", false, ""},
		{"remote", "mongodb://prod:27017", "mongodb://mongo:27017", false, "mongodb://prod:27017", false, ""},
		{"remote", "", "x", false, "", true, errMongoURI},
		{"weird", "", "x", false, "", true, errInvalidMode},
	}
	for _, c := range cases {
		gotLocal, gotURI, gotCode, err := resolveStorageURI("Mongo", c.mode, c.explicit, c.def, errMongoURI)
		if (err != nil) != c.wantErr {
			t.Errorf("mode=%q explicit=%q → err=%v, wantErr=%v", c.mode, c.explicit, err, c.wantErr)
			continue
		}
		if c.wantErr {
			if gotCode != c.wantCode {
				t.Errorf("mode=%q → code=%q, want %q", c.mode, gotCode, c.wantCode)
			}
			continue
		}
		if gotLocal != c.wantLocal || gotURI != c.wantURI {
			t.Errorf("mode=%q → (local=%v, uri=%q), want (%v, %q)", c.mode, gotLocal, gotURI, c.wantLocal, c.wantURI)
		}
	}
}
