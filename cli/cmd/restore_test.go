package cmd

import (
	"testing"

	"github.com/spf13/cobra"
)

func cloneRestoreCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "restore"}
	cmd.Flags().Bool("dry-run", false, "")
	cmd.Flags().Bool("filesystem", true, "")
	cmd.Flags().Bool("datastores", true, "")
	return cmd
}

func TestBuildRestoreBody_DefaultsToFullApply(t *testing.T) {
	body := buildRestoreBody(cloneRestoreCmd(), "20260301-000000-abcd")

	if body["id"] != "20260301-000000-abcd" {
		t.Errorf("id = %v", body["id"])
	}
	if body["dryRun"] != false {
		t.Errorf("dryRun = %v, want false", body["dryRun"])
	}
	if body["filesystem"] != true {
		t.Errorf("filesystem = %v, want true", body["filesystem"])
	}
	if body["datastores"] != true {
		t.Errorf("datastores = %v, want true", body["datastores"])
	}
}

func TestBuildRestoreBody_HonoursScopeFlags(t *testing.T) {
	cmd := cloneRestoreCmd()
	if err := cmd.Flags().Set("dry-run", "true"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.Flags().Set("datastores", "false"); err != nil {
		t.Fatal(err)
	}

	body := buildRestoreBody(cmd, "id-1")

	if body["dryRun"] != true {
		t.Errorf("dryRun = %v, want true", body["dryRun"])
	}
	if body["filesystem"] != true {
		t.Errorf("filesystem = %v, want true", body["filesystem"])
	}
	if body["datastores"] != false {
		t.Errorf("datastores = %v, want false", body["datastores"])
	}
}
