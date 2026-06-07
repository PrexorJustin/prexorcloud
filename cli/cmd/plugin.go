package cmd

import (
	"fmt"
	"os"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/scaffold"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var pluginCmd = &cobra.Command{
	Use:   "plugin",
	Short: "Author standalone @CloudPlugin jars (Path A)",
	Long: `Tooling for the standalone @CloudPlugin path.

A "plugin" here is a single-platform jar that drops into a Paper / Spigot /
Folia / Velocity / BungeeCord server's plugins/ folder and connects to the
controller via cloud-api. It is not a module — no module.yaml, no frontend,
no per-platform variants. Pick this when you only need in-game / in-proxy
behaviour on one platform; pick "module" (prexorctl module new) when you need
cluster-wide state, REST endpoints, dashboard UI, or coordination across nodes.

See https://prexor.cloud/concepts/plugins/ for the full plugin vs module decision guide.`,
}

var (
	pluginNewPlatform    string
	pluginNewMCVersion   string
	pluginNewPackage     string
	pluginNewRepoRoot    string
	pluginNewDescription string
	pluginNewAuthor      string
	pluginNewForce       bool
	pluginNewDry         bool
)

var pluginNewCmd = &cobra.Command{
	Use:   "new <name>",
	Short: "Scaffold a standalone @CloudPlugin subproject",
	Long: `Generates a new standalone plugin subproject under
java/cloud-plugin/cloud-plugin-<name>/.

  --platform=paper|spigot|folia|velocity|bungeecord   required
  --mc-version=1.20|1.21                              paper only (default: 1.20)
  --name=<kebab>                                      kebab name; positional arg also accepted
  --package=<dotted>                                  default: me.prexorjustin.prexorcloud.plugins.<name>

The generated subproject:
  - applies the matching prexorcloud.plugin-<platform> convention plugin
  - contains one Java file: <Pascal>Plugin extends CloudPluginBase, with @CloudPlugin
  - is wired into java/settings.gradle.kts under the // ---- PLUGINS ---- // anchor

After scaffolding:
  cd java && ./gradlew :cloud-plugin:cloud-plugin-<name>:shadowJar
  # then drop the shaded jar from build/libs/ into a server's plugins/ folder.`,
	Args: cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		root := pluginNewRepoRoot
		if root == "" {
			cwd, err := os.Getwd()
			if err != nil {
				return err
			}
			root, err = scaffold.FindRepoRoot(cwd)
			if err != nil {
				return fmt.Errorf("%w (pass --repo-root to override)", err)
			}
		}

		name := ""
		if len(args) == 1 {
			name = args[0]
		}
		if name == "" {
			return fmt.Errorf("plugin new: name is required (pass it as a positional argument)")
		}

		platform, err := parsePluginPlatform(pluginNewPlatform)
		if err != nil {
			return err
		}

		opts := scaffold.PluginOptions{
			RepoRoot:    root,
			Name:        name,
			Package:     pluginNewPackage,
			Platform:    platform,
			MCVersion:   pluginNewMCVersion,
			Description: pluginNewDescription,
			Author:      pluginNewAuthor,
			Force:       pluginNewForce,
			Dry:         pluginNewDry,
		}

		res, err := scaffold.GeneratePlugin(opts)
		if err != nil {
			return err
		}
		fmt.Printf("%s new plugin: %s\n",
			theme.StyleBrand().Render(theme.Arrow()), theme.Code(res.Kebab))
		theme.PrintKV("platform", theme.FG(theme.Fg).Render(string(res.Platform))+
			" "+theme.StyleDim().Render("("+res.ConventionPlugin+")"))
		theme.PrintKV("package", theme.FG(theme.Fg).Render(res.PackageDot))
		theme.PrintKV("dest", theme.Path(res.DestDir))
		if pluginNewDry {
			fmt.Println("  " + theme.StyleMute().Render("(dry run — no files written)"))
		}
		theme.PrintKV("files", theme.Number(fmt.Sprintf("%d", res.FilesWritten)))
		if res.SettingsPatched {
			fmt.Println("  " + theme.StyleGreen().Render(theme.Tick()) + " " +
				theme.StyleDim().Render(fmt.Sprintf("patched settings.gradle.kts (+%d include)", res.IncludesAdded)))
		} else {
			fmt.Println("  " + theme.StyleMute().Render("settings.gradle.kts already contains the include — skipped"))
		}
		fmt.Println()
		fmt.Println(theme.Hint("next:"))
		fmt.Printf("  %s\n", theme.Code(fmt.Sprintf("cd java && ./gradlew :cloud-plugin:cloud-plugin-%s:shadowJar", res.Kebab)))
		return nil
	},
}

func parsePluginPlatform(raw string) (scaffold.PluginPlatform, error) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "":
		return "", fmt.Errorf("--platform is required (paper|spigot|folia|velocity|bungeecord)")
	case "paper":
		return scaffold.PluginPaper, nil
	case "spigot":
		return scaffold.PluginSpigot, nil
	case "folia":
		return scaffold.PluginFolia, nil
	case "velocity":
		return scaffold.PluginVelocity, nil
	case "bungeecord":
		return scaffold.PluginBungeeCord, nil
	default:
		return "", fmt.Errorf("unknown --platform=%q (paper|spigot|folia|velocity|bungeecord)", raw)
	}
}

func init() {
	pluginNewCmd.Flags().StringVar(&pluginNewPlatform, "platform", "",
		"Target platform: paper|spigot|folia|velocity|bungeecord (required)")
	pluginNewCmd.Flags().StringVar(&pluginNewMCVersion, "mc-version", "",
		"Minecraft API version for paper (1.20|1.21). Default 1.20. Ignored on other platforms.")
	pluginNewCmd.Flags().StringVar(&pluginNewPackage, "package", "",
		"Override the generated Java package (default: me.prexorjustin.prexorcloud.plugins.<name>)")
	pluginNewCmd.Flags().StringVar(&pluginNewRepoRoot, "repo-root", "",
		"Path to the repo root (default: discovered upwards from the working directory)")
	pluginNewCmd.Flags().StringVar(&pluginNewDescription, "description", "",
		"Plugin description, written into the @CloudPlugin annotation")
	pluginNewCmd.Flags().StringVar(&pluginNewAuthor, "author", "",
		"Author name written into the @CloudPlugin annotation (default: PrexorCloud)")
	pluginNewCmd.Flags().BoolVar(&pluginNewForce, "force", false,
		"Overwrite an existing plugin directory instead of failing")
	pluginNewCmd.Flags().BoolVar(&pluginNewDry, "dry", false,
		"Walk the scaffold and print what would happen without writing anything")

	pluginCmd.AddCommand(pluginNewCmd)
}
