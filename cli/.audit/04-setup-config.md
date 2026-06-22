# prexorctl Audit 04 — setup / config / context / auth / version / stop

Scope: `cli/cmd/{setup*.go,config.go,context.go,login.go,version.go,stop.go}` plus
`cli/internal/{setup,setupweb,config}`. READ-ONLY audit, no source modified.

## Global flags (root.go:177-188, inherited by every command below)

| Flag | Short | Type | Default | Notes |
|------|-------|------|---------|-------|
| `--json` | `-j` | bool | false | JSON output; also forces no-color. `PREXOR_OUTPUT=json` sets it. |
| `--controller` | `-c` | string | "" | Override controller URL (also `PREXOR_CONTROLLER`). |
| `--token` | `-t` | string | "" | Override auth token (also `PREXOR_TOKEN`). |
| `--context` | | string | "" | Override active context (also `PREXOR_CONTEXT`). |
| `--no-color` | | bool | false | Disable color. |
| `--ascii` | | bool | false | ASCII glyphs only. |
| `--verbose` | `-v` | bool | false | Show HTTP request/response. |

Pre-link gate (root.go:72-81): with no context + no `--controller`/env, only
`setup, login, logout, version, help, completion, context, cluster` run; everything
else errors "no cluster connected".

Config file: `~/.prexorcloud/config.yml` (config.go). Shape = `currentContext`,
`contexts{name:{controller,token}}`, `accent`. v1 flat configs migrated to a
`default` context. Token stored as plaintext JWT, file mode 0600, dir 0700.
`SaveAs(home,uid,gid)` chowns when setup runs under sudo (honors `SUDO_USER`).

---

# 1. `prexorctl setup` (wizard)

**Path:** `setup`
**Purpose:** Install/configure Controller, Daemon, or Dashboard.
**Usage:** `prexorctl setup [flags]` — NO positional args; component is a flag, not a subcommand.

### What it does
- Default UI = **browser wizard** (`runBrowserSetup`, setup_browser.go) on
  `127.0.0.1:9100`. Falls back to TTY on headless hosts (no DISPLAY/WAYLAND/BROWSER,
  `/.dockerenv`, `CI`, `PREXOR_NO_BROWSER`) — `isHeadless()` setup.go:240.
- `browserSetupRequested` (setup.go:208) decides: false if `--no-browser`/`--non-interactive`;
  true if `--public`/`--ssh-tunnel`/not-headless; else true only when inside SSH
  (`inSSHSession`, 4-field `SSH_CONNECTION`).
- TTY path: `refuseSetupOnUnsupportedOS` (Linux only) → header → `selectSetupComponent`
  → `selectSetupInstallMode` → compatibility/platform validation → component runner.
- Browser path bypasses the OS refusal (wizard's Mode screen gates in-UI; CLI-login
  card is the only non-Linux option).

### Flags (init at setup.go:253-318)

Browser/wizard:
| Flag | Type | Default | Function |
|------|------|---------|----------|
| `--browser` | bool | true | Open loopback wizard; `--browser=false` → TTY. |
| `--no-browser` | bool | false | Sugar for `--browser=false` (flipped in PersistentPreRunE). |
| `--browser-addr` | string | "" | host:port; empty → 127.0.0.1:9100 (or 0.0.0.0:9100 if `--public`). |
| `--browser-open` | bool | true | Try to launch system browser. |
| `--ssh-tunnel` | bool | false | Bind loopback, print laptop `ssh -L` cmd; auto-on when SSH+headless; overrides `--public`. |
| `--public` | bool | false | Non-loopback bind + TLS + token auth. |
| `--public-host` | string | "" | Hostname/IP printed in URL (default first non-loopback IPv4). |
| `--browser-idle-timeout` | duration | 0 | Idle auto-shutdown; 0 → 30m. |
| `--manage-firewall` | bool | true | In `--public`: open/close port via ufw/firewall-cmd/iptables. |

Non-interactive / shared lifecycle:
| Flag | Type | Default | Function |
|------|------|---------|----------|
| `--non-interactive` | bool | false | No prompts; use flags + defaults. |
| `--component` | string | "" | controller \| daemon \| dashboard (**help text wrong — see FINDINGS**). |
| `--install-mode` | string | "" | native \| compose (empty → native in NI mode). |
| `--service-mode` | string | "" | LEGACY: prompt/enable/disable systemd registration. |
| `--startup-validation-mode` | string | "" | LEGACY: prompt/enable/disable controller health-validate. |
| `--boot-mode` | string | "" | Canonical: auto-start on boot (systemctl enable / restart=unless-stopped). |
| `--start-mode` | string | "" | Canonical: start now (systemctl start / compose up -d). |

