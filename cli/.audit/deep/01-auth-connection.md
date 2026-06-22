# prexorctl deep-review 01 — auth / connection / identity

Scope: `login`, `logout`, `context {list,current,use,add,remove}`, `config
{view,set,unset}`, `version`. Sources read: `cmd/{login,context,config,version,root}.go`,
`internal/config/config.go`, `internal/api/client.go`, plus the server-side auth
contract (`java/.../rest/route/AuthRoutes.java`, `JwtManager.java`,
`SecurityControllerConfig.java`) to ground the identity/token model.

## Server-side facts that reframe this whole domain (verified, not assumed)

These change the verdicts — the CLI is leaving a *built* server surface on the floor:

- **`POST /api/v1/auth/login` returns `{token, user{username,role,email,createdAt,lastLoginAt}}`** (`LoginResponse`, `AuthRoutes.java:78`). The CLI decodes only `token` (`login.go:69-71`) and throws the identity away.
- **`GET /api/v1/auth/me` exists** (`AuthRoutes.java:188`, op `getCurrentUser`) → full `UserDto`. The CLI's `whoami()` is a hardcoded stub returning `"(authenticated)"` (`status.go:281-287`).
- **`POST /api/v1/auth/refresh` exists** (`AuthRoutes.java:164`, op `refreshToken`) → swaps a valid JWT for a fresh one. The CLI never calls it.
- **`POST /api/v1/auth/logout` revokes the JTI** in the Redis revocation store until natural expiry (`AuthRoutes.java:117`). The CLI's `logout` only clears the local token — **the server-side token stays valid**.
- **`POST /api/v1/auth/change-password` exists** (`AuthRoutes.java:222`). No CLI command maps to it.
- **JWT default TTL = 1440 min (24 h)** (`SecurityControllerConfig.java:18`); the token carries `exp`/`iat`/`jti`/`role` claims (`JwtManager.issue`), all **decodable client-side with zero round-trips**.
- **Login is rate-limited + lockout-aware**: 429 + `Retry-After` header, and a distinct `Locked` outcome (`AuthRoutes.java:79-90`, `RateLimitMiddleware.java:87`). The CLI surfaces none of it.
- **Shared `JWT_SECRET` across the fleet** + `GET /api/v1/cluster/members` returns each member's `restAddr` ⇒ a stored operator token is valid on *any* controller, and the member list is a ready-made failover substrate. The context model stores exactly **one** URL with no failover.

---

### prexorctl login
Current: fully interactive huh form (controller URL only if unset, username, password) → `POST /auth/login` → stores `token` in the current context.

**Verdict: REPLACE** — best-in-class target: a `gh auth login`-grade command that is scriptable (flags/stdin/env), captures identity + expiry, supports CI/service-token paste, multi-endpoint discovery, and degrades cleanly in non-TTY.

**Findings:**
- [5][table-stakes][client-only] **No `--username`/`--password-stdin`/`--controller` flags and no env path** (`login.go:16-66`) — the form is hard-interactive, so login cannot run in CI, a pipe, or `--no-input`. This is the single biggest gap in the domain. Add `--username`, `--password-stdin` (read secret from stdin, never argv), `--controller`, and honor `PREXOR_USERNAME`/`PREXOR_PASSWORD`. Fall back to the form only when a TTY is present and flags are absent.
- [4][6][table-stakes][client-only] **Login response identity is discarded** (`login.go:69-71`). The body already carries `user{username,role,email}`; store at least username+role in the context so `whoami`/`status`/help can show "logged in as alice (ADMIN)" without a round-trip. Without it the CLI literally does not know who it is.
- [4][modern][client-only] **Token expiry is never captured** though the JWT carries `exp` and login knows the 24 h TTL. Decode `exp` client-side and persist `tokenExpiresAt` in the context so the CLI can warn "token expires in 2h" and auto-refresh. No server change needed — the claim is in the token.
- [6][table-stakes][client-only] **`--json` ignored** (`login.go` has no JSON branch). A scripted login can't capture `{controller,user,role,expiresAt}`. Emit a JSON success envelope under `--json`.
- [8][table-stakes][client-only] **429/lockout is opaque.** The server returns 429 + `Retry-After` and a `Locked` body; the client (`client.go`) doesn't read `Retry-After` and login wraps everything as `"login failed: …"` (`login.go:76`). Surface "account locked, retry in Ns" distinctly from "wrong password".
- [10][modern][needs-server-then-client] **No service/CI token path.** Operator JWTs expire in 24 h, which is hostile to CI. Either consume long-lived service tokens via `--token`/`PREXOR_TOKEN` as a first-class "headless auth" (client-only, documented), or add a server-issued non-expiring/long-TTL service-account token type and a `login --service-account` flow (needs-server).
- [3][innovative][needs-server] **No endpoint auto-discovery.** After a successful login the CLI could `GET /cluster/members`, capture every `restAddr`, and store them as the context's failover endpoint set (see cross-cutting "multi-endpoint contexts"). One login → HA-aware context.
- [9][table-stakes][client-only] **Success message omits the context name** ("Logged in to <url> as <user>") — with multiple contexts the operator can't tell *which* context got the token. Name it.
- [10][table-stakes][client-only] **Password may be passed via the form even when a token override is active**; there's no `--check`/verify-only mode. Add `login --status`/reuse `whoami` so an operator can test the stored token without re-entering credentials.

