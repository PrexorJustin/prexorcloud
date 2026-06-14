package setupweb

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/prexorcloud/prexorctl/internal/setup"
	"gopkg.in/yaml.v3"
)

// infoResponse is the static configuration the wizard reads on first load
// to populate placeholder values and figure out which install modes the
// host can support.
type infoResponse struct {
	Defaults struct {
		ControllerHTTPPort   int    `json:"controllerHttpPort"`
		ControllerGRPCPort   int    `json:"controllerGrpcPort"`
		ControllerInstallDir string `json:"controllerInstallDir"`
		DaemonInstallDir     string `json:"daemonInstallDir"`
		DaemonControllerHost string `json:"daemonControllerHost"`
		DaemonGRPCPort       int    `json:"daemonControllerGrpcPort"`
		DashboardInstallDir  string `json:"dashboardInstallDir"`
		DashboardListenPort  string `json:"dashboardListenPort"`
	} `json:"defaults"`
	Platform struct {
		OS            string `json:"os"`
		Arch          string `json:"arch"`
		NativeAllowed bool   `json:"nativeAllowed"`
		// NativeReason explains why nativeAllowed is false (non-Linux, or the
		// wizard isn't running as root), so the frontend can show a precise
		// disabled-state tooltip instead of silently hiding the toggle.
		NativeReason string `json:"nativeReason,omitempty"`
		// InstallSupported reports whether the host can run server-side
		// components at all, in any install mode (Linux-only). When false, the
		// Mode screen disables every install card and leaves only CLI-login
		// available — the wizard binary is on macOS/Windows for the CLI's
		// client verbs, not as an install target.
		InstallSupported bool `json:"installSupported"`
		// InstallUnsupportedReason explains why InstallSupported is false, so
		// the Mode-screen banner can name the actual OS instead of a generic
		// "not supported here".
		InstallUnsupportedReason string `json:"installUnsupportedReason,omitempty"`
	} `json:"platform"`
	Features struct {
		// CliLogin reports whether the wizard's CLI-login mode is wired
		// up — it requires a non-nil OnCliLogin callback so the resulting
		// token can be persisted to the prexorctl config. The frontend
		// hides the 5th mode card when this is false.
		CliLogin bool `json:"cliLogin"`
	} `json:"features"`
}

// infoFeatures is set once at Serve() time and read by handleInfo. Pulled out
// of the closure so the rest of the package can keep handleInfo as a plain
// function for direct invocation from tests.
var infoFeatures struct {
	CliLogin bool
}

func handleInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	resp := infoResponse{}
	resp.Features.CliLogin = infoFeatures.CliLogin
	resp.Defaults.ControllerHTTPPort = setup.DefaultControllerHTTPPort
	resp.Defaults.ControllerGRPCPort = setup.DefaultControllerGRPCPort
	resp.Defaults.ControllerInstallDir = "/opt/prexorcloud/controller"
	resp.Defaults.DaemonInstallDir = "/opt/prexorcloud/daemon"
	resp.Defaults.DaemonControllerHost = setup.DefaultDaemonControllerHost
	resp.Defaults.DaemonGRPCPort = setup.DefaultDaemonControllerGRPCPort
	resp.Defaults.DashboardInstallDir = "/opt/prexorcloud/dashboard"
	resp.Defaults.DashboardListenPort = "80"
	resp.Platform.OS = runtime.GOOS
	resp.Platform.Arch = runtime.GOARCH
	// Native installs need Linux + root (systemd units, package manager, apt
	// key import). nativeInstallAvailable encodes both checks; the frontend
	// gates the Docker-vs-Native toggle on the result and shows NativeReason
	// when it's unavailable.
	resp.Platform.NativeAllowed, resp.Platform.NativeReason = nativeInstallAvailable()
	resp.Platform.InstallSupported, resp.Platform.InstallUnsupportedReason = installSupported()
	writeJSON(w, http.StatusOK, resp)
}

// controllerInstallRequest is the wizard payload for "install controller".
// Only compose mode is currently exposed via the browser path — native mode
// requires interactive package-manager prompts and a TTY.
//
// YamlOverride: when non-empty, the wizard ships its fully-rendered
// controller.yml here and the handler writes it verbatim instead of the
// reduced "ports + URIs only" config WriteControllerConfig would emit. The
// new (post-handoff) wizard always sets this; the legacy field-by-field
// path still works for older clients.
type controllerInstallRequest struct {
	InstallMode  string   `json:"installMode"` // "compose"
	InstallDir   string   `json:"installDir"`
	HTTPPort     string   `json:"httpPort"`
	GRPCPort     string   `json:"grpcPort"`
	CORSOrigins  []string `json:"corsOrigins"`
	MongoMode    string   `json:"mongoMode"` // "local" or "remote"
	MongoURI     string   `json:"mongoUri"`
	RedisMode    string   `json:"redisMode"` // "local" or "remote"
	RedisURI     string   `json:"redisUri"`
	YamlOverride string   `json:"yamlOverride,omitempty"`
	// JoinToken switches the install into "controller-join" mode: the wizard
	// pastes the wire token here (prexor-jt:v1:...) and the handler writes it
	// to config/security/pending-join-token alongside controller.yml. The
	// controller's bootstrap picks the file up on first start and runs
	// ClusterControlService.startInJoinMode against the existing cluster.
	// Empty for Day-0 controllers (the default).
	JoinToken string `json:"joinToken,omitempty"`
	// EnableOnBoot / StartNow mirror the CLI wizard's lifecycle prompts. Pointers
	// so an omitted field defaults to true (auto-start on boot + start now), which
	// matches the browser wizard's historical always-enable-and-start behavior.
	EnableOnBoot *bool `json:"enableOnBoot,omitempty"`
	StartNow     *bool `json:"startNow,omitempty"`
}

