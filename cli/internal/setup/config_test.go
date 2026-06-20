package setup

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestWriteControllerConfigIncludesProductionRuntimeAndRuntimeStores(t *testing.T) {
	dir := t.TempDir()

	err := WriteControllerConfig(dir, ControllerConfig{
		HTTPPort:       "8081",
		GRPCPort:       "9091",
		RuntimeProfile: "production",
		MongoURI:       "mongodb://mongo.internal:27017",
		RedisURI:       "redis://redis.internal:6379",
		CORSOrigins:    []string{"https://dashboard.example.com"},
	})
	if err != nil {
		t.Fatalf("WriteControllerConfig() error = %v", err)
	}

	data, err := os.ReadFile(filepath.Join(dir, "config", "controller.yml"))
	if err != nil {
		t.Fatalf("read controller.yml: %v", err)
	}

	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse controller.yml: %v", err)
	}

	runtimeDoc := doc["runtime"].(map[string]any)
	if got := runtimeDoc["profile"]; got != "production" {
		t.Fatalf("runtime.profile = %v, want production", got)
	}

	databaseDoc := doc["database"].(map[string]any)
	if got := databaseDoc["uri"]; got != "mongodb://mongo.internal:27017" {
		t.Fatalf("database.uri = %v", got)
	}

	redisDoc := doc["redis"].(map[string]any)
	if got := redisDoc["uri"]; got != "redis://redis.internal:6379" {
		t.Fatalf("redis.uri = %v", got)
	}
}

func TestWriteControllerConfigDefaultsRuntimeProfileToProduction(t *testing.T) {
	dir := t.TempDir()

	err := WriteControllerConfig(dir, ControllerConfig{
		HTTPPort:    "8080",
		GRPCPort:    "9090",
		MongoURI:    "mongodb://localhost:27017",
		RedisURI:    "redis://localhost:6379",
		CORSOrigins: []string{"http://localhost:3000"},
	})
	if err != nil {
		t.Fatalf("WriteControllerConfig() error = %v", err)
	}

	data, err := os.ReadFile(filepath.Join(dir, "config", "controller.yml"))
	if err != nil {
		t.Fatalf("read controller.yml: %v", err)
	}

	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse controller.yml: %v", err)
	}

	runtimeDoc := doc["runtime"].(map[string]any)
	if got := runtimeDoc["profile"]; got != "production" {
		t.Fatalf("runtime.profile = %v, want production", got)
	}
}

func TestWriteDaemonConfigWritesEndpointsWhenProvided(t *testing.T) {
	dir := t.TempDir()
	err := WriteDaemonConfig(dir, DaemonConfig{
		NodeID:         "node-1",
		ControllerHost: "ctrl-1",
		GRPCPort:       "9090",
		Endpoints:      []string{"ctrl-2:9090", "ctrl-3:9090"},
		JoinToken:      "tok",
	})
	if err != nil {
		t.Fatalf("WriteDaemonConfig() error = %v", err)
	}

	controller := readDaemonController(t, dir)
	eps, ok := controller["endpoints"].([]any)
	if !ok || len(eps) != 2 || eps[0] != "ctrl-2:9090" || eps[1] != "ctrl-3:9090" {
		t.Fatalf("controller.endpoints = %#v", controller["endpoints"])
	}
}

func TestWriteDaemonConfigOmitsEndpointsWhenEmpty(t *testing.T) {
	dir := t.TempDir()
	err := WriteDaemonConfig(dir, DaemonConfig{NodeID: "node-1", ControllerHost: "ctrl-1", GRPCPort: "9090", JoinToken: "tok"})
	if err != nil {
		t.Fatalf("WriteDaemonConfig() error = %v", err)
	}
	if _, present := readDaemonController(t, dir)["endpoints"]; present {
		t.Fatal("controller.endpoints should be omitted when no endpoints are configured")
	}
}

func TestControllerHTTPURLsPrependsPrimaryAndSubstitutesHTTPPort(t *testing.T) {
	got := ControllerHTTPURLs("ctrl-1", []string{"ctrl-2:9090", "ctrl-3:9090"}, "8080")
	want := []string{"http://ctrl-1:8080", "http://ctrl-2:8080", "http://ctrl-3:8080"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ControllerHTTPURLs() = %v, want %v", got, want)
	}
}

func TestControllerHTTPURLsDeduplicatesAndSkipsBlanks(t *testing.T) {
	got := ControllerHTTPURLs("ctrl-1", []string{"ctrl-1:9090", "", "ctrl-2:9090"}, "8080")
	want := []string{"http://ctrl-1:8080", "http://ctrl-2:8080"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ControllerHTTPURLs() = %v, want %v", got, want)
	}
}

func readDaemonController(t *testing.T, dir string) map[string]any {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "config", "daemon.yml"))
	if err != nil {
		t.Fatalf("read daemon.yml: %v", err)
	}
	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse daemon.yml: %v", err)
	}
	controller, ok := doc["controller"].(map[string]any)
	if !ok {
		t.Fatalf("daemon.yml controller block missing/!map: %#v", doc["controller"])
	}
	return controller
}