Controller flags: `--controller-install-dir` (default `/opt/prexorcloud/controller`),
`--controller-mongo-mode` (local|remote), `--controller-mongo-uri`,
`--controller-redis-mode`, `--controller-redis-uri`, `--controller-http-port`,
`--controller-grpc-port`, `--controller-cors-origin`.

Daemon flags: `--daemon-install-dir` (default `/opt/prexorcloud/daemon`),
`--daemon-node-id`, `--daemon-controller-host`, `--daemon-controller-grpc-port`,
`--daemon-controller-http-port` (default 8080), `--daemon-join-token`.

Dashboard flags: `--dashboard-install-dir` (`/opt/prexorcloud/dashboard`),
`--dashboard-public-url`, `--dashboard-serve-mode` (default `nginx`, **unused — FINDINGS**),
`--dashboard-tls-mode` (default `none`), `--dashboard-tls-email` (**unused — FINDINGS**),
`--dashboard-controller-url`, `--dashboard-admin-user` (default `admin`),
`--dashboard-admin-password`, `--dashboard-listen-port` (default `80`).

**`--json`:** NOT supported by `setup`. Output is styled lipgloss/huh; no machine-readable mode.

---

## 1a. Controller setup (setup_controller.go)
Interactive steps:
1. Dependency check — compose: Docker + Compose; native: must be **root**, ensure Java 25 (Temurin auto-download).
2. `resolveMongoDB` / `resolveRedis` (setup_native.go) — local vs remote; native local installs MongoDB 8.0 / Redis via package manager; remote prompts URI + TCP-dials; Redis remote also probes cluster ownership. Compose local → `mongodb://mongo:27017` / `redis://redis:6379`.
3. Release download — `FetchLatestRelease`, find `cloud-controller-` asset, confirm, download + cosign-verify to `<dir>/PrexorCloudController.jar`.
4. `promptControllerConfig` — HTTP port (8080), gRPC port, CORS origin (`http://localhost:3000`); writes `<dir>/config/controller.yml` (`WriteControllerConfig`).
5. `autoConfigureCLI` — writes controller URL to invoking user's `~/.prexorcloud/config.yml`.
6. Compose: `resolveEnableOnBoot`/`resolveStartNow` → restart policy → `WriteControllerComposeProject` (`docker-compose.yml`, `mongo-data/`, `redis-data/`) → optional `ComposeUp`.
7. Native: install systemd unit `prexorcloud-controller.service` (enable if boot, install-only if start-now), `StartAndValidateControllerService` (45s health), `VerifyNativeControllerInstall`, then `autoLoginAfterControllerReady` (reads `<dir>/config/.initial-admin-password`, logs in as **admin**, saves JWT).

systemd unit (service.go:23): `--enable-preview` java, `Restart=on-failure`, After/Wants mongod/redis when local.

## 1b. Daemon setup (setup_daemon.go)
1. Dependency check (compose: Docker; native: root + Java 25).
2. Release download — `cloud-daemon-` asset → `<dir>/PrexorCloudDaemon.jar`.
3. `promptDaemonConfig` — Node ID (hostname default), Controller host (required), gRPC port (default), join token (required). Writes `<dir>/config/daemon.yml`. Best-effort TCP dial.
4. Cluster enrolment — `ExchangeJoinToken` over controller HTTP (default port 8080) redeems token, installs cert, and if a CLI token is returned saves a context **named after the node** (`saveDaemonHostContext`). Failure is non-fatal (daemon retries via gRPC).
5. Compose or native systemd (`prexorcloud-daemon.service`), enable/start per `resolveEnableOnBoot`/`resolveStartNow`.

## 1c. Dashboard setup (setup_dashboard.go)
- **Compose only** — native hard-refused (setup_dashboard.go:30) even though native impl exists (see FINDINGS).
1. `promptDashboardConfig` — Public URL, Controller base URL, admin password (echo-masked), local port (80). TLSMode from flag.
2. Controller link — `POST /api/v1/auth/login` (hard fail if bad), then `PATCH /api/v1/admin/cors/origins` to register the public origin (warn on failure), save admin JWT as context **`dashboard`**.
3. `CreateDashboardDirs` + `WriteDashboardComposeProject` (nginx). Does not start; prints `docker compose up -d`.

---

# 2. `prexorctl config`

