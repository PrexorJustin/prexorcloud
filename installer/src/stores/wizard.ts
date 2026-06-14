import { defineStore } from 'pinia';
import { genSecret, genUuid } from '@/lib/generators';
import { filesForMode as filesForModeImpl, type YamlFilename } from '@/lib/yaml';
import { apiFetch, apiHeaders } from '@/lib/api';
import { controllerYaml, daemonYaml } from '@/lib/yaml';
import { resetTerminal, writeTerminal } from '@/lib/terminalBus';

export type WizardMode = 'all' | 'controller' | 'controller-join' | 'daemon' | 'dashboard' | 'cli';
export type WizardStep = 'mode' | 'essentials' | 'security' | 'review' | 'cli-login';
export type Profile = 'development' | 'production';
export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
export type LogFormat = 'HUMAN' | 'JSON';
export type SigningMode = 'KEYED' | 'COSIGN_BUNDLE' | 'COSIGN_KEYLESS';

export interface InstallLogEntry {
  msg: string;
  cls: string;
}

// Mirror of cli/internal/setupweb/install.go:validationError. The install
// backend returns one or more of these when controller.yml fails pre-flight
// validation (the rules the Java validator would enforce at startup). Each
// has a stable code, a dotted field path, a human message, and a list of
// recovery hints — rendered inline on the Review screen so the operator
// fixes every issue in one pass rather than discovering them one crash-loop
// restart at a time.
export interface ConfigValidationError {
  code: string;
  field: string;
  message: string;
  recovery?: string[];
}

export type InstallPhaseStatus = 'active' | 'done' | 'failed';

// One entry in the live install timeline. Each backend "step" line opens a new
// phase; the previous active phase is marked done. startedAt/endedAt power the
// per-phase duration shown next to each row.
export interface InstallPhase {
  label: string;
  status: InstallPhaseStatus;
  startedAt: number; // epoch ms
  endedAt: number | null;
  detail?: string; // failure detail, when status === 'failed'
}

// Full state shape ported from defaultState() at cli/internal/setupweb/static/
// index.html:532-647. Fields are grouped semantically so each follow-up screen
// (essentials / security / review / cli-login) lifts its slice directly into a
// Vue component without renaming anything.
export interface WizardState {
  // Navigation
  step: WizardStep;
  mode: WizardMode;

  // Core
  uuid: string;
  profile: Profile;

  // Install path — 'compose' spins everything up as Docker containers (the
  // default, works anywhere with Docker). 'native' installs onto the host via
  // systemd + the package manager and is only offered when /api/info reports
  // the wizard is running on Linux as root (see nativeAllowed/nativeReason).
  installMode: 'compose' | 'native';
  // Native dashboard only: which host web server serves the SPA bundle.
  // Caddy auto-provisions HTTPS for an https:// public URL; nginx serves plain
  // HTTP and assumes TLS is terminated upstream.
  webServer: 'nginx' | 'caddy';

  // Lifecycle — whether the component auto-starts on boot (native: systemctl
  // enable / docker: restart=unless-stopped) and whether the wizard starts it
  // now (native: systemctl start / docker: docker compose up -d). Both default
  // on, matching the wizard's historical always-enable-and-start behavior.
  enableOnBoot: boolean;
  startNow: boolean;

  // Controller — network
  httpHost: string;
  httpPort: number;
  grpcHost: string;
  grpcPort: number;
  allowedSubnets: string[];
  corsOrigins: string[];

  // Storage
  mongoMode: 'local' | 'remote';
  databaseUri: string;
  databaseName: string;
  redisMode: 'local' | 'remote';
  redisUri: string;

  // (Backups: defaults are baked into the Java BackupConfig record —
  // directory='backups', retentionCount=10. No UI ever exposed these
  // fields, so we drop them from the wizard state entirely instead of
  // collecting input that is never seen and never changed.)

  // Security — JWT
  jwtSecret: string;
  jwtExpirationMinutes: number;
  initialAdminPassword: string;

