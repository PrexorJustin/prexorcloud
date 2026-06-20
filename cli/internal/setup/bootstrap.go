package setup

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// BootstrapExchangeResult mirrors the JSON returned by the controller's
// POST /api/v1/bootstrap/exchange endpoint.
type BootstrapExchangeResult struct {
	Pkcs12Base64     string `json:"pkcs12Base64"`
	Pkcs12Password   string `json:"pkcs12Password"`
	CaCertificatePem string `json:"caCertificatePem"`
	CliToken         string `json:"cliToken"`
}

// ExchangeJoinToken drives the daemon's bootstrap from the CLI side: trades a
// join token for a node certificate, the CA bundle, and a DAEMON_HOST CLI JWT.
// It tries each controller REST URL in order, so a single controller being down
// doesn't block enrolment, and writes the cert files into
// <installDir>/config/security/ on the first success so the daemon sees them on
// first start and skips its own gRPC bootstrap.
//
// A permanent rejection (HTTP 4xx — e.g. a bad join token) fails fast without
// trying the others, since every controller shares the same cluster secret.
// The CLI JWT is returned for the caller to stash into ~/.prexorcloud/config.yml.
func ExchangeJoinToken(controllerHTTPURLs []string, joinToken, nodeID, installDir string, timeout time.Duration) (*BootstrapExchangeResult, error) {
	if len(controllerHTTPURLs) == 0 {
		return nil, fmt.Errorf("no controller URLs provided for bootstrap")
	}
	var lastErr error
	for _, baseURL := range controllerHTTPURLs {
		result, permanent, err := exchangeOnce(baseURL, joinToken, nodeID, installDir, timeout)
		if err == nil {
			return result, nil
		}
		if permanent {
			return nil, err
		}
		lastErr = err
	}
	return nil, lastErr
}

// exchangeOnce attempts the bootstrap exchange against one controller. The bool reports whether the
// failure is permanent (don't try other controllers / don't retry).
func exchangeOnce(
	controllerHTTPURL, joinToken, nodeID, installDir string, timeout time.Duration,
) (*BootstrapExchangeResult, bool, error) {
	body, _ := json.Marshal(map[string]string{"joinToken": joinToken, "nodeId": nodeID})

	client := &http.Client{Timeout: timeout}
	req, err := http.NewRequest(http.MethodPost, controllerHTTPURL+"/api/v1/bootstrap/exchange", bytes.NewReader(body))
	if err != nil {
		return nil, false, fmt.Errorf("build request for %s: %w", controllerHTTPURL, err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return nil, false, fmt.Errorf("call controller %s: %w", controllerHTTPURL, err) // network → transient
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, isPermanentHTTP(resp.StatusCode),
			fmt.Errorf("controller %s returned %d: %s", controllerHTTPURL, resp.StatusCode, string(raw))
	}
	var result BootstrapExchangeResult
	if err := json.Unmarshal(raw, &result); err != nil {
		return nil, false, fmt.Errorf("decode response from %s: %w", controllerHTTPURL, err)
	}

	// Write cert files where the daemon expects them. Layout mirrors
	// java/.../daemon/bootstrap/BootstrapManager.java#nodePkcs12Path/etc.
	certDir := filepath.Join(installDir, "config", "security")
	if err := os.MkdirAll(certDir, 0700); err != nil {
		return nil, false, fmt.Errorf("create cert dir: %w", err)
	}
	pkcs12Bytes, err := base64.StdEncoding.DecodeString(result.Pkcs12Base64)
	if err != nil {
		return nil, false, fmt.Errorf("decode pkcs12: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, "node.p12"), pkcs12Bytes, 0600); err != nil {
		return nil, false, fmt.Errorf("write node.p12: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, ".node-password"), []byte(result.Pkcs12Password), 0600); err != nil {
		return nil, false, fmt.Errorf("write .node-password: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, "ca.pem"), []byte(result.CaCertificatePem), 0644); err != nil {
		return nil, false, fmt.Errorf("write ca.pem: %w", err)
	}
	return &result, false, nil
}

// isPermanentHTTP reports whether an HTTP status won't be fixed by retrying or trying another
// controller. 4xx are permanent (bad token / payload), except 408/429 which are transient.
func isPermanentHTTP(status int) bool {
	if status == http.StatusRequestTimeout || status == http.StatusTooManyRequests {
		return false
	}
	return status >= 400 && status < 500
}
