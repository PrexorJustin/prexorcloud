package cmd

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTar(t *testing.T, gzipped bool, entries map[string][]byte) string {
	t.Helper()
	dir := t.TempDir()
	name := "bundle.tar"
	if gzipped {
		name = "bundle.tar.gz"
	}
	path := filepath.Join(dir, name)
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()

	var w io.Writer = f
	var gz *gzip.Writer
	if gzipped {
		gz = gzip.NewWriter(f)
		w = gz
	}
	tw := tar.NewWriter(w)
	for name, data := range entries {
		if err := tw.WriteHeader(&tar.Header{
			Name:     name,
			Mode:     0o644,
			Size:     int64(len(data)),
			Typeflag: tar.TypeReg,
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	tw.Close()
	if gz != nil {
		gz.Close()
	}
	return path
}

func TestResolveModuleInstallInputs_AutodetectCosign(t *testing.T) {
	dir := t.TempDir()
	jar := filepath.Join(dir, "mod.jar")
	sig := jar + ".cosign.bundle"
	os.WriteFile(jar, []byte("jar"), 0o644)
	os.WriteFile(sig, []byte("bundle"), 0o644)

	gotJar, gotSig, cleanup, err := resolveModuleInstallInputs(jar, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if gotJar != jar || gotSig != sig {
		t.Fatalf("got (%s, %s), want (%s, %s)", gotJar, gotSig, jar, sig)
	}
}

func TestResolveModuleInstallInputs_AutodetectSig(t *testing.T) {
	dir := t.TempDir()
	jar := filepath.Join(dir, "mod.jar")
	sig := jar + ".sig"
	os.WriteFile(jar, []byte("jar"), 0o644)
	os.WriteFile(sig, []byte("sig"), 0o644)

	gotJar, gotSig, cleanup, err := resolveModuleInstallInputs(jar, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if gotJar != jar || gotSig != sig {
		t.Fatalf("got (%s, %s), want (%s, %s)", gotJar, gotSig, jar, sig)
	}
}

func TestResolveModuleInstallInputs_NoSidecar(t *testing.T) {
	dir := t.TempDir()
	jar := filepath.Join(dir, "mod.jar")
	os.WriteFile(jar, []byte("jar"), 0o644)

	_, gotSig, cleanup, err := resolveModuleInstallInputs(jar, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if gotSig != "" {
		t.Fatalf("expected empty sig, got %q", gotSig)
	}
}

func TestResolveModuleInstallInputs_ExplicitSignatureOverridesAutodetect(t *testing.T) {
	dir := t.TempDir()
	jar := filepath.Join(dir, "mod.jar")
	autoSig := jar + ".sig"
	overrideSig := filepath.Join(dir, "other.cosign.bundle")
	os.WriteFile(jar, []byte("jar"), 0o644)
	os.WriteFile(autoSig, []byte("auto"), 0o644)
	os.WriteFile(overrideSig, []byte("override"), 0o644)

	_, gotSig, cleanup, err := resolveModuleInstallInputs(jar, overrideSig)
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if gotSig != overrideSig {
		t.Fatalf("got %q, want %q", gotSig, overrideSig)
	}
}

func TestResolveModuleInstallInputs_TarBundle(t *testing.T) {
	path := writeTar(t, false, map[string][]byte{
		"mod.jar":               []byte("jar bytes"),
		"mod.jar.cosign.bundle": []byte("bundle bytes"),
	})

	jar, sig, cleanup, err := resolveModuleInstallInputs(path, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(jar) != "mod.jar" {
		t.Errorf("jar = %s", jar)
	}
	if filepath.Base(sig) != "mod.jar.cosign.bundle" {
		t.Errorf("sig = %s", sig)
	}
	jarBytes, _ := os.ReadFile(jar)
	if string(jarBytes) != "jar bytes" {
		t.Errorf("jar contents = %q", jarBytes)
	}
}

func TestResolveModuleInstallInputs_TarGzBundle(t *testing.T) {
	path := writeTar(t, true, map[string][]byte{
		"mod.jar":     []byte("jar"),
		"mod.jar.sig": []byte("sig"),
	})
	_, sig, cleanup, err := resolveModuleInstallInputs(path, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(sig) != "mod.jar.sig" {
		t.Errorf("sig = %s", sig)
	}
}

func TestResolveModuleInstallInputs_TarRejectsTraversal(t *testing.T) {
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	tw.WriteHeader(&tar.Header{Name: "../escape.jar", Mode: 0o644, Size: 3, Typeflag: tar.TypeReg})
	tw.Write([]byte("jar"))
	tw.Close()

	dir := t.TempDir()
	path := filepath.Join(dir, "evil.tar")
	os.WriteFile(path, buf.Bytes(), 0o644)

	_, _, cleanup, err := resolveModuleInstallInputs(path, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "unsafe entry") {
		t.Errorf("error = %v", err)
	}
}

func TestResolveModuleInstallInputs_TarRejectsMultipleJars(t *testing.T) {
	path := writeTar(t, false, map[string][]byte{
		"a.jar": []byte("a"),
		"b.jar": []byte("b"),
	})
	_, _, cleanup, err := resolveModuleInstallInputs(path, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err == nil || !strings.Contains(err.Error(), "more than one .jar") {
		t.Fatalf("error = %v", err)
	}
}

func TestResolveModuleInstallInputs_TarRequiresJar(t *testing.T) {
	path := writeTar(t, false, map[string][]byte{
		"stuff.txt": []byte("nope"),
	})
	_, _, cleanup, err := resolveModuleInstallInputs(path, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err == nil || !strings.Contains(err.Error(), "no .jar entry") {
		t.Fatalf("error = %v", err)
	}
}

func TestResolveModuleInstallInputs_RejectsNonJarNonTar(t *testing.T) {
	dir := t.TempDir()
	bad := filepath.Join(dir, "notajar.txt")
	os.WriteFile(bad, []byte("hi"), 0o644)
	_, _, cleanup, err := resolveModuleInstallInputs(bad, "")
	if cleanup != nil {
		defer cleanup()
	}
	if err == nil {
		t.Fatal("expected error")
	}
}
