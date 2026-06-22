package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Retry policy for idempotent verbs (GET, HEAD). POST/PATCH/DELETE are
// never retried automatically because they can have server-side
// side-effects. Connection errors + 5xx responses are retried; 4xx are
// not (those are client errors and won't change on a retry).
const (
	retryMaxAttempts = 3                      // initial + 2 retries
	retryBaseDelay   = 200 * time.Millisecond // first backoff
	retryMaxDelay    = 2 * time.Second        // cap
	retryJitterPct   = 0.2                    // ±20%
)

// shouldRetryStatus reports whether an HTTP status code indicates a
// transient server-side problem that may succeed on retry.
func shouldRetryStatus(code int) bool {
	switch code {
	case http.StatusRequestTimeout, // 408
		http.StatusTooManyRequests,     // 429
		http.StatusInternalServerError, // 500
		http.StatusBadGateway,          // 502
		http.StatusServiceUnavailable,  // 503
		http.StatusGatewayTimeout:      // 504
		return true
	}
	return false
}

// isIdempotent reports whether the HTTP method is safe to retry without
// risking duplicated server-side state changes.
func isIdempotent(method string) bool {
	return method == http.MethodGet || method == http.MethodHead
}

// backoffDelay returns the wait duration before the n-th retry (n ≥ 1)
// using exponential growth capped at retryMaxDelay, plus ±retryJitterPct
// uniform jitter to avoid thundering-herd resyncs.
func backoffDelay(attempt int) time.Duration {
	d := retryBaseDelay << (attempt - 1)
	if d > retryMaxDelay {
		d = retryMaxDelay
	}
	jitter := 1 + (rand.Float64()*2-1)*retryJitterPct //nolint:gosec — jitter, not crypto
	return time.Duration(float64(d) * jitter)
}

// Exit codes.
const (
	ExitSuccess   = 0
	ExitError     = 1
	ExitAuthError = 2
	ExitForbidden = 3
	ExitNotFound  = 4
	ExitConnError = 5
)

// Client is the HTTP client for the PrexorCloud REST API.
type Client struct {
	BaseURL    string
	Token      string
	HTTPClient *http.Client
	Verbose    bool
}

// New creates a new API client.
func New(baseURL, token string, verbose bool) *Client {
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Token:   token,
		HTTPClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		Verbose: verbose,
	}
}

// APIError represents an error response from the API.
type APIError struct {
	StatusCode int
	Code       string `json:"code"`
	Message    string `json:"message"`
}

func (e *APIError) Error() string {
	if e.Message != "" {
		return fmt.Sprintf("%s (HTTP %d)", e.Message, e.StatusCode)
	}
	return fmt.Sprintf("HTTP %d", e.StatusCode)
}

// ExitCode returns the appropriate exit code for the error.
func (e *APIError) ExitCode() int {
	switch e.StatusCode {
	case 401:
		return ExitAuthError
	case 403:
		return ExitForbidden
	case 404:
		return ExitNotFound
	default:
		return ExitError
	}
}

// parseAPIError builds an APIError from a >=400 response. It decodes the
// {code,message} envelope when present, and otherwise falls back to a trimmed
// snippet of the raw body so the operator sees *why* a request failed instead
// of a bare "HTTP 500". Closing the body stays the caller's responsibility.
func parseAPIError(resp *http.Response) *APIError {
	apiErr := &APIError{StatusCode: resp.StatusCode}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<10))
	if len(raw) == 0 {
		return apiErr
	}

	// The controller is not perfectly consistent in its error envelope: some
	// handlers return {code,message}, some nest it under {error:{...}}, and the
	// validation layer uses RFC 7807 {title,detail,status}. Probe all of them so
	// the operator sees a real message instead of a raw JSON blob.
	var env struct {
		Code    string `json:"code"`
		Message string `json:"message"`
		Title   string `json:"title"`
		Detail  string `json:"detail"`
		Error   struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	_ = json.Unmarshal(raw, &env)
	switch {
	case env.Message != "":
		apiErr.Message, apiErr.Code = env.Message, env.Code
	case env.Error.Message != "":
		apiErr.Message, apiErr.Code = env.Error.Message, env.Error.Code
	case env.Detail != "":
		apiErr.Message = env.Detail
	case env.Title != "":
		apiErr.Message = env.Title
	default:
		// Unrecognized shape — surface a trimmed snippet of the raw body.
		msg := strings.TrimSpace(string(raw))
		const max = 200
		if len(msg) > max {
			msg = msg[:max] + "…"
		}
		apiErr.Message = msg
	}
	return apiErr
}

func (c *Client) do(method, path string, body any, result any) error {
	return c.doCtx(context.Background(), method, path, body, result)
}

