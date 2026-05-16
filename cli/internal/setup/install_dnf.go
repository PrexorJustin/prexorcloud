package setup

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// installMongoDBDNF installs MongoDB on RHEL-family hosts (RHEL, CentOS,
// Rocky, AlmaLinux, Oracle Linux) using the official MongoDB yum repository.
func installMongoDBDNF(d Distro) error {
	releasever := rhelMajor(d)
	if releasever == "" {
		return fmt.Errorf("cannot determine RHEL major version from VERSION_ID=%q", d.VersionID)
	}

	repoContent := fmt.Sprintf(`[mongodb-org-%s]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/redhat/%s/mongodb-org/%s/x86_64/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-%s.asc
`, mongodbVersion, releasever, mongodbVersion, mongodbVersion)

	repoFile := fmt.Sprintf("/etc/yum.repos.d/mongodb-org-%s.repo", mongodbVersion)
	if err := os.WriteFile(repoFile, []byte(repoContent), 0644); err != nil {
		return fmt.Errorf("failed to write MongoDB repo file: %w", err)
	}

	if out, err := runCmd(exec.Command(d.PackageMgr, "install", "-y", "mongodb-org")); err != nil {
		return fmt.Errorf("%s install mongodb-org failed: %w\n%s", d.PackageMgr, err, out)
	}

	return enableAndStart("mongod")
}

// rhelMajor extracts the RHEL major version from VERSION_ID.
// Examples: "9.4" -> "9"; "9" -> "9"; "8.10" -> "8".
func rhelMajor(d Distro) string {
	v := strings.TrimSpace(d.VersionID)
	if v == "" {
		return ""
	}
	if idx := strings.IndexByte(v, '.'); idx >= 0 {
		return v[:idx]
	}
	return v
}

// installRedisDNF installs Redis from the distro's dnf/yum repository.
// On minimal RHEL/CentOS hosts the redis package lives in EPEL; users can
// install epel-release first if the package is missing.
func installRedisDNF(pkgMgr string) error {
	if out, err := runCmd(exec.Command(pkgMgr, "install", "-y", "redis")); err != nil {
		return fmt.Errorf("%s install redis failed: %w\n%s", pkgMgr, err, out)
	}
	return enableAndStart("redis")
}