  // Rate limiting
  rateIpPerMinute: number;
  rateUserPerMinute: number;
  rateFailOpenOnRedisError: boolean;

  // Lockout
  lockoutEnabled: boolean;
  lockoutMaxAttempts: number;
  lockoutWindowSeconds: number;
  lockoutLockoutSeconds: number;

  // Password reset + SMTP
  passwordResetEnabled: boolean;
  passwordResetTtlMinutes: number;
  passwordResetUrlBase: string;
  smtpHost: string;
  smtpPort: number;
  smtpStartTls: boolean;
  smtpImplicitTls: boolean;
  smtpUsername: string;
  smtpPassword: string;
  smtpFrom: string;

  // Logging
  logLevel: LogLevel;
  logFormat: LogFormat;

  // Metrics
  metricsEnabled: boolean;
  metricsRetentionHours: number;
  metricsCollectionIntervalSeconds: number;

  // Scheduler
  schedulerEvalSeconds: number;
  schedulerCooldownSeconds: number;
  schedulerNodeTimeoutSeconds: number;
  schedulerAuditRetentionDays: number;

  // Heartbeat
  heartbeatIntervalMs: number;
  heartbeatMissedThreshold: number;

  // Crashes
  crashRingBufferSize: number;
  crashLoopThreshold: number;
  crashLoopWindowSeconds: number;

  // Modules
  modulesDirectory: string;
  modulesDataDirectory: string;
  modulesSigningRequired: boolean | null;
  modulesSigningMode: SigningMode;
  modulesSigningTrustRoot: string;
  modulesSigningAllowUnsignedDev: boolean;

  // Dashboard toggle
  dashboardEnabled: boolean;

  // Maintenance
  maintenanceEnabled: boolean;
  maintenanceMessage: string;

  // Share
  shareEnabled: boolean;
  sharePasteUrl: string;
  sharePasteToken: string;
  shareDefaultExpiry: string;
  shareDefaultPrivate: boolean;
  shareE2E: boolean;

  // Daemon — identity
  nodeId: string;
  advertiseAddress: string;
  // Daemon — controller link
  controllerHost: string;
  controllerGrpcPort: number;
  joinToken: string;

  // Controller-join — operator-pasted wire token (prexor-jt:v1:...) that
  // the wizard writes to config/security/pending-join-token so the
  // controller's bootstrap takes the join branch on first start. Distinct
  // from `joinToken` above, which is the daemon-side token; conflating
  // them risks cross-mode leakage if the operator backs through the
  // mode switcher.
  controllerJoinToken: string;
  // Daemon — instances
  certificateDir: string;
  instancesDirectory: string;
  instancesShutdownTimeoutSeconds: number;
  instancesKillTimeoutSeconds: number;
  instancesLogRingBufferLines: number;
  instancesMaxConsoleOutputLinesPerSecond: number;
  // Daemon — resources
  resourcesMaxMemoryMb: number;
  // Daemon — health
  daemonHealthEnabled: boolean;
  daemonHealthBindAddress: string;
  daemonHealthPort: number;
  // Daemon — logging
  daemonLogLevel: LogLevel;
  daemonLogFormat: LogFormat;
  // Daemon — reconnect
  reconnectInitialDelayMs: number;
  reconnectMaxDelayMs: number;
  reconnectMultiplier: number;
  // Daemon — module signing
  daemonModulesSigningRequired: boolean;
  daemonModulesSigningMode: SigningMode;
  daemonModulesSigningTrustRoot: string;

  // Dashboard install
  dashboardInstallDir: string;
  dashboardPublicUrl: string;
  dashboardListenPort: number;
  dashboardControllerUrl: string;
  dashboardAdminUser: string;
  dashboardAdminPassword: string;