func (c *Client) doCtx(ctx context.Context, method, path string, body any, result any) error {
	u := c.BaseURL + path

	// Marshal the body once — we may need to replay it on retry, so we keep
	// the raw bytes around rather than recomputing.
	var bodyBytes []byte
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("marshal request: %w", err)
		}
		bodyBytes = data
	}

	retry := isIdempotent(method)
	maxAttempts := 1
	if retry {
		maxAttempts = retryMaxAttempts
	}

	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		var bodyReader io.Reader
		if bodyBytes != nil {
			bodyReader = bytes.NewReader(bodyBytes)
		}

		req, err := http.NewRequestWithContext(ctx, method, u, bodyReader)
		if err != nil {
			return fmt.Errorf("create request: %w", err)
		}
		if bodyBytes != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if c.Token != "" {
			req.Header.Set("Authorization", "Bearer "+c.Token)
		}

		if c.Verbose {
			if attempt == 1 {
				fmt.Fprintf(os.Stderr, "→ %s %s\n", method, u)
			} else {
				fmt.Fprintf(os.Stderr, "→ %s %s (retry %d/%d)\n", method, u, attempt-1, maxAttempts-1)
			}
		}

		resp, err := c.HTTPClient.Do(req)
		if err != nil {
			lastErr = fmt.Errorf("connection error: %w", err)
			if retry && attempt < maxAttempts {
				if waitErr := sleepWithCtx(ctx, backoffDelay(attempt)); waitErr != nil {
					return waitErr
				}
				continue
			}
			return lastErr
		}

		if c.Verbose {
			fmt.Fprintf(os.Stderr, "← %d %s\n", resp.StatusCode, resp.Status)
		}

		// Success path — decode and return.
		if resp.StatusCode < 400 {
			if result != nil {
				if err := json.NewDecoder(resp.Body).Decode(result); err != nil {
					resp.Body.Close()
					return fmt.Errorf("decode response: %w", err)
				}
			}
			resp.Body.Close()
			return nil
		}

		// Error path — decode the APIError envelope (falling back to the raw
		// body snippet). Close before the next attempt so the connection can be
		// reused from the pool.
		apiErr := parseAPIError(resp)
		resp.Body.Close()

		if retry && attempt < maxAttempts && shouldRetryStatus(resp.StatusCode) {
			lastErr = apiErr
			if waitErr := sleepWithCtx(ctx, backoffDelay(attempt)); waitErr != nil {
				return waitErr
			}
			continue
		}
		return apiErr
	}
	// Exhausted attempts.
	return lastErr
}

// sleepWithCtx pauses for d, but returns early if ctx is cancelled.
func sleepWithCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Get performs a GET request.
func (c *Client) Get(path string, result any) error {
	return c.do(http.MethodGet, path, nil, result)
}

// GetList performs a GET request and decodes the response into a slice.
// Handles both bare arrays (legacy) and the controller's standard pagination
// envelope `{"data":[...], "page":N, "pageSize":N, "total":N}` transparently.
func (c *Client) GetList(path string, params map[string]string, items any) error {
	var raw json.RawMessage
	if err := c.GetWithQuery(path, params, &raw); err != nil {
		return err
	}
	trimmed := bytes.TrimLeft(raw, " \t\r\n")
	if len(trimmed) > 0 && trimmed[0] == '{' {
		var env struct {
			Data json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(raw, &env); err == nil && env.Data != nil {
			return json.Unmarshal(env.Data, items)
		}
	}
	return json.Unmarshal(raw, items)
}

// GetWithQuery performs a GET request with query parameters.
func (c *Client) GetWithQuery(path string, params map[string]string, result any) error {
	if len(params) > 0 {
		q := url.Values{}
		for k, v := range params {
			if v != "" {
				q.Set(k, v)
			}
		}
		if encoded := q.Encode(); encoded != "" {
			path += "?" + encoded
		}
	}
	return c.do(http.MethodGet, path, nil, result)
}

// Post performs a POST request.
func (c *Client) Post(path string, body any, result any) error {
	return c.do(http.MethodPost, path, body, result)
}

// Patch performs a PATCH request.
func (c *Client) Patch(path string, body any, result any) error {
	return c.do(http.MethodPatch, path, body, result)
}

// Put performs a PUT request.
func (c *Client) Put(path string, body any, result any) error {
	return c.do(http.MethodPut, path, body, result)
}

// Delete performs a DELETE request.
func (c *Client) Delete(path string, result any) error {
	return c.do(http.MethodDelete, path, nil, result)
}

// Upload performs a multipart file upload via POST.
func (c *Client) Upload(path string, filePath string, result any) error {
	return c.UploadWithSignature(path, filePath, "", result)
}

// UploadBytes posts an in-memory payload as a single-field multipart upload.
// Used for the frontend hot-reload path where the zipped bundle never needs
// to land on disk.
func (c *Client) UploadBytes(path, fieldName, fileName string, payload []byte, result any) error {
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile(fieldName, fileName)
	if err != nil {
		return fmt.Errorf("create form file: %w", err)
	}
	if _, err := part.Write(payload); err != nil {
		return fmt.Errorf("write payload: %w", err)
	}
	writer.Close()

	u := c.BaseURL + path
	req, err := http.NewRequest(http.MethodPost, u, &buf)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "→ POST %s (multipart bytes upload, %d bytes)\n", u, len(payload))
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("connection error: %w", err)
	}
	defer resp.Body.Close()

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "← %d %s\n", resp.StatusCode, resp.Status)
	}

	if resp.StatusCode >= 400 {
		return parseAPIError(resp)
	}

	if result != nil {
		if err := json.NewDecoder(resp.Body).Decode(result); err != nil {
			return fmt.Errorf("decode response: %w", err)
		}
	}
	return nil
}

