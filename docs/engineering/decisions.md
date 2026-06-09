# Architectural decisions

This is the rationale register. Every entry is a decision that was made deliberately, the alternatives that were rejected, and why. Read this when you wonder "why is this not a Spring Boot app" or "why is there no Grafana folder."

A note on tone: these aren't tablets from a mountain. They reflect what fits the target — a self-hosted Minecraft network orchestrator for one operator team running 50–5000 servers. They would be different decisions for a SaaS at 100x scale, or for a hobby project at 1/100 scale.

---

## ADR 1: Self-hosted, not SaaS

**Decision.** PrexorCloud runs on operator-owned infrastructure. There is no PrexorCloud-hosted control plane.

**Why.**
- The audience is network operators who already own bare metal or VM fleets and are running Minecraft 24/7. They do not need another billing relationship.
- Self-hosting means operators can plug PrexorCloud into whatever monitoring, secrets, and compliance environment they already have.
- A SaaS control plane would require multi-tenancy, quotas, abuse handling, region selection, and a billing pipeline. None of that adds a single feature an operator cares about.

**Trade-off.** The operator has to run MongoDB and Valkey themselves. We compensate with a Compose-first install and `prexorctl setup`.

---

## ADR 2: One controller, one operator team

**Decision.** PrexorCloud is single-tenant. There are no orgs, no projects, no per-tenant isolation.

**Why.** Same as ADR 1. Multi-tenancy is enormous infrastructure (auth scoping, quota enforcement, isolation in every store, cross-tenant leak audits) that the target audience does not need.

**Trade-off.** A hosting company that wants per-customer isolation has to run one PrexorCloud per customer. That is the right answer at this scale — and at much larger scales it would be the right answer for blast-radius reasons too.

---

## ADR 3: MongoDB for durable state, Valkey for coordination

**Decision.** Two backing stores. MongoDB owns anything that must survive a full restart. Valkey (or any Redis-protocol-compatible store) owns coordination.

**MongoDB owns:** users, roles, groups, templates, catalog, deployments, durable workflow intent, instance composition plans, module package metadata, per-module document storage, audit logs, crash records, recovery metadata, backup manifests, networks.

**Valkey owns:** distributed leases + fencing tokens, controller / node / session ownership, short-lived runtime snapshots, JWT and workload credential revocation with TTL, SSE replay buffers + tickets, REST / API / workload rate-limit counters, transient retry wakeups, per-module Redis key/value storage, cluster event fanout where durability is not required.

**Why two stores instead of one.**
- MongoDB has the wrong shape for ephemeral, TTL-driven coordination data. SCAN and TTL semantics on a Mongo collection are slow and noisy compared to Redis-protocol primitives.
- Valkey has the wrong shape for queries against composite documents (groups by parent, instances by node × state, audit search).
- Splitting the two also splits the durability requirement. Valkey loss costs a window of in-flight retries and SSE replay; MongoDB loss costs the cluster.

**Why Valkey by default and not Redis.** Licensing. Valkey is BSD-3, Redis is BSL/SSPL. We take the conservative path. Operators who already run Redis can keep using it — the controller speaks the Redis protocol and `redis://` URIs work unchanged.

**Why MongoDB and not Postgres.** Document shape. Composition plans, module manifests, workflow intent, and per-module storage are deeply nested and per-feature variable. Mongo's document model maps onto them cleanly. Switching to Postgres would mean either a tableful-of-JSON anti-pattern or a rigid schema that fights every new feature.

**Why not embed Mongo via Mongo Realm / Atlas Embedded.** SSPL on the embedded driver is not friendly for distribution. Self-hosted server is fine.

**Trade-off.** Two stores is more operational surface than one. We accept that, given the cleaner data model.

---

## ADR 4: Active-active HA, not active-passive

> **Update (v1.1).** The active-active architecture stands. The lease + fencing **mechanism** described below moves from Valkey-TTL to Raft leases under **ADR 29**. Subsequent sections of this ADR describe the original Valkey-based path and stay accurate for v1.0 deployments; v1.1 substitutes the Raft state machine for the same role.

**Decision.** Multiple controllers run simultaneously. Mutation is gated by lease + fencing token. There is no "leader" controller; any healthy controller can serve traffic and take leases.

**Why.**
- Active-passive failover always has a window where the standby is not yet ready and the leader is gone. Lease-scoped active-active eliminates that window — work simply moves to a different lease holder.
- The implementation is simpler than it sounds. Lease + fencing is well-trodden territory; adding it lets us delete a whole class of "is this the leader?" checks scattered across the scheduler.
- Standby promotion drills (drain mid-failover, deployment mid-failover, placement-time, in-flight module mutation) all work the same way under active-active: the new lease holder reconciles from durable state.

**Trade-off.** Requires Valkey. Single-controller deployments work fine; they just don't get the HA benefits.

---

## ADR 5: Constructor injection only, no DI framework

**Decision.** No Spring, no Guice, no Dagger. `PrexorCloudBootstrap` is the sole composition root. Every dependency is wired by hand.

**Why.**
- Annotation-based DI hides the dependency graph. With ~80 components and circular-ish wiring (scheduler ↔ controller, etc.), an opaque graph is a debugging tax.
- Annotation DI also slows startup measurably (Spring reflection, Guice graph build) and pulls in megabytes of dependencies for little benefit.
- Hand-wiring catches accidental circular dependencies at compile time. Spring catches them at startup, sometimes silently.

**Trade-off.** `PrexorCloudBootstrap` is a long file (~990 lines). It is also the one place an engineer can scan to see exactly what depends on what. We keep that file readable and let it grow.

**Documented exception — Velocity plugin Guice `@Inject`.** `cloud-plugins/.../velocity/PrexorCloudVelocity.java` is annotated with `@Inject` because Velocity's plugin loader (the framework boundary) injects `ProxyServer` and `Logger` itself. Plugin-host frameworks (Velocity, Bukkit, Folia, BungeeCord) own that lifecycle; we don't get a choice. Treat this as a sealed boundary — no other code in the repo may use `@Inject`, `@Autowired`, `@Component`, etc.