  // Wizard ephemeral state
  helpOpen: string | null;
  collapsibles: Record<string, boolean>;
  activeReviewTab: string;
  activePreviewTab: string;
  busy: boolean;
  installLog: InstallLogEntry[];
  installError: string;
  installErrorCode: string;
  installErrorDocsUrl: string;
  installValidationErrors: ConfigValidationError[];
  installDone: boolean;
  // Live install timeline + result, driven by the NDJSON stream.
  installPhases: InstallPhase[];
  installNextSteps: string[];
  installStartedAt: number | null;
  installFinishedAt: number | null;

  // CLI-login mode
  cliLoginController: string;
  cliLoginUsername: string;
  cliLoginPassword: string;
  cliLoginBusy: boolean;
  cliLoginError: string;
  cliLoginDone: boolean;
  cliLoginAs: string;

  // Server-side capability flags (loaded from /api/info on boot).
  // cliLoginAvailable is true unless `/api/info` reports `features.cliLogin: false`,
  // which happens when the wizard server was launched without the OnCliLogin
  // callback wired up (no `prexorctl setup --browser` host to persist the token to).
  cliLoginAvailable: boolean;
  defaultsLoaded: boolean;

  // Native-install capability, loaded from /api/info platform block. When
  // false, the install-path toggle stays locked to compose and surfaces
  // nativeReason as the disabled-state explanation.
  nativeAllowed: boolean;
  nativeReason: string;

  // Server-side install capability for this host. False on macOS/Windows —
  // the wizard binary ships there for the CLI's client verbs, not as an
  // install target for controller/daemon/dashboard. When false the Mode
  // screen disables every install card and only the CLI-login card is
  // selectable. Server-side install handlers also reject any POST under
  // these conditions with INSTALL_UNSUPPORTED.
  installSupported: boolean;
  installUnsupportedReason: string;
}

