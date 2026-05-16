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
// Writes the cert files into <installDir>/config/security/ so the daemon sees
// them on first start and skips its own gRPC bootstrap.
//
// The CLI JWT is returned for the caller to stash into ~/.prexorcloud/config.yml.
func ExchangeJoinToken(controllerHTTPURL, joinToken, nodeID, installDir string, timeout time.Duration) (*BootstrapExchangeResult, error) {
	body, _ := json.Marshal(map[string]string{"joinToken": joinToken, "nodeId": nodeID})

	client := &http.Client{Timeout: timeout}
	req, err := http.NewRequest(http.MethodPost, controllerHTTPURL+"/api/v1/bootstrap/exchange", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("call controller: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("controller returned %d: %s", resp.StatusCode, string(raw))
	}
	var result BootstrapExchangeResult
	if err := json.Unmarshal(raw, &result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}

	// Write cert files where the daemon expects them. Layout mirrors
	// java/.../daemon/bootstrap/BootstrapManager.java#nodePkcs12Path/etc.
	certDir := filepath.Join(installDir, "config", "security")
	if err := os.MkdirAll(certDir, 0700); err != nil {
		return nil, fmt.Errorf("create cert dir: %w", err)
	}
	pkcs12Bytes, err := base64.StdEncoding.DecodeString(result.Pkcs12Base64)
	if err != nil {
		return nil, fmt.Errorf("decode pkcs12: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, "node.p12"), pkcs12Bytes, 0600); err != nil {
		return nil, fmt.Errorf("write node.p12: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, ".node-password"), []byte(result.Pkcs12Password), 0600); err != nil {
		return nil, fmt.Errorf("write .node-password: %w", err)
	}
	if err := os.WriteFile(filepath.Join(certDir, "ca.pem"), []byte(result.CaCertificatePem), 0644); err != nil {
		return nil, fmt.Errorf("write ca.pem: %w", err)
	}
	return &result, nil
}
