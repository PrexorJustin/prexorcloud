package util

import "testing"

func TestStr_ReturnsValue(t *testing.T) {
	m := map[string]any{"name": "lobby-1"}
	if got := Str(m, "name"); got != "lobby-1" {
		t.Errorf("Str() = %q, want %q", got, "lobby-1")
	}
}

func TestStr_ReturnsDashForMissing(t *testing.T) {
	m := map[string]any{}
	if got := Str(m, "missing"); got != "-" {
		t.Errorf("Str() = %q, want %q", got, "-")
	}
}

func TestStr_ConvertsNonString(t *testing.T) {
	m := map[string]any{"count": 42}
	if got := Str(m, "count"); got != "42" {
		t.Errorf("Str() = %q, want %q", got, "42")
	}
}

func TestNum_ReturnsValue(t *testing.T) {
	m := map[string]any{"cpu": 85.5}
	if got := Num(m, "cpu"); got != 85.5 {
		t.Errorf("Num() = %f, want %f", got, 85.5)
	}
}

func TestNum_ReturnsZeroForMissing(t *testing.T) {
	m := map[string]any{}
	if got := Num(m, "missing"); got != 0 {
		t.Errorf("Num() = %f, want 0", got)
	}
}

func TestNum_ReturnsZeroForWrongType(t *testing.T) {
	m := map[string]any{"cpu": "high"}
	if got := Num(m, "cpu"); got != 0 {
		t.Errorf("Num() = %f, want 0", got)
	}
}

func TestFormatUptime(t *testing.T) {
	tests := []struct {
		name string
		ms   int64
		want string
	}{
		{"zero", 0, "-"},
		{"negative", -1000, "-"},
		{"seconds", 45_000, "45s"},
		{"minutes", 125_000, "2m 5s"},
		{"hours", 7_200_000, "2h 0m"},
		{"hours and minutes", 5_430_000, "1h 30m"},
		{"days", 90_000_000, "1d 1h"},
		{"many days", 259_200_000, "3d 0h"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := FormatUptime(tt.ms); got != tt.want {
				t.Errorf("FormatUptime(%d) = %q, want %q", tt.ms, got, tt.want)
			}
		})
	}
}

func TestFormatBytes(t *testing.T) {
	tests := []struct {
		name  string
		bytes int64
		want  string
	}{
		{"bytes", 512, "512 B"},
		{"kilobytes", 2048, "2.0 KB"},
		{"megabytes", 5_242_880, "5.0 MB"},
		{"gigabytes", 2_147_483_648, "2.00 GB"},
		{"zero", 0, "0 B"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := FormatBytes(tt.bytes); got != tt.want {
				t.Errorf("FormatBytes(%d) = %q, want %q", tt.bytes, got, tt.want)
			}
		})
	}
}
