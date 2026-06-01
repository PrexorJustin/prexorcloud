package cmd

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/manifest"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var (
	moduleInstallSignaturePath string
	moduleInstallCheckRequires bool
	moduleInstallRegistry      string
)

var moduleInstallCmd = &cobra.Command{
	Use:   "install <jar|bundle.tar | id[@version]>",
	Short: "Install a signed module — from a local bundle or a configured registry",
	Long: `Install a platform module.

Two sources, auto-detected from the argument:

  • Local bundle — a .jar (sidecar auto-detected as <jar>.cosign.bundle or
    <jar>.sig, or via --signature) or a .tar/.tar.gz/.tgz containing one jar
    and one sidecar. The signature is uploaded so the controller can verify
    it before installing.

  • Registry — a module spec like "stats-aggregator" or
    "stats-aggregator@1.2.0". The controller pulls the signed jar from a
    configured registry (modules.registries), checks its sha256 against the
    index, and verifies the signature against its own trust root. Omit the
    version (or use @latest) for the newest. Use --registry to pin one of the
    configured registries.`,
	Args: cobra.ExactArgs(1),
	RunE: runModuleInstall,
}

func runModuleInstall(cmd *cobra.Command, args []string) error {
	client, err := requireAuth()
	if err != nil {
		return err
	}

	source := args[0]
	if !isLocalModuleSource(source) {
		return runRegistryInstall(client, source)
	}

	jarPath, sigPath, cleanup, err := resolveModuleInstallInputs(source, moduleInstallSignaturePath)
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		return err
	}

	if moduleInstallCheckRequires {
		if err := preflightCheckRequires(client, jarPath); err != nil {
			return err
		}
	}

	var result map[string]any
	if err := client.UploadWithSignature(
		"/api/v1/modules/platform/upload", jarPath, sigPath, &result,
	); err != nil {
		return err
	}

	if flagJSON {
		return theme.PrintJSON(result)
	}

	moduleId := str(result, "moduleId")
	if sigPath != "" {
		theme.PrintSuccess(fmt.Sprintf("Module %q installed (signature: %s)", moduleId, filepath.Base(sigPath)))
	} else {
		theme.PrintSuccess(fmt.Sprintf("Module %q installed (no signature attached)", moduleId))
	}
	return nil
}

// resolveModuleInstallInputs returns the jar path and (optional) signature path
// to upload. If the source is a tar bundle, the contents are extracted into a
// temp directory whose lifetime is bound to the returned cleanup func.
func resolveModuleInstallInputs(source, explicitSig string) (string, string, func(), error) {
	if isTarBundle(source) {
		jar, sig, cleanup, err := extractTarBundle(source)
		if err != nil {
			return "", "", cleanup, err
		}
		if explicitSig != "" {
			return jar, explicitSig, cleanup, nil
		}
		return jar, sig, cleanup, nil
	}

	if !strings.HasSuffix(source, ".jar") {
		return "", "", nil, fmt.Errorf("expected a .jar or .tar/.tar.gz/.tgz bundle, got %s", source)
	}
	if _, err := os.Stat(source); err != nil {
		return "", "", nil, fmt.Errorf("jar not readable: %w", err)
	}

	if explicitSig != "" {
		if _, err := os.Stat(explicitSig); err != nil {
			return "", "", nil, fmt.Errorf("signature not readable: %w", err)
		}
		return source, explicitSig, nil, nil
	}

	// Auto-detect: prefer cosign bundle, fall back to .sig.
	for _, suffix := range []string{".cosign.bundle", ".sig"} {
		candidate := source + suffix
		if _, err := os.Stat(candidate); err == nil {
			return source, candidate, nil, nil
		}
	}
	return source, "", nil, nil
}

func isTarBundle(path string) bool {
	lower := strings.ToLower(path)
	return strings.HasSuffix(lower, ".tar") ||
		strings.HasSuffix(lower, ".tar.gz") ||
		strings.HasSuffix(lower, ".tgz")
}

