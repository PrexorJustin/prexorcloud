package setup

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"gopkg.in/yaml.v3"
)

func TestControllerNativeSetupTopologyAcceptance(t *testing.T) {
	testCases := []struct {
		name                string
		mongoURI            string
		redisURI            string
		serviceOptions      ControllerServiceOptions
		wantUnitContains    []string
		wantUnitNotContains []string
	}{
		{
			name:     "local mongo and redis",
			mongoURI: "mongodb://localhost:27017",
			redisURI: "redis://localhost:6379",
			serviceOptions: ControllerServiceOptions{
				LocalMongo: true,
				LocalRedis: true,
			},
			wantUnitContains: []string{
				"mongod.service",
				"redis.service",
			},
		},
		{
			name:           "remote mongo and redis",
			mongoURI:       "mongodb://mongo.internal:27017",
			redisURI:       "redis://redis.internal:6379",
			serviceOptions: ControllerServiceOptions{},
			wantUnitNotContains: []string{
				"mongod.service",
				"redis.service",
			},
		},
		{
			name:     "local mongo and remote redis",
			mongoURI: "mongodb://localhost:27017",
			redisURI: "redis://redis.internal:6379",
			serviceOptions: ControllerServiceOptions{
				LocalMongo: true,
			},
			wantUnitContains: []string{
				"mongod.service",
			},
			wantUnitNotContains: []string{
				"redis.service",
			},
		},
		{
			name:     "remote mongo and local redis",
			mongoURI: "mongodb://mongo.internal:27017",
			redisURI: "redis://localhost:6379",
			serviceOptions: ControllerServiceOptions{
				LocalRedis: true,
			},
			wantUnitContains: []string{
				"redis.service",
			},
			wantUnitNotContains: []string{
				"mongod.service",
			},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := WriteControllerConfig(dir, ControllerConfig{
				HTTPPort:       "8080",
				GRPCPort:       "9090",
				RuntimeProfile: "production",
				MongoURI:       tc.mongoURI,
				RedisURI:       tc.redisURI,
				CORSOrigins:    []string{"http://localhost:3000"},
			}); err != nil {
				t.Fatalf("WriteControllerConfig() error = %v", err)
			}

			doc := readYAMLDocument(t, filepath.Join(dir, "config", "controller.yml"))
			databaseDoc := doc["database"].(map[string]any)
			redisDoc := doc["redis"].(map[string]any)
			if databaseDoc["uri"] != tc.mongoURI {
				t.Fatalf("database.uri = %v, want %s", databaseDoc["uri"], tc.mongoURI)
			}
			if redisDoc["uri"] != tc.redisURI {
				t.Fatalf("redis.uri = %v, want %s", redisDoc["uri"], tc.redisURI)
			}

			unit := renderControllerUnit("/opt/prexorcloud/controller", "/opt/prexorcloud/jre", tc.serviceOptions)
			for _, want := range tc.wantUnitContains {
				if !strings.Contains(unit, want) {
					t.Fatalf("controller unit missing %q: %s", want, unit)
				}
			}
			for _, unwanted := range tc.wantUnitNotContains {
				if strings.Contains(unit, unwanted) {
					t.Fatalf("controller unit unexpectedly contains %q: %s", unwanted, unit)
				}
			}
		})
	}
}

func TestControllerComposeSetupTopologyAcceptance(t *testing.T) {
	testCases := []struct {
		name             string
		options          ControllerComposeProjectOptions
		wantServices     []string
		wantNotServices  []string
		wantDependencies []string
	}{
		{
			name: "local mongo and redis",
			options: ControllerComposeProjectOptions{
				LocalMongo: true,
				LocalRedis: true,
			},
			wantServices: []string{"controller", "mongo", "redis"},
			wantDependencies: []string{
				"mongo",
				"redis",
			},
		},
		{
			name:            "remote mongo and redis",
			options:         ControllerComposeProjectOptions{},
			wantServices:    []string{"controller"},
			wantNotServices: []string{"mongo", "redis"},
		},
		{
			name: "local mongo and remote redis",
			options: ControllerComposeProjectOptions{
				LocalMongo: true,
			},
			wantServices:     []string{"controller", "mongo"},
			wantNotServices:  []string{"redis"},
			wantDependencies: []string{"mongo"},
		},
		{
			name: "remote mongo and local redis",
			options: ControllerComposeProjectOptions{
				LocalRedis: true,
			},
			wantServices:     []string{"controller", "redis"},
			wantNotServices:  []string{"mongo"},
			wantDependencies: []string{"redis"},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := WriteControllerComposeProject(dir, ControllerConfig{
				HTTPPort: "8080",
				GRPCPort: "9090",
			}, tc.options); err != nil {
				t.Fatalf("WriteControllerComposeProject() error = %v", err)
			}

			doc := readYAMLDocument(t, filepath.Join(dir, "docker-compose.yml"))
			services := doc["services"].(map[string]any)
			for _, want := range tc.wantServices {
				if _, ok := services[want]; !ok {
					t.Fatalf("compose services missing %q: %#v", want, services)
				}
			}
			for _, unwanted := range tc.wantNotServices {
				if _, ok := services[unwanted]; ok {
					t.Fatalf("compose services unexpectedly contain %q: %#v", unwanted, services)
				}
			}

			controller := services["controller"].(map[string]any)
			dependsOn, _ := controller["depends_on"].([]any)
			for _, want := range tc.wantDependencies {
				if !containsAnyString(dependsOn, want) {
					t.Fatalf("controller depends_on missing %q: %#v", want, dependsOn)
				}
			}
			if len(tc.wantDependencies) == 0 && len(dependsOn) != 0 {
				t.Fatalf("controller depends_on unexpectedly populated: %#v", dependsOn)
			}
		})
	}
}

