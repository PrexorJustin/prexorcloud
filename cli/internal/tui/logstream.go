package tui

import (
	"strings"

	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// LogStreamConfig configures a shared streaming-log bubbletea model. It backs
// both `logs --follow` (filter / pause / history) and `instance console`
// (a focused command input line that submits to OnSubmit).
type LogStreamConfig struct {
	Header   []string     // header lines rendered above the scrollback
	Command  string       // status-bar command label
	Cluster  string       // status-bar cluster name
	Version  string       // status-bar version
	Input    bool         // show a focused command input line (console mode)
	Submit   func(string) // called with each submitted command line (console)
}

const logStreamCap = 5000

// logLineMsg carries a freshly arrived log line into the model.
type logLineMsg string

// logClosedMsg signals the upstream stream ended.
type logClosedMsg struct{ err error }

// LogStreamModel is the bubbletea model. Construct with NewLogStream, feed it
// with Push, finish with Close, and run it with Run.
type LogStreamModel struct {
	cfg   LogStreamConfig
	vp    viewport.Model
	input textinput.Model

	lines chan string
	done  chan error

	buf      []string // every line received (capped at logStreamCap)
	paused   bool
	pendBuf  []string // lines held back while paused
	filter   string
	filterOn bool // filter-entry mode active
	atBottom bool

	width, height int
	ready         bool
	err           error
	quit          bool
}

// NewLogStream builds a LogStreamModel. The caller streams data in via Push
// from a separate goroutine and signals end-of-stream with Close.
func NewLogStream(cfg LogStreamConfig) *LogStreamModel {
	ti := textinput.New()
	ti.Prompt = theme.StyleBrand().Render(theme.BrandGlyph() + " ")
	ti.Placeholder = ""
	ti.CharLimit = 1024

	m := &LogStreamModel{
		cfg:      cfg,
		input:    ti,
		lines:    make(chan string, 256),
		done:     make(chan error, 1),
		atBottom: true,
	}
	if cfg.Input {
		m.input.Focus()
	}
	return m
}

// Push hands a rendered log line to the model. Safe to call from any goroutine.
func (m *LogStreamModel) Push(line string) {
	select {
	case m.lines <- line:
	default:
		// Drop on a full buffer rather than block the producer; the UI is
		// already behind and the user can't read 256 lines deep anyway.
	}
}

// Close signals the upstream stream has ended (err may be nil).
func (m *LogStreamModel) Close(err error) {
	select {
	case m.done <- err:
	default:
	}
}

// Run starts the bubbletea program and blocks until the user quits.
func (m *LogStreamModel) Run() error {
	p := tea.NewProgram(m, tea.WithAltScreen())
	_, err := p.Run()
	if err != nil {
		return err
	}
	return m.err
}

func (m *LogStreamModel) Init() tea.Cmd {
	return tea.Batch(m.waitForLine(), m.waitForClose(), textinput.Blink)
}

func (m *LogStreamModel) waitForLine() tea.Cmd {
	return func() tea.Msg { return logLineMsg(<-m.lines) }
}

func (m *LogStreamModel) waitForClose() tea.Cmd {
	return func() tea.Msg { return logClosedMsg{err: <-m.done} }
}

func (m *LogStreamModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmds []tea.Cmd

	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		m.ready = true
		m.layout()

	case logLineMsg:
		m.appendLine(string(msg))
		cmds = append(cmds, m.waitForLine())

	case logClosedMsg:
		m.err = msg.err
		// Stream ended — drain anything buffered, then keep the screen up so
		// the user can scroll. They quit explicitly.

	case tea.KeyMsg:
		if cmd := m.handleKey(msg); cmd != nil {
			cmds = append(cmds, cmd)
		}
		if m.quit {
			return m, tea.Quit
		}
	}

	// Viewport handles its own scroll keys in normal mode.
	if !m.filterOn && !m.cfg.Input {
		var cmd tea.Cmd
		m.vp, cmd = m.vp.Update(msg)
		cmds = append(cmds, cmd)
		m.atBottom = m.vp.AtBottom()
	}

	return m, tea.Batch(cmds...)
}

