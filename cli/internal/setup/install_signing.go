package setup

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// Layout of the trust-root artefacts inside the controller install dir.
//
// The PEM lives under config/ so it's bind-mounted into the compose project
// alongside controller.yml (the compose path already mounts the config dir),
// and the Java validator accepts the same relative path on the native path.
// The private key is parked under secrets/ — same dir tree, distinct prefix —
// so a careless `tar config/` for a backup doesn't include the key.
const (
	moduleTrustRootRelPath = "config/security/module-trust-root.pem"
	cosignPrivateKeyRelDir = "secrets"
	cosignPrivateKeyName   = "cosign.key"
)

// ManagedTrustRootPath returns the relative path the wizard auto-provisions a
// cosign trust-root PEM at when the operator hasn't supplied one of their
// own. The install handler uses this to decide whether a wizard YAML's
// trustRoot field is "operator-managed" (different path → don't touch) or
// "wizard-managed" (matches → ProvisionModuleTrustRoot will keep it stocked).
func ManagedTrustRootPath() string { return moduleTrustRootRelPath }

// ModuleTrustRoot captures the absolute paths of the provisioned trust-root
// artefacts inside an installation. ConfigPath is the path WRITTEN INTO
// controller.yml as modules.signing.trustRoot — relative to the install dir
// so it works identically from native (WorkingDirectory=installDir) and
// compose (the config dir is bind-mounted as-is).
type ModuleTrustRoot struct {
	ConfigPath     string // relative path the controller config references
	PublicKeyFile  string // absolute path on disk
	PrivateKeyFile string // absolute path on disk (0600)
}

// ProvisionModuleTrustRoot generates a cosign keypair when one isn't already
// present and writes:
//
//   - <installDir>/config/security/module-trust-root.pem  (0644, the public key)
//   - <installDir>/secrets/cosign.key                     (0600, the private key)
//
// Both files use plain PEM blocks emitted by `cosign generate-key-pair`. The
// public key path is what controller.yml's modules.signing.trustRoot points
// at; the private key is the one operators sign modules with later.
//
// Passwordless — the operator hasn't been prompted for a key passphrase in
// the wizard, and adding that prompt mid-install would derail the flow for
// a feature most first-installs don't use immediately. COSIGN_PASSWORD=""
// is the documented way to request an unencrypted private key. The key
// lands inside /opt/prexorcloud/controller/secrets/ which is root-owned
// 0700 on the install host, so filesystem perms are the layer of defence.
//
// Idempotent: if both files already exist, ProvisionModuleTrustRoot returns
// the existing paths without regenerating. Re-running the wizard against an
// existing install must not silently rotate the key (would invalidate every
// signature the operator created against the old key).
func ProvisionModuleTrustRoot(installDir string) (ModuleTrustRoot, error) {
	pubFile := filepath.Join(installDir, moduleTrustRootRelPath)
	keyDir := filepath.Join(installDir, cosignPrivateKeyRelDir)
	keyFile := filepath.Join(keyDir, cosignPrivateKeyName)

	tr := ModuleTrustRoot{
		ConfigPath:     moduleTrustRootRelPath,
		PublicKeyFile:  pubFile,
		PrivateKeyFile: keyFile,
	}

	// Idempotency check: both halves of the pair must exist. A half-present
	// state (e.g. pubkey survived but secrets/ was wiped) means we can't
	// honour signatures the operator already issued, so regenerate fresh
	// rather than serve a public key whose private half is gone.
	pubExists := fileExists(pubFile)
	keyExists := fileExists(keyFile)
	if pubExists && keyExists {
		sinkPrintf("Module signing trust root already provisioned at %s.\n", pubFile)
		return tr, nil
	}

	cosign := DetectBinary("cosign")
	if cosign == "" {
		return ModuleTrustRoot{}, fmt.Errorf("cosign binary not on PATH; install cosign first")
	}

	if err := os.MkdirAll(filepath.Dir(pubFile), 0o755); err != nil {
		return ModuleTrustRoot{}, fmt.Errorf("create %s: %w", filepath.Dir(pubFile), err)
	}
	if err := os.MkdirAll(keyDir, 0o700); err != nil {
		return ModuleTrustRoot{}, fmt.Errorf("create %s: %w", keyDir, err)
	}

	// cosign writes cosign.key + cosign.pub next to the CWD with no override.
	// Generate into a fresh temp dir so we can move each half to its final
	// resting place with the perms we want, atomically and without polluting
	// the install dir if generation fails mid-way.
	tmp, err := os.MkdirTemp("", "prexorcloud-cosign-*")
	if err != nil {
		return ModuleTrustRoot{}, fmt.Errorf("create temp dir for cosign keys: %w", err)
	}
	defer os.RemoveAll(tmp)

	cmd := exec.Command(cosign, "generate-key-pair")
	cmd.Dir = tmp
	// COSIGN_PASSWORD="" requests an unencrypted private key. Without this,
	// cosign prompts on stdin and the install hangs forever under systemd.
	cmd.Env = append(os.Environ(), "COSIGN_PASSWORD=")
	if out, err := runCmd(cmd); err != nil {
		return ModuleTrustRoot{}, fmt.Errorf("cosign generate-key-pair: %w\n%s", err, out)
	}

	tmpPriv := filepath.Join(tmp, "cosign.key")
	tmpPub := filepath.Join(tmp, "cosign.pub")
	if !fileExists(tmpPriv) || !fileExists(tmpPub) {
		return ModuleTrustRoot{}, fmt.Errorf("cosign did not produce expected key files in %s", tmp)
	}

	// Move + relax perms. Use copy+remove rather than os.Rename so this works
	// across filesystems (TempDir is frequently a separate mount on Linux).
	if err := copyFileMode(tmpPub, pubFile, 0o644); err != nil {
		return ModuleTrustRoot{}, fmt.Errorf("install public key: %w", err)
	}
	if err := copyFileMode(tmpPriv, keyFile, 0o600); err != nil {
		// Roll back the public key — a public-only trust root with no signing
		// counterpart would silently lock the operator out of signing modules.
		_ = os.Remove(pubFile)
		return ModuleTrustRoot{}, fmt.Errorf("install private key: %w", err)
	}

	sinkPrintf("Generated cosign keypair → %s\n", keyFile)
	sinkPrintf("Wrote module trust root → %s\n", pubFile)
	return tr, nil
}

// copyFileMode writes src to dest with the given permission bits, creating dest
// fresh (O_TRUNC) so a stale file with wider perms can't survive. Used for the
// cosign keys where mode bits are load-bearing (0600 on the private key).
func copyFileMode(src, dest string, mode os.FileMode) error {
	in, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dest, in, mode)
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
