# prexorctl Target Command Catalog

This document describes the **target (post-improvement) command surface** of `prexorctl`, the PrexorCloud control-plane CLI — the CLI *after* every approved de-clutter cut, every `CHANGE` disposition, every net-new command, and every cross-cutting behavior from `cli-modernization-roadmap.md` and `cli-design-review.md` has been applied. It is a complete functional reference written for a visual designer ("Claude Design"): for **every** command it states precisely what the command **Expects** (args, flags, interactive vs scripted) and what it **Delivers** (the exact output — fields, columns, data shape, structured-output views, interactive flow). It is the single source of truth a designer will style each screen from, and it tells the implementing engineer (`Impl note`) what is buildable client-side today versus what needs controller work.

This is a target, not the current build. The current surface (with per-command `KEEP`/`DECLUTTER`/`CHANGE` dispositions) is in `cli-command-catalog.md`; the rationale and phasing are in `cli-modernization-roadmap.md` and `cli-design-review.md`. Where this doc and those disagree, this doc is the intended end-state.

---

## Global behavior

These behaviors are defined **once** here and assumed on every command below. A command entry only re-mentions one of them when it has command-specific nuance (e.g. which `-o wide` columns it adds, or what its danger-gate prompt says).

### Output system (every command)

A single renderer backs a global, persistent flag on **every** command — reads, mutations, and errors alike:

| Flag | Short | Values | Notes |
|------|-------|--------|-------|
| `--output` | `-o` | `table`, `json`, `yaml`, `wide`, `name`, `jsonpath=<expr>`, `template=<go-tmpl>` | `table` is the default on a TTY. `--json` remains as a **hidden alias** for `-o json`. |

- **Total structured output.** Every command — including mutations (`create`/`delete`/`scale`/`deploy`/…) and including the bare host-lifecycle commands — can emit `json`/`yaml`. There are no "no JSON" commands in the target.
- **JSON error envelope.** Under any structured `-o`, failures are emitted as a machine-readable envelope on stderr, not plain text: `{"error":{"code":"<EXIT_KIND>","message":"<human reason>","httpStatus":<n>,"detail":<optional>}}`. stdout stays clean (data only); diagnostics, spinners, and HTTP trace go to stderr.
- **`-o wide`** adds extra columns to list/get views (called out per command where it matters).
- **`-o name`** prints bare identifiers, one per line (pipe-friendly).
- The TUI (pickers, panels, full-screen log/console views) is the `table`/interactive presentation layer only; it never gates data — `-o json|yaml` always produces the same data headlessly.

### Async-convergence (async operations)

Async, control-plane-mutating commands accept a shared wait layer:

| Flag | Meaning |
|------|---------|
| `--wait` | Block until the operation converges (or `--timeout`). |
| `--for=condition=<cond>` | Wait for a specific terminal condition: `running`, `deleted`, `healthy` (command-specific subset). Implies `--wait`. |
| `--timeout=<dur>` | Max wait (e.g. `90s`, `5m`). Default per-command; on expiry the command exits non-zero with the last observed state. |

