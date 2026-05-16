package setup

import (
	"fmt"
	"os/exec"
)

// installRedisAPK installs Redis from the Alpine community repository. Alpine
// uses OpenRC as its init system, so the OpenRC branch of enableAndStart
// handles the lifecycle.
func installRedisAPK() error {
	if out, err := runCmd(exec.Command("apk", "add", "--no-cache", "redis")); err != nil {
		return fmt.Errorf("apk add redis failed: %w\n%s", err, out)
	}
	return enableAndStart("redis")
}