// UploadWithSignature performs a multipart file upload via POST with an optional
// signature sidecar. When sigPath is empty, behaves like Upload.
func (c *Client) UploadWithSignature(path string, filePath string, sigPath string, result any) error {
	f, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("open file: %w", err)
	}
	defer f.Close()

	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile("file", filepath.Base(filePath))
	if err != nil {
		return fmt.Errorf("create form file: %w", err)
	}
	if _, err := io.Copy(part, f); err != nil {
		return fmt.Errorf("copy file: %w", err)
	}

	if sigPath != "" {
		sf, err := os.Open(sigPath)
		if err != nil {
			return fmt.Errorf("open signature: %w", err)
		}
		defer sf.Close()
		sigPart, err := writer.CreateFormFile("signature", filepath.Base(sigPath))
		if err != nil {
			return fmt.Errorf("create signature part: %w", err)
		}
		if _, err := io.Copy(sigPart, sf); err != nil {
			return fmt.Errorf("copy signature: %w", err)
		}
	}
	writer.Close()

	u := c.BaseURL + path
	req, err := http.NewRequest(http.MethodPost, u, &buf)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "→ POST %s (multipart upload)\n", u)
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("connection error: %w", err)
	}
	defer resp.Body.Close()

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "← %d %s\n", resp.StatusCode, resp.Status)
	}

	if resp.StatusCode >= 400 {
		return parseAPIError(resp)
	}

	if result != nil {
		if err := json.NewDecoder(resp.Body).Decode(result); err != nil {
			return fmt.Errorf("decode response: %w", err)
		}
	}
	return nil
}

// SSEStream opens a Server-Sent Events connection and calls the handler for each event.
// The context controls the lifetime of the stream — cancel it to close the connection.
func (c *Client) SSEStream(ctx context.Context, path string, handler func(event, data string) bool) error {
	return c.SSEStreamWithTicket(ctx, path, "/api/v1/events/ticket", handler)
}

// SSEStreamWithTicket opens an SSE connection sourcing the ticket from a
// custom permission-gated endpoint (e.g. /api/v1/system/logs/ticket).
func (c *Client) SSEStreamWithTicket(
	ctx context.Context,
	path string,
	ticketPath string,
	handler func(event, data string) bool,
) error {
	if c.Token == "" {
		return errors.New("missing auth token")
	}

	u := c.BaseURL + path
	ticket, err := c.createSSETicketAt(ctx, ticketPath)
	if err != nil {
		return fmt.Errorf("create SSE ticket: %w", err)
	}
	if strings.Contains(u, "?") {
		u += "&ticket=" + url.QueryEscape(ticket)
	} else {
		u += "?ticket=" + url.QueryEscape(ticket)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "text/event-stream")

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("connection error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return parseAPIError(resp)
	}

	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 1024)
	var event, data string

	for {
		n, err := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
			for {
				idx := bytes.IndexByte(buf, '\n')
				if idx < 0 {
					break
				}
				line := string(buf[:idx])
				buf = buf[idx+1:]

				if line == "" {
					if data != "" {
						if !handler(event, data) {
							return nil
						}
						event = ""
						data = ""
					}
					continue
				}

				if strings.HasPrefix(line, "event:") {
					event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
				} else if strings.HasPrefix(line, "data:") {
					data = strings.TrimSpace(strings.TrimPrefix(line, "data:"))
				}
			}
		}
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
	}
}

func (c *Client) createSSETicket(ctx context.Context) (string, error) {
	return c.createSSETicketAt(ctx, "/api/v1/events/ticket")
}

func (c *Client) createSSETicketAt(ctx context.Context, ticketPath string) (string, error) {
	if c.Token == "" {
		return "", errors.New("missing auth token")
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+ticketPath, nil)
	if err != nil {
		return "", fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("connection error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return "", parseAPIError(resp)
	}

	var body struct {
		Ticket string `json:"ticket"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", fmt.Errorf("decode response: %w", err)
	}
	if body.Ticket == "" {
		return "", errors.New("empty SSE ticket response")
	}
	return body.Ticket, nil
}