Applies to: `group scale`, `group create`, `group delete`, `instance start`, `instance stop`, `node drain`, `deploy <group>`, `cluster leave`, `cluster join` (where present). **Delivers while waiting:** a live progress line on stderr (resource → observed state → target condition, e.g. `group edge: 2/4 instances running…`), refreshed in place; on success a one-line confirmation, on timeout the last observed state and a non-zero exit. Without `--wait` these commands return immediately after the request is accepted (today's behavior) and say so ("scheduled", "accepted").

### Danger-gate (every destructive command)

One uniform idiom replaces today's four:

- **TTY → confirm.** A single styled confirm prompt naming the exact resource and the consequence.
- **`--yes` / `-y`** bypasses the prompt (the only scripted path).
- **Typed-name confirm** for blast-radius / quorum ops (`cluster eject`, `cluster leave`, `cluster recover`, `cluster seed rotate`, `restore`, `group delete` of a non-empty group): the prompt requires the operator to retype the resource name/id, not just `y`.
- **`--dry-run`** where a server-side or local validation can preview the effect without writing.
- **Non-TTY guard.** With no TTY and no `--yes`, a destructive command **refuses** (clear "refusing to <verb> without --yes in a non-interactive session" error, non-zero) — it never silently no-ops and never proceeds unprompted.

Each destructive entry below states the specific confirm copy / typed-name requirement it delivers.

### HA-awareness

- **Multi-endpoint contexts + transparent failover.** A context may hold several controller endpoints; the client transparently fails over to another member (shared JWT + `/cluster/members` restAddrs) when the pinned one is unreachable. `context --discover` fills the endpoint list.
- **Leader/role/health/quorum** are first-class fields in `cluster status` / `cluster members` (leader marker, raft role, health, `lastSeen`, quorum math) and fused in `cluster health` / `cluster leader`.
- **Identity via local JWT decode.** `whoami` / `auth status` decode the stored JWT locally (user, role, expiry) with no server round-trip needed for the basics; server endpoints enrich them.

### No fabricated data

`status` and `group list` deliver **only real fields**. The synthetic TPS/Players/Memory sparklines, the per-group `SPARK (1h)` column, `UPDATED="just now"`, and the invented "recent events" are **removed**. Where a real metric slot exists it is shown; where no real value is available the column/card is omitted entirely (never faked). `whoami` in `status` shows the real decoded username/role, never `(authenticated)`.

### Global flags & environment

Persistent flags on every command:

| Flag | Short | Effect |
|------|-------|--------|
| `--output` | `-o` | Output format (see above). `--json`/`-j` hidden alias. |
| `--controller` | `-c` | Override controller URL(s) for this invocation. |
| `--token` | `-t` | Override auth token for this invocation. |
| `--context` | — | Override active context. |
| `--quiet` | `-q` | Suppress non-essential chatter (spinners, hints); data + errors only. |
| `--no-input` | — | Never prompt; disables pickers/confirms (a missing required arg becomes a clear "arg required" error, not a hang). |
| `--timeout` | — | Per-request / overall timeout. |
| `--no-color` | — | Disable ANSI color (also honored by huh prompts and `--help` in the target). |
| `--ascii` | — | ASCII-only glyphs. |
| `--verbose` | `-v` | HTTP request/response trace to **stderr**. |

Every flag mirrors to a `PREXOR_*` environment variable (`PREXOR_OUTPUT`, `PREXOR_CONTROLLER`, `PREXOR_TOKEN`, `PREXOR_CONTEXT`, `PREXOR_QUIET`, `PREXOR_NO_INPUT`, `PREXOR_TIMEOUT`, `PREXOR_NO_COLOR`/`NO_COLOR`, `PREXOR_ASCII`). Resolution precedence: **flag > `PREXOR_*` env > stored context**. Config stays at `~/.prexorcloud/config.yml` (0600/0700): `currentContext`, `contexts{name:{controller(s), token}}`, `accent`.

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic error |
| 2 | Diagnostic warning (e.g. `module doctor` warnings) |
| 3 | Forbidden (HTTP 403) |
| 4 | Not found (HTTP 404) |
| 5 | Connection error (wired up; conn failures no longer collapse to 1) |
| 6 | Auth error (HTTP 401) — its own code, distinct from the diagnostic-warning `2` |
| 124 | Wait/`--for` timeout (async ops) |

### Pre-link gate

Before a context resolves (no stored context, no `--controller`/env), only these run: `setup`, `login`, `whoami`, `version`, `help`, `completion`, `context`, and the purely local dev commands (`module new`/`scaffold`, `module doctor`, `module test`, `module build`/`sign`/`bundle`, `plugin new`). All local-only commands are annotated so the gate never blocks offline work. Any other command errors with "no cluster connected". Skipped for `--help`.

### Completion

`completion bash` uses `GenBashCompletionV2` (dynamic completions work in bash too). Dynamic resource completion + closed-set enum completion is registered across the board: groups, nodes, instances, crashes, contexts, users, roles, tokens, templates, deployments, catalog platform/version, and enum flags (`--role`, `--state`, `--strategy`, `--scaling-mode`, `--routing`, `--for`, `-o`, …).

### Imperative-first grammar

The CLI keeps its noun→verb grammar. The modern read verbs **`get`** and **`describe`** (with resource aliases and `-o wide|yaml`) are part of the target and documented at the end. Declarative `apply -f` / `diff` / `edit` are **not** in this target (see "Room left for").

---

## whoami

### `prexorctl whoami`
- **Path**: `prexorctl whoami`
- **Purpose**: Show who you are on the active context.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Decodes the stored JWT locally → a compact identity card: **Username**, **Role**, **Token expiry** (absolute + relative, with a warning style when near/after expiry), **Active context**, **Endpoint(s)**, and (best-effort) the connected **member / leader**. `-o json|yaml`: `{username, role, contextName, controller, expiresAt, expired, leader?}`. `-o name`: just the username. Runs pre-link (decodes whatever token exists) and degrades gracefully if no token.
- **Impl note**: `client-only` for the JWT decode; leader enrichment is `needs-server` (best-effort, omitted offline).

---

## auth

Parent group (`prexorctl auth`, no RunE) — identity & session. Server endpoints `/api/v1/auth/me`, `/auth/refresh`, `/auth/logout`, `/auth/change-password` are confirmed to exist.

### `prexorctl auth status`
- **Path**: `prexorctl auth status`
- **Purpose**: Detailed session/identity status.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Local JWT decode enriched by `GET /auth/me` — a card: **Username**, **Role + permission count**, **Issued / Expires** (relative, with an expiry-warning slot), **Active context**, **Endpoint(s)** and which member is currently serving + **leader**, **Token health** (valid / expiring-soon / expired). `-o json|yaml`: `{username, role, permissionCount, issuedAt, expiresAt, expired, expiresInSeconds, contextName, endpoints[], leader?}`.
- **Impl note**: `needs-server` for `/auth/me` enrichment; falls back to `client-only` local decode if offline.

### `prexorctl auth refresh`
- **Path**: `prexorctl auth refresh`
- **Purpose**: Refresh the stored token before it expires.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `POST /auth/refresh`; writes the new JWT to the active context and prints the new expiry (absolute + relative). `-o json|yaml`: `{refreshed:true, expiresAt}`.
- **Impl note**: `needs-server`.

### `prexorctl auth change-password`
- **Path**: `prexorctl auth change-password`
- **Purpose**: Change the current user's password.
- **Expects (args)**: none.
- **Expects (flags)**: `--current-password-stdin` (bool), `--new-password-stdin` (bool) — read from stdin for scripting; never accepts a password as a flag value or positional. On a TTY with neither flag, prompts (masked) for current + new + confirm.
- **Delivers**: `POST /auth/change-password`; prints a success line; optionally re-auths the stored token if the server rotates it. `-o json|yaml`: `{changed:true}`. Non-TTY without the stdin flags errors (no silent prompt).
- **Impl note**: `needs-server`.

---

## setup

Parent group (`prexorctl setup`, no RunE) — install/configure a host component. Component is selected by **subcommand** (`controller`/`daemon`/`dashboard`), not a `--component` flag.

**Shared lifecycle flags** (all setup subcommands): `--non-interactive` (bool; no prompts), `--install-mode` (string; `native|compose`), `--boot-mode` (string; auto-start on boot), `--start-mode` (string; start now), `--dry-run` (bool; plan the install, write nothing). The legacy `--service-mode` and `--startup-validation-mode` flags are **removed** (collapsed into `--boot-mode`/`--start-mode`). Idempotent re-run: re-running over an existing install reconciles rather than erroring. Browser-wizard flags (`--browser`/`--no-browser`/`--browser-addr`/`--browser-open`/`--ssh-tunnel`/`--public`/`--public-host`/`--browser-idle-timeout`/`--manage-firewall`) are shared too; default UI is the loopback browser wizard on `127.0.0.1:9100`, falling back to a TTY flow on headless hosts.

### `prexorctl setup controller`
- **Path**: `prexorctl setup controller [flags]`
- **Purpose**: Install/configure the Controller.
- **Expects (args)**: none.
- **Expects (flags)**: shared lifecycle/browser flags + `--controller-install-dir` (default `/opt/prexorcloud/controller`), `--controller-mongo-mode` (`local|remote`), `--controller-mongo-uri`, `--controller-redis-mode`, `--controller-redis-uri`, `--controller-http-port`, `--controller-grpc-port`, `--controller-cors-origin`. Any admin password/token is read from stdin/env, never argv.
- **Delivers**: Resolves Mongo/Redis, downloads + cosign-verifies the release jar, writes `controller.yml`, configures the CLI context (auto-login as `admin`), installs/starts via compose or a `prexorcloud-controller.service` systemd unit. Interactive: browser wizard or TTY prompts. `-o json|yaml`: structured install report `{component:"controller", installMode, paths, endpoints, started, bootEnabled}`; `--dry-run` returns the same shape with `applied:false` + the planned actions.
- **Impl note**: `client-only` (host lifecycle work the CLI already owns).

### `prexorctl setup daemon`
- **Path**: `prexorctl setup daemon [flags]`
- **Purpose**: Install/configure the Daemon and enrol it.
- **Expects (args)**: none.
- **Expects (flags)**: shared flags + `--daemon-install-dir` (default `/opt/prexorcloud/daemon`), `--daemon-node-id`, `--daemon-controller-host`, `--daemon-controller-grpc-port`, `--daemon-controller-http-port` (default 8080), `--daemon-join-token` (read from stdin/env, not argv).
- **Delivers**: Downloads the daemon jar, writes `daemon.yml`, exchanges the join token to enrol, installs via compose or `prexorcloud-daemon.service`. Same install-report JSON/YAML shape + `--dry-run` as above.
- **Impl note**: `client-only`.

### `prexorctl setup dashboard`
- **Path**: `prexorctl setup dashboard [flags]`
- **Purpose**: Install/configure the Dashboard.
- **Expects (args)**: none.
- **Expects (flags)**: shared flags + `--dashboard-install-dir` (default `/opt/prexorcloud/dashboard`), `--dashboard-public-url`, `--dashboard-tls-mode` (default `none`), `--dashboard-controller-url`, `--dashboard-admin-user` (default `admin`), `--dashboard-admin-password` (stdin/env), `--dashboard-listen-port` (default `80`). The dead `--dashboard-serve-mode` and `--dashboard-tls-email` flags are **removed**.
- **Delivers**: Writes an nginx compose project (compose path) or native static-serve, registers the CORS origin, saves a `dashboard` context. Same install-report JSON/YAML + `--dry-run`.
- **Impl note**: `client-only`.

---

## login

### `prexorctl login`
- **Path**: `prexorctl login [flags]`
- **Purpose**: Authenticate with a controller and store the JWT in the current context.
- **Expects (args)**: none.
- **Expects (flags)**: `--username` (string), `--password-stdin` (bool; read the password from stdin), `--controller` (string; URL, may also come from context). On a TTY with none of these, falls back to the interactive huh form (controller URL if unset, username, masked password).
- **Delivers**: `POST /api/v1/auth/login` → saves the token to the active context (`SetCurrentAuth`). Scripted path: `--username` + `--password-stdin` (+ `--controller`) with no prompts, suitable for CI/service tokens. `-o json|yaml`: `{loggedIn:true, username, controller, expiresAt}`. Non-TTY without credentials errors clearly.
- **Impl note**: `needs-server` (login endpoint); the scriptable wiring is `client-only`.

---

## logout

### `prexorctl logout`
- **Path**: `prexorctl logout`
- **Purpose**: Revoke the session server-side and clear the stored token.
- **Expects (args)**: none.
- **Expects (flags)**: global (`--context` to target a non-active context).
- **Delivers**: `POST /auth/logout` to revoke the token server-side, then removes the token from the resolved context. Prints "Logged out" (and notes the server-side revocation). `-o json|yaml`: `{loggedOut:true, revoked:true|false}` (`revoked:false` if the server call failed but the local token was still cleared).
- **Impl note**: `needs-server` for revocation; local clear is `client-only`.

---

## status

### `prexorctl status`
- **Path**: `prexorctl status`
- **Purpose**: Cluster overview dashboard.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/overview` + `GET /api/v1/groups` + best-effort `GET /api/v1/system/version`, rendered as: a banner with the **real** decoded identity (`whoami` = actual username/role, never `(authenticated)`), a CLUSTER / NODES / INSTANCES summary (counts, health, leader), a metrics card showing **only real values** (synthetic TPS/Players/Memory sparklines removed — a real metric slot if the controller exposes one, otherwise the card is omitted), a groups table (no `SPARK (1h)` column, no fabricated `UPDATED`), and footer hints. `-o json|yaml`: the **full** rendered model (`{overview, groups, version, identity}`) — no longer a strict subset of the TUI. `-o wide`: adds per-node/per-group detail columns.
- **Impl note**: `needs-server` for any real metric slot; the de-faking + total JSON is `client-only`.

---

## version

### `prexorctl version`
- **Path**: `prexorctl version`
- **Purpose**: Show the CLI version and, if logged in, the controller version.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Prints CLI / Go / OS / arch; fetches the controller version block when a token is set. `-o json|yaml`: `{cli, go, os, arch, controller_*}`.
- **Impl note**: `client-only` (controller block is best-effort `needs-server`).

---

## config

Parent group (`prexorctl config`, no RunE) — active-context settings in `~/.prexorcloud/config.yml`.

### `prexorctl config view`
- **Path**: `prexorctl config view`
- **Purpose**: Show the active context's settings.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Prints controller(s), masked token, accent, config path; notes the effective controller from env/flag when it differs. `-o json|yaml`: `{context, controller(s), token(masked), accent, configPath}`.
- **Impl note**: `client-only`.

### `prexorctl config get <key>`
- **Path**: `prexorctl config get <key>`
- **Purpose**: Print a single config value for the active context.
- **Expects (args)**: `<key>` (required): `controller`, `token` (masked), or `accent`.
- **Expects (flags)**: global only.
- **Delivers**: The single value on stdout (token masked). `-o json|yaml`: `{key, value}`. Pipe-friendly bare value with `-o name`.
- **Impl note**: `client-only`.

### `prexorctl config set <key> <value>`
- **Path**: `prexorctl config set <key> [<value>]`
- **Purpose**: Set a value on the active context.
- **Expects (args)**: `<key>` (required): `controller` (URL-validated), `token`, `accent` (purple|cyan|green|amber). `<value>` is required for `controller`/`accent`; for `token` the value is **never** taken from argv — it is read from stdin (`config set token` then pipe/type the JWT), so it can't leak to shell history.
- **Expects (flags)**: global only.
- **Delivers**: Writes the value (auto-creating a `default` context for controller/token). `-o json|yaml`: `{key, set:true}` (token value never echoed).
- **Impl note**: `client-only`.

### `prexorctl config unset <key>`
- **Path**: `prexorctl config unset <key>`
- **Purpose**: Clear a value on the active context.
- **Expects (args)**: `<key>` (required): `controller`, `token`, `accent`.
- **Expects (flags)**: global only.
- **Delivers**: Removes the key. `-o json|yaml`: `{key, unset:true}`.
- **Impl note**: `client-only`.

---

## context

Parent group (`prexorctl context`, no RunE) — named contexts in `~/.prexorcloud/config.yml`. Contexts may hold **multiple** controller endpoints (HA).

### `prexorctl context list` (alias `ls`)
- **Path**: `prexorctl context list`
- **Purpose**: List contexts (current marked `*`).
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Table NAME / CONTROLLER(S) / CURRENT. `-o json|yaml`: `[{name, controllers[], current}]`. `-o name`: context names.
- **Impl note**: `client-only`.

### `prexorctl context current`
- **Path**: `prexorctl context current`
- **Purpose**: Print the active context name.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: The active context name; errors if none. `-o json|yaml`: `{name}`.
- **Impl note**: `client-only`.

### `prexorctl context use [name]`
- **Path**: `prexorctl context use [name]`
- **Purpose**: Set the active context.
- **Expects (args)**: `[name]` optional (picker on a TTY; `--no-input` requires the arg).
- **Expects (flags)**: global only.
- **Delivers**: Sets `currentContext`. Interactive: context picker if no arg. `-o json|yaml`: `{current:<name>}`.
- **Impl note**: `client-only`.

### `prexorctl context add <name>`
- **Path**: `prexorctl context add <name> --controller <url> [--controller <url> ...]`
- **Purpose**: Add a new context (errors if it exists).
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: `--controller` (string, required, URL-validated, **repeatable** for multi-endpoint HA contexts), `--token-stdin` (bool; read the token from stdin — the old `--token <value>` flag is removed so the JWT never lands in shell history).
- **Delivers**: Adds the context. `-o json|yaml`: `{name, controllers[], hasToken}`.
- **Impl note**: `client-only`.

### `prexorctl context rename <old> <new>`
- **Path**: `prexorctl context rename <old> <new>`
- **Purpose**: Rename a context.
- **Expects (args)**: `<old>` (required, existing), `<new>` (required, must not exist).
- **Expects (flags)**: global only.
- **Delivers**: Renames the context; updates `currentContext` if it was the active one. `-o json|yaml`: `{from, to}`.
- **Impl note**: `client-only`.

### `prexorctl context remove [name]` (aliases `rm`, `delete`)
- **Path**: `prexorctl context remove [name]`
- **Purpose**: Remove a context.
- **Expects (args)**: `[name]` optional (picker on a TTY).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass; removing the **current** context requires confirm or `--yes`).
- **Delivers**: Removes the named context. Danger-gate: confirm naming the context when it's the current one. `-o json|yaml`: `{removed:<name>}`.
- **Impl note**: `client-only`.

### `prexorctl context --discover`
- **Path**: `prexorctl context use <name> --discover` / `prexorctl context add <name> --controller <url> --discover`
- **Purpose**: Populate a context's endpoint list from the live cluster.
- **Expects (args)**: a context name (existing for `use`, new for `add`).
- **Expects (flags)**: `--discover` (bool) on `add`/`use`; uses the resolved token + one reachable controller.
- **Delivers**: Calls `GET /cluster/members`, writes every member's REST address into the context's endpoint list (enabling transparent failover). Prints the discovered endpoints. `-o json|yaml`: `{name, controllers[], discovered:<n>}`.
- **Impl note**: `needs-server` (`/cluster/members`); the merge into config is `client-only`.

---

## node

Parent group (`prexorctl node`, no RunE) — manage cluster nodes.

### `prexorctl node list`
- **Path**: `prexorctl node list`
- **Purpose**: List nodes.
- **Expects (args)**: none.
- **Expects (flags)**: `--state` (server-side filter: ONLINE|DRAINING|UNREACHABLE|OFFLINE), plus `--filter`/`--sort`/`--watch` (parity with `group list`).
- **Delivers**: `GET /api/v1/nodes`; table ID / STATUS / CPU / MEMORY / INSTANCES / CONNECTED SINCE + footer counts. `-o wide`: adds raft/role and last-seen. `-o json|yaml`: node array.
- **Impl note**: `needs-server`.

### `prexorctl node info [id]` (alias of `node get`/`describe node`)
- **Path**: `prexorctl node info [id]`
- **Purpose**: Show node details and its running instances.
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/nodes/<id>` (id URL-escaped); a resources card + running-instances table. `-o json|yaml`: full node object. Interactive: node picker if no arg.
- **Impl note**: `needs-server`.

### `prexorctl node drain [id]` (alias `cordon`+drain)
- **Path**: `prexorctl node drain [id]`
- **Purpose**: Mark a node DRAINING (stop scheduling) and optionally wait for it to empty.
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: async-convergence flags (`--wait`, `--for=condition=drained`, `--timeout`).
- **Delivers**: `POST /api/v1/nodes/<id>/drain`; without `--wait`, prints "set to DRAINING"; with `--wait`, shows the live drain progress (instances remaining) until empty or timeout. `-o json|yaml`: `{nodeId, state:"DRAINING", remainingInstances?}`.
- **Impl note**: `needs-server` (drain endpoint exists; the "empty" condition for `--wait` needs lightweight status semantics).

### `prexorctl node undrain [id]` (alias `uncordon`)
- **Path**: `prexorctl node undrain [id]`
- **Purpose**: Clear DRAINING.
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: global only.
- **Delivers**: `POST /api/v1/nodes/<id>/undrain`; prints "set to ONLINE". `-o json|yaml`: `{nodeId, state:"ONLINE"}`.
- **Impl note**: `needs-server`.

---

## group

Parent group (`prexorctl group`, no RunE) — manage server groups.

### `prexorctl group list`
- **Path**: `prexorctl group list`
- **Purpose**: List all groups.
- **Expects (args)**: none.
- **Expects (flags)**: `--filter` (client-side substring on name), `--sort` (`name|players|instances`), `--watch` (live re-render, Ctrl-C to exit).
- **Delivers**: `GET /api/v1/groups`; table GROUP / TYPE / STATUS / INSTANCES / PLAYERS / VERSION — **with no fabricated columns**: the `UPDATED="just now"`, the dead TPS/MEM columns, and the invented "recent events" are removed; only real fields appear. `-o wide`: adds scaling mode, min/max, routing, parent. `-o json|yaml`: group array (filter/sort honored in-process; `--watch` is TUI-only).
- **Impl note**: `needs-server`; the de-faking is `client-only`.

### `prexorctl group info [name]`
- **Path**: `prexorctl group info [name]`
- **Purpose**: Show group details (interactive panel on a TTY, static otherwise).
- **Expects (args)**: `[name]` optional (picker on a TTY).
- **Expects (flags)**: global only (`-o`/`--no-input` force the static/structured path).
- **Delivers**: `GET /api/v1/groups/<name>` (name URL-escaped). On a TTY (and not `-o`): a bubbletea panel that fetches `GET /api/v1/services?group=<name>` and offers in-view actions — `d` drain (`POST /services/<id>/stop`), `r` restart (`POST /services/<id>/force-stop`), Enter attaches the console. Non-TTY / `-o`: a static card (group config + instances table) with **no `parent <nil>`** glitch — null fields render as `—`. `-o json|yaml`: full group object.
- **Impl note**: `needs-server`.

### `prexorctl group create`
- **Path**: `prexorctl group create --name <n> --platform <p> [flags]`
- **Purpose**: Create a new group.
- **Expects (args)**: none.
- **Expects (flags)**: `--name` (required), `--platform` (required), `--platform-version`, `--template` (stringSlice; ordered layers), `--scaling-mode` (default `DYNAMIC`), `--min` (default 1), `--max` (default 10), `--max-players` (int; **new** — previously shown in `group info` but uncreatable), `--parent` (string; **new** — parent group), `--update-strategy` (string; **new**), `--memory` (default 1024 MB), `--routing` (default `LOWEST_PLAYERS`), `--port-start` (default 30000), `--port-end` (default 30100), `--jar-file` (default `server.jar`), plus async-convergence flags (`--wait`, `--for=condition=running`, `--timeout`).
- **Delivers**: `POST /api/v1/groups`. Without `--wait`, returns the created group; with `--wait`, blocks until min instances are running (live progress). `-o json|yaml`: created group object.
- **Impl note**: `needs-server`.

### `prexorctl group update [name]`
- **Path**: `prexorctl group update [name] [flags]`
- **Purpose**: Update mutable group fields.
- **Expects (args)**: `[name]` optional (picker on a TTY).
- **Expects (flags)**: `--min`, `--max`, `--memory`, `--routing`, `--scaling-mode`, `--max-players`, `--update-strategy` (all optional; only set flags are sent).
- **Delivers**: Builds a body of only changed flags → `PATCH /api/v1/groups/<name>`. `-o json|yaml`: updated group object.
- **Impl note**: `needs-server`.

### `prexorctl group scale [name] [replicas]`
- **Path**: `prexorctl group scale [name] [replicas]`
- **Purpose**: Set the `minInstances` floor to N (raising `maxInstances` to match if lower).
- **Expects (args)**: `[name]` and `[replicas]` both optional (picker / numeric prompt fill gaps on a TTY).
- **Expects (flags)**: async-convergence flags (`--wait`, `--for=condition=running`, `--timeout`).
- **Delivers**: Validates replicas ≥ 0; `GET` group for `maxInstances`; `PATCH` with `minInstances` (+ `maxInstances` if replicas exceed current max). Without `--wait`, "scaled to N (scheduling)"; with `--wait`, live progress `N/M running…` until converged or timeout. `-o json|yaml`: `{group, minInstances, maxInstances}`.
- **Impl note**: `needs-server`.

### `prexorctl group delete [name]`
- **Path**: `prexorctl group delete [name]`
- **Purpose**: Delete a group.
- **Expects (args)**: `[name]` optional (picker on a TTY).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass), `--dry-run` (report what would be deleted), async `--wait`/`--for=condition=deleted`/`--timeout`.
- **Delivers**: Danger-gate: confirm naming the group; **typed-name confirm** if the group still has running instances. Non-TTY without `--yes` refuses. Then `DELETE /api/v1/groups/<name>`. `-o json|yaml`: `{deleted:<name>}`.
- **Impl note**: `needs-server`.

