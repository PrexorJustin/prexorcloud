# prexorctl redesign — worklog & handoff (START HERE)

This is the **pick-up-and-work** doc for the prexorctl CLI redesign. A future session
(or Claude Design) should read this first, then the roadmap. It holds current state,
the concrete next tasks, and how to build/test/deploy.

_Last updated: 2026-06-16._

---

## 1. Doc map — what's where
| File | Purpose |
|------|---------|
| **`cli-redesign-worklog.md`** (this) | Current state + executable task checklist + build/test/deploy. |
| **`cli-modernization-roadmap.md`** | The target design: 6 cross-cutting themes + 5-phase plan. The "why/what". |
| `cli-command-catalog.md` | Neutral per-command reference + per-command `Disposition` tags + proposed new commands. The Claude Design handoff. |
| `cli-design-review.md` | Per-finding assessment + de-clutter list + design principles. |
| `cli/.audit/deep/01..07-*.md` | Full per-command verdicts (all 13 rubric dimensions). The detailed backing. |
| `cli/.audit/deep/RUBRIC.md` | The 13-dimension evaluation rubric (reuse it for any re-audit). |
| `cli/.audit/01..05-*.md` | First-pass (per-command isolation) audit notes. |

The CLI source is `cli/` (Go, cobra + charmbracelet). Commands: `cli/cmd/*.go`. HTTP
client + errors + exit codes: `cli/internal/api/client.go`. Config/contexts:
`cli/internal/config/config.go`. Output/theme: `cli/internal/theme/`. TUI: `cli/internal/tui/`.

---

## 2. Current state (2026-06-16)
- **Phase 0 shipped** (4 P0 correctness fixes, tested `go test ./...` green, **uncommitted**, **not deployed** to the fleet binary):
  - HTTP errors carry a real message (`parseAPIError` in `internal/api/client.go`, handles `{message}`/nested `{error:{}}`/RFC-7807 `{title,detail}`/plain-text; covered by `TestGet_APIError_AlternateEnvelopes`).
  - `--verbose` HTTP trace → stderr (was corrupting `--json`/pipes).
  - `group info <name>` renders a static view when not a TTY instead of crashing (`cmd/group.go` `printGroupInfoStatic`).
  - Pre-link gate no longer blocks local-only `plugin new` / `module doctor` / `module test` (`Annotations{"local-only":"true"}`).
- Nothing committed yet (user commit style: short, human, **no Co-Authored-By**).
- **Do not touch** the uncommitted Java changes in `cloud-controller/.../raft/` (`MembershipReconciler.java`, `RaftBootstrap.java`, `RatisMultiPeerSpikeTest.java`) — that's a concurrent session's live #22 Raft work.

---

## 3. Open decisions (some block work — confirm before starting the gated items)
1. **Server coordination cadence** — Themes 2 & 4 need controller work (async status semantics, leader/role/health in `cluster/members` REST, refresh-token lifetime). Ship client-only Phase 1 now and stage server-dependent parts, or lockstep?
2. **Declutter appetite** — confirm merges/removals: `module upload`→`install`; `setup --component`→subcommands; 4→2 setup lifecycle flags; delete dead flags (`setup --dashboard-serve-mode/--dashboard-tls-email`, `module new --browser/--no-mongo`, `logs instance --level/--logger`).
3. **GitOps appetite** — is declarative `apply -f`/`diff`/`edit` (Phase 4) a product goal? Reframes the whole CLI.
4. **Verify server endpoints** — auth agent reports `/auth/me`, `/auth/refresh`, `/auth/logout`, `/auth/change-password` already exist. Confirm against the controller before building `whoami`/`auth status`/`auth refresh`/server-side `logout`.

---

## 4. Phase 1 — client-only foundations (no server changes; do these first)
Each is an independent commit. "Done when" is the acceptance check. File pointers are starting points, not exhaustive.

