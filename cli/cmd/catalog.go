package cmd

import (
	"fmt"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var catalogCmd = &cobra.Command{
	Use:   "catalog",
	Short: "Manage the server platform catalog",
	Long: "Manage the platform catalog the scheduler pulls server/proxy jars from.\n" +
		"Each entry is a platform (e.g. PAPER, VELOCITY) with one or more versions,\n" +
		"each carrying a downloadUrl and optional sha256 checksum.",
}

var catalogListCmd = &cobra.Command{
	Use:   "list",
	Short: "List catalog platforms and versions",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		var entries []api.CatalogEntry
		if err := client.GetList("/api/v1/catalog", nil, &entries); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(entries)
		}

		headers := []string{"PLATFORM", "CATEGORY", "VERSION", "REC", "SHA256", "DOWNLOAD URL"}
		rows := make([][]string, 0, len(entries))
		recommended := 0
		for _, e := range entries {
			rec := ""
			if e.Recommended {
				rec = "★"
				recommended++
			}
			rows = append(rows, []string{
				theme.Code(e.Platform),
				e.Category,
				theme.Code(e.Version),
				rec,
				theme.StyleMute().Render(truncateMiddle(e.SHA256, 12)),
				theme.StyleMute().Render(truncateMiddle(e.DownloadURL, 48)),
			})
		}

		fmt.Println(tui.ListHeader("Listing catalog in cluster",
			shortHost(cfg.Resolve(flagController, flagContext)), "", ""))
		fmt.Println()
		tui.PrintTable(headers, rows)
		fmt.Println(tui.ListFooter("versions", len(entries),
			fmt.Sprintf("%d recommended", recommended)))
		return nil
	},
}

var catalogAddCmd = &cobra.Command{
	Use:   "add <platform>",
	Short: "Add a version to a platform (creates the platform if new)",
	Args:  cobra.ExactArgs(1),
	Example: "  prexorctl catalog add PAPER --version 1.21 \\\n" +
		"    --url https://api.papermc.io/v2/projects/paper/versions/1.21/builds/130/downloads/paper-1.21-130.jar \\\n" +
		"    --sha256 ab9bb1af... --recommended",
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		platform := args[0]
		flags := cmd.Flags()
		version, _ := flags.GetString("version")
		url, _ := flags.GetString("url")
		sha256, _ := flags.GetString("sha256")
		category, _ := flags.GetString("category")
		configFormat, _ := flags.GetString("config-format")
		recommended, _ := flags.GetBool("recommended")

		body := map[string]any{
			"version":      version,
			"downloadUrl":  url,
			"sha256":       sha256,
			"category":     category,
			"configFormat": configFormat,
		}

		var result map[string]any
		if err := client.Post(fmt.Sprintf("/api/v1/catalog/%s/versions", platform), body, &result); err != nil {
			return err
		}
		if recommended {
			if err := client.Put(fmt.Sprintf("/api/v1/catalog/%s/versions/%s/recommended", platform, version), nil, nil); err != nil {
				return fmt.Errorf("version added but marking it recommended failed: %w", err)
			}
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Added %s %s to the catalog", platform, version))
		return nil
	},
}

var catalogUpdateCmd = &cobra.Command{
	Use:   "update [platform] [version]",
	Short: "Update a version's download URL / checksum (and optionally rename it)",
	Args:  cobra.MaximumNArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		platform, version, err := resolveCatalogTarget(client, args, "update")
		if err != nil {
			return err
		}
		flags := cmd.Flags()
		url, _ := flags.GetString("url")
		sha256, _ := flags.GetString("sha256")
		newVersion, _ := flags.GetString("new-version")
		if newVersion == "" {
			newVersion = version
		}

		body := map[string]any{
			"version":     newVersion,
			"downloadUrl": url,
			"sha256":      sha256,
		}

		var result map[string]any
		if err := client.Patch(fmt.Sprintf("/api/v1/catalog/%s/versions/%s", platform, version), body, &result); err != nil {
			return err
		}

		if flagJSON {
			return theme.PrintJSON(result)
		}
		theme.PrintSuccess(fmt.Sprintf("Updated %s %s", platform, newVersion))
		return nil
	},
}