// isLocalModuleSource decides whether the argument names a file on disk (or a
// jar/tar bundle) versus a registry module spec. A real file always wins; an
// "id" or "id@version" string that isn't a path is treated as a registry spec.
func isLocalModuleSource(source string) bool {
	if isTarBundle(source) || strings.HasSuffix(strings.ToLower(source), ".jar") {
		return true
	}
	if _, err := os.Stat(source); err == nil {
		return true
	}
	return false
}

// runRegistryInstall asks the controller to pull "id" or "id@version" from a
// configured registry. The controller does the sha256 + signature verification;
// the CLI just relays the request and prints the result.
func runRegistryInstall(client *api.Client, spec string) error {
	moduleID, version := spec, ""
	if at := strings.IndexByte(spec, '@'); at >= 0 {
		moduleID, version = spec[:at], spec[at+1:]
	}
	if moduleID == "" {
		return fmt.Errorf("invalid module spec %q (expected id or id@version)", spec)
	}

	body := map[string]any{"moduleId": moduleID}
	if version != "" {
		body["version"] = version
	}
	if moduleInstallRegistry != "" {
		body["registryUrl"] = moduleInstallRegistry
	}

	var result map[string]any
	if err := client.Post("/api/v1/modules/platform/registry/install", body, &result); err != nil {
		return err
	}

	if flagJSON {
		return theme.PrintJSON(result)
	}
	installed := str(result, "moduleId")
	installedVersion := str(result, "version")
	theme.PrintSuccess(fmt.Sprintf("Module %q installed from registry (version %s)", installed, installedVersion))
	return nil
}

// extractTarBundle reads source and writes the jar + sidecar entries to a temp
// dir. Rejects bundles that don't contain exactly one jar and at most one
// sidecar. Path-traversal entries are rejected.
func extractTarBundle(source string) (string, string, func(), error) {
	f, err := os.Open(source)
	if err != nil {
		return "", "", nil, fmt.Errorf("open bundle: %w", err)
	}
	defer f.Close()

	var reader io.Reader = f
	lower := strings.ToLower(source)
	if strings.HasSuffix(lower, ".gz") || strings.HasSuffix(lower, ".tgz") {
		gz, err := gzip.NewReader(f)
		if err != nil {
			return "", "", nil, fmt.Errorf("gunzip bundle: %w", err)
		}
		defer gz.Close()
		reader = gz
	}

	dir, err := os.MkdirTemp("", "prexorctl-module-bundle-")
	if err != nil {
		return "", "", nil, err
	}
	cleanup := func() { os.RemoveAll(dir) }

	tr := tar.NewReader(reader)
	var jarPath, sigPath string
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return "", "", cleanup, fmt.Errorf("read bundle: %w", err)
		}
		if hdr.Typeflag != tar.TypeReg {
			continue
		}
		base := filepath.Base(hdr.Name)
		// Reject anything that would escape the temp dir.
		if base != hdr.Name && !isFlatNested(hdr.Name) {
			return "", "", cleanup, fmt.Errorf("unsafe entry in bundle: %s", hdr.Name)
		}

		dest := filepath.Join(dir, base)
		out, err := os.Create(dest)
		if err != nil {
			return "", "", cleanup, fmt.Errorf("write bundle entry: %w", err)
		}
		if _, err := io.Copy(out, tr); err != nil {
			out.Close()
			return "", "", cleanup, fmt.Errorf("copy bundle entry: %w", err)
		}
		out.Close()

		switch {
		case strings.HasSuffix(base, ".jar"):
			if jarPath != "" {
				return "", "", cleanup, fmt.Errorf("bundle contains more than one .jar (%s and %s)", filepath.Base(jarPath), base)
			}
			jarPath = dest
		case strings.HasSuffix(base, ".cosign.bundle"), strings.HasSuffix(base, ".sig"):
			if sigPath != "" {
				return "", "", cleanup, fmt.Errorf("bundle contains more than one signature sidecar")
			}
			sigPath = dest
		}
	}
	if jarPath == "" {
		return "", "", cleanup, fmt.Errorf("bundle contains no .jar entry")
	}
	return jarPath, sigPath, cleanup, nil
}

