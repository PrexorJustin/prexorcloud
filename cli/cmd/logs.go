package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var logsCmd = &cobra.Command{
	Use:   "logs",
	Short: "View controller, daemon, and module logs",
	Long: `Stream or page recent log records from PrexorCloud components.

Run with --follow to open the live tail view (filter with /, pause with p,
scroll with j/k). Without a subcommand, logs --follow tails the controller.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		// `logs --follow` with no subcommand is the design's cluster-wide tail;
		// until a real aggregator endpoint exists it maps to controller logs.
		return logsControllerCmd.RunE(cmd, args)
	},
}

var logsControllerCmd = &cobra.Command{
	Use:   "controller",
	Short: "View recent controller logs (with --follow for live tail)",
	Args:  cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		level, _ := cmd.Flags().GetString("level")
		loggerPrefix, _ := cmd.Flags().GetString("logger")
		tail, _ := cmd.Flags().GetInt("tail")
		follow, _ := cmd.Flags().GetBool("follow")

		share := readShareFlags(cmd)
		if share.enabled {
			if follow {
				return fmt.Errorf("--share cannot be combined with --follow")
			}
			return runShare(
				client,
				"/api/v1/system/logs/share",
				share.toRequest(level, loggerPrefix, tail),
				"controller logs",
			)
		}

		params := map[string]string{
			"level":  level,
			"logger": loggerPrefix,
			"limit":  fmt.Sprintf("%d", tail),
		}

		var resp logsResponse
		if err := client.GetWithQuery("/api/v1/system/logs", params, &resp); err != nil {
			return err
		}

		if flagJSON && !follow {
			return theme.PrintJSON(resp)
		}

		if !follow {
			for _, r := range resp.Records {
				renderLogRecord(r)
			}
			return nil
		}

		return followLogs(cmd.Context(), client, "controller", level,
			func(ctx context.Context, push func(logsRecord)) error {
				for _, r := range resp.Records {
					push(r)
				}
				return streamControllerLogs(ctx, client, level, loggerPrefix, resp.LastSeq(), push)
			})
	},
}

type logsRecord struct {
	Seq       int64             `json:"seq"`
	Ts        int64             `json:"ts"`
	Level     string            `json:"level"`
	Logger    string            `json:"logger"`
	Thread    string            `json:"thread"`
	Message   string            `json:"message"`
	Throwable string            `json:"throwable,omitempty"`
	Mdc       map[string]string `json:"mdc,omitempty"`
}

type logsResponse struct {
	Records  []logsRecord `json:"records"`
	Size     int          `json:"size"`
	Capacity int          `json:"capacity"`
	Level    string       `json:"level"`
	Logger   string       `json:"logger"`
}

func (r logsResponse) LastSeq() int64 {
	if len(r.Records) == 0 {
		return 0
	}
	return r.Records[len(r.Records)-1].Seq
}

// formatLogRecord renders a single log record into the design's tail-line
// shape: `HH:MM:SS.mmm  LEVEL  logger │ message`. The level is color-coded
// (ERROR red, WARN amber, INFO cyan) and a throwable, if present, is appended
// indented and in red.
func formatLogRecord(r logsRecord) string {
	ts := time.UnixMilli(r.Ts).Format("15:04:05.000")
	level := strings.ToUpper(r.Level)
	var levelStyled string
	switch level {
	case "ERROR":
		levelStyled = theme.StyleRed().Bold(true).Render(padRight(level, 5))
	case "WARN":
		levelStyled = theme.StyleAmber().Bold(true).Render(padRight(level, 5))
	case "INFO":
		levelStyled = theme.StyleCyan().Render(padRight(level, 5))
	default:
		levelStyled = theme.StyleDim().Render(padRight(level, 5))
	}
	line := fmt.Sprintf("%s  %s  %s %s %s",
		theme.StyleMute().Render(ts),
		levelStyled,
		theme.StyleDim().Render(r.Logger),
		theme.StyleFaint().Render(theme.BoxSharp().V),
		r.Message,
	)
	if r.Throwable != "" {
		for _, l := range strings.Split(strings.TrimRight(r.Throwable, "\n"), "\n") {
			line += "\n    " + theme.StyleRed().Render(l)
		}
	}
	return line
}

func renderLogRecord(r logsRecord) {
	fmt.Println(formatLogRecord(r))
}

// logsHeader builds the design's `⏵ TAILING` header block for the live view.
func logsHeader(scope, level string) []string {
	if level == "" {
		level = "INFO"
	}
	return []string{
		fmt.Sprintf("%s %s  %s logs  %s level %s+",
			theme.StyleBrand().Render(theme.PlayGlyph()),
			theme.StyleBrand().Bold(true).Render("TAILING"),
			scope,
			theme.Bullet(),
			theme.Code(strings.ToUpper(level)),
		),
		theme.HRule(72),
		theme.Hint("press " + theme.Code("/") + " to filter " + theme.Bullet() + " " +
			theme.Code("p") + " to pause " + theme.Bullet() + " " +
			theme.Code("j/k") + " scroll " + theme.Bullet() + " " +
			theme.Code("Ctrl-C") + " to quit"),
		"",
	}
}

// followLogs runs the shared LogStream bubbletea view, draining the stream
// opened by openStream into it. The stream is cancelled when the user quits.
func followLogs(parent context.Context, client *api.Client, scope, level string,
	openStream func(ctx context.Context, push func(logsRecord)) error) error {

	ctx, cancel := context.WithCancel(parent)
	defer cancel()

	ls := tui.NewLogStream(tui.LogStreamConfig{
		Header:  logsHeader(scope, level),
		Command: "logs " + scope,
		Cluster: shortHost(cfg.Resolve(flagController, flagContext)),
		Version: cliVersion,
	})

	go func() {
		err := openStream(ctx, func(r logsRecord) { ls.Push(formatLogRecord(r)) })
		ls.Close(err)
	}()

	runErr := ls.Run()
	cancel()
	return runErr
}

func streamControllerLogs(ctx context.Context, client *api.Client, level, loggerPrefix string, sinceSeq int64, push func(logsRecord)) error {
	q := []string{"tail=0"}
	if level != "" {
		q = append(q, "level="+level)
	}
	if loggerPrefix != "" {
		q = append(q, "logger="+loggerPrefix)
	}
	path := "/api/v1/system/logs/stream?" + strings.Join(q, "&")

	err := client.SSEStreamWithTicket(ctx, path, "/api/v1/system/logs/ticket", func(event, data string) bool {
		if event != "log" {
			return ctx.Err() == nil
		}
		var r logsRecord
		if err := json.Unmarshal([]byte(data), &r); err != nil {
			return ctx.Err() == nil
		}
		if r.Seq != 0 && r.Seq <= sinceSeq {
			return ctx.Err() == nil
		}
		push(r)
		return ctx.Err() == nil
	})

	if ctx.Err() != nil {
		return nil
	}
	return err
}

var logsDaemonCmd = &cobra.Command{
	Use:   "daemon <node-id>",
	Short: "View recent daemon logs from a connected node (with --follow for live tail)",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		nodeID := args[0]
		if nodeID == "" {
			return fmt.Errorf("node id is required")
		}

		client, err := requireAuth()
		if err != nil {
			return err
		}

		level, _ := cmd.Flags().GetString("level")
		loggerPrefix, _ := cmd.Flags().GetString("logger")
		tail, _ := cmd.Flags().GetInt("tail")
		follow, _ := cmd.Flags().GetBool("follow")

		share := readShareFlags(cmd)
		if share.enabled {
			if follow {
				return fmt.Errorf("--share cannot be combined with --follow")
			}
			return runShare(
				client,
				"/api/v1/nodes/"+nodeID+"/logs/share",
				share.toRequest(level, loggerPrefix, tail),
				"daemon logs "+nodeID,
			)
		}

		params := map[string]string{
			"level":  level,
			"logger": loggerPrefix,
			"limit":  fmt.Sprintf("%d", tail),
		}

		var resp logsResponse
		if err := client.GetWithQuery("/api/v1/nodes/"+nodeID+"/logs", params, &resp); err != nil {
			return err
		}

		if flagJSON && !follow {
			return theme.PrintJSON(resp)
		}

		if !follow {
			for _, r := range resp.Records {
				renderLogRecord(r)
			}
			return nil
		}

		return followLogs(cmd.Context(), client, "daemon "+nodeID, level,
			func(ctx context.Context, push func(logsRecord)) error {
				for _, r := range resp.Records {
					push(r)
				}
				return streamDaemonLogs(ctx, client, nodeID, level, loggerPrefix, resp.LastSeq(), push)
			})
	},
}

func streamDaemonLogs(ctx context.Context, client *api.Client, nodeID, level, loggerPrefix string, sinceSeq int64, push func(logsRecord)) error {
	q := []string{"tail=0"}
	if level != "" {
		q = append(q, "level="+level)
	}
	if loggerPrefix != "" {
		q = append(q, "logger="+loggerPrefix)
	}
	path := "/api/v1/nodes/" + nodeID + "/logs/stream?" + strings.Join(q, "&")
	ticketPath := "/api/v1/nodes/" + nodeID + "/logs/ticket"

	err := client.SSEStreamWithTicket(ctx, path, ticketPath, func(event, data string) bool {
		if event != "log" {
			return ctx.Err() == nil
		}
		var r logsRecord
		if err := json.Unmarshal([]byte(data), &r); err != nil {
			return ctx.Err() == nil
		}
		if r.Seq != 0 && r.Seq <= sinceSeq {
			return ctx.Err() == nil
		}
		push(r)
		return ctx.Err() == nil
	})

	if ctx.Err() != nil {
		return nil
	}
	return err
}

func init() {
	logsCmd.PersistentFlags().Bool("follow", false, "Continue streaming new log records in the live tail view")
	logsCmd.PersistentFlags().Int("tail", 200, "Number of recent records to print before streaming")
	logsCmd.PersistentFlags().String("level", "INFO", "Minimum log level (TRACE/DEBUG/INFO/WARN/ERROR)")
	logsCmd.PersistentFlags().String("logger", "", "Only include records from loggers with this prefix")

	registerShareFlags(logsControllerCmd)
	registerShareFlags(logsDaemonCmd)

	logsCmd.AddCommand(logsControllerCmd)
	logsCmd.AddCommand(logsDaemonCmd)
}