// boolOrDefault returns *p, or def when p is nil (field omitted from the request).
func boolOrDefault(p *bool, def bool) bool {
	if p == nil {
		return def
	}
	return *p
}

type installResponse struct {
	OK        bool     `json:"ok"`
	Messages  []string `json:"messages"`
	Error     string   `json:"error,omitempty"`
	ErrorCode string   `json:"errorCode,omitempty"`
	DocsURL   string   `json:"docsUrl,omitempty"`
	NextSteps []string `json:"nextSteps,omitempty"`
	// ValidationErrors carries the structured list returned by
	// setup.ValidateControllerYAML when the wizard's controller.yml fails
	// pre-flight validation. The frontend renders each entry as an inline
	// block with field, message, and recovery hints — much more actionable
	// than a single error string and a vague docs link.
	ValidationErrors []validationError `json:"validationErrors,omitempty"`
}

// validationError is the wire-level mirror of
// setup.ControllerConfigValidationError. Kept in this package so the JSON
// shape is owned by the API contract, not by the internal setup package.
type validationError struct {
	Code     string   `json:"code"`
	Field    string   `json:"field"`
	Message  string   `json:"message"`
	Recovery []string `json:"recovery,omitempty"`
}

// Stable error codes the wizard frontend maps to fix-it copy. Kept in one
// place so server tests can pin the wire contract.
const (
	errInvalidRequest    = "INVALID_REQUEST"
	errModeUnsupported   = "MODE_UNSUPPORTED"
	errMongoURI          = "MONGO_URI_REQUIRED"
	errRedisURI          = "REDIS_URI_REQUIRED"
	errInvalidMode       = "INVALID_MODE"
	errNodeIDRequired    = "NODE_ID_REQUIRED"
	errJoinTokenRequired = "JOIN_TOKEN_REQUIRED"
	errReleaseFetch      = "RELEASE_FETCH_FAILED"
	errAssetMissing      = "ASSET_NOT_FOUND"
	errInstallDir        = "INSTALL_DIR_FAILED"
	errDownload          = "DOWNLOAD_FAILED"
	errConfigWrite       = "CONFIG_WRITE_FAILED"
	errComposeWrite      = "COMPOSE_WRITE_FAILED"
	errControllerUnreach = "CONTROLLER_UNREACHABLE"
	errAuthFailed        = "AUTH_FAILED"
	errNativeUnavailable  = "NATIVE_UNAVAILABLE"
	errInstallUnsupported = "INSTALL_UNSUPPORTED"
	errDepInstall         = "DEPENDENCY_INSTALL_FAILED"
	errServiceRegister    = "SERVICE_REGISTER_FAILED"
	// errInvalidConfig is returned when the wizard's controller.yml fails
	// pre-flight validation against the rules the Java ConfigValidator
	// would enforce at startup. The response payload includes the full
	// list of errors in ValidationErrors so the operator can fix every
	// problem in one pass.
	errInvalidConfig    = "INVALID_CONFIG"
	errTrustRootProvision = "TRUST_ROOT_PROVISION_FAILED"
)

// docURLFor returns a deep link into the published docs for the given error
// code, or "" if the code does not have a curated landing page. The frontend
// renders the link as "open the troubleshooting page" alongside the error.
func docURLFor(code string) string {
	switch code {
	case errModeUnsupported, errNativeUnavailable, errInstallUnsupported:
		return "https://prexor.cloud/docs/getting-started/installation"
	case errDepInstall, errServiceRegister, errTrustRootProvision:
		return "https://prexor.cloud/docs/operations/production-checklist"
	case errInvalidConfig:
		return "https://prexor.cloud/docs/operations/configuration#controller-yml"
	case errMongoURI, errRedisURI, errInvalidMode:
		return "https://prexor.cloud/docs/operations/configuration"
	case errNodeIDRequired, errJoinTokenRequired:
		return "https://prexor.cloud/docs/guides/multi-node-setup"
	case errReleaseFetch, errDownload:
		return "https://prexor.cloud/docs/getting-started/installation#offline-installs"
	case errAssetMissing:
		return "https://github.com/prexorjustin/prexorcloud/releases"
	case errInstallDir, errConfigWrite, errComposeWrite:
		return "https://prexor.cloud/docs/operations/production-checklist#filesystem-permissions"
	}
	return ""
}