// isFlatNested allows entries like "./foo.jar" but not "../foo.jar" or
// "/etc/foo.jar". We accept a single leading "./" segment for tar archives
// that prefix entries with the working directory.
func isFlatNested(name string) bool {
	if filepath.IsAbs(name) {
		return false
	}
	clean := filepath.Clean(name)
	if strings.Contains(clean, "..") {
		return false
	}
	return !strings.ContainsRune(clean, os.PathSeparator)
}

// preflightCheckRequires reads the local jar's module.yaml, fetches the
// controller's capability inventory, and prints one warning per required
// capability that no installed module currently provides. Non-fatal — the
// install proceeds either way; the goal is operator visibility before the
// controller's own validation rejects the upload.
func preflightCheckRequires(client *api.Client, jarPath string) error {
	m, err := manifest.ReadFromJar(jarPath)
	if err != nil {
		return fmt.Errorf("--check-requires: %w", err)
	}
	if len(m.Capabilities.Requires) == 0 {
		theme.PrintSuccess(fmt.Sprintf("Preflight: %s declares no required capabilities.", m.ID))
		return nil
	}

	var inventory capabilitiesInventory
	if err := client.Get("/api/v1/modules/platform/capabilities", &inventory); err != nil {
		return fmt.Errorf("--check-requires: fetch capabilities: %w", err)
	}

	type providerHit struct {
		moduleID string
		version  string
	}
	providers := make(map[string][]providerHit)
	for _, mod := range inventory.Modules {
		for _, p := range mod.Provides {
			if !p.Active {
				continue
			}
			providers[p.ID] = append(providers[p.ID], providerHit{moduleID: mod.ModuleID, version: p.Version})
		}
	}

	missing := 0
	for _, req := range m.Capabilities.Requires {
		hits := providers[req.ID]
		if len(hits) == 0 {
			missing++
			theme.PrintWarn(fmt.Sprintf(
				"Required capability %q (range %s) — no provider known on this controller.",
				req.ID, req.VersionRange))
			continue
		}
		// One or more active providers — print the list. Range satisfaction
		// is enforced by the controller on install; the CLI only surfaces
		// what's available so the operator can spot obvious mismatches.
		for _, h := range hits {
			theme.PrintSuccess(fmt.Sprintf(
				"Required capability %q (range %s) — provided by %s v%s.",
				req.ID, req.VersionRange, h.moduleID, h.version))
		}
	}
	if missing > 0 {
		theme.PrintWarn(fmt.Sprintf(
			"%d required capability(ies) have no known provider — install may fail.",
			missing))
	}
	return nil
}

// capabilitiesInventory mirrors the relevant subset of the controller's
// /api/v1/modules/platform/capabilities response.
type capabilitiesInventory struct {
	Modules []capabilityModule `json:"modules"`
}

type capabilityModule struct {
	ModuleID string                 `json:"moduleId"`
	Provides []capabilityProvideDTO `json:"provides"`
}

type capabilityProvideDTO struct {
	ID      string `json:"id"`
	Version string `json:"version"`
	Active  bool   `json:"active"`
}

func init() {
	moduleInstallCmd.Flags().StringVar(&moduleInstallSignaturePath, "signature", "",
		"Explicit path to the signature sidecar (.sig or .cosign.bundle). "+
			"Defaults to autodetection of <jar>.cosign.bundle or <jar>.sig.")
	moduleInstallCmd.Flags().BoolVar(&moduleInstallCheckRequires, "check-requires", false,
		"Before uploading, read module.yaml from the jar and warn for any "+
			"required capability that no installed controller module provides.")
	moduleInstallCmd.Flags().StringVar(&moduleInstallRegistry, "registry", "",
		"For a registry install (id[@version]), pin one of the configured "+
			"registry URLs instead of searching all of them.")
	moduleCmd.AddCommand(moduleInstallCmd)
}
