package setup

import (
	"strings"
	"testing"
)

// TestAptGetSuppressesInteractivePrompts guards the env vars + dpkg flags
// that keep `apt-get install` fully unattended inside the wizard. The
// regression we're protecting against: MongoDB's install on Ubuntu 24.04
// pulled in a transitive that woke `needrestart` up, which then drew a
// purple whiptail "Pending kernel upgrade" dialog the wizard's xterm had
// no way to dismiss. Each knob below pins one prompt class to silent.
func TestAptGetSuppressesInteractivePrompts(t *testing.T) {
	cmd := aptGet("install", "-y", "mongodb-org")

	// Args: the dpkg-conflict-resolution flags must appear BEFORE the
	// subcommand so apt-get parses them as global options.
	got := strings.Join(cmd.Args, " ")
	wantContains := []string{
		"apt-get",
		"-o Dpkg::Options::=--force-confdef",
		"-o Dpkg::Options::=--force-confold",
		"install -y mongodb-org",
	}
	for _, w := range wantContains {
		if !strings.Contains(got, w) {
			t.Errorf("aptGet args = %q, missing %q", got, w)
		}
	}

	// Env: every interactive surface we know about pinned to silent. If any
	// of these is missing, the regression returns.
	wantEnv := map[string]string{
		"DEBIAN_FRONTEND":         "noninteractive",
		"NEEDRESTART_MODE":        "a",
		"NEEDRESTART_SUSPEND":     "1",
		"APT_LISTCHANGES_FRONTEND": "none",
		"UCF_FORCE_CONFOLD":       "1",
	}
	seen := map[string]string{}
	for _, kv := range cmd.Env {
		if i := strings.IndexByte(kv, '='); i > 0 {
			seen[kv[:i]] = kv[i+1:]
		}
	}
	for k, v := range wantEnv {
		if seen[k] != v {
			t.Errorf("env[%s] = %q, want %q", k, seen[k], v)
		}
	}
}
