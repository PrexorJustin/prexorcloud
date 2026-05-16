package setup

import (
	"fmt"
	"os"
	"os/exec"
)

// aptGet builds an `apt-get` exec.Cmd preconfigured for fully-unattended
// installs from inside the browser wizard. Three classes of interactive
// surface get suppressed:
//
//   - **debconf prompts** ("Configuring package X — which option do you
//     want?"). DEBIAN_FRONTEND=noninteractive picks the package's default.
//   - **needrestart's purple debconf dialog** ("Pending kernel upgrade —
//     restart now?"). NEEDRESTART_MODE=a applies the auto-restart policy
//     silently; NEEDRESTART_SUSPEND=1 belt-and-braces for older needrestart
//     versions on Ubuntu 22.04 that don't honour MODE=a in every code path.
//   - **dpkg's config-file conflict prompt** ("modified locally — replace?").
//     The Dpkg::Options pair tells dpkg to keep the operator's existing
//     file when there's a conflict (--force-confold) and accept the
//     package's default when there's no local modification (--force-confdef).
//
// Without these the wizard's xterm shows a half-rendered purple whiptail
// dialog the operator can't dismiss (no keyboard wired through the SSE
// stream) and the install hangs forever. Verified against the screenshot
// from the v1.0 install host where MongoDB pulled in a kernel-related
// transitive that woke needrestart up.
//
// Use this anywhere the wizard would otherwise call exec.Command("apt-get", …).
func aptGet(args ...string) *exec.Cmd {
	base := []string{
		"-o", "Dpkg::Options::=--force-confdef",
		"-o", "Dpkg::Options::=--force-confold",
	}
	cmd := exec.Command("apt-get", append(base, args...)...)
	cmd.Env = append(os.Environ(),
		"DEBIAN_FRONTEND=noninteractive",
		"NEEDRESTART_MODE=a",
		"NEEDRESTART_SUSPEND=1",
		"APT_LISTCHANGES_FRONTEND=none",
		"UCF_FORCE_CONFOLD=1",
	)
	return cmd
}

// installMongoDBAPT installs MongoDB on Debian/Ubuntu and their derivatives
// (Linux Mint, Pop!_OS, elementary, Kali, Raspbian, etc.) using the upstream
// MongoDB apt repository. Derivatives are remapped to the upstream vendor
// (ubuntu/debian) and codename so the repo URL resolves to a real archive.
func installMongoDBAPT(d Distro) error {
	keyring := fmt.Sprintf("/usr/share/keyrings/mongodb-server-%s.gpg", mongodbVersion)

	curlOut, err := exec.Command(
		"curl", "-fsSL",
		fmt.Sprintf("https://www.mongodb.org/static/pgp/server-%s.asc", mongodbVersion),
	).Output()
	if err != nil {
		return fmt.Errorf("failed to download MongoDB GPG key: %w", err)
	}

	gpgCmd := exec.Command("gpg", "--batch", "--yes", "--dearmor", "--output", keyring)
	gpgCmd.Stdin = newBytesReader(curlOut)
	if out, err := runCmd(gpgCmd); err != nil {
		return fmt.Errorf("failed to import MongoDB GPG key: %w\n%s", err, out)
	}

	vendor := mongoDBAptVendor(d)
	codename, err := mongoDBCodename(d, vendor)
	if err != nil {
		return err
	}

	component := "multiverse"
	if vendor == "debian" {
		component = "main"
	}

	sourceEntry := fmt.Sprintf(
		"deb [ arch=amd64,arm64 signed-by=%s ] https://repo.mongodb.org/apt/%s %s/mongodb-org/%s %s\n",
		keyring, vendor, codename, mongodbVersion, component,
	)
	sourceFile := fmt.Sprintf("/etc/apt/sources.list.d/mongodb-org-%s.list", mongodbVersion)
	if err := os.WriteFile(sourceFile, []byte(sourceEntry), 0644); err != nil {
		return fmt.Errorf("failed to write MongoDB source list: %w", err)
	}

	if out, err := runCmd(aptGet("update", "-qq")); err != nil {
		return fmt.Errorf("apt-get update failed: %w\n%s", err, out)
	}
	if out, err := runCmd(aptGet("install", "-y", "mongodb-org")); err != nil {
		return fmt.Errorf("apt-get install mongodb-org failed: %w\n%s", err, out)
	}

	return enableAndStart("mongod")
}

// mongoDBAptVendor returns the upstream vendor segment ("ubuntu" or "debian")
// to use in the MongoDB apt repo URL. Ubuntu derivatives (Mint/Pop/elementary)
// resolve to "ubuntu"; pure Debian derivatives (Kali/Devuan) resolve to
// "debian". Defaults to "ubuntu" when the family is ambiguous, since MongoDB's
// Ubuntu archive accepts a wider set of codenames.
func mongoDBAptVendor(d Distro) string {
	if d.ID == "ubuntu" || d.IsLike("ubuntu") {
		return "ubuntu"
	}
	if d.ID == "debian" || d.IsLike("debian") {
		return "debian"
	}
	return "ubuntu"
}

// mongoDBCodename returns a MongoDB-supported apt repo codename. Newer
// upstream releases that MongoDB has not yet published for fall back to the
// latest supported codename for that vendor.
//
// MongoDB 8.0 publishes for: focal, jammy, noble (Ubuntu); bullseye, bookworm
// (Debian).
func mongoDBCodename(d Distro, vendor string) (string, error) {
	if d.Codename == "" {
		return "", fmt.Errorf("cannot determine distribution codename from /etc/os-release")
	}
	switch vendor {
	case "ubuntu":
		switch d.Codename {
		case "focal", "jammy", "noble":
			return d.Codename, nil
		}
		return "noble", nil
	case "debian":
		switch d.Codename {
		case "bullseye", "bookworm":
			return d.Codename, nil
		}
		return "bookworm", nil
	}
	return d.Codename, nil
}

// installRedisAPT installs Redis from the distro's apt repository.
func installRedisAPT() error {
	if out, err := runCmd(aptGet("install", "-y", "redis-server")); err != nil {
		return fmt.Errorf("apt-get install redis-server failed: %w\n%s", err, out)
	}
	return enableAndStart("redis-server")
}