### `prexorctl group maintenance [name] [on|off]`
- **Path**: `prexorctl group maintenance [name] [on|off]`
- **Purpose**: Toggle maintenance (drain) mode.
- **Expects (args)**: `[name]` and `[on|off]` both optional (picker + on/off select on a TTY).
- **Expects (flags)**: global only.
- **Delivers**: Maps `on|true|1` → enabled, else disabled; `PATCH /api/v1/groups/<name>` with `{maintenance:bool}`. `-o json|yaml`: `{group, maintenance:bool}`.
- **Impl note**: `needs-server`.

---

## catalog

Parent group (`prexorctl catalog`, no RunE) — manage the server platform catalog.

### `prexorctl catalog list`
- **Path**: `prexorctl catalog list`
- **Purpose**: List platforms/versions.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/catalog`; table PLATFORM / CATEGORY / VERSION / REC(★) / SHA256 / DOWNLOAD URL. `-o wide`: adds config-format + provenance/signature slot. `-o json|yaml`: catalog array.
- **Impl note**: `needs-server`.

### `prexorctl catalog add <platform>`
- **Path**: `prexorctl catalog add <platform> --version <v> [--url <u>] [flags]`
- **Purpose**: Add a version (creating the platform if new).
- **Expects (args)**: `<platform>` (required).
- **Expects (flags)**: `--version` (required), `--url` (string; optional if auto-resolvable, e.g. `catalog add paper --version 1.21` resolving URL+sha256 server-side), `--sha256`, `--category` (default `SERVER`), `--config-format`, `--recommended` (bool).
- **Delivers**: `POST /api/v1/catalog/<platform>/versions` (+ a follow-up recommended PUT if `--recommended`). `-o json|yaml`: created version object (incl. resolved url/sha256 when auto-resolved).
- **Impl note**: `needs-server`.

### `prexorctl catalog update [platform] [version]`
- **Path**: `prexorctl catalog update [platform] [version] [flags]`
- **Purpose**: Change a version's URL/checksum (and optionally rename).
- **Expects (args)**: `[platform]` and `[version]` optional (pickers on a TTY).
- **Expects (flags)**: `--url`, `--sha256`, `--new-version`.
- **Delivers**: `PATCH /api/v1/catalog/<platform>/versions/<version>` with **only the changed fields** — a rename-only edit no longer blanks `downloadUrl`/`sha256`. `-o json|yaml`: updated version object.
- **Impl note**: `needs-server`.

### `prexorctl catalog recommend [platform] [version]`
- **Path**: `prexorctl catalog recommend [platform] [version]`
- **Purpose**: Mark a version recommended.
- **Expects (args)**: `[platform]` and `[version]` optional (pickers on a TTY).
- **Expects (flags)**: global only.
- **Delivers**: `PUT /api/v1/catalog/<platform>/versions/<version>/recommended`. `-o json|yaml`: `{platform, version, recommended:true}`.
- **Impl note**: `needs-server`.

### `prexorctl catalog remove [platform] [version]` (aliases `rm`, `delete`)
- **Path**: `prexorctl catalog remove [platform] [version]`
- **Purpose**: Remove a version.
- **Expects (args)**: `[platform]` and `[version]` optional (pickers on a TTY).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate: confirm naming the platform+version; non-TTY without `--yes` refuses. `DELETE /api/v1/catalog/<platform>/versions/<version>`. `-o json|yaml`: `{platform, version, removed:true}`.
- **Impl note**: `needs-server`.

---

## instance

Parent group (`prexorctl instance`, alias `inst`, no RunE) — manage server instances.

### `prexorctl instance list`
- **Path**: `prexorctl instance list [flags]`
- **Purpose**: List instances.
- **Expects (args)**: none.
- **Expects (flags)**: `--group`, `--node`, `--state` (server-side query params), plus `--filter`/`--sort`/`--watch` parity.
- **Delivers**: `GET /api/v1/services`; table ID / GROUP / NODE / STATE / PORT / PLAYERS / UPTIME + footer. `-o wide`: adds memory + serving member. `-o json|yaml`: instance array.
- **Impl note**: `needs-server`.

### `prexorctl instance info [id]`
- **Path**: `prexorctl instance info [id]`
- **Purpose**: Show one instance's details.
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/services/<id>`; header + card (port/players/memory/uptime). `-o json|yaml`: full instance object. Interactive: instance picker if no arg.
- **Impl note**: `needs-server`.

