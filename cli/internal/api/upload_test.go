package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUpload_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if !strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
			t.Errorf("Content-Type = %q, want multipart/form-data", r.Header.Get("Content-Type"))
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("auth = %q, want Bearer test-token", r.Header.Get("Authorization"))
		}

		file, header, err := r.FormFile("file")
		if err != nil {
			t.Fatalf("FormFile error: %v", err)
		}
		defer file.Close()

		if header.Filename != "test-module.jar" {
			t.Errorf("filename = %q, want test-module.jar", header.Filename)
		}

		content, _ := io.ReadAll(file)
		if string(content) != "fake jar content" {
			t.Errorf("file content = %q", string(content))
		}

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"name": "test-module"})
	}))
	defer server.Close()

	// Create temp file
	tmpDir := t.TempDir()
	jarPath := filepath.Join(tmpDir, "test-module.jar")
	if err := os.WriteFile(jarPath, []byte("fake jar content"), 0644); err != nil {
		t.Fatal(err)
	}

	c := New(server.URL, "test-token", false)
	var result map[string]string
	err := c.Upload("/api/v1/modules/platform/upload", jarPath, &result)
	if err != nil {
		t.Fatalf("Upload() error = %v", err)
	}
	if result["name"] != "test-module" {
		t.Errorf("result = %v", result)
	}
}

func TestUpload_FileNotFound(t *testing.T) {
	c := New("http://localhost", "token", false)
	err := c.Upload("/upload", "/nonexistent/file.jar", nil)
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestUpload_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"message": "Only .jar files"})
	}))
	defer server.Close()

	tmpDir := t.TempDir()
	filePath := filepath.Join(tmpDir, "bad.txt")
	os.WriteFile(filePath, []byte("not a jar"), 0644)

	c := New(server.URL, "token", false)
	err := c.Upload("/upload", filePath, nil)
	if err == nil {
		t.Fatal("expected error")
	}
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("expected *APIError, got %T", err)
	}
	if apiErr.StatusCode != 400 {
		t.Errorf("StatusCode = %d, want 400", apiErr.StatusCode)
	}
}

func TestUploadWithSignature_PostsBothParts(t *testing.T) {
	var sawJar, sawSig bool
	var sigName, sigBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			t.Fatalf("ParseMultipartForm: %v", err)
		}
		if jarFile, jarHdr, err := r.FormFile("file"); err == nil {
			defer jarFile.Close()
			if jarHdr.Filename == "mod.jar" {
				sawJar = true
			}
		}
		if sigFile, sigHdr, err := r.FormFile("signature"); err == nil {
			defer sigFile.Close()
			sigName = sigHdr.Filename
			b, _ := io.ReadAll(sigFile)
			sigBody = string(b)
			sawSig = true
		}
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"moduleId": "ok"})
	}))
	defer server.Close()

	tmpDir := t.TempDir()
	jarPath := filepath.Join(tmpDir, "mod.jar")
	sigPath := filepath.Join(tmpDir, "mod.jar.cosign.bundle")
	os.WriteFile(jarPath, []byte("jar bytes"), 0o644)
	os.WriteFile(sigPath, []byte("bundle bytes"), 0o644)

	c := New(server.URL, "tok", false)
	if err := c.UploadWithSignature("/upload", jarPath, sigPath, nil); err != nil {
		t.Fatalf("UploadWithSignature: %v", err)
	}
	if !sawJar {
		t.Error("server did not receive jar part")
	}
	if !sawSig {
		t.Error("server did not receive signature part")
	}
	if sigName != "mod.jar.cosign.bundle" {
		t.Errorf("sig filename = %q", sigName)
	}
	if sigBody != "bundle bytes" {
		t.Errorf("sig body = %q", sigBody)
	}
}

func TestUploadWithSignature_OmitsPartWhenSigPathEmpty(t *testing.T) {
	var sawSig bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseMultipartForm(1 << 20)
		if _, _, err := r.FormFile("signature"); err == nil {
			sawSig = true
		}
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"moduleId": "ok"})
	}))
	defer server.Close()

	tmpDir := t.TempDir()
	jarPath := filepath.Join(tmpDir, "m.jar")
	os.WriteFile(jarPath, []byte("jar"), 0o644)

	c := New(server.URL, "tok", false)
	if err := c.UploadWithSignature("/upload", jarPath, "", nil); err != nil {
		t.Fatal(err)
	}
	if sawSig {
		t.Error("signature part should be absent when sigPath is empty")
	}
}
