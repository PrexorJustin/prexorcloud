package cmd

import (
	"fmt"
	"strings"

	"github.com/charmbracelet/huh"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var userCmd = &cobra.Command{
	Use:   "user",
	Short: "Manage users",
}

var userListCmd = &cobra.Command{
	Use:   "list",
	Short: "List users",
	RunE: func(cmd *cobra.Command, args []string) error {
		var users []map[string]any
		_, done, err := fetchList("/api/v1/users", nil, &users)
		if err != nil || done {
			return err
		}

		headers := []string{"ID", "USERNAME", "ROLE", "CREATED AT"}
		rows := make([][]string, 0, len(users))
		for _, u := range users {
			rows = append(rows, []string{
				theme.StyleMute().Render(str(u, "id")),
				theme.Code(str(u, "username")),
				theme.Pill(theme.PillBrand, str(u, "role")),
				theme.StyleMute().Render(str(u, "createdAt")),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing users", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("users", len(users)))
		return nil
	},
}

var userCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a user",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		username, _ := cmd.Flags().GetString("username")
		role, _ := cmd.Flags().GetString("role")

		password := ""
		if err := huh.NewInput().
			Title("Password").
			EchoMode(huh.EchoModePassword).
			Value(&password).
			Validate(func(s string) error {
				if s == "" {
					return fmt.Errorf("password is required")
				}
				return nil
			}).
			WithTheme(tui.HuhTheme()).
			Run(); err != nil {
			return err
		}

		var result map[string]any
		if err := client.Post("/api/v1/users", map[string]string{
			"username": username,
			"password": password,
			"role":     role,
		}, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("User '%s' created with role %s", username, role))
		return nil
	},
}

var userDeleteCmd = &cobra.Command{
	Use:   "delete <username>",
	Short: "Delete a user",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var confirm bool
		if err := huh.NewConfirm().
			Title(fmt.Sprintf("Delete user '%s'?", args[0])).
			Description("This action cannot be undone.").
			Value(&confirm).
			WithTheme(tui.HuhTheme()).
			Run(); err != nil {
			return err
		}
		if !confirm {
			fmt.Println("Cancelled.")
			return nil
		}

		if err := client.Delete("/api/v1/users/"+args[0], nil); err != nil {
			return err
		}

		theme.PrintSuccess("User '" + args[0] + "' deleted")
		return nil
	},
}

var roleCmd = &cobra.Command{
	Use:   "role",
	Short: "Manage roles",
}

var roleListCmd = &cobra.Command{
	Use:   "list",
	Short: "List roles",
	RunE: func(cmd *cobra.Command, args []string) error {
		var roles []map[string]any
		_, done, err := fetchList("/api/v1/roles", nil, &roles)
		if err != nil || done {
			return err
		}

		headers := []string{"NAME", "BUILT-IN", "PERMISSIONS"}
		rows := make([][]string, 0, len(roles))
		for _, r := range roles {
			perms := "-"
			if p, ok := r["permissions"].([]any); ok {
				parts := make([]string, len(p))
				for i, v := range p {
					parts[i] = fmt.Sprintf("%v", v)
				}
				perms = strings.Join(parts, ", ")
			}
			builtIn, _ := r["builtIn"].(bool)
			builtInStr := "no"
			if builtIn {
				builtInStr = "yes"
			}
			rows = append(rows, []string{
				theme.Code(str(r, "name")),
				theme.StyleDim().Render(builtInStr),
				theme.StyleDim().Render(perms),
			})
		}
		fmt.Println(tui.SimpleListHeader("Listing roles", shortHost(cfg.Resolve(flagController, flagContext))))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("roles", len(roles)))
		return nil
	},
}

var roleShowCmd = &cobra.Command{
	Use:   "show <name>",
	Short: "Show a single role with its full permission list",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var role map[string]any
		if err := client.Get("/api/v1/roles/"+args[0], &role); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(role)
		}
		fmt.Println(theme.Code(str(role, "name")))
		builtIn, _ := role["builtIn"].(bool)
		if builtIn {
			fmt.Println(theme.StyleDim().Render("(built-in role — cannot be modified or deleted)"))
		}
		fmt.Println()
		if perms, ok := role["permissions"].([]any); ok {
			fmt.Printf("Permissions (%d):\n", len(perms))
			for _, p := range perms {
				fmt.Println("  - " + fmt.Sprintf("%v", p))
			}
		}
		return nil
	},
}

var roleCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a custom role",
	Long: `Create a new custom role with a specific permission set.

Permissions are passed comma-separated. The full permission list is in
` + "`prexorctl role show ADMIN`" + ` (the built-in admin role provides every
permission as a reference). Pass an empty list to create a stub role and
fill it in later via ` + "`prexorctl role update`" + `.

Example:
  prexorctl role create --name viewer-readonly \
    --permissions groups.view,instances.view,nodes.view`,
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		name, _ := cmd.Flags().GetString("name")
		permsRaw, _ := cmd.Flags().GetString("permissions")

		perms := splitCSV(permsRaw)
		var result map[string]any
		body := map[string]any{
			"name":        name,
			"permissions": perms,
		}
		if err := client.Post("/api/v1/roles", body, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Role '%s' created with %d permission(s)", name, len(perms)))
		return nil
	},
}

var roleUpdateCmd = &cobra.Command{
	Use:   "update <name>",
	Short: "Update the permission set of an existing custom role",
	Long: `Replace the permission list on an existing custom role.

Built-in roles (ADMIN, OPERATOR, VIEWER) cannot be modified — the controller
rejects the request with a 422. Use ` + "`role create`" + ` to derive a custom
role from a built-in one.

Example:
  prexorctl role update viewer-readonly \
    --permissions groups.view,instances.view,nodes.view,crashes.view`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		permsRaw, _ := cmd.Flags().GetString("permissions")
		perms := splitCSV(permsRaw)

		var result map[string]any
		body := map[string]any{"permissions": perms}
		if err := client.Patch("/api/v1/roles/"+args[0], body, &result); err != nil {
			return err
		}
		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Role '%s' updated to %d permission(s)", args[0], len(perms)))
		return nil
	},
}

var roleDeleteCmd = &cobra.Command{
	Use:   "delete <name>",
	Short: "Delete a custom role",
	Long: `Permanently remove a custom role. Built-in roles cannot be deleted.

If any users still hold the role, the controller rejects the request with a
422. Re-assign those users first (` + "`prexorctl user list --role <name>`" + `).`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}
		var confirm bool
		if err := huh.NewConfirm().
			Title(fmt.Sprintf("Delete role '%s'?", args[0])).
			Description("This action cannot be undone. Built-in roles cannot be deleted.").
			Value(&confirm).
			WithTheme(tui.HuhTheme()).
			Run(); err != nil {
			return err
		}
		if !confirm {
			fmt.Println("Cancelled.")
			return nil
		}
		if err := client.Delete("/api/v1/roles/"+args[0], nil); err != nil {
			return err
		}
		theme.PrintSuccess("Role '" + args[0] + "' deleted")
		return nil
	},
}

// splitCSV is the shared helper for `--permissions a,b,c` style flags. Empty
// segments are dropped so trailing commas and double commas don't trip
// permission validation server-side.
func splitCSV(s string) []string {
	if strings.TrimSpace(s) == "" {
		return []string{}
	}
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func init() {
	userCreateCmd.Flags().String("username", "", "Username (required)")
	userCreateCmd.Flags().String("role", "VIEWER", "Role (ADMIN, OPERATOR, VIEWER)")
	_ = userCreateCmd.MarkFlagRequired("username")

	userCmd.AddCommand(userListCmd, userCreateCmd, userDeleteCmd)

	roleCreateCmd.Flags().String("name", "", "Role name (required)")
	roleCreateCmd.Flags().String("permissions", "", "Comma-separated permission list (e.g. groups.view,instances.start)")
	_ = roleCreateCmd.MarkFlagRequired("name")

	roleUpdateCmd.Flags().String("permissions", "", "Comma-separated permission list (replaces the existing set)")
	_ = roleUpdateCmd.MarkFlagRequired("permissions")

	roleCmd.AddCommand(roleListCmd, roleShowCmd, roleCreateCmd, roleUpdateCmd, roleDeleteCmd)
	// Role is added to root in root.go init
}
