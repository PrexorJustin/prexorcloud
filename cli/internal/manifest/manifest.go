// Package manifest reads the module.yaml document that ships at the JAR root
// of every PrexorCloud platform module. It mirrors the strict YAML parser in
// PlatformModuleManifestParser.java but only models the fields prexorctl
// preflights need (id, version, capability provides/requires).
package manifest

import (
	"archive/zip"
	"fmt"
	"io"
	"os"

	"gopkg.in/yaml.v3"
)

// Manifest is the parsed view of module.yaml.
type Manifest struct {
	ManifestVersion int          `yaml:"manifestVersion"`
	ID              string       `yaml:"id"`
	Version         string       `yaml:"version"`
	Backend         Backend      `yaml:"backend"`
	Frontend        *Frontend    `yaml:"frontend,omitempty"`
	Capabilities    Capabilities `yaml:"capabilities"`
	Extensions      []Extension  `yaml:"extensions"`
}

type Backend struct {
	Entrypoint string `yaml:"entrypoint"`
}

type Frontend struct {
	SDKVersion int    `yaml:"sdkVersion"`
	Entry      string `yaml:"entry"`
}

type Capabilities struct {
	Provides []ProvidedCapability `yaml:"provides"`
	Requires []RequiredCapability `yaml:"requires"`
}

type ProvidedCapability struct {
	ID              string `yaml:"id"`
	Version         string `yaml:"version"`
	DeprecatedSince string `yaml:"deprecatedSince,omitempty"`
	RemovedIn       string `yaml:"removedIn,omitempty"`
}

type RequiredCapability struct {
	ID           string `yaml:"id"`
	VersionRange string `yaml:"versionRange"`
}

type Extension struct {
	ID         string             `yaml:"id"`
	Target     string             `yaml:"target"`
	Activation string             `yaml:"activation"`
	Variants   []ExtensionVariant `yaml:"variants"`
}

type ExtensionVariant struct {
	ID                string `yaml:"id"`
	MCVersionRange    string `yaml:"mcVersionRange"`
	RuntimeAPIVersion int    `yaml:"runtimeApiVersion"`
	Artifact          string `yaml:"artifact"`
	SHA256            string `yaml:"sha256"`
	InstallPath       string `yaml:"installPath"`
}

// JarManifestPath is the canonical location of module.yaml inside a built
// platform-module jar. Mirrors PlatformModuleManifestParser.FILE_NAME on the
// controller side.
const JarManifestPath = "META-INF/prexor/module.yaml"

// ReadFromJar opens a module jar and parses module.yaml from META-INF/prexor.
// As a fallback, also accepts a top-level module.yaml so the helper works on
// raw source-tree zips as well as built jars.
func ReadFromJar(jarPath string) (*Manifest, error) {
	zr, err := zip.OpenReader(jarPath)
	if err != nil {
		return nil, fmt.Errorf("open jar: %w", err)
	}
	defer zr.Close()

	var fallback *zip.File
	for _, f := range zr.File {
		if f.Name == JarManifestPath {
			return readZipEntry(f)
		}
		if f.Name == "module.yaml" {
			fallback = f
		}
	}
	if fallback != nil {
		return readZipEntry(fallback)
	}
	return nil, fmt.Errorf("no %s (or root module.yaml) in %s", JarManifestPath, jarPath)
}

func readZipEntry(f *zip.File) (*Manifest, error) {
	rc, err := f.Open()
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", f.Name, err)
	}
	defer rc.Close()
	data, err := io.ReadAll(rc)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", f.Name, err)
	}
	return parse(data)
}

// ReadFromFile parses a module.yaml file directly off disk.
func ReadFromFile(path string) (*Manifest, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	return parse(data)
}

func parse(data []byte) (*Manifest, error) {
	var m Manifest
	if err := yaml.Unmarshal(data, &m); err != nil {
		return nil, fmt.Errorf("decode module.yaml: %w", err)
	}
	if m.ID == "" {
		return nil, fmt.Errorf("module.yaml: missing 'id'")
	}
	if m.ManifestVersion < 1 || m.ManifestVersion > 2 {
		return nil, fmt.Errorf("module.yaml: unsupported manifestVersion %d (supported: 1..2)", m.ManifestVersion)
	}
	return &m, nil
}
