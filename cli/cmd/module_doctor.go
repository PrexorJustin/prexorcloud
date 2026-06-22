package cmd

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/manifest"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var moduleDoctorCmd = &cobra.Command{
	Use:   "doctor <jar>",
	Short: "Validate a built module jar against the platform-module contract",
	Long: `Reads META-INF/prexor/module.yaml from the supplied jar and runs
the same shape checks the controller performs on install — manifestVersion,
backend entrypoint class presence, capability semver, declared extension
artifacts (presence + sha256), plus a soft check for an adjacent signature
sidecar (.cosign.bundle or .sig).

Exit codes are CI-friendly:
  0   clean (no findings)
  1   warnings only
  2   errors (controller would reject)`,
	Args: cobra.ExactArgs(1),
	RunE: runModuleDoctor,
	// Validates a local jar offline; never contacts a controller.
	Annotations: map[string]string{"local-only": "true"},
}

var idPattern = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
var semverPattern = regexp.MustCompile(`^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$`)
var sha256Pattern = regexp.MustCompile(`^[a-fA-F0-9]{64}$`)

type doctorReport struct {
	errors   []string
	warnings []string
}

func (r *doctorReport) err(msg string)  { r.errors = append(r.errors, msg) }
func (r *doctorReport) warn(msg string) { r.warnings = append(r.warnings, msg) }

func runModuleDoctor(cmd *cobra.Command, args []string) error {
	path := args[0]
	stat, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("doctor: %w", err)
	}
	if stat.IsDir() {
		return fmt.Errorf("doctor: directory inputs not supported yet — pass a built .jar")
	}
	if !strings.HasSuffix(strings.ToLower(path), ".jar") {
		return fmt.Errorf("doctor: expected a .jar, got %s", path)
	}

	report := &doctorReport{}
	doctorValidateJar(path, report)

	for _, w := range report.warnings {
		theme.PrintWarn(w)
	}
	for _, e := range report.errors {
		theme.PrintError(e)
	}

	switch {
	case len(report.errors) > 0:
		// Hard validation failure → exit 2 (distinct from generic exit 1).
		// Individual errors were already printed above via theme.PrintError();
		// this returned error is the umbrella for the run.
		return &ExitCodeError{
			Code:    2,
			Message: fmt.Sprintf("doctor: %s has %d error(s)", filepath.Base(path), len(report.errors)),
		}
	case len(report.warnings) > 0:
		return &ExitCodeError{
			Code:    1,
			Message: fmt.Sprintf("doctor: %s has %d warning(s)", filepath.Base(path), len(report.warnings)),
		}
	default:
		theme.PrintSuccess(fmt.Sprintf("doctor: %s is clean.", filepath.Base(path)))
	}
	return nil
}

func doctorValidateJar(jarPath string, r *doctorReport) {
	zr, err := zip.OpenReader(jarPath)
	if err != nil {
		r.err(fmt.Sprintf("open jar: %v", err))
		return
	}
	defer zr.Close()

	jarEntries := make(map[string]*zip.File, len(zr.File))
	for _, f := range zr.File {
		jarEntries[f.Name] = f
	}

	m, err := manifest.ReadFromJar(jarPath)
	if err != nil {
		r.err(fmt.Sprintf("manifest: %v", err))
		return
	}
	if _, ok := jarEntries[manifest.JarManifestPath]; !ok {
		r.warn(fmt.Sprintf("manifest at root module.yaml; controller expects %s", manifest.JarManifestPath))
	}

	doctorCheckIdentity(m, r)
	doctorCheckBackend(m, jarEntries, r)
	doctorCheckCapabilities(m, r)
	doctorCheckExtensions(m, jarEntries, r)
	doctorCheckSignatureSidecar(jarPath, r)
}

func doctorCheckIdentity(m *manifest.Manifest, r *doctorReport) {
	if m.ManifestVersion < 1 || m.ManifestVersion > 2 {
		r.err(fmt.Sprintf("manifestVersion=%d (supported: 1..2)", m.ManifestVersion))
	}
	if !idPattern.MatchString(m.ID) {
		r.err(fmt.Sprintf("id %q must match [a-z][a-z0-9-]*", m.ID))
	}
	if !semverPattern.MatchString(m.Version) {
		r.err(fmt.Sprintf("version %q is not semver", m.Version))
	}
}

func doctorCheckBackend(m *manifest.Manifest, entries map[string]*zip.File, r *doctorReport) {
	if m.Backend.Entrypoint == "" {
		r.err("backend.entrypoint missing")
		return
	}
	classPath := strings.ReplaceAll(m.Backend.Entrypoint, ".", "/") + ".class"
	if _, ok := entries[classPath]; !ok {
		r.err(fmt.Sprintf("backend.entrypoint %s not present in jar (expected %s)", m.Backend.Entrypoint, classPath))
	}
}

func doctorCheckCapabilities(m *manifest.Manifest, r *doctorReport) {
	for _, p := range m.Capabilities.Provides {
		if !idPattern.MatchString(p.ID) {
			r.err(fmt.Sprintf("capabilities.provides id %q must match [a-z][a-z0-9-]*", p.ID))
		}
		if !semverPattern.MatchString(p.Version) {
			r.err(fmt.Sprintf("capabilities.provides[%s].version %q is not semver", p.ID, p.Version))
		}
		doctorCheckDeprecation(m, p, r)
	}
	for _, req := range m.Capabilities.Requires {
		if !idPattern.MatchString(req.ID) {
			r.err(fmt.Sprintf("capabilities.requires id %q must match [a-z][a-z0-9-]*", req.ID))
		}
		if strings.TrimSpace(req.VersionRange) == "" {
			r.err(fmt.Sprintf("capabilities.requires[%s] has empty versionRange", req.ID))
		}
	}
}

