package setup

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type startupContractSnapshot struct {
	ProtocolVersion             string `json:"protocolVersion"`
	DefaultGrpcPort             int    `json:"defaultGrpcPort"`
	DefaultHeartbeatIntervalMs  int    `json:"defaultHeartbeatIntervalMs"`
	DefaultNodeTimeoutMs        int    `json:"defaultNodeTimeoutMs"`
	ControllerHTTPHost          string `json:"controllerHttpHost"`
	ControllerHTTPPort          int    `json:"controllerHttpPort"`
	ControllerGRPCHost          string `json:"controllerGrpcHost"`
	ControllerGRPCPort          int    `json:"controllerGrpcPort"`
	DaemonControllerHost        string `json:"daemonControllerHost"`
	DaemonControllerGrpcPort    int    `json:"daemonControllerGrpcPort"`
	CliControllerHTTPPort       int    `json:"cliControllerHttpPort"`
	CliControllerGRPCPort       int    `json:"cliControllerGrpcPort"`
	CliDaemonControllerGRPCPort int    `json:"cliDaemonControllerGrpcPort"`
}

func TestStartupContractSnapshot(t *testing.T) {
	snapshotPath := filepath.Clean(filepath.Join("..", "..", "..", "java", "cloud-protocol", "contracts", "startup-contract.json"))
	data, err := os.ReadFile(snapshotPath)
	if err != nil {
		t.Fatalf("read startup contract snapshot: %v", err)
	}

	var snapshot startupContractSnapshot
	if err := json.Unmarshal(data, &snapshot); err != nil {
		t.Fatalf("parse startup contract snapshot: %v", err)
	}

	if snapshot.CliControllerHTTPPort != DefaultControllerHTTPPort {
		t.Fatalf("cli controller http port drifted: snapshot=%d actual=%d", snapshot.CliControllerHTTPPort, DefaultControllerHTTPPort)
	}
	if snapshot.CliControllerGRPCPort != DefaultControllerGRPCPort {
		t.Fatalf("cli controller grpc port drifted: snapshot=%d actual=%d", snapshot.CliControllerGRPCPort, DefaultControllerGRPCPort)
	}
	if snapshot.CliDaemonControllerGRPCPort != DefaultDaemonControllerGRPCPort {
		t.Fatalf(
			"cli daemon controller grpc port drifted: snapshot=%d actual=%d",
			snapshot.CliDaemonControllerGRPCPort,
			DefaultDaemonControllerGRPCPort,
		)
	}
	if snapshot.ControllerHTTPHost != DefaultControllerHTTPHost {
		t.Fatalf("cli controller http host drifted: snapshot=%s actual=%s", snapshot.ControllerHTTPHost, DefaultControllerHTTPHost)
	}
	if snapshot.ControllerGRPCHost != DefaultControllerGRPCHost {
		t.Fatalf("cli controller grpc host drifted: snapshot=%s actual=%s", snapshot.ControllerGRPCHost, DefaultControllerGRPCHost)
	}
	if snapshot.DaemonControllerHost != DefaultDaemonControllerHost {
		t.Fatalf("cli daemon controller host drifted: snapshot=%s actual=%s", snapshot.DaemonControllerHost, DefaultDaemonControllerHost)
	}
}
