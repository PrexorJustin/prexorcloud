package setupweb

import (
	"bufio"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func quietLogf(string, ...any) {}

// streamRequest builds a POST carrying Accept: application/x-ndjson so
// newEmitter selects the streaming implementation.
func streamRequest() *http.Request {
	r := httptest.NewRequest(http.MethodPost, "/api/install/controller", nil)
	r.Header.Set("Accept", "application/x-ndjson")
	return r
}

func decodeEvents(t *testing.T, body string) []streamEvent {
	t.Helper()
	var evs []streamEvent
	sc := bufio.NewScanner(strings.NewReader(body))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var ev streamEvent
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			t.Fatalf("non-JSON NDJSON line %q: %v", line, err)
		}
		evs = append(evs, ev)
	}
	return evs
}

func TestStreamEmitter_StepOutputDone(t *testing.T) {
	w := httptest.NewRecorder()
	var succeeded bool
	em := newEmitter(w, streamRequest(), quietLogf, func() { succeeded = true })

	if _, ok := em.(*streamEmitter); !ok {
		t.Fatalf("expected streamEmitter, got %T", em)
	}
	if ct := w.Header().Get("Content-Type"); ct != streamMediaType {
		t.Fatalf("Content-Type = %q, want %q", ct, streamMediaType)
	}

	em.step("Resolving latest controller release…")
	// Raw output is forwarded verbatim, including ANSI + carriage returns.
	if _, err := em.rawWriter().Write([]byte("$ apt-get install -y redis\r\n\x1b[32mSetting up redis…\x1b[0m\n")); err != nil {
		t.Fatalf("rawWriter write: %v", err)
	}
	em.finishOK([]string{"systemctl status prexorcloud-controller"})

	evs := decodeEvents(t, w.Body.String())
	// step, one output chunk (verbatim), done
	if len(evs) != 3 {
		t.Fatalf("got %d events, want 3: %+v", len(evs), evs)
	}
	if evs[0].Type != "step" || evs[0].Msg == "" {
		t.Errorf("event 0 = %+v, want a step", evs[0])
	}
	if evs[1].Type != "output" || !strings.Contains(evs[1].Data, "\x1b[32m") || !strings.Contains(evs[1].Data, "\r\n") {
		t.Errorf("event 1 = %+v, want raw output preserving ANSI + CRLF", evs[1])
	}
	last := evs[2]
	if last.Type != "done" || !last.OK || len(last.NextSteps) != 1 {
		t.Errorf("final event = %+v, want done{ok,nextSteps}", last)
	}
	if !succeeded {
		t.Error("onSuccess hook was not invoked on finishOK")
	}
}

func TestStreamEmitter_FailDoesNotSucceed(t *testing.T) {
	w := httptest.NewRecorder()
	var succeeded bool
	em := newEmitter(w, streamRequest(), quietLogf, func() { succeeded = true })

	em.step("Installing MongoDB…")
	em.finishErr(errDepInstall, "install MongoDB: exit status 100")

	evs := decodeEvents(t, w.Body.String())
	last := evs[len(evs)-1]
	if last.Type != "done" || last.OK {
		t.Errorf("final event = %+v, want done{ok:false}", last)
	}
	if last.Error == "" || last.ErrorCode != errDepInstall {
		t.Errorf("final event missing error/code: %+v", last)
	}
	if last.DocsURL == "" {
		t.Errorf("expected a docs URL for %s", errDepInstall)
	}
	if succeeded {
		t.Error("onSuccess must NOT fire on finishErr (operator should be able to retry)")
	}
}

func TestNewEmitter_BufferedWithoutAcceptHeader(t *testing.T) {
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/api/install/controller", nil) // no Accept
	em := newEmitter(w, r, quietLogf, func() {})
	if _, ok := em.(*bufferedEmitter); !ok {
		t.Fatalf("expected bufferedEmitter without Accept header, got %T", em)
	}

	em.step("step one")
	em.finishOK([]string{"next"})

	var resp installResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("buffered body is not a single installResponse: %v", err)
	}
	if !resp.OK || len(resp.Messages) != 1 || len(resp.NextSteps) != 1 {
		t.Errorf("buffered response = %+v, want ok with 1 message + 1 next step", resp)
	}
}