// doctorCheckDeprecation enforces shape of the manifestVersion-2 deprecation
// fields and surfaces a warning so authors know each deprecated provide will
// emit a controller-side warning every time a consumer resolves against it.
func doctorCheckDeprecation(m *manifest.Manifest, p manifest.ProvidedCapability, r *doctorReport) {
	hasDeprecated := p.DeprecatedSince != ""
	hasRemovedIn := p.RemovedIn != ""
	if !hasDeprecated && !hasRemovedIn {
		return
	}
	if m.ManifestVersion < 2 {
		r.err(fmt.Sprintf(
			"capabilities.provides[%s]: deprecatedSince/removedIn require manifestVersion >= 2 (have %d)",
			p.ID, m.ManifestVersion))
		return
	}
	if hasDeprecated && !semverPattern.MatchString(p.DeprecatedSince) {
		r.err(fmt.Sprintf("capabilities.provides[%s].deprecatedSince %q is not semver", p.ID, p.DeprecatedSince))
	}
	if hasRemovedIn && !semverPattern.MatchString(p.RemovedIn) {
		r.err(fmt.Sprintf("capabilities.provides[%s].removedIn %q is not semver", p.ID, p.RemovedIn))
	}
	if hasRemovedIn && !hasDeprecated {
		r.err(fmt.Sprintf("capabilities.provides[%s].removedIn requires deprecatedSince to be set", p.ID))
	}
	if hasDeprecated {
		msg := fmt.Sprintf("capabilities.provides[%s] is deprecated since %s", p.ID, p.DeprecatedSince)
		if hasRemovedIn {
			msg += fmt.Sprintf(" (removed in %s)", p.RemovedIn)
		}
		msg += " — consumers will see controller warnings until they migrate"
		r.warn(msg)
	}
}

func doctorCheckExtensions(m *manifest.Manifest, entries map[string]*zip.File, r *doctorReport) {
	seenExtIDs := map[string]bool{}
	for _, ext := range m.Extensions {
		if !idPattern.MatchString(ext.ID) {
			r.err(fmt.Sprintf("extension id %q must match [a-z][a-z0-9-]*", ext.ID))
		}
		if seenExtIDs[ext.ID] {
			r.err(fmt.Sprintf("duplicate extension id %q", ext.ID))
		}
		seenExtIDs[ext.ID] = true

		if len(ext.Variants) == 0 {
			r.err(fmt.Sprintf("extension %s has no variants", ext.ID))
			continue
		}
		seenVarIDs := map[string]bool{}
		for _, v := range ext.Variants {
			if seenVarIDs[v.ID] {
				r.err(fmt.Sprintf("extension %s declares duplicate variant id %q", ext.ID, v.ID))
			}
			seenVarIDs[v.ID] = true

			f, ok := entries[v.Artifact]
			if !ok {
				r.err(fmt.Sprintf("extension %s/%s: artifact %q not found in jar", ext.ID, v.ID, v.Artifact))
				continue
			}
			// AUTO is a build-time placeholder filled by the gradle plugin
			// before the jar is produced — finding it here means an
			// unprocessed source dump, not a release artifact.
			if v.SHA256 == "" || v.SHA256 == "AUTO" {
				r.warn(fmt.Sprintf("extension %s/%s: sha256 is %q (controller expects a 64-char hex digest)",
					ext.ID, v.ID, v.SHA256))
				continue
			}
			if !sha256Pattern.MatchString(v.SHA256) {
				r.err(fmt.Sprintf("extension %s/%s: sha256 %q is not a 64-char hex string", ext.ID, v.ID, v.SHA256))
				continue
			}
			actual, err := hashZipEntry(f)
			if err != nil {
				r.err(fmt.Sprintf("extension %s/%s: read %s: %v", ext.ID, v.ID, v.Artifact, err))
				continue
			}
			if !strings.EqualFold(actual, v.SHA256) {
				r.err(fmt.Sprintf("extension %s/%s: sha256 mismatch (manifest=%s, actual=%s)",
					ext.ID, v.ID, v.SHA256, actual))
			}
		}
	}
}

func doctorCheckSignatureSidecar(jarPath string, r *doctorReport) {
	for _, suffix := range []string{".cosign.bundle", ".sig"} {
		if _, err := os.Stat(jarPath + suffix); err == nil {
			return
		}
	}
	r.warn(fmt.Sprintf(
		"no signature sidecar found next to jar (looked for %s.cosign.bundle and %s.sig). "+
			"The controller rejects unsigned modules when modules.signing.required=true.",
		filepath.Base(jarPath), filepath.Base(jarPath)))
}

func hashZipEntry(f *zip.File) (string, error) {
	rc, err := f.Open()
	if err != nil {
		return "", err
	}
	defer rc.Close()
	h := sha256.New()
	if _, err := io.Copy(h, rc); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func init() {
	moduleCmd.AddCommand(moduleDoctorCmd)
}
