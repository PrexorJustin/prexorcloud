package cmd

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/scaffold"
	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

var (
	moduleDevRepoRoot string
	moduleDevPoll     time.Duration
	moduleDevNoBuild  bool
)

var moduleDevCmd = &cobra.Command{
	Use:   "dev <name>",
	Short: "Watch a module's jar and reupload to the local controller on change",
	Long: `Resolves the module at java/cloud-modules/<name>, parses its
archiveName from build.gradle.kts, then watches build/libs/<archiveName>.jar
for mtime changes. On every change, uploads the jar to the controller — POST
.../modules/platform/upload for the first install, POST
.../modules/platform/{moduleId}/upgrade for subsequent reloads.

If the module has a frontend/ subtree, frontend/dist/ is watched separately.
A change there triggers a frontend-only POST .../modules/platform/{moduleId}/frontend/reload
that re-stages just the dashboard bundle without touching the platform module's
classloader — useful for tight Vite/dashboard iteration loops. Jar uploads
already include the latest frontend, so a jar change short-circuits the
frontend-only path on the same tick.

This is a polling watcher (no fsnotify). By default it spawns
./gradlew :cloud-modules:<name>:assemble -t in the background;
pass --no-build if you'd rather run gradle yourself in another terminal.

Stop with Ctrl+C.`,
	Args: cobra.ExactArgs(1),
	RunE: runModuleDev,
}