func TestNativeControllerSetupBootableAcceptance(t *testing.T) {
	testCases := []struct {
		name                string
		cfg                 ControllerConfig
		serviceOptions      ControllerServiceOptions
		verificationOptions ControllerVerificationOptions
		wantTCPChecks       []string
		wantUnitContains    []string
		wantUnitNotContains []string
	}{
		{
			name: "local managed mongo and redis",
			cfg: ControllerConfig{
				HTTPPort:       "8080",
				GRPCPort:       "9090",
				RuntimeProfile: "production",
				MongoURI:       "mongodb://localhost:27017",
				RedisURI:       "redis://localhost:6379",
				CORSOrigins:    []string{"http://localhost:3000"},
			},
			serviceOptions: ControllerServiceOptions{
				LocalMongo: true,
				LocalRedis: true,
			},
			verificationOptions: ControllerVerificationOptions{
				LocalMongo:        true,
				LocalRedis:        true,
				ServiceRegistered: true,
			},
			wantTCPChecks: []string{
				"127.0.0.1:27017",
				"127.0.0.1:6379",
			},
			wantUnitContains: []string{
				"mongod.service",
				"redis.service",
			},
		},
		{
			name: "remote runtime stores",
			cfg: ControllerConfig{
				HTTPPort:       "18080",
				GRPCPort:       "19090",
				RuntimeProfile: "production",
				MongoURI:       "mongodb://mongo.internal:27017",
				RedisURI:       "redis://redis.internal:6379",
				CORSOrigins:    []string{"https://dashboard.example.com"},
			},
			serviceOptions: ControllerServiceOptions{},
			verificationOptions: ControllerVerificationOptions{
				ServiceRegistered: true,
			},
			wantUnitNotContains: []string{
				"mongod.service",
				"redis.service",
			},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := CreateControllerDirs(dir); err != nil {
				t.Fatalf("CreateControllerDirs() error = %v", err)
			}
			mustWriteFile(t, filepath.Join(dir, "PrexorCloudController.jar"))

			if err := WriteControllerConfig(dir, tc.cfg); err != nil {
				t.Fatalf("WriteControllerConfig() error = %v", err)
			}

			doc := readYAMLDocument(t, filepath.Join(dir, "config", "controller.yml"))
			runtimeDoc := doc["runtime"].(map[string]any)
			databaseDoc := doc["database"].(map[string]any)
			redisDoc := doc["redis"].(map[string]any)
			if runtimeDoc["profile"] != "production" {
				t.Fatalf("runtime.profile = %v, want production", runtimeDoc["profile"])
			}
			if databaseDoc["uri"] != tc.cfg.MongoURI {
				t.Fatalf("database.uri = %v, want %s", databaseDoc["uri"], tc.cfg.MongoURI)
			}
			if redisDoc["uri"] != tc.cfg.RedisURI {
				t.Fatalf("redis.uri = %v, want %s", redisDoc["uri"], tc.cfg.RedisURI)
			}

			unit := renderControllerUnit(dir, ManagedJREPath, tc.serviceOptions)
			for _, want := range tc.wantUnitContains {
				if !strings.Contains(unit, want) {
					t.Fatalf("controller unit missing %q: %s", want, unit)
				}
			}
			for _, unwanted := range tc.wantUnitNotContains {
				if strings.Contains(unit, unwanted) {
					t.Fatalf("controller unit unexpectedly contains %q: %s", unwanted, unit)
				}
			}

			var tcpChecks []string
			var systemctlCalls []string
			deps := controllerVerifierDeps{
				fileStat: os.Stat,
				tcpCheck: func(addr string, timeout time.Duration) error {
					tcpChecks = append(tcpChecks, addr)
					return nil
				},
				systemctl: func(args ...string) (string, error) {
					systemctlCalls = append(systemctlCalls, strings.Join(args, " "))
					switch args[0] {
					case "start":
						return "", nil
					case "is-enabled":
						return "enabled", nil
					case "is-active":
						return "active", nil
					default:
						return "", nil
					}
				},
				httpHealth: func(url string, timeout time.Duration) error { return nil },
				sleep:      func(time.Duration) {},
			}

			startupCheck := startAndValidateControllerService(tc.cfg, 2*time.Second, deps)
			if startupCheck.Status != VerificationOK {
				t.Fatalf("startupCheck.Status = %s, want ok (%#v)", startupCheck.Status, startupCheck)
			}

			report := verifyNativeControllerInstall(dir, tc.cfg, tc.verificationOptions, deps)
			if report.HasIssues() {
				t.Fatalf("report unexpectedly has issues: %#v", report)
			}
			if !slices.Equal(tcpChecks, tc.wantTCPChecks) {
				t.Fatalf("tcpChecks = %#v, want %#v", tcpChecks, tc.wantTCPChecks)
			}

			for _, call := range systemctlCalls {
				if strings.Contains(call, "mongod") || strings.Contains(call, "redis") {
					t.Fatalf("systemctl call unexpectedly referenced local data service: %s", call)
				}
			}
		})
	}
}

func readYAMLDocument(t *testing.T, path string) map[string]any {
	t.Helper()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}

	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
	return doc
}

func containsAnyString(values []any, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
