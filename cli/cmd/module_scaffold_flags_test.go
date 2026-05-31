package cmd

import (
	"testing"
)

// TestBuildSpecFromFlags_VersionRangeWithComma is a regression test for the
// flag-parsing bug we hit during track C.4 smoke-testing: StringSliceVar would
// split the version range "[1.0,2.0)" on its comma, yielding two bogus
// requirements. We switched to StringArrayVar — verify the comma survives.
func TestBuildSpecFromFlags_VersionRangeWithComma(t *testing.T) {
	defer resetModuleNewFlags()
	moduleNewCapabilities = []string{"prexor.smoke@1.0.0"}
	moduleNewRequires = []string{"prexor.player.journey@[1.0,2.0)"}

	spec, err := buildSpecFromFlags("smoke-test", []string{"paper"})
	if err != nil {
		t.Fatalf("buildSpecFromFlags: %v", err)
	}
	if len(spec.Provides) != 1 || spec.Provides[0].ID != "prexor.smoke" || spec.Provides[0].Version != "1.0.0" {
		t.Fatalf("Provides = %+v", spec.Provides)
	}
	if len(spec.Requires) != 1 {
		t.Fatalf("Requires count = %d, want 1: %+v", len(spec.Requires), spec.Requires)
	}
	if got := spec.Requires[0].VersionRange; got != "[1.0,2.0)" {
		t.Errorf("VersionRange = %q, want [1.0,2.0)", got)
	}
}

func TestBuildSpecFromFlags_NoFrontendNoPlugin(t *testing.T) {
	defer resetModuleNewFlags()
	moduleNewNoFrontend = true
	moduleNewNoPlugin = true

	spec, err := buildSpecFromFlags("backend-only", nil)
	if err != nil {
		t.Fatalf("buildSpecFromFlags: %v", err)
	}
	if spec.WithFrontend {
		t.Error("WithFrontend = true, want false")
	}
	if spec.WithPlugin {
		t.Error("WithPlugin = true, want false")
	}
	if len(spec.PluginTargets) != 0 {
		t.Errorf("PluginTargets = %+v, want empty", spec.PluginTargets)
	}
}

func TestBuildSpecFromFlags_DefaultsAllPlatformsWhenPluginOn(t *testing.T) {
	defer resetModuleNewFlags()

	spec, err := buildSpecFromFlags("auto-plugin", nil)
	if err != nil {
		t.Fatalf("buildSpecFromFlags: %v", err)
	}
	if !spec.WithPlugin {
		t.Fatal("WithPlugin = false, want true")
	}
	if len(spec.PluginTargets) == 0 {
		t.Errorf("PluginTargets is empty — expected fallback to AllTargets")
	}
}

func TestBuildSpecFromFlags_CapabilityWithoutVersionUsesDefault(t *testing.T) {
	defer resetModuleNewFlags()
	moduleNewCapabilities = []string{"prexor.bare"}
	moduleNewRequires = []string{"prexor.consumer"}

	spec, err := buildSpecFromFlags("defaults", nil)
	if err != nil {
		t.Fatalf("buildSpecFromFlags: %v", err)
	}
	if spec.Provides[0].Version != "1.0.0" {
		t.Errorf("default provides version = %q, want 1.0.0", spec.Provides[0].Version)
	}
	if spec.Requires[0].VersionRange != "[1.0,2.0)" {
		t.Errorf("default requires range = %q, want [1.0,2.0)", spec.Requires[0].VersionRange)
	}
}

// resetModuleNewFlags returns the package-level Cobra flag globals to their
// zero state. Run as a deferred call from each test that sets them so cases
// stay independent.
func resetModuleNewFlags() {
	moduleNewPackage = ""
	moduleNewRepoRoot = ""
	moduleNewStripComments = false
	moduleNewForce = false
	moduleNewDry = false
	moduleNewInteractive = false
	moduleNewWizard = false
	moduleNewBrowser = false
	moduleNewTargets = nil
	moduleNewMcPlugin = nil
	moduleNewCapabilities = nil
	moduleNewRequires = nil
	moduleNewNoRest = false
	moduleNewNoMongo = false
	moduleNewNoFrontend = false
	moduleNewNoPlugin = false
}