### prexorctl logout
Current: clears `token` on the resolved context locally; prints "Logged out".

**Verdict: IMPROVE** — target: revoke server-side, name the context, support `--all`, honor `--json`.

**Findings:**
- [10][table-stakes][client-only→needs-server] **Local-only logout leaves the JWT valid on the server.** `POST /api/v1/auth/logout` revokes the JTI (`AuthRoutes.java:117`); the CLI never calls it (`login.go:92-101`). A "logged out" token is still accepted by every fleet member for up to 24 h. Call the endpoint (best-effort; tolerate 501 when Redis is absent), then clear locally.
- [9][table-stakes][client-only] **Doesn't name the cleared context** (`login.go:99` prints bare "Logged out"). Say which context/controller.
- [6][table-stakes][client-only] **No `--json`** — inconsistent with `version`/`config view`/`context list`.
- [5][modern][client-only] **No `--all` to clear every context's token** at once (useful when rotating `JWT_SECRET` fleet-wide). Currently you must `logout` per context.
- [4][modern][client-only] **No idempotency signal** — logging out of a context with no token still prints success. Fine, but under `--json` report `{revoked:false, reason:"no token"}`.

### prexorctl context list / current / use / add / remove
Current: CRUD over named `(controller, token)` pairs in `~/.prexorcloud/config.yml`; `list`/`current` support `--json`; `use`/`remove` use the interactive picker.

**Verdict: IMPROVE** — target: `kubectl config`/`etcdctl --endpoints`-grade contexts that model **multiple endpoints per context** with failover, carry identity, and round-trip fully via `--json`.

**Findings:**
- [3][innovative][client-only] **A context pins exactly one controller URL** (`config.Context{Controller,Token}`, `config.go:19-22`). Given fleet-wide shared `JWT_SECRET` + `restAddr` from `/cluster/members`, transparent any-member failover is architecturally free but impossible today. Evolve `Context` to `{endpoints:[]string, token, …}` and have the client iterate endpoints on connection failure (see cross-cutting). This is *the* defining feature of an HA control-plane CLI and it's absent.
- [5][modern][client-only] **`context add` takes only `--controller` (single)** (`context.go:168`). Add `--endpoints url1,url2,url3` (CSV) and/or a `--discover` flag that logs in, calls `/cluster/members`, and fills the endpoint list automatically.
- [2][4][table-stakes][client-only] **No `context rename` and no `context set/edit`.** To change a URL you must `remove`+`add` (losing the token) or fall back to `config set`. `kubectl config rename-context` / `set-context` are table stakes.
- [6][table-stakes][client-only] **`use`, `add`, `remove` ignore `--json`** (`context.go:83-165`) while `list`/`current` honor it. A script that adds a context can't parse confirmation. Make all five JSON-consistent.
- [6][modern][client-only] **`context list` shows only name/controller/current** — not whether a token is present, the logged-in user, token expiry, or reachability. A `--wide`/`-o wide` column set (`USER`, `EXPIRES`, `STATUS`) would make this the operator's at-a-glance fleet view. The data (user/expiry) is available once login captures it.
- [3][modern][needs-server] **No reachability/health probe.** `context list --check` could `GET /auth/me` (or `/system/version`) per context and show reachable/leader/expired — etcdctl `endpoint health` / `endpoint status` equivalent. Especially valuable to spot which controllers are up during a partition.
- [4][table-stakes][client-only] **`add` rejects an existing name with an error** (`context.go:123-124`) but offers no `--overwrite`/upsert. Minor, but every other tool allows update-in-place.
- [5][table-stakes][client-only] **`context add --token` takes the secret on argv** (`context.go:169`) — leaks into shell history/`ps`. Offer `--token-stdin`/`-`. (Same class as `config set token`.)
- [8][table-stakes][client-only] **`use <bad>` / `remove <bad>` error is bare** ("unknown context %q", `config.go:241`) — no "did you mean…" against the known names, which are right there. Cheap fuzzy suggestion.
- [9][modern][client-only] **No completion on `context use`/`remove`** beyond the dynamic helper noted in audit 05 — verify it lists context names (`completeContextNames`); if so, good; if not, add it. (`use`/`remove` already pick interactively, so this is the non-TTY parity gap.)

