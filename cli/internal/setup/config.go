package setup

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

// ControllerConfig holds the values collected by the controller setup wizard.
type ControllerConfig struct {
	HTTPPort       string
	GRPCPort       string
	RuntimeProfile string
	MongoURI       string
	RedisURI       string
	CORSOrigins    []string
}

// DaemonConfig holds the values collected by the daemon setup wizard.
type DaemonConfig struct {
	NodeID         string
	ControllerHost string
	GRPCPort       string
	JoinToken      string
}

// WriteControllerConfig merges the wizard values into controller.yml and writes it
// to <installDir>/config/controller.yml.
func WriteControllerConfig(installDir string, cfg ControllerConfig) error {
	outPath := filepath.Join(installDir, "config", "controller.yml")

	// Build the config map directly — avoids coupling to the Java config struct.
	doc := map[string]any{
		"uuid": "",
		"http": map[string]any{
			"host": DefaultControllerHTTPHost,
			"port": mustInt(cfg.HTTPPort, DefaultControllerHTTPPort),
			"cors": map[string]any{
				"allowedOrigins": cfg.CORSOrigins,
			},
		},
		"grpc": map[string]any{
			"host": DefaultControllerGRPCHost,
			"port": mustInt(cfg.GRPCPort, DefaultControllerGRPCPort),
		},
		"network": map[string]any{
			"allowedSubnets": []string{"0.0.0.0/0", "::/0"},
		},
		"database": map[string]any{
			"uri":      cfg.MongoURI,
			"database": "prexorcloud",
		},
		"redis": map[string]any{
			"uri": cfg.RedisURI,
		},
		"logging": map[string]any{
			"level":  "INFO",
			"format": "HUMAN",
		},
		"scheduler": map[string]any{
			"evaluationIntervalSeconds": 15,
			"scalingCooldownSeconds":    60,
			"nodeTimeoutSeconds":        90,
		},
		"heartbeat": map[string]any{
			"intervalMs":      30000,
			"missedThreshold": 3,
		},
		"runtime": map[string]any{
			"profile": runtimeProfile(cfg.RuntimeProfile),
		},
		"security": map[string]any{
			"jwtSecret":            "",
			"jwtExpirationMinutes": 1440,
			"initialAdminPassword": "",
			"rateLimiting": map[string]any{
				"perIpPerMinute":   100,
				"perUserPerMinute": 300,
			},
		},
		"crashes": map[string]any{
			"ringBufferSize":         500,
			"crashLoopThreshold":     3,
			"crashLoopWindowSeconds": 300,
		},
		"metrics": map[string]any{
			"enabled":                   true,
			"retentionHours":            168,
			"collectionIntervalSeconds": 30,
		},
		"maintenance": map[string]any{
			"enabled": false,
			"message": "The network is currently under maintenance.",
		},
		"modules": map[string]any{
			"directory":     "modules",
			"dataDirectory": "modules/data",
			// Module signing is opt-in: production profile would otherwise require a
			// configured trustRoot. Flip required=true and set trustRoot once a signing
			// trust bundle is provisioned.
			"signing": map[string]any{
				"required":                 false,
				"trustRoot":                "",
				"allowUnsignedDevelopment": true,
			},
		},
		"dashboard": map[string]any{
			"enabled": true,
		},
		"webhooks": []any{},
	}

	return writeYAML(outPath, doc)
}

// WriteControllerConfig merges the wizard values into daemon.yml and writes it
// to <installDir>/config/daemon.yml.
func WriteDaemonConfig(installDir string, cfg DaemonConfig) error {
	outPath := filepath.Join(installDir, "config", "daemon.yml")

	doc := map[string]any{
		"nodeId": cfg.NodeID,
		"controller": map[string]any{
			"host":     cfg.ControllerHost,
			"grpcPort": mustInt(cfg.GRPCPort, DefaultDaemonControllerGRPCPort),
		},
		"security": map[string]any{
			"certificateDir": "config/security",
			"joinToken":      cfg.JoinToken,
		},
		"instances": map[string]any{
			"directory":              "instances",
			"shutdownTimeoutSeconds": 30,
			"killTimeoutSeconds":     10,
			"logRingBufferLines":     500,
		},
		"resources": map[string]any{
			"maxMemoryMb": 0,
		},
		"logging": map[string]any{
			"level":  "INFO",
			"format": "HUMAN",
		},
		"labels": map[string]any{},
		"reconnect": map[string]any{
			"initialDelayMs": 1000,
			"maxDelayMs":     60000,
			"multiplier":     2.0,
		},
	}

	return writeYAML(outPath, doc)
}

// DialTCP attempts a TCP connection to addr to verify reachability.
// addr must be in "host:port" form (e.g. "localhost:27017").
func DialTCP(addr string, timeout time.Duration) error {
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return fmt.Errorf("cannot reach %s: %w", addr, err)
	}
	conn.Close()
	return nil
}

// DialTCPRetry repeatedly tries to dial addr until it succeeds or the total
// timeout elapses. Used after starting a local service that may not yet be
// listening when systemd reports it as active.
func DialTCPRetry(addr string, total time.Duration) error {
	deadline := time.Now().Add(total)
	var lastErr error
	for {
		conn, err := net.DialTimeout("tcp", addr, time.Second)
		if err == nil {
			conn.Close()
			return nil
		}
		lastErr = err
		if time.Now().After(deadline) {
			return fmt.Errorf("cannot reach %s: %w", addr, lastErr)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// DialTCPFromURI extracts host:port from a URI like "mongodb://host:port" or "redis://host:port"
// and attempts a TCP connection.
func DialTCPFromURI(uri string, timeout time.Duration) error {
	// Prefer a real URI parse: credentialed URIs ("scheme://user:pass@host:port/db")
	// must dial host:port only — the userinfo and path are not part of the TCP
	// address. net/url.Host excludes both. (Manual stripping left "user:pass@"
	// in front of host, breaking SplitHostPort with "too many colons".)
	if u, err := url.Parse(uri); err == nil && u.Host != "" {
		return DialTCP(u.Host, timeout)
	}
	// Fallback for scheme-less / unparseable inputs (no userinfo present).
	addr := stripScheme(uri)
	if idx := indexByte(addr, '/'); idx >= 0 {
		addr = addr[:idx]
	}
	return DialTCP(addr, timeout)
}

func writeYAML(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := yaml.NewEncoder(f)
	enc.SetIndent(2)
	return enc.Encode(v)
}

func mustInt(s string, def int) int {
	var v int
	if _, err := fmt.Sscan(s, &v); err != nil || v <= 0 {
		return def
	}
	return v
}

func runtimeProfile(profile string) string {
	if profile == "" {
		return "production"
	}
	return profile
}

func stripScheme(uri string) string {
	for _, prefix := range []string{"mongodb://", "redis://", "http://", "https://"} {
		if len(uri) > len(prefix) && uri[:len(prefix)] == prefix {
			return uri[len(prefix):]
		}
	}
	return uri
}

func indexByte(s string, b byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == b {
			return i
		}
	}
	return -1
}
