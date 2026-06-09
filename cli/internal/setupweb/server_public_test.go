package setupweb

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

// startPublicServer boots Serve in --public mode against a free loopback port
// and returns the wizard URL (carrying the token) plus a cancel func.
func startPublicServer(t *testing.T) (wizardURL string, stop func()) {
	t.Helper()
	// We need a non-loopback bind for Public=true to be accepted, but in
	// tests we bind to a free localhost port and rely on the cert's
	// 127.0.0.1 SAN to make the TLS handshake work. We hand the server a
	// /32 bind on 127.0.0.1 — but Public requires non-loopback per the
	// validation we wrote. So use 0.0.0.0:<port> and dial 127.0.0.1.
	l, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	addr := net.JoinHostPort("0.0.0.0", fmt.Sprintf("%d", port))

	urlCh := make(chan string, 1)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- Serve(ctx, Options{
			BindAddr:    addr,
			Public:      true,
			PublicHost:  "127.0.0.1",
			OpenBrowser: false,
			IdleTimeout: 5 * time.Minute,
			Logf: func(format string, args ...any) {
				msg := fmt.Sprintf(format, args...)
				if strings.Contains(msg, "Setup wizard ready at") {
					tail := strings.TrimSpace(strings.TrimPrefix(msg, "Setup wizard ready at"))
					select {
					case urlCh <- tail:
					default:
					}
				}
			},
		})
	}()

	select {
	case wizardURL = <-urlCh:
	case <-time.After(3 * time.Second):
		cancel()
		t.Fatal("Serve never logged the wizard URL")
	}

	stop = func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("Serve did not return after cancel")
		}
	}
	return wizardURL, stop
}

func tlsClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // self-signed cert in tests
		},
		Timeout: 5 * time.Second,
	}
}

func parseWizardURL(t *testing.T, raw string) (base, token string) {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	frag := u.Fragment
	u.Fragment = ""
	if !strings.HasPrefix(frag, "token=") {
		t.Fatalf("URL fragment = %q, want token=…", frag)
	}
	return u.String(), strings.TrimPrefix(frag, "token=")
}

func TestPublicMode_TokenInFragment(t *testing.T) {
	rawURL, stop := startPublicServer(t)
	defer stop()

	if !strings.HasPrefix(rawURL, "https://") {
		t.Errorf("public URL should be https, got %q", rawURL)
	}
	if !strings.Contains(rawURL, "#token=") {
		t.Errorf("public URL should carry #token=, got %q", rawURL)
	}
}

func TestPublicMode_RejectsRequestsWithoutToken(t *testing.T) {
	rawURL, stop := startPublicServer(t)
	defer stop()
	base, _ := parseWizardURL(t, rawURL)

	res, err := tlsClient().Get(base + "api/info")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", res.StatusCode)
	}
}

func TestPublicMode_AcceptsTokenAsBearer(t *testing.T) {
	rawURL, stop := startPublicServer(t)
	defer stop()
	base, token := parseWizardURL(t, rawURL)

	req, _ := http.NewRequest(http.MethodGet, base+"api/info", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := tlsClient().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("status = %d, want 200", res.StatusCode)
	}
	var info infoResponse
	if err := json.NewDecoder(res.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if info.Defaults.ControllerHTTPPort != 8080 {
		t.Errorf("defaults.controllerHttpPort = %d", info.Defaults.ControllerHTTPPort)
	}
}

func TestPublicMode_RejectsLoopbackBind(t *testing.T) {
	err := Serve(contextDone(), Options{
		BindAddr:    "127.0.0.1:0",
		Public:      true,
		OpenBrowser: false,
		Logf:        func(string, ...any) {},
	})
	if err == nil || !strings.Contains(err.Error(), "loopback") {
		t.Errorf("expected loopback rejection, got %v", err)
	}
}

func TestPublicMode_RequiresPublicForNonLoopback(t *testing.T) {
	err := Serve(contextDone(), Options{
		BindAddr:    "0.0.0.0:0",
		Public:      false,
		OpenBrowser: false,
		Logf:        func(string, ...any) {},
	})
	if err == nil || !strings.Contains(err.Error(), "--public") {
		t.Errorf("expected --public requirement, got %v", err)
	}
}

func contextDone() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}