- [ ] **Unified output system.** Add `internal/output` with an `Emit(v any, opts)` renderer; add global `-o/--output {table,json,yaml,wide,name,jsonpath,template}` (keep `--json` as a hidden alias mapping to `-o json`). Route the ~30 `if flagJSON {…} else {…}` sites (start: `cmd/helpers.go` `fetchList`/`fetchOne`, then each `cmd/*.go`) through it. _Done when:_ every command (reads **and** mutations) emits structured output under `-o json`, and `status --json` is no longer a subset.
- [ ] **JSON error envelope.** In `cmd/root.go` `Execute()`, when output is JSON, print errors as a JSON object on stderr (code, message, httpStatus) instead of `theme.PrintError` text. _Done when:_ `prexorctl group info nope -o json` emits a parseable error object.
- [ ] **One danger-gate helper.** New `cmd/confirm.go`: `confirmDestructive(action string, opts{typedName, yes bool})` — TTY→confirm, `--yes` bypass, typed-name for blast-radius ops, non-TTY without `--yes`→clear error (never silent no-op). Add `--yes`/`-y` and wire into: `group delete`, `user delete`, `role delete`, `restore`, `backup prune`, `backup delete`, `catalog remove`, `template rollback`, `deploy rollback`, `cluster eject/leave/recover`, `node drain`, `instance stop --force`, scale-to-0. _Done when:_ all are scriptable with `--yes` and safe without it; the `deploy` `[y/N]` no longer silently no-ops in CI.
- [ ] **Exit-code model.** `internal/api/client.go`: give 401 a code distinct from the diagnostic-warning `2`; wrap transport failures in a typed error so `ExitConnError=5` is used. Document the table in `root.go`. _Done when:_ a conn refusal exits 5, a 401 exits its own code, a doctor-warning still exits 2.
- [ ] **`completion bash` → `GenBashCompletionV2`** (`cmd/completion.go`). _Done when:_ `group info <TAB>` completes group names in bash.
- [ ] **Completion + pickers completeness.** Add dynamic completion for users/roles/tokens/templates/deployments and closed-set enum flags (`--role`, `--state`, `--strategy`, `--scaling-mode`, `--routing`, `--category`); add `pickUser`/`pickRole`/`pickTemplate`/`pickDeployment` so `ExactArgs` holdouts get the arg-less selector.
- [ ] **Secrets off argv.** `config set token` (`cmd/config.go`) and `context add --token` (`cmd/context.go`) and `setup --*-password/-token` (`cmd/setup*.go`) read from stdin/`-`/env, not a positional/flag value.
- [ ] **URL-escape path ids** everywhere (`token revoke`, `crash info`, `backup verify/delete`, `cluster join-token revoke`, `cluster recover` nodeIds, `logs daemon/instance`, `stop node`); prefer decoding typed structs over `map[string]any`.
- [ ] **Kill fabricated data.** Remove synthetic metrics from `cmd/status.go` (TPS/Players/Memory sparklines, `SPARK (1h)` column) and `cmd/group.go` list (`UPDATED="just now"`, invented events, dead TPS/MEM columns) — show real values or omit. Fix `whoami` via local JWT-claim decode.
- [ ] **Non-TTY safety.** Bare `prexorctl stop` in a non-TTY must error-with-usage, not run `stop local` (`cmd/stop.go`). `group info` done; give `logs all` a non-TTY JSON-lines/plain mode.
- [ ] **Scriptable `login`.** Add `--username`/`--password-stdin`/`--controller` + `PREXOR_USERNAME/PASSWORD` to `cmd/login.go`; huh form only as TTY fallback. Same for `user create` (`--password-stdin`). _Done when:_ `prexorctl login --username admin --password-stdin -c URL` works headless.
- [ ] **Global `--quiet`/`-q`, `--no-input`, `--timeout`** (decouple prompt-suppression from output format); mirror persistent flags to `PREXOR_*` env.
- [ ] **De-clutter** (after decision #2): merge `module upload`→`install`; delete dead flags; `setup` subcommands; collapse lifecycle flags.

## 5. Phase 2+ (see roadmap for detail)
- **Phase 2 (control-plane correctness, some needs-server):** async `--wait`/`--for`/`--timeout`; HA multi-endpoint contexts + any-member failover + `context --discover`; identity/session (`whoami`/`auth status`/`auth refresh`, server-side `logout`, CI service tokens); leader/role/health in `cluster status`/`members` + `cluster health`/`cluster leader`; backup/restore safety (`restore --dry-run` + pre-restore snapshot, `backup pull`, honest `deploy rollback`).
- **Phase 3 (modern ergonomics):** `get`/`describe` + aliases + `-o wide/yaml/jsonpath`; `--watch`; `rollout` semantics for deploy; `logs --since/--previous` + scriptable follow + SSE reconnect; `catalog add paper@1.21` auto-resolve; `module build/sign/bundle`, de-monorepo dev loop; `setup` idempotency.
- **Phase 4 (GitOps):** `apply -f`/`diff`/`edit`; plugins; `self-update`.

---

## 6. Build / test / run / deploy
```bash
# build + test (from cli/)
cd cli && go build -o prexorctl . && go test ./...   # spotless/format not needed for Go
# the JS/Java repo gates don't apply to cli/

# live fleet (Hetzner) — for manual verification
/usr/bin/ssh -N -L 8080:localhost:8080 -L 9090:localhost:9090 root@167.233.120.10 &   # tunnel
# token: POST http://localhost:8080/api/v1/auth/login with admin/$ADMIN_PASS (creds: ~/prexor-fleet/secrets.env)
#   then write it into ~/.prexorcloud/config.yml context "fleet" (controller http://localhost:8080).
# JWT has ~24h TTL → re-auth each session.

# deploy the rebuilt CLI to the fleet (only when asked)
/usr/bin/scp cli/prexorctl root@167.233.120.10:/usr/local/bin/prexorctl
```
Fleet state at handoff: ctrl-1 healthy **single-member** (clusterId `7c5cebc9`, NOT HA — #22 open), daemons ONLINE, groups scaled to 0. `backup create` returns a controller-side **HTTP 500** (Part 12 bug, not CLI). Instance start/console/exec live-tests need a healthy scheduler + a real MC client (user-in-loop).

## 7. Constraints
- Commit messages: short, human, **no Co-Authored-By** trailer.
- Keep changes scoped to `cli/`; never touch the concurrent Java `raft/` changes.
- Re-run `go test ./...` before any commit; keep `--json`/stdout clean (data only) and chatter on stderr.