---

## ADR 6: Compose-first install, Helm is a stretch

**Decision.** The reference deployment is Docker Compose. Kubernetes (via Helm) is a stretch goal, not a release gate.

**Why.**
- The audience is operators running MC networks. They already have Docker. Most do not run Kubernetes — and if they do, they are running it for the dashboard infrastructure, not the MC processes.
- A daemon spawning JVMs inside a Kubernetes pod is awkward. Either you commit to "one MC instance per pod" (massive overhead per server, slow start, ports become a churn problem) or you let the daemon spawn child processes inside the pod (fighting the pod abstraction).
- Compose handles "one controller, two daemons, one Mongo, one Valkey" perfectly. systemd handles bare metal.

**Trade-off.** No K8s headline feature. Operators who want K8s can write their own manifests.

---

## ADR 7: No Kubernetes-style isolation (no cgroups, no containers per instance)

**Decision.** The daemon spawns MC processes with `ProcessBuilder` directly. No cgroup scoping, no per-instance containers, no namespace isolation.

**Why.**
- The latency budget for "operator clicks scale-up → player joins" is small. Container start adds seconds. Adding 5–30s to every scale-up is a regression.
- MC servers do not need defensive isolation against each other in the threat model. They run the same operator's code, served to the same operator's players. The threat model is around external network exposure, not lateral movement between instances.
- Resource limits are advisory: the daemon caps memory via `-Xmx` and disks are operator-managed. That is the level of "isolation" operators ask for.

**Trade-off.** A runaway server can starve a node. Operators mitigate this with per-host instance count limits and node selection. A future v2 could add cgroup scoping; it is not in v1 scope.

---

## ADR 8: Username + password + JWT only

**Decision.** Auth is local users with bcrypt-hashed passwords, issuing JWTs. Optional email-based password reset. No OIDC, no SAML, no SCIM, no MFA, no passkeys.

**Why.**
- A network operator team is small (1–10 people). The marginal benefit of SSO at that size is small; the marginal complexity (provider matrix, claim mapping, group sync, link/unlink flows, session stores) is large.
- We had OIDC. We deleted it. The justification was "credibility check for enterprise"; in practice the audience is not enterprise, and the maintenance cost outpaced any actual logins.
- Password reset gives the one operationally useful thing OIDC did — recover from "I locked myself out" without DB surgery.

**Trade-off.** A hosting company that wants central SSO across 30 operator teams cannot use one PrexorCloud (see ADR 2). They run one per team, which is fine.

---

## ADR 9: Prometheus only, no OpenTelemetry

