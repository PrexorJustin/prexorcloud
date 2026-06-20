package setup

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func okExchangeHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(BootstrapExchangeResult{
			Pkcs12Base64:     base64.StdEncoding.EncodeToString([]byte("p12-bytes")),
			Pkcs12Password:   "pw",
			CaCertificatePem: "ca-pem",
			CliToken:         "jwt",
		})
	}
}

func TestExchangeJoinTokenSweepsPastTransientFailureToHealthyController(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // 503 → transient
	}))
	defer down.Close()
	up := httptest.NewServer(okExchangeHandler())
	defer up.Close()

	dir := t.TempDir()
	res, err := ExchangeJoinToken([]string{down.URL, up.URL}, "tok", "node-1", dir, 5*time.Second)
	if err != nil {
		t.Fatalf("ExchangeJoinToken() error = %v", err)
	}
	if res.CliToken != "jwt" {
		t.Fatalf("CliToken = %q, want jwt", res.CliToken)
	}
	if _, err := os.Stat(filepath.Join(dir, "config", "security", "node.p12")); err != nil {
		t.Fatalf("certificate not written on success: %v", err)
	}
}

func TestExchangeJoinTokenFailsFastOnPermanentRejection(t *testing.T) {
	calls := 0
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls++
		w.WriteHeader(http.StatusUnauthorized) // 401 → permanent (bad token)
	}))
	defer bad.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		okExchangeHandler()(w, r)
	}))
	defer other.Close()

	dir := t.TempDir()
	_, err := ExchangeJoinToken([]string{bad.URL, other.URL}, "tok", "node-1", dir, 5*time.Second)
	if err == nil {
		t.Fatal("expected a permanent rejection to surface as an error")
	}
	if calls != 1 {
		t.Fatalf("permanent rejection should not try other controllers, calls = %d", calls)
	}
}

func TestExchangeJoinTokenErrorsWhenAllControllersDown(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway) // 502 → transient
	}))
	defer down.Close()

	dir := t.TempDir()
	_, err := ExchangeJoinToken([]string{down.URL, down.URL}, "tok", "node-1", dir, 5*time.Second)
	if err == nil {
		t.Fatal("expected an error when every controller is unreachable")
	}
}

func TestExchangeJoinTokenRequiresAtLeastOneURL(t *testing.T) {
	_, err := ExchangeJoinToken(nil, "tok", "node-1", t.TempDir(), time.Second)
	if err == nil {
		t.Fatal("expected an error when no controller URLs are provided")
	}
}
