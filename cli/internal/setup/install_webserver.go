package setup

import (
	"fmt"
	"os"
	"os/exec"
)

// WebServer identifies the host web server used to serve the dashboard bundle
// in a native (non-compose) install.
type WebServer string

const (
	// WebServerNginx serves the static bundle over plain HTTP and assumes TLS
	// is terminated upstream (load balancer / reverse proxy).
	WebServerNginx WebServer = "nginx"
	// WebServerCaddy serves the static bundle and auto-provisions a Let's
	// Encrypt certificate when the public URL is https:// (Caddy's default).
	WebServerCaddy WebServer = "caddy"
)

// nginxServiceName / caddyServiceName are the system-wide unit names the
// distro packages register. Both families use these names under systemd; the
// OpenRC names happen to match too.
const (
	nginxServiceName = "nginx"
	caddyServiceName = "caddy"
)

// InstallNginx installs nginx from the distro's native package repository.
// nginx ships in the base repos of every supported family, so no third-party
// repo wiring is needed (unlike MongoDB or Caddy on Debian/Ubuntu).
func InstallNginx(d Distro) error {
	if DetectBinary("nginx") != "" {
		return enableAndStart(nginxServiceName)
	}
	if err := installDistroPackage(d, "nginx"); err != nil {
		return err
	}
	return enableAndStart(nginxServiceName)
}

// InstallCaddy installs Caddy. Arch, Fedora, openSUSE, and Alpine ship Caddy
// in their native repos; Debian/Ubuntu need the official Cloudsmith apt repo
// (mirrors the MongoDB apt idiom in install_apt.go).
func InstallCaddy(d Distro) error {
	if DetectBinary("caddy") != "" {
		return enableAndStart(caddyServiceName)
	}
	switch d.PackageMgr {
	case "apt":
		if err := installCaddyAPT(); err != nil {
			return err
		}
	default:
		if err := installDistroPackage(d, "caddy"); err != nil {
			return err
		}
	}
	return enableAndStart(caddyServiceName)
}

// installCaddyAPT wires the official Caddy stable apt repository (hosted on
// Cloudsmith) and installs the caddy package. Same shape as installMongoDBAPT:
// fetch the signing key, write a signed-by source entry, update, install.
func installCaddyAPT() error {
	keyring := "/usr/share/keyrings/caddy-stable-archive-keyring.gpg"

	curlOut, err := exec.Command("curl", "-1fsSL",
		"https://dl.cloudsmith.io/public/caddy/stable/gpg.key").Output()
	if err != nil {
		return fmt.Errorf("failed to download Caddy GPG key: %w", err)
	}
	gpgCmd := exec.Command("gpg", "--batch", "--yes", "--dearmor", "--output", keyring)
	gpgCmd.Stdin = newBytesReader(curlOut)
	if out, err := runCmd(gpgCmd); err != nil {
		return fmt.Errorf("failed to import Caddy GPG key: %w\n%s", err, out)
	}

	listOut, err := exec.Command("curl", "-1fsSL",
		"https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt").Output()
	if err != nil {
		return fmt.Errorf("failed to download Caddy apt source list: %w", err)
	}
	if err := os.WriteFile("/etc/apt/sources.list.d/caddy-stable.list", listOut, 0644); err != nil {
		return fmt.Errorf("failed to write Caddy source list: %w", err)
	}

	if out, err := runCmd(aptGet("update", "-qq")); err != nil {
		return fmt.Errorf("apt-get update failed: %w\n%s", err, out)
	}
	if out, err := runCmd(aptGet("install", "-y", "caddy")); err != nil {
		return fmt.Errorf("apt-get install caddy failed: %w\n%s", err, out)
	}
	return nil
}

// installDistroPackage installs a single package using the host's package
// manager, non-interactively. Used for packages that exist in every family's
// base repo under the same name (nginx; caddy on non-apt distros).
func installDistroPackage(d Distro, pkg string) error {
	var cmd *exec.Cmd
	switch d.PackageMgr {
	case "apt":
		if out, err := runCmd(aptGet("update", "-qq")); err != nil {
			return fmt.Errorf("apt-get update failed: %w\n%s", err, out)
		}
		cmd = aptGet("install", "-y", pkg)
	case "dnf":
		cmd = exec.Command("dnf", "install", "-y", pkg)
	case "yum":
		cmd = exec.Command("yum", "install", "-y", pkg)
	case "pacman":
		cmd = exec.Command("pacman", "-S", "--noconfirm", pkg)
	case "zypper":
		cmd = exec.Command("zypper", "--non-interactive", "install", pkg)
	case "apk":
		cmd = exec.Command("apk", "add", "--no-cache", pkg)
	default:
		return fmt.Errorf("unsupported package manager: %s", d.PackageMgr)
	}
	if out, err := runCmd(cmd); err != nil {
		return fmt.Errorf("install %s failed: %w\n%s", pkg, err, out)
	}
	return nil
}

// reloadOrRestart applies a new config for an already-running service: it
// tries a graceful reload first (nginx/Caddy both support it) and falls back
// to a restart when reload isn't available. Supports systemd and OpenRC.
func reloadOrRestart(service string) error {
	switch detectServiceMgr() {
	case "systemd":
		if err := exec.Command("systemctl", "reload", service).Run(); err == nil {
			return nil
		}
		if out, err := runCmd(exec.Command("systemctl", "restart", service)); err != nil {
			return fmt.Errorf("systemctl restart %s: %w\n%s", service, err, out)
		}
		return nil
	case "openrc":
		if err := exec.Command("rc-service", service, "reload").Run(); err == nil {
			return nil
		}
		if out, err := runCmd(exec.Command("rc-service", service, "restart")); err != nil {
			return fmt.Errorf("rc-service %s restart: %w\n%s", service, err, out)
		}
		return nil
	}
	return fmt.Errorf("no supported service manager (systemd/openrc) found on PATH")
}

// DetectBinary returns the resolved path of name on PATH, or "" if absent.
// Thin exported wrapper over exec.LookPath so the wizard can report which
// dependencies are already present without each caller importing os/exec.
func DetectBinary(name string) string {
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}
