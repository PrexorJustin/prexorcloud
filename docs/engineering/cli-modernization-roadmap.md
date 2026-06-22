# prexorctl modernization roadmap

Output of the **deep design pass**: every command in the CLI was evaluated against
a 13-dimension rubric (`cli/.audit/deep/RUBRIC.md`) across seven domains. Per-command
verdicts and detail live in `cli/.audit/deep/01..07-*.md`. This document is the
synthesis — the cross-cutting themes and the phased plan to make `prexorctl` the
best, most modern, most user-friendly control-plane CLI we can build.

Companion docs: **`cli-redesign-worklog.md`** (START HERE to actually do the work —
current state, Phase-1 task checklist, build/test/deploy), `cli-command-catalog.md`
(current surface + dispositions), `cli-design-review.md` (assessment + de-clutter list).

---

## The target in one paragraph

A modern control-plane CLI is **predictable** (one noun→verb grammar, consistent
flags), **structured** (every command speaks both human and machine via one `-o`
system, including errors), **async-aware** (you can wait for and watch convergence,
not just fire-and-hope), **HA-aware** (survives a controller dying, shows who the
leader is), **safe-by-default** (uniform danger gates, dry-runs, never fabricated
data), and ultimately **declarative** (GitOps `apply`/`diff`/`edit`). prexorctl today
is a solid imperative CLI; it is none of those six things consistently. The findings
below cluster almost entirely into those six gaps.

---

## The six cross-cutting themes (the spine)

Every domain independently surfaced the same handful. Fix these once, globally, and
~70% of the per-command findings dissolve.

### 1. Unified output system — replace `--json` (bool) with `-o`
Today `--json` is a boolean, present on most reads, **missing on most mutations**,
and `status --json` is a strict subset of what the TUI shows. Errors are never
machine-readable. **Target:** a single `output.Emit` renderer and a global
`-o {table,json,yaml,wide,name,jsonpath,template}` (keep `--json` as a hidden alias);
**every** command — reads, mutations, and a **JSON error envelope** — flows through
it. This alone retires ~20 "missing `--json`" findings. *[table-stakes, client-only]*

### 2. Async-convergence — `--wait` / `--for=condition` / `--timeout`
The control plane is asynchronous: `group scale`, `instance start/stop`, `node drain`,
`group create`, `deploy <group>`, `cluster leave/join` all **return before the thing
actually happens**, and scripts get no signal. **Target:** a shared wait layer
(`--wait`, `--for=condition=running|deleted|healthy`, `--timeout`), with exit code set
by terminal state. This is the single biggest "feels unfinished" gap for an orchestration
CLI. *[table-stakes; needs lightweight server status semantics]*

### 3. One danger-gate model — `--yes` + TTY guard + typed-confirm + `--dry-run`
**Three** idioms exist today (huh-confirm, raw `[y/N]`, typed-"yes") plus **many
destructive commands with no gate at all** (`restore` — full live overwrite, the worst
one; `backup prune`/`delete`; `catalog remove`; `template`/`deploy rollback`;
`token`/`join-token revoke`). And the gated ones can't be scripted (no `--yes`),
while the `[y/N]` ones **silently no-op in CI** (EOF→exit 0, did nothing). **Target:**
one helper — TTY → confirm; `--yes` bypass; quorum/blast-radius ops require typed name;
`--dry-run` everywhere it makes sense. *[table-stakes, client-only]*

### 4. HA-awareness — failover, identity, leader visibility
The CLI is the single point of failure in an HA system:
- **Multi-endpoint contexts + any-member failover.** A context pins one URL; if that
  controller dies the CLI is dead though the cluster is fine. Shared JWT + `/cluster/members`
  restAddrs make failover *free*. *[client-only; discovery trivial]*
- **Identity & session.** `whoami="(authenticated)"` is a stub; no token-expiry UX, no
  refresh, `logout` doesn't revoke server-side. Add `whoami`/`auth status` (decode JWT
  locally), expiry warnings + `auth refresh`, real `logout`. *(per audit, `/auth/me`,
  `/auth/refresh`, `/auth/logout`, `/auth/change-password` already exist — verify.)*
- **Leader/health visibility.** `isLeader()` is internal-only; `cluster status`/`members`
  show no leader, role, `lastSeen`, or quorum math. Surface them; add `cluster health`,
  `cluster leader`. `logs`/`stop` are HA-blind (operate on whichever member you pinned).
  *[mixed: failover client-only, leader/health needs-server]*

### 5. Scriptability & honesty — non-TTY safety, real exit codes, no fake data
- **Non-TTY footguns:** `group info`/`status`/`logs all` launch TUIs that can't run in a
  pipe; bare `prexorctl stop` in a non-TTY **stops the host** (most destructive default);
  `login`/`user create` are interactive-only (no CI path).
- **Exit-code model is broken:** `ExitAuthError=2` collides with the documented
  "validation-warning=2"; `ExitConnError=5` is dead (conn errors → generic 1). Renumber,
  type connection errors, document once.
- **Fabricated data must go:** `status` shows synthetic TPS/Players/Memory sparklines + a
  flat `SPARK (1h)` column; `group list` shows `UPDATED="just now"` + invented "recent
  events" + dead TPS/MEM columns. Showing fake telemetry in an ops tool is a trust bug —
  wire real metrics or remove. *[table-stakes; client-only except real metrics]*

