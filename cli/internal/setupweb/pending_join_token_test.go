package setupweb

import (
	"os"
	"path/filepath"
	"testing"
)

// writePendingJoinToken is the wizard's hand-off to PrexorCloudBootstrap's
// pending-token branch. The file path is part of a contract with
// PrexorCloudBootstrap.PENDING_JOIN_TOKEN_FILE
// (java/cloud-controller/.../controller/PrexorCloudBootstrap.java), so the
// shape of what we write is load-bearing — these tests pin both the
// filesystem-level invariants and the exact byte layout the Java side reads.

func TestWritePendingJoinToken_CreatesParentDirsAndWritesToken(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config", "security", "pending-join-token")
	token := "prexor-jt:v1:eyJqdGkiOiJhYmMifQ.aGVsbG8"

	if err := writePendingJoinToken(path, token); err != nil {
		t.Fatalf("writePendingJoinToken returned err: %v", err)
	}

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	want := token + "\n"
	if string(got) != want {
		t.Errorf("file contents = %q, want %q", string(got), want)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	// 0o600: owner read/write only. The token is single-use credential
	// material; world-readable would be a leak even on a developer laptop.
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("file perms = %o, want 0600", perm)
	}

	// And the parent dir gets 0o700 to match the Java side's
	// FilePermissions.setOwnerOnly on config/security.
	parent, err := os.Stat(filepath.Dir(path))
	if err != nil {
		t.Fatalf("Stat parent: %v", err)
	}
	if perm := parent.Mode().Perm(); perm != 0o700 {
		t.Errorf("parent dir perms = %o, want 0700", perm)
	}
}

func TestWritePendingJoinToken_OverwritesPriorToken(t *testing.T) {
	// Re-running the wizard against the same install dir should replace any
	// stale token rather than append. Operators retrying a failed install
	// need the new token to take effect, not blend with the broken one.
	dir := t.TempDir()
	path := filepath.Join(dir, "pending-join-token")
	if err := writePendingJoinToken(path, "prexor-jt:v1:first"); err != nil {
		t.Fatal(err)
	}
	if err := writePendingJoinToken(path, "prexor-jt:v1:second"); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "prexor-jt:v1:second\n" {
		t.Errorf("file contents = %q, want %q", string(got), "prexor-jt:v1:second\n")
	}
}
