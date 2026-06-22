# prexorctl deep review — Artifacts & Rollouts

Scope: `prexorctl catalog` (list/add/update/recommend/remove), `prexorctl template`
(list/versions/rollback), `prexorctl deploy` (trigger/list/show/pause/resume/rollback).

Sources read: `cli/cmd/catalog.go`, `cli/cmd/template.go`, `cli/cmd/deploy.go`,
`cli/internal/api/types.go`, plus the backend ground-truth
`controller/rest/route/DeploymentRoutes.java`, `controller/rest/route/CatalogRoutes.java`,
`controller/catalog/CatalogConfigLoader.java`,
`controller/rest/dto/UpdateCatalogVersionRequest.java`.

Every command below is scored against all 13 rubric dimensions; verdicts cite
`file:line` and tag each finding `[table-stakes|modern|innovative]` +
`[client-only|needs-server]`.

Backend facts that anchor the tagging:
- **`deploy <group>` is async**: `POST /deploy` returns `202` immediately and the
  rollout runs in a virtual thread (`DeploymentRoutes.java:204-209`). The CLI's only
  "wait" is the interactive TUI; there is no `--wait` for scripts.
- **Deployment states** are `IN_PROGRESS / PAUSED / ROLLED_BACK` (+ presumably
  `COMPLETED/FAILED`); pause/resume/rollback just flip `state` and re-spawn
  `rollingRestart` on resume (`DeploymentRoutes.java:269-285`). Rollback **does not
  restore template/module state** — it only marks the record `ROLLED_BACK`
  (`deploy.go:367` Long text admits "restoring … is operator-driven").
- **`canaryInstances` vs `canaryPercent` mutual-exclusion IS enforced server-side**
  (`DeploymentRoutes.java:359-361` → 400), so the CLI's missing client check is a UX
  gap, not a correctness hole — but the error only surfaces after a round-trip.
- **Catalog update is destructive by construction**: `CatalogConfigLoader.updateEntry`
  does `removeIf(oldVersion)` then `add(new VersionEntry(newVersion, downloadUrl,
  sha256, wasRecommended))` — it writes whatever URL/sha it's handed, with **no
  null-means-keep merge**. The CLI always sends `downloadUrl`+`sha256`
  (`catalog.go:137-141`), so a rename-only update blanks both fields. Only
  `recommended` is preserved across the update.
- **No catalog "resolve" endpoint** exists: `add` requires an explicit `--url`; there
  is no server-side `paper@1.21 → download URL+sha` resolution.
- **No deployment dry-run/diff endpoint** exists; the PLAN preview is synthesized
  client-side from the group doc (`deploy.go:122-160`).

---

## `prexorctl catalog`

Parent (`catalog.go:12`) — "Manage the server platform catalog". Good `Long`. Help-only.

### `prexorctl catalog list`
Current: `GET /api/v1/catalog` → table PLATFORM/CATEGORY/VERSION/REC(★)/SHA256/DOWNLOAD URL with middle-truncation (`catalog.go:20-65`).

**Verdict: IMPROVE** — best-in-class target: a declarative resource list with
provenance columns, `-o wide|yaml|name`, and filtering, like `gh release list` /
`kubectl get`.

**Findings:**
- [6][table-stakes][client-only] Only table + `--json`; no `-o wide|yaml|name|jsonpath`, no `--quiet`/`-o name` for `plat@ver` piping. `catalog list -o name` feeding `xargs catalog remove` is the obvious automation that doesn't exist.
- [6][modern][client-only] SHA256 is middle-truncated to 12 chars (`catalog.go:52`) — unverifiable from the table. A `wide` mode (or `--json`) should show the full digest; the truncated form is fine for the default but must be recoverable.
- [5/6][table-stakes][client-only] No `--platform`/`--category`/`--recommended-only` filter; everything is client-eyeball. Sibling `group list` has `--filter`; catalog has none. Inconsistent.
- [6][modern][client-only] No provenance/trust column: there is no indication whether an entry **has** a sha256 at all (unpinned = supply-chain risk). A `SIGNED?`/`PINNED?` glyph (✓ when sha256 present, ⚠ when blank) would surface the exact field-blanking bug below at a glance.
- [13][innovative][needs-server] No `catalog describe <platform>` to show per-platform version tree + which one is recommended + which groups consume each version (reverse index). Today you can't tell "is anyone using PAPER 1.20?" before removing it.

