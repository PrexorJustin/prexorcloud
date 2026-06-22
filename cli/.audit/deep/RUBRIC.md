# prexorctl deep-review rubric — evaluate EVERY command on ALL of this

You are auditing the `prexorctl` CLI to make it **the best, most modern, most
innovative, most user-friendly** control-plane CLI possible. Do NOT just hunt
bugs. For **every command and subcommand** in your scope, run the FULL checklist
below and propose the best-in-class target. Skipping a dimension because it
"seems fine" is not allowed — state your verdict on each.

## Architecture context (assume true; verified)
- Controller is **HA via embedded Ratis**: any controller "serves reads and forwards writes to the leader". `isLeader()` exists internally but is **NOT exposed** via REST today.
- The fleet shares one **JWT_SECRET** → an operator token is valid on **any** member.
- App state (groups/instances/users/catalog) lives in **shared Mongo**; only Raft *membership* ops route through the leader.
- ⇒ Transparent **any-member failover** is architecturally free. Today a CLI context pins **one** controller URL with **no failover, no endpoint discovery, no token refresh, no identity**.
- Many orchestration ops are **async** (scale/deploy/start return before the workload is RUNNING; the scheduler reconciles).
- `GET /api/v1/cluster/members` returns each member's `restAddr` (the failover substrate).

## Per-command evaluation — answer each dimension
1. **Necessity & grammar** — Is this command needed, or redundant/mergeable (declutter)? Is the noun/verb right and predictable? Should it be an alias of something else?
2. **Naming & mental model** — Consistent noun→verb grammar with siblings? Short aliases? Does CLI naming match backend/domain (e.g. `instance` vs `/api/v1/services`)?
3. **Topology / distributed behavior** — Behavior under single-controller AND HA: controller-down failover, leader change, multi-endpoint, partition/quorum loss, which node serves it, does it need the leader.
4. **State & lifecycle** — Idempotency; **async convergence** (should it offer `--wait`/`--for=condition`/`--timeout`?); partial failure; retries; reconciliation; optimistic concurrency (version conflicts).
5. **Input model** — args vs flags vs stdin vs file; required/optional; validation; enums; tab-completion; interactive pickers; sane defaults; reading secrets from stdin not argv.
6. **Output model** — human (table/card/tree) AND machine: `--json`, and ideally `-o yaml|wide|name|jsonpath|template|csv`; `--quiet`; stable scriptable contract; streaming where relevant.
7. **Scriptability & automation** — non-TTY behavior (no hidden prompts/TUIs that hang); exit-code semantics; env vars; idempotent re-runs; pipe-ability; `--no-input`.
8. **Errors & recovery** — actionable messages (not bare HTTP codes); error taxonomy; JSON error envelope under `--json`; "did you mean…"; remediation hints; correct exit codes.
9. **UX, discoverability, help** — help accuracy + examples; progressive disclosure; confirmations & danger gates; first-run/onboarding; latency feedback (spinners/progress); accessibility (`--no-color`/`--ascii`).
10. **Safety & security** — blast radius; destructive gates (`--yes` / typed-confirm / `--dry-run`); secret handling; TLS hygiene; least-privilege; auditability.
11. **Performance & efficiency** — round-trips; pagination; client-side caching; streaming vs buffering; large-output handling; startup cost.
12. **Consistency & composability** — Follows the same patterns as sibling commands? Composes in pipelines (stdout = data, stderr = chatter)?
13. **Modern & innovative** — Benchmark against best-in-class: **kubectl** (get/describe/apply/-o/--watch/wait/rollout/scale/cordon/drain/edit/explain), **gh**, **stripe**, **flyctl**, **cockroach**, **etcdctl** (--endpoints/member/snapshot), **consul operator raft**, **doctl**, **wrangler**, **turso/planetscale**. What would modernize this command? Candidates: declarative `apply -f`, unified `get/describe`, `--watch`, `--wait`, `--dry-run=client|server`, rich `-o`, resource aliases/short-names, `edit`, live TUI dashboards, krew-style plugins, self-update, AI-assist, shell-completion depth.

## Output format (write to your assigned doc)
For each command, a block:
- `### prexorctl <command>` — current one-line behavior.
- **Verdict:** one of `KEEP` / `IMPROVE` / `DECLUTTER (merge/remove)` / `REPLACE` + a one-sentence "best-in-class target".
- **Findings:** bullets, each tagged with the dimension number(s) above, a priority `[table-stakes]` / `[modern]` / `[innovative]`, and `[client-only]` or `[needs-server]`.

End your doc with a **"Cross-cutting / systemic"** section: patterns that should be fixed once, CLI-wide (output system, error/exit model, async-convergence story, auth/endpoint model, danger-gate model, completion, theming, plus any net-new commands the domain implies).

Be exhaustive and specific (cite `file:line` where useful). This is a maximum-effort pass.