func handleInstallController(logf func(string, ...any), onSuccess func()) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if ok, why := installSupported(); !ok {
			writeError(w, http.StatusUnprocessableEntity, errInstallUnsupported, why)
			return
		}
		var req controllerInstallRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "invalid request body: "+err.Error())
			return
		}
		req.InstallMode = stringOrDefault(req.InstallMode, installModeCompose)
		if req.InstallMode != installModeCompose && req.InstallMode != installModeNative {
			writeError(w, http.StatusBadRequest, errModeUnsupported,
				fmt.Sprintf("unknown install mode %q (expected %q or %q).", req.InstallMode, installModeCompose, installModeNative))
			return
		}
		if req.InstallMode == installModeNative {
			if ok, why := nativeInstallAvailable(); !ok {
				writeError(w, http.StatusUnprocessableEntity, errNativeUnavailable, why)
				return
			}
		}
		req.InstallDir = stringOrDefault(req.InstallDir, "/opt/prexorcloud/controller")
		req.HTTPPort = stringOrDefault(req.HTTPPort, strconv.Itoa(setup.DefaultControllerHTTPPort))
		req.GRPCPort = stringOrDefault(req.GRPCPort, strconv.Itoa(setup.DefaultControllerGRPCPort))
		if len(req.CORSOrigins) == 0 {
			req.CORSOrigins = []string{"http://localhost:3000"}
		}

		mongoLocal, mongoURI, code, err := resolveStorageURI("Mongo", req.MongoMode, req.MongoURI, "mongodb://mongo:27017", errMongoURI)
		if err != nil {
			writeError(w, http.StatusBadRequest, code, err.Error())
			return
		}
		redisLocal, redisURI, code, err := resolveStorageURI("Redis", req.RedisMode, req.RedisURI, "redis://redis:6379", errRedisURI)
		if err != nil {
			writeError(w, http.StatusBadRequest, code, err.Error())
			return
		}

		// Pre-flight validation: refuse to do ANY side effect (cosign install,
		// JAR download, systemd unit registration) if the wizard's
		// controller.yml would fail the Java validator at startup. This is
		// the regression guard for the production-profile + missing
		// trustRoot bug that crash-looped the JVM 115,000 times on a live
		// install host. Caller still gets a clean 422 with the full list of
		// findings — no half-installed state to clean up.
		if strings.TrimSpace(req.YamlOverride) != "" {
			if verrs := setup.ValidateControllerYAML(req.YamlOverride); len(verrs) > 0 {
				writeValidationErrors(w, verrs)
				return
			}
		}

		em := newEmitter(w, r, logf, onSuccess)
		say := func(format string, args ...any) { em.step(fmt.Sprintf(format, args...)) }

		// Stream the live command output (cosign download, package manager
		// progress bars, systemctl chatter) into the install console for
		// both paths. The sink restores to io.Discard on return so it
		// doesn't leak past this request.
		restore := setup.SetCommandSink(em.rawWriter())
		defer restore()

		// Cosign is needed in two distinct cases:
		//   1. Native installs verify the downloaded JAR's signature
		//      fail-closed (vs. compose's older soft-allow). Pre-install
		//      to make DownloadAndVerifyAsset's verify run for real.
		//   2. Any install path whose wizard YAML asks us to auto-provision
		//      a module-signing trust root — cosign generate-key-pair is
		//      the tool that produces the PEM the validator wants.
		// Installing cosign for case (2) on compose is new; older compose
		// installs without signing don't pay this cost.
		allowMissingCosign := true
		needCosignForTrustRoot := shouldProvisionTrustRoot(req.YamlOverride)
		if req.InstallMode == installModeNative || needCosignForTrustRoot {
			allowMissingCosign = ensureCosign(say)
		}

		say("Resolving latest controller release…")
		rel, err := setup.FetchLatestRelease(setup.GithubRepo)
		if err != nil {
			em.finishErr(errReleaseFetch, "fetch latest release: "+err.Error())
			return
		}
		asset, err := setup.FindAssetPrefix(rel, "cloud-controller-")
		if err != nil {
			em.finishErr(errAssetMissing, "find controller jar in release: "+err.Error())
			return
		}
		say("Found %s (%s).", rel.TagName, setup.HumanBytes(asset.Size))

		if err := setup.CreateControllerDirs(req.InstallDir); err != nil {
			em.finishErr(errInstallDir, "create install directories: "+err.Error())
			return
		}
		jarDest := filepath.Join(req.InstallDir, "PrexorCloudController.jar")
		if err := setup.DownloadAndVerifyAsset(rel, asset, jarDest, setup.CosignIdentityRegexJars, allowMissingCosign); err != nil {
			em.finishErr(errDownload, "download + verify controller jar: "+err.Error())
			return
		}
		say("Downloaded + verified controller jar to %s.", jarDest)

		cfg := setup.ControllerConfig{
			HTTPPort:       req.HTTPPort,
			GRPCPort:       req.GRPCPort,
			RuntimeProfile: "production",
			MongoURI:       mongoURI,
			RedisURI:       redisURI,
			CORSOrigins:    req.CORSOrigins,
		}
		// Provision a cosign trust root if the wizard's controller.yml
		// references the wizard-managed default path. The Java validator
		// requires modules.signing.trustRoot to be non-blank when
		// runtime.profile=production AND signing.required=true; without an
		// auto-provisioned PEM the JVM hits ConfigValidator.java:87-90 and
		// crash-loops at startup (the bug this whole pass fixes).
		//
		// The provisioner is idempotent: re-running the wizard against an
		// existing install reuses the existing keypair instead of rotating
		// it, so signatures the operator has already issued keep verifying.
		// If the operator pointed trustRoot at their own path, we skip and
		// trust they placed the PEM themselves.
		if shouldProvisionTrustRoot(req.YamlOverride) {
			say("Provisioning module signing trust root…")
			if _, perr := setup.ProvisionModuleTrustRoot(req.InstallDir); perr != nil {
				em.finishErr(errTrustRootProvision, "provision module trust root: "+perr.Error())
				return
			}
		}

		// If the wizard supplied a fully-rendered controller.yml (post-handoff
		// flow), use it verbatim instead of WriteControllerConfig's reduced
		// schema. The compose project still needs the cfg struct above for port
		// + URI propagation into docker-compose.yml.
		if strings.TrimSpace(req.YamlOverride) != "" {
			cfgPath := filepath.Join(req.InstallDir, "config", "controller.yml")
			if err := writeYamlOverride(cfgPath, req.YamlOverride); err != nil {
				em.finishErr(errConfigWrite, "write controller.yml override: "+err.Error())
				return
			}
			say("Wrote controller.yml (from wizard).")
		} else if err := setup.WriteControllerConfig(req.InstallDir, cfg); err != nil {
			em.finishErr(errConfigWrite, "write controller.yml: "+err.Error())
			return
		} else {
			say("Wrote controller.yml.")
		}

		// Controller-join: drop the operator-pasted wire token into the
		// canonical location the controller's bootstrap polls
		// (PrexorCloudBootstrap.PENDING_JOIN_TOKEN_FILE). Writing this is
		// what flips the controller from Day-0 bootstrap into
		// ClusterControlService.startInJoinMode on first start. Owner-only
		// perms because the token is single-use cluster credential material —
		// it's HMAC-bound to the cluster seed but still worth treating like a
		// short-lived secret.
		if strings.TrimSpace(req.JoinToken) != "" {
			tokenPath := filepath.Join(req.InstallDir, "config", "security", "pending-join-token")
			if err := writePendingJoinToken(tokenPath, strings.TrimSpace(req.JoinToken)); err != nil {
				em.finishErr(errConfigWrite, "write pending-join-token: "+err.Error())
				return
			}
			say("Wrote pending-join-token (controller will run join flow on first start).")
		}

		isControllerJoin := strings.TrimSpace(req.JoinToken) != ""
		enableOnBoot := boolOrDefault(req.EnableOnBoot, true)
		startNow := boolOrDefault(req.StartNow, true)

		if req.InstallMode == installModeNative {
			nextSteps, code, err := installControllerNative(say, req.InstallDir, req.HTTPPort, cfg, mongoLocal, redisLocal, enableOnBoot, startNow)
			if err != nil {
				em.finishErr(code, err.Error())
				return
			}
			if isControllerJoin {
				nextSteps = append(nextSteps, controllerJoinFollowupSteps()...)
			}
			em.finishOK(nextSteps)
			return
		}

		restartPolicy := "no"
		if enableOnBoot {
			restartPolicy = "unless-stopped"
		}
		if err := setup.WriteControllerComposeProject(req.InstallDir, cfg, setup.ControllerComposeProjectOptions{
			LocalMongo:    mongoLocal,
			LocalRedis:    redisLocal,
			RestartPolicy: restartPolicy,
		}); err != nil {
			em.finishErr(errComposeWrite, "write docker-compose.yml: "+err.Error())
			return
		}
		say("Wrote docker-compose.yml.")

		composeSteps := []string{}
		if startNow {
			say("Starting controller (docker compose up -d)…")
			if err := setup.ComposeUp(req.InstallDir); err != nil {
				em.finishErr(errComposeWrite, err.Error())
				return
			}
			say("Controller started.")
			composeSteps = append(composeSteps,
				fmt.Sprintf("Open http://localhost:%s once the controller is healthy.", req.HTTPPort))
		} else {
			composeSteps = append(composeSteps,
				fmt.Sprintf("cd %s", req.InstallDir),
				"docker compose up -d",
				fmt.Sprintf("Open http://localhost:%s once the controller is healthy.", req.HTTPPort))
		}
		if isControllerJoin {
			composeSteps = append(composeSteps, controllerJoinFollowupSteps()...)
		}
		em.finishOK(composeSteps)
	}
}

