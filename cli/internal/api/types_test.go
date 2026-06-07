package api

import (
	"encoding/json"
	"testing"
)

func TestOverviewResponse_Deserialize(t *testing.T) {
	raw := `{"nodeCount":3,"instanceCount":10,"playerCount":42,"groupCount":5}`
	var resp OverviewResponse
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if resp.NodeCount != 3 {
		t.Errorf("NodeCount = %d, want 3", resp.NodeCount)
	}
	if resp.InstanceCount != 10 {
		t.Errorf("InstanceCount = %d, want 10", resp.InstanceCount)
	}
	if resp.PlayerCount != 42 {
		t.Errorf("PlayerCount = %d, want 42", resp.PlayerCount)
	}
	if resp.GroupCount != 5 {
		t.Errorf("GroupCount = %d, want 5", resp.GroupCount)
	}
}

func TestInstanceResponse_Deserialize(t *testing.T) {
	raw := `{"id":"lobby-1","group":"lobby","node":"node-1","state":"RUNNING","port":25565,"playerCount":10,"uptimeMs":3600000}`
	var resp InstanceResponse
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if resp.ID != "lobby-1" {
		t.Errorf("ID = %q, want lobby-1", resp.ID)
	}
	if resp.State != "RUNNING" {
		t.Errorf("State = %q, want RUNNING", resp.State)
	}
	if resp.PlayerCount != 10 {
		t.Errorf("PlayerCount = %d, want 10", resp.PlayerCount)
	}
}

func TestGroupResponse_Deserialize(t *testing.T) {
	raw := `{"name":"lobby","platform":"paper","scalingMode":"DYNAMIC","minInstances":1,"maxInstances":5,"maxPlayers":50,"runningInstances":2,"totalPlayers":15,"maintenance":false,"static":false}`
	var resp GroupResponse
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if resp.Name != "lobby" {
		t.Errorf("Name = %q, want lobby", resp.Name)
	}
	if resp.MinInstances != 1 {
		t.Errorf("MinInstances = %d, want 1", resp.MinInstances)
	}
	if resp.Maintenance {
		t.Error("Maintenance should be false")
	}
}

func TestCrashResponse_Deserialize(t *testing.T) {
	raw := `{"id":"crash-1","instanceId":"lobby-1","group":"lobby","nodeId":"node-1","exitCode":137,"classification":"OOM_KILLED","logTail":["line1","line2"],"uptimeMs":5000,"timestamp":"2026-01-01T00:00:00Z"}`
	var resp CrashResponse
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if resp.ExitCode != 137 {
		t.Errorf("ExitCode = %d, want 137", resp.ExitCode)
	}
	if resp.Classification != "OOM_KILLED" {
		t.Errorf("Classification = %q, want OOM_KILLED", resp.Classification)
	}
	if len(resp.LogTail) != 2 {
		t.Errorf("LogTail length = %d, want 2", len(resp.LogTail))
	}
}

func TestNodeResponse_Serialize(t *testing.T) {
	resp := NodeResponse{
		ID:            "node-1",
		Type:          "CONNECTED",
		Status:        "ONLINE",
		CPUUsage:      45.5,
		UsedMemoryMB:  8192,
		TotalMemoryMB: 16384,
		InstanceCount: 3,
	}
	data, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	var parsed map[string]any
	json.Unmarshal(data, &parsed)
	if parsed["id"] != "node-1" {
		t.Errorf("id = %v, want node-1", parsed["id"])
	}
	if parsed["cpuUsage"] != 45.5 {
		t.Errorf("cpuUsage = %v, want 45.5", parsed["cpuUsage"])
	}
}

func TestTokenResponse_OmitsJoinToken(t *testing.T) {
	resp := TokenResponse{
		TokenID: "tok-1",
		NodeID:  "node-1",
	}
	data, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	var parsed map[string]any
	json.Unmarshal(data, &parsed)
	if _, ok := parsed["joinToken"]; ok {
		t.Error("joinToken should be omitted when empty")
	}
}
