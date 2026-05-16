package cmd

import (
	"errors"
	"fmt"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

// shareRequest mirrors controller/rest/dto/ShareRequestDto and ShareLogRequestDto.
// Fields are pointers / strings so omitempty leaves them out of the wire body
// and the controller falls back to its configured defaults.
type shareRequest struct {
	Level         string `json:"level,omitempty"`
	Logger        string `json:"logger,omitempty"`
	Limit         int    `json:"limit,omitempty"`
	Expiry        string `json:"expiry,omitempty"`
	IsPrivate     *bool  `json:"isPrivate,omitempty"`
	BurnAfterRead *bool  `json:"burnAfterRead,omitempty"`
}

// shareResult mirrors controller/rest/dto/ShareResultDto.
type shareResult struct {
	URL           string `json:"url"`
	RawURL        string `json:"rawUrl"`
	ExpiresAt     string `json:"expiresAt"`
	IsPrivate     bool   `json:"isPrivate"`
	BurnAfterRead bool   `json:"burnAfterRead"`
	DeleteToken   string `json:"deleteToken,omitempty"`
	DeleteURL     string `json:"deleteUrl,omitempty"`
}

// registerShareFlags attaches the uniform --share / --expiry / --public /
// --burn-after-read flag set to the given cobra command.
func registerShareFlags(cmd *cobra.Command) {
	cmd.Flags().Bool("share", false, "Upload a redacted copy to the configured paste service and print the link")
	cmd.Flags().String("expiry", "", "Paste expiry preset: 1h | 1d | 30d | never")
	cmd.Flags().Bool("public", false, "Mark the paste public (overrides share.defaultPrivate=true)")
	cmd.Flags().Bool("burn-after-read", false, "Destroy the paste on first read")
}

// shareFlags extracts the uniform share-flag set from a cobra command.
type shareFlags struct {
	enabled       bool
	expiry        string
	visibility    *bool
	burnAfterRead *bool
}

func readShareFlags(cmd *cobra.Command) shareFlags {
	enabled, _ := cmd.Flags().GetBool("share")
	expiry, _ := cmd.Flags().GetString("expiry")
	out := shareFlags{enabled: enabled, expiry: expiry}
	if cmd.Flags().Changed("public") {
		pub, _ := cmd.Flags().GetBool("public")
		v := !pub
		out.visibility = &v
	}
	if cmd.Flags().Changed("burn-after-read") {
		b, _ := cmd.Flags().GetBool("burn-after-read")
		out.burnAfterRead = &b
	}
	return out
}

func (f shareFlags) toRequest(level, loggerPrefix string, limit int) shareRequest {
	return shareRequest{
		Level:         level,
		Logger:        loggerPrefix,
		Limit:         limit,
		Expiry:        f.expiry,
		IsPrivate:     f.visibility,
		BurnAfterRead: f.burnAfterRead,
	}
}

// runShare POSTs the share request and renders the result. Returns nil and
// short-circuits the caller's normal output path.
func runShare(client *api.Client, path string, body shareRequest, kind string) error {
	var result shareResult
	if err := client.Post(path, body, &result); err != nil {
		return mapShareError(err)
	}
	if flagJSON {
		return theme.PrintJSON(result)
	}
	theme.PrintSuccess(fmt.Sprintf("Shared %s → %s", kind, result.URL))
	if result.ExpiresAt != "" {
		fmt.Println(theme.StyleDim().Render("expires: " + result.ExpiresAt))
	}
	if result.BurnAfterRead {
		fmt.Println(theme.StyleAmber().Render("burn-after-read enabled — link can only be opened once"))
	}
	if result.DeleteURL != "" {
		fmt.Println(theme.StyleDim().Render("revoke:  " + result.DeleteURL))
	}
	return nil
}

// mapShareError converts the controller's structured error responses into
// human-friendly CLI messages.
func mapShareError(err error) error {
	if err == nil {
		return nil
	}
	msg := err.Error()
	switch {
	case strings.Contains(msg, "SHARE_DISABLED") || strings.Contains(msg, "409"):
		return errors.New("sharing is not configured on this controller (share.enabled=false)")
	case strings.Contains(msg, "PASTE_UPSTREAM_ERROR") || strings.Contains(msg, "502"):
		return fmt.Errorf("paste service unreachable: %w", err)
	default:
		return err
	}
}