var catalogRecommendCmd = &cobra.Command{
	Use:   "recommend [platform] [version]",
	Short: "Mark a version as the recommended one for its platform",
	Args:  cobra.MaximumNArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		platform, version, err := resolveCatalogTarget(client, args, "recommend")
		if err != nil {
			return err
		}
		if err := client.Put(fmt.Sprintf("/api/v1/catalog/%s/versions/%s/recommended", platform, version), nil, nil); err != nil {
			return err
		}
		theme.PrintSuccess(fmt.Sprintf("%s %s is now the recommended version", platform, version))
		return nil
	},
}

var catalogRemoveCmd = &cobra.Command{
	Use:     "remove [platform] [version]",
	Aliases: []string{"rm", "delete"},
	Short:   "Remove a version from the catalog",
	Args:    cobra.MaximumNArgs(2),
	RunE: func(cmd *cobra.Command, args []string) error {
		client, err := requireAuth()
		if err != nil {
			return err
		}

		platform, version, err := resolveCatalogTarget(client, args, "remove")
		if err != nil {
			return err
		}
		if err := client.Delete(fmt.Sprintf("/api/v1/catalog/%s/versions/%s", platform, version), nil); err != nil {
			return err
		}
		theme.PrintSuccess(fmt.Sprintf("Removed %s %s from the catalog", platform, version))
		return nil
	},
}

// resolveCatalogTarget resolves a (platform, version) pair from args, falling
// back to interactive pickers for whichever piece is missing (TTY only).
func resolveCatalogTarget(client *api.Client, args []string, verb string) (string, string, error) {
	var platform, version string
	if len(args) >= 1 {
		platform = args[0]
	} else {
		if !interactive() {
			return "", "", fmt.Errorf("platform required (e.g. `prexorctl catalog %s <platform> <version>`)", verb)
		}
		p, err := pickCatalogPlatform(client, "Select a platform")
		if err != nil {
			return "", "", err
		}
		platform = p
	}
	if len(args) >= 2 {
		version = args[1]
	} else {
		if !interactive() {
			return "", "", fmt.Errorf("version required (e.g. `prexorctl catalog %s %s <version>`)", verb, platform)
		}
		v, err := pickCatalogVersion(client, platform, fmt.Sprintf("Select a %s version", platform))
		if err != nil {
			return "", "", err
		}
		version = v
	}
	return platform, version, nil
}

// truncateMiddle shortens long values (sha256, URLs) for table display, keeping
// the start and end so they stay recognizable.
func truncateMiddle(s string, max int) string {
	if max < 5 || len(s) <= max {
		return s
	}
	keep := (max - 1) / 2
	return s[:keep] + "…" + s[len(s)-keep:]
}

func init() {
	catalogAddCmd.Flags().String("version", "", "Version string, e.g. 1.21 (required)")
	catalogAddCmd.Flags().String("url", "", "Direct download URL for the jar (required)")
	catalogAddCmd.Flags().String("sha256", "", "Optional SHA-256 checksum of the jar")
	catalogAddCmd.Flags().String("category", "SERVER", "Catalog category: SERVER or PROXY")
	catalogAddCmd.Flags().String("config-format", "", "Config format hint used to scaffold the base template (e.g. paper, velocity)")
	catalogAddCmd.Flags().Bool("recommended", false, "Mark this version as the platform's recommended version")
	_ = catalogAddCmd.MarkFlagRequired("version")
	_ = catalogAddCmd.MarkFlagRequired("url")

	catalogUpdateCmd.Flags().String("url", "", "New download URL")
	catalogUpdateCmd.Flags().String("sha256", "", "New SHA-256 checksum")
	catalogUpdateCmd.Flags().String("new-version", "", "Rename the version to this value")

	catalogCmd.AddCommand(catalogListCmd, catalogAddCmd, catalogUpdateCmd, catalogRecommendCmd, catalogRemoveCmd)
}