// ensureCosign installs cosign when missing so the JAR/bundle signature
// verification that follows runs for real. It returns the allowMissingCosign
// flag the caller threads into DownloadAndVerifyAsset: false once cosign is
// guaranteed present (verify fail-closed), true if the install attempt failed
// (fall back to checksum-only integrity rather than abort the whole install).
//
// Output streams to the active command sink, so the wizard shows the cosign
// download + checksum check live. Only meaningful on the native path; callers
// gate the call on installMode == native.
func ensureCosign(say func(string, ...any)) (allowMissingCosign bool) {
	say("Ensuring required packages (cosign)…")
	switch present, err := setup.EnsureCosign(); {
	case err != nil:
		say("⚠ cosign install failed (%v) — continuing with checksum-only integrity.", err)
		return true
	case present:
		say("cosign already installed.")
		return false
	default:
		say("Installed cosign.")
		return false
	}
}

type daemonInstallRequest struct {
	InstallMode    string `json:"installMode"`
	InstallDir     string `json:"installDir"`
	NodeID         string `json:"nodeId"`
	ControllerHost string `json:"controllerHost"`
	GRPCPort       string `json:"grpcPort"`
	JoinToken      string `json:"joinToken"`
	// Optional fully-rendered daemon.yml from the wizard; same role as
	// controllerInstallRequest.YamlOverride.
	YamlOverride string `json:"yamlOverride,omitempty"`
	// EnableOnBoot / StartNow mirror the CLI wizard's lifecycle prompts; nil ⇒ true.
	EnableOnBoot *bool `json:"enableOnBoot,omitempty"`
	StartNow     *bool `json:"startNow,omitempty"`
}

