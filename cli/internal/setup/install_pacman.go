package setup

import (
	"fmt"
	"os/exec"
)

// installRedisPacman installs Redis from the Arch extra repository. Works on
// Arch and its derivatives (Manjaro, EndeavourOS, CachyOS, Garuda, Artix).
func installRedisPacman() error {
	if out, err := runCmd(exec.Command("pacman", "-Sy", "--noconfirm", "redis")); err != nil {
		return fmt.Errorf("pacman -S redis failed: %w\n%s", err, out)
	}
	return enableAndStart("redis")
}