function defaultState(): WizardState {
  return {
    step: 'mode',
    mode: 'all',

    uuid: genUuid(),
    profile: 'development',

    installMode: 'compose',
    webServer: 'nginx',
    enableOnBoot: true,
    startNow: true,

    httpHost: '0.0.0.0',
    httpPort: 8080,
    grpcHost: '0.0.0.0',
    grpcPort: 9090,
    allowedSubnets: ['0.0.0.0/0', '::/0'],
    corsOrigins: ['http://localhost:3000'],

    mongoMode: 'local',
    databaseUri: 'mongodb://mongo:27017',
    databaseName: 'prexorcloud',
    redisMode: 'local',
    redisUri: 'redis://redis:6379',

    // Auto-generate a strong JWT secret at wizard mount. The legacy default
    // was an empty string + a `<auto-generate-on-first-start>` placeholder
    // written into the YAML — but Java's JwtManager Base64-decodes the field
    // unconditionally, so the placeholder crash-looped the JVM (the `<`
    // character isn't valid Base64). Operators can still hit "Generate" on
    // the Security screen to rotate, or paste their own value here.
    jwtSecret: genSecret(64),
    jwtExpirationMinutes: 1440,
    initialAdminPassword: '',

    rateIpPerMinute: 100,
    rateUserPerMinute: 300,
    rateFailOpenOnRedisError: false,

    lockoutEnabled: true,
    lockoutMaxAttempts: 5,
    lockoutWindowSeconds: 900,
    lockoutLockoutSeconds: 900,

    passwordResetEnabled: false,
    passwordResetTtlMinutes: 30,
    passwordResetUrlBase: '',
    smtpHost: '',
    smtpPort: 587,
    smtpStartTls: true,
    smtpImplicitTls: false,
    smtpUsername: '',
    smtpPassword: '',
    smtpFrom: '',

    logLevel: 'INFO',
    logFormat: 'HUMAN',

    metricsEnabled: true,
    metricsRetentionHours: 168,
    metricsCollectionIntervalSeconds: 30,

    schedulerEvalSeconds: 15,
    schedulerCooldownSeconds: 60,
    schedulerNodeTimeoutSeconds: 90,
    schedulerAuditRetentionDays: 90,

    heartbeatIntervalMs: 30000,
    heartbeatMissedThreshold: 3,

    crashRingBufferSize: 500,
    crashLoopThreshold: 3,
    crashLoopWindowSeconds: 300,

    modulesDirectory: 'modules',
    modulesDataDirectory: 'modules/data',
    modulesSigningRequired: null,
    modulesSigningMode: 'KEYED',
    modulesSigningTrustRoot: '',
    modulesSigningAllowUnsignedDev: true,

    dashboardEnabled: true,

    maintenanceEnabled: false,
    maintenanceMessage: 'The network is currently under maintenance.',

    shareEnabled: false,
    sharePasteUrl: 'https://pste.dev',
    sharePasteToken: '',
    shareDefaultExpiry: '1d',
    shareDefaultPrivate: true,
    shareE2E: false,

    nodeId: '',
    advertiseAddress: '',
    controllerHost: '',
    controllerGrpcPort: 9090,
    joinToken: '',
    controllerJoinToken: '',
    certificateDir: 'config/security',
    instancesDirectory: 'instances',
    instancesShutdownTimeoutSeconds: 30,
    instancesKillTimeoutSeconds: 10,
    instancesLogRingBufferLines: 500,
    instancesMaxConsoleOutputLinesPerSecond: 200,
    resourcesMaxMemoryMb: 0,
    daemonHealthEnabled: true,
    daemonHealthBindAddress: '127.0.0.1',
    daemonHealthPort: 9091,
    daemonLogLevel: 'INFO',
    daemonLogFormat: 'HUMAN',
    reconnectInitialDelayMs: 1000,
    reconnectMaxDelayMs: 60000,
    reconnectMultiplier: 2.0,
    daemonModulesSigningRequired: false,
    daemonModulesSigningMode: 'COSIGN_BUNDLE',
    daemonModulesSigningTrustRoot: '',

    dashboardInstallDir: '/opt/prexorcloud/dashboard',
    dashboardPublicUrl: '',
    dashboardListenPort: 80,
    dashboardControllerUrl: '',
    dashboardAdminUser: 'admin',
    dashboardAdminPassword: '',

    helpOpen: null,
    collapsibles: { jwt: true },
    activeReviewTab: 'controller.yml',
    activePreviewTab: 'controller.yml',
    busy: false,
    installLog: [],
    installError: '',
    installErrorCode: '',
    installErrorDocsUrl: '',
    installValidationErrors: [],
    installDone: false,
    installPhases: [],
    installNextSteps: [],
    installStartedAt: null,
    installFinishedAt: null,

    cliLoginController: '',
    cliLoginUsername: '',
    cliLoginPassword: '',
    cliLoginBusy: false,
    cliLoginError: '',
    cliLoginDone: false,
    cliLoginAs: '',

    cliLoginAvailable: true,
    defaultsLoaded: false,

    nativeAllowed: false,
    nativeReason: '',

    // Default `true` so the UI doesn't briefly disable every install card
    // before /api/info resolves on first paint — the server overrides to
    // `false` on macOS/Windows during loadDefaults.
    installSupported: true,
    installUnsupportedReason: '',
  };
}

// Shape of /api/info — mirrors cli/internal/setupweb/install.go:infoResponse.
// Only the fields the wizard actually consumes are typed; anything else the
// server adds later is ignored (extra-field tolerance).
interface InfoResponse {
  defaults?: {
    controllerHttpPort?: number;
    controllerGrpcPort?: number;
    daemonControllerHost?: string;
    daemonControllerGrpcPort?: number;
    dashboardInstallDir?: string;
    dashboardListenPort?: string;
  };
  features?: {
    cliLogin?: boolean;
  };
  platform?: {
    os?: string;
    arch?: string;
    nativeAllowed?: boolean;
    nativeReason?: string;
    installSupported?: boolean;
    installUnsupportedReason?: string;
  };
}

// Keys whose value is `boolean` — used to type-narrow toggle helpers so we
// can't accidentally bind a toggle to a non-boolean field.
type BoolKeys = {
  [K in keyof WizardState]: WizardState[K] extends boolean ? K : never;
}[keyof WizardState];