### `prexorctl instance start <group>`
- **Path**: `prexorctl instance start <group> [flags]`
- **Purpose**: Start a new instance in a group.
- **Expects (args)**: `<group>` (required, exactly one).
- **Expects (flags)**: async-convergence (`--wait`, `--for=condition=running`, `--timeout`).
- **Delivers**: `POST /api/v1/groups/<group>/start`. Without `--wait`, "<count> instance(s) scheduled"; with `--wait`, blocks to running. `-o json|yaml`: `{group, scheduled:<count>, instanceIds?}`.
- **Impl note**: `needs-server`.

### `prexorctl instance stop [id]`
- **Path**: `prexorctl instance stop [id] [flags]`
- **Purpose**: Stop an instance.
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: `--force` (routes to `/force-stop`), `--yes`/`-y` (danger-gate bypass), async `--wait`/`--for=condition=deleted`/`--timeout`.
- **Delivers**: Danger-gate confirm naming the instance; non-TTY without `--yes` refuses. `POST /api/v1/services/<id>/stop` (or `/force-stop`). Without `--wait`, "stopping"/"force-stopped"; with `--wait`, blocks until gone. `-o json|yaml`: `{instanceId, action:"stop"|"force-stop"}`.
- **Impl note**: `needs-server`.

### `prexorctl instance exec <id> <command...>`
- **Path**: `prexorctl instance exec <id> <command...>`
- **Purpose**: Send a console command to an instance.
- **Expects (args)**: `<id>` + `<command...>` (≥ two args; no picker).
- **Expects (flags)**: global only.
- **Delivers**: Joins the command args → `POST /api/v1/services/<id>/command`. `-o json|yaml`: `{instanceId, command, accepted:true}`.
- **Impl note**: `needs-server`.

### `prexorctl instance console [id]`
- **Path**: `prexorctl instance console [id]`
- **Purpose**: Attach to a live console (SSE).
- **Expects (args)**: `[id]` optional (picker on a TTY).
- **Expects (flags)**: global only (a TTY/interactive command).
- **Delivers**: Best-effort `GET /services/<id>` header, then streams `GET /api/v1/services/<id>/console` (ticketed SSE) with input via `POST .../command`; Ctrl-Q detaches. Full-screen log stream with input. (`-o json` is not meaningful for an interactive stream; the command is interactive-only.)
- **Impl note**: `needs-server`.

---

## template

Parent group (`prexorctl template`, no RunE) — manage templates.

### `prexorctl template list`
- **Path**: `prexorctl template list`
- **Purpose**: List templates.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/templates`; table NAME / HASH / SIZE / DESCRIPTION + footer. `-o json|yaml`: template array.
- **Impl note**: `needs-server`.

### `prexorctl template versions <name>`
- **Path**: `prexorctl template versions <name>`
- **Purpose**: Show a template's version history.
- **Expects (args)**: `<name>` (required; completion-backed).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/templates/<name>/versions`; numbered rows (version / hash / size / created). `-o json|yaml`: version array.
- **Impl note**: `needs-server`.

### `prexorctl template rollback <name>`
- **Path**: `prexorctl template rollback <name>`
- **Purpose**: Roll back to the previous template version.
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm. `POST /api/v1/templates/<name>/rollback`. `-o json|yaml`: `{status, template}`. (Distinct from `deploy rollback` — template content rollback, not a deployment revert; help cross-references both.)
- **Impl note**: `needs-server`.

---

## deploy

`prexorctl deploy <group>` is runnable (triggers a rollout) and hosts subcommands.

### `prexorctl deploy <group>` (trigger)
- **Path**: `prexorctl deploy <group> [flags]`
- **Purpose**: Trigger a rolling deployment for a group.
- **Expects (args)**: `<group>` (required, exactly one).
- **Expects (flags)**: `--strategy`, `--batch-size`, `--canary-instances`, `--canary-percent`, `--health-gate`, `--auto-rollback`, `--promotion-timeout` (sec), `--min-healthy` (sec), `--yes`/`-y` (danger-gate bypass), async `--wait`/`--for=condition=healthy`/`--timeout`.
- **Delivers**: Builds a body of only changed flags. On a TTY: `GET` group → plan preview → danger-gate confirm → `POST .../deploy` → live polling of `GET .../deployments/<rev>` (rollout progress). `--yes` skips the confirm. `-o json|yaml`: deployment result (`{rev, state, strategy}`); with `--wait`, exits by terminal rollout state.
- **Impl note**: `needs-server`.

### `prexorctl deploy list <group>`
- **Path**: `prexorctl deploy list <group> [flags]`
- **Purpose**: Deployment history.
- **Expects (args)**: `<group>` (required).
- **Expects (flags)**: `--page` (default 1), `--page-size` (default 50; max 100).
- **Delivers**: `GET /api/v1/groups/<group>/deployments?page&pageSize`; table REV / STATE / STRATEGY / TRIGGER / PROGRESS / CREATED. `-o json|yaml`: page envelope.
- **Impl note**: `needs-server`.

### `prexorctl deploy show <group> <rev>`
- **Path**: `prexorctl deploy show <group> <rev>`
- **Purpose**: Show one deployment's details.
- **Expects (args)**: `<group>` + `<rev>` (both required).
- **Expects (flags)**: global only.
- **Delivers**: `GET .../deployments/<rev>`; KV blocks incl. nested rollout config. `-o json|yaml`: full deployment object.
- **Impl note**: `needs-server`.

### `prexorctl deploy pause <group> <rev>`
- **Path**: `prexorctl deploy pause <group> <rev>`
- **Purpose**: Pause an in-progress deployment.
- **Expects (args)**: `<group>` + `<rev>` (required).
- **Expects (flags)**: global only.
- **Delivers**: `POST .../deployments/<rev>/pause`. `-o json|yaml`: deployment state.
- **Impl note**: `needs-server`.

### `prexorctl deploy resume <group> <rev>`
- **Path**: `prexorctl deploy resume <group> <rev>`
- **Purpose**: Resume a paused deployment.
- **Expects (args)**: `<group>` + `<rev>` (required).
- **Expects (flags)**: global only.
- **Delivers**: `POST .../deployments/<rev>/resume`. `-o json|yaml`: deployment state.
- **Impl note**: `needs-server`.

### `prexorctl deploy rollback <group> <rev>`
- **Path**: `prexorctl deploy rollback <group> <rev>`
- **Purpose**: Roll back a deployment (revert to the prior revision).
- **Expects (args)**: `<group>` + `<rev>` (required).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass), async `--wait`/`--timeout`.
- **Delivers**: Danger-gate confirm naming the group+rev. `POST .../deployments/<rev>/rollback`. `-o json|yaml`: deployment state. (Distinct from `template rollback`; help disambiguates.)
- **Impl note**: `needs-server`.

---

## module

Parent group (`prexorctl module`, no RunE) — manage modules. (`module upload` is **removed** — folded into `module install`.)

