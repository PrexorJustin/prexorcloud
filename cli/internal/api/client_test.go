package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNew_CreatesClient(t *testing.T) {
	c := New("http://localhost:8080", "token-123", false)
	if c.BaseURL != "http://localhost:8080" {
		t.Errorf("BaseURL = %q, want %q", c.BaseURL, "http://localhost:8080")
	}
	if c.Token != "token-123" {
		t.Errorf("Token = %q, want %q", c.Token, "token-123")
	}
	if c.Verbose {
		t.Error("Verbose should be false")
	}
}

func TestNew_TrimsTrailingSlash(t *testing.T) {
	c := New("http://localhost:8080/", "tok", false)
	if c.BaseURL != "http://localhost:8080" {
		t.Errorf("BaseURL = %q, want trailing slash removed", c.BaseURL)
	}
}

func TestGet_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("method = %s, want GET", r.Method)
		}
		if r.URL.Path != "/api/v1/nodes" {
			t.Errorf("path = %s, want /api/v1/nodes", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer my-token" {
			t.Errorf("auth header = %q, want Bearer my-token", r.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode([]map[string]string{{"id": "node-1"}})
	}))
	defer server.Close()

	c := New(server.URL, "my-token", false)
	var result []map[string]string
	err := c.Get("/api/v1/nodes", &result)
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if len(result) != 1 || result[0]["id"] != "node-1" {
		t.Errorf("result = %v, want [{id: node-1}]", result)
	}
}

func TestGet_APIError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]string{"message": "not found"})
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	var result any
	err := c.Get("/api/v1/nodes/missing", &result)
	if err == nil {
		t.Fatal("expected error")
	}
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("expected *APIError, got %T", err)
	}
	if apiErr.StatusCode != 404 {
		t.Errorf("StatusCode = %d, want 404", apiErr.StatusCode)
	}
	if apiErr.Message != "not found" {
		t.Errorf("Message = %q, want %q", apiErr.Message, "not found")
	}
}

func TestPost_SendsJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Content-Type = %q, want application/json", r.Header.Get("Content-Type"))
		}
		var body map[string]string
		json.NewDecoder(r.Body).Decode(&body)
		if body["name"] != "lobby" {
			t.Errorf("body.name = %q, want lobby", body["name"])
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "created"})
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	var result map[string]string
	err := c.Post("/api/v1/groups", map[string]string{"name": "lobby"}, &result)
	if err != nil {
		t.Fatalf("Post() error = %v", err)
	}
	if result["status"] != "created" {
		t.Errorf("result = %v", result)
	}
}

func TestDelete_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete {
			t.Errorf("method = %s, want DELETE", r.Method)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	err := c.Delete("/api/v1/groups/lobby", nil)
	if err != nil {
		t.Fatalf("Delete() error = %v", err)
	}
}

func TestGetWithQuery_AddsParams(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("limit") != "10" {
			t.Errorf("limit = %q, want 10", r.URL.Query().Get("limit"))
		}
		if r.URL.Query().Get("offset") != "20" {
			t.Errorf("offset = %q, want 20", r.URL.Query().Get("offset"))
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode([]string{})
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	var result []string
	err := c.GetWithQuery("/api/v1/audit", map[string]string{"limit": "10", "offset": "20"}, &result)
	if err != nil {
		t.Fatalf("GetWithQuery() error = %v", err)
	}
}

func TestGetWithQuery_SkipsEmptyValues(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RawQuery != "keep=yes" {
			t.Errorf("query = %q, want keep=yes", r.URL.RawQuery)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(nil)
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	err := c.GetWithQuery("/api/v1/test", map[string]string{"keep": "yes", "skip": ""}, nil)
	if err != nil {
		t.Fatalf("GetWithQuery() error = %v", err)
	}
}

func TestGet_NoAuthHeader_WhenTokenEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Errorf("unexpected auth header: %q", r.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(nil)
	}))
	defer server.Close()

	c := New(server.URL, "", false)
	c.Get("/api/v1/test", nil)
}

func TestAPIError_Error(t *testing.T) {
	err := &APIError{StatusCode: 401, Message: "Unauthorized"}
	if err.Error() != "Unauthorized (HTTP 401)" {
		t.Errorf("Error() = %q", err.Error())
	}
}

func TestAPIError_ErrorNoMessage(t *testing.T) {
	err := &APIError{StatusCode: 500}
	if err.Error() != "HTTP 500" {
		t.Errorf("Error() = %q", err.Error())
	}
}

func TestAPIError_ExitCode(t *testing.T) {
	tests := []struct {
		status int
		want   int
	}{
		{401, ExitAuthError},
		{403, ExitForbidden},
		{404, ExitNotFound},
		{500, ExitError},
	}
	for _, tt := range tests {
		err := &APIError{StatusCode: tt.status}
		if got := err.ExitCode(); got != tt.want {
			t.Errorf("ExitCode() for %d = %d, want %d", tt.status, got, tt.want)
		}
	}
}

func TestPatch_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPatch {
			t.Errorf("method = %s, want PATCH", r.Method)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "updated"})
	}))
	defer server.Close()

	c := New(server.URL, "token", false)
	var result map[string]string
	err := c.Patch("/api/v1/groups/lobby", map[string]int{"minInstances": 3}, &result)
	if err != nil {
		t.Fatalf("Patch() error = %v", err)
	}
	if result["status"] != "updated" {
		t.Errorf("result = %v", result)
	}
}

func TestSSEStream_UsesTicketBasedAuth(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/events/ticket":
			if r.Method != http.MethodPost {
				t.Fatalf("ticket method = %s, want POST", r.Method)
			}
			if got := r.Header.Get("Authorization"); got != "Bearer my-token" {
				t.Fatalf("ticket auth = %q, want Bearer my-token", got)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"ticket":"ticket-123"}`))
		case "/api/v1/services/test/console":
			if got := r.URL.Query().Get("ticket"); got != "ticket-123" {
				t.Fatalf("stream ticket = %q, want ticket-123", got)
			}
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = fmt.Fprint(w, "event: log\n")
			_, _ = fmt.Fprint(w, "data: hello\n\n")
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	c := New(server.URL, "my-token", false)
	var gotEvent, gotData string
	err := c.SSEStream(context.Background(), "/api/v1/services/test/console", func(event, data string) bool {
		gotEvent = event
		gotData = data
		return false
	})
	if err != nil {
		t.Fatalf("SSEStream() error = %v", err)
	}
	if gotEvent != "log" || gotData != "hello" {
		t.Fatalf("got event/data = %q/%q, want log/hello", gotEvent, gotData)
	}
}

func TestSSEStream_PropagatesTicketErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/events/ticket" {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(http.StatusUnauthorized)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"message":"unauthorized"}`))
	}))
	defer server.Close()

	c := New(server.URL, "bad-token", false)
	err := c.SSEStream(context.Background(), "/api/v1/services/test/console", func(event, data string) bool {
		return false
	})
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "create SSE ticket") {
		t.Fatalf("error = %v, want ticket creation context", err)
	}
}

func TestSSEStream_RequiresAuthToken(t *testing.T) {
	c := New("http://localhost:8080", "", false)

	err := c.SSEStream(context.Background(), "/api/v1/services/test/console", func(event, data string) bool {
		return false
	})
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "missing auth token") {
		t.Fatalf("error = %v, want missing auth token", err)
	}
}
