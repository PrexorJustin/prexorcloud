package setup

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
)

const (
	ManagedJREPath = "/opt/prexorcloud/jre"
	mongodbVersion = "8.0"
)

// ErrNoNativeMongoDB is returned by InstallMongoDB on distros where MongoDB
// does not publish a native package (Arch family, openSUSE, Alpine, Fedora,
// Amazon Linux). Callers should surface a remote-MongoDB or compose-mode path
// instead of treating this as a hard failure.
var ErrNoNativeMongoDB = errors.New("no native MongoDB package available for this distro")

// InstallJRE downloads and installs the Eclipse Temurin Java 25 JRE
// to the managed JRE path (/opt/prexorcloud/jre).
func InstallJRE() error {
	if err := os.MkdirAll(ManagedJREPath, 0755); err != nil {
		return fmt.Errorf("failed to create JRE directory: %w", err)
	}
	return DownloadTemurinJRE(ManagedJREPath)
}

// MongoDBNative reports whether a native MongoDB installer is available for d.
// MongoDB only publishes packages for Debian/Ubuntu (apt) and the RHEL family
// (dnf/yum). Fedora, Arch, openSUSE, Alpine, and Amazon Linux are excluded.
func MongoDBNative(d Distro) bool {
	if d.ID == "fedora" || d.ID == "amzn" {
		return false
	}
	switch d.PackageMgr {
	case "apt":
		return true
	case "dnf", "yum":
		return d.IsLike("rhel") || d.IsLike("centos")
	}
	return false
}

// InstallMongoDB installs MongoDB using the official repository for the given
// distro. Returns ErrNoNativeMongoDB when no native package is available.
func InstallMongoDB(d Distro) error {
	if !MongoDBNative(d) {
		return ErrNoNativeMongoDB
	}
	switch d.PackageMgr {
	case "apt":
		return installMongoDBAPT(d)
	case "dnf", "yum":
		return installMongoDBDNF(d)
	}
	return ErrNoNativeMongoDB
}

// InstallRedis installs Redis using the system package manager. Native Redis
// packages are available on every supported distro family.
func InstallRedis(d Distro) error {
	switch d.PackageMgr {
	case "apt":
		return installRedisAPT()
	case "dnf", "yum":
		return installRedisDNF(d.PackageMgr)
	case "pacman":
		return installRedisPacman()
	case "zypper":
		return installRedisZypper()
	case "apk":
		return installRedisAPK()
	}
	return fmt.Errorf("unsupported package manager: %s", d.PackageMgr)
}

// enableAndStart enables and starts a service on the host's init system. It
// supports systemd (systemctl) and OpenRC (rc-service / rc-update). The
// service argument is a system-wide service name; callers must pass the
// correct name for the host's init system (e.g. systemd uses "redis-server"
// on Debian, while OpenRC on Alpine uses "redis").
func enableAndStart(service string) error {
	switch detectServiceMgr() {
	case "systemd":
		if out, err := runCmd(exec.Command("systemctl", "enable", service)); err != nil {
			return fmt.Errorf("systemctl enable %s: %w\n%s", service, err, out)
		}
		if out, err := runCmd(exec.Command("systemctl", "start", service)); err != nil {
			return fmt.Errorf("systemctl start %s: %w\n%s", service, err, out)
		}
		return nil
	case "openrc":
		if out, err := runCmd(exec.Command("rc-update", "add", service, "default")); err != nil {
			return fmt.Errorf("rc-update add %s: %w\n%s", service, err, out)
		}
		if out, err := runCmd(exec.Command("rc-service", service, "start")); err != nil {
			return fmt.Errorf("rc-service %s start: %w\n%s", service, err, out)
		}
		return nil
	}
	return fmt.Errorf("no supported service manager (systemd/openrc) found on PATH")
}

// CreateControllerDirs creates the required directory structure under installDir.
func CreateControllerDirs(installDir string) error {
	dirs := []string{
		installDir,
		filepath.Join(installDir, "config"),
		filepath.Join(installDir, "config", "security"),
		filepath.Join(installDir, "modules"),
		filepath.Join(installDir, "modules", "data"),
		filepath.Join(installDir, "dashboard"),
		filepath.Join(installDir, "templates"),
	}
	for _, d := range dirs {
		if err := os.MkdirAll(d, 0755); err != nil {
			return err
		}
	}
	return nil
}

// CreateDaemonDirs creates the required directory structure under installDir.
func CreateDaemonDirs(installDir string) error {
	dirs := []string{
		installDir,
		filepath.Join(installDir, "config"),
		filepath.Join(installDir, "config", "security"),
		filepath.Join(installDir, "instances"),
	}
	for _, d := range dirs {
		if err := os.MkdirAll(d, 0755); err != nil {
			return err
		}
	}
	return nil
}

// newBytesReader wraps a byte slice as an io.Reader (avoids importing bytes in this file).
func newBytesReader(b []byte) *bytesReader {
	return &bytesReader{data: b}
}

type bytesReader struct {
	data []byte
	pos  int
}

func (r *bytesReader) Read(p []byte) (int, error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	n := copy(p, r.data[r.pos:])
	r.pos += n
	return n, nil
}