### `prexorctl module list`
- **Path**: `prexorctl module list`
- **Purpose**: List installed modules.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/modules`; table NAME / ENABLED / FRONTEND / PLUGINS + footer counts. `-o wide`: adds version + capabilities. `-o json|yaml`: module array.
- **Impl note**: `needs-server`.

### `prexorctl module install <source>`
- **Path**: `prexorctl module install <jar|bundle.tar|id[@version]> [flags]`
- **Purpose**: Install a module from a local jar, a signed bundle, or a configured registry. (Absorbs the old `module upload`: a bare local `.jar` with no sidecar is the unsigned-upload path.)
- **Expects (args)**: `<source>` (required): a local `.jar` (sidecar auto-detected; unsigned allowed), a `.tar`/`.tar.gz`/`.tgz` bundle (exactly one jar, ≤1 sidecar), or a registry spec `id`/`id@version`/`id@latest`.
- **Expects (flags)**: `--signature` (explicit sidecar path), `--check-requires` (preflight capability check, non-fatal), `--registry` (pin one registry URL).
- **Delivers**: Local source → `POST /api/v1/modules/platform/upload` (multipart, with signature if present). Registry source → `POST /api/v1/modules/platform/registry/install` with `{moduleId, version?, registryUrl?}` (controller verifies sha256 + signature). `-o json|yaml`: `{moduleId, version, installed:true}` for both paths.
- **Impl note**: `needs-server`.

### `prexorctl module delete <name>`
- **Path**: `prexorctl module delete <name>`
- **Purpose**: Remove an installed module.
- **Expects (args)**: `<name>` (required; completion-backed).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the module; non-TTY without `--yes` refuses. `DELETE /api/v1/modules/platform/<name>`. `-o json|yaml`: `{module:<name>, deleted:true}`.
- **Impl note**: `needs-server`.

### `prexorctl module enable <name>`
- **Path**: `prexorctl module enable <name>`
- **Purpose**: Enable an installed but disabled module.
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: global only.
- **Delivers**: Enables the module (controller toggle). `-o json|yaml`: `{module:<name>, enabled:true}`.
- **Impl note**: `needs-server`.

### `prexorctl module disable <name>`
- **Path**: `prexorctl module disable <name>`
- **Purpose**: Disable an installed module without removing it.
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass — disabling can take features offline).
- **Delivers**: Danger-gate confirm. Disables the module. `-o json|yaml`: `{module:<name>, enabled:false}`.
- **Impl note**: `needs-server`.

### `prexorctl module describe <name>`
- **Path**: `prexorctl module describe <name>`
- **Purpose**: Show full detail on one installed module.
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: global only.
- **Delivers**: A detail card: id, version, enabled, frontend, plugins, declared capabilities, requires, signature/provenance, install source. `-o json|yaml`: full module descriptor.
- **Impl note**: `needs-server`.

### `prexorctl module search [query]`
- **Path**: `prexorctl module search [query]`
- **Purpose**: Browse modules offered by configured registries.
- **Expects (args)**: `[query]` optional (substring filter via `?q=`).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/modules/platform/registry?q=`; table MODULE / VERSION / REGISTRY / INSTALLED / TAGS. `-o json|yaml`: `{registries, modules}`.
- **Impl note**: `needs-server`.

### `prexorctl module upgrade [id]`
- **Path**: `prexorctl module upgrade [id]` (or `--all`)
- **Purpose**: Upgrade an installed module to the newest registry version.
- **Expects (args)**: `[id]` optional (mutually exclusive with `--all`).
- **Expects (flags)**: `--all` (upgrade every module with a newer version), `--registry` (pin one registry).
- **Delivers**: `GET .../registry` catalog, classify, `POST .../registry/install` pinned to the advertised version. `--all` iterates; non-zero if any failed. `-o json|yaml`: per-module result list (including the "already up to date" branch — total JSON, no text-only path).
- **Impl note**: `needs-server`.

### `prexorctl module build <name>`
- **Path**: `prexorctl module build <name>`
- **Purpose**: Build a module's jar locally (gradle).
- **Expects (args)**: `<name>` (required). Annotated `local-only`.
- **Expects (flags)**: `--repo-root` (default discovered upward), `--gradle-arg` (repeatable).
- **Delivers**: Runs the module's gradle assemble; prints the produced jar path. `-o json|yaml`: `{module, jarPath, sha256}`.
- **Impl note**: `client-only` (local gradle; no auth).

### `prexorctl module sign <jar>`
- **Path**: `prexorctl module sign <jar> [flags]`
- **Purpose**: Produce a cosign signature sidecar for a built module jar.
- **Expects (args)**: `<jar>` (required; must end `.jar`). Annotated `local-only`.
- **Expects (flags)**: `--key` (cosign key path / KMS ref), `--output`/`-o` semantics here are output-format only; the sidecar path is derived (`<jar>.sig`/`.bundle`) or set via `--sig-out`.
- **Delivers**: Signs the jar, writes the sidecar. `-o json|yaml`: `{jar, signaturePath, sha256}`.
- **Impl note**: `client-only`.

### `prexorctl module bundle <name|jar>`
- **Path**: `prexorctl module bundle <name|jar> [flags]`
- **Purpose**: Package a jar (+ signature sidecar) into an installable `.tar.gz` bundle.
- **Expects (args)**: `<name|jar>` (module name to build-then-bundle, or an explicit jar). Annotated `local-only`.
- **Expects (flags)**: `--signature` (sidecar to include; else auto-detected/auto-signed via `--sign`), `--sign` (sign during bundling), `--out` (bundle path).
- **Delivers**: Writes a `.tar.gz` containing exactly one jar + ≤1 sidecar (the shape `module install` consumes). `-o json|yaml`: `{bundlePath, jar, signed:bool, sha256}`.
- **Impl note**: `client-only`.

### `prexorctl module registry list`
- **Path**: `prexorctl module registry list`
- **Purpose**: List configured module registries.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Table NAME/URL/ENABLED (+ reachability). `-o json|yaml`: registry array.
- **Impl note**: `needs-server` (registries are controller-side config).

### `prexorctl module registry add <url>`
- **Path**: `prexorctl module registry add <url> [--name <n>]`
- **Purpose**: Register a module registry.
- **Expects (args)**: `<url>` (required, URL-validated).
- **Expects (flags)**: `--name` (optional label).
- **Delivers**: Adds the registry. `-o json|yaml`: `{name, url, added:true}`.
- **Impl note**: `needs-server`.

### `prexorctl module registry remove <name|url>`
- **Path**: `prexorctl module registry remove <name|url>`
- **Purpose**: Remove a configured registry.
- **Expects (args)**: `<name|url>` (required).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm. Removes the registry. `-o json|yaml`: `{registry, removed:true}`.
- **Impl note**: `needs-server`.