### `prexorctl catalog add <platform>`
Current: `POST /catalog/<platform>/versions`; if `--recommended`, a **second** `PUT …/recommended` (`catalog.go:67-113`).

**Verdict: IMPROVE** — target: declarative, auto-resolving `catalog add paper@1.21`
that fills URL+sha from a known upstream, à la `mise`/`asdf`/`brew` version resolution.

**Findings:**
- [4][table-stakes][needs-server] **Non-atomic recommend**: the version is created by the POST even if the follow-up `PUT …/recommended` fails (`catalog.go:101-105`), leaving a half-applied add. Backend should accept `recommended` in the add body (one transactional write) instead of forcing two calls.
- [5/13][innovative][needs-server] **`catalog add paper@1.21` should auto-resolve** the download URL and sha256 from a curated upstream resolver (PaperMC/Velocity/Purpur APIs the project already references in the `Example`). Today the operator must hand-paste a build-specific URL **and** compute the sha themselves; this is the single biggest ergonomic gap in the whole artifact surface. Needs a controller resolver endpoint (`POST /catalog/resolve {platform, version}` → `{url, sha256}`) so trust stays server-side.
- [5][table-stakes][client-only] No `--sha256` auto-compute: even without a resolver, the CLI could `HEAD`/stream the `--url` and compute the digest locally when `--sha256` is omitted (with a confirmation), turning "unpinned by default" into "pinned by default".
- [5][table-stakes][client-only] `--category` / `--config-format` accept any string with no enum validation or completion; backend only knows `SERVER`/`PROXY` (`CatalogRoutes.java:93`). A bad category is accepted silently. Add `RegisterFlagCompletionFunc` + client-side enum check.
- [8][table-stakes][client-only] `add` of a duplicate returns the bare server `409` mapped to exit 1; no "version already exists — use `catalog update`/`catalog recommend`" remediation hint.
- [10][table-stakes][client-only] Adding an **unpinned** version (no `--sha256`) over an HTTP(S) URL is a silent supply-chain footgun — should at least warn ("no checksum: the jar will not be integrity-verified") unless `--no-verify`/`--unpinned` is passed.
- [6][modern][client-only] No `--dry-run` to print the resolved body without writing.

### `prexorctl catalog update [platform] [version]`
Current: `PATCH /catalog/<platform>/versions/<version>` with `{version, downloadUrl, sha256}` — **always sends all three** (`catalog.go:137-141`).

**Verdict: REPLACE the body-builder** — target: true partial-update PATCH semantics
that never touch a field the operator didn't name (like `group update` already does
via `flags.Changed`).

**Findings:**
- [4][table-stakes][client-only **and** needs-server] **Field-blanking bug, confirmed against the backend.** `catalog update PAPER 1.21 --new-version 1.21.1` sends `downloadUrl:""` + `sha256:""`; `CatalogConfigLoader.updateEntry` removes the old entry and re-adds it with those empty strings, so the URL and checksum are **wiped**. Two-layer fix: (a) **client-only** — only include `downloadUrl`/`sha256` in the body when `flags.Changed(...)` (mirror `buildDeployBody`/`group update`); (b) **needs-server** — `updateEntry` should treat `null` as "keep existing" so the API is safe for any client. Both should land; (a) is the immediate fix.
- [5][table-stakes][client-only] `--new-version` is a *rename*, but it's bundled into the same verb as URL/sha edits, which is exactly what causes the blank. Consider a dedicated `catalog rename <platform> <old> <new>` (or make rename the only thing that defaults the other fields to "unchanged").
- [6][table-stakes][client-only] `--json` dumps the raw `versionResponse` (just `{platform, version}`), not the resulting entry with URL/sha — a script can't confirm the update preserved provenance. Echo the full post-update entry.
- [9][modern][client-only] No diff/confirmation: a destructive overwrite of a pinned URL prints only a one-line success. Show `old → new` for changed fields (especially when the sha changes).

