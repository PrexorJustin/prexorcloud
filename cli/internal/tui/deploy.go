package tui

import (
	"fmt"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// DeployStatus is one poll of a deployment's rollout state, mapped from the
// controller's DeploymentRecord.
type DeployStatus struct {
	Revision    int
	Strategy    string
	State       string // IN_PROGRESS / PAUSED / COMPLETED / FAILED / ROLLED_BACK
	Updated     int    // instances rolled so far
	Total       int    // instances to roll
	CreatedAt   string
	CompletedAt string
}

// Terminal reports whether the rollout has reached a state that won't change.
func (s DeployStatus) Terminal() bool {
	switch strings.ToUpper(s.State) {
	case "COMPLETED", "FAILED", "ROLLED_BACK", "CANCELLED":
		return true
	}
	return false
}

// DeployConfig configures the rollout-progress bubbletea view.
type DeployConfig struct {
	Group   string
	Cluster string
	Version string
	Canary  int                          // canary instance count (0 = none)
	Image   string                       // target image, for the header
	Poll    func() (DeployStatus, error) // polled ~every 400ms until terminal
}

type deployTickMsg struct{}

// DeployModel renders a live rollout. It is intentionally inline (no alt
// screen) so the final ROLLOUT COMPLETE block stays in the user's scrollback;
// the partial progress bar is overwritten in place, never left behind.
type DeployModel struct {
	cfg     DeployConfig
	status  DeployStatus
	err     error
	started time.Time
	width   int
	canaryGreen bool
	done    bool
}

// NewDeploy builds the rollout view.
func NewDeploy(cfg DeployConfig) *DeployModel {
	return &DeployModel{cfg: cfg, started: time.Now(), width: 80}
}

// Run renders the rollout until it reaches a terminal state.
func (m *DeployModel) Run() error {
	p := tea.NewProgram(m)
	_, err := p.Run()
	if err != nil {
		return err
	}
	return m.err
}

func (m *DeployModel) Init() tea.Cmd { return m.tick() }

func (m *DeployModel) tick() tea.Cmd {
	return tea.Tick(400*time.Millisecond, func(time.Time) tea.Msg { return deployTickMsg{} })
}

func (m *DeployModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
	case tea.KeyMsg:
		if msg.String() == "ctrl+c" {
			return m, tea.Quit
		}
	case deployTickMsg:
		st, err := m.cfg.Poll()
		if err != nil {
			m.err = err
			return m, tea.Quit
		}
		m.status = st
		if m.cfg.Canary > 0 && st.Updated >= m.cfg.Canary {
			m.canaryGreen = true
		}
		if st.Terminal() {
			m.done = true
			return m, tea.Quit
		}
		return m, m.tick()
	}
	return m, nil
}

func (m *DeployModel) View() string {
	var b strings.Builder

	// PLAN / header line.
	b.WriteString(fmt.Sprintf("  %s  group %s  %s strategy %s  %s rev %s",
		theme.StyleBrand().Bold(true).Render(theme.BrandGlyph()+" ROLLOUT"),
		theme.Code(m.cfg.Group),
		theme.Bullet(),
		theme.Code(strings.ToLower(orDash(m.status.Strategy, m.cfg.Strategyish()))),
		theme.Bullet(),
		theme.Number(fmt.Sprintf("r%d", m.status.Revision)),
	))
	if m.cfg.Image != "" {
		b.WriteString("  " + theme.Bullet() + " " + theme.StyleGreen().Render(m.cfg.Image))
	}
	b.WriteString("\n")
	b.WriteString("  " + theme.HRule(min(m.width-4, 92)))
	b.WriteString("\n\n")

	total := m.status.Total
	updated := m.status.Updated

	if m.done {
		b.WriteString(m.summary())
		return b.String()
	}

	// Canary phase, then main rollout.
	barW := min(m.width-40, 48)
	if barW < 10 {
		barW = 10
	}

	if m.cfg.Canary > 0 && !m.canaryGreen {
		b.WriteString(fmt.Sprintf("  %s  %s\n\n",
			theme.StyleBrand().Bold(true).Render("CANARY"),
			theme.StyleDim().Render(fmt.Sprintf("rolling out %d instance(s) for healthcheck", m.cfg.Canary)),
		))
		ratio := ratioOf(updated, m.cfg.Canary)
		b.WriteString(m.barLine("canary", ratio, barW))
	} else {
		if m.cfg.Canary > 0 {
			b.WriteString(fmt.Sprintf("  %s  %s\n\n",
				theme.StyleBrand().Bold(true).Render("ROLLOUT"),
				theme.StyleDim().Render("canary green — rolling the remaining batches"),
			))
		} else {
			b.WriteString(fmt.Sprintf("  %s  %s\n\n",
				theme.StyleBrand().Bold(true).Render("ROLLOUT"),
				theme.StyleDim().Render("rolling instances in batches"),
			))
		}
		ratio := ratioOf(updated, total)
		b.WriteString(m.barLine(m.cfg.Group, ratio, barW))
	}

	b.WriteString("\n\n")
	state := strings.ToUpper(orDash(m.status.State, "PENDING"))
	b.WriteString(fmt.Sprintf("  %s %s  %s  elapsed %s\n",
		theme.StyleDim().Render("state"),
		theme.StatusPill(state),
		theme.StyleMute().Render(fmt.Sprintf("%d/%d instances", updated, total)),
		theme.Number(fmtElapsed(time.Since(m.started))),
	))
	b.WriteString("\n  " + theme.Hint("Ctrl-C to stop watching (the rollout continues server-side)"))
	return b.String()
}