> **Superseded in part (v1.2) by [ADR 30](#adr-30-opentelemetry-tracing-opt-in-alongside-prometheus).** Prometheus stays the metrics surface. The "no OTel SDK, no distributed tracing" stance is reversed: opt-in OpenTelemetry tracing now ships, off by default and a no-op when disabled. The reasoning below is preserved for v1.0/v1.1 context and still explains why tracing is *opt-in* rather than always-on.

**Decision.** The controller exposes Prometheus metrics on `/metrics`. There is no OTel SDK, no W3C trace-context propagation, no distributed tracing.

**Why.**
- Distributed tracing earns its keep when there are dozens of services with unclear call chains. PrexorCloud is two services (controller, daemon) with one well-defined gRPC contract. The trace tells you nothing the gRPC log does not.
- OTel adds a meaningful runtime cost and a non-trivial dependency surface. For a 1–10-process control plane it is over-engineering.
- Prometheus + structured JSON logging is the lowest-common-denominator stack operators already run. We do not push them onto a new tracing pipeline.

**Trade-off.** A future where PrexorCloud is decomposed into many services would re-open this. We are nowhere near that.

---

## ADR 10: No Grafana dashboard pack

**Decision.** We expose `/metrics` with stable, labelled time-series. We do not ship Grafana dashboard JSON, do not maintain provisioning manifests, and do not publish to grafana.com.

**Why.**
- Maintaining dashboards as code is a real burden. Every metric rename ripples into every panel. Every panel layout decision makes someone unhappy.
- Operators who run Grafana already have layout opinions. Imposing ours adds friction.
- The product dashboard already shows current state (groups, instances, deployments, players, modules). Trend analysis is a different audience and a different tool.
- If we need to point operators at trends, we can add an alert-rules `.yml` reference in [`monitoring.md`](../public/en/operations/monitoring.md). That is more useful than a dashboard.

**Trade-off.** Operators who *would* have used a turnkey pack do extra work. We accept that — the metrics are stable and well-named, and PromQL examples in [`monitoring.md`](../public/en/operations/monitoring.md) cover the common queries.

---

## ADR 11: SSE for live data, not WebSocket

**Decision.** The dashboard subscribes to a single SSE stream. We do not run a WebSocket server.

**Why.**
- Everything the dashboard receives is server → client. SSE is the standard fit for that direction.
- SSE plays nicely with HTTP/2 multiplexing, with reverse proxies, and with HTTP intermediaries that may strip WebSocket upgrade frames.
- Last-Event-ID resumption is built into SSE. Implementing the equivalent on WebSocket means rolling a session+sequence protocol on top.
- Dashboard → controller writes go through the same REST API humans and CLIs use. There is no need for a parallel write channel.

**Trade-off.** SSE has a 6-connection-per-host limit on HTTP/1.1 (HTTP/2 lifts this). Not relevant at our scale.

---

## ADR 12: Modules link via capability handles, never via classpath

**Decision.** Cross-module classloader exposure is forbidden. Modules expose typed capabilities (`CapabilityHandle<T>`) and consume them by capability name + interface. There is no shared "internal" types module.

**Why.**
- Without this rule, every module would eventually depend on every other module's internals, and unloading one would mean unloading all of them. We have seen this play out in other plugin systems.
- Capability handles let us provide a single named contract with at most one provider at a time, swap providers (variant resolution), and unload providers cleanly via a dynamic handle whose delegate is nulled on deactivation.

**Trade-off.** Modules cannot reach into each other's internals "just this once." That is the point.

---

## ADR 13: JVM modules, not WASM

**Decision.** Platform modules ship as JVM jars. WASM modules are not in scope.

**Why.**
- The audience is Minecraft server operators. They already have JVM expertise — every server they run is a JVM. Asking them to ship WASM would be a context switch with no payoff.
- Module authors get full access to capabilities, stores, REST routes, frontend manifests, everything. WASM would force capability-by-capability bridging or a bytecode interpreter inside the controller.
- The "why WASM" argument is sandboxing. Modules are signed bundles installed by the operator. The threat is *not* a hostile module — operators only install things they trust. The threat model is signed-bundle integrity (covered by the signature verifier) and supply-chain attestation (covered by Cosign + offline Rekor).

**Trade-off.** Modules can crash the controller JVM. That has not been a problem in practice. If it becomes one, the answer is process isolation — not WASM.

---

## ADR 14: No marketplace, no central index

> **Superseded in part (v1.2) by [ADR 31](#adr-31-a-signed-module-index-but-not-a-marketplace).** The *marketplace* rejection stands — no hosted server, no accounts, no ranking/takedowns/payments. But the blanket "no central index" is relaxed: an optional, signed, **static** index (discovery only, never a trust anchor) now ships. The reasoning below still explains why we refuse a *marketplace*.

**Decision.** Module install is `prexorctl module install <bundle>` against a local file or URL. Authors host their own bundles. There is no PrexorCloud-hosted index.

**Why.**
- Running a registry is its own startup. Discoverability, abuse handling, takedowns, search, ranking, popularity charts, deletion, GDPR — all real work.
- The audience is small. Operators who want a module either know the author or read README files. That works at this scale.

**Trade-off.** No "browse modules" button. Acceptable. A v2 could add a curated list (just markdown) without becoming a registry.

---

## ADR 15: Cosign + offline Rekor, not custom signing

**Decision.** Module bundles are signed via Cosign-format signatures. Production controllers verify signatures fail-closed. Optional offline Rekor SET enforcement binds signatures to a Rekor log entry without network access.

**Why.**
- Cosign is the closest thing to a standard for signing artefacts in 2026. Building yet another signing format would be re-inventing wheels for no reason.
- Offline Rekor SET verification (no network at install time) means the controller does not need internet access to verify signatures. We just need the Rekor public key bundled at config time.
- Inclusion-proof Merkle-path verification is *not* implemented. SET is enough — it binds the signature to a Rekor entry, which is enough for our threat model.

**Trade-off.** We do not get the strongest possible attestation (no inclusion proof) and we depend on Sigstore's ecosystem. Both acceptable.

---

## ADR 16: Hand-rendered Prometheus exposition in modules

**Decision.** When modules export Prometheus metrics, they format the exposition by hand. Modules do not pull in the Prometheus client library.

**Why.**
- Module dependencies are a slippery slope. Every dep a module pulls in is a potential conflict with the controller's classpath, a potential CVE we have to patch, and a potential 10× jar size.
- Prometheus exposition format is plain text. Hand-rendering is 30 lines and never breaks.
- The reference module (`stats-aggregator`) does this. Anyone copying it gets the pattern for free.

**Trade-off.** If exposition format gains a new feature, modules have to update by hand. The format has been stable for years.

---

## ADR 17: Configuration as Java records with compact constructors

**Decision.** All config is Jackson-mapped Java records. Compact constructors apply defaults. There are no separate "Config" + "ConfigBuilder" + "ConfigBuilderFactory" classes.

**Why.**
- Records are immutable, ergonomic, and serialise cleanly to and from YAML.
- Compact constructors are the right place for "this field defaults to X if absent" — close to the field, easy to read.
- Builders would add hundreds of lines of boilerplate for no behavioural improvement.

**Trade-off.** Adding a field means touching every positional constructor (back-compat for existing tests). Acceptable.

---

## ADR 18: Workload extensions resolve by manifest variant, not by classpath

**Decision.** When a module exposes a workload extension (e.g., a Paper plugin variant), the controller resolves which jar to ship based on a deterministic variant manifest. The daemon installs that exact jar into the instance.

**Why.**
- Multiple modules can offer Paper plugins. The controller decides which one applies to which instance based on group / template / version manifest matchers.
- The decision is hashed into the composition plan. The daemon does not invent the choice — it applies it. This is the same "controller decides, daemon executes" rule that makes HA + recovery tractable.

**Trade-off.** Variant resolution is one more thing to test (`extension hash mismatch + variant switch` in the test harness). Worth it.

---

## ADR 19: Imperative templates and groups, not GitOps

**Decision.** Operators create groups and templates via REST / CLI / dashboard. There is no Git pull loop reconciling YAML manifests into running state.

**Why.**
- GitOps adds a mental model that helps at one scale (large teams, regulated environments) and hurts at another (one operator team trying to scale up a lobby fast).
- The dashboard *is* the imperative interface, and it is designed for fast operator action.
- Anyone who really wants GitOps can wrap `prexorctl` in their own pipeline.

**Trade-off.** No "here's a YAML file, apply it" experience. The CLI does most of what GitOps would do (`prexorctl deploy`, `prexorctl group apply`, etc.) without the reconciler loop.

---

## ADR 20: No hot-reload UX with state preservation

**Decision.** Module reload = uninstall + install. There is no "swap a module in place while keeping its in-memory state."

**Why.**
- State-preserving hot reload is hard. It interacts with capability handles, dynamic proxies, classloader lifecycle, frontend manifests, and per-module storage. Getting it wrong silently corrupts state.
- Uninstall + install is the right contract: explicit, observable, testable. The window of unavailability is seconds.

**Trade-off.** Operators who expected to "patch a module live" reload via stop / install / start. That is the expected DX in this category.

---

## ADR 21: AI-assist is out of scope

**Decision.** No BYO-key LLM wrapper, no chat-with-your-cluster, no AI-generated runbooks.

**Why.**
- Operators reading these docs are perfectly capable of writing PromQL and reading crash records. Wrapping an LLM around them adds a hallucination risk for a function the operator is already good at.
- Maintaining a provider matrix (hosted and self-hosted LLM backends), a redaction layer for cluster state, dashboard surfaces, prompt templates, and evaluation drift would be a small product.
- If an operator wants LLM help, they can paste a crash record into the tool of their choice themselves. We do not need to ship that integration.

**Trade-off.** None we care about.

---

## ADR 22: i18n / a11y / theme / browser shell — descoped

**Decision.** The dashboard is English-only, theme-fixed, and does not include an in-browser xterm.js shell, replayable incident time-slider, or PWA manifest.

**Why.**
- Each of these is a separate engineering project, easy to underestimate.
- The audience is a small operator team. They are not blocked on any of these to operate the system.
- Real i18n would mean a translation pipeline, RTL support, locale-aware formatting in dozens of components — not just `t()` wrapping. The right time to invest is when there is a request from a non-English audience that uses the product.

**Trade-off.** PRs welcome at v2.

---

## ADR 23: Performance baselines, not performance gates

**Decision.** Nightly CI runs perf baselines (controller cold start, coordination-store latency, SSE latency, scheduler tick at 1k groups) and warns on >25% drift. CI does not fail on drift.

**Why.**
- Hard CI gates on perf require a stable test environment. GitHub Actions runners are noisy. A flaky gate trains operators to ignore it, which is worse than no gate.
- A drift signal in the run summary is an operator nudge, not a blocker. Anyone reviewing nightly runs sees it.

**Trade-off.** A real regression can land before drift triggers. We accept that — drift is not the only signal; operators report degraded behaviour quickly at this audience size.

---

## ADR 24: Drop docs in English, not multilingual

**Decision.** Every document in `docs/` is in English. We removed the German `CLOUD_GUIDE.md` even though it had the most detail of any doc.

**Why.**
- "Everyone should understand" — the user said so. English is the language of the rest of the codebase, the gRPC API, the JSON event payloads, the variable names, and the runbooks. Mixing languages forks the audience.
- One source of truth beats two languages of stale truth.

**Trade-off.** Operators who only read German lose a doc that was 1100 lines long. Acceptable — this is not a German-only project.

---

## ADR 25: Three-way Java toolchain split (21-api / 21-compat / 25-preview)

**Decision.** `java/build-logic/src/main/kotlin/` exposes three convention plugins, applied per-module:

| Convention plugin | Used by | Targets |
|---|---|---|
| `prexorcloud.java21-api.gradle.kts` | `cloud-api` (the unified public SPI) | Java 21 source + bytecode, conservative API |
| `prexorcloud.java21-compat.gradle.kts` | `cloud-plugins/*` (Paper / Spigot / Folia / Velocity / Bungee plugins) | Java 21 — matches what the host MC servers ship |
| `prexorcloud.java25-preview.gradle.kts` | `cloud-controller`, `cloud-daemon`, `cloud-modules:runtime`, controller modules | Java 25 with `--enable-preview` so we get pattern-matching + scoped values inside the control plane |

**Why three, not one.**
- **Java 21 for the public SPI.** Plugin and module developers compile against `cloud-api`. Pegging it to Java 21 means anyone running Paper 1.21+ (Java 21) can build modules without upgrading their toolchain.
- **Java 21 for in-server plugins.** Paper / Velocity / etc. JVMs are not under our control. Whatever the host runtime supports is what our plugin jar must run on.
- **Java 25 for the controller + daemon.** We do control these JVMs (we ship the Docker images and systemd units). Newer Java buys real ergonomics — pattern-matched switch, virtual threads, scoped values — and we get measurable startup + GC wins.

**Trade-off.** Three convention plugins are more moving parts than one. The boundary between them is a single-file decision (`apply(plugin = "prexorcloud.java21-api")` vs the others), so the friction is concentrated, not spread out. Anyone adding a new module has to pick one — that's documented in the convention-plugin headers themselves.

**Don't simplify.** A future contributor will be tempted to "just standardise on Java 25". That breaks the public SPI for every plugin/module developer running Paper 1.21. The split is deliberate.

---

## ADR 26: `_generated/` content trees stay committed (for now)

**Decision.** The two auto-generated reference trees live in git:
- `docs/public/en/reference/cli/_generated/` — emitted by `tools/gen-cli-docs.ts` from `prexorctl --help`
- `docs/public/en/internals/protocol/_generated/` — emitted by `tools/gen-grpc-docs.sh` from `cloud-protocol/src/main/proto/`

**Why.** Committing them surfaces drift in PR review: when a CLI command grows a flag, the diff appears next to the code change, and the reviewer can confirm the help text reads correctly. Git-ignoring them would hide the surface from PR review.

**Guard rail.** A CI job re-runs both generators and fails if there's a working-tree diff (see `.github/workflows/ci.yml :: drift-check`). Contributors get a "run `pnpm gen:cli` before committing" error if they forget.

**Future.** If the trees grow past ~500 files, revisit — at that point the noise outweighs the review value, and we git-ignore + drift-check.

---

## ADR 27: `prexorctl context` — single-file, plaintext, kubeconfig-shaped

**Decision.** `prexorctl` keeps a list of named *contexts* (controller URL + token, optional per-context label) in a single file at `~/.prexorcloud/config.yml`, alongside a `currentContext` pointer and any non-context-scoped user preferences (e.g. `accent`). Tokens are stored as plaintext JWTs and protected by file permissions (`0600` file, `0700` directory). Switching contexts is a CLI-local operation; the dashboard is not involved.

**Shape.**

```yaml
currentContext: prod
contexts:
  prod:
    controller: https://prexor.example.com
    token: eyJhbGciOi...
  staging:
    controller: https://prexor.staging.example.com
    token: eyJhbGciOi...
accent: cyan
```

Pre-context configs (flat `controller:` + `token:` at the root) are migrated lazily: on load, if `contexts` is absent and `controller` is set, the loader synthesises a single `default` context and rewrites the file on next save. No prompts, no separate migration command.

**Commands.**
- `prexorctl context list` — table of name / controller / current marker.
- `prexorctl context current` — print the active context name (scripting).
- `prexorctl context use <name>` — set `currentContext`.
- `prexorctl context add <name> --controller=URL [--token=TOK]` — append a context.
- `prexorctl context remove <name>` — drop a context; refuse if it is `currentContext` unless `--force` is passed (then clear the pointer).
- `prexorctl context rename <old> <new>` — convenience; not MVP-blocking.

A global `--context <name>` flag overrides `currentContext` for a single invocation.

**Resolution order** (highest wins, evaluated independently for each field):
1. `--controller` / `--token` flag.
2. `PREXOR_CONTROLLER` / `PREXOR_TOKEN` env var.
3. `--context <name>` flag → that context's `controller` / `token`.
4. `PREXOR_CONTEXT` env var → that context's `controller` / `token`.
5. `currentContext` from `config.yml`.

This preserves the current flag > env > config precedence (the existing `Config.Resolve` / `ResolveToken` semantics) and inserts the context selectors at the boundary between env and stored config.

**Why one file, not a directory of per-context files.**
- Single-file is what kubeconfig, `~/.aws/config`, and `~/.netrc` all do; users know how to back it up, version it, and `chmod` it.
- Atomic rewrites are a single `os.WriteFile` to a temp path + `os.Rename`. A directory of files makes "set current context" a two-file mutation that can tear.
- The file stays small (a handful of contexts, a few kilobytes); the readability argument for splitting only kicks in around dozens of entries.
- A future `PREXOR_CONFIG=:path1:path2` merge mode (kubeconfig-style) can be layered on without changing the single-file default.

**Why plaintext tokens.**
- Encrypted-at-rest needs a key source. Two real options, both worse:
  - **Passphrase on every invocation.** Breaks `prexorctl deploy` in CI, breaks shell scripts, breaks the `prexorctl module dev` watch loop. Caching the unlocked secret means there's a plaintext cache somewhere — we're back where we started, with one more moving part.
  - **OS keychain.** Cross-platform mess (libsecret on Linux, Keychain on macOS, DPAPI on Windows), each with its own auth-prompt and lock-on-screensaver UX. Per ADR 22 we have explicitly descoped OS-shell integration, and the keychain story belongs in the same bucket.
- `0600` + `0700` is the established unix posture for credential files: `~/.ssh/id_*`, `~/.kube/config`, `~/.aws/credentials`. An attacker who can read mode-0600 files in the operator's home already owns the machine; encrypted-at-rest with the passphrase typed on the same machine does not defend against that threat model.
- Tokens are short-lived JWTs. Rotation is cheap (re-login emits a new token). If a token leaks, the operator rotates it; we do not pretend the file is a long-term secret store.

**Why the dashboard is not part of this.**
- Switching `prexorctl` context only changes which controller the *CLI process* talks to. Each dashboard tab holds its own session cookie scoped to whatever controller served the tab. Two browser tabs against two controllers are already independent and stay independent.
- The "cross-tab UX" question is a non-problem; resolved by being explicit that the CLI context is CLI-local.

**Trade-off.** Operators who want hardware-token-backed credentials or screensaver-locking on their `prexorctl` install do not get it from us. They can wrap the binary in a launcher that decrypts a sealed `config.yml` into a tmpfs path and sets `PREXOR_CONTROLLER` / `PREXOR_TOKEN`; the env-var override path stays as the integration point for that.

**Scope of the implementing PR.** Data-model + load/save migration + `prexorctl context {list,current,use,add,remove}` + global `--context` flag + env-var hookup + tests. Renames, dashboard awareness, and multi-file merge stay out of MVP.

---

## ADR 28: Module `RELOADING` — a fast path, gated on compatibility, opt-in

**Decision.** Add a `RELOADING` state to the platform-module lifecycle and an `onReload(ModuleContext)` hook to `PlatformModule`. When an **active** module is replaced by a new jar that is **reload-compatible**, the lifecycle takes `ACTIVE → RELOADING → ACTIVE`, invoking only `onReload` on the new entrypoint — skipping `onStop` / `onUnload` / `onLoad` / `onStart`. The old classloader is closed once `onReload` returns. This is a fast path layered beside `upgrade()`, not a replacement for it.

**Why this exists.** The capability layer is already OSGi-style: consumers hold a stable `DynamicCapabilityHandle`, and `CapabilityRegistry.replaceModuleBindings` swaps the provider's delegate underneath them without a re-resolve. But the *lifecycle* does not match — `ModuleLifecycleManager.upgrade()` still drives an active module through the full `ACTIVE → STOPPING → onStop → onUnload → INSTALLED → onLoad → onUpgrade → onStart → ACTIVE` teardown/reinit. For the `prexorctl module dev` watch loop that is slow and throws away the module's warm internal state on every save. `RELOADING` closes that gap: the capability layer can already absorb the swap, so let the lifecycle do the same.

**State-machine additions.**
- `ACTIVE → RELOADING` — a new jar arrives for an active, reload-eligible module.
- `RELOADING → ACTIVE` — `onReload` returned cleanly.
- `RELOADING → FAILED` — `onReload` threw.

`RELOADING` is reachable **only** from `ACTIVE`. A module being replaced from `INSTALLED` / `WAITING` goes through `install` / `upgrade` as today — there is no warm state to preserve, so the fast path buys nothing. Like `STOPPING`, `RELOADING` is transient: it is held only inside the `ModuleLifecycleManager`'s `synchronized` block and never persists across a lock release on the happy path, though it is observable in a snapshot taken mid-reload.

**The `onReload` hook, and why it is not `onUpgrade`.** `onUpgrade` is the *middle* hook of the full upgrade path — it runs between a fresh `onLoad` and `onStart`, so a module can lean on those for setup. `onReload` is the *only* hook the fast path calls. That difference is the whole point: `onReload` tells the module "your predecessor was never stopped — you must hand off live state yourself." The module re-arms its scheduler tasks, rebuilds or re-points caches, and re-registers routes from inside `onReload`. The `ModuleContext` carries `previousVersion` so the module can diff. Reusing `onUpgrade` would blur a contract that needs to be sharp.

**Routes still re-register.** `onRegisterRoutes` runs in the reload path exactly as in `upgrade()`: route handlers are classes in the *old* classloader, so they cannot be "kept warm" — the bytecode changed. The reload clears and re-registers routes against the new classloader. This is cheap and unavoidable; it is the one part of the module that is always rebuilt.

**Reload-compatibility gate.** The fast path is taken only when **both** hold:
1. The new manifest opts in — `backend.controller.reloadable: true` (an additive optional field on manifestVersion 2).
2. The capability declaration is **identical** to the running version — same `provides`, same `requires`. Any change to either falls back to `upgrade()`, because changed `requires` must be re-reconciled and changed `provides` can dangle consumers.

If either check fails, or the module simply does not set the flag, the existing `upgrade()` path runs and today's behaviour is preserved.

**Why opt-in.** `onReload` shifts the burden of live-state hand-off onto the module author; the framework cannot verify the author actually did it. A module that does not implement `onReload` but were silently fast-pathed would keep running stale internal state while looking `ACTIVE`. The manifest flag makes the contract explicit — "I implement `onReload` and can hand off my own state" — and the default (off) gives every existing module the safe full-upgrade path with no change.

**Failure semantics — no rollback.** If `onReload` throws, the module goes `RELOADING → FAILED`. There is deliberately no rollback to the old runtime: `onReload` may have already mutated shared state (capability delegates, scheduler, routes) before throwing, and the module contract cannot promise side-effect-freedom up to the throw point. Keeping the old runtime alive as a rollback target would also double the count of live classloaders for the window. The operator recovers with a full reinstall; the `prexorctl module dev` loop surfaces the error inline, which is exactly where a botched `onReload` should be caught.

**Trigger point.** `PlatformModuleManager.install()` already branches install-vs-upgrade. It gains a third branch: previous state is `ACTIVE` **and** the new jar passes the compatibility gate → `lifecycleManager.reload(...)`. Capability handles swap through the existing `replaceModuleBindings` call the active-upgrade branch already makes. The `prexorctl module dev` jar-reload track flows through `install()` unchanged and picks up the fast path for free.

**Trade-off.** A module author who wants fast reload must implement `onReload` *correctly* — a buggy hand-off produces a half-reloaded module that still reports `ACTIVE`, and the framework cannot catch that. Accepted because: the flag is opt-in; the primary consumer is the dev watch loop where the author is actively iterating and will see the breakage immediately; and a production install can leave the flag off and keep the fully-safe upgrade path.

**Scope of the implementing PR.** `ModuleState.RELOADING` + `onReload` default-no-op hook on `PlatformModule` + `ModuleLifecycleManager.reload(...)` + the compatibility gate + the manifest `reloadable` flag + the `PlatformModuleManager.install()` third branch + close-old-classloader-after-`onReload` + lifecycle tests covering the fast path, the gate falling back to `upgrade()`, and the `RELOADING → FAILED` throw path. Out of scope: rolling back a failed reload, reloading non-active modules, and the frontend bundle reload (already shipped separately).

---

## ADR 29: Embedded Apache Ratis for the cluster control plane

**Decision.** Multi-controller HA is backed by an embedded Apache Ratis Raft group. The N controllers form one Raft group with a typed state machine (`ClusterControlStateMachine`) that holds cluster identity, versioned config, members, join tokens, and named leader leases. MongoDB stays the system-of-record for business state (templates, instances, deployments, audit log, shares); Valkey stays for ephemeral high-frequency state (rate-limit counters, SSE tickets, daemon heartbeats, console buffers). The Raft state machine holds none of that.

**Why Ratis and not something else.**
- **Apache Ratis** is actively maintained, Apache 2, production-hardened by Apache Ozone (exabytes of storage), and ships a gRPC transport that drops into our existing gRPC infrastructure. **Selected.**
- **MicroRaft** is clean but development has slowed; we'd own more of the stack over time.
- **JRaft (Alibaba)** is heavy, opinionated, and tied to the SOFA framework.
- **Atomix** stalled in 2022.
- **Roll our own.** Rejected. Raft is famously easy to get subtly wrong and this is not the place to be original.

**Why embed at all.**
- **No external coordinator.** No etcd, no Consul, no ZooKeeper. The controllers *are* the coordinator. Operations stays "N controllers + Mongo + Valkey", the same envelope we had before HA.
- **Strong consistency by construction.** Two operators patching the CORS list at the same instant cannot lose a write; the Raft log linearises them. No optimistic-retry dance against Mongo.
- **Membership is first-class.** Joint-consensus member-add is one Raft operation; the same code path handles a cluster of size 1 and a cluster of size 7. Split-brain is structurally prevented by quorum.
- **Leader election is free.** Named leases (scheduler, deployment-reconciler, DR-drill, audit-pruner) live in the state machine. Replaces the previous Valkey-TTL lease dance and its known races at expiry.

**Trade-off.** Raft is non-trivial. Snapshot tuning, log compaction, joint-consensus during membership changes, recovery from majority loss are all real ops concerns. We accept that complexity in exchange for the guarantees above; it is the same complexity every serious cloud control plane runs (Consul, etcd, CockroachDB, Kafka KRaft, NATS JetStream, Ozone).

**Boundary — what is NOT in Raft.**
- Business state (templates, instances, deployments, audit log, shares) stays in Mongo.
- Ephemeral fan-out (player join/leave, console lines, daemon heartbeats) stays on Valkey pub/sub.
- Daemon-side state stays on the daemon's local filesystem.

**Cross-cutting impact.**
- Supersedes the **mechanism** in **ADR 4 "Active-active HA, not active-passive"** — active-active is preserved, but the lease + fencing primitive moves from Valkey TTL to Raft leases. The architectural shape is unchanged; the substrate is stronger.
- Live config reload is the Raft `apply()` itself. Subscribers (CorsAllowList, JwtManager, RateLimiter) react to a typed `ClusterConfigChangedEvent` on the controller's local EventBus. No Redis round-trip for config.

**Status.** Shipped in v1.1 (2026-05-31), all phases complete: gRPC membership + TLS bootstrap, REST + `prexorctl cluster`, leader leases, the wizard token-join branch, the dashboard `/cluster` view, and recovery tooling. The operator path is in [`upgrade-v1.0-to-v1.1.md`](../runbooks/upgrade-v1.0-to-v1.1.md) and [`recover-cluster.md`](../runbooks/recover-cluster.md); the planning track lives in `northstar-plan.md` (Track A).

---

## ADR 30: OpenTelemetry tracing, opt-in alongside Prometheus

> Reverses, in part, **[ADR 9](#adr-9-prometheus-only-no-opentelemetry)** — read that first for the original "no tracing" reasoning, which still explains why this is opt-in.

**Decision.** PrexorCloud ships OpenTelemetry distributed tracing, **off by default**. Prometheus stays the metrics surface; structured JSON stays the log surface. Tracing is a third, optional signal: enable it with `telemetry.enabled=true` (controller and daemon each have their own block). When disabled, a no-op tracer is installed, the SDK is never built, and there is effectively zero runtime cost.

When enabled, spans are batch-exported over OTLP to any compatible collector (Jaeger, Tempo, Honeycomb, Datadog). The controller traces its HTTP server requests (continuing an inbound W3C `traceparent`), `auth.login` / `auth.token-verify`, `scheduler.tick`, instance placement, deployment reconcile, and each committed `raft.apply`. Trace context propagates Controller → Daemon over the existing gRPC command stream (a `traceparent` field on `ControllerMessage`), where the daemon continues it with a `daemon.command` span.

**Why reverse ADR 9.**
- ADR 9's "two services, one gRPC contract, the trace tells you nothing the log doesn't" held at v1.0. It is weaker now: a start command crosses controller → daemon → server process with async hand-offs and lease-holder indirection, and "where did this go / why was it slow" is a genuine cross-process question a trace answers and correlated logs answer poorly.
- The cost objection is addressed by construction: disabled is a no-op tracer with no SDK, so operators who don't want tracing pay nothing. This is strictly additive — Prometheus and logs are untouched.
- Operators increasingly already run Tempo/Jaeger and would rather point PrexorCloud at it than reconstruct call chains from logs.

**Boundary — what this is not.**
- **Not on by default.** No behavioural or cost change unless explicitly enabled.
- **Not metrics or logs.** Prometheus stays the metrics surface (ADR 9's metrics half is unchanged); logs stay structured JSON — there is no OTel logs/metrics pipeline.
- **No javaagent.** Spans are placed by hand at meaningful boundaries; there is no auto-instrumentation agent and no gRPC/Mongo/Valkey auto-spans.
- **MC-plugin hop not yet traced.** The chain reaches the daemon; pushing it into the Bukkit/Velocity plugin is a later step.

**Trade-off.** A dependency surface (the OTel SDK + OTLP exporter) and a pair of config blocks that sit dormant unless enabled. We accept that for a signal that is increasingly expected of a control plane and costs nothing when off. See `docs/engineering/northstar-plan.md` Track D.

---

## ADR 31: A signed module index, but not a marketplace

> Relaxes, in part, **[ADR 14](#adr-14-no-marketplace-no-central-index)** — the marketplace rejection there still stands; this only adds a static, signed *index* for discovery.

**Decision.** PrexorCloud supports optional module **registries**: a registry is a static JSON index file (hosted by anyone on GitHub Pages, S3, a plain web server) listing modules with `moduleId`, `version`, `jarUrl`, `sha256`, signature sidecar URL, and metadata. Operators configure a list of index URLs in `modules.registries`. The controller can then browse/search them, and resolve + download + install a module by id — but **the registry is discovery only, never a trust anchor**. Every install passes the same two gates as a manual upload: sha256 pinned against the index, and signature verified against the controller's **own** configured trust root. `prexorctl module {search,install,upgrade}` and a dashboard browse page sit on top.

**Why relax ADR 14.**
- ADR 14 rejected "a marketplace **and** a central index" because running a registry is a startup: accounts, abuse handling, takedowns, ranking, GDPR. Every one of those costs is a property of a *hosted, mutable, social* registry — **a marketplace**. A static signed index has none of them: it is a file. There is nothing to moderate, rank, or take down on our side; an operator who distrusts an index simply doesn't configure it.
- The discovery gap ADR 14 accepted ("operators know the author or read READMEs") is real friction once there is more than a handful of first-party modules. A signed index closes it with one-command install/upgrade while keeping the trust story identical.
- Differentiation: a *signed* module index is something no other Minecraft-cloud system offers. Doing it without becoming a marketplace is the whole point.

**Boundary — what this is not.**
- **Not a marketplace.** No PrexorCloud-hosted server, no accounts, no uploads-to-us, no ranking/popularity/payments. ADR 14's marketplace rejection is intact.
- **Not a trust anchor.** The index can claim anything; trust is the controller's own cosign/Rekor trust root. Fail-closed: an unverifiable module does not install.
- **SSRF-bounded.** The registry-install REST route only accepts a `registryUrl` already in the configured list.
- **No first-party hosted registry yet.** `registry.prexorcloud.dev` is a follow-up; the mechanism works against any operator-provided index today.

**Trade-off.** A modest amount of resolve/download/verify code in the controller and three CLI verbs. We accept that for discovery + signed one-command install, having kept every cost ADR 14 worried about out of scope. See `docs/engineering/northstar-plan.md` Track C.1.

---

## ADR 32: Design tokens stay mirrored and CI-guarded, not imported as a package

> Resolves the open "NPM-Workspace" question in **`docs/engineering/northstar-plan.md` Track E.1**. The earlier framing assumed surfaces would eventually `import` a `@prexorcloud/design-system` package; this records why the guarded-mirror approach is the chosen steady state instead.

**Decision.** `design-system/` is the canonical token source. `tokens.json` is the machine source of truth; a zero-dependency generator (`build-tokens.mjs`) emits committed `dist/tokens.{css,ts,json}`, and `colors_and_type.css` is the human-readable mirror that CI proves is in lockstep. Each surface — website (Tailwind/HSL), dashboard (Nuxt/Tailwind), installer (Vite), CLI (ANSI) — continues to **reimplement** the tokens in its own stack rather than importing the package. A CI parity suite (`design-system/__tests__/`) pins what must not drift: the raw colour scales (reef/ink/sand/state) and the `--text-*` size scale, across all three frontends, against the canon. The one programmatic consumer — the website's Mermaid docs palette — reads the generated `dist/tokens.json` through a build-time Node script (the same pattern as `sync-openapi.mjs`), not an npm import.

**Why mirror + guard, not package import.**
- The real problem was **silent drift**, not duplication: the canon (saturated cyan-9/slate) and the shipped surfaces (softened "Quiet Studio" reef/ink/sand) diverged for weeks because nothing checked them against each other. A drift guard closes that directly and mechanically — a surface that re-tunes a pinned token fails CI — which removes most of the value the heavier migration was meant to deliver.
- The four surfaces are **independent pnpm projects** with separate lockfiles; there is no monorepo-wide workspace. Package imports would mean either a root workspace (restructuring every surface's install + CI) or per-surface `file:` deps (lockfile churn under `--frozen-lockfile`, plus Vite externalisation for the `.ts`/HSL forms). Real cost, for a single-operator project.
- Surfaces legitimately need **different forms** of the same tokens: the website needs HSL triplets (Tailwind alpha syntax `bg-primary/20`), the CLI needs ANSI-256 approximations, and each keeps surface-specific semantic aliases (`--bg`/`--surface` vs shadcn's `--background`/`--card`). A single imported CSS artifact wouldn't serve them without per-surface transforms anyway — so "implement the tokens, don't import them" is a deliberate fit, not just inertia.

**Boundary — what this is not.**
- **Not a ban on consumption.** `dist/` is real and importable; the Mermaid palette already consumes it. A future surface needing token *values* in JS/TS can read `dist/tokens.{ts,json}` via a build script, or import the package if a workspace is later introduced.
- **Not unguarded mirroring.** Colour raw scales and the `--text-*` scale are pinned across all three frontends. Semantic aliases and radius are intentionally *not* pinned — semantics are surface-specific by design, and radius uses divergent var names (`--radius-*` vs `--r-*`), so only its values were reconciled.
- **Not Style-Dictionary.** The generator is deliberately zero-dependency (a cosign/Rekor-hardened project does not want a token toolchain in its supply chain). Revisit only if multi-platform export (Figma sync, Android) becomes a real need.

**Trade-off.** Each surface keeps a hand-maintained token block that must be updated alongside `tokens.json` — but the guard tells you exactly when one drifts, and each copy is in the form its stack actually needs. We accept N guarded copies over a workspace + package import + per-surface build transforms, which is more moving parts than a single-operator project with an already-closed drift risk can justify. See `docs/engineering/northstar-plan.md` Track E.

## ADR 33: Design-system is a token + CSS reference, not a component runtime

> Closes the open "component library" and "Histoire stories" items in **`docs/engineering/northstar-plan.md` Track E.1**. The corollary of ADR 32 for components: the same import-vs-mirror logic that kept tokens out of a shared package keeps components out of one too.

**Decision.** `design-system/` ships component *primitives* as a **token-only CSS reference** (`components.css`: button, input, select, checkbox, switch, badge, card, table, tooltip) — never as a runtime Vue/React component package and never with a Histoire/Storybook app. Each surface keeps implementing its own components (dashboard: Reka-UI primitives; installer: native `<button>` + `.btn`; website: Starlight) and uses `components.css` the way it uses `colors_and_type.css` — as the canonical spec to mirror. CI (`__tests__/components.test.mjs`) enforces the headless rule: `components.css` may carry **no** hardcoded colour, and every `var(--token)` it references must be defined in the canon, so the primitives stay restyleable purely by swapping tokens.

**Why not a Vue component package + Histoire.**
- It would pull heavy frontend tooling (Vue, a bundler, Histoire) into the deliberately zero-dependency design-system package — the same supply-chain cost ADR 32 rejected for a token toolchain.
- It would reopen the **import-vs-mirror** question ADR 32 settled: four independent pnpm projects, no workspace, surfaces that each need their own component idioms (Reka-UI a11y wiring vs native vs Starlight). A shared runtime component wouldn't drop cleanly into all three.
- The actual recurring failure mode is **visual/contrast drift**, which `components.test.mjs` (no hardcoded colour) + the token contrast guard (ADR-32 suite, `contrast.test.mjs`) already catch — without a component runtime.

**Boundary — what this is not.**
- **Not "no components."** `components.css` is real and broadenable; new shared primitives are added there (token-only, CI-guarded), not in a JS package.
- **Not a ban on a future package.** If a workspace is ever introduced (the ADR-32 escape hatch), real headless components could live alongside the CSS reference. Not a v1.x need.

**Trade-off.** Surfaces re-implement component markup/behaviour rather than importing it — accepted, because the per-surface idioms differ anyway and the only thing that must not drift (token-driven styling) is mechanically guarded. See `docs/engineering/northstar-plan.md` Track E (E-P4).

