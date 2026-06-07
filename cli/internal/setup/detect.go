package setup

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

// Distro holds Linux distribution information for package manager selection.
type Distro struct {
	ID         string   // "ubuntu", "debian", "fedora", "arch", "manjaro", "alpine", ...
	Like       []string // values of ID_LIKE from /etc/os-release, lowercased
	Codename   string   // "jammy", "focal", "bookworm", "" (rolling/none)
	VersionID  string   // "22.04", "9", ""
	PackageMgr string   // "apt", "dnf", "yum", "pacman", "zypper", "apk"
	ServiceMgr string   // "systemd", "openrc", ""
}

// IsLike reports whether id matches Distro.ID or appears in Distro.Like.
// Used to recognize derivative distros (e.g. Linux Mint reports
// ID_LIKE=ubuntu, EndeavourOS reports ID_LIKE=arch).
func (d Distro) IsLike(id string) bool {
	id = strings.ToLower(id)
	if d.ID == id {
		return true
	}
	for _, l := range d.Like {
		if l == id {
			return true
		}
	}
	return false
}

// DetectDistro reads /etc/os-release and returns distro information.
// Returns an error if the file is missing or no supported package manager
// can be matched for the distro family.
func DetectDistro() (Distro, error) {
	if runtime.GOOS != "linux" {
		return Distro{}, fmt.Errorf("automatic dependency installation is only supported on Linux")
	}

	f, err := os.Open("/etc/os-release")
	if err != nil {
		return Distro{}, fmt.Errorf("cannot read /etc/os-release: %w", err)
	}
	defer f.Close()

	vals := make(map[string]string)
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if idx := strings.IndexByte(line, '='); idx >= 0 {
			key := line[:idx]
			val := strings.Trim(line[idx+1:], `"`)
			vals[key] = val
		}
	}

	// Prefer upstream codename for derivatives so apt repo lookups resolve to a
	// codename the upstream vendor publishes for.
	codename := strings.ToLower(vals["VERSION_CODENAME"])
	if v := strings.ToLower(vals["UBUNTU_CODENAME"]); v != "" {
		codename = v
	}
	if v := strings.ToLower(vals["DEBIAN_CODENAME"]); v != "" {
		codename = v
	}

	var like []string
	for _, l := range strings.Fields(strings.ToLower(vals["ID_LIKE"])) {
		like = append(like, l)
	}

	d := Distro{
		ID:        strings.ToLower(vals["ID"]),
		Like:      like,
		Codename:  codename,
		VersionID: vals["VERSION_ID"],
	}

	d.PackageMgr = detectPackageMgr(d)
	if d.PackageMgr == "" {
		return d, fmt.Errorf("unsupported distro %q — no recognized package manager (apt/dnf/yum/pacman/zypper/apk)", d.ID)
	}
	d.ServiceMgr = detectServiceMgr()

	return d, nil
}

// detectPackageMgr maps a distro family to its native package manager.
// For RHEL-family distros, prefers dnf when available, otherwise yum.
func detectPackageMgr(d Distro) string {
	switch {
	case d.IsLike("debian") || d.IsLike("ubuntu"):
		return "apt"
	case d.IsLike("rhel") || d.IsLike("centos") || d.IsLike("fedora"):
		if _, err := exec.LookPath("dnf"); err == nil {
			return "dnf"
		}
		return "yum"
	case d.IsLike("arch"):
		return "pacman"
	case d.IsLike("suse") || d.IsLike("opensuse"):
		return "zypper"
	case d.IsLike("alpine"):
		return "apk"
	}
	return ""
}

// detectServiceMgr returns "systemd", "openrc", or "" depending on which
// init/service manager is available on the host.
func detectServiceMgr() string {
	if _, err := exec.LookPath("systemctl"); err == nil {
		return "systemd"
	}
	if _, err := exec.LookPath("rc-service"); err == nil {
		return "openrc"
	}
	return ""
}

// DetectJava checks for a usable Java 25+ installation.
// It first checks the PrexorCloud-managed JRE, then falls back to PATH.
// Returns the version string (e.g. "25.0.1") or "" if not found.
func DetectJava(managedJREPath string) string {
	candidates := []string{
		filepath.Join(managedJREPath, "bin", "java"),
	}
	if p, err := exec.LookPath("java"); err == nil {
		candidates = append(candidates, p)
	}

	for _, candidate := range candidates {
		if v := javaVersion(candidate); v >= 25 {
			return javaVersionString(candidate)
		}
	}
	return ""
}

// DetectMongoDB checks whether mongod is installed and accessible.
// Returns the version string or "".
func DetectMongoDB() string {
	out, err := exec.Command("mongod", "--version").CombinedOutput()
	if err != nil {
		return ""
	}
	// "db version v8.0.4"
	re := regexp.MustCompile(`v(\d+\.\d+\.\d+)`)
	if m := re.FindSubmatch(out); m != nil {
		return string(m[1])
	}
	return "unknown"
}

// DetectRedis checks whether redis-server is installed and accessible.
// Returns the version string or "".
func DetectRedis() string {
	out, err := exec.Command("redis-server", "--version").CombinedOutput()
	if err != nil {
		return ""
	}
	// "Redis server v=7.2.4 ..."
	re := regexp.MustCompile(`v=(\d+\.\d+\.\d+)`)
	if m := re.FindSubmatch(out); m != nil {
		return string(m[1])
	}
	return "unknown"
}

// DetectDocker checks whether Docker is installed and returns the client version.
func DetectDocker() string {
	if out, err := exec.Command("docker", "version", "--format", "{{.Client.Version}}").CombinedOutput(); err == nil {
		if version := strings.TrimSpace(string(out)); version != "" {
			return version
		}
	}

	out, err := exec.Command("docker", "--version").CombinedOutput()
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`(\d+\.\d+\.\d+)`)
	if m := re.FindSubmatch(out); m != nil {
		return string(m[1])
	}
	return "unknown"
}

// DetectDockerCompose checks whether either the Docker Compose plugin or the
// legacy docker-compose binary is installed and returns its version.
func DetectDockerCompose() string {
	if out, err := exec.Command("docker", "compose", "version", "--short").CombinedOutput(); err == nil {
		if version := strings.TrimSpace(string(out)); version != "" {
			return version
		}
	}

	out, err := exec.Command("docker-compose", "version", "--short").CombinedOutput()
	if err != nil {
		return ""
	}
	if version := strings.TrimSpace(string(out)); version != "" {
		return version
	}
	return "unknown"
}

// javaVersion returns the major version of the java binary at the given path,
// or 0 if it cannot be determined.
func javaVersion(javaBin string) int {
	out, err := exec.Command(javaBin, "-version").CombinedOutput()
	if err != nil {
		return 0
	}
	// `java -version` outputs to stderr: 'openjdk version "25.0.1" ...'
	re := regexp.MustCompile(`version "(\d+)[\.\-]`)
	if m := re.FindSubmatch(out); m != nil {
		v, _ := strconv.Atoi(string(m[1]))
		return v
	}
	return 0
}

// javaVersionString returns the full version string for a java binary.
func javaVersionString(javaBin string) string {
	out, err := exec.Command(javaBin, "-version").CombinedOutput()
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`version "([^"]+)"`)
	if m := re.FindSubmatch(out); m != nil {
		return string(m[1])
	}
	return "unknown"
}
