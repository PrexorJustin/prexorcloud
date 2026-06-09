package tui

import (
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss/v2"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// GroupInstance is one row of the INSTANCES table in the group-info view.
// Fields the real API doesn't serve yet (TPS, Mem, Match) are passed as "—".
type GroupInstance struct {
	Name    string
	Node    string
	Status  string
	Players string
	TPS     string
	Mem     string
	Uptime  string
	Match   string
}

// GroupInfoConfig configures the interactive group-info bubbletea model.
type GroupInfoConfig struct {
	Name      string
	Type      string // GAME / STATIC
	Status    string // UP / DRAIN / DOWN
	Cluster   string
	Version   string
	ConfigKV  [][2]string // CONFIG panel rows
	ScalingKV [][2]string // SCALING panel rows
	TemplKV   [][2]string // TEMPLATE panel rows
	Events    []string    // RECENT EVENTS lines (pre-rendered)
	Instances []GroupInstance

	OnDrain   func(id string) error          // `d` — drain/stop the selected instance
	OnRestart func(id string) error          // `r` — restart the selected instance
	Refresh   func() ([]GroupInstance, error) // re-fetch the instances table
}

// GroupInfoModel is the bubbletea model for `group info <name>`.
type GroupInfoModel struct {
	cfg      GroupInfoConfig
	sel      int
	width    int
	height   int
	ready    bool
	statusMsg string
	attachID string // set when the user pressed ↵ to attach
}

// NewGroupInfo builds the model.
func NewGroupInfo(cfg GroupInfoConfig) *GroupInfoModel {
	return &GroupInfoModel{cfg: cfg}
}

// Run runs the model and returns the instance id the user chose to attach to
// (empty if they quit without attaching).
func (m *GroupInfoModel) Run() (string, error) {
	p := tea.NewProgram(m, tea.WithAltScreen())
	_, err := p.Run()
	return m.attachID, err
}

func (m *GroupInfoModel) Init() tea.Cmd { return nil }

func (m *GroupInfoModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		m.ready = true

	case tea.KeyMsg:
		switch msg.String() {
		case "ctrl+c", "q":
			return m, tea.Quit
		case "up", "k":
			if m.sel > 0 {
				m.sel--
			}
		case "down", "j":
			if m.sel < len(m.cfg.Instances)-1 {
				m.sel++
			}
		case "enter":
			if id := m.selectedID(); id != "" {
				m.attachID = id
				return m, tea.Quit
			}
		case "d":
			if id := m.selectedID(); id != "" && m.cfg.OnDrain != nil {
				if err := m.cfg.OnDrain(id); err != nil {
					m.statusMsg = theme.StyleRed().Render("drain failed: " + err.Error())
				} else {
					m.statusMsg = theme.StyleGreen().Render(theme.Tick() + " draining " + id)
					m.reload()
				}
			}
		case "r":
			if id := m.selectedID(); id != "" && m.cfg.OnRestart != nil {
				if err := m.cfg.OnRestart(id); err != nil {
					m.statusMsg = theme.StyleRed().Render("restart failed: " + err.Error())
				} else {
					m.statusMsg = theme.StyleGreen().Render(theme.Tick() + " restarting " + id)
					m.reload()
				}
			}
		}
	}
	return m, nil
}

func (m *GroupInfoModel) selectedID() string {
	if m.sel >= 0 && m.sel < len(m.cfg.Instances) {
		return m.cfg.Instances[m.sel].Name
	}
	return ""
}

func (m *GroupInfoModel) reload() {
	if m.cfg.Refresh == nil {
		return
	}
	if insts, err := m.cfg.Refresh(); err == nil {
		m.cfg.Instances = insts
		if m.sel >= len(insts) {
			m.sel = len(insts) - 1
		}
		if m.sel < 0 {
			m.sel = 0
		}
	}
}

