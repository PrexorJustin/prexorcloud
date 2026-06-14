package setup

import (
	"os"
	"path/filepath"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestWriteControllerComposeProjectIncludesLocalMongoAndRedis(t *testing.T) {
	dir := t.TempDir()

	err := WriteControllerComposeProject(dir, ControllerConfig{
		HTTPPort: "18080",
		GRPCPort: "19090",
	}, ControllerComposeProjectOptions{
		LocalMongo: true,
		LocalRedis: true,
	})
	if err != nil {
		t.Fatalf("WriteControllerComposeProject() error = %v", err)
	}

	doc := readComposeDoc(t, filepath.Join(dir, "docker-compose.yml"))
	services := doc["services"].(map[string]any)

	if _, ok := services["controller"]; !ok {
		t.Fatal("controller service missing")
	}
	if _, ok := services["mongo"]; !ok {
		t.Fatal("mongo service missing")
	}
	if _, ok := services["redis"]; !ok {
		t.Fatal("redis service missing")
	}

	controller := services["controller"].(map[string]any)
	dependsOn := controller["depends_on"].([]any)
	if len(dependsOn) != 2 {
		t.Fatalf("controller depends_on = %#v", dependsOn)
	}

	if _, err := os.Stat(filepath.Join(dir, "mongo-data")); err != nil {
		t.Fatalf("mongo-data directory missing: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "redis-data")); err != nil {
		t.Fatalf("redis-data directory missing: %v", err)
	}
}

func TestWriteControllerComposeProjectOmitsLocalStoresForRemoteDependencies(t *testing.T) {
	dir := t.TempDir()

	err := WriteControllerComposeProject(dir, ControllerConfig{
		HTTPPort: "8080",
		GRPCPort: "9090",
	}, ControllerComposeProjectOptions{})
	if err != nil {
		t.Fatalf("WriteControllerComposeProject() error = %v", err)
	}

	doc := readComposeDoc(t, filepath.Join(dir, "docker-compose.yml"))
	services := doc["services"].(map[string]any)
	if len(services) != 1 {
		t.Fatalf("services = %#v, want only controller", services)
	}
	if _, ok := services["mongo"]; ok {
		t.Fatal("mongo service unexpectedly present")
	}
	if _, ok := services["redis"]; ok {
		t.Fatal("redis service unexpectedly present")
	}
}

func TestWriteDaemonComposeProjectWritesDaemonService(t *testing.T) {
	dir := t.TempDir()

	if err := WriteDaemonComposeProject(dir); err != nil {
		t.Fatalf("WriteDaemonComposeProject() error = %v", err)
	}

	doc := readComposeDoc(t, filepath.Join(dir, "docker-compose.yml"))
	services := doc["services"].(map[string]any)
	daemon := services["daemon"].(map[string]any)
	if daemon["working_dir"] != daemonComposeWorkDir {
		t.Fatalf("daemon working_dir = %v, want %s", daemon["working_dir"], daemonComposeWorkDir)
	}
}

func TestWriteControllerComposeProjectRestartPolicy(t *testing.T) {
	for _, tc := range []struct {
		name   string
		policy string
		want   string
	}{
		{"default", "", "unless-stopped"},
		{"explicit unless-stopped", "unless-stopped", "unless-stopped"},
		{"no auto-start", "no", "no"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := WriteControllerComposeProject(dir, ControllerConfig{HTTPPort: "8080", GRPCPort: "9090"},
				ControllerComposeProjectOptions{RestartPolicy: tc.policy}); err != nil {
				t.Fatalf("WriteControllerComposeProject() error = %v", err)
			}
			doc := readComposeDoc(t, filepath.Join(dir, "docker-compose.yml"))
			controller := doc["services"].(map[string]any)["controller"].(map[string]any)
			if controller["restart"] != tc.want {
				t.Fatalf("restart = %v, want %v", controller["restart"], tc.want)
			}
		})
	}
}

func TestWriteDaemonComposeProjectWithRestart(t *testing.T) {
	dir := t.TempDir()
	if err := WriteDaemonComposeProjectWithRestart(dir, "no"); err != nil {
		t.Fatalf("WriteDaemonComposeProjectWithRestart() error = %v", err)
	}
	doc := readComposeDoc(t, filepath.Join(dir, "docker-compose.yml"))
	daemon := doc["services"].(map[string]any)["daemon"].(map[string]any)
	if daemon["restart"] != "no" {
		t.Fatalf("restart = %v, want no", daemon["restart"])
	}
}

func readComposeDoc(t *testing.T, path string) map[string]any {
	t.Helper()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read compose file: %v", err)
	}

	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse compose file: %v", err)
	}
	return doc
}
