package scaffold

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// archiveNamePattern matches `archiveName.set("foo")` in a module's
// build.gradle.kts. The convention plugin requires every module to set this,
// and the resulting jar lands at `build/libs/<archiveName>.jar`.
var archiveNamePattern = regexp.MustCompile(`archiveName\.set\(\s*"([^"]+)"\s*\)`)

// Module describes a resolved cloud-module checkout on disk.
type Module struct {
	Name             string // kebab name (no prefix)
	Dir              string // absolute path to java/cloud-modules/<name>
	ArchiveName      string // value of archiveName.set(...)
	JarPath          string // absolute path to build/libs/<archiveName>.jar
	GradleTask       string // :cloud-modules:<name>:<task> root
	FrontendDistPath string // absolute path to frontend/dist (empty when module has no frontend)
}

// LocateModule resolves a module by name within a repo checkout. Name may
// either be the bare kebab form ("example") or the legacy directory form
// ("cloud-module-example") that pre-dates the Track B repo hygiene pass;
// both round-trip to the same module under java/cloud-modules/<name>/.
func LocateModule(repoRoot, name string) (*Module, error) {
	if repoRoot == "" {
		return nil, fmt.Errorf("repoRoot must be set")
	}
	kebab := strings.TrimPrefix(name, templateModuleLegacyPrefix)
	if !kebabPattern.MatchString(kebab) {
		return nil, fmt.Errorf("invalid module name %q — use kebab-case", name)
	}

	dir := filepath.Join(repoRoot, "java", "cloud-modules", kebab)
	build := filepath.Join(dir, "build.gradle.kts")
	raw, err := os.ReadFile(build)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w (is the module name correct?)", build, err)
	}
	m := archiveNamePattern.FindStringSubmatch(string(raw))
	if len(m) != 2 {
		return nil, fmt.Errorf("could not find archiveName.set(\"...\") in %s", build)
	}
	archive := m[1]

	// The convention plugin gates frontend wiring on frontend/package.json
	// presence; mirror that here so callers can branch on FrontendDistPath != "".
	frontendDist := ""
	if _, err := os.Stat(filepath.Join(dir, "frontend", "package.json")); err == nil {
		frontendDist = filepath.Join(dir, "frontend", "dist")
	}

	return &Module{
		Name:             kebab,
		Dir:              dir,
		ArchiveName:      archive,
		JarPath:          filepath.Join(dir, "build", "libs", archive+".jar"),
		GradleTask:       ":cloud-modules:" + kebab,
		FrontendDistPath: frontendDist,
	}, nil
}
