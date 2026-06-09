package setupweb

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGenerateToken_Length(t *testing.T) {
	tok, err := generateToken()
	if err != nil {
		t.Fatal(err)
	}
	// 32 bytes -> 43 chars in raw URL-safe base64 (no padding).
	if len(tok) != 43 {
		t.Errorf("token length = %d, want 43", len(tok))
	}
	tok2, _ := generateToken()
	if tok == tok2 {
		t.Errorf("two consecutive tokens collided — RNG broken: %q", tok)
	}
}

func TestValidateToken_ConstantTime(t *testing.T) {
	cases := []struct {
		name, expected, got string
		want                bool
	}{
		{"match", "abc123", "abc123", true},
		{"different", "abc123", "abc124", false},
		{"length-mismatch-short", "abc123", "abc12", false},
		{"length-mismatch-long", "abc123", "abc1234", false},
		{"empty-expected", "", "abc", false},
		{"empty-got", "abc", "", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := validateToken(c.expected, c.got); got != c.want {
				t.Errorf("validateToken(%q, %q) = %v, want %v", c.expected, c.got, got, c.want)
			}
		})
	}
}

func TestRequireToken_LoopbackPassesThrough(t *testing.T) {
	// Empty token = loopback mode = no auth gate. Wrapper is a no-op.
	called := false
	h := requireToken("", newRateLimiter(), func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(204)
	})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	h(rec, req)
	if !called {
		t.Error("loopback mode should call inner handler")
	}
	if rec.Code != 204 {
		t.Errorf("status = %d", rec.Code)
	}
}

func TestRequireToken_RejectsMissingHeader(t *testing.T) {
	h := requireToken("secret", newRateLimiter(), func(w http.ResponseWriter, r *http.Request) {
		t.Error("handler should not run")
	})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	req.RemoteAddr = "127.0.0.1:9999"
	h(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	if !strings.Contains(rec.Header().Get("WWW-Authenticate"), "Bearer") {
		t.Errorf("WWW-Authenticate = %q, want Bearer challenge", rec.Header().Get("WWW-Authenticate"))
	}
}

func TestRequireToken_RejectsWrongToken(t *testing.T) {
	h := requireToken("secret", newRateLimiter(), func(w http.ResponseWriter, r *http.Request) {
		t.Error("handler should not run")
	})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	req.RemoteAddr = "127.0.0.1:9999"
	req.Header.Set("Authorization", "Bearer wrong")
	h(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestRequireToken_AcceptsValidToken(t *testing.T) {
	called := false
	h := requireToken("secret", newRateLimiter(), func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(200)
	})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	req.RemoteAddr = "127.0.0.1:9999"
	req.Header.Set("Authorization", "Bearer secret")
	h(rec, req)
	if !called || rec.Code != 200 {
		t.Errorf("expected 200, got %d (called=%v)", rec.Code, called)
	}
}

func TestRequireToken_RateLimits(t *testing.T) {
	limiter := newRateLimiter()
	h := requireToken("secret", limiter, func(w http.ResponseWriter, r *http.Request) {
		t.Error("handler should never run for invalid tokens")
	})
	// 10 invalid attempts trip the lockout.
	for i := 0; i < 10; i++ {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
		req.RemoteAddr = "10.0.0.1:1234"
		req.Header.Set("Authorization", "Bearer wrong")
		h(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d: status = %d, want 401", i, rec.Code)
		}
	}
	// 11th attempt — even with a valid token — should be 429.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	req.RemoteAddr = "10.0.0.1:1234"
	req.Header.Set("Authorization", "Bearer secret")
	h(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("post-lockout status = %d, want 429", rec.Code)
	}

	// A different IP isn't affected.
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	req2.RemoteAddr = "10.0.0.2:1234"
	req2.Header.Set("Authorization", "Bearer secret")
	hOther := requireToken("secret", limiter, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
	})
	hOther(rec2, req2)
	if rec2.Code != 200 {
		t.Errorf("other IP got blocked too: status=%d", rec2.Code)
	}
}

func TestRateLimiter_SuccessClearsFails(t *testing.T) {
	r := newRateLimiter()
	for i := 0; i < 5; i++ {
		r.recordFail("9.9.9.9")
	}
	r.recordSuccess("9.9.9.9")
	if r.locked("9.9.9.9") {
		t.Error("success should reset the fail counter")
	}
}
