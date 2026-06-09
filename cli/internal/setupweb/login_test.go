package setupweb

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// fakeController spins up a tiny http server that mimics the controller's
// /api/v1/auth/login endpoint with the response we want to test against.
// status==0 means "succeed with a real token".
func fakeController(t *testing.T, status int, token string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/auth/login" || r.Method != http.MethodPost {
			http.Error(w, "wrong route", http.StatusNotFound)
			return
		}
		if status > 0 {
			http.Error(w, "controller error", status)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"token": token})
	}))
}

// postCliLogin marshals and POSTs a cliLoginRequest to the given handler,
// returning the (status, decoded body) pair. Body shape: cliLoginResponse on
// success, installResponse-shaped on writeError.
func postCliLogin(t *testing.T, h http.HandlerFunc, req cliLoginRequest) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(req)
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/api/cli/login", bytes.NewReader(body))
	h(w, r)
	var out map[string]any
	if err := json.NewDecoder(w.Body).Decode(&out); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	return w.Code, out
}

func TestCliLogin_Success(t *testing.T) {
	server := fakeController(t, 0, "tok-abc")
	defer server.Close()

	var savedController, savedToken string
	h := handleCliLogin(func(controller, token string) error {
		savedController, savedToken = controller, token
		return nil
	}, func(string, ...any) {})

	code, body := postCliLogin(t, h, cliLoginRequest{
		Controller: server.URL,
		Username:   "alice",
		Password:   "hunter2",
	})

	if code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%v", code, body)
	}
	if ok, _ := body["ok"].(bool); !ok {
		t.Fatalf("ok = false, want true; body=%v", body)
	}
	if savedToken != "tok-abc" {
		t.Fatalf("OnCliLogin token = %q, want %q", savedToken, "tok-abc")
	}
	if savedController != server.URL {
		t.Fatalf("OnCliLogin controller = %q, want %q", savedController, server.URL)
	}
	if got, _ := body["username"].(string); got != "alice" {
		t.Fatalf("response username = %q, want alice", got)
	}
}

func TestCliLogin_RejectsMissingFields(t *testing.T) {
	h := handleCliLogin(func(string, string) error { return nil }, nil)
	cases := []struct {
		name string
		req  cliLoginRequest
	}{
		{"no controller", cliLoginRequest{Username: "a", Password: "p"}},
		{"no username", cliLoginRequest{Controller: "http://x", Password: "p"}},
		{"no password", cliLoginRequest{Controller: "http://x", Username: "a"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			code, body := postCliLogin(t, h, c.req)
			if code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", code)
			}
			if got, _ := body["errorCode"].(string); got != errInvalidRequest {
				t.Fatalf("errorCode = %q, want %q", got, errInvalidRequest)
			}
		})
	}
}

func TestCliLogin_AuthFailed(t *testing.T) {
	server := fakeController(t, http.StatusUnauthorized, "")
	defer server.Close()

	called := false
	h := handleCliLogin(func(string, string) error { called = true; return nil }, nil)

	code, body := postCliLogin(t, h, cliLoginRequest{
		Controller: server.URL,
		Username:   "alice",
		Password:   "wrong",
	})

	if code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", code)
	}
	if got, _ := body["errorCode"].(string); got != errAuthFailed {
		t.Fatalf("errorCode = %q, want %q", got, errAuthFailed)
	}
	if called {
		t.Fatal("OnCliLogin invoked despite auth failure")
	}
}

func TestCliLogin_ControllerUnreachable(t *testing.T) {
	// Port 1 on localhost — guaranteed to refuse the connection.
	h := handleCliLogin(func(string, string) error { return nil }, nil)
	code, body := postCliLogin(t, h, cliLoginRequest{
		Controller: "http://127.0.0.1:1",
		Username:   "alice",
		Password:   "p",
	})

	if code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", code)
	}
	if got, _ := body["errorCode"].(string); got != errControllerUnreach {
		t.Fatalf("errorCode = %q, want %q", got, errControllerUnreach)
	}
}

func TestNormalizeControllerURL(t *testing.T) {
	cases := []struct{ in, want string }{
		{"", ""},
		{"  http://example.com:8080/  ", "http://example.com:8080"},
		{"example.com:8080", "http://example.com:8080"},
		{"https://example.com", "https://example.com"},
		{"https://example.com/", "https://example.com"},
		{"not a url at all", ""},
	}
	for _, c := range cases {
		t.Run(c.in, func(t *testing.T) {
			got := normalizeControllerURL(c.in)
			if got != c.want {
				t.Fatalf("normalizeControllerURL(%q) = %q, want %q", c.in, got, c.want)
			}
		})
	}
}