func runModuleDev(cmd *cobra.Command, args []string) error {
	root := moduleDevRepoRoot
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
	mod, err := scaffold.LocateModule(root, args[0])
	if err != nil {
		return err
	}
	client, err := requireAuth()
	if err != nil {
		return err
	}

	ctx, cancel := signal.NotifyContext(cmd.Context(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	theme.PrintTitle(fmt.Sprintf("module dev — %s", mod.Name))
	theme.PrintKV("dir", mod.Dir)
	theme.PrintKV("jar", mod.JarPath)
	if mod.FrontendDistPath != "" {
		theme.PrintKV("frontend", mod.FrontendDistPath)
	}
	theme.PrintKV("controller", client.BaseURL)
	theme.PrintKV("poll", moduleDevPoll.String())
	fmt.Println()

	var gradleCancel context.CancelFunc
	if !moduleDevNoBuild {
		gradleCtx, c := context.WithCancel(ctx)
		gradleCancel = c
		if err := startContinuousBuild(gradleCtx, root, mod); err != nil {
			cancel()
			return err
		}
		defer gradleCancel()
	}

	return watchAndReload(ctx, client, mod)
}

// startContinuousBuild spawns `./gradlew :…:assemble -t` in the background.
// Output is forwarded to the user's terminal so build errors are visible.
func startContinuousBuild(ctx context.Context, root string, mod *scaffold.Module) error {
	gradlew := filepath.Join(root, "java", "gradlew")
	if _, err := os.Stat(gradlew); err != nil {
		return fmt.Errorf("gradlew not found at %s — pass --no-build and run it yourself", gradlew)
	}
	task := mod.GradleTask + ":assemble"
	theme.PrintKV("build", "./gradlew "+task+" -t (continuous)")
	fmt.Println()

	c := exec.CommandContext(ctx, gradlew, task, "-t", "--quiet", "--console=plain")
	c.Dir = filepath.Join(root, "java")
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	if err := c.Start(); err != nil {
		return fmt.Errorf("start gradle: %w", err)
	}
	go func() {
		_ = c.Wait()
	}()
	return nil
}

func watchAndReload(ctx context.Context, client *api.Client, mod *scaffold.Module) error {
	var lastJarMtime time.Time
	var lastJarSize int64
	var lastFrontendStamp time.Time
	// moduleId is unknown until the first successful upload — the manifest
	// inside the jar is the source of truth, so we can't pre-derive it from
	// archiveName.
	moduleID := lookupInstalledModuleID(client, mod)

	tick := time.NewTicker(moduleDevPoll)
	defer tick.Stop()

	for {
		select {
		case <-ctx.Done():
			fmt.Println()
			theme.PrintWarn("module dev stopped")
			return nil
		case <-tick.C:
			jarChanged, jarMtime, jarSize, err := jarHasChanged(mod.JarPath, lastJarMtime, lastJarSize)
			if err != nil {
				theme.PrintWarn(fmt.Sprintf("stat jar: %v", err))
				continue
			}

			frontendStamp := lastFrontendStamp
			frontendChanged := false
			if mod.FrontendDistPath != "" {
				stamp, ok, err := frontendMaxMtime(mod.FrontendDistPath)
				if err != nil {
					theme.PrintWarn(fmt.Sprintf("scan frontend dist: %v", err))
				} else if ok && !stamp.Equal(lastFrontendStamp) {
					frontendStamp = stamp
					frontendChanged = true
				}
			}

			// Skip the very first observation of each track so operators don't
			// see an immediate reupload from a stale-on-disk build.
			firstJar := lastJarMtime.IsZero() && !jarMtime.IsZero()
			firstFrontend := lastFrontendStamp.IsZero() && !frontendStamp.IsZero()
			if firstJar {
				theme.PrintKV("jar baseline", fmt.Sprintf("%s (%d bytes) — waiting", jarMtime.Format("15:04:05"), jarSize))
			}
			if firstFrontend {
				theme.PrintKV("frontend baseline", fmt.Sprintf("%s — waiting", frontendStamp.Format("15:04:05")))
			}
			lastJarMtime = jarMtime
			lastJarSize = jarSize
			lastFrontendStamp = frontendStamp
			if firstJar || firstFrontend {
				continue
			}

			// Jar takes priority — its shadowJar already bundles the latest
			// frontend, so re-uploading the frontend separately on the same
			// tick would be redundant work.
			switch {
			case jarChanged:
				ts := jarMtime.Format("15:04:05")
				id, err := uploadOrUpgrade(client, mod, moduleID)
				if err != nil {
					theme.PrintError(fmt.Sprintf("[%s] reload failed: %v", ts, err))
					continue
				}
				moduleID = id
				theme.PrintSuccess(fmt.Sprintf("[%s] reloaded %s (%d bytes)", ts, id, jarSize))

			case frontendChanged:
				if moduleID == "" {
					// No install has happened yet — the controller has no
					// LoadedFrontend record to reload. Wait for the first jar
					// upload to seed it.
					continue
				}
				ts := frontendStamp.Format("15:04:05")
				hash, err := uploadFrontendBundle(client, moduleID, mod.FrontendDistPath)
				if err != nil {
					theme.PrintError(fmt.Sprintf("[%s] frontend reload failed: %v", ts, err))
					continue
				}
				theme.PrintSuccess(fmt.Sprintf("[%s] frontend hot-reloaded (hash=%s)", ts, hash))
			}
		}
	}
}

// jarHasChanged stats the jar and reports whether its mtime/size moved since
// the last observation. Missing-file is silent so the ticker can keep polling
// while gradle is mid-rebuild.
func jarHasChanged(jarPath string, lastMtime time.Time, lastSize int64) (bool, time.Time, int64, error) {
	st, err := os.Stat(jarPath)
	if err != nil {
		if os.IsNotExist(err) {
			return false, lastMtime, lastSize, nil
		}
		return false, lastMtime, lastSize, err
	}
	if st.ModTime().Equal(lastMtime) && st.Size() == lastSize {
		return false, lastMtime, lastSize, nil
	}
	return true, st.ModTime(), st.Size(), nil
}

// frontendMaxMtime returns the latest mtime of any regular file under root
// (recursive). ok=false means the directory doesn't yet exist (e.g. vite
// hasn't produced dist/ yet).
func frontendMaxMtime(root string) (time.Time, bool, error) {
	if _, err := os.Stat(root); err != nil {
		if os.IsNotExist(err) {
			return time.Time{}, false, nil
		}
		return time.Time{}, false, err
	}
	var max time.Time
	walkErr := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		if info.ModTime().After(max) {
			max = info.ModTime()
		}
		return nil
	})
	if walkErr != nil {
		return time.Time{}, false, walkErr
	}
	return max, !max.IsZero(), nil
}

// uploadFrontendBundle zips the frontend dist tree (re-prefixed under
// META-INF/frontend/ so the controller's existing extractFrontend reads it
// as if it were inside a module jar), then POSTs it to the frontend-reload
// endpoint. Returns the controller-reported content hash.
func uploadFrontendBundle(client *api.Client, moduleID, distPath string) (string, error) {
	payload, err := zipFrontendDist(distPath)
	if err != nil {
		return "", err
	}
	var result map[string]any
	endpoint := "/api/v1/modules/platform/" + moduleID + "/frontend/reload"
	if err := client.UploadBytes(endpoint, "frontend", "frontend-bundle.zip", payload, &result); err != nil {
		return "", err
	}
	hash, _ := result["contentHash"].(string)
	return hash, nil
}

