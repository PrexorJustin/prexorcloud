package setup

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// VerifyOptions parameterise a cosign verify-blob invocation against an
// upstream artefact signed in the `.sig` + `.crt` format (the output of
// `cosign sign-blob --output-signature ... --output-certificate ...`).
// This matches what .github/workflows/release.yml and release-jars.yml
// actually publish — keep callers and CI in sync.
type VerifyOptions struct {
	BlobPath       string // path to the artefact being verified
	SignatureURL   string // upstream URL of the .sig sidecar
	CertificateURL string // upstream URL of the .crt sidecar
	IdentityRegex  string // certificate-identity regex (signer subject)
	OIDCIssuer     string // certificate-oidc-issuer URL
	WorkDir        string // where to land the downloaded sidecars
	AllowMissing   bool   // when true, missing sidecars/cosign returns nil with a warning
}

// Identity regexes for keyless-signed prexorcloud release artefacts. The
// regex is case-insensitive because GitHub Actions OIDC subjects sometimes
// normalise the org/repo segment to lowercase even when the canonical
// repository name uses mixed case (PrexorJustin/prexorcloud).
const (
	// CosignIdentityRegexPrexorctl matches the GoReleaser-signed
	// checksums.txt for the prexorctl CLI binary (release.yml).
	CosignIdentityRegexPrexorctl = `(?i)^https://github\.com/prexorjustin/prexorcloud/\.github/workflows/release\.ya?ml@.*`

	// CosignIdentityRegexJars matches the SHA256SUMS signature for the
	// controller + daemon JARs (release-jars.yml).
	CosignIdentityRegexJars = `(?i)^https://github\.com/prexorjustin/prexorcloud/\.github/workflows/release-jars\.ya?ml@.*`

	// DefaultCosignOIDCIssuer is GitHub Actions' OIDC token issuer.
	DefaultCosignOIDCIssuer = "https://token.actions.githubusercontent.com"
)

// VerifyCosignSignature runs `cosign verify-blob` against opts.BlobPath
// using the .sig + .crt sidecars fetched from opts.SignatureURL and
// opts.CertificateURL. Returns nil on success, an error on signature
// mismatch or unexpected I/O failure.
//
// Soft-fail policy (AllowMissing=true):
//   - cosign not on PATH      → warn + return nil
//   - sidecar URL returns 404 → warn + return nil
//
// AllowMissing is intended for dev / unsigned-fixture workflows.
// Production callers must pass false.
func VerifyCosignSignature(opts VerifyOptions) error {
	if opts.BlobPath == "" || opts.SignatureURL == "" || opts.CertificateURL == "" {
		return errors.New("cosign verify: BlobPath, SignatureURL, CertificateURL are required")
	}
	if opts.IdentityRegex == "" {
		return errors.New("cosign verify: IdentityRegex is required")
	}
	if opts.OIDCIssuer == "" {
		opts.OIDCIssuer = DefaultCosignOIDCIssuer
	}

	cosignPath, err := exec.LookPath("cosign")
	if err != nil {
		msg := "cosign not installed — signature verification skipped. " +
			"For production installs, install cosign: https://docs.sigstore.dev/cosign/installation/"
		if opts.AllowMissing {
			fmt.Fprintln(os.Stderr, "warning: "+msg)
			return nil
		}
		return fmt.Errorf("%s", msg)
	}

	base := filepath.Base(opts.BlobPath)
	sigDest := filepath.Join(opts.WorkDir, base+".sig")
	crtDest := filepath.Join(opts.WorkDir, base+".crt")

	if err := downloadSidecar(opts.SignatureURL, sigDest); err != nil {
		if errors.Is(err, os.ErrNotExist) && opts.AllowMissing {
			fmt.Fprintf(os.Stderr,
				"warning: cosign signature missing (%s) — continuing with checksum-only integrity.\n",
				opts.SignatureURL)
			return nil
		}
		return fmt.Errorf("download cosign signature: %w", err)
	}
	if err := downloadSidecar(opts.CertificateURL, crtDest); err != nil {
		if errors.Is(err, os.ErrNotExist) && opts.AllowMissing {
			fmt.Fprintf(os.Stderr,
				"warning: cosign certificate missing (%s) — continuing with checksum-only integrity.\n",
				opts.CertificateURL)
			return nil
		}
		return fmt.Errorf("download cosign certificate: %w", err)
	}

	cmd := exec.Command(cosignPath, //nolint:gosec — args are static + fully derived from opts
		"verify-blob",
		"--certificate", crtDest,
		"--signature", sigDest,
		"--certificate-identity-regexp", opts.IdentityRegex,
		"--certificate-oidc-issuer", opts.OIDCIssuer,
		opts.BlobPath,
	)
	// Tee verify output to both the CLI's stderr (terminal users) and the
	// command sink (the browser wizard streams it live).
	w := io.MultiWriter(os.Stderr, currentSink())
	cmd.Stdout = w
	cmd.Stderr = w
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("cosign verify-blob failed: %w", err)
	}
	return nil
}

