package setupweb

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// CLI-login flow. The fifth mode card on Step 1 of the wizard ("CLI Login")
// short-circuits the install path entirely: instead of generating YAML and
// installing a component, it POSTs the operator's credentials to an existing
// controller's /api/v1/auth/login, then hands the resulting bearer token to
// the cmd-side OnCliLogin callback (which persists it into the prexorctl
// config). On success the wizard renders a "you're signed in" screen and
// requests POST /api/exit to tear itself down.
//
// The handler is intentionally side-effect-light on the setupweb side — it
// owns the HTTP call to the controller and the request/response shape, but
// the actual config write happens in the cmd package so this file doesn't
// need to import internal/config and so tests can substitute a no-op
// OnCliLogin callback.

type cliLoginRequest struct {
	Controller string `json:"controller"`
	Username   string `json:"username"`
	Password   string `json:"password"`
}

type cliLoginResponse struct {
	OK         bool   `json:"ok"`
	Controller string `json:"controller,omitempty"`
	Username   string `json:"username,omitempty"`
	Error      string `json:"error,omitempty"`
	ErrorCode  string `json:"errorCode,omitempty"`
}

// handleCliLogin returns an http.HandlerFunc that authenticates against the
// caller-supplied controller and persists the resulting token via onLogin.
// Disabled at registration time when onLogin is nil (see server.go).
func handleCliLogin(onLogin func(controller, token string) error, logf func(string, ...any)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		var req cliLoginRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "invalid request body: "+err.Error())
			return
		}

		controller := normalizeControllerURL(req.Controller)
		username := strings.TrimSpace(req.Username)
		if controller == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "controller URL is required")
			return
		}
		if username == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "username is required")
			return
		}
		if req.Password == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "password is required")
			return
		}

		token, code, err := postControllerLogin(r.Context(), controller, username, req.Password)
		if err != nil {
			status := http.StatusBadGateway
			if code == errAuthFailed {
				status = http.StatusUnauthorized
			}
			writeError(w, status, code, err.Error())
			return
		}

		if err := onLogin(controller, token); err != nil {
			writeError(w, http.StatusInternalServerError, errConfigWrite, "save config: "+err.Error())
			return
		}

		if logf != nil {
			logf("Logged in to %s as %s.", controller, username)
		}
		writeJSON(w, http.StatusOK, cliLoginResponse{OK: true, Controller: controller, Username: username})
	}
}

// postControllerLogin hits POST <controller>/api/v1/auth/login and returns
// the bearer token. The error code is one of errControllerUnreach (network /
// 5xx) or errAuthFailed (4xx, bad credentials).
func postControllerLogin(ctx context.Context, controller, username, password string) (string, string, error) {
	body, _ := json.Marshal(map[string]string{
		"username": username,
		"password": password,
	})

	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	endpoint := strings.TrimRight(controller, "/") + "/api/v1/auth/login"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return "", errInvalidRequest, fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Accept", "application/json")

	resp, err := http.DefaultClient.Do(httpReq)
	if err != nil {
		return "", errControllerUnreach, friendlyControllerError(controller, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return "", errAuthFailed, errors.New("controller rejected the credentials")
	}
	if resp.StatusCode >= 400 {
		return "", errControllerUnreach, fmt.Errorf("controller returned %s", resp.Status)
	}

	var parsed struct {
		Token string `json:"token"`
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", errControllerUnreach, fmt.Errorf("read response: %w", err)
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", errControllerUnreach, fmt.Errorf("decode response: %w", err)
	}
	if strings.TrimSpace(parsed.Token) == "" {
		return "", errAuthFailed, errors.New("controller returned an empty token")
	}
	return parsed.Token, "", nil
}

// normalizeControllerURL trims whitespace + trailing slash and adds a scheme
// when one is missing. The wizard's input is a plain text field, so the
// operator might paste "controller.example.com:8443" — we treat that as
// http:// for parity with prexorctl login.
func normalizeControllerURL(in string) string {
	s := strings.TrimSpace(in)
	if s == "" {
		return ""
	}
	if !strings.Contains(s, "://") {
		s = "http://" + s
	}
	u, err := url.Parse(s)
	if err != nil || u.Host == "" {
		return ""
	}
	return strings.TrimRight(u.String(), "/")
}

// friendlyControllerError swaps the raw net error for a sentence the wizard
// can render directly. Most of the time the operator just typed the URL
// wrong or the controller isn't up yet.
func friendlyControllerError(controller string, err error) error {
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return fmt.Errorf("can't resolve %s — check the hostname", controller)
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return fmt.Errorf("timed out reaching %s — is the controller running?", controller)
	}
	return fmt.Errorf("can't reach %s — %v", controller, err)
}