func (m *DeployModel) barLine(label string, ratio float64, barW int) string {
	pct := fmt.Sprintf("%3d%%", int(ratio*100))
	return fmt.Sprintf("  %s  %s %s  %s",
		theme.StyleMute().Render("  "+theme.BoxRound().BL+theme.BoxRound().H),
		theme.FG(theme.Fg).Render(padCell(label, 18)),
		ProgressBar(barW, ratio),
		theme.StyleCyan().Render(pct),
	)
}

func (m *DeployModel) summary() string {
	var b strings.Builder
	state := strings.ToUpper(m.status.State)
	dur := time.Since(m.started)
	if m.status.CreatedAt != "" && m.status.CompletedAt != "" {
		if d, ok := parseDuration(m.status.CreatedAt, m.status.CompletedAt); ok {
			dur = d
		}
	}

	switch state {
	case "COMPLETED":
		b.WriteString(fmt.Sprintf("  %s  group %s  %s %d/%d instances rolled\n",
			theme.StatusPill("ROLLOUT COMPLETE"),
			theme.Code(m.cfg.Group),
			theme.Bullet(),
			m.status.Updated, m.status.Total,
		))
		b.WriteString(fmt.Sprintf("    %s  %s    %s  %s\n",
			theme.StyleDim().Render("duration"), theme.Number(fmtElapsed(dur)),
			theme.StyleDim().Render("revision"), theme.Number(fmt.Sprintf("r%d", m.status.Revision)),
		))
		b.WriteString("\n  " + theme.Hint("view rollout details: "+
			theme.Code(fmt.Sprintf("prexorctl deploy show %s %d", m.cfg.Group, m.status.Revision))))
	case "FAILED", "ROLLED_BACK", "CANCELLED":
		label := "ROLLOUT FAILED"
		if state == "ROLLED_BACK" {
			label = "ROLLOUT ROLLED BACK"
		}
		b.WriteString(fmt.Sprintf("  %s  group %s  %s %d/%d instances rolled before stopping\n",
			theme.Pill(theme.PillRed, theme.DotUp()+" "+label),
			theme.Code(m.cfg.Group),
			theme.Bullet(),
			m.status.Updated, m.status.Total,
		))
		b.WriteString("\n  " + theme.Hint("inspect: "+
			theme.Code(fmt.Sprintf("prexorctl deploy show %s %d", m.cfg.Group, m.status.Revision))))
	}
	b.WriteString("\n")
	return b.String()
}

// Strategyish returns a fallback strategy label when the first poll hasn't
// landed yet.
func (c DeployConfig) Strategyish() string { return "rolling" }

// ── helpers ──────────────────────────────────────────────────────────────────

func ratioOf(n, d int) float64 {
	if d <= 0 {
		return 0
	}
	r := float64(n) / float64(d)
	if r > 1 {
		return 1
	}
	if r < 0 {
		return 0
	}
	return r
}

func orDash(s, fallback string) string {
	if strings.TrimSpace(s) == "" {
		return fallback
	}
	return s
}

func fmtElapsed(d time.Duration) string {
	d = d.Round(time.Second)
	m := int(d.Minutes())
	s := int(d.Seconds()) % 60
	if m == 0 {
		return fmt.Sprintf("%ds", s)
	}
	return fmt.Sprintf("%dm %02ds", m, s)
}

// parseDuration returns the gap between two RFC3339 timestamps.
func parseDuration(from, to string) (time.Duration, bool) {
	t1, err1 := time.Parse(time.RFC3339, from)
	t2, err2 := time.Parse(time.RFC3339, to)
	if err1 != nil || err2 != nil {
		return 0, false
	}
	return t2.Sub(t1), true
}
