package theme

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"strings"
	"testing"
)

func captureStdout(t *testing.T, fn func()) string {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	old := os.Stdout
	os.Stdout = w

	fn()

	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	io.Copy(&buf, r)
	r.Close()
	return buf.String()
}

func TestPrintJSON_ProducesValidIndentedJSON(t *testing.T) {
	type payload struct {
		Name  string `json:"name"`
		Count int    `json:"count"`
	}
	p := payload{Name: "lobby", Count: 3}

	out := captureStdout(t, func() {
		if err := PrintJSON(p); err != nil {
			t.Errorf("PrintJSON() error = %v", err)
		}
	})

	var decoded payload
	if err := json.Unmarshal([]byte(out), &decoded); err != nil {
		t.Fatalf("output is not valid JSON: %v\noutput was: %s", err, out)
	}
	if decoded.Name != "lobby" || decoded.Count != 3 {
		t.Errorf("decoded = %+v, want {lobby 3}", decoded)
	}
	if !strings.Contains(out, "\n") {
		t.Error("JSON output should be indented (multi-line)")
	}
}

func TestPrintJSON_ReturnsErrorForUnencodable(t *testing.T) {
	ch := make(chan int)
	if err := PrintJSON(ch); err == nil {
		t.Error("PrintJSON() should return error for unencodable value")
	}
}