**Path:** `config` — parent group, no RunE.

### 2a. `config view`
- Purpose: show active context's controller/token (masked)/accent + config path.
- Usage: `config view`. No flags.
- **`--json`: yes** — `{context, controller, token(masked), configPath}`.
- Also prints "effective controller (from env/flag)" hint when it differs.

### 2b. `config set <key> <value>`
- Purpose: set value on active context. Keys: `controller` (URL-validated), `token`, `accent`.
- Usage: `config set <key> <value>` (ExactArgs 2). Auto-creates `default` context for controller/token.
- **`--json`: NO** — prints styled success only (masks token in echo).

### 2c. `config unset <key>`
- Purpose: clear `controller`/`token`/`accent` on active context.
- Usage: `config unset <key>` (ExactArgs 1).
- **`--json`: NO**.

---

# 3. `prexorctl context`

Parent group, no RunE. Stored in `~/.prexorcloud/config.yml`.

| Subcommand | Usage | Args | Purpose | `--json` |
|------------|-------|------|---------|----------|
| `list` (alias `ls`) | `context list` | none | List contexts (current marked `*`). | yes (`[{name,controller,current}]`) |
| `current` | `context current` | none | Print active context name; errors if none. | yes (`{name}`) |
| `use [name]` | `context use [name]` | ≤1 | Set active context; picker if no arg in TTY. | no |
| `add <name>` | `context add <name> --controller <url>` | 1 | Add context; `--controller` required+validated, `--token` optional. Errors if exists. | no |
| `remove [name]` (alias `rm`,`delete`) | `context remove [name]` | ≤1 | Remove; picker if no arg; `--force` needed to remove current. | no |

`add` flags: `--controller` (string, required), `--token` (string, optional).
`remove` flag: `--force` (bool, false).

---

# 4. Auth

### 4a. `prexorctl login`
- Purpose: authenticate with a controller; store JWT in current context.
- Usage: `login`. **NO flags at all.** Fully interactive huh form: Controller URL
  (only if not already configured), Username, Password (masked). POSTs
  `/api/v1/auth/login`, saves via `SetCurrentAuth`.
- **`--json`: NO**. **No `--username`/`--password`/`--controller-arg` — cannot be scripted (see FINDINGS).**

