package cmd

import (
	"fmt"

	"github.com/charmbracelet/huh"
	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var loginCmd = &cobra.Command{
	Use:   "login",
	Short: "Authenticate with a PrexorCloud controller",
	RunE: func(cmd *cobra.Command, args []string) error {
		controller := cfg.Resolve(flagController, flagContext)
		username := ""
		password := ""

		// Build the form dynamically: include controller URL only if not configured.
		var fields []huh.Field
		if controller == "" {
			fields = append(fields,
				huh.NewInput().
					Title("Controller URL").
					Placeholder("http://localhost:8080").
					Value(&controller).
					Validate(func(s string) error {
						if s == "" {
							return fmt.Errorf("controller URL is required")
						}
						return nil
					}),
			)
		}
		fields = append(fields,
			huh.NewInput().
				Title("Username").
				Value(&username).
				Validate(func(s string) error {
					if s == "" {
						return fmt.Errorf("username is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Password").
				EchoMode(huh.EchoModePassword).
				Value(&password).
				Validate(func(s string) error {
					if s == "" {
						return fmt.Errorf("password is required")
					}
					return nil
				}),
		)

		fmt.Println()
		fmt.Println(theme.Heading("Sign in to PrexorCloud"))
		fmt.Println(theme.Subtitle("Enter your controller URL and credentials below."))
		fmt.Println()

		if err := huh.NewForm(huh.NewGroup(fields...)).WithTheme(tui.HuhTheme()).Run(); err != nil {
			return err
		}

		client := api.New(controller, "", flagVerbose)
		var resp struct {
			Token string `json:"token"`
		}
		if err := client.Post("/api/v1/auth/login", map[string]string{
			"username": username,
			"password": password,
		}, &resp); err != nil {
			return fmt.Errorf("login failed: %w", err)
		}

		cfg.SetCurrentAuth(controller, resp.Token)
		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}

		theme.PrintSuccess(fmt.Sprintf("Logged in to %s as %s", controller, username))
		return nil
	},
}

var logoutCmd = &cobra.Command{
	Use:   "logout",
	Short: "Remove stored authentication",
	RunE: func(cmd *cobra.Command, args []string) error {
		if ctx := cfg.ContextRef(flagContext); ctx != nil {
			ctx.Token = ""
		}
		if err := cfg.Save(); err != nil {
			return fmt.Errorf("save config: %w", err)
		}
		theme.PrintSuccess("Logged out")
		return nil
	},
}