func handleInstallDaemon(logf func(string, ...any), onSuccess func()) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if ok, why := installSupported(); !ok {
			writeError(w, http.StatusUnprocessableEntity, errInstallUnsupported, why)
			return
		}
		var req daemonInstallRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "invalid request body: "+err.Error())
			return
		}
		req.InstallMode = stringOrDefault(req.InstallMode, installModeCompose)
		if req.InstallMode != installModeCompose && req.InstallMode != installModeNative {
			writeError(w, http.StatusBadRequest, errModeUnsupported,
				fmt.Sprintf("unknown install mode %q (expected %q or %q).", req.InstallMode, installModeCompose, installModeNative))
			return
		}
		if req.InstallMode == installModeNative {
			if ok, why := nativeInstallAvailable(); !ok {
				writeError(w, http.StatusUnprocessableEntity, errNativeUnavailable, why)
				return
			}
		}
		if strings.TrimSpace(req.NodeID) == "" {
			writeError(w, http.StatusBadRequest, errNodeIDRequired, "nodeId is required")
			return
		}
		if strings.TrimSpace(req.JoinToken) == "" {
			writeError(w, http.StatusBadRequest, errJoinTokenRequired, "joinToken is required")
			return
		}
		req.InstallDir = stringOrDefault(req.InstallDir, "/opt/prexorcloud/daemon")
		req.ControllerHost = stringOrDefault(req.ControllerHost, setup.DefaultDaemonControllerHost)
		req.GRPCPort = stringOrDefault(req.GRPCPort, strconv.Itoa(setup.DefaultDaemonControllerGRPCPort))

		em := newEmitter(w, r, logf, onSuccess)
		say := func(format string, args ...any) { em.step(fmt.Sprintf(format, args...)) }

		allowMissingCosign := true
		if req.InstallMode == installModeNative {
			restore := setup.SetCommandSink(em.rawWriter())
			defer restore()
			allowMissingCosign = ensureCosign(say)
		}

		say("Resolving latest daemon release…")
		rel, err := setup.FetchLatestRelease(setup.GithubRepo)
		if err != nil {
			em.finishErr(errReleaseFetch, "fetch latest release: "+err.Error())
			return
		}
		asset, err := setup.FindAssetPrefix(rel, "cloud-daemon-")
		if err != nil {
			em.finishErr(errAssetMissing, "find daemon jar: "+err.Error())
			return
		}
		say("Found %s (%s).", rel.TagName, setup.HumanBytes(asset.Size))

		if err := setup.CreateDaemonDirs(req.InstallDir); err != nil {
			em.finishErr(errInstallDir, "create install directories: "+err.Error())
			return
		}
		jarDest := filepath.Join(req.InstallDir, "PrexorCloudDaemon.jar")
		if err := setup.DownloadAndVerifyAsset(rel, asset, jarDest, setup.CosignIdentityRegexJars, allowMissingCosign); err != nil {
			em.finishErr(errDownload, "download + verify daemon jar: "+err.Error())
			return
		}
		say("Downloaded + verified daemon jar to %s.", jarDest)

		cfg := setup.DaemonConfig{
			NodeID:         req.NodeID,
			ControllerHost: req.ControllerHost,
			GRPCPort:       req.GRPCPort,
			JoinToken:      req.JoinToken,
		}
		if strings.TrimSpace(req.YamlOverride) != "" {
			cfgPath := filepath.Join(req.InstallDir, "config", "daemon.yml")
			if err := writeYamlOverride(cfgPath, req.YamlOverride); err != nil {
				em.finishErr(errConfigWrite, "write daemon.yml override: "+err.Error())
				return
			}
			say("Wrote daemon.yml (from wizard).")
		} else if err := setup.WriteDaemonConfig(req.InstallDir, cfg); err != nil {
			em.finishErr(errConfigWrite, "write daemon.yml: "+err.Error())
			return
		} else {
			say("Wrote daemon.yml.")
		}

		enableOnBoot := boolOrDefault(req.EnableOnBoot, true)
		startNow := boolOrDefault(req.StartNow, true)

		if req.InstallMode == installModeNative {
			nextSteps, code, err := installDaemonNative(say, req.InstallDir, req.NodeID, enableOnBoot, startNow)
			if err != nil {
				em.finishErr(code, err.Error())
				return
			}
			em.finishOK(nextSteps)
			return
		}

		restartPolicy := "no"
		if enableOnBoot {
			restartPolicy = "unless-stopped"
		}
		if err := setup.WriteDaemonComposeProjectWithRestart(req.InstallDir, restartPolicy); err != nil {
			em.finishErr(errComposeWrite, "write docker-compose.yml: "+err.Error())
			return
		}
		say("Wrote docker-compose.yml.")

		if startNow {
			say("Starting daemon (docker compose up -d)…")
			if err := setup.ComposeUp(req.InstallDir); err != nil {
				em.finishErr(errComposeWrite, err.Error())
				return
			}
			say("Daemon started.")
			em.finishOK([]string{
				fmt.Sprintf("Confirm the new node appears in the controller's node list (joining as %s).", req.NodeID),
			})
			return
		}

		em.finishOK([]string{
			fmt.Sprintf("cd %s", req.InstallDir),
			"docker compose up -d",
			fmt.Sprintf("Confirm the new node appears in the controller's node list (joining as %s).", req.NodeID),
		})
	}
}