type ListKeys = {
  [K in keyof WizardState]: WizardState[K] extends string[] ? K : never;
}[keyof WizardState];

export const useWizardStore = defineStore('wizard', {
  state: (): WizardState => defaultState(),
  getters: {
    // Which YAML/config files this mode generates — used by the live-preview
    // pane and the continue-bar copy.
    filesForMode(state): YamlFilename[] {
      return filesForModeImpl(state.mode, state.installMode, state.webServer);
    },
  },
  actions: {
    setMode(m: WizardMode) {
      // Mirror the Mode-screen UI: when the host can't install server-side
      // components, only 'cli' is a real choice. Refusing a non-cli set here
      // keeps the store honest even if a stale stepper or test bypasses the
      // disabled-card UI.
      if (!this.installSupported && m !== 'cli') return;
      this.mode = m;
    },
    setStep(s: WizardStep) {
      this.step = s;
    },
    advance() {
      if (this.step === 'mode') {
        this.step = this.mode === 'cli' ? 'cli-login' : 'essentials';
      } else if (this.step === 'essentials') {
        this.step = 'security';
      } else if (this.step === 'security') {
        this.step = 'review';
      }
    },
    toggleHelp(keyPath: string) {
      this.helpOpen = this.helpOpen === keyPath ? null : keyPath;
    },
    toggleCollapsible(id: string) {
      this.collapsibles = { ...this.collapsibles, [id]: !this.collapsibles[id] };
    },
    toggleBool(key: BoolKeys) {
      (this[key] as boolean) = !(this[key] as boolean);
    },
    setListItem(key: ListKeys, index: number, value: string) {
      const next = [...this[key]];
      next[index] = value;
      this[key] = next;
    },
    addListItem(key: ListKeys) {
      this[key] = [...this[key], ''];
    },
    removeListItem(key: ListKeys, index: number) {
      const next = [...this[key]];
      next.splice(index, 1);
      this[key] = next;
    },
    regenerateUuid() {
      this.uuid = genUuid();
    },
    // Local-storage URIs differ by install path: compose reaches the sidecar
    // by service name (mongo/redis), native reaches a host-installed service on
    // localhost. These helpers + the matchers below keep the URI in sync as the
    // operator flips between Docker and native.
    localMongoUri(): string {
      return this.installMode === 'native' ? 'mongodb://localhost:27017' : 'mongodb://mongo:27017';
    },
    localRedisUri(): string {
      return this.installMode === 'native' ? 'redis://localhost:6379' : 'redis://redis:6379';
    },
    setInstallMode(m: 'compose' | 'native') {
      if (m === 'native' && !this.nativeAllowed) return;
      this.installMode = m;
      // Re-point any local-storage URIs at the new path's address.
      if (this.mongoMode === 'local') this.databaseUri = this.localMongoUri();
      if (this.redisMode === 'local') this.redisUri = this.localRedisUri();
    },
    setMongoMode(m: 'local' | 'remote') {
      this.mongoMode = m;
      if (m === 'local') {
        this.databaseUri = this.localMongoUri();
      } else if (
        this.databaseUri === 'mongodb://mongo:27017' ||
        this.databaseUri === 'mongodb://localhost:27017'
      ) {
        this.databaseUri = '';
      }
    },
    setRedisMode(m: 'local' | 'remote') {
      this.redisMode = m;
      if (m === 'local') {
        this.redisUri = this.localRedisUri();
      } else if (
        this.redisUri === 'redis://redis:6379' ||
        this.redisUri === 'redis://localhost:6379'
      ) {
        this.redisUri = '';
      }
    },
    pushLog(msg: string, cls = '') {
      this.installLog = [...this.installLog, { msg, cls }];
    },
    // Open a new timeline phase, completing whatever phase was active.
    beginPhase(label: string) {
      const now = Date.now();
      const phases = this.installPhases.map((p) =>
        p.status === 'active' ? { ...p, status: 'done' as const, endedAt: now } : p,
      );
      phases.push({ label, status: 'active', startedAt: now, endedAt: null });
      this.installPhases = phases;
    },
    // Mark the active phase failed (terminal error mid-install).
    failActivePhase(detail: string) {
      const now = Date.now();
      this.installPhases = this.installPhases.map((p) =>
        p.status === 'active' ? { ...p, status: 'failed' as const, endedAt: now, detail } : p,
      );
    },
    // Complete any still-active phase (successful end of an install component).
    completeActivePhase() {
      const now = Date.now();
      this.installPhases = this.installPhases.map((p) =>
        p.status === 'active' ? { ...p, status: 'done' as const, endedAt: now } : p,
      );
    },
    reset() {
      Object.assign(this, defaultState());
    },
    async doInstall() {
      this.busy = true;
      this.installError = '';
      this.installErrorCode = '';
      this.installErrorDocsUrl = '';
      this.installValidationErrors = [];
      this.installLog = [];
      this.installPhases = [];
      this.installNextSteps = [];
      this.installStartedAt = Date.now();
      this.installFinishedAt = null;
      resetTerminal();
      try {
        if (this.mode === 'all' || this.mode === 'controller' || this.mode === 'controller-join') {
          await postInstall(this, '/api/install/controller', {
            installMode: this.installMode,
            httpPort: String(this.httpPort),
            grpcPort: String(this.grpcPort),
            corsOrigins: this.corsOrigins,
            mongoMode: this.mongoMode,
            mongoUri: this.databaseUri,
            redisMode: this.redisMode,
            redisUri: this.redisUri,
            yamlOverride: controllerYaml(this.$state),
            // controller-join: hand the operator-pasted wire token to the
            // installer so it writes config/security/pending-join-token —
            // the controller's bootstrap picks it up on first start and
            // runs ClusterControlService.startInJoinMode against the
            // existing cluster.
            joinToken: this.mode === 'controller-join' ? this.controllerJoinToken.trim() : '',
            enableOnBoot: this.enableOnBoot,
            startNow: this.startNow,
          });
        }
        if (this.mode === 'all' || this.mode === 'daemon') {
          await postInstall(this, '/api/install/daemon', {
            installMode: this.installMode,
            nodeId: this.nodeId,
            controllerHost: this.controllerHost || '127.0.0.1',
            grpcPort: String(this.controllerGrpcPort),
            joinToken: this.joinToken,
            yamlOverride: daemonYaml(this.$state),
            enableOnBoot: this.enableOnBoot,
            startNow: this.startNow,
          });
        }
        if (this.mode === 'dashboard') {
          await postInstall(this, '/api/install/dashboard', {
            installMode: this.installMode,
            webServer: this.webServer,
            installDir: this.dashboardInstallDir,
            publicUrl: this.dashboardPublicUrl,
            listenPort: String(this.dashboardListenPort),
            controllerUrl: this.dashboardControllerUrl,
            adminUser: this.dashboardAdminUser,
            adminPassword: this.dashboardAdminPassword,
          });
        }
        this.completeActivePhase();
        this.installDone = true;
      } catch (e) {
        this.installError = e instanceof Error ? e.message : String(e);
        this.failActivePhase(this.installError);
      } finally {
        this.installFinishedAt = Date.now();
        this.busy = false;
      }
    },
    async submitCliLogin() {
      this.cliLoginBusy = true;
      this.cliLoginError = '';
      try {
        const res = await fetch('/api/cli/login', {
          method: 'POST',
          headers: apiHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({
            controller: this.cliLoginController.trim(),
            username: this.cliLoginUsername.trim(),
            password: this.cliLoginPassword,
          }),
        });
        const body = (await res.json().catch(() => ({}))) as {
          ok?: boolean;
          error?: string;
          controller?: string;
          username?: string;
        };
        if (!res.ok || body.ok === false) {
          this.cliLoginError = body.error || `Login failed (${res.status})`;
          return;
        }
        this.cliLoginDone = true;
        this.cliLoginController = body.controller || this.cliLoginController;
        this.cliLoginAs = body.username || this.cliLoginUsername;
        this.cliLoginPassword = '';
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        this.cliLoginError = `Can't reach the wizard server: ${msg}`;
      } finally {
        this.cliLoginBusy = false;
      }
    },
    async exitWizard() {
      try {
        await apiFetch('/api/exit', { method: 'POST' });
      } catch {
        // server may already be torn down — ignore.
      }
    },
    // Pulls /api/info once at boot and merges the host-derived defaults into
    // the wizard state. Idempotent — repeat calls short-circuit on
    // defaultsLoaded so a remount during dev doesn't clobber user edits.
    async loadDefaults() {
      if (this.defaultsLoaded) return;
      try {
        const res = await apiFetch('/api/info', { method: 'GET' });
        if (!res.ok) return;
        const info = (await res.json()) as InfoResponse;
        const d = info.defaults ?? {};
        if (typeof d.controllerHttpPort === 'number') this.httpPort = d.controllerHttpPort;
        if (typeof d.controllerGrpcPort === 'number') {
          this.grpcPort = d.controllerGrpcPort;
          this.controllerGrpcPort = d.controllerGrpcPort;
        }
        if (typeof d.daemonControllerHost === 'string' && d.daemonControllerHost !== '') {
          this.controllerHost = d.daemonControllerHost;
        }
        if (typeof d.daemonControllerGrpcPort === 'number') {
          this.controllerGrpcPort = d.daemonControllerGrpcPort;
        }
        if (typeof d.dashboardInstallDir === 'string' && d.dashboardInstallDir !== '') {
          this.dashboardInstallDir = d.dashboardInstallDir;
        }
        if (typeof d.dashboardListenPort === 'string' && d.dashboardListenPort !== '') {
          const n = Number(d.dashboardListenPort);
          if (Number.isFinite(n)) this.dashboardListenPort = n;
        }
        if (info.features?.cliLogin === false) {
          this.cliLoginAvailable = false;
        }
        if (info.platform?.nativeAllowed === true) {
          this.nativeAllowed = true;
          this.nativeReason = '';
        } else if (info.platform) {
          this.nativeAllowed = false;
          this.nativeReason = info.platform.nativeReason ?? '';
        }
        // Server-side install gating. Defaults stay `true` so first paint
        // doesn't flash a disabled UI; the server flips it to `false` here
        // when this host can't run server components (macOS/Windows). When
        // unsupported, snap the default mode to 'cli' so the operator lands
        // on the only card they can actually use.
        if (info.platform?.installSupported === false) {
          this.installSupported = false;
          this.installUnsupportedReason = info.platform.installUnsupportedReason ?? '';
          if (this.mode !== 'cli') this.mode = 'cli';
        } else if (info.platform?.installSupported === true) {
          this.installSupported = true;
          this.installUnsupportedReason = '';
        }
      } catch {
        // Server unreachable or response malformed — keep the schema defaults.
      } finally {
        this.defaultsLoaded = true;
      }
    },
  },
});

