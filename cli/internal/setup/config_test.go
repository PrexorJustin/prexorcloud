package setup

import (
	"os"
	"path/filepath"
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
