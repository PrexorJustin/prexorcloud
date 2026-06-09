package cmd

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var diagnosticsCmd = &cobra.Command{
	Use:     "diagnostics",
	Aliases: []string{"diag"},
	Short:   "Generate operator-facing diagnostics",
}

var diagnosticsBundleCmd = &cobra.Command{
	Use:   "bundle",
	Short: "Collect a redacted diagnostics bundle (tar.gz)",
	Long: `Collect a redacted diagnostics bundle for support / postmortems.

The bundle includes:
  manifest.json    - bundle metadata + controller version
  readiness.json   - readiness probe snapshot
  overview.json    - cluster counts (nodes, instances, players, groups)
  settings.json    - non-sensitive runtime settings
  config.json      - controller config with secrets redacted
  redis.json       - Redis-protocol keyspace summary
  leases.json      - distributed lease holders
  logs.txt         - recent controller log records (best-effort)

Sensitive fields (JWT secrets, admin password, URI credentials) are redacted
server-side before transport. Logs older than the controller log buffer
capacity are not retrievable through this surface; rotate the bundle close
to the incident or scrape on-disk logs separately.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		out, _ := cmd.Flags().GetString("out")
		logLines, _ := cmd.Flags().GetInt("log-lines")

		share := readShareFlags(cmd)
		if share.enabled {
			if err := runShare(
				client,
				"/api/v1/system/diagnostics/share",
				share.toRequest("", "", 0),
				"diagnostics bundle",
			); err != nil {
				return err
			}
			// --share + --out: share AND keep the local copy.
			if out == "" {
				return nil
			}
		}

		var diag map[string]any
		if err := client.Get("/api/v1/system/diagnostics", &diag); err != nil {
			return fmt.Errorf("fetch diagnostics: %w", err)
		}

		var logs map[string]any
		if logLines > 0 {
			params := map[string]string{
				"level": "DEBUG",
				"limit": fmt.Sprintf("%d", logLines),
			}
			if err := client.GetWithQuery("/api/v1/system/logs", params, &logs); err != nil {
				theme.PrintWarn(fmt.Sprintf("could not fetch logs (continuing without): %v", err))
				logs = nil
			}
		}

		path, err := writeBundle(out, diag, logs)
		if err != nil {
			return err
		}

		stat, _ := os.Stat(path)
		size := ""
		if stat != nil {
			size = fmt.Sprintf(" (%s)", humanBytes(stat.Size()))
		}
		theme.PrintSuccess(fmt.Sprintf("Diagnostics bundle written: %s%s", path, size))
		return nil
	},
}

func writeBundle(outPath string, diag map[string]any, logs map[string]any) (string, error) {
	if outPath == "" {
		ts := time.Now().Format("20060102-150405")
		outPath = filepath.Join(".", fmt.Sprintf("prexorctl-diag-%s.tar.gz", ts))
	}

	f, err := os.Create(outPath)
	if err != nil {
		return "", fmt.Errorf("create bundle: %w", err)
	}
	defer f.Close()

	gz := gzip.NewWriter(f)
	defer gz.Close()

	tw := tar.NewWriter(gz)
	defer tw.Close()

	manifest := map[string]any{
		"bundleVersion":     1,
		"generatedAt":       time.Now().UTC().Format(time.RFC3339),
		"clientTool":        "prexorctl",
		"controllerId":      diag["controllerId"],
		"generatedAtMs":     diag["generatedAtMs"],
		"controllerVersion": diag["version"],
	}
	if err := writeJSONEntry(tw, "manifest.json", manifest); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "readiness.json", diag["readiness"]); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "overview.json", diag["overview"]); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "settings.json", diag["settings"]); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "config.json", diag["redactedConfig"]); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "redis.json", diag["redisKeyspace"]); err != nil {
		return "", err
	}
	if err := writeJSONEntry(tw, "leases.json", diag["leases"]); err != nil {
		return "", err
	}

	if logs != nil {
		if err := writeLogsEntry(tw, "logs.txt", logs); err != nil {
			return "", err
		}
	}

	return outPath, nil
}

func writeJSONEntry(tw *tar.Writer, name string, payload any) error {
	body, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal %s: %w", name, err)
	}
	return writeTarFile(tw, name, body)
}

func writeLogsEntry(tw *tar.Writer, name string, logs map[string]any) error {
	records, _ := logs["records"].([]any)
	var buf bytes.Buffer
	for _, raw := range records {
		rec, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		ts := int64(0)
		if v, ok := rec["ts"].(float64); ok {
			ts = int64(v)
		}
		level := strings.ToUpper(strAny(rec["level"]))
		logger := strAny(rec["logger"])
		msg := strAny(rec["message"])
		fmt.Fprintf(&buf, "%s %-5s %s %s\n", time.UnixMilli(ts).UTC().Format("2006-01-02T15:04:05.000Z"), level, logger, msg)
		if th := strAny(rec["throwable"]); th != "" {
			for _, line := range strings.Split(strings.TrimRight(th, "\n"), "\n") {
				fmt.Fprintf(&buf, "    %s\n", line)
			}
		}
	}
	return writeTarFile(tw, name, buf.Bytes())
}

func writeTarFile(tw *tar.Writer, name string, body []byte) error {
	header := &tar.Header{
		Name:    name,
		Mode:    0o600,
		Size:    int64(len(body)),
		ModTime: time.Now(),
	}
	if err := tw.WriteHeader(header); err != nil {
		return fmt.Errorf("write header %s: %w", name, err)
	}
	if _, err := tw.Write(body); err != nil {
		return fmt.Errorf("write body %s: %w", name, err)
	}
	return nil
}

func strAny(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for x := n / unit; x >= unit; x /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}

func init() {
	diagnosticsBundleCmd.Flags().StringP("out", "o", "", "Output path (default: ./prexorctl-diag-<timestamp>.tar.gz)")
	diagnosticsBundleCmd.Flags().Int("log-lines", 500, "Number of recent log lines to include (0 to skip)")
	registerShareFlags(diagnosticsBundleCmd)
	diagnosticsCmd.AddCommand(diagnosticsBundleCmd)
}
