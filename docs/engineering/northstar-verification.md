# Northstar — Verification & Sign-off Guide

**Companion to:** [`northstar-plan.md`](./northstar-plan.md)
**Purpose:** the exact steps to prove every open item in the plan is actually done, so the plan can be moved to **DONE** honestly — not on faith.
**Written:** 2026-06-07.

> Language note: the plan is in German; this runbook is in English because it's a working checklist. Say the word and I'll mirror it to German.

---

## 0. The honest headline — read this first

The plan is **~81 % eng-day-weighted**. What remains is **almost entirely runtime/infra verification of already-written code**, plus a few non-test items. That means:

**You cannot mark the plan "completely DONE" by running `./gradlew build`.** The remaining work splits into three tiers, and only Tier 1 is pure CI:

| Tier | What it is | Can a test close it? | Blocker to "DONE"? |
|------|-----------|----------------------|--------------------|
| **1 — Automated gates** | Everything code-complete must stay green | ✅ Yes, runnable now | No — confidence floor |
| **2 — Infra/runtime lab** | Real MC servers, Geyser+Bedrock client, external Mongo at scale, 3-node Raft, CI test-login | ⚠️ Yes, but needs infra you must stand up | **Yes — the real blockers** |
| **3 — Not a test** | Track I docs rewrite, registry hosting, 60-day perf trend, explicit follow-ups | ❌ No — it's work or a decision | **Yes — needs a call, not a run** |

So "mark it DONE" = run Tier 1 → stand up the Tier 2 lab and walk the checklist → make explicit decisions on Tier 3 (do / defer / descope). The sign-off matrix in §4 is the single source of truth.

### Environment prerequisites (all tiers)

- **JDK ≥ 21 to run Gradle** (the Loom/ModDevGradle plugins load into the daemon JVM). **Spotless formatting needs JDK 25** specifically. CI uses Temurin 25.
  - Local JDK 26 is present at `~/.jdks/openjdk-26.0.1` — prefix Gradle calls with `JAVA_HOME=~/.jdks/openjdk-26.0.1`.
- **Node + pnpm** for dashboard/installer/website (`pnpm build` runs `sdk:check` first).
- **External Mongo + Redis/Valkey** for the harness suite (Tier 2A). Point the harness at them with `PREXOR_TEST_MONGO_URI` / `PREXOR_TEST_REDIS_URI` (or `-Dprexor.test.mongoUri` / `-Dprexor.test.redisUri`). Without them the harness tests `assumeTrue`-skip — green but proving nothing.
- **Docker** for the Testcontainers-based module harness (`ModuleTestHarness`).
- All Gradle commands below run from `java/`. Go commands from `cli/`. pnpm commands from the named frontend dir.

---

## 1. Tier 1 — Automated gates (run these first, they must all be green)

These prove the code is correct, formatted, contract-stable, accessible (static), and i18n-complete. They are the floor: if any is red, nothing downstream is trustworthy. Each maps to a CI job — running them locally just front-runs CI.

### 1.1 Java — build, test, coverage, format

```bash
# from java/  (CI job: "Java Build, Test & Coverage")
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew spotlessCheck build
```

Covers: all unit + in-process integration tests that don't need external infra, palantir-java-format, JaCoCo coverage. This is the bulk of Tracks A–H's *code* correctness (Raft state machine, reloaders, telemetry spans, module resource/quota/health, registry client, backup schedule, network router, etc. — every `…Test` cited in the plan).

### 1.2 Contract drift (proto + startup config)

```bash
# from java/  (CI job: "Contract Drift Checks")
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-protocol:test :cloud-daemon:test \
  --tests me.prexorjustin.prexorcloud.protocol.contract.ProtoContractDriftTest \
  --tests me.prexorjustin.prexorcloud.daemon.contract.StartupContractDriftTest
# from cli/
go test ./internal/setup -run TestStartupContractSnapshot
```

Proves the additive proto changes from D.3 (`traceparent`) and F.1 (`ConfigFormat.GEYSER`) match `contracts/proto-contracts.sha256` with no `PROTOCOL_VERSION` bump.

### 1.3 CLI (Go) — scaffolder, module upgrade, registry

```bash
# from cli/
go test ./...
```

Covers C.1 (`module_upgrade_test.go`, `module_registry_install_test.go`) and C.4 scaffolder (`TestPruneExtensions*`, `TestStripOnRegisterRoutes`, `TestGenerate*`).

### 1.4 Dashboard — i18n, a11y (static), unit, build, authed-axe hard gate

```bash
# from dashboard/  (CI job: "Dashboard Lint, Test & Build")
pnpm install --frozen-lockfile
pnpm i18n:check                 # locale parity en↔de (hard)
pnpm i18n:check-hardcoded       # 0 hardcoded user-facing strings (hard) — H.3
pnpm a11y:check                 # img alt + icon-control names (hard) — H.2 static
pnpm test:coverage              # vitest (store tests for cluster/modules/players/audit/system/trace)
VITE_DEV_MOCK=1 pnpm build      # sdk:check + nuxt build with dev-mock baked in
```

