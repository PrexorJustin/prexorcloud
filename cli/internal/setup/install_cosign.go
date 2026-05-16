package setup

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// cosignReleaseBase is the GitHub "latest" download path for the official
// sigstore cosign release binaries. The release attaches one self-contained
// binary per OS/arch (cosign-linux-amd64, cosign-linux-arm64, …) plus a
// cosign_checksums.txt manifest we use for integrity.
const cosignReleaseBase = "https://github.com/sigstore/cosign/releases/latest/download/"

// cosignInstallPath is where EnsureCosign drops the binary. /usr/local/bin is
// on root's PATH on every supported distro and is the conventional location
// for operator-installed tools (won't be clobbered by the package manager).
const cosignInstallPath = "/usr/local/bin/cosign"

// EnsureCosign guarantees the cosign binary is available on PATH so JAR
// signature verification can run for real instead of soft-failing. It returns
// (alreadyPresent, err): when cosign is already on PATH it is a no-op
// returning (true, nil); otherwise it installs the official release binary and
// returns (false, nil). On failure the caller may fall back to checksum-only
// integrity rather than abort the install.
//
// Output streams to the command sink (SetCommandSink) so the wizard shows the
// download + verification live.
func EnsureCosign() (alreadyPresent bool, err error) {
	if p := DetectBinary("cosign"); p != "" {
		return true, nil
	}
	return false, InstallCosign()
}

// InstallCosign downloads the official cosign release binary for the host
// architecture, verifies it against the release's published SHA-256 manifest,
// and installs it to /usr/local/bin/cosign (0755). It does not use a package
// manager: cosign is absent from most distro base repos, and the upstream
// binary is the method sigstore documents and the one that behaves identically
// across every distro family.
func InstallCosign() error {
	arch := runtime.GOARCH // cosign names assets "amd64"/"arm64" — matches GOARCH.
	switch arch {
	case "amd64", "arm64":
	default:
		return fmt.Errorf("no cosign release binary for architecture %s", arch)
	}
	asset := "cosign-linux-" + arch

	tmp := filepath.Join(os.TempDir(), asset)
	defer os.Remove(tmp)
	sinkPrintf("$ download %s%s\n", cosignReleaseBase, asset)
	if err := DownloadFile(cosignReleaseBase+asset, tmp); err != nil {
		return fmt.Errorf("download cosign: %w", err)
	}

	// Integrity: the binary can't sign-verify itself (chicken-and-egg), so we
	// pin its SHA-256 against the manifest served from the same release.
	sums := filepath.Join(os.TempDir(), "cosign_checksums.txt")
	defer os.Remove(sums)
	if err := DownloadFile(cosignReleaseBase+"cosign_checksums.txt", sums); err != nil {
		return fmt.Errorf("download cosign checksums: %w", err)
	}
	expected, err := lookupChecksum(sums, asset)
	if err != nil {
		return fmt.Errorf("cosign checksum manifest: %w", err)
	}
	actual, err := sha256File(tmp)
	if err != nil {
		return fmt.Errorf("hash downloaded cosign: %w", err)
	}
	if !strings.EqualFold(actual, expected) {
		return fmt.Errorf("cosign SHA-256 mismatch: expected %s, got %s", expected, actual)
	}

	if err := installExecutable(tmp, cosignInstallPath); err != nil {
		return fmt.Errorf("install cosign to %s: %w", cosignInstallPath, err)
	}
	// Confirm the freshly installed binary runs (and stream its version).
	if out, err := runCmd(exec.Command(cosignInstallPath, "version")); err != nil {
		return fmt.Errorf("cosign installed but not runnable: %w\n%s", err, out)
	}
	sinkPrintf("installed cosign to %s\n", cosignInstallPath)
	return nil
}

// installExecutable copies src to dest with 0755 perms. A plain copy (not
// os.Rename) because src lives in TempDir, which is frequently on a different
// filesystem than /usr/local/bin and would make rename fail with EXDEV.
func installExecutable(src, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