// One NDJSON event emitted by the streaming install handler.
interface InstallStreamEvent {
  type: 'step' | 'output' | 'done';
  msg?: string; // step (opens a new timeline phase)
  data?: string; // output (raw VPS terminal bytes — ANSI + \r intact)
  ok?: boolean; // done
  error?: string; // done
  errorCode?: string; // done
  docsUrl?: string; // done
  nextSteps?: string[]; // done
}

// Shared install-POST helper. Asks the wizard server to stream the install as
// newline-delimited JSON (Accept: application/x-ndjson) so the Review screen
// shows the *real* VPS console output (apt/cosign/systemctl …) live, line by
// line, instead of one batched dump at the end. Falls back to the legacy
// buffered JSON response if the server doesn't stream (older binary).
async function postInstall(
  store: ReturnType<typeof useWizardStore>,
  url: string,
  body: unknown,
): Promise<void> {
  store.pushLog('POST ' + url, '');
  const res = await fetch(url, {
    method: 'POST',
    headers: apiHeaders({
      'Content-Type': 'application/json',
      Accept: 'application/x-ndjson, application/json',
    }),
    body: JSON.stringify(body),
  });

  const ctype = res.headers?.get('Content-Type') || '';
  if (res.ok && res.body && ctype.includes('application/x-ndjson')) {
    await consumeInstallStream(store, url, res.body);
    return;
  }

  // Legacy buffered path: single JSON document, all messages at once. This is
  // also the path validation errors come back on (the install handler
  // refuses to stream a config that wouldn't even start) — capture the
  // structured fields so Review.vue can render the per-field block.
  const j = (await res.json().catch(() => ({}))) as {
    ok?: boolean;
    error?: string;
    errorCode?: string;
    docsUrl?: string;
    messages?: string[];
    nextSteps?: string[];
    validationErrors?: ConfigValidationError[];
  };
  (j.messages || []).forEach((m) => store.pushLog(m, ''));
  if (!res.ok || j.ok === false) {
    const msg = j.error || `HTTP ${res.status}`;
    store.pushLog('✗ ' + msg, 'err');
    if (j.errorCode) store.installErrorCode = j.errorCode;
    if (j.docsUrl) store.installErrorDocsUrl = j.docsUrl;
    if (j.validationErrors?.length) store.installValidationErrors = j.validationErrors;
    throw new Error(msg);
  }
  store.pushLog('✓ ' + url + ' completed', 'ok');
  (j.nextSteps || []).forEach((m) => store.pushLog('  ' + m, ''));
}