**Authed-flow axe — the hard a11y gate (E-P1.1 / v1.2 success criterion "Lighthouse A11y ≥ 90"):**

```bash
# after the VITE_DEV_MOCK=1 build above, from dashboard/
pnpm preview &                  # serves on :3000
AXE_DIR=$(mktemp -d)
( cd "$AXE_DIR" && npm init -y && npm i @axe-core/playwright playwright && npx playwright install --with-deps chromium )
cp scripts/axe-authed.mjs "$AXE_DIR/"
( cd "$AXE_DIR" && BASE_URL=http://127.0.0.1:3000 node axe-authed.mjs )
```

Must report **0 serious/critical across all 16 authed routes**. This is already a hard CI gate; the local run reproduces it.

### 1.5 Installer wizard

```bash
# from installer/  (CI job: "Installer Wizard …")
pnpm install --frozen-lockfile
pnpm format:check
pnpm a11y:check                 # hard gate (E-P2)
pnpm typecheck
pnpm test
pnpm build
```

### 1.6 Website (E.4 theme wiring + docs build)

```bash
# from website/  (CI workflow: website.yml)
pnpm install --frozen-lockfile
pnpm build                      # runs prebuild theme generators (starlight + mermaid) under a freshness guard
pnpm exec astro check
```

The freshness guards fail if `starlight-theme.generated.css` / the mermaid palette drift from `design-system/dist/tokens.json` — that *is* the E.4 verification.

### 1.7 Design-system parity, contrast, drift

```bash
# from design-system/  (CI job: "design-system")
node build-tokens.mjs           # regenerate dist/
git diff --exit-code dist/      # dist/ must be fresh (no diff)
node --test __tests__/          # parity + contrast.test.mjs (both themes) + surface-drift + components.test.mjs
```

Closes the *static* half of E.1 and the H.2 contrast audit (`contrast.test.mjs`, both themes green).

> **Tier 1 exit criterion:** every command above is green. That confirms all *code-complete* plan items are correct. It does **not** confirm the runtime-bound items in Tier 2.

---

## 2. Tier 2 — Infra/runtime lab (the real "mark DONE" blockers)

Each subsection is one open plan item. You need to stand up the named infra, run the steps, and record the result in §4. These are the items the plan flags as "Laufzeit-/Infra-Verifikation offen".

### 2A. Harness suite against real Mongo + Redis (Tracks A-HA, C, H + DR)

Bring up Mongo + Redis/Valkey, export the URIs, then:

```bash
export PREXOR_TEST_MONGO_URI=mongodb://127.0.0.1:27017
export PREXOR_TEST_REDIS_URI=redis://127.0.0.1:6379
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:test               # RecoveryTest, PlatformModuleLifecycleTest, AuditTest …
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:nightlyScenariosTest
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:drDrill            # @Tag("dr") — backup → wipe → restore
```

What this proves:
- **HA failover** (`RecoveryTest.startWithRedisHa(1,2)` cases) — two concurrent controllers. **Depends on the issue #3 fix** (PR #12): before it, both controllers bound Raft 9190 and shared `data/raft`, so these would hang in `awaitLeader()`. Land #12 first.
- **DR drill** (Track H/C.5) — `cloud-test-harness:drDrill`, mirrored by the nightly `dr-drill` job.
- **Single-controller recovery** across restart (state recovered from Mongo).

### 2B. Scheduler-tick p99 @ 100 groups < 50 ms (H.1)