type dashboardInstallRequest struct {
	InstallMode      string `json:"installMode"` // "compose" (default) or "native"
	WebServer        string `json:"webServer"`   // native only: "nginx" (default) or "caddy"
	InstallDir       string `json:"installDir"`
	PublicURL        string `json:"publicUrl"`
	ListenPort       string `json:"listenPort"`
	ControllerURL    string `json:"controllerUrl"`
	AdminUser        string `json:"adminUser"`
	AdminPassword    string `json:"adminPassword"`
	SkipCORSRegister bool   `json:"skipCorsRegister"`
}

// handleInstallDashboard mirrors handleInstallController/Daemon but for the
// Dashboard component: validates the controller is reachable, logs in with the
// supplied admin credentials, registers the dashboard's public origin in the
// controller's CORS allow-list, and emits a compose project the operator can
// `docker compose up -d`. Hard-fails on controller-unreachable per the design
// agreement — a dashboard without a working controller link is useless.
func handleInstallDashboard(logf func(string, ...any), onSuccess func()) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if ok, why := installSupported(); !ok {
			writeError(w, http.StatusUnprocessableEntity, errInstallUnsupported, why)
			return
		}
		var req dashboardInstallRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "invalid request body: "+err.Error())
			return
		}
		req.InstallMode = stringOrDefault(req.InstallMode, installModeCompose)
		if req.InstallMode != installModeCompose && req.InstallMode != installModeNative {
			writeError(w, http.StatusBadRequest, errModeUnsupported,
				fmt.Sprintf("unknown install mode %q (expected %q or %q).", req.InstallMode, installModeCompose, installModeNative))
			return
		}
		if req.InstallMode == installModeNative {
			if ok, why := nativeInstallAvailable(); !ok {
				writeError(w, http.StatusUnprocessableEntity, errNativeUnavailable, why)
				return
			}
		}
		if strings.TrimSpace(req.PublicURL) == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "publicUrl is required")
			return
		}
		if strings.TrimSpace(req.ControllerURL) == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "controllerUrl is required")
			return
		}
		if strings.TrimSpace(req.AdminPassword) == "" {
			writeError(w, http.StatusBadRequest, errInvalidRequest, "adminPassword is required")
			return
		}
		req.InstallDir = stringOrDefault(req.InstallDir, "/opt/prexorcloud/dashboard")
		req.ListenPort = stringOrDefault(req.ListenPort, "80")
		req.AdminUser = stringOrDefault(req.AdminUser, "admin")

		em := newEmitter(w, r, logf, onSuccess)
		say := func(format string, args ...any) { em.step(fmt.Sprintf(format, args...)) }

		allowMissingCosign := true
		if req.InstallMode == installModeNative {
			restore := setup.SetCommandSink(em.rawWriter())
			defer restore()
			allowMissingCosign = ensureCosign(say)
		}

		// Login to controller (hard-fail if it 4xx/5xxs or is unreachable).
		say("Authenticating against controller %s as %s…", req.ControllerURL, req.AdminUser)
		token, err := dashboardControllerLogin(req.ControllerURL, req.AdminUser, req.AdminPassword)
		if err != nil {
			em.finishErr(errReleaseFetch, "controller login failed: "+err.Error())
			return
		}
		say("Controller login OK.")

		// Register CORS (non-fatal — installer surfaces a manual fix-it if it
		// fails, but the install still produces a working compose project).
		if !req.SkipCORSRegister {
			changed, _, err := dashboardRegisterCORS(req.ControllerURL, token, req.PublicURL)
			if err != nil {
				say("CORS registration failed (%v). Add %s to controller.yml http.cors.allowedOrigins manually.", err, req.PublicURL)
			} else if changed {
				say("Registered %s in controller CORS allow-list (live, no restart needed).", req.PublicURL)
			} else {
				say("%s already in controller CORS allow-list.", req.PublicURL)
			}
		}

		if req.InstallMode == installModeNative {
			nextSteps, code, err := installDashboardNative(
				say, req.InstallDir, req.PublicURL, req.ListenPort, req.ControllerURL,
				setup.WebServer(stringOrDefault(req.WebServer, string(setup.WebServerNginx))),
				allowMissingCosign,
			)
			if err != nil {
				em.finishErr(code, err.Error())
				return
			}
			em.finishOK(nextSteps)
			return
		}

		// Emit compose project.
		cfg := setup.DashboardConfig{
			InstallDir:    req.InstallDir,
			PublicURL:     req.PublicURL,
			ListenPort:    req.ListenPort,
			ControllerURL: req.ControllerURL,
			TLSMode:       "none",
		}
		if err := setup.CreateDashboardDirs(req.InstallDir); err != nil {
			em.finishErr(errInstallDir, "create install directories: "+err.Error())
			return
		}
		if err := setup.WriteDashboardComposeProject(req.InstallDir, cfg); err != nil {
			em.finishErr(errComposeWrite, "write compose project: "+err.Error())
			return
		}
		say("Wrote docker-compose.yml + nginx.conf to %s.", req.InstallDir)

		em.finishOK([]string{
			fmt.Sprintf("cd %s", req.InstallDir),
			"docker compose up -d",
			fmt.Sprintf("Open %s once nginx is healthy.", req.PublicURL),
		})
	}
}

