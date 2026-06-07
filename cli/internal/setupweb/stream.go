package setupweb

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
)

// installEmitter abstracts how an install handler reports progress and its
// final result. Two implementations:
//
//   - bufferedEmitter collects every step into one installResponse and writes
//     it as a single JSON document at the end. This is the legacy contract the
//     CLI tests and any non-streaming client rely on.
//   - streamEmitter writes newline-delimited JSON (NDJSON) events as they
//     happen — high-level "step" lines, raw "output" lines tee'd from the VPS
//     commands, and a terminal "done" event. The browser wizard opts in with
//     Accept: application/x-ndjson so it can render live console output.
//
// Handlers are written once against this interface; newEmitter picks the
// implementation from the request's Accept header.
type installEmitter interface {
	// step records a high-level progress line (the say() messages).
	step(line string)
	// rawWriter returns the io.Writer raw command output should stream to, or
	// nil when this emitter doesn't forward raw output (buffered mode).
	rawWriter() io.Writer
	// finishOK terminates with success + next steps and invokes the success
	// hook (single-use wizard teardown).
	finishOK(nextSteps []string)
	// finishErr terminates with a failure. It does NOT invoke the success
	// hook, so a failed install leaves the wizard running for a retry.
	finishErr(code, msg string)
}

// streamMediaType is the Accept value the wizard sends to request live NDJSON
// streaming instead of a single buffered JSON response.
const streamMediaType = "application/x-ndjson"

// newEmitter returns a streaming emitter when the client asked for NDJSON and
// the ResponseWriter supports flushing; otherwise a buffered one. logf mirrors
// progress to the CLI terminal in both modes. onSuccess is called from
// finishOK only.
func newEmitter(w http.ResponseWriter, r *http.Request, logf func(string, ...any), onSuccess func()) installEmitter {
	flusher, canFlush := w.(http.Flusher)
	if canFlush && strings.Contains(r.Header.Get("Accept"), streamMediaType) {
		// Commit to a 200 streaming response now: validation already passed by
		// the time a handler builds its emitter, and every code path ends in a
		// "done" event carrying the real ok/error.
		h := w.Header()
		h.Set("Content-Type", streamMediaType)
		h.Set("Cache-Control", "no-cache")
		h.Set("X-Content-Type-Options", "nosniff")
		w.WriteHeader(http.StatusOK)
		flusher.Flush()
		return &streamEmitter{w: w, flusher: flusher, enc: json.NewEncoder(w), logf: logf, onSuccess: onSuccess}
	}
	return &bufferedEmitter{w: w, logf: logf, onSuccess: onSuccess}
}

// bufferedEmitter — single JSON document at the end (legacy contract).
type bufferedEmitter struct {
	w         http.ResponseWriter
	logf      func(string, ...any)
	onSuccess func()
	messages  []string
}

func (e *bufferedEmitter) step(line string) {
	e.messages = append(e.messages, line)
	e.logf("%s", line)
}

func (e *bufferedEmitter) rawWriter() io.Writer { return nil }

func (e *bufferedEmitter) finishOK(nextSteps []string) {
	writeJSON(e.w, http.StatusOK, installResponse{OK: true, Messages: e.messages, NextSteps: nextSteps})
	if e.onSuccess != nil {
		e.onSuccess()
	}
}

func (e *bufferedEmitter) finishErr(code, msg string) {
	writeInstallError(e.w, e.messages, code, msg)
}

// streamEvent is one NDJSON line. Type is "step" | "output" | "done".
//
// Output carries RAW terminal bytes (ANSI escapes, carriage returns, partial
// lines) exactly as the VPS command emitted them — the wizard feeds them to an
// xterm.js terminal that renders colours and in-place \r progress bars. JSON
// string encoding keeps each event on one physical NDJSON line even when Data
// contains newlines (they're escaped as \n).
type streamEvent struct {
	Type      string   `json:"type"`
	Msg       string   `json:"msg,omitempty"`       // step
	Data      string   `json:"data,omitempty"`      // output (raw terminal bytes)
	OK        bool     `json:"ok,omitempty"`        // done
	NextSteps []string `json:"nextSteps,omitempty"` // done
	Error     string   `json:"error,omitempty"`     // done
	ErrorCode string   `json:"errorCode,omitempty"` // done
	DocsURL   string   `json:"docsUrl,omitempty"`   // done
}

// streamEmitter — NDJSON events, flushed per write.
type streamEmitter struct {
	w         http.ResponseWriter
	flusher   http.Flusher
	enc       *json.Encoder
	logf      func(string, ...any)
	onSuccess func()
	mu        sync.Mutex
}

// emit serialises one event and flushes it. json.Encoder appends a trailing
// newline, which is exactly the NDJSON record separator. Guarded by mu because
// raw command output arrives from exec's stdout/stderr pump goroutines, which
// can race with the handler goroutine's step/finish calls.
func (e *streamEmitter) emit(ev streamEvent) {
	e.mu.Lock()
	defer e.mu.Unlock()
	_ = e.enc.Encode(ev)
	e.flusher.Flush()
}

func (e *streamEmitter) step(line string) {
	e.logf("%s", line)
	e.emit(streamEvent{Type: "step", Msg: line})
}

func (e *streamEmitter) rawWriter() io.Writer { return rawForwarder{e: e} }

func (e *streamEmitter) finishOK(nextSteps []string) {
	e.emit(streamEvent{Type: "done", OK: true, NextSteps: nextSteps})
	if e.onSuccess != nil {
		e.onSuccess()
	}
}

func (e *streamEmitter) finishErr(code, msg string) {
	e.emit(streamEvent{Type: "done", OK: false, Error: msg, ErrorCode: code, DocsURL: docURLFor(code)})
}

// rawForwarder forwards each chunk of raw command output verbatim as one
// "output" event — no line-splitting and no stripping of carriage returns or
// ANSI escapes, so the wizard's xterm.js terminal reproduces the VPS console
// exactly (colours, spinners, apt's \r progress bars). xterm tolerates ANSI
// sequences split across writes, so chunk boundaries are harmless. emit() is
// already mutex-guarded, which serialises the concurrent writes exec spawns
// for a command's stdout and stderr pipes.
type rawForwarder struct{ e *streamEmitter }

func (w rawForwarder) Write(p []byte) (int, error) {
	if len(p) > 0 {
		w.e.emit(streamEvent{Type: "output", Data: string(p)})
	}
	return len(p), nil
}
