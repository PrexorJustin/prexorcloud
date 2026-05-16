package setup

import (
	"fmt"
	"os"
	"path/filepath"
)

const (
	controllerComposeWorkDir = "/opt/prexorcloud/controller"
	daemonComposeWorkDir     = "/opt/prexorcloud/daemon"
)

type ControllerComposeProjectOptions struct {
	LocalMongo bool
	LocalRedis bool
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
		"restart":     "unless-stopped",
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
// that runs the downloaded daemon JAR from the generated install directory.
func WriteDaemonComposeProject(installDir string) error {
	return writeYAML(filepath.Join(installDir, "docker-compose.yml"), map[string]any{
		"services": map[string]any{
			"daemon": map[string]any{
				"image":       "eclipse-temurin:25-jre",
				"working_dir": daemonComposeWorkDir,
				"command":     javaJarCommand("PrexorCloudDaemon.jar"),
				"volumes":     []string{"./:" + daemonComposeWorkDir},
				"restart":     "unless-stopped",
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