### 6. Declarative & discoverable — the GitOps + UX layer
- **Declarative `apply -f` / `diff` / `edit`** over the config-shaped resources (groups,
  roles, catalog, templates) — the headline modernization; lets operators version their
  cluster in git. *[innovative; server-side dry-run helps]*
- **Unified `get` / `describe`** with resource aliases/short-names, and `-o wide`.
- **Completion & pickers completeness:** bash dynamic completion is **dead** (uses v1
  `GenBashCompletion` → switch to V2); no completion/pickers for users/roles/tokens/
  templates/deployments or closed-set enum flags (`--role`, `--state`, `--strategy`, …).
- **Theming/help consistency:** huh prompts + `--help` hardcode purple/v1 and ignore the
  accent + `--ascii`; route spinners/progress to stderr. *[mostly client-only]*

---

## Phased plan

### Phase 0 — shipped (this engagement)
Errors carry a real message; `--verbose`→stderr; `group info` non-TTY fallback; pre-link
gate no longer blocks local-only `plugin new`/`module doctor`/`module test`. Tested, green.

### Phase 1 — Foundations (client-only, table-stakes) — do first, unblocks the rest
1. Unified `-o` output renderer + total `--json` + JSON error envelope (Theme 1).
2. One danger-gate helper; add `--yes`/`--dry-run`; fix the `[y/N]` non-TTY no-op (Theme 3).
3. Exit-code model fix + typed connection errors; global `--quiet`/`--no-input`/`--timeout`;
   mirror every flag to `PREXOR_*` env (Theme 5).
4. `completion bash` → V2; complete enum/resource completion + pickers (Theme 6).
5. Secrets off argv (`config set token`, `context add --token`, `setup --*-password/-token`)
   → stdin/env; URL-escape all path ids; decode typed structs not `map[string]any`.
6. Kill fabricated data in `status`/`group list`; fix `whoami` via local JWT decode (Theme 5).
7. Non-TTY safety: bare `stop` errors instead of stopping the host; `group info`/`logs all`
   print-and-exit by default with the TUI behind `--watch`/`--ui`.
8. Scriptable `login` (`--username`/`--password-stdin`/env, form as fallback).

### Phase 2 — Control-plane correctness (table-stakes; some needs-server)
1. Async-convergence layer `--wait`/`--for`/`--timeout` across scale/start/stop/drain/
   create/deploy/cluster ops (Theme 2).
2. HA multi-endpoint contexts + any-member failover + `context --discover` (Theme 4).
3. Identity/session: `whoami`/`auth status`/`auth refresh`, expiry warnings, server-side
   `logout` revocation, CI service tokens (Theme 4).
4. Leader/role/health/quorum exposure in `cluster status`/`members`; `cluster health`,
   `cluster leader`; HA-aware `logs --member/--all-members` and `stop` quorum pre-flight (Theme 4).
5. Backup/restore safety: `restore` confirm + `--yes` + `--dry-run` + mandatory pre-restore
   snapshot; `backup verify --json` must exit non-zero on a bad bundle; `backup pull` +
   `restore --from-file`; honest `deploy rollback` (real revert or rename+gate).

### Phase 3 — Modern ergonomics
- `get`/`describe` unified verbs + aliases; `-o yaml|wide|name|jsonpath`; real `--sort-by`,
  label/`--field-selector`, pagination on all `list`s.
- `--watch` live views; deploy as `rollout` (status/history/undo/pause/resume) with `--wait`.
- Logs: `--since`/`--since-time`/`--previous`, scriptable `--follow` (JSON-lines), SSE
  reconnect (streams die at ~30s today, no resume), plain output when piped.
- Catalog: `catalog add paper@1.21` auto-resolves URL+sha256; show provenance/signature.
- Dev loop: de-monorepo `new`/`dev`/`test`; add `module build`/`sign`/`bundle` (the CLI can
  consume signed bundles but not produce them); `module enable`/`disable`/`describe`;
  `module registry` verbs; merge `module upload`→`install`.
- Installer: `setup` subcommands (match `stop`); idempotent re-run + `--dry-run` + `--json`;
  native/dashboard parity; collapse 4 lifecycle flags → 2.
- `node drain` → kubectl-style `cordon` + `drain`(+evict+wait); `undrain`→`uncordon`.

### Phase 4 — Innovative / GitOps
- Declarative `apply -f` / `diff` / `edit` over groups/roles/catalog/templates (Theme 6).
- krew-style plugin system; `self-update` / `version --check`; profiles; optional AI-assist.

---

## Net-new commands the audit implies
`whoami`, `auth status|refresh|change-password`, `config get <key>`, `context rename`,
`context --discover`, `cluster health`, `cluster leader`, `cluster leases` (route exists,
no verb), `backup pull`, `restore --from-file`, `module build|sign|bundle|enable|disable|
describe`, `module registry list|add|remove`, and the unified `get`/`describe`/`apply`/
`diff`/`edit`/`explain` verbs.

---

## Decisions for you
1. **Server-side coordination:** Themes 2 & 4 (async status semantics, leader/health in REST,
   refresh-token lifetime) need controller work. Pursue in lockstep with the CLI redesign, or
   ship the client-only Phase-1 now and stage the rest?
2. **Declutter appetite (Theme 6 / de-clutter list):** confirm the merges/removals
   (`module upload`→`install`, `setup --component`→subcommands, 4→2 lifecycle flags, dead
   flags) — these reduce surface before Claude Design freezes it.
3. **GitOps appetite (Phase 4):** is declarative `apply` a goal for this product? It changes
   how the whole CLI (and dashboard) is framed.
