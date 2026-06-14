package setup

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// defaultRestartPolicy is used when a caller leaves RestartPolicy empty — the
// service restarts on boot and after crashes, matching the prior hardcoded value.
const defaultRestartPolicy = "unless-stopped"

func restartOrDefault(policy string) string {
	if policy == "" {
		return defaultRestartPolicy
	}
	return policy
}

// ComposeUp runs `docker compose up -d` in the given install directory so the
// wizard can start the stack immediately after writing docker-compose.yml.
func ComposeUp(installDir string) error {
	cmd := exec.Command("docker", "compose", "up", "-d")
	cmd.Dir = installDir
	if out, err := runCmd(cmd); err != nil {
		return fmt.Errorf("docker compose up -d failed: %w\n%s", err, out)
	}
	return nil
}

const (
	controllerComposeWorkDir = "/opt/prexorcloud/controller"
	daemonComposeWorkDir     = "/opt/prexorcloud/daemon"
)

// ControllerComposeDir and DaemonComposeDir are the install directories where the
// wizard writes each component's docker-compose.yml.
func ControllerComposeDir() string { return controllerComposeWorkDir }
func DaemonComposeDir() string     { return daemonComposeWorkDir }

// HasComposeProject reports whether a docker-compose.yml exists in installDir,
// i.e. the component was installed in Docker (Compose) mode rather than native.
func HasComposeProject(installDir string) bool {
	if installDir == "" {
		return false
	}
	_, err := os.Stat(filepath.Join(installDir, "docker-compose.yml"))
	return err == nil
}

// ComposeStop runs `docker compose stop` in installDir, stopping a compose-managed
// PrexorCloud component without removing its containers or volumes.
func ComposeStop(installDir string) error {
	cmd := exec.Command("docker", "compose", "stop")
	cmd.Dir = installDir
	if out, err := runCmd(cmd); err != nil {
		return fmt.Errorf("docker compose stop failed: %w\n%s", err, out)
	}
	return nil
}

type ControllerComposeProjectOptions struct {
	LocalMongo bool
	LocalRedis bool
	// RestartPolicy for the controller service (e.g. "unless-stopped" to auto-start
	// on boot, "no" to never auto-start). Empty defaults to "unless-stopped".
	RestartPolicy string
}

// WriteControllerComposeProject writes a Docker Compose file for a controller
// install that runs the downloaded controller JAR plus optional local MongoDB
// and Redis services.
func WriteControllerComposeProject(installDir string, cfg ControllerConfig, opts ControllerComposeProjectOptions) error {
	if opts.LocalMongo {
		if err := os.MkdirAll(filepath.Join(installDir, "mongo-data"), 0755); err != nil {
			return err
		}
	}
	if opts.LocalRedis {
		if err := os.MkdirAll(filepath.Join(installDir, "redis-data"), 0755); err != nil {
			return err
		}
	}

	controller := map[string]any{
		"image":       "eclipse-temurin:25-jre",
		"working_dir": controllerComposeWorkDir,
		"command":     javaJarCommand("PrexorCloudController.jar"),
		"volumes":     []string{"./:" + controllerComposeWorkDir},
		"ports":       []string{fmt.Sprintf("%d:%d", mustInt(cfg.HTTPPort, DefaultControllerHTTPPort), mustInt(cfg.HTTPPort, DefaultControllerHTTPPort)), fmt.Sprintf("%d:%d", mustInt(cfg.GRPCPort, DefaultControllerGRPCPort), mustInt(cfg.GRPCPort, DefaultControllerGRPCPort))},
		"restart":     restartOrDefault(opts.RestartPolicy),
		"init":        true,
	}

	dependsOn := make([]string, 0, 2)
	services := map[string]any{
		"controller": controller,
	}

	if opts.LocalMongo {
		dependsOn = append(dependsOn, "mongo")
		services["mongo"] = map[string]any{
			"image":   "mongo:8",
			"ports":   []string{"27017:27017"},
			"volumes": []string{"./mongo-data:/data/db"},
			"restart": "unless-stopped",
		}
	}
	if opts.LocalRedis {
		dependsOn = append(dependsOn, "redis")
		services["redis"] = map[string]any{
			"image":   "redis:7-alpine",
			"ports":   []string{"6379:6379"},
			"volumes": []string{"./redis-data:/data"},
			"restart": "unless-stopped",
		}
	}
	if len(dependsOn) > 0 {
		controller["depends_on"] = dependsOn
	}

	return writeYAML(filepath.Join(installDir, "docker-compose.yml"), map[string]any{
		"services": services,
	})
}

// WriteDaemonComposeProject writes a Docker Compose file for a daemon install
// that runs the downloaded daemon JAR from the generated install directory, with
// the default (auto-start on boot) restart policy.
func WriteDaemonComposeProject(installDir string) error {
	return WriteDaemonComposeProjectWithRestart(installDir, defaultRestartPolicy)
}

// WriteDaemonComposeProjectWithRestart is like WriteDaemonComposeProject but lets
// the caller pick the restart policy ("unless-stopped" to auto-start on boot, "no"
// otherwise). Empty defaults to "unless-stopped".
func WriteDaemonComposeProjectWithRestart(installDir, restartPolicy string) error {
	return writeYAML(filepath.Join(installDir, "docker-compose.yml"), map[string]any{
		"services": map[string]any{
			"daemon": map[string]any{
				"image":       "eclipse-temurin:25-jre",
				"working_dir": daemonComposeWorkDir,
				"command":     javaJarCommand("PrexorCloudDaemon.jar"),
				"volumes":     []string{"./:" + daemonComposeWorkDir},
				"restart":     restartOrDefault(restartPolicy),
				"init":        true,
			},
		},
	})
}

func javaJarCommand(jarName string) []string {
	return []string{
		"java",
		"--enable-preview",
		"--enable-native-access=ALL-UNNAMED",
		"--sun-misc-unsafe-memory-access=allow",
		"-Dio.netty.noUnsafe=true",
		"-jar",
		jarName,
	}
}
