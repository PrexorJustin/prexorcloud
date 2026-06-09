package setup

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

const (
	controllerServiceName = "prexorcloud-controller"
	daemonServiceName     = "prexorcloud-daemon"
	systemdUnitDir        = "/etc/systemd/system"
)

type ControllerServiceOptions struct {
	LocalMongo bool
	LocalRedis bool
}

// controllerUnitTemplate is the systemd unit file for the controller.
// %s placeholders: after/wants block, install dir, jre path
const controllerUnitTemplate = `[Unit]
Description=PrexorCloud Controller
%s

[Service]
Type=simple
WorkingDirectory=%s
ExecStart=%s/bin/java \
  --enable-preview \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Dio.netty.noUnsafe=true \
  -jar PrexorCloudController.jar
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
`

// daemonUnitTemplate is the systemd unit file for the daemon.
const daemonUnitTemplate = `[Unit]
Description=PrexorCloud Daemon
After=network.target

[Service]
Type=simple
WorkingDirectory=%s
ExecStart=%s/bin/java \
  --enable-preview \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Dio.netty.noUnsafe=true \
  -jar PrexorCloudDaemon.jar
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
`

// RegisterControllerService writes the systemd unit file and enables the controller service.
func RegisterControllerService(installDir, jrePath string, opts ControllerServiceOptions) error {
	return registerService(controllerServiceName, renderControllerUnit(installDir, jrePath, opts))
}

// RegisterDaemonService writes the systemd unit file and enables the daemon service.
func RegisterDaemonService(installDir, jrePath string) error {
	return registerService(daemonServiceName, fmt.Sprintf(daemonUnitTemplate, installDir, jrePath))
}

// StartService starts an already-registered systemd unit. Used by the browser
// wizard's native path after RegisterControllerService/RegisterDaemonService —
// those enable the unit but leave starting to the caller so the cobra path can
// gate it behind a separate "start now?" prompt. The wizard always starts.
func StartService(name string) error {
	if out, err := runCmd(exec.Command("systemctl", "start", name)); err != nil {
		return fmt.Errorf("systemctl start %s failed: %w\n%s", name, err, out)
	}
	return nil
}

func registerService(name, content string) error {
	unitPath := filepath.Join(systemdUnitDir, name+".service")
	if err := os.WriteFile(unitPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write unit file: %w", err)
	}

	if err := exec.Command("systemctl", "daemon-reload").Run(); err != nil {
		return fmt.Errorf("systemctl daemon-reload failed: %w", err)
	}
	if err := exec.Command("systemctl", "enable", name).Run(); err != nil {
		return fmt.Errorf("systemctl enable %s failed: %w", name, err)
	}
	return nil
}

func renderControllerUnit(installDir, jrePath string, opts ControllerServiceOptions) string {
	unitDeps := []string{"network.target"}
	if opts.LocalMongo {
		unitDeps = append(unitDeps, "mongod.service")
	}
	if opts.LocalRedis {
		unitDeps = append(unitDeps, "redis.service")
	}

	afterLine := "After=" + joinSystemdDeps(unitDeps)
	if len(unitDeps) == 1 {
		return fmt.Sprintf(controllerUnitTemplate, afterLine, installDir, jrePath)
	}

	wantsLine := "Wants=" + joinSystemdDeps(unitDeps[1:])
	return fmt.Sprintf(controllerUnitTemplate, afterLine+"\n"+wantsLine, installDir, jrePath)
}

func joinSystemdDeps(values []string) string {
	switch len(values) {
	case 0:
		return ""
	case 1:
		return values[0]
	default:
		result := values[0]
		for _, value := range values[1:] {
			result += " " + value
		}
		return result
	}
}

// ControllerServiceName returns the systemd service name for the controller.
func ControllerServiceName() string { return controllerServiceName }

// DaemonServiceName returns the systemd service name for the daemon.
func DaemonServiceName() string { return daemonServiceName }