### prexorctl config view / set / unset
Current: `view` shows active context's controller/token(masked)/accent + path (`--json` ok); `set <key> <value>` and `unset <key>` mutate `controller|token|accent` on the active context (no `--json`).

**Verdict: IMPROVE** — target: `gh config`/`git config`-grade: a `get <key>` accessor, JSON-consistent writes, secret-safe token input, and validated enums with completion.

**Findings:**
- [6][table-stakes][client-only] **`set`/`unset` ignore `--json`** (`config.go:59-125`) while `view` honors it (`config.go:27`). Inconsistent within one group.
- [5][6][table-stakes][client-only] **No `config get <key>`** single-value accessor — scripts must `config view --json | jq -r .controller`. Add `config get controller` (raw value to stdout, `--quiet`-friendly).
- [5][10][table-stakes][client-only] **`config set token <value>` takes the JWT on argv** (`config.go:77-81`) → shell history + process table leak. Support `config set token -` / `--value-stdin`, or just deprecate token-via-`set` in favor of `login`.
- [2][5][modern][client-only] **`config set/unset` only know context-scoped keys** (`controller`,`token`,`accent`). There's no path to set global prefs cleanly vs context prefs, and `accent` is global while `controller`/`token` are per-context — the same verb mutates two scopes with no signposting. Document/namespace (e.g. `accent` shown as a global key in help).
- [9][table-stakes][client-only] **`set` help under-documents `accent`** (`config.go:62`) — lists it as valid but never states the enum (`purple|cyan|green|amber`, `palette.go:18`). No client-side validation either: `config set accent blue` succeeds and silently falls back at render. Validate + complete the enum.
- [9][modern][client-only] **No `config edit`** to open the YAML in `$EDITOR` (kubectl/gh have it). Minor, but the file is hand-edited today.
- [6][table-stakes][client-only] **`maskToken` collapses ≤10-char tokens to `***`** (`config.go:145`) — fine for safety, but for real 24 h JWTs the `abc123…wxyz` form is good; consider also printing decoded `exp`/`sub` (non-secret claims) in `view` so the operator sees who/when without leaking the signature.

### prexorctl version
Current: prints CLI version (+ go/os/arch); if a token is set, fetches `GET /api/v1/system/version` and appends a controller block. `--json` supported.

**Verdict: IMPROVE** — target: `kubectl version`-style client/server split that is honest about failure and shows the connection target.

**Findings:**
- [8][table-stakes][client-only] **Controller-version fetch errors are silently swallowed** (`version.go:34,58`) — if the controller is down/unauthorized the block just vanishes, in `--json` too. Operator can't tell "no token" from "controller unreachable" from "version endpoint 404". Under `--json`, emit `controller_error` (or `controller:null` + `controller_reachable:false`); in human mode print a dim "controller: unreachable (<reason>)".
- [3][modern][client-only] **Doesn't show *which* endpoint answered** — in an HA/multi-endpoint world `version` should report the controller URL it hit (and, once exposed, whether it's the leader). Add the resolved controller to both outputs.
- [6][modern][client-only] **`--json` controller keys are flattened with a `controller_` prefix** (`version.go:35-37`) into a `map[string]string`, while the human path nests a card. Use a nested `{cli:{…}, controller:{…}}` object so the JSON is structured, not stringly-prefixed.
- [4][table-stakes][client-only] **No client/server-skew warning.** Once both versions are known, a mismatch (CLI newer/older than controller) is worth a one-line hint — standard in kubectl/cockroach.
- [13][modern][client-only] **No self-update / update-check** (`brew`/`flyctl version update`, `gh` upgrade hint). `version` is the natural home for "a newer prexorctl is available" against the GitHub release feed the setup path already knows how to read (`FetchLatestRelease`).