### `prexorctl module dev <name>`
- **Path**: `prexorctl module dev <name>`
- **Purpose**: Watch a module's jar (and frontend dist) and hot-reload to the controller.
- **Expects (args)**: `<name>` (required).
- **Expects (flags)**: `--repo-root` (default discovered upward), `--poll` (default 750ms), `--no-build` (don't spawn `gradlew assemble -t`).
- **Delivers**: Watches `build/libs/<archiveName>.jar`; on change `POST .../upload` (first) or `POST .../{moduleId}/upgrade` (install on 404). Watches `frontend/dist/` → `POST .../{moduleId}/frontend/reload`. Continuous gradle build unless `--no-build`. Foreground watch loop (Ctrl-C stops); status lines to stderr.
- **Impl note**: `needs-server` (hot-reload endpoints); the watch loop is `client-only`.

### `prexorctl module test <name>`
- **Path**: `prexorctl module test <name>`
- **Purpose**: Run a module's gradle `test` task.
- **Expects (args)**: `<name>` (required). Annotated `local-only`.
- **Expects (flags)**: `--repo-root`, `--gradle-arg` (repeatable).
- **Delivers**: Runs `./gradlew :cloud-modules:<name>:test --console=plain [extra]` from `java/`, forwarding stdio; propagates the gradle exit code.
- **Impl note**: `client-only` (no auth).

### `prexorctl module doctor <jar>`
- **Path**: `prexorctl module doctor <jar>`
- **Purpose**: Validate a built module jar against the platform-module contract, offline.
- **Expects (args)**: `<jar>` (required; must end `.jar`). Annotated `local-only`.
- **Expects (flags)**: global only.
- **Delivers**: Reads `META-INF/prexor/module.yaml` and runs controller-equivalent checks (manifest version, id/semver, entrypoint, capabilities, extension artifacts + sha256, signature sidecar). Exit codes: 0 clean, 2 warnings, 1 errors (the diagnostic-warning `2` is intentional here). `-o json|yaml`: `{ok, errors[], warnings[]}`.
- **Impl note**: `client-only`.

### `prexorctl module new [name]` (alias `module scaffold`)
- **Path**: `prexorctl module new [name]`
- **Purpose**: Scaffold a new cloud-module under `java/cloud-modules/<name>/`.
- **Expects (args)**: `[name]` optional (required for non-wizard paths). Annotated `local-only`.
- **Expects (flags)**: `--package` (default `me.prexorjustin.prexorcloud.modules.<name>`), `--repo-root`, `--strip-comments`, `--force` (overwrite), `--dry` (plan only), `--interactive` (legacy targets-only prompt), `--wizard` (force full wizard — and it **does** force it even when `--targets` is passed; help fixed), `--targets` (subset of the **single** canonical target list: paper, folia, velocity, bungeecord, bedrock-geyser), `--mc-plugin` (alias for `--targets`), `--all-defaults`, `--capabilities` (repeatable `id`/`id@version`), `--requires` (repeatable `id`/`id@range`), `--no-rest`, `--no-frontend`, `--no-plugin`. The dead `--browser` and the no-op `--no-mongo` flags are **removed**.
- **Delivers**: Resolves the repo root, generates from the `example` template with token replacement, prunes targets, applies overrides, patches `java/settings.gradle.kts`. Flow precedence: `--interactive` → targets prompt; composable spec flags → direct scaffold; `--targets`/`--mc-plugin`/`--all-defaults` → non-interactive scaffold; else → full TUI wizard (identity → rest/frontend → plugin → platform targets → per-target version strategy → optional jar-split → provides/requires). `-o json|yaml`: `{module, path, targets[], files[]}` (also drives `--dry`).
- **Impl note**: `client-only`.

---

## plugin

Parent group (`prexorctl plugin`, no RunE) — author standalone `@CloudPlugin` jars (Path A).

### `prexorctl plugin new <name>`
- **Path**: `prexorctl plugin new <name>`
- **Purpose**: Scaffold a standalone single-platform `@CloudPlugin` subproject.
- **Expects (args)**: `<name>` (required; **positional only** — the help no longer advertises a non-existent `--name` flag). Annotated `local-only`.
- **Expects (flags)**: `--platform` (required: paper|spigot|folia|velocity|bungeecord), `--mc-version` (paper only; 1.20 default or 1.21), `--package` (default `me.prexorjustin.prexorcloud.plugins.<name>`), `--repo-root`, `--description`, `--author` (default "PrexorCloud"), `--force` (overwrite), `--dry` (plan only).
- **Delivers**: Writes `java/cloud-plugin/cloud-plugin-<name>/` with `build.gradle.kts` and a `<Pascal>Plugin.java extends CloudPluginBase` annotated `@CloudPlugin`; patches `settings.gradle.kts`. `-o json|yaml`: `{plugin, path, platform, files[]}` (also drives `--dry`).
- **Impl note**: `client-only`.

---

## cluster

Parent group (`prexorctl cluster`, no RunE) — manage the controller cluster.

### `prexorctl cluster status`
- **Path**: `prexorctl cluster status`
- **Purpose**: Cluster id, membership, config version, and HA health at a glance.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/cluster` (+ members); prints clusterId, memberCount, activeConfigVersion, createdAt, and the HA summary: current **leader**, **quorum** math (have/need), overall **health**. `-o json|yaml`: `{clusterId, memberCount, activeConfigVersion, createdAt, leader, quorum:{have,need,healthy}}`.
- **Impl note**: `needs-server` (leader/quorum exposure in REST).

### `prexorctl cluster members`
- **Path**: `prexorctl cluster members`
- **Purpose**: List controller members with HA detail.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/cluster/members`; table NODE ID / ROLE (leader★/follower/listener) / HEALTH / RAFT ADDR / REST ADDR / GRPC ADDR / LABEL / LAST SEEN / JOINED AT. `-o wide`: adds commit index / lag. `-o json|yaml`: members array with `{role, health, lastSeen}`.
- **Impl note**: `needs-server` (role/health/lastSeen).

### `prexorctl cluster health`
- **Path**: `prexorctl cluster health`
- **Purpose**: One fused health verdict for the cluster.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: A single verdict (`HEALTHY` / `DEGRADED` / `NO_QUORUM`) with the supporting facts: members up/total, quorum have/need, leader present, any unreachable members. Exit code reflects the verdict (0 healthy, non-zero degraded/no-quorum) for scripting. `-o json|yaml`: `{verdict, membersUp, membersTotal, quorum:{have,need}, leader, unreachable[]}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster leader`
- **Path**: `prexorctl cluster leader [--transfer <nodeId>]`
- **Purpose**: Show (or transfer) the Raft leader.
- **Expects (args)**: none.
- **Expects (flags)**: `--transfer <nodeId>` (request a leadership transfer; danger-gated), `--yes`/`-y` (bypass).
- **Delivers**: Default: prints the current leader (nodeId, addr, since). With `--transfer`: danger-gate confirm (typed nodeId) then requests the transfer and reports the new leader. `-o json|yaml`: `{leader:{nodeId, restAddr, since}}` or `{transferredTo, leader}`.
- **Impl note**: `needs-server` (leader read; transfer endpoint).

### `prexorctl cluster leases`
- **Path**: `prexorctl cluster leases`
- **Purpose**: List active cluster leases (route exists; this adds the verb).
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Table LEASE / HOLDER / ACQUIRED / EXPIRES / STATE. `-o json|yaml`: lease array.
- **Impl note**: `needs-server` (route exists, needs the CLI verb).

### `prexorctl cluster eject <nodeId>`
- **Path**: `prexorctl cluster eject <nodeId>`
- **Purpose**: Force-remove a controller from the Raft group (irreversible).
- **Expects (args)**: `<nodeId>` (required, exactly one).
- **Expects (flags)**: `--yes`/`-y` (bypass), `--reason` (audit reason).
- **Delivers**: Danger-gate: **typed-name confirm** (retype the nodeId) — quorum blast-radius; non-TTY without `--yes` refuses. `DELETE /api/v1/cluster/members/<nodeId>[?reason=...]` (path + reason URL-escaped). `-o json|yaml`: `{ejected:<nodeId>, reason?}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster leave`
- **Path**: `prexorctl cluster leave`
- **Purpose**: Have the targeted controller gracefully leave and shut down.
- **Expects (args)**: none.
- **Expects (flags)**: `--yes`/`-y` (bypass), async `--wait`/`--timeout`.
- **Delivers**: Danger-gate: **typed-name confirm** (retype the controller nodeId). `POST /api/v1/cluster/leave` (empty body); prints "Controller <nodeId> leaving cluster <clusterId>". With `--wait`, blocks until membership reflects the departure. `-o json|yaml`: `{nodeId, clusterId, leaving:true}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster join-token create`
- **Path**: `prexorctl cluster join-token create [flags]`
- **Purpose**: Issue a new cluster (controller-to-controller) join token; prints the wire token once. *(Distinct from `prexorctl token` which issues node/daemon tokens — help on both cross-references the other.)*
- **Expects (args)**: none.
- **Expects (flags)**: `--ttl-seconds` (default 86400), `--label`, `--join-addr` (stringSlice, required, repeatable; existing controller gRPC host:port).
- **Delivers**: `POST /api/v1/cluster/join-tokens` with `{ttlSeconds, joinAddrs[, label]}`; prints JTI, token, expiry, "shown once" warning. `-o json|yaml`: `{jti, token, expiresAt, joinAddrs[]}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster join-token list`
- **Path**: `prexorctl cluster join-token list`
- **Purpose**: List outstanding cluster join tokens.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: Table JTI / LABEL / STATUS / CREATED AT / EXPIRES AT. `-o json|yaml`: tokens array.
- **Impl note**: `needs-server`.

### `prexorctl cluster join-token revoke <jti>`
- **Path**: `prexorctl cluster join-token revoke <jti>`
- **Purpose**: Revoke an outstanding cluster join token.
- **Expects (args)**: `<jti>` (required, exactly one; URL-escaped).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the JTI. `DELETE /api/v1/cluster/join-tokens/<jti>`. `-o json|yaml`: `{jti, revoked:true}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster seed rotate`
- **Path**: `prexorctl cluster seed rotate`
- **Purpose**: Rotate the cluster seed secret (HMAC key); invalidates all outstanding tokens.
- **Expects (args)**: none.
- **Expects (flags)**: `--yes`/`-y` (bypass).
- **Delivers**: Danger-gate: **typed-name confirm** (retype the clusterId — invalidates every token). `POST /api/v1/cluster/seed/rotate`; prints "Seed rotated for cluster <id> by <user> at <ts>". `-o json|yaml`: `{clusterId, rotatedBy, rotatedAt}`.
- **Impl note**: `needs-server`.

### `prexorctl cluster recover`
- **Path**: `prexorctl cluster recover [flags]`
- **Purpose**: Recover a degraded cluster — quorum-preserved shrink or catastrophic single-survivor reset.
- **Expects (args)**: none.
- **Expects (flags)**: `--eject` (stringSlice; dead nodeIds, skips the prompt), `--i-have-only-survivor` (print the catastrophic reset playbook), `--yes`/`-y` (bypass), `--dry-run` (list what would be ejected without ejecting).
- **Delivers**: With `--i-have-only-survivor`, prints the offline reset playbook (no API/FS change). Otherwise `GET /cluster/members`, prompts for comma-separated dead nodeIds (unless `--eject`), danger-gate **typed-name confirm** of the surviving clusterId, then loops `DELETE /cluster/members/<id>?reason=cluster+recover`. `-o json|yaml`: `{ejected[], survivors[]}` (and the playbook branch emits structured steps).
- **Impl note**: `needs-server`.

---

## token

Parent group (`prexorctl token`, no RunE) — node/daemon join tokens via `/api/v1/admin/tokens` (distinct from `cluster join-token`; help cross-references).

### `prexorctl token create`
- **Path**: `prexorctl token create [flags]`
- **Purpose**: Create a new node join token.
- **Expects (args)**: none.
- **Expects (flags)**: `--node` (node id; omitted from body if empty), `--ttl` (default `1h`).
- **Delivers**: `POST /api/v1/admin/tokens` with `{nodeId?, ttl?}`; prints token id, join token, node id, expiry. `-o json|yaml`: `{id, token, nodeId?, expiresAt}`.
- **Impl note**: `needs-server`.

### `prexorctl token list`
- **Path**: `prexorctl token list`
- **Purpose**: List node join tokens.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/admin/tokens`; table TOKEN ID / NODE / EXPIRES AT / STATUS. `-o json|yaml`: tokens array.
- **Impl note**: `needs-server`.

### `prexorctl token revoke <id>`
- **Path**: `prexorctl token revoke <id>`
- **Purpose**: Revoke a node join token.
- **Expects (args)**: `<id>` (required, exactly one; URL-escaped).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the token id. `DELETE /api/v1/admin/tokens/<id>`. `-o json|yaml`: `{id, revoked:true}`.
- **Impl note**: `needs-server`.

---

## user

Parent group (`prexorctl user`, no RunE) — manage users.

### `prexorctl user list`
- **Path**: `prexorctl user list`
- **Purpose**: List all users.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/users`; table ID / USERNAME / ROLE / CREATED AT + footer. `-o json|yaml`: user array.
- **Impl note**: `needs-server`.

### `prexorctl user create`
- **Path**: `prexorctl user create --username <name> [--role <role>] [flags]`
- **Purpose**: Create a user.
- **Expects (args)**: none.
- **Expects (flags)**: `--username` (required), `--role` (default `VIEWER`; ADMIN|OPERATOR|VIEWER, completion-backed), `--password-stdin` (bool; read password from stdin — scriptable). On a TTY without `--password-stdin`, prompts (masked) for the password; non-TTY without `--password-stdin` errors (no forced prompt).
- **Delivers**: `POST /api/v1/users` with `{username, password, role}`. `-o json|yaml`: created user object (password never echoed).
- **Impl note**: `needs-server`; the scriptable wiring is `client-only`.

### `prexorctl user delete <username>`
- **Path**: `prexorctl user delete <username>`
- **Purpose**: Delete a user.
- **Expects (args)**: `<username>` (required, exactly one; URL-escaped).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the user; non-TTY without `--yes` refuses. `DELETE /api/v1/users/<username>`. `-o json|yaml`: `{username, deleted:true}`.
- **Impl note**: `needs-server`.

---

## role

Parent group (`prexorctl role`, no RunE) — manage roles.

### `prexorctl role list`
- **Path**: `prexorctl role list`
- **Purpose**: List roles.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/roles`; table NAME / BUILT-IN / **PERMISSIONS (count)** — a permission *count*, not the full comma-joined set (which exploded table width). `-o wide`: shows the first few permissions + "… +N". `-o json|yaml`: role array (full permission lists intact).
- **Impl note**: `needs-server`; the count rendering is `client-only`.

### `prexorctl role show <name>`
- **Path**: `prexorctl role show <name>`
- **Purpose**: Show a single role with its full permission list.
- **Expects (args)**: `<name>` (required, exactly one).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/roles/<name>`; name, optional built-in note, bulleted permission list. `-o json|yaml`: full role object.
- **Impl note**: `needs-server`.

### `prexorctl role create`
- **Path**: `prexorctl role create --name <name> [--permissions a,b,c]`
- **Purpose**: Create a custom role with a permission set.
- **Expects (args)**: none.
- **Expects (flags)**: `--name` (required), `--permissions` (comma-separated, empty allowed; completion-backed).
- **Delivers**: `POST /api/v1/roles` with `{name, permissions:[...]}`. `-o json|yaml`: created role object.
- **Impl note**: `needs-server`.

### `prexorctl role update <name>`
- **Path**: `prexorctl role update <name> --permissions a,b,c`
- **Purpose**: Replace the permission set on an existing custom role.
- **Expects (args)**: `<name>` (required, exactly one).
- **Expects (flags)**: `--permissions` (required; replaces the existing set).
- **Delivers**: `PATCH /api/v1/roles/<name>` with `{permissions:[...]}`; built-in roles reject with 422. `-o json|yaml`: updated role object.
- **Impl note**: `needs-server`.

### `prexorctl role delete <name>`
- **Path**: `prexorctl role delete <name>`
- **Purpose**: Delete a custom role.
- **Expects (args)**: `<name>` (required, exactly one).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the role; non-TTY without `--yes` refuses. `DELETE /api/v1/roles/<name>`; built-in roles can't be deleted, in-use roles reject with 422. `-o json|yaml`: `{role, deleted:true}`.
- **Impl note**: `needs-server`.

---

## crash

Parent group (`prexorctl crash`, no RunE) — crash reports.

### `prexorctl crash list`
- **Path**: `prexorctl crash list`
- **Purpose**: List crash reports.
- **Expects (args)**: none.
- **Expects (flags)**: `--group`, `--node`, `--since` (ISO 8601; sent as wire param `from` — help says so).
- **Delivers**: `GET /api/v1/crashes?group=&node=&from=`; table ID / INSTANCE / GROUP / NODE / EXIT / CLASS / CRASHED AT / UPTIME. `-o json|yaml`: crash array.
- **Impl note**: `needs-server`.

### `prexorctl crash info [id]`
- **Path**: `prexorctl crash info [id]`
- **Purpose**: Show crash details (context card + last log lines), optionally share.
- **Expects (args)**: `[id]` optional (picker on a TTY; at most one; URL-escaped).
- **Expects (flags)**: share flags (`--share`, `--expiry`, `--public`, `--burn-after-read` — see **share**).
- **Delivers**: With `--share`, `POST /api/v1/crashes/<id>/share` and print the link; else `GET /api/v1/crashes/<id>` → crash card + last log lines. `-o json|yaml`: raw crash (or share result with `--share`). Interactive: crash picker when id omitted on a TTY.
- **Impl note**: `needs-server`.

---

## logs

Parent (`prexorctl logs`) with a runnable bare form. Persistent flags: `--follow`/`-f` (live tail), `--tail`/`-n` (default 200), `--level` (TRACE|DEBUG|INFO|WARN|ERROR; default INFO), `--logger` (logger-prefix filter), and HA member-targeting (`--member <nodeId>` / `--all-members`). When piped (`-o`/no TTY), output is plain text/JSON-lines, not the TUI.

### `prexorctl logs` (bare)
- **Path**: `prexorctl logs`
- **Purpose**: Choose what to tail.
- **Expects (args)**: none.
- **Expects (flags)**: persistent log flags.
- **Delivers**: On a TTY, a 4-way chooser (controller/daemon/instance/all), forcing `--follow=true`, delegating to the chosen subcommand. Non-interactive falls through to `logs controller`.
- **Impl note**: `needs-server`.

### `prexorctl logs controller`
- **Path**: `prexorctl logs controller`
- **Purpose**: View/tail controller logs (HA member-aware).
- **Expects (args)**: none.
- **Expects (flags)**: persistent + share flags + `--member`/`--all-members`.
- **Delivers**: With `--share` (rejected if `--follow`), `POST /system/logs/share`. Non-follow: `GET /api/v1/system/logs?level=&logger=&limit=<tail>`. Follow: seeds the page then SSE `/system/logs/stream` (ticketed) in the tail view. `--all-members` merges streams across controllers. `-o json|yaml` (non-follow) / JSON-lines when piped + `--follow`.
- **Impl note**: `needs-server`.

### `prexorctl logs daemon [node-id]`
- **Path**: `prexorctl logs daemon [node-id]`
- **Purpose**: View/tail a daemon's logs.
- **Expects (args)**: `[node-id]` optional (picker on a TTY; URL-escaped).
- **Expects (flags)**: persistent + share flags.
- **Delivers**: Same shape as controller but `/api/v1/nodes/<id>/logs[/share|/stream|/ticket]`. `-o json|yaml` when not `--follow`. Interactive: node picker when id omitted.
- **Impl note**: `needs-server`.

### `prexorctl logs instance [id]`
- **Path**: `prexorctl logs instance [id]`
- **Purpose**: View/tail an instance console.
- **Expects (args)**: `[id]` optional (picker on a TTY; URL-escaped).
- **Expects (flags)**: persistent + share flags. The `--level`/`--logger` flags are **removed** from this subcommand (they were accepted but ignored — an instance console has no log-level filtering).
- **Delivers**: With `--share` (rejected if `--follow`), `POST /services/<id>/console/share`. Non-follow: `GET /services/<id>/console/history?limit=<tail>`. Follow: SSE `/services/<id>/console` (read-only tail). `-o json|yaml` for the non-follow history path. Interactive: instance picker when id omitted.
- **Impl note**: `needs-server`.

### `prexorctl logs all`
- **Path**: `prexorctl logs all`
- **Purpose**: Merged live tail of every instance console.
- **Expects (args)**: none.
- **Expects (flags)**: `--group`, `--node` filters + persistent flags. On a TTY, full-screen merged TUI; piped, a merged plain/JSON-lines stream (no longer TUI-only).
- **Delivers**: `GET /api/v1/services`, filter by group/node, fan out one SSE console stream per instance into a merged colored view (TUI) or a merged line stream (piped).
- **Impl note**: `needs-server`.

---

## diagnostics

Parent group (`prexorctl diagnostics`, alias `diag`).

### `prexorctl diagnostics bundle`
- **Path**: `prexorctl diagnostics bundle`
- **Purpose**: Collect a redacted diagnostics bundle (tar.gz) locally, or share it.
- **Expects (args)**: none.
- **Expects (flags)**: `--out` (default `./prexorctl-diag-<timestamp>.tar.gz`; note: distinct from the global `-o`/`--output` format flag), `--log-lines` (default 500; 0 skips), share flags (`--share`, `--expiry`, `--public`, `--burn-after-read`).
- **Delivers**: With `--share`, `POST /system/diagnostics/share` and print the link (also writes the local bundle if `--out` given). Else `GET /api/v1/system/diagnostics` (+ optional logs), writing a tar.gz (manifest/readiness/overview/settings/config/redis/leases/logs). `-o json|yaml`: structured result on **both** the share and local-write paths (`{bundlePath?, shareUrl?, sizeBytes}`) — total, not share-only.
- **Impl note**: `needs-server`.

---

## share

Parent group (`prexorctl share`, alias `shares`).

**Share flags** (registered on `crash info`, `logs controller/daemon/instance`, `diagnostics bundle`): `--share` (upload a redacted copy to the paste service and print the link), `--expiry` (`1h|1d|30d|never`), `--public` (mark public; sent only if changed), `--burn-after-read` (destroy on first read; sent only if changed).

### `prexorctl share list`
- **Path**: `prexorctl share list`
- **Purpose**: List recent shares (newest first).
- **Expects (args)**: none.
- **Expects (flags)**: `--kind` (CRASH|CONTROLLER_LOGS|DAEMON_LOGS|DIAGNOSTICS|INSTANCE_CONSOLE), `--active-only`, `--limit` (default 50; → `pageSize`, server caps 200).
- **Delivers**: `GET /api/v1/shares?kind=&activeOnly=&pageSize=`; table ID / KIND / WHEN / BY / BYTES / URL / STATUS. `-o json|yaml`: full page envelope.
- **Impl note**: `needs-server`.

### `prexorctl share view <id>`
- **Path**: `prexorctl share view <id>`
- **Purpose**: View a single share.
- **Expects (args)**: `<id>` (required, exactly one; URL-escaped).
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/shares/<id>`; fields + a revocability hint. `-o json|yaml`: share object.
- **Impl note**: `needs-server`.

### `prexorctl share revoke <id>`
- **Path**: `prexorctl share revoke <id>`
- **Purpose**: Revoke a paste share.
- **Expects (args)**: `<id>` (required, exactly one).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate: `GET /api/v1/shares/<id>` then a typed/`y` confirm (unified gate); `POST /api/v1/shares/<id>/revoke`. `-o json|yaml`: revoked record.
- **Impl note**: `needs-server`.

---

## backup

Parent group (`prexorctl backup`, no RunE) — controller backups. Bundles live on the controller host; `backup pull` (new) is the only command that transports one to the client.

### `prexorctl backup create`
- **Path**: `prexorctl backup create`
- **Purpose**: Create a new backup bundle (Mongo + Redis + on-disk state).
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `POST /api/v1/backups`; prints id, size, mongo doc count, redis key count, file count. `-o json|yaml`: raw manifest.
- **Impl note**: `needs-server`.

### `prexorctl backup list`
- **Path**: `prexorctl backup list`
- **Purpose**: List backups stored on the controller.
- **Expects (args)**: none.
- **Expects (flags)**: global only.
- **Delivers**: `GET /api/v1/backups`; directory + retention header, then table ID / Created / Size / Mongo Docs / Redis Keys / Files. `-o json|yaml`: full envelope.
- **Impl note**: `needs-server`.

### `prexorctl backup verify <id>`
- **Path**: `prexorctl backup verify <id>`
- **Purpose**: Verify a bundle is restorable.
- **Expects (args)**: `<id>` (required, exactly one; URL-escaped).
- **Expects (flags)**: global only.
- **Delivers**: `POST /api/v1/backups/<id>/verify`; on invalid, prints the missing files/dirs/collections/prefixes/empty lists. **Exit code is non-zero for an INVALID bundle under any `-o`** (the JSON path no longer swallows the failure). `-o json|yaml`: raw verify response with an explicit `{valid:bool}`.
- **Impl note**: `needs-server`.

### `prexorctl backup pull <id>`
- **Path**: `prexorctl backup pull <id> [--out <path>]`
- **Purpose**: Download a backup bundle from the controller to the local machine.
- **Expects (args)**: `<id>` (required, exactly one).
- **Expects (flags)**: `--out` (local file path; default `./<id>.tar.gz`).
- **Delivers**: Streams the bundle to disk (progress on stderr); prints the local path + sha256. `-o json|yaml`: `{id, path, sizeBytes, sha256}`.
- **Impl note**: `needs-server` (a download endpoint must expose the bundle bytes; today the CLI never transports them).

### `prexorctl backup prune`
- **Path**: `prexorctl backup prune [flags]`
- **Purpose**: Delete backups beyond retention.
- **Expects (args)**: none.
- **Expects (flags)**: `--keep` (default 0 = server retentionCount default), `--yes`/`-y` (danger-gate bypass), `--dry-run` (list what would be removed).
- **Delivers**: Danger-gate confirm (counts to be removed); non-TTY without `--yes` refuses. `POST /api/v1/backups/prune[?keep=N]`; prints removed ids or "No backups pruned". `-o json|yaml`: `{removed:[ids], kept:N}`.
- **Impl note**: `needs-server`.

### `prexorctl backup delete <id>` (alias `rm`)
- **Path**: `prexorctl backup delete <id>`
- **Purpose**: Delete a single backup by id.
- **Expects (args)**: `<id>` (required, exactly one; URL-escaped).
- **Expects (flags)**: `--yes`/`-y` (danger-gate bypass).
- **Delivers**: Danger-gate confirm naming the id; non-TTY without `--yes` refuses. `DELETE /api/v1/backups/<id>`. `-o json|yaml`: `{id, deleted:true}`.
- **Impl note**: `needs-server`.

---

## restore

### `prexorctl restore [id]`
- **Path**: `prexorctl restore [id] [flags]`
- **Purpose**: Restore the controller from a backup bundle — the single highest-risk command (full live overwrite).
- **Expects (args)**: `[id]` (a backup id stored on the controller); omit when using `--from-file`.
- **Expects (flags)**: `--from-file <path>` (restore from a locally-held bundle, e.g. one fetched via `backup pull` — uploads then restores), `--dry-run` (validate + report, no writes), `--filesystem` (default true; restore on-disk filesystem), `--datastores` (default true; restore Mongo + Redis), `--yes`/`-y` (danger-gate bypass). The Long help documents exactly `--filesystem`/`--datastores` (the phantom `--no-files`/`--no-data` are gone).
- **Delivers**: Danger-gate: **typed-name confirm** (retype the backup id / "RESTORE") — there is no undo; non-TTY without `--yes` refuses; a **mandatory pre-restore snapshot** is taken first. `POST /api/v1/restore` with `{id|uploaded, dryRun, filesystem, datastores}`; prints applied/dry-run, filesystem entry count + rollback snapshot id, datastore counts. Rejected server-side if the bundle fails verification. `-o json|yaml`: raw response incl. the rollback snapshot id.
- **Impl note**: `needs-server` (restore + `--from-file` upload endpoint); the gate is `client-only`.

---

## stop

Parent (`prexorctl stop`) with a runnable bare form. Persistent flag: `--yes`/`-y` (danger-gate bypass).

### `prexorctl stop` (bare)
- **Path**: `prexorctl stop`
- **Purpose**: Stop services locally or across the fleet.
- **Expects (args)**: none.
- **Expects (flags)**: persistent `--yes`.
- **Delivers**: On a TTY, a chooser (local / node / controller). **Non-TTY no longer defaults to stopping the host** — it errors asking for an explicit subcommand (`stop local`/`node`/`controller`), removing the most destructive default.
- **Impl note**: `client-only` (dispatch).

### `prexorctl stop local`
- **Path**: `prexorctl stop local`
- **Purpose**: Stop this host's controller/daemon.
- **Expects (args)**: none.
- **Expects (flags)**: persistent `--yes`.
- **Delivers**: Danger-gate confirm (non-TTY requires `--yes`). Detects controller+daemon via the compose dir or systemd unit and stops them (systemd needs root). Errors if nothing is installed. `-o json|yaml`: `{stopped:[components]}`.
- **Impl note**: `client-only`.

### `prexorctl stop node [id]`
- **Path**: `prexorctl stop node [id]`
- **Purpose**: Stop a remote node (immediate, no drain).
- **Expects (args)**: `[id]` optional (picker on a TTY; URL-escaped).
- **Expects (flags)**: persistent `--yes`.
- **Delivers**: Danger-gate confirm naming the node; non-TTY without `--yes` refuses. `POST /api/v1/nodes/<id>/shutdown`. `-o json|yaml`: result map.
- **Impl note**: `needs-server`.

### `prexorctl stop controller`
- **Path**: `prexorctl stop controller`
- **Purpose**: Stop the connected controller.
- **Expects (args)**: none.
- **Expects (flags)**: persistent `--yes`.
- **Delivers**: Danger-gate confirm (quorum pre-flight warning if stopping it would lose quorum); non-TTY without `--yes` refuses. `POST /api/v1/system/shutdown`; warns that restart-always supervisors will restart it. `-o json|yaml`: result.
- **Impl note**: `needs-server`.

---

## completion

### `prexorctl completion [bash|zsh|fish|powershell]`
- **Path**: `prexorctl completion [bash|zsh|fish|powershell]`
- **Purpose**: Generate a shell completion script.
- **Expects (args)**: `[shell]` — `bash`, `zsh`, `fish`, or `powershell`.
- **Expects (flags)**: global only.
- **Delivers**: Writes a completion script to stdout. **bash uses `GenBashCompletionV2`**, so dynamic resource completions (groups/nodes/instances/…) work in bash too — at parity with zsh/fish/powershell.
- **Impl note**: `client-only`.

---

## get / describe (unified read verbs)

Modern, resource-oriented read verbs that complement (not replace) the noun→verb commands above. They share completion and the global `-o` system.

### `prexorctl get <type> [name] [flags]`
- **Path**: `prexorctl get <type> [name]`
- **Purpose**: List or fetch any resource by type, uniformly.
- **Expects (args)**: `<type>` (with aliases/short-names: `groups`/`grp`, `nodes`/`no`, `instances`/`inst`/`svc`, `users`, `roles`, `crashes`, `backups`, `templates`, `deployments`/`deploy`, `modules`, `catalog`, `shares`, `cluster`); `[name]` optional (a single resource when given).
- **Expects (flags)**: `--selector`/`--field-selector` (filter), `--sort-by`, pagination, plus global `-o`.
- **Delivers**: The same table as the type's native `list` (default), or a single row/object when `[name]` is given. `-o wide` adds the type's wide columns; `-o yaml|json` the raw object(s); `-o name` bare ids.
- **Impl note**: `needs-server` (per-type GETs); the verb/alias routing is `client-only`.

### `prexorctl describe <type> <name>`
- **Path**: `prexorctl describe <type> <name>`
- **Purpose**: Show the rich detail card for one resource (the `info`/`show` view), uniformly.
- **Expects (args)**: `<type>` (same aliases as `get`), `<name>` (required; picker on a TTY when omitted for picker-capable types).
- **Expects (flags)**: global `-o`.
- **Delivers**: The type's detail card (e.g. for a group: config + instances; for a node: resources + running instances; for a role: full permission list). `-o yaml|json`: the full object.
- **Impl note**: `needs-server`.

---

## Removed / merged

| Removed / merged | Replacement |
|------------------|-------------|
| `module upload <file.jar>` | Folded into **`module install <source>`** (a bare unsigned local jar is the upload path). |
| `setup --component <c>` flag | Replaced by subcommands **`setup controller` / `setup daemon` / `setup dashboard`**. |
| `setup --service-mode`, `setup --startup-validation-mode` | Collapsed into **`--boot-mode`** + **`--start-mode`**. |
| `setup --dashboard-serve-mode`, `setup --dashboard-tls-email` | Deleted (never consumed). |
| `module new --browser` | Deleted (returned "not implemented"). |
| `module new --no-mongo` | Deleted (silent no-op). |
| `logs instance --level`, `logs instance --logger` | Deleted on that subcommand (accepted but ignored). |
| `context add --token <value>` | Replaced by **`--token-stdin`** (secret off argv). |
| `config set token <value>` (positional value) | Value now read from **stdin** (secret off argv). |
| `plugin new --name` (advertised, unregistered) | Removed from help; name stays **positional**. |
| Boolean `--json` everywhere | Retained only as a **hidden alias** for `-o json`. |

---

## Room left for (not in this target)

A short, explicit note so the designer leaves conceptual space without designing these now:

- **Declarative GitOps layer — `apply -f` / `diff` / `edit`** over the config-shaped resources (groups, roles, catalog, templates). This is the headline future modernization (roadmap Phase 4) but is intentionally **not** part of this target. The unified `get`/`describe` verbs above are the read half that an eventual `apply`/`diff`/`edit` would build on; leave room in the resource-view screens for a future "diff/edit" affordance.
- **Other Phase-4 ideas** (krew-style plugins, `self-update`/`version --check`, profiles, optional AI-assist) are likewise out of scope here.