func (m *GroupInfoModel) View() string {
	if !m.ready {
		return "loading" + theme.Ellipsis()
	}
	var b strings.Builder

	// Title line.
	b.WriteString("  ")
	b.WriteString(theme.Heading(m.cfg.Name))
	b.WriteString("   ")
	b.WriteString(theme.StatusPill(m.cfg.Status))
	b.WriteString("   ")
	b.WriteString(theme.StyleDim().Render("group " + m.cfg.Type))
	b.WriteString("  " + theme.Bullet() + " ")
	b.WriteString(theme.StyleDim().Render("cluster ") + theme.Code(m.cfg.Cluster))
	b.WriteString("\n")
	b.WriteString("  " + theme.HRule(m.width-4))
	b.WriteString("\n\n")

	// Three panels side by side.
	cw := (m.width - 8) / 3
	if cw < 24 {
		cw = 24
	}
	c1 := Card("CONFIG", kvBody(m.cfg.ConfigKV), cw)
	c2 := Card("SCALING", kvBody(m.cfg.ScalingKV), cw)
	c3 := Card("TEMPLATE", kvBody(m.cfg.TemplKV), cw)
	b.WriteString(indentBlock(lipgloss.JoinHorizontal(lipgloss.Top, c1, "  ", c2, "  ", c3), 2))
	b.WriteString("\n\n")

	// INSTANCES table.
	b.WriteString("  " + lipgloss.NewStyle().Foreground(theme.Fg).Bold(true).Render("INSTANCES"))
	b.WriteString("  " + theme.StyleDim().Render("("+itoa(len(m.cfg.Instances))+")"))
	b.WriteString("\n")
	b.WriteString(m.instancesTable())
	b.WriteString("\n")

	// RECENT EVENTS.
	if len(m.cfg.Events) > 0 {
		b.WriteString("\n  " + lipgloss.NewStyle().Foreground(theme.Fg).Bold(true).Render("RECENT EVENTS"))
		b.WriteString("\n")
		for _, e := range m.cfg.Events {
			b.WriteString("  " + e + "\n")
		}
	}

	if m.statusMsg != "" {
		b.WriteString("\n  " + m.statusMsg + "\n")
	}

	// Fill to push the status bar to the bottom.
	rendered := b.String()
	gap := m.height - lipgloss.Height(rendered) - 1
	for i := 0; i < gap; i++ {
		rendered += "\n"
	}

	rendered += StatusBar(m.width, StatusBarCtx{
		Command:  "group info " + m.cfg.Name,
		KeyHints: []string{"↑↓ navigate", "↵ attach", "d drain", "r restart", "q quit"},
		Cluster:  m.cfg.Cluster,
		Version:  m.cfg.Version,
	})
	return rendered
}

func (m *GroupInfoModel) instancesTable() string {
	headers := []string{"NAME", "NODE", "STATUS", "PLAYERS", "TPS", "MEM", "UPTIME", "MATCH"}
	widths := []int{22, 12, 12, 9, 8, 7, 9, 12}

	var b strings.Builder
	// Header.
	b.WriteString("  ")
	for i, h := range headers {
		b.WriteString(theme.StyleDim().Bold(true).Render(padCell(h, widths[i])))
		b.WriteString(" ")
	}
	b.WriteString("\n  ")
	total := 0
	for _, w := range widths {
		total += w + 1
	}
	b.WriteString(theme.StyleFaint().Render(strings.Repeat(theme.BoxSharp().H, total)))
	b.WriteString("\n")

	selBg := lipgloss.NewStyle().Background(lipgloss.Color("#2a1840"))
	for i, in := range m.cfg.Instances {
		cells := []string{
			theme.Code(padCell(in.Name, widths[0])),
			theme.StyleDim().Render(padCell(in.Node, widths[1])),
			padCell(in.Status, widths[2]),
			theme.Number(padCell(in.Players, widths[3])),
			padCell(in.TPS, widths[4]),
			padCell(in.Mem, widths[5]),
			theme.StyleMute().Render(padCell(in.Uptime, widths[6])),
			theme.StyleDim().Render(padCell(in.Match, widths[7])),
		}
		// StatusPill is already styled; for the table keep it as a plain
		// uppercase token so columns stay grid-aligned.
		cells[2] = statusToken(in.Status, widths[2])
		row := "  " + strings.Join(cells, " ")
		if i == m.sel {
			row = selBg.Render(row)
		}
		b.WriteString(row)
		b.WriteString("\n")
	}
	if len(m.cfg.Instances) == 0 {
		b.WriteString("  " + theme.StyleMute().Render("no instances running"))
		b.WriteString("\n")
	}
	return b.String()
}

// statusToken renders a status as a colored plain token (no pill background)
// so the INSTANCES table stays grid-aligned.
func statusToken(status string, width int) string {
	up := strings.ToUpper(status)
	var st lipgloss.Style
	switch up {
	case "RUNNING", "UP", "ONLINE", "READY":
		st = theme.StyleGreen()
		up = theme.DotUp() + " " + up
	case "STARTING", "DRAIN", "DRAINING", "PENDING":
		st = theme.StyleAmber()
		up = theme.DotDrain() + " " + up
	case "STOPPED", "CRASHED", "DOWN", "FAILED":
		st = theme.StyleRed()
		up = theme.DotUp() + " " + up
	default:
		st = theme.StyleDim()
	}
	return st.Render(padCell(up, width))
}

// ── helpers ──────────────────────────────────────────────────────────────────

func kvBody(kv [][2]string) string {
	lines := make([]string, 0, len(kv))
	for _, p := range kv {
		lines = append(lines, theme.StyleMute().Render(padCell(p[0], 13))+" "+p[1])
	}
	return strings.Join(lines, "\n")
}

func indentBlock(s string, n int) string {
	pad := strings.Repeat(" ", n)
	lines := strings.Split(s, "\n")
	for i := range lines {
		lines[i] = pad + lines[i]
	}
	return strings.Join(lines, "\n")
}

func padCell(s string, width int) string {
	w := lipgloss.Width(s)
	if w > width {
		// Truncate visible runes (cells here never carry ANSI).
		runes := []rune(s)
		if len(runes) > width {
			return string(runes[:width])
		}
		return s
	}
	return s + strings.Repeat(" ", width-w)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