// consumeInstallStream reads the NDJSON body, splitting on newlines and
// dispatching each event into the install log as it arrives. Throws on a
// done{ok:false} event so doInstall surfaces the error.
async function consumeInstallStream(
  store: ReturnType<typeof useWizardStore>,
  url: string,
  body: ReadableStream<Uint8Array>,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let failure: string | null = null;

  const handle = (evt: InstallStreamEvent) => {
    switch (evt.type) {
      case 'step': {
        const msg = evt.msg ?? '';
        store.pushLog(msg, '');
        store.beginPhase(msg);
        // Echo the step into the terminal as a bright header so the scrollback
        // is a complete record on its own.
        writeTerminal(`\x1b[1;36m\r\n▸ ${msg}\x1b[0m\r\n`);
        break;
      }
      case 'output':
        // Raw VPS terminal bytes — straight to xterm (also kept as a text-only
        // fallback line in installLog for non-terminal contexts/tests).
        if (evt.data) {
          writeTerminal(evt.data);
          store.pushLog(evt.data.replace(/\r/g, '').replace(/\n$/, ''), 'out');
        }
        break;
      case 'done':
        if (evt.ok) {
          store.pushLog('✓ ' + url + ' completed', 'ok');
          store.installNextSteps = [...store.installNextSteps, ...(evt.nextSteps || [])];
          writeTerminal('\x1b[1;32m\r\n✓ ' + url + ' completed\x1b[0m\r\n');
        } else {
          failure = evt.error || 'install failed';
          if (evt.errorCode) store.installErrorCode = evt.errorCode;
          if (evt.docsUrl) store.installErrorDocsUrl = evt.docsUrl;
          store.pushLog('✗ ' + failure, 'err');
          writeTerminal('\x1b[1;31m\r\n✗ ' + failure + '\x1b[0m\r\n');
        }
        break;
    }
  };

  const flushLine = (line: string) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
      handle(JSON.parse(trimmed) as InstallStreamEvent);
    } catch {
      // Tolerate a stray non-JSON line rather than abort the whole install.
    }
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (value) {
      buffer += decoder.decode(value, { stream: true });
      let nl = buffer.indexOf('\n');
      while (nl >= 0) {
        flushLine(buffer.slice(0, nl));
        buffer = buffer.slice(nl + 1);
        nl = buffer.indexOf('\n');
      }
    }
    if (done) break;
  }
  flushLine(buffer); // trailing line with no newline (shouldn't happen, be safe)

  if (failure !== null) throw new Error(failure);
}
