package cmd

import (
	"fmt"
	"runtime"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/prexorcloud/prexorctl/internal/tui"
	"github.com/spf13/cobra"
)

var cliVersion = "dev"

// SetVersion sets the CLI version from main (injected via ldflags).
func SetVersion(v string) {
	cliVersion = v
}

var versionCmd = &cobra.Command{
	Use:   "version",
	Short: "Show CLI and controller version",
	RunE: func(cmd *cobra.Command, args []string) error {
		if flagJSON {
			info := map[string]string{
				"cli":  cliVersion,
				"go":   runtime.Version(),
				"os":   runtime.GOOS,
				"arch": runtime.GOARCH,
			}
			client, err := newClient()
			if err == nil && client.Token != "" {
				var serverVersion map[string]any
				if err := client.Get("/api/v1/system/version", &serverVersion); err == nil {
					for k, v := range serverVersion {
						info["controller_"+k] = fmt.Sprintf("%v", v)
					}
				}
			}
			return theme.PrintJSON(info)
		}

		fmt.Println()
		fmt.Println(theme.Heading("PrexorCloud CLI"))
		fmt.Println(theme.Subtitle("Operator command-line for the PrexorCloud control plane."))
		fmt.Println()

		cliBody := strings.Join([]string{
			padRight(theme.StyleMute().Render("version"), 16) + theme.Number(cliVersion),
			padRight(theme.StyleMute().Render("go"), 16) + theme.StyleDim().Render(runtime.Version()),
			padRight(theme.StyleMute().Render("os/arch"), 16) + theme.StyleDim().Render(fmt.Sprintf("%s/%s", runtime.GOOS, runtime.GOARCH)),
		}, "\n")
		fmt.Println(tui.Card("CLI", cliBody, 48))

		client, err := newClient()
		if err == nil && client.Token != "" {
			var serverVersion map[string]any
			if err := client.Get("/api/v1/system/version", &serverVersion); err == nil {
				lines := make([]string, 0, len(serverVersion))
				for k, v := range serverVersion {
					lines = append(lines, padRight(theme.StyleMute().Render(k), 16)+theme.StyleDim().Render(fmt.Sprintf("%v", v)))
				}
				fmt.Println()
				fmt.Println(tui.Card("CONTROLLER", strings.Join(lines, "\n"), 48))
			}
		}
		fmt.Println()
		return nil
	},
}