The benchmark exists (`PerformanceBaselineTest.measureSchedulerTick`) and the timer `prexorcloud.scheduler.tick.duration` publishes p50/p95/**p99**. It **records**, it does not assert a threshold — so this is a measured manual gate.

```bash
export PREXOR_TEST_MONGO_URI=mongodb://127.0.0.1:27017
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:perfBaselines -Dperf.scheduler.groups=100
# then inspect:
jq '.schedulerTick' java/cloud-test-harness/build/reports/perf-baselines/baseline-report.json
./scripts/perf-baseline-check.sh java/cloud-test-harness/build/reports/perf-baselines/baseline-report.json
```

**Pass:** `schedulerTick.p99 < 50` (ms) at 100 groups. (Default `perf.scheduler.groups` is 1000 — override to 100 to match the plan's target.) Record the number in §4.

### 2C. 3-node Raft cluster — kill leader, reelection < 5 s (v1.1 success gate)

In-process spike coverage:

```bash
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-controller:spikeTest   # RatisMultiPeerSpikeTest, EndToEndJoinTest, ClusterControlServiceJoinTest
```

For the literal v1.1 gate ("3-node cluster, killing the leader → reelection < 5 s"), also do a **manual 3-node deploy**: follow [`runbooks/upgrade-v1.0-to-v1.1.md`](../runbooks/upgrade-v1.0-to-v1.1.md) to seed + join 3 controllers, `kill -9` the leader, and time `prexorctl cluster status` until a new leader appears. Confirm against [`runbooks/recover-cluster.md`](../runbooks/recover-cluster.md) for the majority-loss path.

### 2D. F.1 — Bedrock routing end-to-end (real Geyser + Bedrock client)

Code-complete (edition detection, `bedrockLobbyGroup`/`bedrockFallbackGroups`, Geyser sidecar, daemon provisioning, dashboard field). **Open: a real Bedrock client lands in the Bedrock lobby.**

1. Build the Geyser extension: `./gradlew :cloud-plugins:proxy:geyser:shadowJar`.
2. Provision a `GEYSER` platform group with `bedrockProxyGroup` set to a running Velocity/Bungee proxy group (dashboard CreateGroup → Orchestration step, or REST).
3. Configure a `NetworkComposition` with `bedrockLobbyGroup` + `bedrockFallbackGroups`.
4. Connect with **Minecraft Bedrock Edition** to the Geyser listener.
5. **Assert:** the Bedrock player lands in `bedrockLobbyGroup` (not the Java lobby); dashboard shows the player as `bedrock` edition; kill the lobby instance and confirm failover walks `bedrockFallbackGroups`.

Known follow-ups (Tier 3): reactive re-resolution when the fronted proxy moves; the real Geyser download URL lives in the operator catalog, not the repo.

### 2E. F.2 — Fabric server mod on a real server

```bash
./gradlew :cloud-plugins:server:fabric:remapJar   # → build/libs/*.jar (~4.2 MB, deps shaded, remapped)
```

Drop the remapped jar into a **Fabric 1.21.1 dedicated server**'s `mods/`, start it. **Assert:** the mod registers with the controller (appears as a node/instance) and reports player join/leave + a metrics snapshot every ~10 s (200 ticks). Confirm no slf4j/logback binding hijack in the server log.

### 2F. F.3 — NeoForge server mod on a real server

```bash
./gradlew :cloud-plugins:server:neoforge:shadowJar   # → build/libs/PrexorCloudNeoForge.jar (~3.2 MB; no remap needed)
```

Drop into a **NeoForge 21.1.233 (MC 1.21.1)** server's `mods/`, start it. **Assert:** same as F.2 — registration + metrics. The NeoForge jar already excludes logback (the leak F.2 still has); confirm clean logging.

### 2G. H.2 — axe/Lighthouse ≥ 95 hard gate over authed flows

Today: authed-flow axe is a hard gate at **0 serious/critical** (§1.4). **Open: raise the bar to Lighthouse-A11y ≥ 95** as a hard CI gate over authed flows, which needs a **real backend + a CI test-login** (the dev-mock build proves the markup; a ≥95 score wants the real authed app). Steps to close:

1. Stand up the controller in CI (or a seeded test backend) with a known admin credential.
2. Drive login in `axe-authed.mjs` / a Lighthouse-CI run against the authed routes.
3. Add the ≥95 threshold as a hard gate in `ci.yml`.

Until that infra exists, this stays open; the 0-serious/critical axe gate is the current honest bar (and satisfies the v1.2 "≥90" criterion).

---

## 3. Tier 3 — Not closeable by a test (needs a decision or more work)

These will **never** go green from a CI run. Each needs an explicit call recorded in §4.

| Item | Plan ref | Why no test closes it | How to close it honestly |
|------|----------|----------------------|--------------------------|
| **Track I — docs/README rewrite** | §9b (I.1–I.4) | It's unstarted *implementation* work (a whole v1.4 milestone), not verification. Only I.0 style-spec shipped. | Do the work, or explicitly scope v1.3-DONE to exclude v1.4. The plan already sequences I strictly last. |
| **Perf-trend over 60 days** | H.1 | Needs 60 days of production telemetry. | Mark "deferred — requires production data"; it's an ops-ongoing review, not a release gate. |
| **Registry hosting** (`registry.prexorcloud.dev`) | C.1 | Backend + CLI + UI shipped; hosting is an ops decision (GitHub Pages vs server). | Decide host, publish the static index, point a config at it. ADR 31 already covers the design. |
| **F.1(b) reactive re-resolution** | F.1 | Explicit follow-up; v1 resolves at provision-time. | Descope from v1.3 or schedule as a follow-up. |
| **F.2 logback `SLF4JServiceProvider` leak** | F.3 note | Build-exclude follow-up on the Fabric jar. | One-line build exclude (mirror NeoForge); then re-run §2E. |
| **D.1 gRPC auto-instrumentation** | D.1 | Deliberately out of scope (needs the OTel javaagent; trace context already rides the payload via D.3). | Leave out-of-scope; note in sign-off. |
| **C.2 stage-3 hard isolation** | C.2 | Optional, process-isolation; default stays in-process. | Descope (plan marks it optional). |
| **C.5 bidirectional Discord / `--no-mongo` strip** | C.5 / C.4 | Explicit follow-ups. | Descope or schedule. |

---

## 4. Sign-off matrix (the single source of truth)

Fill `Result` as you go. The plan is **DONE** when every row is `PASS`, `DEFERRED (decision logged)`, or `DESCOPED (decision logged)` — and Track I is either done or explicitly carved into v1.4.

| # | Item | Track | Tier | How verified | Result |
|---|------|-------|------|--------------|--------|
| 1 | `spotlessCheck build` green | A–H code | 1 | §1.1 | ✅ PASS (2026-06-09) — BUILD SUCCESSFUL |
| 2 | Contract drift (proto + startup) | D.3/F.1 | 1 | §1.2 | ✅ PASS (2026-06-09) — via build + `go test` |
| 3 | CLI `go test ./...` | C.1/C.4 | 1 | §1.3 | ✅ PASS (2026-06-09) — 355 tests, 11 pkgs |
| 4 | Dashboard i18n + static a11y + unit + build | E/H.2/H.3 | 1 | §1.4 | ✅ PASS (2026-06-09) — 1163 unit tests; see note |
| 5 | Authed-flow axe — 0 serious/critical (hard) | E-P1.1 | 1 | §1.4 | ✅ PASS (2026-06-09) — 32/32 route×theme, 0 serious/critical |
| 6 | Installer gates | E-P2 | 1 | §1.5 | ✅ PASS (2026-06-09) |
| 7 | Website build + theme freshness | E.4 | 1 | §1.6 | ✅ PASS (2026-06-09) — 286 pages, astro check clean |
| 8 | Design-system parity + contrast + drift | E.1/H.2 | 1 | §1.7 | ✅ PASS (2026-06-09) — 16 tests, dist fresh |

> **Tier-1 run 2026-06-09 (branch `plan/finish-and-docs`):** all 8 automated-gate rows green. The run caught one real branch-red — two dashboard *page-test* mocks (`cluster-players`, `modules-index`) had drifted behind shipped store surface (F.1 `editionCounts`; C.3 health + C.1 registry-catalog). Fixed in commit `d540ecc`; full suite back to 1163 green. Rows 9–20 (Tier 2 lab + Tier 3 decisions) remain open and are not runnable from a dev box.
| 9 | Harness suite vs real Mongo+Redis (incl. HA failover) | A/C | 2A | §2A | ☐ |
| 10 | DR drill | C.5/H | 2A | §2A | ☐ |
| 11 | Scheduler p99 @100 groups < 50 ms | H.1 | 2B | §2B | ☐ (record p99) |
| 12 | 3-node Raft reelection < 5 s | A | 2C | §2C | ☐ |
| 13 | F.1 Bedrock client → Bedrock lobby | F.1 | 2D | §2D | ☐ |
| 14 | F.2 Fabric mod on real 1.21.1 server | F.2 | 2E | §2E | ☐ |
| 15 | F.3 NeoForge mod on real server | F.3 | 2F | §2F | ☐ |
| 16 | axe/Lighthouse ≥95 authed hard gate | H.2 | 2G | §2G | ☐ |
| 17 | Track I docs rewrite | I | 3 | do, or carve to v1.4 | ☐ |
| 18 | Perf-trend 60 d | H.1 | 3 | defer (ops) | ☐ |
| 19 | Registry hosting | C.1 | 3 | host + decide | ☐ |
| 20 | F.1(b) / F.2-logback / D.1 / C.2-s3 / C.5-bidi | F/D/C | 3 | descope/schedule | ☐ |

### Recommended order

1. **Tier 1 (§1)** — fastest, and a red here invalidates everything else. ~1 session.
2. **Land issue-#3 fix (PR #12)** — unblocks the HA rows (#9) by removing the Raft port/dir collision.
3. **Tier 2A/2B/2C (§2A–C)** — stand up Mongo+Redis once, then harness + perf + spike all run off it. ~1 lab session.
4. **Tier 2D–2F (§2D–F)** — the MC-server lab (Geyser+Bedrock client, Fabric, NeoForge). This is the heaviest setup; budget a dedicated session with the three server types installed.
5. **Tier 2G (§2G)** — wire the CI test-login backend, then the ≥95 gate.
6. **Tier 3 (§3)** — make the calls, log each as a one-line decision (ADR or plan note).

When rows 1–16 are PASS and 17–20 carry a logged decision, update `northstar-plan.md` §⏱️ to **DONE** and tag the release.
