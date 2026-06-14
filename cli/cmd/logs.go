package cmd

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var logsCmd = &cobra.Command{
	Use:   "logs [controller|daemon|instance|all]",
	Short: "View controller, daemon, and instance logs",
	Long: `Stream or page logs from any PrexorCloud component — the controller, a
node's daemon, a single instance's console, or every instance at once.

Run with --follow (-f) to open the live tail view: filter with /, pause with p,
scroll with j/k, quit with Ctrl-C.

With no subcommand in an interactive terminal, a picker lets you choose what to
tail (like ` + "`group info`" + `). In scripts or with --json it defaults to the
controller log so automation stays predictable.

  prexorctl logs                      # interactive picker (TTY)
  prexorctl logs controller -f        # live controller tail
  prexorctl logs daemon               # pick a node, then tail it
  prexorctl logs instance survival-lobby-5 -f
  prexorctl logs all --group survival-lobby   # merged tail of a group`,
	RunE: func(cmd *cobra.Command, args []string) error {
		// Interactive TTY with no subcommand: offer a chooser. Non-interactive
		// (scripts/pipes/--json) keeps the predictable controller default.
		if interactive() {
			return runLogsChooser(cmd, args)
		}
		return logsControllerCmd.RunE(cmd, args)
	},
}

// runLogsChooser is the `group info`-style entry point: pick a target, then a
// concrete resource, then tail it live. Defaults to --follow since picking a
// target interactively implies "show me the live stream".
func runLogsChooser(cmd *cobra.Command, args []string) error {
	client, err := requireAuth()
	if err != nil {
		return err
	}

	if !cmd.Flags().Changed("follow") {
		_ = cmd.Flags().Set("follow", "true")
	}

	choice, err := pickOne("What do you want to tail?", []huh.Option[string]{
		huh.NewOption("Controller      — the control-plane log", "controller"),
		huh.NewOption("Daemon          — a node agent's log", "daemon"),
		huh.NewOption("Instance        — one server/proxy console", "instance"),
		huh.NewOption("All instances   — merged live console tail", "all"),
	})
	if err != nil {
		return err
	}

	switch choice {
	case "controller":
		return logsControllerCmd.RunE(cmd, args)
	case "daemon":
		node, err := pickNode(client, "Select a node")
		if err != nil {
			return err
		}
		return logsDaemonCmd.RunE(cmd, []string{node})
	case "instance":
		id, err := pickInstance(client, "Select an instance")
		if err != nil {
			return err
		}
		return logsInstanceCmd.RunE(cmd, []string{id})
	case "all":
		return logsAllCmd.RunE(cmd, args)
	}
	return nil
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
	Use:   "daemon [node-id]",
	Short: "View a node daemon's logs (with --follow for live tail)",
	Args:  cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		nodeID, err := resolveArg(args, "node id required (e.g. `prexorctl logs daemon <node-id>`)",
			func() (string, error) { return pickNode(client, "Select a node") })
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

var logsInstanceCmd = &cobra.Command{
	Use:   "instance [id]",
	Short: "View an instance's console log (with --follow for live tail)",
	Args:  cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		id, err := resolveArg(args, "instance id required (e.g. `prexorctl logs instance <id>`)",
			func() (string, error) { return pickInstance(client, "Select an instance") })
		if err != nil {
			return err
		}

		follow, _ := cmd.Flags().GetBool("follow")
		tail, _ := cmd.Flags().GetInt("tail")

		share := readShareFlags(cmd)
		if share.enabled {
			if follow {
				return fmt.Errorf("--share cannot be combined with --follow")
			}
			return runShare(
				client,
				"/api/v1/services/"+id+"/console/share",
				share.toRequest("", "", tail),
				"instance console "+id,
			)
		}

		if !follow {
			return printInstanceHistory(client, id, tail)
		}
		return tailInstanceConsole(cmd.Context(), client, id)
	},
}

type consoleHistoryResponse struct {
	Lines []struct {
		Ts   string `json:"ts"`
		Line string `json:"line"`
	} `json:"lines"`
}

// printInstanceHistory pages the recent console buffer for one instance and
// exits — the non-follow counterpart to the live tail.
func printInstanceHistory(client *api.Client, id string, limit int) error {
	params := map[string]string{"limit": fmt.Sprintf("%d", limit)}
	var resp consoleHistoryResponse
	if err := client.GetWithQuery("/api/v1/services/"+id+"/console/history", params, &resp); err != nil {
		return err
	}
	if flagJSON {
		return theme.PrintJSON(resp)
	}
	for _, l := range resp.Lines {
		fmt.Println(formatConsoleLine(l.Line))
	}
	return nil
}

// tailInstanceConsole opens a read-only live console tail for one instance.
// (`instance console` adds a command input; `logs instance` is view-only.)
func tailInstanceConsole(parent context.Context, client *api.Client, id string) error {
	var inst map[string]any
	_ = client.Get("/api/v1/services/"+id, &inst)
	node := str(inst, "node")
	group := str(inst, "group")
	state := str(inst, "state")
	if state == "" {
		state = "UNKNOWN"
	}

	ctx, cancel := context.WithCancel(parent)
	defer cancel()

	ls := tui.NewLogStream(tui.LogStreamConfig{
		Header:  consoleHeader(id, node, group, state),
		Command: "logs instance " + id,
		Cluster: shortHost(cfg.Resolve(flagController, flagContext)),
		Version: cliVersion,
	})

	go func() {
		streamErr := client.SSEStream(ctx, "/api/v1/services/"+id+"/console",
			func(event, data string) bool {
				ls.Push(formatConsoleLine(data))
				return ctx.Err() == nil
			})
		if ctx.Err() != nil {
			streamErr = nil
		}
		ls.Close(streamErr)
	}()

	runErr := ls.Run()
	cancel()
	return runErr
}

