package cmd

import (
	"testing"

	"github.com/spf13/cobra"
)

func TestBuildDeployBody_EmptyWhenNoFlagsChanged(t *testing.T) {
	cmd := cloneDeployCmd()
	body := buildDeployBody(cmd)
	if len(body) != 0 {
		t.Errorf("expected empty body, got %v", body)
	}
}

func TestBuildDeployBody_OnlyIncludesChangedFlags(t *testing.T) {
	cmd := cloneDeployCmd()
	if err := cmd.Flags().Set("strategy", "CANARY"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Flags().Set("canary-percent", "20"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Flags().Set("health-gate", "true"); err != nil {
		t.Fatal(err)
	}

	body := buildDeployBody(cmd)
	if body["strategy"] != "CANARY" {
		t.Errorf("strategy = %v", body["strategy"])
	}
	if body["canaryPercent"] != 20 {
		t.Errorf("canaryPercent = %v", body["canaryPercent"])
	}
	if body["healthGateEnabled"] != true {
		t.Errorf("healthGateEnabled = %v", body["healthGateEnabled"])
	}
	if _, ok := body["batchSize"]; ok {
		t.Errorf("batchSize must not be present when flag unchanged")
	}
	if _, ok := body["autoRollbackOnFailure"]; ok {
		t.Errorf("autoRollbackOnFailure must not be present when flag unchanged")
	}
}

func TestBuildDeployBody_AllFlagsSet(t *testing.T) {
	cmd := cloneDeployCmd()
	pairs := map[string]string{
		"strategy":          "ROLLING",
		"batch-size":        "5",
		"canary-instances":  "2",
		"health-gate":       "true",
		"auto-rollback":     "false",
		"promotion-timeout": "300",
		"min-healthy":       "30",
	}
	for k, v := range pairs {
		if err := cmd.Flags().Set(k, v); err != nil {
			t.Fatalf("set %s: %v", k, err)
		}
	}

	body := buildDeployBody(cmd)
	want := map[string]any{
		"strategy":                "ROLLING",
		"batchSize":               5,
		"canaryInstances":         2,
		"healthGateEnabled":       true,
		"autoRollbackOnFailure":   false,
		"promotionTimeoutSeconds": int64(300),
		"minHealthySeconds":       int64(30),
	}
	for k, v := range want {
		if body[k] != v {
			t.Errorf("body[%s] = %v (%T), want %v (%T)", k, body[k], body[k], v, v)
		}
	}
}

// cloneDeployCmd returns a fresh copy of deployCmd so test mutations
// (flag.Set marks Changed=true) do not leak across tests.
func cloneDeployCmd() *cobra.Command {
	c := &cobra.Command{Use: "deploy"}
	c.Flags().String("strategy", "", "")
	c.Flags().Int("batch-size", 0, "")
	c.Flags().Int("canary-instances", 0, "")
	c.Flags().Int("canary-percent", 0, "")
	c.Flags().Bool("health-gate", false, "")
	c.Flags().Bool("auto-rollback", false, "")
	c.Flags().Int64("promotion-timeout", 0, "")
	c.Flags().Int64("min-healthy", 0, "")
	return c
}
