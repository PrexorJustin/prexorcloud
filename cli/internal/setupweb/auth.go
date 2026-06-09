package setupweb

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// generateToken returns a 32-byte cryptographically random token, URL-safe
// base64-encoded. Used to gate /api/* in --public mode.
func generateToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("read random: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// validateToken does a constant-time compare so the server's behaviour
// can't be timing-fingerprinted by a remote attacker.
func validateToken(expected, got string) bool {
	if len(expected) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(expected), []byte(got)) == 1
}

// rateLimiter caps how fast a single remote IP can fail token checks.
// 10 fails in a row → 60-second lockout, then the counter resets.
type rateLimiter struct {
	mu         sync.Mutex
	state      map[string]*rateState
	maxFails   int
	lockoutFor time.Duration
}

type rateState struct {
	fails int
	until time.Time
}

func newRateLimiter() *rateLimiter {
	return &rateLimiter{
		state:      map[string]*rateState{},
		maxFails:   10,
		lockoutFor: 60 * time.Second,
	}
}

func (r *rateLimiter) locked(ip string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	s, ok := r.state[ip]
	if !ok {
		return false
	}
	// state.until is zero while we're still counting fails up to maxFails —
	// no active lockout yet, leave the fail counter intact.
	if s.until.IsZero() {
		return false
	}
	if time.Now().Before(s.until) {
		return true
	}
	// lockout window expired — clear state so the operator gets fresh attempts
	delete(r.state, ip)
	return false
}

func (r *rateLimiter) recordFail(ip string) (locked bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	s, ok := r.state[ip]
	if !ok {
		s = &rateState{}
		r.state[ip] = s
	}
	s.fails++
	if s.fails >= r.maxFails {
		s.until = time.Now().Add(r.lockoutFor)
		s.fails = 0
		return true
	}
	return false
}

func (r *rateLimiter) recordSuccess(ip string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.state, ip)
}

// remoteIP extracts the client IP from r.RemoteAddr. Loopback IPs are kept
// as-is; we don't honour X-Forwarded-For because the wizard never sits
// behind a reverse proxy by design.
func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// requireToken wraps next with bearer-token validation. When token is empty
// (loopback mode) the wrapper is a no-op pass-through.
func requireToken(token string, limiter *rateLimiter, next http.HandlerFunc) http.HandlerFunc {
	if token == "" {
		return next
	}
	return func(w http.ResponseWriter, r *http.Request) {
		ip := remoteIP(r)
		if limiter.locked(ip) {
			http.Error(w, `{"error":"rate limited — too many failed token attempts"}`,
				http.StatusTooManyRequests)
			return
		}
		auth := r.Header.Get("Authorization")
		const prefix = "Bearer "
		if !strings.HasPrefix(auth, prefix) {
			limiter.recordFail(ip)
			w.Header().Set("WWW-Authenticate", "Bearer realm=\"prexorctl-setup\"")
			http.Error(w, `{"error":"missing Authorization: Bearer <token>"}`,
				http.StatusUnauthorized)
			return
		}
		got := strings.TrimSpace(auth[len(prefix):])
		if !validateToken(token, got) {
			limiter.recordFail(ip)
			http.Error(w, `{"error":"invalid token"}`, http.StatusUnauthorized)
			return
		}
		limiter.recordSuccess(ip)
		next(w, r)
	}
}
