package setup

import (
	"archive/tar"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	// GithubRepo is the canonical "owner/name" pair the wizard fetches
	// controller + daemon JARs from. Release artefacts are produced by
	// .github/workflows/release-jars.yml and attached to GitHub Releases
	// keyed by tag.
	GithubRepo = "PrexorJustin/prexorcloud"

	// AdoptiumAPIv3 — Eclipse Temurin Java 25 JRE bundle for Linux. The
	// %s placeholder takes the architecture name (x64 / aarch64).
	AdoptiumAPIv3 = "https://api.adoptium.net/v3/binary/latest/25/ga/linux/%s/jre/hotspot/normal/eclipse"
)

// ReleaseSource is a human-readable label for the active download backend,
// surfaced in the wizard spinner text so operators can see where bytes are
// coming from.
func ReleaseSource() string {
	return "GitHub releases (" + GithubRepo + ")"
}

// Release represents a GitHub release.
type Release struct {
	TagName string  `json:"tag_name"`
	Assets  []Asset `json:"assets"`
}

// Asset represents a file attached to a GitHub release.
type Asset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
}

// FetchLatestRelease queries the GitHub releases API for the latest tagged
// release of repo (e.g. "PrexorJustin/prexorcloud"). The returned Release
// carries every attached asset; callers pick the one they want with
// FindAsset or FindAssetPrefix.
func FetchLatestRelease(repo string) (Release, error) {
	url := fmt.Sprintf("https://api.github.com/repos/%s/releases/latest", repo)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return Release{}, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "prexorctl")

	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return Release{}, fmt.Errorf("failed to reach GitHub API: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return Release{}, fmt.Errorf("GitHub API returned %d — no releases published yet?", resp.StatusCode)
	}

	var rel Release
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return Release{}, fmt.Errorf("failed to parse GitHub API response: %w", err)
	}
	return rel, nil
}

// FindAsset returns the asset with the given exact name from a release.
func FindAsset(rel Release, name string) (Asset, error) {
	for _, a := range rel.Assets {
		if a.Name == name {
			return a, nil
		}
	}
	return Asset{}, fmt.Errorf("asset %q not found in release %s", name, rel.TagName)
}

// FindAssetPrefix returns the first asset whose name begins with prefix.
// Use this when the asset name embeds the release version (e.g. GoReleaser
// or gradle shadowJar outputs like "cloud-controller-1.0.0-all.jar") and
// the caller doesn't want to plumb the version through.
func FindAssetPrefix(rel Release, prefix string) (Asset, error) {
	for _, a := range rel.Assets {
		if strings.HasPrefix(a.Name, prefix) {
			return a, nil
		}
	}
	return Asset{}, fmt.Errorf("no asset with prefix %q in release %s", prefix, rel.TagName)
}

// DownloadAndVerifyAsset downloads asset from rel to destPath, then
// verifies it against the release's cosign-signed SHA256SUMS using
// identityRegex (typically CosignIdentityRegexJars for controller/daemon
// JARs). Use this for any artefact that will be executed locally.
//
// allowMissingCosign=true soft-warns when cosign isn't installed or the
// SHA256SUMS signature sidecars are absent — the SHA-256 chain still
// catches in-flight corruption. Production paths must pass false.
func DownloadAndVerifyAsset(rel Release, asset Asset, destPath, identityRegex string, allowMissingCosign bool) error {
	if err := DownloadFile(asset.BrowserDownloadURL, destPath); err != nil {
		return err
	}
	return VerifyReleaseAsset(destPath, asset.Name, rel, identityRegex, allowMissingCosign)
}

// DownloadFile downloads url to destPath, printing a simple percentage progress.
//
// SECURITY NOTE: this performs no signature verification. Use
// `DownloadAndVerify` for artefacts that will be executed locally; reserve
// `DownloadFile` for fixtures where SHA-256 (computed elsewhere) is enough.
func DownloadFile(url, destPath string) error {
	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return err
	}

	resp, err := http.Get(url) //nolint:noctx
	if err != nil {
		return fmt.Errorf("download failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download returned HTTP %d", resp.StatusCode)
	}

	f, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer f.Close()

	total := resp.ContentLength
	var written int64
	buf := make([]byte, 32*1024)

	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			written += int64(n)
			if total > 0 {
				pct := int(written * 100 / total)
				fmt.Printf("\r    %d%% (%s / %s)", pct, HumanBytes(written), HumanBytes(total))
				// Mirror the in-place progress bar to the live command sink so
				// the wizard terminal shows the same \r-updated line.
				sinkPrintf("\r    %d%% (%s / %s)", pct, HumanBytes(written), HumanBytes(total))
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
	}
	if total > 0 {
		fmt.Println() // newline after progress
		sinkPrintf("\n")
	}
	return nil
}

// DownloadTemurinJRE downloads the Eclipse Temurin Java 25 JRE for the current
// Linux architecture and extracts it to destDir (/opt/prexorcloud/jre).
func DownloadTemurinJRE(destDir string) error {
	arch := runtime.GOARCH
	switch arch {
	case "amd64":
		arch = "x64"
	case "arm64":
		arch = "aarch64"
	default:
		return fmt.Errorf("unsupported architecture: %s", runtime.GOARCH)
	}

	// Adoptium API redirects to the actual binary download.
	downloadURL := fmt.Sprintf(AdoptiumAPIv3, arch)

	tmpFile := filepath.Join(os.TempDir(), "temurin-jre-25.tar.gz")
	defer os.Remove(tmpFile)

	if err := DownloadFile(downloadURL, tmpFile); err != nil {
		return fmt.Errorf("failed to download Temurin JRE: %w", err)
	}

	if err := extractTarGz(tmpFile, destDir); err != nil {
		return fmt.Errorf("failed to extract Temurin JRE: %w", err)
	}

	return nil
}

// extractTarGz extracts a .tar.gz archive into destDir, stripping the top-level directory.
func extractTarGz(src, destDir string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	var topDir string

	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		// Determine the top-level directory name to strip it.
		parts := strings.SplitN(hdr.Name, "/", 2)
		if topDir == "" {
			topDir = parts[0]
		}

		// Strip the top-level directory from the path.
		rel := hdr.Name
		if strings.HasPrefix(rel, topDir+"/") {
			rel = rel[len(topDir)+1:]
		}
		if rel == "" {
			continue
		}

		target := filepath.Join(destDir, rel)

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(hdr.Mode)); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return err
			}
			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode))
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			out.Close()
		case tar.TypeSymlink:
			_ = os.Remove(target) // ignore error if not exists
			if err := os.Symlink(hdr.Linkname, target); err != nil {
				return err
			}
		}
	}
	return nil
}

// HumanBytes formats a byte count as a human-readable string.
func HumanBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}
