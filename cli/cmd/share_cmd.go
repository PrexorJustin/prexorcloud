package cmd

import (
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

// Top-level `prexorctl share` parent — list / view / revoke persisted shares.
// The `--share` flag on `crash info`, `logs controller|daemon`, `diagnostics
// bundle`, `instance console` is the *invocation* side; this command tree is
// the operator-facing reverse side that lets you audit and nuke past shares.

var shareCmd = &cobra.Command{
	Use:     "share",
	Short:   "List, view and revoke past paste shares",
	Aliases: []string{"shares"},
}

type shareRecord struct {
	ID            string `json:"id"`
	Kind          string `json:"kind"`
	ResourceID    string `json:"resourceId"`
	URL           string `json:"url"`
	RawURL        string `json:"rawUrl"`
	ExpiresAt     string `json:"expiresAt"`
	BurnAfterRead bool   `json:"burnAfterRead"`
	IsPrivate     bool   `json:"isPrivate"`
	SizeBytes     int64  `json:"sizeBytes"`
	SharedByUser  string `json:"sharedByUser"`
	SharedAt      string `json:"sharedAt"`
	RevokedAt     string `json:"revokedAt,omitempty"`
	Revocable     bool   `json:"revocable"`
}

type shareListPage struct {
	Data     []shareRecord `json:"data"`
	Total    int           `json:"total"`
	Page     int           `json:"page"`
	PageSize int           `json:"pageSize"`
}

var shareListCmd = &cobra.Command{
	Use:   "list",
	Short: "List recent paste shares (newest first)",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		kind, _ := cmd.Flags().GetString("kind")
		activeOnly, _ := cmd.Flags().GetBool("active-only")
		limit, _ := cmd.Flags().GetInt("limit")
		params := map[string]string{}
		if kind != "" {
			params["kind"] = strings.ToUpper(kind)
		}
		if activeOnly {
			params["activeOnly"] = "true"
		}
		if limit > 0 {
			params["pageSize"] = fmt.Sprintf("%d", limit)
		}
		var page shareListPage
		if err := client.GetWithQuery("/api/v1/shares", params, &page); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(page)
		}
		if len(page.Data) == 0 {
			theme.PrintWarn("No shares found.")
			return nil
		}
		headers := []string{"ID", "KIND", "WHEN", "BY", "BYTES", "URL", "STATUS"}
		rows := make([][]string, 0, len(page.Data))
		for _, r := range page.Data {
			status := "active"
			if r.RevokedAt != "" {
				status = theme.StyleDim().Render("revoked")
			} else if !r.Revocable {
				status = theme.StyleDim().Render("non-revocable")
			}
			rows = append(rows, []string{
				shortID(r.ID),
				r.Kind,
				humanWhen(r.SharedAt),
				r.SharedByUser,
				fmt.Sprintf("%d", r.SizeBytes),
				r.URL,
				status,
			})
		}
		tui.PrintTable(headers, rows)
		return nil
	},
}

var shareViewCmd = &cobra.Command{
	Use:   "view <id>",
	Short: "View a single paste share",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var record shareRecord
		if err := client.Get("/api/v1/shares/"+url.PathEscape(args[0]), &record); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(record)
		}
		theme.PrintTitle("Share " + record.ID)
		fmt.Println("kind:      " + record.Kind)
		if record.ResourceID != "" {
			fmt.Println("resource:  " + record.ResourceID)
		}
		fmt.Println("url:       " + record.URL)
		fmt.Println("rawUrl:    " + record.RawURL)
		fmt.Println("by:        " + record.SharedByUser)
		fmt.Println("when:      " + record.SharedAt)
		if record.ExpiresAt != "" {
			fmt.Println("expires:   " + record.ExpiresAt)
		}
		fmt.Println("bytes:     " + fmt.Sprintf("%d", record.SizeBytes))
		if record.BurnAfterRead {
			fmt.Println(theme.StyleAmber().Render("burn-after-read: enabled (single-read link)"))
		}
		if record.RevokedAt != "" {
			fmt.Println(theme.StyleDim().Render("revoked at: " + record.RevokedAt))
		} else if record.Revocable {
			fmt.Println(theme.StyleDim().Render("revocable via: prexorctl share revoke " + record.ID))
		} else {
			fmt.Println(theme.StyleDim().Render("non-revocable (no delete token captured)"))
		}
		return nil
	},
}

var shareRevokeCmd = &cobra.Command{
	Use:   "revoke <id>",
	Short: "Revoke a paste share (calls pste DELETE and marks the record revoked)",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		yes, _ := cmd.Flags().GetBool("yes")
		if !yes {
			var existing shareRecord
			if err := client.Get("/api/v1/shares/"+url.PathEscape(args[0]), &existing); err != nil {
				return err
			}
			fmt.Printf("About to revoke %s share %s shared by %s at %s\n",
				existing.Kind, existing.ID, existing.SharedByUser, existing.SharedAt)
			fmt.Print("Type yes to confirm: ")
			var answer string
			_, _ = fmt.Scanln(&answer)
			if strings.TrimSpace(strings.ToLower(answer)) != "yes" {
				return fmt.Errorf("revoke aborted")
			}
		}
		var revoked shareRecord
		if err := client.Post("/api/v1/shares/"+url.PathEscape(args[0])+"/revoke", nil, &revoked); err != nil {
			return mapShareError(err)
		}
		if flagJSON {
			return theme.PrintJSON(revoked)
		}
		theme.PrintSuccess(fmt.Sprintf("Revoked share %s (%s)", revoked.ID, revoked.Kind))
		if revoked.RevokedAt != "" {
			fmt.Println(theme.StyleDim().Render("revoked at: " + revoked.RevokedAt))
		}
		return nil
	},
}

func init() {
	shareListCmd.Flags().String("kind", "", "Filter by surface: CRASH | CONTROLLER_LOGS | DAEMON_LOGS | DIAGNOSTICS | INSTANCE_CONSOLE")
	shareListCmd.Flags().Bool("active-only", false, "Hide revoked entries")
	shareListCmd.Flags().Int("limit", 50, "Max rows to return (server caps at 200)")
	shareRevokeCmd.Flags().BoolP("yes", "y", false, "Skip confirmation prompt")

	shareCmd.AddCommand(shareListCmd, shareViewCmd, shareRevokeCmd)
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8]
}

func humanWhen(iso string) string {
	if iso == "" {
		return ""
	}
	t, err := time.Parse(time.RFC3339Nano, iso)
	if err != nil {
		return iso
	}
	delta := time.Since(t)
	switch {
	case delta < time.Minute:
		return "just now"
	case delta < time.Hour:
		return fmt.Sprintf("%dm ago", int(delta.Minutes()))
	case delta < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(delta.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(delta.Hours()/24))
	}
}