func (m *LogStreamModel) handleKey(msg tea.KeyMsg) tea.Cmd {
	// Filter-entry mode: capture typing.
	if m.filterOn {
		switch msg.String() {
		case "enter", "esc":
			m.filterOn = false
			if msg.String() == "esc" {
				m.filter = ""
			}
			m.refresh()
		case "backspace":
			if m.filter != "" {
				m.filter = m.filter[:len(m.filter)-1]
				m.refresh()
			}
		default:
			if len(msg.String()) == 1 {
				m.filter += msg.String()
				m.refresh()
			}
		}
		return nil
	}

	// Console input mode: textinput owns the line.
	if m.cfg.Input {
		switch msg.String() {
		case "ctrl+c", "ctrl+q":
			m.quit = true
			return nil
		case "enter":
			line := strings.TrimSpace(m.input.Value())
			if line != "" && m.cfg.Submit != nil {
				m.cfg.Submit(line)
			}
			m.input.SetValue("")
			return nil
		case "pgup", "pgdown", "home", "end":
			var cmd tea.Cmd
			m.vp, cmd = m.vp.Update(msg)
			m.atBottom = m.vp.AtBottom()
			return cmd
		}
		var cmd tea.Cmd
		m.input, cmd = m.input.Update(msg)
		return cmd
	}

	// Normal (logs) mode.
	switch msg.String() {
	case "ctrl+c", "q":
		m.quit = true
	case "p":
		m.paused = !m.paused
		if !m.paused {
			// Flush held lines.
			for _, l := range m.pendBuf {
				m.buf = append(m.buf, l)
			}
			m.pendBuf = m.pendBuf[:0]
			m.capBuf()
			m.refresh()
		}
	case "/":
		m.filterOn = true
	case "end", "G":
		m.vp.GotoBottom()
		m.atBottom = true
	}
	return nil
}

// appendLine adds a line to the buffer (or the pending buffer while paused).
func (m *LogStreamModel) appendLine(line string) {
	if m.paused {
		m.pendBuf = append(m.pendBuf, line)
		return
	}
	m.buf = append(m.buf, line)
	m.capBuf()
	m.refresh()
}

func (m *LogStreamModel) capBuf() {
	if len(m.buf) > logStreamCap {
		m.buf = m.buf[len(m.buf)-logStreamCap:]
	}
}

// refresh re-renders the viewport content, applying the active filter, and
// keeps the view pinned to the bottom unless the user has scrolled up.
func (m *LogStreamModel) refresh() {
	if !m.ready {
		return
	}
	var visible []string
	if m.filter == "" {
		visible = m.buf
	} else {
		needle := strings.ToLower(m.filter)
		for _, l := range m.buf {
			if strings.Contains(strings.ToLower(stripANSI(l)), needle) {
				visible = append(visible, l)
			}
		}
	}
	wasBottom := m.atBottom
	m.vp.SetContent(strings.Join(visible, "\n"))
	if wasBottom {
		m.vp.GotoBottom()
	}
}

// layout sizes the viewport around the header and footer chrome. One line is
// always reserved for the filter / scrolled-indicator slot so the viewport
// height stays stable as that line appears and disappears.
func (m *LogStreamModel) layout() {
	headerH := len(m.cfg.Header)
	footerH := 1 + 1 // status bar + indicator slot
	if m.cfg.Input {
		footerH++ // input line
	}
	vpH := m.height - headerH - footerH
	if vpH < 1 {
		vpH = 1
	}
	if m.vp.Width == 0 {
		m.vp = viewport.New(m.width, vpH)
	} else {
		m.vp.Width = m.width
		m.vp.Height = vpH
	}
	m.input.Width = m.width - 4
	m.refresh()
}

func (m *LogStreamModel) View() string {
	if !m.ready {
		return "starting" + theme.Ellipsis()
	}
	var b strings.Builder

	for _, h := range m.cfg.Header {
		b.WriteString(h)
		b.WriteString("\n")
	}

	b.WriteString(m.vp.View())
	b.WriteString("\n")

	// Sticky indicator / filter line — always one row so the layout is stable.
	switch {
	case m.filterOn:
		b.WriteString(theme.StyleBrand().Render("/" + m.filter + "▏"))
	case m.filter != "":
		b.WriteString(theme.Hint("filter: ") + theme.StyleCyan().Render(m.filter) +
			theme.Hint("  ("+theme.Bullet()+" press / to edit, esc to clear)"))
	case !m.atBottom:
		b.WriteString(theme.StyleAmber().Render(theme.PauseGlyph() + " scrolled — press End to follow"))
	}
	b.WriteString("\n")

	if m.cfg.Input {
		b.WriteString(m.input.View())
		b.WriteString("\n")
	}

	b.WriteString(m.statusBar())
	return b.String()
}

func (m *LogStreamModel) statusBar() string {
	hints := []string{}
	if m.cfg.Input {
		hints = []string{"ctrl-q detach", "pgup/pgdn scroll"}
	} else {
		hints = []string{"/ filter", "p pause", "j/k scroll", "ctrl-c quit"}
	}
	if m.paused {
		hints = append([]string{theme.StyleAmber().Render("PAUSED")}, hints...)
	}
	return StatusBar(m.width, StatusBarCtx{
		Command:  m.cfg.Command,
		KeyHints: hints,
		Cluster:  m.cfg.Cluster,
		Version:  m.cfg.Version,
	})
}

// stripANSI removes ANSI escape sequences so the filter matches visible text.
func stripANSI(s string) string {
	var b strings.Builder
	inEsc := false
	for _, r := range s {
		if inEsc {
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') {
				inEsc = false
			}
			continue
		}
		if r == 0x1b {
			inEsc = true
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