---

## Cross-cutting / systemic

These should be fixed once, CLI-wide; they dominate the per-command findings above.

1. **Multi-endpoint, failover-aware contexts (the headline gap).** Today `Context = {controller, token}` (one URL, no failover) despite the architecture making transparent any-member failover free (shared `JWT_SECRET`, `restAddr` in `/cluster/members`). Evolve to `Context = {endpoints:[]string, token, user, role, tokenExpiresAt}`; teach `api.Client` to take an ordered endpoint list and, on a *connection* error (not a 4xx), advance to the next endpoint with the existing backoff. Seed endpoints from `login --discover`/`context add --discover`/`context refresh-endpoints`. [innovative][needs-server for discovery, client-only for failover]

2. **Token lifecycle: refresh + expiry UX.** The server exposes `/auth/refresh` and tokens carry `exp` (24 h). The CLI should (a) decode and store `tokenExpiresAt` at login, (b) warn when a token is near/after expiry instead of returning a bare 401, and (c) optionally auto-refresh on a near-expiry request via `/auth/refresh`. Add an explicit `prexorctl auth refresh` too. [modern][needs-server endpoint already exists]

3. **Identity is unwired end-to-end.** Login already receives the user; `/auth/me` exists; yet `whoami()` is a stub (`status.go:281`). Add a real `prexorctl whoami` / `prexorctl auth status` that reports user, role, controller, endpoint, token expiry, and reachability — and back `status.go`'s banner with it. [table-stakes][needs-server endpoint already exists]

4. **Server-side logout/revocation.** `logout` must call `POST /auth/logout` to revoke the JTI, not just delete the local copy (tolerating 501 when Redis is absent). Otherwise "logout" is security theater. [table-stakes][needs-server endpoint already exists]

5. **Missing commands the domain implies:** `auth status`/`whoami`, `auth refresh`, `auth change-password` (`/auth/change-password` is built and unused), `context rename`, `context set-endpoints`/`refresh-endpoints`, `config get`. Consider grouping the verbs under a single `prexorctl auth {login,logout,status,refresh,token,change-password}` (gh/flyctl model) with `login`/`logout` kept as top-level aliases. [table-stakes/modern][mixed]

6. **TLS hygiene.** `api.New` builds a bare `http.Client` with no TLS config (`client.go:83-92`) — no `--insecure`/`PREXOR_INSECURE` for self-signed dev controllers and no `--cacert`/`PREXOR_CACERT` for a private CA, even though `setup --public` provisions TLS. An operator with a self-signed controller cannot connect at all and has no escape hatch. Add both flags + env, gated behind a visible warning when insecure. [table-stakes][client-only]

7. **Scriptability contract across the domain.** `login` (and the audit-05 `user create`/`*delete`) hard-prompt with huh, hanging or failing in non-TTY. Establish one rule: every credential/confirmation has a flag/stdin path; prompts appear only when stdin+stdout are TTYs and no flag was given; `--no-input` forces fail-fast. Secrets read from stdin, never argv (applies to `login`, `config set token`, `context add --token`). [table-stakes][client-only]

8. **`--json` completeness + JSON error envelope.** `login`/`logout`/`config set`/`config unset`/`context use|add|remove` all ignore `--json`; and errors are never JSON (per audit 05, all go through `theme.PrintError` as plain stderr). A `--json` consumer that hits a 401 gets empty stdout + unparseable stderr. Standardize: success → JSON object on stdout; error → `{error:{code,message,exitCode}}` on stderr under `--json`. [table-stakes][client-only]

9. **Exit-code clarity for auth.** `ExitAuthError=2` (401) collides with the documented "diagnostic warnings = 2" convention, and `ExitConnError=5` is dead because connection failures return a plain `fmt.Errorf` not an `APIError` (`client.go:221`), mapping to exit 1. Auth/connection is exactly where scripts branch on exit codes — wire connection errors to a typed error so `5` means "controller unreachable" and reserve `2` for auth. [table-stakes][client-only]

10. **Login error taxonomy.** Read `Retry-After` and distinguish 401 invalid-credentials vs 429 rate-limited vs `Locked` vs connection-refused vs TLS-failure — each with a specific remediation hint, rather than the current `"login failed: %w"` wrapper. [table-stakes][client-only]
