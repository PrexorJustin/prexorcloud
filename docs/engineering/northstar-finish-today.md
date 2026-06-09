# Northstar — finish today

**What this is:** the short, ordered list of everything *you* still have to do to mark the
[northstar plan](./northstar-plan.md) **100% DONE**. Everything that a machine can verify is
already green — I ran the full Tier-1 gate suite on 2026-06-09 and recorded it in the
[sign-off matrix](./northstar-verification.md#4-sign-off-matrix-the-single-source-of-truth)
(rows 1–8 PASS). What's left is **infra/runtime verification** (you stand up the lab) and a
handful of **decisions** (you make the call). This guide is the today-oriented cut of
[`northstar-verification.md`](./northstar-verification.md) §2–§3 — go there for the full detail.

Work it top to bottom. Each step says what to run, what "pass" looks like, and where to record it.

---

## Before you start — the lab

You need these once; most steps reuse them.

- **JDK to run Gradle:** `~/.jdks/openjdk-26.0.1` is present. Prefix Gradle with
  `JAVA_HOME=~/.jdks/openjdk-26.0.1`. (Spotless needs JDK 25 specifically, but that's a Tier-1
  gate already passed — not needed here.)
- **Mongo + Redis/Valkey** reachable locally. Quickest:
  ```bash
  docker run -d --name prexor-mongo -p 27017:27017 mongo:7
  docker run -d --name prexor-valkey -p 6379:6379 valkey/valkey:8
  export PREXOR_TEST_MONGO_URI=mongodb://127.0.0.1:27017
  export PREXOR_TEST_REDIS_URI=redis://127.0.0.1:6379
  ```
  Without these the harness tests `assumeTrue`-skip — green but proving nothing.
- **Docker** running (for the Testcontainers module harness).
- **For the MC-server lab (steps A4–A6):** a Fabric 1.21.1 dedicated server, a NeoForge
  21.1.233 (MC 1.21.1) server, a standalone Geyser + a Velocity proxy, and a **Minecraft
  Bedrock Edition** client. This is the heaviest setup — budget a dedicated block for it.
- All Gradle commands run from `java/`.

---

## Part A — the infra lab (the real blockers)

### A0. Land the issue-#3 fix (PR #12) FIRST

The HA-failover harness cases (`RecoveryTest.startWithRedisHa`) hang until #12 lands — before it,
two in-process controllers bind the same Raft port (9190) and share `data/raft`. Merge PR #12,
then continue. If it's already merged, skip.

### A1. Harness suite vs real Mongo + Redis  → matrix rows 9, 10

With the env vars exported above:

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:test
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:nightlyScenariosTest
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:drDrill   # @Tag("dr")
```

**Pass:** all green. This proves HA failover (two concurrent controllers), single-controller
recovery across restart, and the DR drill (backup → wipe → restore). Record rows 9 (harness/HA)
and 10 (DR drill).

### A2. Scheduler tick p99 @ 100 groups < 50 ms  → matrix row 11

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-test-harness:perfBaselines -Dperf.scheduler.groups=100
jq '.schedulerTick' cloud-test-harness/build/reports/perf-baselines/baseline-report.json
```

**Pass:** `schedulerTick.p99 < 50` (ms) at 100 groups. The benchmark records, it doesn't assert
the threshold — read the number and **write the p99 into row 11**. (Default `perf.scheduler.groups`
is 1000; the `-Dperf.scheduler.groups=100` override matches the plan's target.)

### A3. 3-node Raft — kill leader, reelection < 5 s  → matrix row 12

In-process coverage first:

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-controller:spikeTest
```

Then the literal v1.1 gate, a **manual 3-node deploy**: follow
[`runbooks/upgrade-v1.0-to-v1.1.md`](../runbooks/upgrade-v1.0-to-v1.1.md) to seed + join three
controllers, `kill -9` the leader, and time `prexorctl cluster status` until a new leader appears.

**Pass:** new leader inside 5 s. Cross-check the majority-loss path against
[`runbooks/recover-cluster.md`](../runbooks/recover-cluster.md). Record row 12.

### A4. Bedrock client → Bedrock lobby (F.1)  → matrix row 13

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-plugins:proxy:geyser:shadowJar
```

1. Provision a `GEYSER` platform Group with `bedrockProxyGroup` set to a **running** Velocity/Bungee
   proxy Group (dashboard CreateGroup → Orchestration step, or REST).
2. Configure a `NetworkComposition` with `bedrockLobbyGroup` + `bedrockFallbackGroups`.
3. Connect with **Minecraft Bedrock Edition** to the Geyser listener.

**Pass:** the Bedrock player lands in `bedrockLobbyGroup` (not the Java lobby); the dashboard shows
them as `bedrock` edition; kill the lobby instance and confirm failover walks
`bedrockFallbackGroups`. Full walkthrough: [Bedrock with Geyser](../public/en/guides/bedrock-with-geyser.md).
Record row 13.

### A5. Fabric server mod on a real server (F.2)  → matrix row 14

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-plugins:server:fabric:remapJar   # → build/libs/*.jar (~4.2 MB)
```

Drop the remapped jar into a **Fabric 1.21.1 dedicated server**'s `mods/`, start it.

**Pass:** the mod registers with the controller (appears as a node/instance) and reports player
join/leave + a metrics snapshot every ~10 s (200 ticks). Confirm no slf4j/logback binding hijack in
the server log. Record row 14.

> Known follow-up (Tier-3 B5): the Fabric jar still bundles a logback `SLF4JServiceProvider` the
> NeoForge jar already excludes. One-line build exclude clears it.

### A6. NeoForge server mod on a real server (F.3)  → matrix row 15

```bash
cd java
JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew :cloud-plugins:server:neoforge:shadowJar   # → PrexorCloudNeoForge.jar (~3.2 MB)
```

Drop into a **NeoForge 21.1.233 (MC 1.21.1)** server's `mods/`, start it.

**Pass:** same as A5 — registration + metrics every ~10 s; clean logging (this jar already excludes
logback). Record row 15.

### A7. axe/Lighthouse ≥ 95 over authed flows  → matrix row 16

Today the authed-flow axe gate is **0 serious/critical** and already hard (Tier-1 row 5, verified).
This row raises the bar to a **Lighthouse-A11y ≥ 95** hard gate, which needs a real backend + a CI
test-login (the dev-mock build proves the markup; a ≥95 score wants the real authed app):

1. Stand up the controller in CI (or a seeded test backend) with a known admin credential.
2. Drive login in a Lighthouse-CI run against the authed routes.
3. Add the ≥95 threshold as a hard gate in `.github/workflows/ci.yml`.

**Pass:** CI fails below 95. Until that infra exists this stays open; the 0-serious/critical gate is
the honest current bar and already satisfies the v1.2 "≥90" criterion. Record row 16 (or mark
DEFERRED with that rationale).

---

## Part B — the decisions (no test closes these)  → matrix rows 17–20

Make each call and log it as one line (an ADR note or a plan note). The plan is DONE when each is
PASS, DEFERRED, or DESCOPED **with the decision written down**.

| # | Decision | Recommended call | How to close |
|---|----------|------------------|--------------|
| 17 | **Track I docs** | Done bar screenshots/GIFs (Part C below). | Capture the shots (I generated dashboard ones — see Part C), or carve the GIFs to a v1.4.1 follow-up. |
| 18 | **Perf-trend over 60 days** | DEFER — it's an ops review, not a release gate. | Plan note: "deferred — requires 60 d production telemetry." |
| 19 | **Registry hosting** (`registry.prexorcloud.dev`) | Publish the static index to GitHub Pages (ADR 31 already covers the design). | Decide host, publish the index JSON, point a config at it. |
| 20a | **F.1(b) reactive re-resolution** | DESCOPE from v1.3 (v1 resolves at provision time). | Plan note or scheduled follow-up. |
| 20b | **F.2 Fabric logback exclude** | DO IT — one-line build exclude mirroring NeoForge, then re-run A5. | `java/cloud-plugins/server/fabric/build.gradle.kts`. |
| 20c | **D.1 gRPC auto-instrumentation** | Leave out-of-scope (needs the OTel javaagent; trace context already rides the payload via D.3). | Note in sign-off. |
| 20d | **C.2 stage-3 hard isolation** | DESCOPE (plan marks it optional; default stays in-process). | Note in sign-off. |
| 20e | **C.5 bidirectional Discord / `--no-mongo` strip** | DESCOPE or schedule. | Note in sign-off. |

---

## Part C — flip the plan to DONE

1. Fill every row of the [sign-off matrix](./northstar-verification.md#4-sign-off-matrix-the-single-source-of-truth)
   with `PASS`, `DEFERRED (decision logged)`, or `DESCOPED (decision logged)`.
2. **Screenshots/GIFs (I.4 visual):** dashboard screenshots are captured under
   `dashboard/docs/screenshots/` (dark theme, dev-mock data). Review them, drop the best into the
   dashboard README / docs, and (optional) record an installer GIF the same way. That closes the
   last I.4 item.
3. Update [`northstar-plan.md`](./northstar-plan.md) §⏱️ header to **DONE**.
4. Tag the release.

When rows 1–16 are PASS and 17–20 carry a logged decision, the plan is honestly **100% DONE**.