### `prexorctl catalog recommend [platform] [version]`
Current: `PUT …/recommended`; prints a success line (`catalog.go:156-176`).

**Verdict: IMPROVE** — target: scriptable, JSON-emitting "promote" with a clear
before/after.

**Findings:**
- [6][table-stakes][client-only] **Ignores `--json` entirely** (`catalog.go:173`) — only a human success line. Should emit `{platform, version, recommended:true, previousRecommended:…}`.
- [4][modern][client-only] No echo of what the *previous* recommended version was; recommend is a silent state change with no audit feedback to the operator (the server audits it at `CatalogRoutes.java:172`, but the CLI shows nothing).
- [2][modern][client-only] Verb naming: `recommend` is fine, but a `--recommended` flag already exists on `add`; consider `catalog promote` as an alias to match the mental model "promote this version to default", and document that `recommend` and `add --recommended` are the same operation.

### `prexorctl catalog remove [platform] [version]` (aliases `rm`, `delete`)
Current: `DELETE …/versions/<version>`; **no confirmation**; prints a success line (`catalog.go:178-199`).

**Verdict: IMPROVE** — target: guarded, scriptable destructive delete with a usage
check, like `kubectl delete` (`--yes`, refuses in-use artifacts).

**Findings:**
- [10][table-stakes][client-only] **No danger gate at all** — no confirm, no `--yes`, no `--dry-run`. Removing a version that running groups pin will break the next scale-up. Add a typed/`--yes` gate and (ideally) refuse if any group references it.
- [6][table-stakes][client-only] **Ignores `--json`** (`catalog.go:196`); no machine acknowledgment.
- [4][modern][needs-server] No "in-use" guard: there's no check that no group/template depends on the version before deleting (reverse index again). At minimum a client-side `GET /groups` scan + `--force` to override.
- [8][table-stakes][client-only] Removing the **recommended** version leaves the platform with no recommended entry; no warning. Should flag this and optionally prompt to promote another.