### 4b. `prexorctl logout`
- Purpose: clear stored token on the resolved context.
- Usage: `logout`. No own flags (uses global `--context`).
- **`--json`: NO**. Prints "Logged out" (doesn't name the context).

---

# 5. `prexorctl version`
- Purpose: show CLI + (if logged in) controller version.
- Usage: `version`. No own flags.
- **`--json`: yes** — `{cli, go, os, arch, controller_*}`. Controller block only
  fetched when a token is set; fetch errors silently swallowed.

---

# 6. `prexorctl stop`

**Path:** `stop [local|node|controller]`
**Purpose:** stop services locally or across the fleet.
**Bare behavior:** TTY → picker (`runStopChooser`); non-TTY → falls back to `stop local`.
**Persistent flag:** `--yes`/`-y` (bool, false) — skip confirmation for remote stops.

### 6a. `stop local`
- Usage: `stop local` (NoArgs). Detects controller+daemon via compose dir or systemd unit.
- systemd stop requires **root** (`sudo prexorctl stop local`); docker via caller's access.
- Errors if nothing installed. **`--json`: NO** (plain success/skip lines — inconsistent, see FINDINGS).

### 6b. `stop node [id]`
- Usage: `stop node [id]` (≤1). Picker if no id in TTY. `POST /api/v1/nodes/<id>/shutdown`.
- Immediate stop, no drain; confirm prompt (or `--yes`); non-TTY without `--yes` refuses.
- **`--json`: yes** (raw result map).

### 6c. `stop controller`
- Usage: `stop controller` (NoArgs). `POST /api/v1/system/shutdown` on connected controller.
- Confirm/`--yes` gating. Warns restart-always supervisors will restart it.
- **`--json`: yes**.

---

# FINDINGS

- **`prexorctl login` has NO `--username`/`--password`/`--controller` flags and no env-var path** (login.go:13-87) — it is a hard-interactive huh form, so it cannot be scripted, used in CI, or run non-interactively. This is the single biggest scriptability gap. (Bootstrap auto-login only happens inside `setup`; there is no headless `login`.)
- **`login` ignores `--json`** (login.go) — no machine-readable success/token-status output.
- **`--component` flag help is wrong/stale** (setup.go:287): says "controller or daemon" but `dashboard` is a valid, validated component (setup_helpers.go:193-202). Help omits dashboard.
- **Dead/unreferenced flags `--dashboard-serve-mode` and `--dashboard-tls-email`** (setup.go:312,314): `setupDashboardServeMode`/`setupDashboardTLSEmail` are registered but never consumed by the CLI dashboard path (setup_dashboard.go only reads PublicURL/ControllerURL/ListenPort/TLSMode/admin). They are no-ops that mislead operators into thinking serve-mode/ACME-email are configurable from the CLI.
- **Dashboard native install unreachable from the CLI** (setup_dashboard.go:30 hard-refuses native: "re-run with --install-mode=compose") even though a full native dashboard installer exists (`internal/setup/install_dashboard_native.go`, `internal/setupweb/native.go installDashboardNative`). Feature exists but only the browser wizard could ever reach it — CLI/wizard capability mismatch. `--dashboard-tls-mode` (none/letsencrypt/custom/...) is also only meaningful for the native path, so it's near-dead on the compose-only CLI path too.
- **Dead production code kept alive only by tests** (setup_systemd.go): `promptServiceRegistration` (line 11), `resolveServiceRegistration` (line 25), and `resolveControllerStartupValidation` (line 112) are referenced only from `setup_test.go` — the real flow uses `resolveEnableOnBoot`/`resolveStartNow`. Confusing residue from the `--service-mode`→`--boot-mode`/`--start-mode` migration.
- **Three overlapping lifecycle flags with no deprecation signposting** (setup.go:289-292): `--service-mode` + `--startup-validation-mode` (legacy) coexist with `--boot-mode` + `--start-mode` (canonical) as equal first-class flags in `--help`. The legacy pair is silently used only as a fallback (`firstNonEmpty`, setup_systemd.go:57,66). Operators get four flags for two questions with no "deprecated" hint.
- **`validateSetupModeCompatibility` only gates the legacy flag** (setup_helpers.go:215-228): it rejects `--service-mode=enable` with `--install-mode=compose`, but the canonical `--boot-mode`/`--start-mode` are NOT range-checked against compose there. Inconsistent validation surface between the old and new flags.
- **`config set` / `config unset` do not honor `--json`** (config.go:59-125) while `config view` does (config.go:27). Scripts that set values and parse output get plain styled text. Inconsistent across the same command group.
- **`config set token <value>` takes the secret as a CLI arg** (config.go:77-81) — leaks the JWT into shell history/process list. No stdin/`-`/prompt alternative.
- **`config set` help under-documents `accent`** (config.go:62): lists `accent` as a valid key but never states the allowed values (purple/cyan/green/amber, per config.go doc comment). Weak help.
- **No `config get <key>` single-value accessor** — only whole-config `view`. Forces `--json | jq` for one field; minor scripting friction.
- **`stop local` lacks `--json`** (stop.go:84-131) while sibling `stop node`/`stop controller` both support it (stop.go:180,218). Inconsistent within one command tree.
- **`stop` bare → `stop local` in non-TTY** (stop.go:30-35): a stray scripted/piped `prexorctl stop` silently stops THIS host's services (and may demand root mid-script). Surprising default for automation; documented but easy to footgun.
- **`logout` success message doesn't name the context cleared** (login.go:99) — just "Logged out". With multiple contexts the operator can't tell which token was removed. `logout` also has no `--json`.
- **Naming/shape inconsistency: `setup` uses flags-only for component selection, while `stop` uses real subcommands.** `prexorctl setup controller` does not work (must be `--component=controller`), unlike `prexorctl stop controller`. Inconsistent CLI grammar across two install/lifecycle verbs.
- **`autoLoginAfterControllerReady` hardcodes username `admin`** (setup_controller.go:388) — fine for first-boot bootstrap, but the timeout error message ("admin user may already exist") is the only feedback; a custom-admin or returning install silently skips auto-login.
- **`version` swallows controller-version fetch errors silently** (version.go:34,58) — if the controller is unreachable/unauthorized, the controller block just vanishes with no warning, even in `--json`. Hard to distinguish "no token" from "controller down".
- **`maskToken` collapses any token ≤10 chars to `***`** (config.go:145) — harmless, but means short/dev tokens give no prefix hint in `config view`.
- **Duplicate dashboard install-dir default literal** (setup.go:310 `"/opt/prexorcloud/dashboard"` vs setup_dashboard.go:19 `dashboardInstallDir` const) — two sources of truth for the same default; drift risk.
