package cmd

import "testing"

func TestParseCatalogEntries(t *testing.T) {
	raw := []map[string]any{
		{"moduleId": "a", "version": "1.2.0", "installed": true, "installedVersion": "1.1.0"},
		{"moduleId": "b", "version": "2.0.0"}, // not installed, missing fields
	}
	got := parseCatalogEntries(raw)
	if len(got) != 2 {
		t.Fatalf("got %d entries, want 2", len(got))
	}
	if got[0] != (registryCatalogEntry{moduleID: "a", version: "1.2.0", installed: true, installedVersion: "1.1.0"}) {
		t.Fatalf("entry[0] = %+v", got[0])
	}
	if got[1].installed || got[1].installedVersion != "" {
		t.Fatalf("entry[1] should be uninstalled with no version: %+v", got[1])
	}
}

func TestDecideUpgrade(t *testing.T) {
	catalog := []registryCatalogEntry{
		{moduleID: "newer", version: "1.2.0", installed: true, installedVersion: "1.1.0"},
		{moduleID: "current", version: "3.0.0", installed: true, installedVersion: "3.0.0"},
		{moduleID: "absent-locally", version: "1.0.0", installed: false},
		{moduleID: "no-version", version: "", installed: true, installedVersion: "1.0.0"},
	}
	cases := []struct {
		id   string
		want upgradeDecision
	}{
		{"newer", upgradeAvailable},
		{"current", upgradeUpToDate},
		{"absent-locally", upgradeNotInstalled},
		{"no-version", upgradeUpToDate},
		{"unknown", upgradeNotInRegistry},
	}
	for _, tc := range cases {
		t.Run(tc.id, func(t *testing.T) {
			if _, got := decideUpgrade(catalog, tc.id); got != tc.want {
				t.Fatalf("decideUpgrade(%q) = %v, want %v", tc.id, got, tc.want)
			}
		})
	}
}

func TestSelectUpgradable(t *testing.T) {
	catalog := []registryCatalogEntry{
		{moduleID: "a", version: "1.2.0", installed: true, installedVersion: "1.1.0"}, // upgradable
		{moduleID: "b", version: "2.0.0", installed: true, installedVersion: "2.0.0"}, // up to date
		{moduleID: "c", version: "1.0.0", installed: false},                           // not installed
		{moduleID: "d", version: "", installed: true, installedVersion: "1.0.0"},      // no advertised version
		{moduleID: "e", version: "9.9.9", installed: true, installedVersion: "1.0.0"}, // upgradable
	}
	got := selectUpgradable(catalog)
	if len(got) != 2 {
		t.Fatalf("got %d upgradable, want 2: %+v", len(got), got)
	}
	if got[0].moduleID != "a" || got[1].moduleID != "e" {
		t.Fatalf("unexpected upgradable set: %+v", got)
	}
}
