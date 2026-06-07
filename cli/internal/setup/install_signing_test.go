package setup

import (
	"os"
	"path/filepath"
	"testing"
)

// TestProvisionModuleTrustRootIdempotent confirms a second call against an
// already-provisioned install returns the same paths without regenerating
// the key (we'd lose existing signatures otherwise).
func TestProvisionModuleTrustRootIdempotent(t *testing.T) {
	installDir := t.TempDir()
	pubPath := filepath.Join(installDir, moduleTrustRootRelPath)
	keyPath := filepath.Join(installDir, cosignPrivateKeyRelDir, cosignPrivateKeyName)

	if err := os.MkdirAll(filepath.Dir(pubPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(keyPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pubPath, []byte("-----BEGIN PUBLIC KEY-----\nfake\n-----END PUBLIC KEY-----\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, []byte("-----BEGIN ENCRYPTED COSIGN PRIVATE KEY-----\nfake\n-----END ENCRYPTED COSIGN PRIVATE KEY-----\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	tr, err := ProvisionModuleTrustRoot(installDir)
	if err != nil {
		t.Fatalf("ProvisionModuleTrustRoot returned error: %v", err)
	}
	if tr.ConfigPath != moduleTrustRootRelPath {
		t.Errorf("ConfigPath = %q, want %q", tr.ConfigPath, moduleTrustRootRelPath)
	}
	if tr.PublicKeyFile != pubPath {
		t.Errorf("PublicKeyFile = %q, want %q", tr.PublicKeyFile, pubPath)
	}
	got, err := os.ReadFile(pubPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "-----BEGIN PUBLIC KEY-----\nfake\n-----END PUBLIC KEY-----\n" {
		t.Errorf("idempotent call rewrote the existing public key:\n%s", got)
	}
}

// TestProvisionModuleTrustRootMissingCosign keeps the failure mode crisp when
// cosign isn't on PATH — we expect a clear error, not a partial install or a
// half-written key pair.
func TestProvisionModuleTrustRootMissingCosign(t *testing.T) {
	// Force DetectBinary to find nothing by pointing PATH at an empty dir.
	t.Setenv("PATH", t.TempDir())

	installDir := t.TempDir()
	_, err := ProvisionModuleTrustRoot(installDir)
	if err == nil {
		t.Fatalf("expected error when cosign is missing, got nil")
	}
	// The trust-root dir must not exist if provisioning failed — half-state
	// is what locks operators out of signing later.
	if fileExists(filepath.Join(installDir, moduleTrustRootRelPath)) {
		t.Errorf("public key file should not exist after failed provisioning")
	}
	if fileExists(filepath.Join(installDir, cosignPrivateKeyRelDir, cosignPrivateKeyName)) {
		t.Errorf("private key file should not exist after failed provisioning")
	}
}

// TestProvisionModuleTrustRootHalfState confirms that finding only one half of
// the pair triggers regeneration rather than serving a public key whose
// private counterpart is gone (signatures issued against it would never
// verify after a restore).
func TestProvisionModuleTrustRootHalfState(t *testing.T) {
	t.Setenv("PATH", t.TempDir()) // no cosign → can't regenerate

	installDir := t.TempDir()
	pubPath := filepath.Join(installDir, moduleTrustRootRelPath)
	if err := os.MkdirAll(filepath.Dir(pubPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pubPath, []byte("public only"), 0o644); err != nil {
		t.Fatal(err)
	}

	// Private key missing → must not short-circuit as idempotent.
	if _, err := ProvisionModuleTrustRoot(installDir); err == nil {
		t.Fatalf("expected error when private key is missing and cosign unavailable, got nil")
	}
}