### `resolveCatalogTarget` (shared picker, `catalog.go:201-230`)
- [5][modern][client-only] Good: pickers fill platform+version on a TTY and error with a copy-pasteable example off-TTY. This is the right pattern — but `add` uniquely does **not** use it (it's `ExactArgs(1)` + required flags), so the interactive story is inconsistent across the group.

---

## `prexorctl template`

Parent (`template.go:11`) — "Manage templates". Bare `Short`, no `Long`, no examples.
The whole surface is read-mostly: list, versions, rollback. No `info`, `create`,
`upload`, `delete`, `diff`, `apply`.

### `prexorctl template list`
Current: `GET /api/v1/templates` → table NAME/HASH(8)/SIZE/DESCRIPTION (`template.go:16-54`).

**Verdict: IMPROVE** — target: typed, richer resource list with platform + version
count + which groups consume it.

**Findings:**
- [4/6][table-stakes][client-only] Decodes into `[]map[string]any` and stringifies via `str`/`num` (`template.go:25,37-46`) even though a typed `TemplateResponse` already exists in `types.go:117`. Inconsistent with `catalog list`'s typed path; loses type safety for no reason.
- [6][table-stakes][client-only] No `currentVersion`/version-count column, no `PLATFORM` column (the field exists on `TemplateResponse.Platform`). An operator can't see at a glance how many versions a template has or what platform it targets.
- [6][modern][client-only] No `-o wide|yaml|name`; `template list -o name | xargs -n1 template versions` isn't possible.
- [13][innovative][needs-server] No reverse index: "which groups use this template?" — high-value for safe rollback/edit decisions.

### `prexorctl template versions <name>`
Current: `GET …/versions` → numbered table `#`/HASH/SIZE/CREATED (`template.go:56-96`).

**Verdict: IMPROVE** — target: version history that marks the *current* version and
supports diffing, like `git log` / `flyctl releases`.

**Findings:**
- [4][table-stakes][client-only] **Numbering assumes newest-first**: `len(versions)-i` (`template.go:83`) produces wrong `#`s if the API ever returns oldest-first. The ordering assumption is undocumented and unenforced. Either sort client-side by `createdAt` or stop synthesizing ordinals and show the hash as the identity.
- [6][table-stakes][client-only] No "← current" / "← recommended" marker on the active version, so the operator can't tell what `rollback` will move *away from* or *toward*.
- [5][table-stakes][client-only] `ExactArgs(1)`, **no picker** — inconsistent with catalog's `resolveCatalogTarget`. Should offer a template picker on a TTY.
- [13][modern][needs-server] No `template diff <name> <a> <b>` (or `--diff` against current). Versions are content-addressed (hash + size); a file-level or manifest-level diff between two hashes is the natural "what changed" view and the precondition for a *confident* rollback. Needs a server diff endpoint.

### `prexorctl template rollback <name>`
Current: `POST …/rollback` — rolls back to **previous** only; **no target selection, no confirmation** (`template.go:98-118`).

**Verdict: IMPROVE** — target: `rollback [--to <version|hash>]` with a confirmation
gate and a before/after echo, like `kubectl rollout undo --to-revision`.

**Findings:**
- [4/5][table-stakes][needs-server] **Can only roll back to the immediately previous version** — there is no `--to <version>`/`--to-revision`. `kubectl rollout undo` and `flyctl` both support arbitrary targets. Needs a server param (`POST …/rollback {targetHash}` or `{version}`).
- [10][table-stakes][client-only] **No confirmation and no `--dry-run`** for a state-mutating rollback that affects every group consuming the template. Add a `--yes` gate + a "this will move template X from <hashA> to <hashB>" preview.
- [4][table-stakes][client-only] No echo of the resulting version: `--json` synthesizes `{status:"rolled_back", template:name}` (`template.go:113`) with no hash/version, so a script can't confirm *where* it landed. The success line is equally vague ("rolled back").
- [5][table-stakes][client-only] `ExactArgs(1)`, no picker — same inconsistency as `versions`.
- [2][modern][client-only] Name collision: `template rollback` and `deploy rollback` are two unrelated verbs. Document/disambiguate (e.g. `template rollback` = artifact content; `deploy rollback` = mark a rollout record). Neither is currently confirmed.

> Missing-but-implied commands: `template describe <name>`, `template diff`, and an
> ingest path (`template push`/`apply -f`) for declarative template management. The
> group currently lets you *roll back* a template you can't *create or inspect*
> through the CLI — an incomplete lifecycle.

---

## `prexorctl deploy` — promote to a first-class `rollout`

This is the headline of the section. `deploy` is both a runnable trigger
(`deploy <group>`) **and** a subcommand host (list/show/pause/resume/rollback). The
right mental model is **kubectl's `rollout`**: status / history / undo / pause /
resume, with `--watch`/`--wait`, a real diff/dry-run, and a clean non-TTY contract.

### `prexorctl deploy <group>` (trigger)
Current: builds a changed-flags body; `--json` → `POST …/deploy` and dump; otherwise → group GET → PLAN preview → `[y/N]` → `POST` → live polling TUI (`deploy.go:28-103`).

**Verdict: REPLACE/IMPROVE** — target: a true `rollout` with honest preview,
script-safe confirmation, `--wait`, and `--dry-run=server`.

**Findings:**
- [7][table-stakes][client-only] **The `[y/N]` prompt silently no-ops in scripts.** `confirmRollout` (`deploy.go:163-172`) reads stdin with no TTY check; in a non-TTY without `--yes`, `ReadString` hits EOF → returns `false` → prints "Cancelled" → **exit 0**. A CI pipeline that forgets `--yes` reports success while deploying nothing. Must: detect non-TTY and either require `--yes` (error if absent, non-zero exit) or treat missing TTY as a hard failure. This is the most dangerous bug in the section.
- [9/8][table-stakes][client-only] **The PLAN preview is misleading.** `healthcheck` is hardcoded `"on"` (`deploy.go:127`) regardless of `--health-gate`; the canary line prints only `--canary-instances` and shows `0` when `--canary-percent` is used (`deploy.go:132`); `batch size` shows "group default" but strategy defaults to `"rolling"` even if the group's `updateStrategy` is something else (the real default lives server-side at `resolveTriggerOptions`, `DeploymentRoutes.java:341`). The preview claims to be a plan but doesn't reflect the flags or the actual server defaults.
- [4][modern][needs-server] **No real dry-run/diff.** The backend has no preview endpoint, so the "PLAN" can't show prev→next template/image diff (`deploy.go:120-121` admits this). Add `POST …/deploy?dryRun=true` returning the resolved `DeploymentRolloutConfig` + the template snapshot it *would* apply, then render an honest `--dry-run=server` plan. The current PLAN should be labeled a *summary*, not a plan, until then.
- [4][table-stakes][client-only] **No `--wait` for non-interactive completion.** The async `202` (`DeploymentRoutes.java:208`) only converges via the TUI. Scripts get back a revision and nothing else. Add `--wait` (+ `--timeout`) that polls `GET …/deployments/<rev>` to a terminal state and sets exit code by outcome (0 COMPLETED, non-zero ROLLED_BACK/FAILED) — the kubectl `rollout status --watch` contract. Pairs with `--json` to emit the final record.
- [5][table-stakes][client-only] **`--canary-instances` / `--canary-percent` mutual exclusion not checked client-side.** Help says "mutually exclusive" (`deploy.go:352`) but `buildDeployBody` sends both; the server rejects with 400 (`DeploymentRoutes.java:359`). Use cobra `MarkFlagsMutuallyExclusive` to fail fast with a clear local message instead of a round-trip 400.
- [5][table-stakes][client-only] **No group picker** — `ExactArgs(1)` (`deploy.go:24`) while sibling info/update commands offer pickers. On a TTY, deploy should offer the same group picker.
- [5/8][modern][client-only] No flag validation locally for `--batch-size >=1`, `--promotion-timeout >=1`, `--min-healthy >=0` — all enforced server-side (`DeploymentRoutes.java:350-367`) and surfaced only as 400s. Validate client-side; offer completion for `--strategy` enum.
- [3][modern][needs-server] Topology: deploy is a write (`GROUPS_UPDATE`) and is HA-free at the app layer (shared Mongo), but the rollout *thread* runs on the controller that accepted the POST (`DeploymentRoutes.java:204`). If that controller dies mid-rollout, who resumes? The CLI should surface which controller owns the rollout, and `--wait` should survive a failover (re-poll via any member). Document the ownership/resume story.
- [6][modern][client-only] `--json` path skips the PLAN and TUI but emits only the initial `202` record (`deploy.go:42-48`) — no final state. With `--wait` this becomes the terminal record; without it, document that `--json` returns the *accepted* record, not the outcome.
- [9][modern][client-only] The live TUI image label is `platform-platformVersion` (`deploy.go:78`) — not the template chain that actually rolls out. Mildly misleading "image" framing for a Minecraft template deploy.

### `prexorctl deploy list <group>`
Current: `GET …/deployments?page&pageSize` → table REV/STATE/STRATEGY/TRIGGER/PROGRESS(bar)/CREATED (`deploy.go:214-273`). Best read as **`rollout history`**.

**Verdict: IMPROVE** — target: `rollout history` with consistent theming, a current-revision marker, and `--watch`.

**Findings:**
- [6/12][table-stakes][client-only] **Unthemed and inconsistent**: bare `"No deployments found."` and **no `ListHeader`/`ListFooter`** (`deploy.go:242,270`), unlike every other list command. Use the shared list chrome.
- [6][table-stakes][client-only] No marker for the *current/active* revision or the *last successful* one — the two things an operator scanning history actually wants. (`kubectl rollout history` highlights current.)
- [4][modern][client-only] No `--watch` to follow an in-progress rollout's row updating, despite `group list` having `--watch`.
- [6][modern][client-only] Pagination is page/pageSize only; no `--all` and no `--limit`/`-n`. The server caps pageSize at 100 (`DeploymentRoutes.java:102,470`); the CLI should expose a simple `-n`/`--limit` and auto-page for `--all`/`--json`.
- [2][modern][client-only] Naming: surface this as `deploy history <group>` (alias `list`) to match the `rollout` mental model.

### `prexorctl deploy show <group> <rev>`
Current: `GET …/deployments/<rev>` → KV blocks incl. nested rollout config (`deploy.go:275-319`). This is the **`rollout status`/`describe`** of one revision.

**Verdict: IMPROVE** — target: `rollout status <group>` (no rev = latest) + rich `describe` with the template snapshot diff and live progress.

**Findings:**
- [5][table-stakes][client-only] Requires an explicit `<rev>` (`ExactArgs(2)`). The most common query is "status of the *latest* rollout for this group" — make `rev` optional and default to latest; add `deploy status <group>` as the kubectl-style alias.
- [6][table-stakes][client-only] Does not render `templateSnapshot`/`configSnapshot` (present in the payload, `DeploymentRoutes.java:317-318`) — the actual template→hash map that defines what this revision deployed. That's the single most useful field for "what did r5 ship?" and it's dropped.
- [4][modern][client-only] No `--watch` to turn `show` into a live status view for an in-progress rev (today only the trigger path gets the live TUI).
- [6][modern][client-only] Progress shown as `N/M instances` text only; reuse the `ProgressBar` that `deploy list` already renders for consistency.
- [8][table-stakes][client-only] Invalid/nonexistent rev returns the bare server 400/404 mapped to exit code; no "no deployment r7 for <group>; latest is r5" hint.

### `prexorctl deploy pause | resume | rollback <group> <rev>` (`newDeployActionCmd`, `deploy.go:321-346`)
Current: `POST …/deployments/<rev>/<action>`; `--json` dumps result; success line uses `%q` on the group name.

**Verdict: IMPROVE** — target: the kubectl `rollout pause/resume/undo` trio with
gates on the destructive one and honest semantics.

**Findings:**
- [10][table-stakes][client-only] **`rollback` has no confirmation and no `--dry-run`** despite being disruptive. And per the backend it only flips `state=ROLLED_BACK` and re-runs nothing (`DeploymentRoutes.java:265-285`) — it does **not** restore template/module state (the Long text at `deploy.go:367` concedes this). So the verb over-promises: an operator expects `undo` to revert workloads. Either implement a real revert (needs-server: re-apply the prior revision's `templateSnapshot`) or rename to `mark-rolled-back` and require `--yes`. This is a correctness/expectation gap, not cosmetics.
- [4][modern][client-only] No state-precondition feedback: pausing an already-completed rollout, or resuming one that isn't paused, just flips `state` server-side with no guard. The CLI should read current state and refuse/explain (e.g. "r5 is COMPLETED; nothing to pause").
- [9][table-stakes][client-only] **Success message uses `%q`** (`deploy.go:342`) → prints the group name in quotes (`Deployment r2 for "lobby": rollback`), inconsistent with every other `'%s'` success line. Cosmetic but trivially fixable.
- [5][table-stakes][client-only] `ExactArgs(2)`, no pickers; `rev` could default to "latest in-progress" for `pause`/`resume`.
- [6][modern][client-only] `--json` emits the server's `{status:…}` ack only; no resulting deployment record, so a script can't confirm the new state without a follow-up `show`.

---

## Cross-cutting / systemic (artifact & rollout layer)

1. **`--json` and `-o` must be universal and complete.** `catalog recommend`,
   `catalog remove` ignore `--json` entirely; `catalog update` / `template rollback` /
   `deploy *` action verbs emit thin synthesized acks instead of the resulting
   resource. Adopt one rule: every mutating command, under `--json`, prints the
   **post-mutation resource** (or a structured ack with the new state), and every
   list/show supports `-o json|yaml|wide|name`. `[table-stakes][client-only]`

2. **One danger-gate model.** `catalog remove`, `template rollback`, `deploy rollback`
   have **no** gate; `deploy <group>` has a stdin `[y/N]` that no-ops in scripts.
   Standardize on `huh` confirm on a TTY + mandatory `--yes` (error, non-zero exit)
   off a TTY, plus `--dry-run` on every destructive/mutating verb. No command should
   ever silently succeed-without-acting in a non-TTY. `[table-stakes][client-only]`

3. **Partial-update safety, everywhere.** `catalog update` blanks fields because it
   sends unset flags; adopt the `flags.Changed`/`buildDeployBody` discipline for *all*
   PATCH bodies, and (server side) make every PATCH treat `null` as "keep".
   `[table-stakes][client-only + needs-server]`

4. **Async-convergence story = `--wait`/`--watch` as a first-class, CLI-wide pattern.**
   `deploy` is async (`202`) with completion only via TUI; `template rollback` /
   `deploy rollback` return before convergence. Add `--wait [--timeout]` (poll to a
   terminal state, exit code by outcome) and `--watch` (live follow) uniformly, mapped
   onto the existing deployment poll loop. `[table-stakes][client-only]`

5. **Reframe `deploy` as `rollout`.** Rename/alias to match kubectl's proven grammar:
   `deploy <group>` (trigger), `deploy status <group> [rev]`, `deploy history <group>`,
   `deploy pause/resume/undo`. Make `rev` default to "latest" across show/pause/resume.
   Add a real `--dry-run=server` once a preview endpoint exists. `[modern][client-only + needs-server]`

6. **Provenance & supply-chain trust as a visible, default-on concern.** Surface
   sha256 presence in `catalog list`, warn on unpinned `add`, auto-compute or
   auto-resolve checksums, and (long-term) tie into the project's existing cosign
   module-signing story for catalog jars. `[modern][client-only + needs-server]`

7. **Server gaps the artifact layer implies:** catalog version *resolver*
   (`paper@1.21` → url+sha), atomic `add --recommended`, catalog/template *in-use
   reverse index* (safe delete/rollback), `template diff`, arbitrary-target
   `template rollback --to`, deployment *dry-run/diff* endpoint, and a **real**
   deployment rollback that re-applies the prior revision's snapshot.
   `[needs-server]`

8. **Typed decoding + consistent list chrome.** `template list`/`versions` and all
   `deploy` handlers decode into `map[string]any` while `catalog list` uses typed
   structs; `deploy list` skips the shared `ListHeader/Footer`. Unify on typed DTOs
   (the structs already exist in `types.go`) and the shared list chrome.
   `[table-stakes][client-only]`

9. **Picker/`ExactArgs` consistency.** `catalog update/recommend/remove` offer
   pickers; `catalog add`, `template versions/rollback`, and every `deploy` command
   use `ExactArgs` with no fallback. Apply the `resolveArg`/picker pattern uniformly
   on a TTY. `[modern][client-only]`

10. **Enum validation + shell completion** for `--category`, `--config-format`,
    `--strategy` (and registered completion for platform/version/group/rev args).
    Invalid enums are currently accepted locally and only rejected (or silently
    misinterpreted) downstream. `[modern][client-only]`
