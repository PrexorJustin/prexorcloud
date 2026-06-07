package setup

import (
	"fmt"
	"os/exec"
)

// installRedisZypper installs Redis from the openSUSE/SLES zypper repository.
// SUSE ships Redis as a multi-instance template unit; the "default" instance
// is started so the standard 6379 port comes up.
func installRedisZypper() error {
	if out, err := runCmd(exec.Command("zypper", "--non-interactive", "install", "redis")); err != nil {
		return fmt.Errorf("zypper install redis failed: %w\n%s", err, out)
	}
	return enableAndStart("redis@default")
}