var logsAllCmd = &cobra.Command{
	Use:   "all",
	Short: "Live tail of every instance's console, merged with colored prefixes",
	Long: `Fan out a live console tail across every running instance and merge the
streams into one view, each line tagged with a per-instance colored prefix.

Narrow the set with --group or --node.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		groupFilter, _ := cmd.Flags().GetString("group")
		nodeFilter, _ := cmd.Flags().GetString("node")

		var instances []api.InstanceResponse
		if err := client.GetList("/api/v1/services", nil, &instances); err != nil {
			return err
		}

		targets := make([]api.InstanceResponse, 0, len(instances))
		for _, i := range instances {
			if i.ID == "" {
				continue
			}
			if groupFilter != "" && !equalFold(i.Group, groupFilter) {
				continue
			}
			if nodeFilter != "" && !equalFold(i.Node, nodeFilter) {
				continue
			}
			targets = append(targets, i)
		}
		if len(targets) == 0 {
			return errors.New("no instances match (try without --group/--node, or start some)")
		}

		ctx, cancel := context.WithCancel(cmd.Context())
		defer cancel()

		ls := tui.NewLogStream(tui.LogStreamConfig{
			Header:  logsAllHeader(len(targets), groupFilter, nodeFilter),
			Command: "logs all",
			Cluster: shortHost(cfg.Resolve(flagController, flagContext)),
			Version: cliVersion,
		})

		var wg sync.WaitGroup
		for idx, inst := range targets {
			id := inst.ID
			prefix := instancePrefix(id, idx)
			wg.Add(1)
			go func() {
				defer wg.Done()
				_ = client.SSEStream(ctx, "/api/v1/services/"+id+"/console",
					func(event, data string) bool {
						ls.Push(prefix + " " + formatConsoleLine(data))
						return ctx.Err() == nil
					})
			}()
		}

		// When every upstream stream ends on its own, close the view (unless the
		// user already quit, which cancels ctx).
		go func() {
			wg.Wait()
			if ctx.Err() == nil {
				ls.Close(nil)
			}
		}()

		runErr := ls.Run()
		cancel()
		return runErr
	},
}

// instancePalette is the rotating color set for per-instance prefixes in the
// merged `logs all` view. Hand-picked to stay legible on dark backgrounds.
var instancePalette = []lipgloss.Style{
	theme.StyleCyan(),
	theme.StyleGreen(),
	theme.StyleAmber(),
	theme.StyleBlue(),
	theme.StyleBrand(),
	theme.StyleRed(),
}

func instancePrefix(id string, idx int) string {
	return instancePalette[idx%len(instancePalette)].Render("[" + id + "]")
}

// logsAllHeader builds the `⏵ TAILING` header for the merged instance view.
func logsAllHeader(count int, group, node string) []string {
	scope := fmt.Sprintf("%d instance%s", count, plural(count, "", "s"))
	filt := ""
	if group != "" {
		filt += "  " + theme.Bullet() + " group " + theme.Code(group)
	}
	if node != "" {
		filt += "  " + theme.Bullet() + " node " + theme.Code(node)
	}
	return []string{
		fmt.Sprintf("%s %s  %s%s",
			theme.StyleBrand().Render(theme.PlayGlyph()),
			theme.StyleBrand().Bold(true).Render("TAILING"),
			scope,
			filt,
		),
		theme.HRule(72),
		theme.Hint("merged live console " + theme.Bullet() + " press " +
			theme.Code("/") + " to filter " + theme.Bullet() + " " +
			theme.Code("p") + " to pause " + theme.Bullet() + " " +
			theme.Code("Ctrl-C") + " to quit"),
		"",
	}
}

func init() {
	logsCmd.PersistentFlags().BoolP("follow", "f", false, "Continue streaming new log records in the live tail view")
	logsCmd.PersistentFlags().IntP("tail", "n", 200, "Number of recent records to print before streaming")
	logsCmd.PersistentFlags().String("level", "INFO", "Minimum log level (TRACE/DEBUG/INFO/WARN/ERROR)")
	logsCmd.PersistentFlags().String("logger", "", "Only include records from loggers with this prefix")

	logsAllCmd.Flags().String("group", "", "Only tail instances in this group")
	logsAllCmd.Flags().String("node", "", "Only tail instances on this node")

	registerShareFlags(logsControllerCmd)
	registerShareFlags(logsDaemonCmd)
	registerShareFlags(logsInstanceCmd)

	logsCmd.AddCommand(logsControllerCmd)
	logsCmd.AddCommand(logsDaemonCmd)
	logsCmd.AddCommand(logsInstanceCmd)
	logsCmd.AddCommand(logsAllCmd)
}