func zipFrontendDist(distPath string) ([]byte, error) {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	walkErr := filepath.WalkDir(distPath, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(distPath, path)
		if err != nil {
			return err
		}
		// Use forward slashes in the zip regardless of host OS, and prefix
		// with META-INF/frontend/ so the controller's extractFrontend (which
		// looks for that prefix in module jars) reads it identically.
		entry := "META-INF/frontend/" + strings.ReplaceAll(rel, string(filepath.Separator), "/")
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		header, err := w.Create(entry)
		if err != nil {
			return err
		}
		if _, err := io.Copy(header, file); err != nil {
			return err
		}
		return nil
	})
	if walkErr != nil {
		return nil, walkErr
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// lookupInstalledModuleID best-effort matches an already-installed platform
// module by jarFile (which the controller stores as the original upload
// filename, i.e. archiveName + ".jar"). When found, we can start in upgrade
// mode after a CLI restart.
func lookupInstalledModuleID(client *api.Client, mod *scaffold.Module) string {
	var resp struct {
		Modules []map[string]any `json:"modules"`
	}
	if err := client.Get("/api/v1/modules/platform", &resp); err != nil {
		return ""
	}
	want := mod.ArchiveName + ".jar"
	for _, m := range resp.Modules {
		jarFile, _ := m["jarFile"].(string)
		id, _ := m["moduleId"].(string)
		if jarFile == want && id != "" {
			return id
		}
	}
	return ""
}

// uploadOrUpgrade returns the moduleId after the upload completes. When
// moduleID is non-empty it tries upgrade first, falling back to install if
// the controller reports the module is gone.
func uploadOrUpgrade(client *api.Client, mod *scaffold.Module, moduleID string) (string, error) {
	if moduleID != "" {
		var result map[string]any
		err := client.Upload("/api/v1/modules/platform/"+moduleID+"/upgrade", mod.JarPath, &result)
		if err == nil {
			if id, _ := result["moduleId"].(string); id != "" {
				return id, nil
			}
			return moduleID, nil
		}
		var apiErr *api.APIError
		if !errors.As(err, &apiErr) || apiErr.StatusCode != 404 {
			return "", err
		}
		// fall through to fresh install
	}
	var result map[string]any
	if err := client.Upload("/api/v1/modules/platform/upload", mod.JarPath, &result); err != nil {
		return "", err
	}
	id, _ := result["moduleId"].(string)
	return id, nil
}

var (
	moduleTestRepoRoot string
	moduleTestArgs     []string
)

var moduleTestCmd = &cobra.Command{
	Use:   "test <name>",
	Short: "Run a module's gradle test task",
	Long: `Wrapper for ` + "`./gradlew :cloud-modules:<name>:test`" + `. Runs from
the repo's java/ directory and forwards stdout/stderr.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		root := moduleTestRepoRoot
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
		mod, err := scaffold.LocateModule(root, args[0])
		if err != nil {
			return err
		}

		gradlew := filepath.Join(root, "java", "gradlew")
		if _, err := os.Stat(gradlew); err != nil {
			return fmt.Errorf("gradlew not found at %s", gradlew)
		}

		argv := append([]string{mod.GradleTask + ":test", "--console=plain"}, moduleTestArgs...)
		c := exec.CommandContext(cmd.Context(), gradlew, argv...)
		c.Dir = filepath.Join(root, "java")
		c.Stdout = os.Stdout
		c.Stderr = os.Stderr
		c.Stdin = os.Stdin
		if err := c.Run(); err != nil {
			// Forward Gradle's exit code so CI / shell pipelines can branch
			// on it. Routed through the typed ExitCodeError so root.go's
			// Execute() handles the exit; no direct os.Exit() calls here.
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				return &ExitCodeError{
					Code:    exitErr.ExitCode(),
					Message: fmt.Sprintf("gradle test exited with code %d", exitErr.ExitCode()),
				}
			}
			return err
		}
		return nil
	},
}

func init() {
	moduleDevCmd.Flags().StringVar(&moduleDevRepoRoot, "repo-root", "",
		"Path to the repo root (default: discovered upwards from the working directory)")
	moduleDevCmd.Flags().DurationVar(&moduleDevPoll, "poll", 750*time.Millisecond,
		"How often to check the module jar for changes")
	moduleDevCmd.Flags().BoolVar(&moduleDevNoBuild, "no-build", false,
		"Don't spawn ./gradlew :...:assemble -t; assume something else rebuilds the jar")

	moduleTestCmd.Flags().StringVar(&moduleTestRepoRoot, "repo-root", "",
		"Path to the repo root (default: discovered upwards from the working directory)")
	moduleTestCmd.Flags().StringSliceVar(&moduleTestArgs, "gradle-arg", nil,
		"Extra arguments forwarded to gradle (repeatable)")

	moduleCmd.AddCommand(moduleDevCmd, moduleTestCmd)
}