// dashboardControllerLogin is a thin POST to /api/v1/auth/login. Returns the
// JWT on success. Intentionally not using internal/api.Client because the
// wizard server runs without the caller's CLI context.
func dashboardControllerLogin(controllerURL, user, password string) (string, error) {
	bodyBytes, _ := json.Marshal(map[string]string{"username": user, "password": password})
	body := bytes.NewReader(bodyBytes)
	req, err := http.NewRequest(http.MethodPost, strings.TrimRight(controllerURL, "/")+"/api/v1/auth/login", body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("controller returned %d: %s", resp.StatusCode, string(raw))
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("decode login response: %w", err)
	}
	if out.Token == "" {
		return "", fmt.Errorf("controller returned empty token")
	}
	return out.Token, nil
}

// dashboardRegisterCORS adds origin to the controller's CORS allow-list via the
// new admin endpoint introduced for this installer. Returns (changed, restartRequired, err).
func dashboardRegisterCORS(controllerURL, token, origin string) (bool, bool, error) {
	bodyBytes, _ := json.Marshal(map[string]string{"action": "add", "origin": origin})
	body := bytes.NewReader(bodyBytes)
	req, err := http.NewRequest(http.MethodPatch, strings.TrimRight(controllerURL, "/")+"/api/v1/admin/cors/origins", body)
	if err != nil {
		return false, false, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return false, false, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return false, false, fmt.Errorf("controller returned %d: %s", resp.StatusCode, string(raw))
	}
	var out struct {
		Changed         bool `json:"changed"`
		RestartRequired bool `json:"restartRequired"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return false, false, err
	}
	return out.Changed, out.RestartRequired, nil
}

// shouldProvisionTrustRoot decides whether the install handler should
// auto-generate a cosign keypair for this controller.yml. We provision when
// the wizard YAML asks for signing (required or production-derived) AND its
// trustRoot path matches the wizard-managed default (config/security/
// module-trust-root.pem). An operator who points the field at their own
// PEM has opted out of auto-provisioning, and we trust them to put the file
// there themselves — overwriting it would clobber an external key.
//
// Returns false for an empty YAML override (compose path's reduced default
// config already sets required=false, so no trust root is needed).
func shouldProvisionTrustRoot(yamlOverride string) bool {
	if strings.TrimSpace(yamlOverride) == "" {
		return false
	}
	// Parse once via the validator's own loose schema so we don't duplicate
	// the "production implies required" logic here.
	var probe struct {
		Runtime struct {
			Profile string `json:"profile" yaml:"profile"`
		} `json:"runtime" yaml:"runtime"`
		Modules struct {
			Signing struct {
				Required  *bool  `json:"required" yaml:"required"`
				TrustRoot string `json:"trustRoot" yaml:"trustRoot"`
			} `json:"signing" yaml:"signing"`
		} `json:"modules" yaml:"modules"`
	}
	if err := yaml.Unmarshal([]byte(yamlOverride), &probe); err != nil {
		return false
	}
	requiredEffective := strings.TrimSpace(probe.Runtime.Profile) == "production"
	if probe.Modules.Signing.Required != nil {
		requiredEffective = *probe.Modules.Signing.Required
	}
	if !requiredEffective {
		return false
	}
	return strings.TrimSpace(probe.Modules.Signing.TrustRoot) == setup.ManagedTrustRootPath()
}

// writeYamlOverride writes a YAML string the wizard rendered directly to disk,
// creating the parent directory if needed. Replaces WriteControllerConfig /
// WriteDaemonConfig when the wizard ships its own full config.
func writeYamlOverride(path, body string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(body), 0o600)
}

// writePendingJoinToken drops the operator-pasted cluster join token at the
// path the controller's bootstrap polls on startup. Owner-only perms because
// the token is single-use cluster credential material — even after the
// controller successfully joins and deletes the file, a copy hanging around
// in a backup is a credential leak. The 0o700 dir perm matches what the
// controller itself sets on config/security/ via FilePermissions.setOwnerOnly.
func writePendingJoinToken(path, token string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(token+"\n"), 0o600)
}

// controllerJoinFollowupSteps is appended to the post-install nextSteps when
// the wizard ran in controller-join mode. Two reasons it exists:
//   - After the join, the operator needs an unambiguous "did this work?" check.
//     `prexorctl cluster members` against a controller that just joined is
//     about as direct as it gets.
//   - A 1-node or 2-node Raft group provides zero fault tolerance. Raft needs
//     ≥3 voting members to survive losing one. The wizard doesn't know the
//     post-join member count (it was a question for the cluster, not the
//     joiner), so we point the operator at the same command that will tell
//     them.
func controllerJoinFollowupSteps() []string {
	return []string{
		"prexorctl cluster members        # confirm this controller appears as READY",
		"# Raft tolerates floor((N-1)/2) controller failures. Aim for an odd member count ≥ 3 for HA;",
		"# a 1- or 2-node cluster has zero fault tolerance.",
	}
}

// resolveStorageURI maps the wizard's mode/URI pair to (localContainer, uri)
// the way the CLI helpers expect — "local" means spawn a sidecar container in
// the compose project, "remote" requires an explicit URI from the operator.
// uriMissingCode is the error code to return when mode=remote without a URI;
// callers thread the storage-specific code through.
func resolveStorageURI(label, mode, explicit, localDefault, uriMissingCode string) (localContainer bool, uri string, code string, err error) {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "", "local":
		return true, localDefault, "", nil
	case "remote":
		if strings.TrimSpace(explicit) == "" {
			return false, "", uriMissingCode, fmt.Errorf("%s remote URI is required when mode=remote", label)
		}
		return false, explicit, "", nil
	default:
		return false, "", errInvalidMode, fmt.Errorf("%s mode must be 'local' or 'remote', got %q", label, mode)
	}
}

func stringOrDefault(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return s
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, installResponse{
		OK:        false,
		Error:     msg,
		ErrorCode: code,
		DocsURL:   docURLFor(code),
	})
}

// writeValidationErrors responds with 422 + the full list of validator
// findings. We return ALL of them at once (mirroring the Java validator's
// "collect, then fail") so the operator can fix every issue in a single
// pass through the wizard, rather than burning a round trip per error.
//
// The top-level Error string picks the first finding's message so plain
// "show the error" UIs still get something meaningful; ValidationErrors
// carries the full structured list for the wizard's inline-renderer.
func writeValidationErrors(w http.ResponseWriter, errs []setup.ControllerConfigValidationError) {
	wire := make([]validationError, 0, len(errs))
	for _, e := range errs {
		wire = append(wire, validationError{
			Code:     e.Code,
			Field:    e.Field,
			Message:  e.Message,
			Recovery: e.Recovery,
		})
	}
	top := "controller.yml failed pre-flight validation"
	if len(errs) > 0 {
		top = errs[0].Error()
	}
	writeJSON(w, http.StatusUnprocessableEntity, installResponse{
		OK:               false,
		Error:            top,
		ErrorCode:        errInvalidConfig,
		DocsURL:          docURLFor(errInvalidConfig),
		ValidationErrors: wire,
	})
}

func writeInstallError(w http.ResponseWriter, messages []string, code, msg string) {
	writeJSON(w, http.StatusInternalServerError, installResponse{
		OK:        false,
		Messages:  messages,
		Error:     msg,
		ErrorCode: code,
		DocsURL:   docURLFor(code),
	})
}
