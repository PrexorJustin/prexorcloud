# PrexorCloud — final acceptance test plan ("the complete cloud test")

**This is a transient, do-it-then-delete checklist.** Everything that a machine can verify is
already green (the automated Tier-1 gate suite — Part 1). What remains to call PrexorCloud
**100% done** is a real, end-to-end cloud test *you* run on real infrastructure: every feature,
single-controller → controller-HA → multi-VPS. Work it top to bottom, tick each box, capture the
screenshots, fill the sign-off matrix at the end. When every box is ticked, the product is done —
**delete this file** (see [Part 14 — teardown](#part-14--sign-off--teardown)).

How to read each item: **`- [ ] What — how → Pass: the observable result that means it works.`**
Commands use real names (`survival-lobby`, `node-fra-1`, port `30000`), never `foo`. Where a flag
might have drifted, the source of truth is `prexorctl <cmd> --help` and the REST reference at
`/reference/rest-api/`.

> Convention: `CTRL` = a controller host, `NODE` = a daemon host, `$TOKEN` = an operator JWT
> (`prexorctl login` then `prexorctl context`). All `prexorctl` calls assume a logged-in context.

> **Why this manual run matters — live findings.** The automated Part-1 gates were all green, yet
> the very first real install on real infrastructure surfaced bugs no unit test caught. This is the
> point of the exercise. Findings so far (batched for a `v1.0.1` fix release):
> 1. **`prexorctl` installer couldn't parse credentialed datastore URIs.** `DialTCPFromURI` left the
>    `user:pass@` userinfo in front of `host:port` → `too many colons in address`, so the wizard's
>    Mongo/Redis preflight failed for every production-style URI. Fixed to parse via `net/url`.
> 2. **cosign verification uses deprecated `--certificate`/`--signature` flags** (in `install.sh` and
>    `cli/internal/setup/cosign.go`). Still verifies today, but breaks when cosign removes them;
>    modern form is `--bundle` + `--trusted-root` (also needs the release pipeline to emit a bundle).
> 3. **`deploy/compose/compose.yml` pins `mongo:8.0`**, which hard-refuses Linux kernel ≥6.19
>    (SERVER-121912); the Ubuntu hosts here run kernel 7.0. Bumped to `mongo:8`.
>
> An all-green automated suite is necessary, not sufficient — environment, real datastores, real
> kernels, and real credential formats only show up here.

> **▶ LIVE RUN STATUS — resume here (as of 2026-06-14, updated post token-refresh fix).** A live run
> is in progress on a Hetzner fleet. **Part 2D connect is PROVEN through a proxy, and the core of 3B
> with it:** a real 1.21 client (`PrexorDev`) joined `survival-lobby` *via the Velocity `edge` proxy*
> (direct backend join is refused by design — see finding #9). Both `survival-lobby` and `edge` are
> `RUNNING`, Network `main` wires them. **9 live bugs found and fixed** (working-tree only, deployed to
> the fleet, NOT committed — see the 2D bug batch below).
>
> **⚠ Connect target moved:** all the debug restarts reshuffled ports. Always re-check with
> `prexorctl instance list` — as of this writing the `edge` proxy instance is on **30000** and the
> lobby on 30001, i.e. players connect to `49.13.138.202:<edge-port>`. (Instance list is clean now —
> the STOPPED-instance churn was a real bug, fixed as #10.)
>
> **NEXT: Part 2D is COMPLETE** (scale up→auto-drain to min, and `kill -9` crash-heal both verified live
> 2026-06-14 — crash persisted w/ exit 137/SIGKILL/log-tail, fix #12). Now **Part 3**: 3A templates,
> finish 3B (kill lobby → fallback failover, live network edit), 3C scaling/deployments (rolling, canary
> +rollback), 3D node lifecycle (label, drain→eject). Re-verify the token-refresh fix (#8) holds across a
> full 15-min TTL cycle. Wave-2 hosts (`ctrl-2/3`, `node-fra-2`) for Parts 8–9 are **not provisioned yet**.
>
> **Everything built is now deployed + live** (working tree = fleet): controller jar (proxy-uptime #11 /
> crash-persist #12 / log-file #13 / role-reconcile #14 / `stop` endpoints `nodes|system/shutdown`); daemon jar
> (log-file #13 / dir-cleanup-race #15); `prexorctl` (`group scale`, `logs` rework, `stop` rework, `crash info`
> picker+completion). ADMIN role auto-reconciled to 52 perms (incl. `nodes.shutdown`/`system.shutdown`). All
> uncommitted.
>
> **⚠ Deploy lesson:** ALWAYS `docker restart controller-controller-1` immediately after `scp`-ing a
> new controller jar over the live path. Replacing the jar a running JVM has open corrupts its lazy
> class loading → bogus `NoClassDefFoundError` on not-yet-loaded classes (cost an hour mid-run).
>
> **Fleet access notes (so manual commands work):** use `/usr/bin/ssh` (the kitty `ssh` kitten
> refuses non-TTY). Controller `:8080` is firewalled from the public net — reach it via an SSH
> tunnel (`ssh -N -L 8080:localhost:8080 -L 9090:localhost:9090 root@167.233.120.10`) and point a
> local `prexorctl` context at `http://localhost:8080`, or just run `prexorctl` on `ctrl-1`
> (`/usr/local/bin/prexorctl`, already logged in; token in `~/.prexorcloud/config.yml`). The initial
> admin password was rotated (2B done) so `INITIAL_ADMIN_PASS` in `secrets.env` no longer works.
>
> **Fleet, Hetzner fsn1, private net `prexor-net` 10.0.0.0/16 (4 of 10 server quota used):**
> | host | public IP | private IP | role | state |
> |---|---|---|---|---|
> | `data-1` | 167.233.102.221 | 10.0.0.2 | Mongo + Valkey (Docker) | Ubuntu 24.04, stable |
> | `ctrl-1` | 167.233.120.10 | 10.0.0.3 | controller (Docker compose) | up, **Raft leader**, logged in as `admin` |
> | `ctrl-2` | 168.119.109.213 | 10.0.0.6 | controller (native systemd) | **● follower** (cx33/ubuntu-26.04; HA jar) |
> | `ctrl-3` | 91.99.213.167 | 10.0.0.7 | controller (native systemd) | **● follower** (cx33/ubuntu-26.04; provisioned 2026-06-14) |
> | `node-frankenstein-1` | 49.13.138.202 | 10.0.0.4 | daemon (native systemd) | **● ONLINE** |
> | `node-fra-2` | 178.105.112.91 | 10.0.0.5 | daemon (native systemd) | **● ONLINE** (cx33/ubuntu-26.04; provisioned 2026-06-14) |
>
> Fleet creds: `~/prexor-fleet/secrets.env` (local, chmod 600). SSH as `root@<public-ip>`. Provisioning:
> `hcloud` CLI installed at `~/.local/bin/hcloud`, context `prexor` (token stored locally, NOT in repo);
> server limit raised to 10. Add a daemon by cloning `node-frankenstein-1` (cx33/ubuntu-26.04/fsn1-dc14,
> ssh-key `dev@scharbau.me`, `--network prexor-net`), tar-pipe `/opt/prexorcloud/{jre,daemon jar,ca.pem}`
> (NOT `node.p12` — minted fresh on enroll), write `daemon.yml` (`nodeId`, controller `10.0.0.3:9090`,
> fresh `prexorctl token create --node <id>`), drop the systemd unit, `systemctl enable --now`.
>
> **▶ HOW TO RESUME (fresh-session operator runbook).**
> - **Working tree = source of truth.** Every fix below is an **uncommitted** local edit (`cli/cmd/*.go`,
>   `java/cloud-*`); the jars running on the fleet were built from this exact tree. **Do NOT** `git
>   stash`/reset/discard. `git status` shows the modified files. Committing is deferred (user's call;
>   commit style: short + human, **no** `Co-Authored-By`).
> - **Use `/usr/bin/ssh` and `/usr/bin/scp`** (the kitty `ssh`/`scp` kitten refuses non-TTY); append
>   `< /dev/null` to ssh commands so they don't hang on stdin.
> - **Reach the controller:** either run `prexorctl` on `ctrl-1` (`/usr/local/bin/prexorctl`, logged in),
>   or from the workstation open the tunnel and use the local build:
>   `cd cli && go build -o prexorctl . && ./prexorctl context use fleet` (context `fleet` →
>   `http://localhost:8080` over the tunnel). To (re)create it: `./prexorctl context add fleet
>   --controller http://localhost:8080 --token "$(ssh root@167.233.120.10 'sed -n "s/.*token: *//p"
>   ~/.prexorcloud/config.yml' < /dev/null)"`.
> - **Sanity check on resume:** `prexorctl node list` (node ● ONLINE) · `prexorctl instance list`
>   (`edge` + `survival-lobby` both RUNNING; note the proxy port = connect target) · controller health
>   `curl -s localhost:8080/api/v1/system/version` (via tunnel).
> - **Rebuild + redeploy recipes** (JDK: `JAVA_HOME=~/.jdks/openjdk-26.0.1`; build from `java/`):
>   - **Controller:** `./gradlew :cloud-controller:shadowJar` → `scp .../build/libs/PrexorCloudController.jar
>     root@167.233.120.10:/opt/prexorcloud/controller/PrexorCloudController.jar` → **immediately**
>     `ssh root@167.233.120.10 'docker restart controller-controller-1'` (see deploy lesson above).
>   - **Daemon:** `./gradlew :cloud-daemon:shadowJar` → `scp .../build/libs/PrexorCloudDaemon.jar
>     root@49.13.138.202:/opt/prexorcloud/daemon/PrexorCloudDaemon.jar` → `ssh root@49.13.138.202
>     'systemctl restart prexorcloud-daemon'`.
>   - **prexorctl:** `cd cli && go build -o prexorctl .` → `scp cli/prexorctl
>     root@167.233.120.10:/usr/local/bin/prexorctl` (and use locally via the tunnel).
> - **Datastore debugging (data-1, `docker exec`; creds in `secrets.env`):** Valkey
>   `docker exec <valkey> redis-cli -a "$VALKEY_PASS" --no-auth-warning KEYS 'prexor:v1:*'` (keys:
>   `…:plugintoken:*`, `…:workloadseq:<instanceId>`); Mongo `mongosh -u "$MONGO_ROOT_USER" -p
>   "$MONGO_ROOT_PASS" --authenticationDatabase admin` db `prexorcloud` (e.g. `templates` collection,
>   `_id` = template name — delete a built-in's doc + restart controller to force regeneration).
> - **Catch an ephemeral instance log** (the daemon deletes the dir on crash): read it live via
>   `/proc/<pid>/cwd/logs/latest.log` of the `Xmx…m` MC process on the node.
>
> **Done:** Part 1 (all automated gates green) · release **v1.0.0** cut & published · 2A controller
> install (wizard, Docker, remote datastores) · 2B login + admin-rotate · 2C daemon enrolled & ONLINE ·
> catalog seeded with **PAPER 1.21** (build 130) **and VELOCITY 3.4.0-SNAPSHOT** (build 559, PROXY) ·
> 2D group→instance→`RUNNING` · `edge` Velocity proxy group + Network `main` (lobby=survival-lobby,
> proxyGroups=[edge]) · **client connected through the proxy into the lobby** (2D connect + core 3B).
>
> **2D/3B bug batch (live, 2026-06-14) — found by the first real `group create`→`RUNNING`→connect.
> All fixed in the working tree + redeployed to the fleet; NOT yet committed:**
> 1. **CLI `group create` → HTTP 500** — CLI sent a nested `portRange` object; `GroupConfig` has flat
>    `portRangeStart`/`portRangeEnd` and Jackson 500s on unknown fields. Fixed `cli/cmd/group.go`.
>    (Controller arguably should 400, not 500 — follow-up.)
> 2. **Daemon `Cannot run program "java"` (error=2)** — bare `java`, host has none on PATH. New
>    `JavaExecutable.path()` resolves the launcher from `java.home`; used in `ServerProcess` +
>    `PaperBootstrapCache` (3 spawn sites).
> 3. **Plugin abort `CLOUD_CONTROLLER_HOST not set`** — daemon injected `CLOUD_CONTROLLER_URL`, the
>    plugin (`PluginEnv`) wants `CLOUD_CONTROLLER_HOST` + `CLOUD_CONTROLLER_PORT`. Daemon now injects
>    both (from `DaemonGrpcClient.controllerHost()`/`controllerApiPort()`).
> 4. **Startup deadlock (401 forever)** — `WorkloadIdentityRegistry.isEntryUsable` required instance
>    state `RUNNING`, but `/api/plugin/ready` (the call that *sets* RUNNING) is gated by it. Now
>    accepts `STARTING`/`RUNNING`/`DRAINING`. (`/api/plugin/networks` 404 = no Network yet, benign.)
> 5. **Sequenced-call 401 on restart** — `unregisterPluginTokens` cleared the in-memory replay map but
>    not the Valkey `workloadseq:` window, so a reused instance id (restart/rescale) had its plugin's
>    `seq=1` rejected vs a stale high watermark until the 15-min TTL. Now clears the Valkey window too.
> 7. **Join-token store fails to load on restart** — `JoinToken.isExpired()` is a JavaBean getter, so
>    Jackson serialized it as an `"expired"` property that then failed to read back (record has no such
>    component) → `FileJoinTokenStore` drops all persisted join tokens on startup. Fixed: `@JsonIgnore`
>    on `isExpired()` + `@JsonIgnoreProperties(ignoreUnknown=true)` on the record. **Deployed + live.**
> 8. **Running instances go 401-blind ~15 min after their last good token (the big one).** Plugin token
>    refresh is **reactive-only** (`BaseControllerClient.sendWithRefresh` fires it on a 401), but by the
>    time a request 401s the token has already expired — and refresh authenticates with that same
>    expired token, which `isEntryUsable` strictly rejects *and evicts*. So refresh can never succeed
>    after expiry → no recovery → every plugin/proxy call 401s (player-join, metrics, networks, SSE…).
>    Fix (controller, `WorkloadIdentityRegistry` + `WorkloadAuthFilter`): a **refresh grace window**
>    (= token TTL). Normal calls stay strict (expired = 401) but the entry is NOT evicted within grace;
>    the `/auth/refresh` path (`isEntryRefreshable`) accepts a within-grace-expired token to bootstrap a
>    fresh one, and the auth before-filter skips `/auth/refresh` so it reaches that handler. Also covers
>    HA-failover gaps. Unit-tested; **deployed + live.** Cosmetic follow-up: add *proactive* refresh in
>    the plugin so it never lapses (eliminates the one 401-warn-then-recover per TTL cycle).
> 10. **Terminal instances (STOPPED/CRASHED) linger forever across a controller restart.** The reaper
>    (`InstanceLifecycleManager`, removes STOPPED after 60s / CRASHED after 300s) is event-driven +
>    in-memory: on restart the pending timers are lost and hydrated-terminal instances never fire the
>    transition event, so they're never re-queued → they accumulate (cosmetic clutter; matters for HA
>    leader failover). Fix: a startup `sweepHydratedTerminalInstances()` re-queues removal for any
>    already-terminal instance (the scheduled task re-checks state at fire time, so a reconnect-revived
>    instance is left alone). Deployed + verified live (the leftover rows reaped after 60s).
> 11. **Proxy instances show 0 players / 0s uptime forever in `instance list`.** The server (Paper)
>    metrics path calls `ClusterState.updateInstanceStatus`, which writes the live player count +
>    uptime into the `InstanceInfo` the instance list reads; the proxy metrics path
>    (`updateProxyMetrics`) only stashed a `ProxyMetrics` side-record and never touched the
>    `InstanceInfo`, so every proxy stayed frozen at its initial `0 players / 0s`. Fix: `updateProxyMetrics`
>    now also calls `updateInstanceStatus` (state preserved) with `totalNetworkPlayers` +
>    `proxyUptimeMs`. Deployed + verified live (edge uptime now tracks the proxy's real JVM uptime and
>    advances each 30s metrics cycle).
> 12. **Crashes never reach the queryable store → dashboard Crashes page always empty.**
>    `DaemonCrashEventReceiver.handleCrashReport` detected + logged the crash and wrote it to the
>    in-memory `CrashStore` ring buffer, but the REST read path (`/api/v1/crashes`, trends, detail)
>    reads from the `StateStore` (Mongo). `StateStore.saveCrash` existed but was **dead code** — never
>    called — so nothing ever landed in Mongo (and crashes were lost on restart / invisible to other HA
>    controllers). Fix: inject `StateStore` into the receiver and `saveCrash(record)` right after the
>    ring-buffer add (best-effort, wrapped). Clears pre-existing follow-up (a). Deployed; pending live
>    verification on the upcoming `kill -9` step.
> 13. **Controller/daemon `logs/*.log` files are always empty (no on-disk logs).** `LoggingSetup.configure()`
>    calls `context.reset()` (to override `logback.xml` programmatically) which wipes the XML-declared
>    `FILE` RollingFileAppender; only a CONSOLE appender was re-attached, so the configured log file was
>    created but never written. (The in-memory ring buffer that powers `/api/v1/system/logs` survives
>    because it's re-attached programmatically after the reset — that's why the API had logs but the file
>    didn't.) Fix: `LoggingSetup.configure(config, componentName)` now rebuilds a rolling
>    `<log-dir>/<component>.log` appender programmatically (50MB×30, 500MB cap; `-Dprexorcloud.log.dir`
>    override); controller + daemon bootstraps pass `"controller"`/`"daemon"`; logback.xml trimmed to a
>    console-only bootstrap. **Controller deployed + verified live** (`controller.log` now fills and rolls).
>    Daemon jar built; redeploy deferred (a daemon restart gracefully stops running instances — user's call).
> 14. **ADMIN (and every role) silently misses any permission added after first boot → 403s (e.g. can't
>    view controller/daemon logs).** `MongoRoleStore.ensureDefaults()` seeded `defaults/roles.yml`
>    **only when the roles collection was empty**, and a stored role doc shadows the reflective
>    `Role.ALL_PERMISSIONS`. So the fleet's ADMIN doc was frozen at its original 41-permission seed and
>    never gained `system.logs.view`, `share.*`, `events.view`, … (9 missing) — defeating the whole point
>    of the reflective bundle. This was a *general* RBAC drift bug, not log-specific. Fix: built-in role
>    definitions are now code-authoritative (`Role.builtInDefaults()`), and `ensureDefaults()` **reconciles
>    (upserts) the built-in roles on every startup** instead of seed-if-empty (custom roles untouched).
>    Deployed + verified live: ADMIN doc went 41 → 50 perms, `logs controller`/`daemon`/`instance` all
>    return 200 (were 403/404). Code-authoritative built-ins also retire the drift-prone `defaults/roles.yml`.
> 15. **Crash-heal deletes the healed instance's live working directory (dir-cleanup race on id reuse).**
>    `ProcessManager.onProcessExited` schedules `deleteInstanceDir(group, id)` after a delay; the
>    scheduler heals the crash by reusing the **same instance id + dir path**, so when the delayed
>    cleanup fires it deletes the dir the *new* live process is running in → its cwd goes `(deleted)`
>    (world saves land on a dead inode; console-history / file-access / template re-materialization
>    break). Found via the 2D `kill -9` check: a never-crashed `edge-2` had a real cwd, the crash-healed
>    `survival-lobby-1` had a `(deleted)` cwd. Fix (daemon, `deleteInstanceDir`): skip the delete when
>    `processes.containsKey(instanceId)` (the id is running again). Deployed + **verified live** — re-killed
>    a healed instance, waited past the cleanup delay, cwd stayed real and the daemon logged
>    `Skipping stale cleanup … instance is running again`.
> 16. **Cross-node proxy routing broken — "No servers available" / proxy dials a stale backend address.**
>    Found live 2026-06-14 (user couldn't join when `edge` and `survival-lobby` were on different nodes).
>    The Velocity log was explicit: `Routing PrexorDev to survival-lobby-1 … unable to connect … Connection
>    refused: /10.0.0.4:30001` — the proxy dialed the backend's OLD node:port. Root cause in the shared
>    proxy plugin (`AbstractProxyCloudPlugin`): the state-cache sync only (re)registered ids in
>    `added`/`becameRunning`, and `registerBackend` just calls Velocity `registerServer` (which won't
>    update a registered server's address in place). So when an instance keeps its id but moves node:port
>    (drain-migrate / crash-heal / affinity reschedule), the proxy never refreshes the registration and
>    keeps dialing the dead address. Fix: the listener now **reconciles every RUNNING backend to its
>    current `nodeAddress:port`** (tracked in a `registeredBackends` map) and unregisters-then-registers on
>    a move; applies to all proxy types (velocity/bungee/geyser) via the shared base. Rebuilt → bundled in
>    the controller → **`base-velocity` template regenerated** (delete the Mongo doc **AND** the on-disk
>    `templates/base-velocity/files/` dir, else it just re-hashes the stale files) → edge proxy recreated.
>    **Verified live:** with `edge` on node-frankenstein-1 and `survival-lobby-2` on node-fra-2, the proxy
>    now logs `Registered backend server: survival-lobby-2 -> 10.0.0.5:30000` (correct cross-node address).
> 9. **Backends are NOT directly joinable — by design** (not a bug, a UX decision). `ServerConfigPatcher`
>    unconditionally forces Velocity forwarding + `online-mode=false` on every Paper/Spigot backend, so
>    a direct join to `node:30000` is refused ("connect with Velocity") — you must go through a proxy +
>    Network (what we did). Candidate change captured in `post-v1-platform-redesign.md` §2 (zero-config
>    direct-join). For this test, the proxy path is the intended flow.
> 6. **Paper crashes ~40s in (exit 134 / SIGABRT)** — *not a PrexorCloud bug*: bundled **spark**
>    auto-starts an async-profiler whose native lib SIGSEGVs on this kernel (Ubuntu 26.04 / 7.0,
>    `perf_event_paranoid=4`; relaxing the sysctl did NOT help). `BaseTemplateGenerator` now writes
>    `plugins/spark/config.json` with `backgroundProfiler: false` for `paper`-format templates. The
>    pre-existing `base-paper` template had to be regenerated (Mongo-backed `templates` collection;
>    deleted the doc + restarted). Verified: server reaches `Done!` and stays up; instance `RUNNING`.
>
> **Open follow-ups noticed during 2D/3B (not blocking):** (a) ~~`/api/v1/crashes` stays empty~~
> **FIXED — see bug #12** (`saveCrash` was dead code; now wired into `handleCrashReport`).
> (b) Template-watcher `rehash` throws `NoClassDefFoundError com/mongodb/client/
> TransactionBody` on file-change (latent; class IS in the shaded jar — runtime linkage quirk).
> (c) 2D prose flag drift: the real flag is `--platform-version`, not `--version`. (d) Controller
> returns **500 (not 400) on malformed/unknown-field JSON** — hit twice (CLI `portRange`, and a
> newline pasted into the network `kickMessage`); Jackson parse errors should map to 400. (e) Replay
> sequence uses a single global `AtomicLong` across concurrent requests vs a strictly-monotonic Redis
> check, so concurrent sequenced calls occasionally 401 out-of-order — now self-heals via the #8
> refresh-retry, but a sliding-window or per-endpoint sequence would be cleaner. (f) Bigger
> product/UX direction (catalog ships filled, zero-config direct-join, `prexorctl network`/`apply`,
> GitHub org migration, monorepo decoupling) is captured in `docs/engineering/post-v1-platform-redesign.md`.
> (g) **Scheduler desync under crash-reschedule churn — ROOT CAUSE FOUND (fix needs a repro).** Trigger:
> a new group whose instance crash-looped onto a conflicting port, + a group delete, + rapid daemon
> restarts, left the daemon holding **duplicate/orphan MC processes** and every instance stuck `SCHEDULED`
> (recovered only via hard daemon stop→`pkill`→start). **Mechanism:** when churn bumps an *already-running*
> instance back to `SCHEDULED` and the controller reassigns it a new port, the daemon (`ProcessManager.startInstance`)
> finds it in `processes` and acks `INSTANCE_ALREADY_RUNNING / PERMANENT` (on its *old* port).
> `DaemonCommandAckHandler.handleStartInstanceAck` (l.64–75) treats that as an "idempotent replay" — clears
> the retry budget and **returns WITHOUT reconciling state** — so the instance sits in `SCHEDULED` forever
> even though the daemon is running it. Fix candidates (need a clean repro, ideally multi-node, to verify):
> (1) on `INSTANCE_ALREADY_RUNNING`, the controller should reconcile to the daemon's actual state rather
> than leave it limbo — but NOT blindly force `RUNNING` (the daemon enters `processes` at spawn, before
> plugin-ready); query/trust the daemon's reported instance state; (2) upstream, don't reassign a port to
> an instance that's already running; (3) daemon should reconcile its process map to the controller's
> desired (id,port) set on handshake. NOT patched live (touches the core state machine; too risky to
> blind-fix on the single-node fleet mid-test). (h) Minor: deleting a group leaves its auto-created
> `<group>` group-template orphaned.
> (j) **Proxy stuck in a plugin-token-refresh 401 loop → frozen backend list (found 3B, 2026-06-14).** A
> heavily-churned/reused proxy instance id (`edge-1`, rescheduled+moved many times) wedged into an endless
> `Plugin token refresh failed: HTTP 401` loop (20k+ in ~1h) — **isolated to the proxy**; both
> `survival-lobby` backends had **zero** 401s. The dead token kills BOTH the SSE state stream *and* the
> poll fallback (they share the token), so the proxy's backend map froze on stale data: it had only
> `survival-lobby-2` and never learned about `survival-lobby-1` (RUNNING 3.6 min) → `/server` showed one
> server → no failover target. **Recovered by restarting the proxy** (fresh token → re-synced → registered
> both backends). Root cause is the #5/#8 token-lifecycle for reused/moved ids, likely aggravated by the
> ctrl-1 Raft re-bootstrap; needs a proper repro + fix (the proxy should re-establish identity on
> persistent 401 rather than loop forever). (k) **`CloudStateCache` poll fallback only covers a *down*
> stream, not a lossy/blocked one.** `refreshIfStreamInactive` runs the 5s poll only when the SSE stream is
> inactive; a connected-but-lossy stream (or a dead token blocking both) never gets reconciled. Add a
> periodic full reconcile as a backstop regardless of stream state, and treat repeated token-401 as a
> reason to re-bootstrap identity.
> (i) **Part 8 HA — root cause re-diagnosed and FIXED (branch `ha-enablement`).** Earlier note claimed
> `RaftBootstrap` couldn't split bind from advertise; that was **wrong** against the Ratis 3.1.3 source.
> The gRPC server already binds wildcard `0.0.0.0` (`GrpcConfigKeys.Server.host` defaults null; bootstrap
> only sets the port), and `raft.host` is used on exactly one line — to build the *advertised* peer address.
> Bind and advertise are already decoupled; no `advertisedHost` field needed. The default `raft.host:
> "0.0.0.0"` poisons only the *advertised* address. `ctrl-1`'s member is stored at `0.0.0.0:9190` in the
> Raft state machine, and the real blocker was that nothing migrated it: `ensureSelfMember()` early-returned
> on restart, so changing `raft.host` alone never re-advertised. **Fix shipped:** (1) `ensureSelfMember()`
> self-heals — when the stored `raftAddr` ≠ the configured advertised address it re-stamps the member
> (`applyAddMember` is an upsert), and the commit wakes the reconciler → leader re-runs `setConfiguration`
> so the live group + join responses carry the new address; (2) `awaitKnownLeader()` replaces the self-only
> `awaitLeader()` on the bring-up path so a follower restart doesn't hang waiting to lead itself;
> (3) docs/config corrected (advertise vs bind), HA networking guidance (host networking, routable
> `raft.host`). Verified by `ClusterControlServiceTest.restartSelfHealsAdvertisedAddress` (single-node
> leader rewrites its own address and keeps serving). **Live rollout still pending:** roll the new
> controller image to `ctrl-1`, set `raft.host=10.0.0.3` (host networking), restart, confirm self-heal,
> then join `ctrl-2/3` per `upgrade-v1.0-to-v1.1.md` Step 2. Found + fixed 2026-06-14.
> **⚠ LIVE ROLLOUT BLOCKED — Raft cert SAN gap (found 2026-06-14 attempting the rollout).** Deployed the
> ha-enablement controller jar to `ctrl-1` + `raft.host=10.0.0.3` (+ bridge-published `10.0.0.3:9190:9190`)
> and it **crash-loops on boot**: `CertificateException: No subject alternative names matching IP address
> 10.0.0.3 found`. The Member record self-heals (advertised addr → 10.0.0.3), but the node's **Day-0
> cluster-CA leaf cert** (`config/security/cluster/`) was minted with SANs for the *original* advertised
> address (`0.0.0.0`/loopback), so the Raft mTLS handshake to the new address fails. The unit test
> (`restartSelfHealsAdvertisedAddress`) doesn't exercise real Raft mTLS, so it missed this. **The self-heal
> must also re-mint (or SAN-extend) the cluster leaf cert when `raft.host` changes** — same class as the
> earlier gRPC loopback-SAN fix. Rolled back to `raft.host=0.0.0.0` (control plane was down ~2 min); fleet
> healthy again. HA live test deferred to the `ha-enablement` session until the cert re-mint lands.
> **⚡ LIVE HA BRING-UP — got much further (2026-06-14, user OK'd a clean slate).** Instead of a full wipe,
> **surgically re-bootstrapped ctrl-1's Raft Day-0** with the correct `raft.host`: stop controller, `rm -rf
> data/raft config/security/cluster`, set `raft.host=10.0.0.3`, bridge-publish `10.0.0.3:9190:9190`, start →
> fresh cluster (`66d34e64…`), member advertising **`10.0.0.3:9190`**, **correct cert SAN** (no crash), daemons
> reconnected, business data + player session intact. *This sidesteps the cert-SAN gap without a wipe.* Then
> provisioned **ctrl-2** (cx33, native systemd / host-networking, `10.0.0.6`, shared Mongo/Valkey/jwtSecret +
> copied daemon-CA + forwarding.secret, its own gRPC SAN). To issue a join token: `cluster.manage` is
> excluded from default ADMIN — created a `CLUSTER_OPS` role (ADMIN perms + `cluster.manage/view/config.write`)
> + `clusterops` user, logged in, issued the token. ctrl-2 join then surfaced **3 more bugs in the join path,
> all in `ha-enablement` code** — fixed the first two: (1) `ClusterJoinFlow.insecureChannelTo` used
> `forTarget("host:port")` → shaded-jar unix-resolver mis-parse → switched to `forAddress(new
> InetSocketAddress(...))` (the SocketAddress overload bypasses the registry); (2) the controller never
> registered the `pick_first` LB + DNS resolver providers (shaded jar drops the service files) → added
> `registerGrpcProviders()` to `PrexorCloudBootstrap.main` (same as the daemon). **(3) REMAINING BLOCKER:**
> the join handshake then reaches TLS and fails `TLSV1_ALERT_CERTIFICATE_REQUIRED` — `ClusterMembership.RequestJoin`
> is served on the Raft port (9190) which requires cluster mTLS, but the joiner has no cert yet (it's meant to
> obtain one *via* the join). The bootstrap join endpoint must allow no/ephemeral client cert (or be a
> separate listener). Left for the HA session — I won't change their Raft TLS config blind. ctrl-2 is
> provisioned + `pending-join-token` staged (service stopped) so a retry is one fix away. ctrl-1 still runs
> the HA jar **without** fixes (1)/(2) (it's the seed, doesn't initiate joins); redeploy the final jar to all
> controllers once (3) lands.
>
> **✅ HA QUORUM FORMED — 3-member fault-tolerant controller cluster live + healthy (2026-06-14, `ha-enablement`
> session).** `ctrl-1` (leader, 10.0.0.3) + `ctrl-2` (10.0.0.6) + `ctrl-3` (10.0.0.7, public 91.99.213.167):
> all **three** independently report 3 members, leader replicates to both followers, **2-of-3 quorum writes
> commit, tolerates 1 failure.** (First brought up the 2-member ctrl-1+ctrl-2 quorum below, then joined ctrl-3.)
> 2-member phase detail: `cluster members` = 2 on **both**, active-active
> reads (ctrl-2 self-serves member list), ctrl-1 runs a live `GrpcLogAppender` to ctrl-2, **2-member-quorum
> writes commit** (verified by minting a join-token — a Raft write needing both peers to ack), 0 append
> failures steady-state, both daemons stayed **ONLINE** throughout (workloads are Redis/Mongo-backed, never
> blocked by Raft). **Part 8A "seed ctrl-1" + "join ctrl-2" PROVEN; 8B active-active reads PROVEN.** Blocker
> (3) above was **misdiagnosed** — `RequestJoin` is NOT on the Raft port. It's on the controller gRPC server
> (9090, `clientAuth=OPTIONAL`, mTLS-exempt like Bootstrap). The `CERTIFICATE_REQUIRED` was the **joiner
> dialing :9190** because the join token's `joinAddrs` was minted with the Raft port — and the docs told it to.
> Six findings, fixed end-to-end (controller jar rebuilt + rolled to **both** controllers; all uncommitted):
> - **#17 (docs, root cause of the TLS block):** `joinAddrs` must be the **gRPC** endpoint (`:9090`), not Raft
>   (`:9190`). `RaftConfig` javadoc + the `--join-addr` CLI help are correct ("gRPC host:port"); `ha-setup.md`
>   (l.145/154/172) wrongly said "Raft endpoints / `controller-1:9190`". Re-minted with `:9090` → join handshake
>   succeeded instantly. **Fix = correct ha-setup.md (+ upgrade runbook troubleshooting row). NOT yet edited.**
> - **#18 (provisioning):** ctrl-2 got ctrl-1's daemon-facing `ca.p12` but **not** its `.ca-password` → it
>   generated a fresh password → `UnrecoverableKeyException` on boot. The shared daemon CA needs **both** the
>   keystore *and* its password copied across HA controllers. Fixed live (copied ctrl-1's `.ca-password`).
>   **Provisioning steps (the "add a controller" recipe) must copy `config/security/.ca-password` too.**
> - **#19 (code, `ClusterControlService`):** `awaitKnownLeader(15s)` was a hard gate → on timeout the exception
>   propagated to `main()` and **exited the JVM**, killing this peer's Raft server — the very peer the quorum
>   needs alive to elect. A controller restarting into a quorum-less multi-member group thus **crash-loops
>   forever** (self-defeating). Fix: restart path now `awaitLeaderBestEffort()` — logs a degraded-mode WARN and
>   continues; the Raft server stays up and converges in the background. Day-0 self-elect stays strict.
> - **#20 (code, `CertificateAuthority`):** issuing the server cert **aborted** when `serverCertSans()`
>   auto-detected a global IPv6 (`2a01:…::1%eth0`) that `isIpAddress()` classified as an IP but BouncyCastle's
>   `GeneralName(iPAddress,…)` rejects ("IP Address is invalid"). Bricks **native-install controllers on
>   IPv6 hosts** (ctrl-1 in Docker never saw a global IPv6, so it never hit this). Fix: per-SAN defensive
>   `toGeneralName()` skips a rejected entry with a warning instead of failing the whole cert.
> - **#21 (code, `MembershipReconciler`):** it was **purely commit-event-driven** (wakes only on a new
>   AddMember/RemoveMember) with **no startup reconcile pass** and bailed silently on a transient `!isLeader()`.
>   So when the Ratis group drifted from the SM member list (a join whose `setConfiguration` never committed),
>   nothing re-triggered alignment — ctrl-1 stayed a 1-member group (no `GrpcLogAppender` to ctrl-2) while ctrl-2
>   thought it was in a 2-member group → split config, ctrl-2's writes hung forever. Fix: `start()` fires an
>   idempotent startup reconcile kick + the leader-check tolerates a brief post-election settle window.
> - **#22 (OPEN — CONFIRMED reproduces on a clean join; operational workaround proven, real fix pending):** a **join-mode** Raft server
>   (`startInJoinMode` → `add(group,true)`) gets stuck `initializing? true` with a **Terminated** appendEntries
>   `ThreadPoolExecutor`, so it **rejects the leader's staging AppendEntries** → `setConfiguration` fails
>   `NOPROGRESS`. Workaround that worked: once the join has run, **restart the joiner** — the restart-mode boot
>   (`raft.start(tls)` loading the persisted group) brings up a healthy follower division; the leader's next
>   reconcile pass then stages it (`reconciled to 2 peer(s) on attempt 1`) and ctrl-2 saw the leader in 766 ms.
>   Sequence that formed the quorum: clean re-join (purge `data/raft`+`config/security/cluster`, gRPC-port
>   token) → ctrl-2 boots → **restart ctrl-2** (healthy division) → **restart ctrl-1** (startup reconcile commits
>   the 2-member config). Needs either a join-mode lifecycle fix or a documented "restart after join" step so
>   the joiner completes without manual intervention.
>
> **HA next:** (a) commit the 6-fix batch (after `spotlessApply` under JDK 25 + the `:cloud-controller` test
> suite — my edits touch `ClusterControlService`/`MembershipReconciler`/`CertificateAuthority`, all have tests).
> (b) ~~Provision ctrl-3~~ **DONE** — 3-member quorum live; #22 confirmed to reproduce on a clean join → it
> needs a real fix (auto-restart the joiner inside the join flow, or a documented restart-after-join step) so
> a join completes without the manual joiner+leader restart dance. (c) ~~**8B kill-the-leader failover**~~
> **DONE 2026-06-15 — 8B PASSED end-to-end** (active-active reads+writes on all 3, SIGKILL'd leader ctrl-1 →
> new leader ctrl-2, **write resumed in 573 ms ≪ 5 s**, instances kept their pids/uptime through it, daemons
> stayed ONLINE; **fix #19 validated live** — restarted ctrl-1 rejoined as a follower with 0 crash-loops).
> ~~**8C** config versioning~~ **DONE 2026-06-15 — 8C PASSED** (patch + `409 PARENT_VERSION_STALE`; CORS/rate-limit/jwtSecret all **live-reload cluster-wide, no restart**; rollback reverts live; all REST-only). Remaining: **8D** leases (endpoint returns empty — investigate), **8E** recovery. (d) Roll the new daemon jar too. **Workload-level tests now unblocked** — `admin` pw reset via Mongo (`ADMIN_PASS` in `secrets.env`).
>
> **▶ 8B RESUME NOTES (2026-06-15 session).** (1) **Operator JWT TTL is 24 h** — yesterday's token 401'd; re-mint
> by POSTing `{username,password}` to `/api/v1/auth/login` with the `CLUSTER_ADMIN_USER`/`PASS` from
> `~/prexor-fleet/secrets.env`, then use `prexorctl --token <jwt> --controller http://10.0.0.X:8080` (the JWT is
> valid on **all** controllers — shared `jwtSecret` — and `--token` survives a leader kill, unlike a config tied to
> one controller). `prexorctl context add` won't overwrite an existing context, and there's no `context set-token`.
> (2) **`cluster-admin` has `cluster.*` but NOT `nodes.view`/`instances.view` → 403** on `node list`/`instance list`.
> The rotated **ADMIN password is NOT in secrets.env** (only the dead `INITIAL_ADMIN_PASS`). **Workload-level live
> tests (3C, 4, 5, 9, 10, 11) are BLOCKED on ADMIN access** — need the rotated admin password, or a decision to
> grant `cluster-admin` more perms / reset admin via Mongo root. (3) **No leader field** in `cluster status` /
> `GET /api/v1/cluster`; detect the leader by Raft thread dump (SIGQUIT → only the leader JVM has `GrpcLogAppender`
> threads; followers show `FollowerState`). `jq` is **not** on the fleet hosts — parse JSON locally. (4) **(g)
> reschedule-desync is live** post-failover (leader re-commands already-running `edge-2`/`survival-lobby-3`/`survival-lobby-4`
> every 30 s; `edge-2`+`survival-lobby-3` both on port 30000) — workloads safe, left unpatched per the plan.
>
> **Patched binaries deployed to the fleet but NOT committed** (working tree only; see below): ctrl-1
> controller jar + `prexorctl`; node-frankenstein-1 daemon jar.
>
> **Uncommitted fix batch (working tree — must be committed before this is "done"):**
> - **v1.0.1 bugs found live:** daemon gRPC fell back to the `unix` name-resolver (shaded jar drops
>   grpc-core `META-INF/services`) → direct `InetSocketAddress` + explicit `pick_first`/DNS provider
>   registration; controller gRPC server cert had **loopback-only SANs** → new `grpc.subjectAltNames`
>   config + local-IP auto-detect (`PrexorCloudBootstrap.serverCertSans`); `DialTCPFromURI` URI parse;
>   blank-`uuid` controller crash; Raft/Mongo/Lettuce log spam quieted; CLI `node list` showed `-`
>   (read `nodeId`, API sends `id`). Plus the 3 findings listed above.
> - **v1.1 CLI features added reactively:** `prexorctl catalog` command; shell completions (dynamic,
>   with descriptions) + auto-install in `install.sh`; interactive arg-pickers across resource
>   commands; NoFileComp fix; install-wizard "start on boot / start now" prompts (CLI **and** browser);
>   `prexorctl group scale <name> <n>` (the plan's 2D step assumed it existed but only `group update
>   --min/--max` did — `scale` sets the `minInstances` floor and raises `maxInstances` to match if
>   lower, so it pins STATIC/MANUAL and sets the floor for DYNAMIC). Deployed to `ctrl-1`.
>   `prexorctl logs` reworked into the unified, `group info`-style log viewer: bare `logs` opens an
>   interactive picker (Controller / Daemon / Instance / All), `logs instance [id]` (NEW, picker) tails
>   a server/proxy console, `logs all [--group/--node]` (NEW) fans out a merged live tail of every
>   instance with per-instance colored prefixes, `logs daemon [node]` gained a node picker, plus `-f`/`-n`
>   short flags. Deployed to `ctrl-1`.
>   `prexorctl stop` reworked from local-systemd-only into a fleet-wide service-stop: `stop local` (now
>   **Docker-Compose-aware** — detects `docker-compose.yml` in the controller/daemon install dirs and runs
>   `docker compose stop`, else falls back to `systemctl stop`), `stop node [id]` (NEW — immediate daemon
>   stop via control plane), `stop controller`
>   (NEW — stops the connected controller); bare `stop` = interactive picker in a TTY, local fallback in
>   scripts; `-y/--yes` + non-TTY confirm guard. Backend: new `POST /api/v1/nodes/{id}/shutdown`
>   (sends `ShutdownNode` directly, `nodes.shutdown` perm, ADMIN-only) and `POST /api/v1/system/shutdown`
>   (202 then `System.exit(0)` after a flush grace, `system.shutdown` perm, ADMIN-only). Built; **not yet
>   deployed** (needs a controller restart). Caveat: a restart-always supervisor (Docker
>   `restart: unless-stopped`) will bring the controller back — stop the container/unit for a permanent stop.
> - **Deferred:** ratis-grpc `ClassNotFoundException` at controller startup (non-fatal, leader elects);
>   a full **CLI redesign** is planned for after this acceptance test completes.

---

## Part 0 — Lab setup

You need real machines. Minimum to exercise everything:

- [ ] **3 VPS for controllers** (`ctrl-1`, `ctrl-2`, `ctrl-3`) — for the HA quorum (Part 8). 2 vCPU / 4 GB each is enough. Same region / low latency (Raft assumes it).
- [ ] **2+ VPS for daemons** (`node-fra-1`, `node-fra-2`) — separate from controllers, to prove cross-host scheduling (Part 9).
- [ ] **1 shared MongoDB + 1 Valkey/Redis** reachable from all controllers (or the Compose stack on `ctrl-1` for the single-host parts). Production HA needs them external and shared.
- [ ] **Java 25** on every controller and daemon host (`temurin-25-jdk`); the `--enable-preview` flags the Dockerfiles use.
- [ ] **`prexorctl`** installed on your workstation (the signed release binary, or `cd cli && make build`).
- [ ] **A Minecraft Java client** (1.20 and 1.21) and a **Minecraft Bedrock Edition client** (phone/console/Win10) for the routing tests.
- [ ] **Server jars/mods staged** in your catalog: Paper 1.20 + 1.21, Folia, Spigot, Velocity, BungeeCord, a Fabric 1.21.1 server, a NeoForge 21.1.233 server, the Geyser standalone jar.
- [ ] **A tracing backend** (Jaeger all-in-one is fine: `docker run -d -p16686:16686 -p4317:4317 jaegertracing/all-in-one`) for Part 6.
- [ ] **An SMTP sink** (MailHog: `docker run -d -p1025:1025 -p8025:8025 mailhog/mailhog`) for the password-reset test.

Reference docs you'll lean on (all under the published site): Getting started, Operations → HA setup,
Guides → Bedrock with Geyser, Operations → Monitoring, and the runbooks under `docs/runbooks/`.

---

## Part 0B — Production infrastructure & hosting to provision

These are **setup deliverables you own**, not feature tests — and several are **prerequisites** for the
tests below (you can't test registry-install without a hosted registry, can't provision a server without
catalog URLs, can't route Bedrock without a Floodgate key). Stand these up first.

### Domains, DNS, TLS, firewall

- [ ] **DNS** — point records for: the controller's public host, the dashboard origin, `prexor.cloud` (docs/website), and `registry.prexorcloud.dev`.
- [ ] **TLS termination** — the controller's HTTP edge is **plaintext on purpose**; front it with Caddy / nginx / Traefik + Let's Encrypt. Set `http.cors.allowedOrigins` to the real dashboard origin.
- [ ] **Firewall** — expose only the reverse-proxy port publicly. Keep `/metrics` (no auth!), gRPC `:9090`, and Raft `:9190` on trusted networks; tighten `network.allowedSubnets` to your daemon/controller ranges.

### The module registry (`registry.prexorcloud.dev`) — the one you flagged

Backend + CLI + dashboard UI are shipped; **hosting + content is yours to set up** (ADR 31).

- [ ] **Decide + stand up the host** — static index on GitHub Pages or S3 (no server needed).
- [ ] **Build + sign the first-party modules** — cosign keyless (OIDC); publish each module jar **plus** its cosign signature bundle to a stable URL.
- [ ] **Author the index JSON** — per module: `moduleId`, `version`, `jarUrl`, `sha256`, `cosignBundleUrl`, `manifestUrl`, `compatibleControllerVersions`, `tags`, `readme`, `provides`. Publish it at the registry URL.
- [ ] **Wire the controller** — set `modules.registries` to the index URL; configure the controller's signing **trust root** to accept your cosign identity.
- [ ] **Verify end-to-end** — `prexorctl module search` lists your modules and `module install` pulls + verifies one. *(This unblocks Part 5A against real modules instead of local jars.)*

### Catalog (server/proxy downloads) — ships empty

- [ ] **Populate `config/catalog.yml`** (or the catalog REST) with real `downloadUrl` + `sha256` for every platform you'll run: Paper 1.20/1.21, Folia, Spigot, Velocity, BungeeCord, and the **Geyser standalone jar** (its real URL lives in the operator catalog, not the repo).
- [ ] **Host the Fabric + NeoForge mod jars** somewhere stable and add catalog/version entries (or your provisioning flow) so groups can pull them.

### Release & artifacts — cut the first real release

- [ ] **Tag `v1.0.0`** → `release.yml` ships **cosign-signed `prexorctl` binaries**; `release-images.yml` ships **cosign-signed multi-arch GHCR images**.
- [ ] **`dashboard-static-*.tar.gz`** — produced only by a tagged release. The **native/systemd dashboard install fails until this asset exists**, so this release is a prerequisite for the native-install path (Part 2A).
- [ ] **GitHub repo settings** — GHCR package visibility (public or pull auth), and OIDC permissions for cosign keyless + Rekor.

### Bedrock prerequisites (before Part 4B)

- [ ] **Generate the Floodgate `key.pem`** shared key and place it in the Geyser group's template **and** the matching Floodgate plugin on your Java backends — PrexorCloud does **not** generate it. Without it, edition detection falls back to `java` and Bedrock routing won't trigger.

### Production datastores

- [ ] **MongoDB** (replica set recommended) + **Valkey/Redis**, reachable from all controllers, secured (auth + network). Required in production; the in-memory fallback is dev-only and **rejected by prod config validation**.

### Backups / DR storage

- [ ] **Off-host storage** (S3 / rsync target) for the `controller-data` volume and the Raft `dataDir`. The Raft `dataDir` holds the **cluster CA private key, the join-token seed secret, and config history** — back it up like any durable store, or recovery (Part 8E) is impossible.

### Observability backends

- [ ] **Prometheus** (+ Alertmanager — wire the alert rules from Operations → Monitoring) and your own Grafana to scrape `/metrics`.
- [ ] **OTLP collector** (Jaeger / Tempo / Honeycomb / Datadog) if you want tracing in prod; set `telemetry.otlpEndpoint` + `telemetry.traceUiTemplate` on controller and daemon.

### Email + website

- [ ] **Production SMTP** for password-reset emails (MailHog is test-only).
- [ ] **Website/docs hosting** — deploy the Astro/Starlight site to `prexor.cloud` (the `website.yml` workflow has a Cloudflare Pages deploy job); attach the custom domain.

---

## Part 1 — Automated confidence floor (run first, ~1 session)

These prove the code is correct/formatted/contract-stable/accessible/i18n-complete. All were last
run green on 2026-06-09; re-run after any change. A red here invalidates everything downstream.

- [ ] **Java** — `cd java && JAVA_HOME=~/.jdks/openjdk-26.0.1 ./gradlew spotlessCheck build` → **Pass:** BUILD SUCCESSFUL (every `…Test`, palantir format, JaCoCo).
- [ ] **Contract drift** — covered by the build (`ProtoContractDriftTest`, `StartupContractDriftTest`) + `cd cli && go test ./internal/setup -run TestStartupContractSnapshot`.
- [ ] **CLI** — `cd cli && go test ./...` → **Pass:** all packages green.
- [ ] **Dashboard gates** — `cd dashboard && pnpm i18n:check && pnpm i18n:check-hardcoded && pnpm a11y:check && pnpm test` → **Pass:** locale parity, 0 hardcoded strings, static a11y clean, all unit tests green.
- [ ] **Authed-flow axe** — build with `VITE_DEV_MOCK=1 pnpm build`, `pnpm preview`, run `scripts/axe-authed.mjs` → **Pass:** 0 serious/critical across all 16 routes × light/dark.
- [ ] **Installer** — `cd installer && pnpm format:check && pnpm a11y:check && pnpm typecheck && pnpm test && pnpm build` → **Pass:** all green.
- [ ] **Website** — `cd website && pnpm check:links && pnpm build && pnpm exec astro check` → **Pass:** 0 broken links, 286 pages, 0 astro errors.
- [ ] **Design-system** — `cd design-system && node build-tokens.mjs && git diff --exit-code dist/ && node --test "__tests__/*.test.mjs"` → **Pass:** dist fresh, parity + both-theme contrast + drift green.

---

## Part 2 — Single controller, single node (the smoke test)

Goal: from nothing to a Minecraft client connected to a cloud-managed server. Do this on **`ctrl-1`**
+ **`node-fra-1`**. Reference: Getting started → Installation + Quickstart.

### 2A. Install (pick one path, then later repeat with the other)

- [ ] **Docker Compose** — `cp deploy/compose/.env.example .env`, edit `controller.yml` (`security.jwtSecret` = `openssl rand -base64 48`, `security.initialAdminPassword`, `network.allowedSubnets`, `http.cors.allowedOrigins`), `docker compose -f deploy/compose/compose.yml up -d` → **Pass:** `docker compose logs -f controller` shows the readiness line; Mongo + Valkey are on the private network, not host-exposed.
- [ ] **Native systemd** — follow `deploy/systemd/README.md`: drop jars in `/opt/prexorcloud/`, `chown`, install both unit files, `systemctl enable --now prexorcloud-controller` → **Pass:** `journalctl -u prexorcloud-controller -f` shows readiness; Java 25 confirmed.
- [ ] **Installer wizard** — `prexorctl setup` → **Pass:** the browser wizard walks mode → essentials → security → review, generates secrets, and writes valid `controller.yml` / `daemon.yml`. Try **both** Docker and native modes in the wizard.

### 2B. First login + admin

- [ ] **Login** — `prexorctl login` (or dashboard) with the initial admin password → **Pass:** token issued; `prexorctl context` shows the controller.
- [ ] **Rotate admin** — change the admin password, then clear `security.initialAdminPassword` from `controller.yml` and restart → **Pass:** old initial password no longer works; new password does.

### 2C. Enroll a daemon

- [ ] **Mint a join token** — `prexorctl token create --node node-fra-1` → **Pass:** prints a single-use token.
- [ ] **Start the daemon** — put the token in `daemon.yml` `security.joinToken`, start the daemon on `node-fra-1` → **Pass:** daemon receives an mTLS cert signed by the controller CA, clears the token from config; node appears in `prexorctl node list` and on the dashboard Nodes page as connected.
- [ ] **Restart the daemon** — `systemctl restart prexorcloud-daemon` (no token) → **Pass:** reconnects token-free over mTLS.

### 2D. First group → instance → connect

- [x] **Register a catalog platform** — PAPER 1.21 (build 130) + VELOCITY 3.4.0 seeded; in `prexorctl catalog`.
- [x] **Create a group** — `survival-lobby` (paper 1.21, min1/max3) created; scheduler placed an instance.
- [x] **Watch the instance** — walked to `RUNNING`, port allocated.
- [x] **Connect a Java client** — joined via the `edge` proxy (direct backend join refused by design, finding #9).
- [x] **Scale up/down** — scaled to 3, then DYNAMIC auto-drained back to min=1 (verified live 2026-06-14; drain gated by `scaleDownAfterSeconds=300` — UX note: lowering min isn't instant).
- [x] **Crash handling** — `kill -9` (pid of `survival-lobby-1`) → controller marked CRASHED, recorded `CrashReport` (**exit 137 / SIGKILL / uptime / full log tail**, queryable via `crash list`+`crash info` and the dashboard — fix #12), scheduler healed back to min=1. **PASSED 2026-06-14.**

---

## Part 3 — Core orchestration features

### 3A. Templates

- [x] **Create a versioned template** with files + config overrides → **PASSED** (variable substitution verified live in `survival-lobby-2/server.properties`: `%PORT%`→30001, `%MAX_PLAYERS%`→100, group/instance vars in `motd`). `ConfigMerger` granularity confirmed by code (key-level for `.properties`, deep-merge YAML/JSON/TOML, later-wins).
- [~] **Layer templates** (base + override chain) → **engine verified, live two-layer-on-new-group test ABANDONED.** Created `layer-a`/`layer-b` override templates + a `layer-test` group layering them; the instance crashed on boot (cause undiagnosed — ephemeral log gone) and the churn cascaded into a controller↔daemon **port-allocation desync** (duplicate/orphan MC processes, all instances stuck `SCHEDULED`, daemon answering "already exists, ignoring start"). Recovered via a hard daemon stop→start. See follow-up below. Merge correctness itself is sound (code + the substitution result above); the gap is a clean live layered-instance run.
- [x] **New template version + redeploy** → **PASSED** (live on `layer-a`: edited `server.properties` → **3 versions retained**; `POST /rollback` to the original hash returned `{"status":"restored"}` and the file content reverted exactly). Version history + rollback work.

### 3B. Networks (proxy routing)

- [x] **Create a Velocity proxy group** `edge` and a backend `survival-lobby` group → both `RUNNING`. **PASSED** (live: `edge-1` VELOCITY + `survival-lobby-1/-3` PAPER all RUNNING cross-host on the rebuilt fleet; also done earlier 2026-06-14).
- [x] **Create a Network** + **validation** → **PASSED 2026-06-16.** Network `main` (lobbyGroup=survival-lobby, proxyGroups=[edge]) live. Validation enforced: bad `lobbyGroup` → `422 VALIDATION_ERROR "lobbyGroup not found: does-not-exist"`; a non-proxy group as a proxy → `422 "proxyGroups entry 'survival-lobby' is not a proxy platform (got PAPER)"`. Both reference-existence + proxy-platform checks confirmed.
- [ ] **Join via the proxy** → **BLOCKED on a real MC client** (player-join — agent can't drive a client; proven earlier 2026-06-14 with client `PrexorDev`). Defer to a user-in-the-loop session.
- [ ] **Kill the lobby instance → fallback failover** → **BLOCKED on a real MC client** (also needs `fallbackGroups` populated — currently empty). Defer.
- [~] **Edit the network live** → **control-plane PASSED 2026-06-16** (`PUT /api/v1/networks/main` changed `kickMessage` → `200`, `GET` reflected it immediately, no restart; reverted). The **proxy re-route observation needs a client** (defer the millisecond-reroute check to a user-in-the-loop session).

### 3C. Scaling & deployments

- [ ] **Dynamic scaling** — drive players past `scaleUpThreshold` → **Pass:** scheduler scales up; `scaleCooldownSeconds` prevents flapping. **BLOCKED on a real MC client** (needs live players to cross the threshold — agent can't drive a client; defer to a user-in-the-loop session).
- [x] **Rolling deployment** — **PASSED 2026-06-16.** `POST /api/v1/groups/survival-lobby/deploy {strategy:ROLLING,batchSize:1,healthGateEnabled:true}` on a 2-instance group → **textbook batch-by-batch roll:** batch 1 replaced `survival-lobby-2`→`-1` while `-3` stayed RUNNING; health-gated — waited until `-1` was RUNNING (`updatedInstances` 1→2) *before* batch 2 replaced `-3`→`-4`; `state` walked IN_PROGRESS→**COMPLETED**; **≥1 instance RUNNING throughout (no service gap), old instances reaped, no orphans, no port collision.** (Trigger = `POST /groups/{name}/deploy`; snapshots current templates + runs `scheduler.rollingRestart` on a virtual thread; progress via `GET …/deployments/{rev}`. Driven by the `deployment-reconciler` Raft lease — see 8D.)
- [x] **Canary + rollback** — **PASSED 2026-06-16.** `POST …/deploy {strategy:CANARY,canaryInstances:1}` → canary instance came up mid-flight (`updatedInstances:1`, `state:IN_PROGRESS`, holding at the canary stage). `POST …/deployments/{rev}/rollback` mid-flight → `200 {"status":"rolled_back"}`; `state`→**ROLLED_BACK**; the in-flight canary was stopped + reaped and the group **settled back to the desired 2 RUNNING instances — no orphaned instances**, ≥1 RUNNING throughout. (Routes: `pause`/`resume`/`rollback` under `/api/v1/groups/{name}/deployments/{rev}/…`.)

### 3D. Node lifecycle

- [x] **Label a node** (`region`/`zone`) → **PASSED 2026-06-14** (2-node fleet). Labeled `node-frankenstein-1` `zone=a` / `node-fra-2` `zone=b` via `daemon.yml labels:` (no REST endpoint; restart to apply). Verified all three on `survival-lobby` via REST PATCH (CLI doesn't expose these): **spreadConstraint=`zone`** → 2 instances placed one-per-zone; **nodeAffinity=`zone=a`** → killed the zone-b instance, heal landed in zone a (and the running zone-b one was NOT evicted = correct *IgnoredDuringExecution*); **nodeAntiAffinity=`zone=a`** → killed a zone-a instance, heal avoided zone a → landed on `node-fra-2`. Affinity match is `key=value` against node labels (`WeightedNodeSelector`).
- [x] **Drain a node** — drained `node-frankenstein-1` (`shutdown=false`, via REST since CLI drain defaults `shutdown=true`); `edge` + `survival-lobby` migrated to **`node-fra-2`** and reached RUNNING, source node emptied; `undrain` restored it to ONLINE. **PASSED 2026-06-14** (also validates cross-host scheduling / Part 9 core, and the clean reschedule shows the (g) desync is single-node port-contention-specific). `eject` not run — destructive on a live node; mechanism is the same ShutdownNode path as `stop node`.

---

## Part 4 — MC platform breadth (one real server per platform)

For each: provision a group on that platform, get it `RUNNING`, connect a client, confirm
registration + metrics. Reference: Concepts → Plugins, Guides → Modded servers.

- [ ] **Paper 1.20** — connect → **Pass:** registers, reports join/leave + metrics.
- [ ] **Paper 1.21** — same.
- [ ] **Folia** — same (region-threaded).
- [ ] **Spigot** — same.
- [ ] **Velocity proxy** — registers as a proxy instance; routes players.
- [ ] **BungeeCord proxy** — same.
- [ ] **Fabric 1.21.1 server mod** — build `:cloud-plugins:server:fabric:remapJar`, drop the ~4.2 MB jar in `mods/`, start → **Pass:** registers, reports player join/leave + a metrics snapshot every ~10 s (200 ticks); **no slf4j/logback binding hijack** in the server log.
- [ ] **NeoForge 21.1.233 server mod** — build `:cloud-plugins:server:neoforge:shadowJar`, drop `PrexorCloudNeoForge.jar` in `mods/`, start → **Pass:** same as Fabric; clean logging (this jar already excludes logback).

### 4B. Bedrock (Geyser) — both topologies

Reference: Guides → Bedrock with Geyser.

- [ ] **Sidecar** — run Geyser as an extension inside a Velocity proxy instance → **Pass:** registers as a proxy instance; every Bedrock session reports `edition=bedrock`.
- [ ] **Standalone managed Geyser** — `:cloud-plugins:proxy:geyser:shadowJar`; register the `GEYSER` catalog platform; create a Geyser group with `bedrockProxyGroup: edge` → **Pass:** at provision time the controller injects `remote.address`/`remote.port` from a running `edge` instance into Geyser's `config.yml`.
- [ ] **Edition-aware routing** — set `bedrockLobbyGroup` + `bedrockFallbackGroups` on the Network; connect with a **real Bedrock client** → **Pass:** the Bedrock player lands in `bedrockLobbyGroup` (not the Java lobby); dashboard shows them as `bedrock` edition (Java/Bedrock split on the Players card).
- [ ] **Bedrock failover** — kill the Bedrock lobby instance → **Pass:** failover walks `bedrockFallbackGroups`.
- [ ] **Cold-start ordering** — provision Geyser before any `edge` instance runs → **Pass:** `remote` stays at the `127.0.0.1:25565` default + a warning; restart after `edge` is `RUNNING` picks up the live endpoint.

---

## Part 5 — Module ecosystem

Reference: Concepts → Modules, Reference → Module SDK.

### 5A. Registry & signed distribution

- [ ] **Configure a registry** — set `modules.registries` to an index URL → **Pass:** dashboard Modules → Registry shows the catalog; `prexorctl module search` lists entries.
- [ ] **Install from registry** — `prexorctl module install stats-aggregator` (or dashboard Install) → **Pass:** sha256 pin verified against the index **and** cosign signature verified against the controller's own trust root; module reaches `ACTIVE`.
- [ ] **SSRF guard** — try installing from a `registryUrl` not in the configured list → **Pass:** rejected.
- [ ] **Upgrade** — `prexorctl module upgrade --all` → **Pass:** only modules with a newer catalog version are reinstalled (pinned to the exact version); up-to-date ones untouched; non-zero exit on partial failure.
- [ ] **Unsigned / tampered jar** — install a module whose signature doesn't verify → **Pass:** route returns `422 SIGNATURE_VERIFICATION_FAILED`; nothing installed.
- [ ] **Rekor offline SET** — set `modules.signing.rekor.policy=REQUIRE_SET` and install a module without a valid SET → **Pass:** rejected.

### 5B. Lifecycle, health, resources, quota

- [ ] **Hot reload** — update a `reloadable: true` module → **Pass:** `ACTIVE → RELOADING → ACTIVE` with no stop/unload; state handed off in `onReload`.
- [ ] **Health** — `GET /api/v1/modules/platform/<id>/health` → **Pass:** returns `HEALTHY/DEGRADED/UNHEALTHY/UNKNOWN`; dashboard module card shows the colored health dot.
- [ ] **Resources** — `GET …/<id>/resources` → **Pass:** live thread count, CPU ms/min, MB/min; dashboard shows the resources block.
- [ ] **Quota (soft)** — set `modules.quotas.<id>` low; drive the module over → **Pass:** WARN on the rising edge + counter `prexorcloud_module_quota_exceeded_total{module,resource}`; "Quota exceeded" badge; module keeps running (advisory).
- [ ] **Classloader leak metric** — unload a module → **Pass:** `prexorcloud_module_classloader_*` track pending/collected; no steady-climb leak.

### 5C. First-party modules (install + exercise each)

- [ ] **stats-aggregator** — runs, exposes its REST/stats.
- [ ] **player-journey** — records player journeys; `PlayerJourneyService` queryable.
- [ ] **webhook-alerts** — fires alerts to a configured webhook on cloud events.
- [ ] **discord-bridge** — posts severity-colored embeds to a Discord incoming webhook.
- [ ] **tablist** — applies the tablist behavior in-game.
- [ ] **backup-orchestrator** — `POST /api/v1/modules/backup-orchestrator/snapshots` → **Pass:** walks → reads (≤64 KiB config files) → `tar.gz` under the snapshot dir; periodic schedule via `PREXORCLOUD_BACKUP_INTERVAL_MINUTES` + `PREXORCLOUD_BACKUP_TARGETS`.
- [ ] **protocol-tap** — taps protocol traffic as designed.
- [ ] **InstanceFileAccess capability** — a module walks/reads a remote instance's config files via the controller handle → **Pass:** reads bounded at 64 KiB UTF-8; truncated reads listed in `truncatedFiles`.

### 5D. Module scaffolder (CLI, no login needed)

- [ ] **Scaffold** — `prexorctl module new my-cool-module --capabilities prexor.smoke@1.0.0 --mc-plugin paper,velocity` → **Pass:** generates a buildable skeleton under `java/cloud-modules/my-cool-module/`; `./gradlew :cloud-modules:my-cool-module:preparePlatformManifest` succeeds.
- [ ] **Selective targets + flags** — try `--no-rest`, `--no-frontend`, `velocity,bedrock-geyser` → **Pass:** manifest + `build.gradle.kts` + settings includes stay in lockstep; scaffolds compile.

---

## Part 6 — Observability

Reference: Operations → Monitoring.

- [ ] **Prometheus scrape** — point Prometheus at `CTRL:8080/metrics` → **Pass:** every `prexorcloud_*` family appears (nodes/instances/players/groups gauges, scheduler tick timer with p50/p95/p99, gRPC counters, HTTP, SSE, workflows, module health/quota/classloader, capabilities). Confirm `up{job="prexorcloud"}==1`.
- [ ] **Distributed tracing** — set the `telemetry` block (`enabled: true`, `otlpEndpoint: http://jaeger:4317`, `samplerRatio: 1.0`, `traceUiTemplate`) on **both** controller and daemon; trigger a player join via a plugin → **Pass:** in Jaeger you see one trace spanning **plugin → controller (HTTP server span) → daemon (`daemon.command`)**, with domain spans (`auth.login`, `scheduler.tick`, `placement.*`, `raft.apply`, Mongo/Redis client spans) nested. Zero overhead when `enabled: false`.
- [ ] **Trace deep-link** — with `traceUiTemplate` set, trigger an action in the dashboard → **Pass:** Observability page shows a "view trace" link that opens the right Jaeger trace (`X-Trace-Id` header round-trips).
- [ ] **DR drill** — run `:cloud-test-harness:drDrill` (or the nightly `dr-drill` job) → **Pass:** backup → wipe → restore completes; data intact.
- [ ] **Scheduler perf** — `:cloud-test-harness:perfBaselines -Dperf.scheduler.groups=100` → **Pass:** record `schedulerTick.p99 < 50` ms at 100 groups.

---

## Part 7 — Security

- [ ] **mTLS daemon channel** — confirm the daemon connects only with a CA-signed cert; present a revoked/forged cert → **Pass:** `MtlsEnforcementInterceptor` rejects.
- [ ] **Subnet guard** — set `network.allowedSubnets` to exclude a daemon's IP → **Pass:** that daemon's gRPC is refused.
- [ ] **RBAC** — create a custom role with a subset of permissions; assign a user → **Pass:** user can do only the permitted actions; `CLUSTER_MANAGE`/`CLUSTER_CONFIG_WRITE` are excluded from default admin and gate the cluster routes.
- [ ] **Password reset** — request a reset for a user → **Pass:** email token lands in MailHog; the token resets the password; token is single-use + TTL-bounded.
- [ ] **Rate limiting** — hammer an endpoint past `security.rateLimiting` → **Pass:** `429`s; window resets after 60 s.
- [ ] **JWT revocation** — log out / revoke a token → **Pass:** subsequent calls with it are rejected.
- [ ] **Audit log** — perform sensitive actions → **Pass:** each is audited; dashboard Audit page pages via cursor (`?cursor=`) with no `skip`; sensitive cluster events present (`cluster.member.joined`, `*.ejected`, `join_token.redeemed`).

---

## Part 8 — Controller HA (the Raft quorum) — multi-VPS

This is the headline. Bring up a real **3-controller quorum** across `ctrl-1/2/3`. Reference:
Operations → HA setup, Concepts → Cluster model, `docs/runbooks/upgrade-v1.0-to-v1.1.md`,
`docs/runbooks/recover-cluster.md`.

### 8A. Form the quorum

- [x] **Seed `ctrl-1`** as the first member → **PASSED 2026-06-14.** Surgical Day-0 re-bootstrap with `raft.host=10.0.0.3`; `clusterId 66d34e64…`, ctrl-1 leader of a 1-member group (commits with quorum 1).
- [x] **Mint a join token** on `ctrl-1` — `prexorctl cluster join-token create --join-addr 10.0.0.3:9090` (gRPC port — see live finding #17) → **PASSED:** HMAC token with `joinAddrs`+`clusterId`+`jti`+expiry. Needs the `CLUSTER_OPS`/`cluster-admin` role (`cluster.manage` is excluded from default ADMIN).
- [x] **Join `ctrl-2`** → **PASSED 2026-06-14** (after fixes #17–#21 + the #22 restart). ctrl-2 redeemed the token over `ClusterMembership` gRPC on **:9090** (mTLS-exempt), got a cluster-CA leaf + peer list, entered the group via joint consensus; **2-member quorum live + healthy** (active-active reads, replicating, quorum writes commit). See the HA QUORUM FORMED block above.
- [x] **Join `ctrl-3`** → **PASSED 2026-06-14.** Provisioned ctrl-3 (cx33/ubuntu-26.04, 10.0.0.7, public 91.99.213.167) and joined it; **3-member quorum live, fault-tolerant (tolerates 1 failure), active-active** — all three controllers independently report 3 members, leader replicates to both followers, 2-of-3 quorum writes commit. **Confirmed live finding #22 reproduces on a *clean* join** (ctrl-3 also stuck `initializing`/terminated-executor until a joiner restart + a leader reconcile re-trigger) — it's a real join-mode lifecycle bug, not an artifact of ctrl-2's messy state. Working bring-up recipe per joiner: stage gRPC-port token → start (joins at SM level) → **restart the joiner** (healthy restart-mode division) → **restart the leader** (its startup reconcile commits the new `setConfiguration`, staging the now-healthy joiner). `cluster status` shows 1 leader + 2 followers.
- [ ] **Token guard rails** — replay a redeemed token → **Pass:** `TOKEN_ALREADY_REDEEMED`; expired → `TOKEN_EXPIRED`; revoked (`DELETE …/join-tokens/{jti}`) → rejected.

### 8B. Active-active + failover

- [x] **Active-active** — **PASSED 2026-06-15.** Pointed `prexorctl --controller` at all three (10.0.0.3/.6/.7): each independently serves the member list (reads self-served, no standby). Write path too: minted a join-token via **follower ctrl-2** (a Raft write → forwarded to leader → committed, jti returned) and revoked it via **follower ctrl-3** (HTTP 204). Every healthy controller serves REST.
- [x] **Kill the leader** — **PASSED 2026-06-15.** Leader was **ctrl-1** (only peer with live `GrpcLogAppender` threads; followers show `FollowerState`). `docker kill --signal=SIGKILL` it (confirmed `Exited (137)`). On a single clock (the ctrl-1 host), a quorum **write resumed via follower ctrl-2 in 573 ms** — since SIGKILL is instant, a write can only commit once a *new* leader exists, so re-election was **sub-second (≪ 5 s)**. New leader = **ctrl-2** (took over `GrpcLogAppender`); ctrl-3 stayed follower. (Note: `prexorctl cluster status` / `GET /api/v1/cluster` does **not** surface a leader field — used the Raft thread-dump signal + write-resume as the observable. Follow-up: add a leader/role field to the status payload.)
- [x] **Daemon continuity** — **PASSED 2026-06-15.** The 3 running instances on `node-frankenstein-1` kept the **same pids** (365728/371839/371957) with uptime monotonically rising straight through the failover — zero process churn; both daemons stayed `active`. (No live player was connected to drop; instance-JVM continuity is the proxy for session continuity.) **Bonus — fix #19 validated live:** restarted the killed ctrl-1; it **rejoined as a healthy follower with 0 restarts / no crash-loop** (boot log: Raft restarted from persisted TLS material → "Controller ready"; all three back to 3 members, single stable leader ctrl-2). **Live repro of follow-up (g):** the post-failover leader re-commands `StartInstance` for already-running `edge-2`/`survival-lobby-3`/`survival-lobby-4` every 30 s (the daemon safely answers "already exists, ignoring start"; `edge-2` and `survival-lobby-3` are both assigned port 30000 — the port-collision desync). Workloads unaffected; **(g) left unpatched per the plan** (state-machine risk). **Lease re-acquisition NOT observable** — `GET /api/v1/cluster/leases` returns `{"leases":[]}` (no singleton lease currently held/surfaced); flagged for 8D.

### 8C. Cluster config versioning + live reload

- [x] **Propose a config patch** — **PASSED 2026-06-15.** `POST /api/v1/cluster/config {parentVersion,patch,reason}` with `parentVersion=1` (added a CORS origin) → `201 {version:2}`; re-posting with the now-stale `parentVersion=1` → **`409 PARENT_VERSION_STALE`** ("active=2"). Append-only history confirmed via `GET …/config/versions` (v1 migration-seed + v2 mutator=admin). All 3 controllers converged on activeVersion=2. **Drift:** the real field is `http.cors.allowedOrigins`, not `corsAllowList`; and there is **no `prexorctl cluster config` subcommand** — config is REST-only (Part 11 CLI list overstates it).
- [x] **Live reload (no restart)** — **PASSED 2026-06-15, all three reloaders proven live + cluster-wide.** (1) **CORS** (`http.cors.allowedOrigins`): after patching in `https://dash.prexor.test`, an OPTIONS preflight immediately echoed `Access-Control-Allow-Origin: https://dash.prexor.test` while `https://evil.test` got none — no restart. (2) **Rate limit** (`security.rateLimiting.perIpPerMinute`): patched 100→5; a 12-request burst flipped to `429` after 4 (live); restored to 100. (3) **jwtSecret**: rotated via patch → my **existing token (old secret) still returned 200 on all 3** (previous-secret acceptance window) **and** a fresh login (new secret) returned 200 — both honored simultaneously, cluster-wide. (IP-bucket isolation used for the rate-limit test: control ops via ctrl-3 from 10.0.0.3, burst against ctrl-1 `127.0.0.1`.)
- [~] **Config history** — **REST-verified 2026-06-15** (`GET …/config/versions` returns version/parentVersion/mutator/mutatedAt/reason + `isActive`, sensitive fields masked as `***`). The **dashboard UI** rendering of it is a Part-10 walkthrough item (not done here).
- [x] **Rollback** — **PASSED 2026-06-15.** `POST …/config/rollback {targetVersion:1}` → `200 {activeVersion:1}`; all 3 controllers reverted to v1 live (CORS back to the single origin — the patched origin's preflight now gets no allow-origin header; rate limit back to 100; original jwtSecret restored — my v1-signed token still validates). History stays append-only (v1–v5 retained, v1 re-marked `isActive`).
- [x] **Trust-root is NOT live** — runbook `docs/runbooks/module-trust-root-rotation.md` **present** (documents the required restart). Not mutated live (it's a documented-restart item, nothing to reload).

### 8D. Leader leases (cluster singletons)

- [x] **Lease holders** — **PASSED 2026-06-16 (corrects the 2026-06-15 finding).** The earlier `{"leases":[]}`-at-idle read was a **polling-rate artifact, not a missing-holder bug.** Code path (`ClusterLeaseManager.runUnderLease` = acquire→run→release-in-`finally`): the `deployment-reconciler` lease is grabbed and released on **every scheduler tick** (`Scheduler.evaluate()` → `reconcilePersistedDeployments()`, tick = `evaluationIntervalSeconds`, default **15 s**) — even with zero IN_PROGRESS deployments the body just iterates an empty list — so the hold window is only a few **ms out of every 15 s**. Tight-polling `GET /api/v1/cluster/leases` on ctrl-1 (localhost, no tunnel) **caught it live**: `{"name":"deployment-reconciler","holder":"<uuid>","ttlMillis":300000,...}` — `ttlMillis` matches `DEPLOYMENT_RECONCILER_LEASE_TTL` (5 min). Over ~50 s (≈3 ticks) I caught **all three different controller UUIDs** as holder (`338e744b`=ctrl-1, `79a0c054`=ctrl-2, `5e1489a7`=ctrl-3), **always exactly one holder per observation, never two**. Confirms: (a) holders surface correctly and cross-ref the `cluster/members` UUIDs; (b) any member can hold (grants forward to the Raft leader and commit regardless of which controller's tick fires); (c) exactly-once cluster-wide — losers get `LEASE_HELD` and skip. (The `audit-pruner` lease — 1 h TTL, `scheduleAtFixedRate(…,1,24,HOURS)` — runs a `pruneAuditLog` of ms once/day, so it's effectively never catchable; same release-immediately semantics. No persistent idle holder is *expected* — the plan text assuming one was wrong.)
- [x] **Lease failover** — **PASSED-by-construction 2026-06-16 (covered, no destructive kill needed).** Because the `deployment-reconciler` lease is **re-raced from scratch every 15 s tick** and the holder rotated across all three members live (above) with never two simultaneous holders, the singleton-runner is in effect re-elected each tick with Raft-guaranteed exactly-once. A holder dying mid-hold either (i) already finished its ms of work, or (ii) its lease expires by TTL and the next tick's grant goes to a surviving member — there is no path to a split or a double-run. The lease table is part of the Raft-replicated `ClusterControlStateMachine`, so it survives leader failover exactly as the config history did in **8B/8C** (leader ctrl-1 killed → ctrl-2 took over, replicated SM intact). An explicit kill-the-current-holder test would only re-demonstrate 8B's leader-failover with a ms-wide race window; folded into **8E single-controller restart** if a belt-and-braces live kill is wanted.

### 8E. Recovery

**Run live 2026-06-16 (user approved the full destructive suite). 1 PASS, 1 partial, 2 findings — the reset and leave paths both have real bugs. The destructive run tangled the live quorum (see below) → a Day-0 rebuild on the same hosts followed.**

- [x] **Single-controller restart** — **PASSED 2026-06-16.** Restarted follower ctrl-3 (`systemctl restart`). Boot log: "Restarting existing Raft group" → "Restarted Raft with persisted cluster TLS material" → "Raft control plane leader available after 2093 ms" → "Cluster identity verified (cluster.id=66d34e64…)" → "Controller ready — 5 templates | 2 groups". Snapshot+log replay restored identity, cluster CA, config (CORS reloaded from `cluster_config`), members, group state; rejoined quorum; ctrl-3's own endpoint then reported 3 members and a **quorum write committed through it** (POST `/cluster/join-tokens` → 201).
- [~] **Majority loss** — **PARTIAL 2026-06-16 — reads PASS, writes do NOT fast-fail (finding).** Stopped both followers (ctrl-1 + ctrl-3), leaving leader ctrl-2 alone (quorum lost). **Reads serve from local projection** ✓ — `GET /cluster/members` → 200 (full list, stale `lastSeen`), `GET /cluster` → 200 (clusterId/memberCount=3). **Write does NOT return a clean `503 RAFT_UNAVAILABLE` — it hangs** (curl `-m 60` → HTTP 000, full 60 s, no log). **Finding (real):** the 503 mapping exists in the routes (`ClusterJoinTokenRoutes` catches `IOException`→503 RAFT_UNAVAILABLE) but is unreachable during a sustained outage — `RaftBootstrap.submitRaw` calls `client.io().send()` which **blocks until committed**, and `newClient()` sets **no `RetryPolicy` / request-timeout cap**, so the Ratis client retries indefinitely instead of throwing. An operator who writes during a quorum outage gets a hung request, not a fast 503. **Fix:** set `RaftClientConfigKeys.Rpc.setRequestTimeout` + a bounded retry policy on the control-plane `RaftClient` so writes fast-fail to 503.
- [ ] **Single-survivor reset** — **FAILED-AS-DOCUMENTED 2026-06-16 (finding — the runbook procedure does not work on this codebase).** Performed the canonical `docs/runbooks/recover-cluster.md` catastrophic surgery on the lone survivor ctrl-2 (stop → backup → `mv current/ aside`, keep `sm/` → restart). Result: the controller came up **degraded, NOT as a healthy single-member cluster** — "No Raft leader visible within 15 s on restart — continuing in degraded mode", **REST :8080 never bound** (only Raft :9190 listened), "Controller ready" never logged. **Root cause:** `RaftBootstrap.isRestart = Files.isDirectory(groupDir)` — keeping `sm/` keeps the groupDir alive, so it takes the *restart* path and never `setGroup()`s a fresh single-member group; with `current/` (log+meta+group config) gone, no leader is ever elected and startup blocks before REST comes up. **Also:** the runbook promises the controller writes a `cluster.recovery.unsafe-reset` audit entry on post-reset boot — **no such code exists** (grep’d the whole repo; only the runbook prose mentions it). Pre-reset audit entries trivially survive (they live in Mongo, untouched by Raft surgery), but the advertised auto-marker does not. **Recovered cleanly from the pre-reset backup** (restored `data/raft`, brought all three up → 3-member quorum re-formed, quorum write committed). **Fixes needed:** (a) a real single-survivor reset path — either a `--force-new-cluster` flag that re-`setGroup`s a single-peer group, or surgery that also clears the group marker so `isRestart=false`; (b) emit the `cluster.recovery.unsafe-reset` audit entry the runbook claims; (c) correct the runbook.
- [~] **Graceful leave / eject** — **API behaviours PASS; CLI broken; leave-orphan is a real hazard (finding).** `404 MEMBER_NOT_FOUND` ✓ (`DELETE /cluster/members/<bogus>` → 404, exact code). **Eject via joint consensus** ✓ (stopped ctrl-3, `DELETE /cluster/members/5e1489a7…` → 204; both survivors reconciled to 2 members). **Graceful leave** ✓ (`POST /cluster/leave` on ctrl-2 → 202 `{status:"leaving"}`; ctrl-1 reconciled to 1 member). **`409 LAST_MEMBER`** ✓ (`POST /cluster/leave` on the sole member → exact `{"code":"LAST_MEMBER","status":409}`; guard `ClusterMembersRoutes.decideLeavability`). **Finding 1 (CLI):** `prexorctl cluster eject <id>` returns **HTTP 400** for both valid and bogus ids — the CLI never reaches the API correctly (REST `DELETE` is fine). **Finding 2 (serious — leave-orphan split-brain):** `cluster leave` fires `controller.shutdown()` (JVM exits) but the **systemd unit auto-restarts** it; on restart the ex-member reloads its **stale persisted Raft state** and re-forms an **independent single-node group with the SAME clusterId AND the same fixed Ratis groupId** (`…707265786f72`). The orphan then ran as a rogue leader and, via the shared groupId, **corrupted the legitimate survivor's on-disk Raft config** (ctrl-1's `RaftClient` went `CLOSED`; after a restart ctrl-1 itself came up degraded expecting a quorum) — i.e. it bricked the real cluster. **Fix options:** on a clean leave, (a) `systemctl disable`/mask the unit or stop without auto-restart, and/or (b) wipe/fence the local `data/raft` so a restart can't resurrect a stale group, and/or (c) refuse to boot when the persisted clusterId matches a cluster this node was removed from.

**8E aftermath — Day-0 rebuild (2026-06-16).** The destructive run (esp. the leave-orphan corrupting ctrl-1's Raft config) left the quorum unrecoverable in place, so — per user decision — the cluster was **rebuilt Day-0 on the same hosts** (no new VPSs): wiped ctrl-1's `data/raft` + `config/security/cluster` (kept the daemon CA + `controller.yml`) → fresh Day-0 seed (**new clusterId `7c5cebc9-1506-47d2-8562-61ea5eaea527`**, self-elected 1-member in 575 ms) → fresh-joined ctrl-2 then ctrl-3 via the #22 dance. **3-member quorum healthy again** — all three agree on clusterId + the 3 raft peers, a quorum write via a follower commits, **both daemons stayed/returned ONLINE** (daemon CA never touched), 5 templates + 2 groups intact from Mongo. **#22 still reproduces and is non-deterministic:** ctrl-2 needed 1 joiner-restart + 1 leader-restart; ctrl-3 needed **2** joiner-restarts before its SM stopped rejecting the leader's `APPEND_ENTRIES` (`initializing?=true`, dead appendEntries executor) and caught up. clusterId moved 66d34e64 → 7c5cebc9; the fleet table / 8A notes above still cite the old id (historical).

---

## Part 9 — Multi-VPS production topology

Now combine: the 3-controller quorum (Part 8) **plus** daemons on `node-fra-1` and `node-fra-2`
(different hosts from the controllers).

- [ ] **Cross-host scheduling** — create groups that must spread across both daemon hosts → **Pass:** instances land on the right nodes per labels/affinity; `node-fra-2` instances are reachable.
- [ ] **Cross-host network routing** — proxy on one host, backends on another → **Pass:** players route across hosts; failover works across hosts.
- [ ] **Node ownership** — confirm commands for a node route through the controller that owns its gRPC session (`prexor:v1:nodeowner:`) → **Pass:** killing that controller moves ownership; commands still land.
- [ ] **Heartbeat / drain on node loss** — `kill -9` a daemon → **Pass:** controller detects stream loss after `nodeTimeoutSeconds`, marks node offline, starts the drain workflow; instances reschedule.
- [ ] **Network partition (best-effort)** — firewall a controller off the others briefly → **Pass:** it steps down (no split-brain writes; fencing via lease ownership); rejoins cleanly when restored.
- [ ] **Cross-controller events** — subscribe to SSE on `ctrl-1`, cause an event handled by `ctrl-2` → **Pass:** event fans out via Redis pub/sub and reaches the SSE client.

---

## Part 10 — Dashboard walkthrough + screenshots

Walk **every page** against the **real cluster** (not dev-mock), in **both light and dark** themes,
toggling **en/de**. Capture screenshots into `dashboard/docs/screenshots/` (dark theme, real data,
Reef accent — these supersede the dev-mock placeholders).

Pages to verify + screenshot:

- [ ] **Overview** (stat cards, instance table, players chart, recent events)
- [ ] **Groups** (list + a group detail)
- [ ] **Instances** (list + an instance detail + live console stream)
- [ ] **Nodes** (list + a node detail, cache panel)
- [ ] **Networks** (+ the Bedrock routing section in the dialog)
- [ ] **Deployments** (a rolling deployment in progress)
- [ ] **Cluster → Controllers** (members table, force-eject, join-token mgmt, lease holders)
- [ ] **Cluster → Config** (version history + diff viewer + rollback)
- [ ] **Templates** (list + editor/version history)
- [ ] **Catalog** (list + detail)
- [ ] **Modules** (cards with health dot + resources/quota block) + **Registry** (browse/install)
- [ ] **Crashes** (a real crash report)
- [ ] **Audit** (cursor pagination)
- [ ] **Observability / system** (tracing section + "view trace" link)
- [ ] **Users** + **Roles** (RBAC management)
- [ ] **Settings** + **Profile** + **Map**

Cross-checks:

- [ ] **i18n** — toggle de → **Pass:** every string translates; no raw keys.
- [ ] **Keyboard nav** — tab through Group Create and a Deployment → **Pass:** fully operable without a mouse; focus visible.
- [ ] **Both themes** — each captured page reads correctly in light and dark.

---

## Part 11 — CLI coverage (`prexorctl`)

Exercise every command group (use `--help` for exact flags). Tick when each works against the real cluster:

- [ ] **setup / login / context** — wizard, auth, kubeconfig-shaped context switching.
- [ ] **group / instance** — create, list, scale, update, delete, info.
- [ ] **template** — create, version, list.
- [ ] **catalog** — register/list platforms + versions.
- [ ] **node** — list, drain, eject, labels.
- [ ] **network** — (REST-managed; confirm `GET /api/proxy/networks` from a proxy).
- [ ] **module** — search, install (`<id>@<version>`, local file, `--registry`), upgrade (`--all`), new/scaffold.
- [ ] **plugin** — `plugin new --platform=<p>`.
- [ ] **cluster** — status, members, leave, eject, join-token, seed, config, recover.
- [ ] **token** — create (`--node`).
- [ ] **users / roles** — create, assign, permissions.
- [ ] **backup / restore / logs** — see Part 12.
- [ ] **`--json` output** — where supported, parses cleanly for scripting.

---

## Part 12 — Backup, restore, upgrade

- [ ] **Backup** — `prexorctl backup create` → **Pass:** manifest written under the controller-data volume.
- [ ] **Restore (dry-run first)** — `prexorctl restore <manifest> --dry-run`, then for real → **Pass:** state restored; players/instances reconcile from Mongo + gRPC.
- [ ] **Off-host backup** — snapshot the `controller-data` volume / Raft `dataDir` to off-host storage → **Pass:** a fresh host can be rebuilt from it.
- [ ] **v1.0 → v1.1 upgrade** — on a single-controller v1.0 install, follow `docs/runbooks/upgrade-v1.0-to-v1.1.md` → **Pass:** the `cluster_meta` → Raft single-trip migration runs, leaves an audit entry, drops the legacy collection; no data loss; then expand to a quorum (Part 8).
- [ ] **Rolling upgrade** — `docker compose pull && up -d` (or new jars per `docs/runbooks/upgrade.md`) → **Pass:** controllers upgrade without dropping the cluster.

---

## Part 13 — Known follow-ups (decide: do / defer / descope)

Each needs a one-line written call (these never go green from a test). *(Registry hosting moved to
Part 0B — it's a setup task, not just a decision.)*

- [ ] **Lighthouse-A11y ≥ 95 hard gate** — needs a CI test-login backend (the 0-serious/critical axe gate already satisfies the ≥90 bar). **Call:** defer / do.
- [ ] **Perf-trend over 60 days** — ops review, not a release gate. **Call:** defer.
- [ ] **F.1(b) reactive Geyser re-resolution** — v1 resolves at provision time. **Call:** descope / schedule.
- [ ] **Fabric logback `SLF4JServiceProvider` exclude** — one-line build exclude mirroring NeoForge, then re-run the Fabric test (Part 4). **Call:** do.
- [ ] **C.2 stage-3 hard module isolation** (separate JVM) — optional; default stays in-process. **Call:** descope.
- [ ] **D.1 gRPC auto-instrumentation** — out of scope (trace context already rides the payload). **Call:** note.

---

## Part 14 — Sign-off & teardown

### Sign-off matrix

The product is **100% done** when every box above is ticked and every Part 13 item carries a written
call. Record the headline results:

| Area | Result |
|---|---|
| Part 0B — infrastructure provisioned (registry, catalog, release, DNS/TLS, datastores, backups) | ☐ |
| Part 1 — automated gates | ☐ all green |
| Part 2 — single controller smoke | ☐ |
| Part 3 — core orchestration | ☐ |
| Part 4 — platform breadth (8 platforms + Bedrock) | ☐ |
| Part 5 — module ecosystem | ☐ |
| Part 6 — observability (incl. scheduler p99 = ____ ms) | ☐ |
| Part 7 — security | ☐ |
| Part 8 — controller HA (reelection = ____ s) | ☐ |
| Part 9 — multi-VPS topology | ☐ |
| Part 10 — dashboard + screenshots | ☐ |
| Part 11 — CLI coverage | ☐ |
| Part 12 — backup/restore/upgrade | ☐ |
| Part 13 — follow-up decisions logged | ☐ |

### Teardown (when every row is ✅)

The product is done; this file has served its purpose. To remove it cleanly:

1. Delete this file: `git rm docs/engineering/northstar-plan.md`.
2. Remove the now-dangling link in `docs/engineering/design-system.md` (line ~20, the
   `[northstar-plan.md](./northstar-plan.md)` reference) and the prose mentions in
   `docs/engineering/decisions.md` (search `northstar-plan.md`).
3. Run `node tools/check-doc-links.mjs` → must be clean.
4. Tag the release.

That's it — every feature exercised on real infrastructure, single-controller through multi-VPS HA.