// VerifyReleaseAsset is the high-level entry the wizard uses before
// executing a downloaded JAR. Trust chain:
//
//  1. Cosign-verify SHA256SUMS against identityRegex + the GH Actions OIDC
//     issuer using SHA256SUMS.sig + SHA256SUMS.crt from the same release.
//  2. Parse the now-trusted SHA256SUMS and locate the line for remoteName.
//  3. Hash localPath and compare against the trusted entry.
//
// When cosign is missing AND allowMissing=true, step 1 is skipped with a
// loud warning — step 3 still catches in-flight corruption. Production
// paths must pass allowMissing=false to fail closed.
//
// remoteName is the asset filename as it appears in SHA256SUMS (matching
// what GitHub serves), independent of whatever local filename the caller
// renamed it to on disk.
func VerifyReleaseAsset(localPath, remoteName string, rel Release, identityRegex string, allowMissing bool) error {
	sumsAsset, err := FindAsset(rel, "SHA256SUMS")
	if err != nil {
		return fmt.Errorf("release %s has no SHA256SUMS — cannot verify integrity", rel.TagName)
	}
	sigAsset, err := FindAsset(rel, "SHA256SUMS.sig")
	if err != nil && !allowMissing {
		return fmt.Errorf("release %s has no SHA256SUMS.sig", rel.TagName)
	}
	crtAsset, err := FindAsset(rel, "SHA256SUMS.crt")
	if err != nil && !allowMissing {
		return fmt.Errorf("release %s has no SHA256SUMS.crt", rel.TagName)
	}

	workDir := filepath.Dir(localPath)
	sumsPath := filepath.Join(workDir, "SHA256SUMS")
	if err := DownloadFile(sumsAsset.BrowserDownloadURL, sumsPath); err != nil {
		return fmt.Errorf("download SHA256SUMS: %w", err)
	}
	defer os.Remove(sumsPath)

	if sigAsset.BrowserDownloadURL != "" && crtAsset.BrowserDownloadURL != "" {
		if err := VerifyCosignSignature(VerifyOptions{
			BlobPath:       sumsPath,
			SignatureURL:   sigAsset.BrowserDownloadURL,
			CertificateURL: crtAsset.BrowserDownloadURL,
			IdentityRegex:  identityRegex,
			WorkDir:        workDir,
			AllowMissing:   allowMissing,
		}); err != nil {
			return fmt.Errorf("cosign verify SHA256SUMS: %w", err)
		}
		// Clean up sidecar artefacts the cosign step downloaded next to
		// the local file. Best-effort — leaving them behind is harmless.
		_ = os.Remove(filepath.Join(workDir, "SHA256SUMS.sig"))
		_ = os.Remove(filepath.Join(workDir, "SHA256SUMS.crt"))
	} else {
		fmt.Fprintln(os.Stderr,
			"warning: SHA256SUMS signature absent from this release — falling back to checksum-only integrity.")
	}

	expected, err := lookupChecksum(sumsPath, remoteName)
	if err != nil {
		return err
	}
	actual, err := sha256File(localPath)
	if err != nil {
		return fmt.Errorf("hash %s: %w", localPath, err)
	}
	if actual != expected {
		return fmt.Errorf("SHA-256 mismatch for %s: expected %s, got %s", remoteName, expected, actual)
	}
	return nil
}

// downloadSidecar fetches url to dest. Returns os.ErrNotExist on a 404 so
// callers can disambiguate "no signature was published" from "the network
// is broken". Other HTTP errors and IO errors are wrapped.
func downloadSidecar(url, dest string) error {
	resp, err := http.Get(url) //nolint:noctx — short-lived setup-time fetch
	if err != nil {
		return fmt.Errorf("http get: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return os.ErrNotExist
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("http %d", resp.StatusCode)
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(f, resp.Body)
	return err
}

// lookupChecksum parses a `sha256sum`-formatted file and returns the hex
// hash for the entry matching filename. The format is "<hash>  <name>" or
// "<hash> *<name>" (binary mode); both are accepted.
func lookupChecksum(path, filename string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read %s: %w", path, err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		name := strings.TrimPrefix(fields[1], "*")
		if name == filename {
			return fields[0], nil
		}
	}
	return "", fmt.Errorf("no checksum entry for %q in %s", filename, path)
}

// sha256File returns the hex-encoded SHA-256 of path.
func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// CosignBundleURL is retained as a thin shim for any external caller still
// expecting the legacy `.cosign.bundle` URL convention. Internal code should
// use VerifyReleaseAsset, which derives sidecar URLs from the release manifest.
//
// Deprecated: prexorcloud no longer publishes .cosign.bundle artefacts.
// New callers should use VerifyReleaseAsset.
func CosignBundleURL(blobURL string) string {
	return strings.TrimRight(blobURL, "/") + ".cosign.bundle"
}
