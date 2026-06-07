# PrexorCloud — Northstar-Plan: bestes & modernstes MC-Cloud-System

**Status:** v1 — gezeichnet am 2026-05-31 nach Gesamt-Audit.
**Owner:** unzugewiesen.
**Zielhorizont:** v1.1 (HA-Foundation) → v1.2 (Ökosystem-Reife) → v1.3 (Plattform-Ausbau).
**Verwandte Dokumente:**
- [`cluster-join-plan.md`](./cluster-join-plan.md) — Raft-Control-Plane-Detailplan (in diesen Plan eingebettet als **Track A**)
- [`track-e-plan.md`](./track-e-plan.md) — Frontend- & A11y-Detailplan (**Track E**, phasiert)
- [`MASTER_PLAN.md`](./MASTER_PLAN.md) — bisheriger Rahmenplan
- [`WEBSITE_PLAN.md`](./WEBSITE_PLAN.md) — Public-Site (geliefert v1.0)

---

## ⏱️ Aktueller Stand — wo wir gerade stehen (Stand 2026-06-05)

**Gesamt ≈ 73 % (eng-day-gewichtet).** Milestones: **v1.1 ≈ 100 %** (A.8 Config-History-UI geshippt) · **v1.2 ≈ 80 %** (C+D quasi fertig, **E ist die Lücke**) · **v1.3 ≈ 20 %** (F angefangen — F.1-Sichtbarkeit, H ≈ 45 %).

**Track-Stand:** A 100 % · B 100 % · C ~97 % · D ~96 % · E ~75 % · F ~8 % · G 100 % · H ~45 %.

**Zuletzt geliefert (Session 2026-06-06):**
- **E-P1.1 authed-Flow-axe als harter Gate — v1.2-A11y-Kriterium erfüllt.** Der Scan war ein stiller False-Green (dev-mock kompilierte aus → 0/16 Routen gescannt, „grün"). Drei Infra-Bugs gefixt (dev-mock `vite.define`-Bridge, `pnpm build` auf `main` durch stale SDK-Types kaputt, CI-axe-Install scheiterte an `workspace:*`), dann den realen Backlog abgearbeitet: `<html lang>`, Audit-Scroll-Region, Neutral-Text-Kontraste, und 5 sub-AA-Accents abgedunkelt. **0 serious/critical über alle 16 authed-Routen**, CI-Gate ist jetzt **hart**. Track E ≈ 75 %.

**Zuletzt geliefert (Session 2026-06-05, Teil 2):**
- **F.1 Bedrock-vs-Java-Spieler-Sichtbarkeit** — Controller leitet pro Spieler eine `edition` aus der UUID ab (Floodgate-Konvention, kein Plugin-Change); Dashboard zeigt den Java/Bedrock-Split (Stat-Card, Detail-Zeile, Bedrock-Badge). `PlayerEditionTest` + Mapper/Store-Tests; OpenAPI + i18n en/de in sync. Routing-Verhalten + Geyser-Proxy bleiben offen. → erster Anstoß für Track F.
- **D.1 MongoDB-OTel-Instrumentation** — `MongoCommandTracer` (CommandListener) emittiert pro Mongo-Command eine CLIENT-Span unter der auslösenden HTTP-/Domain-Span; auf `MongoClientSettings` registriert, null-Overhead wenn Telemetry aus. `MongoCommandTracerTest` (5).
- **D.1 Redis/Lettuce-OTel-Instrumentation** — `RedisTracing` adaptiert Lettuces native Tracing-SPI auf OTel; nur bei `telemetry.enabled` auf `ClientResources` installiert, Command-Args ausgeschlossen. Telemetry-Bau nach vorn gezogen (vor Redis-Connect). `RedisTracingTest` (3). → D.1 nur noch gRPC offen (Javaagent-Domäne).
- **D.3 MC-Plugin→Controller-Trace-Hop (SDK-frei)** — `W3CTraceparent` mintet pro Plugin-Request einen gesampleten `traceparent`; Controller continued die Trace. `W3CTraceparentTest` (3). → D.3 komplett (bis auf aufgezeichnete Plugin-Span, die ein Plugin-SDK bräuchte).
- **C.5 backup-orchestrator periodische Snapshots** — `BackupSchedule` (env-getrieben) + `scheduleAtFixedRate` in `onStart`; opt-in via `PREXORCLOUD_BACKUP_INTERVAL_MINUTES`+`PREXORCLOUD_BACKUP_TARGETS`, sonst REST-only. `BackupScheduleTest` (6).
- **C.4 Scaffolder: Scaffolds durchgängig baubar + Bedrock-Target + `--no-rest`** — Manifest + `build.gradle.kts`-`extensionArtifacts` + `settings`-Includes werden in Lockstep auf die Auswahl geprunt; `bedrock-geyser` ist jetzt erstklassiges Target (Default = 4 Plattformen wie das `example`); `--no-rest` löscht `rest/` + `onRegisterRoutes`-Override. End-to-end gradle-verifiziert (Default/3-/2-Target-Scaffolds bestehen `preparePlatformManifest`). Go-Tests `TestPruneExtensionsBlock`/`TestPruneExtensionArtifacts`/`TestStripOnRegisterRoutes` u.a.

**Zuletzt geliefert (Session 2026-06-05):**
- **A.8 Config-Version-History/Diff-UI** — neue Dashboard-Seite `/cluster/config` (Versionsverlauf + `DiffViewer` patch-vs-parent + Rollback-Dialog), Nav-Eintrag, Store-Actions, i18n en+de, 8 neue Store-Tests. Reine Frontend-Verdrahtung (Backend war schon da). → **v1.1 komplett.**
- **H.1 Audit-Log-Keyset-Pagination** — `getAuditLogSeek(cursor,limit)` (Range-Scan über `_id`, kein `skip`), REST-`?cursor=`-Pfad + `nextCursor`, Dashboard-Audit-Seite auf Cursor-Stack migriert. Legacy-Offset-Pfad bleibt. Harness + Vitest-Tests grün; pre-existing Harness-Compile-Break (`TestCluster`/`TelemetryDaemonConfig`) nebenbei gefixt.

**Zuvor (Session 2026-06-03):**
- **H.3 Dashboard-i18n vollständig** — alle 104 Rest-Hardcode-Strings extrahiert (Batches 20–22), `i18n:check-hardcoded` jetzt **harter** CI-Gate (2072 Keys, en+de).
- **H.2 statische A11y** — `a11y-lint.mjs` (harter Gate) + 26 Icon-only-Controls mit `aria-label` benannt; **Runtime-axe-Job** (soft) gegen Login-Oberfläche.
- **Track A Loose Ends** — `/cluster/leases`-Endpoint, `cluster.member.joined`-Audit, tote `ClusterJoinRoutes` entfernt.

**👉 Hier weitermachen:** v1.1 ist vollständig, A.8 + H.1-Pagination durch. Was bleibt, sitzt in den zwei großen Tracks plus H-Resten:
- **Track E** (~12 d) — Frontend-Konsolidierung + A11y-**Runtime**-Härtung (authed-Flow-axe mit Test-Login, Voll-Contrast-Audit, Keyboard-Nav).
- **Track F** (~15 d) — Bedrock/Fabric/Forge, echte neue Subsysteme (kein Batch-Job).
- H-Reste: H.1 Perf-Trend-Review + Scheduler-Tick-Profiling; H.2 Runtime-axe über authed-Flows.
- **Track E ist gescopet:** [`track-e-plan.md`](./track-e-plan.md) (4 Phasen, ~13–14 d). Empfohlener Einstieg **E-P1.1** — authed-Flow-axe via dem schon existierenden `dev-mock`-Layer (billigster Weg zum v1.2-A11y-Gate „Lighthouse ≥ 90").

---

## 0. Nordstern — was „bestes MC-Cloud-System" konkret bedeutet

Die Marketing-Aussage ist sinnlos, wenn man nicht definiert, woran man sich messen lässt. PrexorCloud will in folgenden **acht Dimensionen** das Beste sein, was der Minecraft-Cloud-Markt bietet (Vergleichsgegner: CloudNet 4, SimpleCloud v3, ReformCloud, Aves Cloud):

| Dimension | Heutiger Stand | Ziel v1.3 | Gewicht |
|---|---|---|---|
| **HA / Cluster-Konsistenz** | 4/10 (Single-Controller + Mongo-Hack) | 9/10 (Raft-Quorum, strong consistency) | 20 % |
| **Supply-Chain-Security** | 9/10 (cosign + Rekor + mTLS) | 10/10 (+ SBOM-Diffs, automated CVE-Gate) | 15 % |
| **Observability / Ops** | 8/10 (Perf-Baseline + DR-Drill) | 10/10 (+ OpenTelemetry, distributed tracing) | 15 % |
| **Modul-Ökosystem** | 7.5/10 (Capability-API, First-Party-Module) | 10/10 (+ Registry, Sandbox, Resource-Limits) | 15 % |
| **Dev-Experience (SDK, CLI)** | 8/10 (OpenAPI-getrieben) | 9.5/10 (+ language-SDKs, Module-Scaffolder) | 10 % |
| **MC-Plattform-Breite** | 7/10 (Paper, Folia, Velocity, BC, Bedrock via Geyser) | 9/10 (+ first-class Bedrock-Routing, Fabric, Forge) | 10 % |
| **UX / Dashboard** | 7/10 (Vue/Nuxt) | 9/10 (Design-System voll integriert, A11y, i18n) | 10 % |
| **Repo-Hygiene / Onboarding** | 6/10 (Sediment, READMEs fehlen) | 9/10 (sauber, dokumentiert, lehrbar) | 5 % |

**Gewichteter heutiger Gesamtwert: ~7.8/10. Zielwert v1.3: ~9.5/10.**

Alles weitere im Plan ist auf diese Skala kalibriert: kein Feature, das nicht messbar einen dieser Werte hebt, wird priorisiert.

---

## 1. Strategische Reihenfolge — warum nicht parallel alles

Die Phasenreihenfolge folgt drei Regeln:

1. **Ohne HA ist alles andere Lippenbekenntnis.** Solange v3-Raft nicht fertig ist, ist „bestes Cluster-System" eine Lüge. Track A geht voraus.
2. **Hygiene vor Erweiterung.** Tote Verzeichnisse und unverdrahteter Scaffolding-Code müssen weg, bevor neue Subsysteme dazukommen — sonst potenzieren sich die Reib-Punkte für Contributors.
3. **Ökosystem-Features bauen auf stabilem Kern auf.** Module-Sandboxing und -Registry brauchen die Raft-basierte Trust-Root-Verteilung als Fundament.

Daraus ergeben sich **sieben Tracks**, die teilweise parallel laufen können, aber klare Reihenfolge-Constraints haben.

```
v1.1 ──── Track A: HA-Foundation (Raft v3, Phase 3-11)         │ 25 eng-days
          Track B: Repo-Hygiene & Cleanup                       │  4 eng-days
          Track G: Doku & ADR-Lücken schließen                  │  5 eng-days

v1.2 ──── Track C: Modul-Ökosystem-Reife                       │ 28 eng-days
          Track D: Observability Gen-2 (OTel/Tracing)           │  8 eng-days
          Track E: Frontend & Design-System Konsolidierung      │ 20 eng-days

v1.3 ──── Track F: MC-Plattform-Breite & Bedrock-Tiefenausbau   │ 15 eng-days
          Track H: Polish, Performance, A11y, i18n              │ 10 eng-days

──────────────────────────────────────────────────────────────
Gesamt:                                                          115 eng-days
                                                                ≈ 5–6 Monate bei 1 FTE
                                                                ≈ 2.5–3 Monate bei 2 FTE
```

---

## 2. Track A — HA-Foundation (Raft v3 fertigstellen)

**Ziel:** Echte Multi-Controller-HA mit Quorum-Konsistenz. **Status heute: ~20 %** (Raft-Scaffolding liegt da, Bootstrap ist nicht verdrahtet).

Detailplan siehe [`cluster-join-plan.md`](./cluster-join-plan.md). Hier nur die **Lücke**, was noch zu tun ist:

### A.1 Bootstrap wirklich auf Raft umstellen — *Phase 3 zu Ende bringen* (~4 d) — ✅ **shipped (Commit 2c6960b)**

**Heutiger Defekt:** `PrexorCloudBootstrap.reconcileClusterIdentity()` ist immer noch der v1-Pfad gegen `cluster_meta` in Mongo. `ClusterControlService` (178 LOC) ist instanziierbar aber nirgends instanziiert.

**Konkret:**
- `PrexorCloudBootstrap.java:331` — `reconcileClusterIdentity(stateStore)` durch `clusterControlService = new ClusterControlService(config, nodeId)` ersetzen.
- `ClusterControlService.bootstrap()` aufrufen, das übernimmt: Raft-Group hochfahren (Day-0) oder rejoinen (Restart), `cluster.id` aus State-Machine lesen, in `controller.yml` mirrorn.
- `MongoStateStore.getClusterId()`/`stampClusterId()` entfernen.
- `cluster_meta`-Collection bei Migration auslesen, in Raft schreiben, anschließend `DROP`en. **Single-Trip-Migration**, keine Doppelschreibung wie früher geplant.
- **Phase-1-Spike-Müll löschen:** `cluster/raft/KeyValueStateMachine.java` (88 LOC) + `KvOp.java` (61 LOC). War das Trivial-KV aus dem Spike; jetzt überflüssig.

**Akzeptanz:** Frischer Boot ohne `cluster_meta`-Collection muss ohne Fehler durchlaufen. Boot mit existierender `cluster_meta` muss migrieren und im Audit-Log einen Eintrag hinterlassen.

### A.2 gRPC-Membership + TLS-Bootstrap — *Phase 4* (~5 d)

Wie im `cluster-join-plan.md` §4 spezifiziert. Konkret:

- Neuer Proto-Service `ClusterMembership` in `contracts/cluster.proto` mit RPCs: `RequestJoin`, `LeaveCluster`, `ForceEject`.
- CSR-basierter TLS-Bootstrap: Joining-Controller schickt eine CSR im Join-Request; Leader signiert mit Cluster-CA, returnt signiertes Cert + Cluster-CA-Bundle + Raft-Group-Membership-Info.
- Snapshot-Streaming-Endpoint für Catchup.
- `JoinToken.hmac` mit `clusterMeta.seedSecret` verifizieren.
- Audit-Trail: `cluster.member.joined`, `cluster.member.removed`, `cluster.join_token.redeemed`.

**Risiko:** Ratis' Joint-Consensus-API ist subtil. Empfehlung: vor Phase 4 ein Wochenend-Spike auf einer 3-Node-Konfiguration, um die API zu verstehen. Sonst läuft man in unverständliche Logfile-Botschaften.

### A.3 REST + CLI — *Phase 5* (~3 d)

REST-Endpoints aus `cluster-join-plan.md` §REST-surface; alle 11 Routen implementieren. `prexorctl cluster {status,members,leave,eject,join-token,seed,config,recover}` Subcommands.

**Berechtigungen:**
- Neuer `Permission.CLUSTER_VIEW`, `CLUSTER_CONFIG_WRITE`, `CLUSTER_MANAGE`. Letztere **nicht** in default ADMIN — über `Role.EXCLUDED_FROM_DEFAULT_ADMIN`-Mechanik.
- Alten `Permission.CLUSTER_JOIN` ausbauen, `ClusterJoinRoutes` löschen.

### A.4 Versioned Config + REST-Patch — *Phase 6* (~3 d) — ✅ **shipped (Commit 15316eb)**

Append-only-Versionierung von `clusterConfig`. `parentVersion`-Konflikterkennung (409). Rollback per `POST /cluster/config/rollback {targetVersion}`. Masking für sensitive Felder (`security.jwtSecret`, `redis.uri`, SMTP).

**Achtung Boundary:** Ein Patch auf `corsAllowList` darf nicht den ganzen Config-State neu schreiben — Patch-Semantik (RFC 7396 oder eigene Path-basierte Patches). Empfehlung: eigene Path-basierte Patches, da JSON-Merge-Patch mit Arrays unklar ist.

### A.5 Live-Reload über Raft `apply()` — *Phase 7* (~2 d) — ✅ **shipped**

`ClusterControlStateMachine.apply()` feuert pro committed Entry ein Event auf den internen `EventBus`. Der `ClusterConfigReloadCoordinator` (`controller/cluster/reload/`) abonniert `ClusterConfigChangedEvent`, foldet die aktive Config-Version über die Parent-Chain (`ClusterConfigProjection`) und verteilt die effektive Config an die registrierten Subscriber:

- ✅ **CorsAllowList** — `CorsAllowListReloader` ersetzt die Live-Origin-Liste (inkl. Removals).
- ✅ **RateLimiter** — `RateLimitReloader` swappt `perIp`/`perUser`/`failOpen` atomar in `RateLimitMiddleware` (Limits jetzt `volatile` + `reconfigure()`).
- ✅ **JwtManager** — `JwtSecretReloader` rotiert den aktiven Signaturschlüssel cluster-weit und hält den vorherigen im Acceptance-Window.

Der Coordinator primed beim Start einmalig (joinende Controller adoptieren so die cluster-autoritative Config aus dem Snapshot statt der lokalen `controller.yml`). Tests: `ClusterConfigProjectionTest`, `ReloadersTest`, `ClusterConfigReloadCoordinatorTest`.

**Bewusst nicht live:** `modules.signing.trustRoot` — der Signature-Verifier wird einmalig beim Boot gebaut; Trust-Root-Wechsel brauchen einen Controller-Restart (dokumentiert in `docs/runbooks/module-trust-root-rotation.md`). Ein dedizierter `SigningPolicyManager` existiert nicht; das Signing-Config lebt in `ModuleSigningConfig` und wird vom `PlatformModuleSignatureVerifier` konsumiert.

**Was raus kann:** Redis-Pubsub-Subscriptions für config-Änderungen. Was bleibt: Redis-Pubsub für ephemere Events (Player-Join, Console-Lines, Daemon-Heartbeats).

### A.6 Leader-Leases für Scheduler / Reconciler / DR-Drill / Audit-Pruner — *Phase 8* (~3 d)

Aktuell Redis-basierte Leases mit bekannten TTL-Races. Auf Raft-Leases umstellen:

```java
clusterLeases.takeLease("scheduler", ttl = 30s,
    onAcquire = () -> scheduler.start(),
    onLost    = () -> scheduler.stop());
```

**Wichtig:** Phase 8 ist **deferrable** auf v1.2. Die Redis-Leases funktionieren heute, sind nur unsauber. Wenn Track-A-Budget knapp wird, kann das nach hinten rutschen.

### A.7 Wizard Token-Branch — *Phase 9* (~2 d)

`installer/`-Wizard bekommt die zweite Hauptfrage: „Hast du ein Join-Token?". Wenn ja: Token einkleben, Bootstrap-Vars für den Day-N-Pfad einrichten, Pending-Token in `data/pending-join-token` schreiben.

### A.8 Dashboard „Cluster"-Page — *Phase 10* (~3 d) — ✅ **shipped**

Dashboard-Seiten unter `/cluster/*`:
- ✅ **Mitglieder-Tabelle** + Force-Eject-Button mit Confirmation, Join-Token-Management, Lease-Holder-Übersicht — `pages/cluster/controllers.vue` (bereits vorher geliefert).
- ✅ **Config-Version-History mit Diff-Viewer (2026-06-05)** — neue Seite `pages/cluster/config.vue` + Nav-Eintrag „Cluster config" unter der Cluster-Gruppe (perm `cluster.view`). Zeigt den append-only `cluster_config`-Verlauf (Version/Parent/Mutator/Zeit/Grund, Active-Badge, neueste zuerst); Auswahl einer Zeile diffed deren `patch` gegen den `patch` der Parent-Version über die bestehende `DiffViewer`-Komponente (unified/split, JSON). Rollback-Button pro Nicht-Active-Version (perm `cluster.config.write`) mit Reason-Dialog → `POST /cluster/config/rollback`. Store-Actions `fetchConfigVersions`/`fetchConfigVersion`/`rollbackConfig` (`stores/cluster.ts`), i18n en+de (harter Parity- + Hardcode-Gate grün), Tests in `stores/__tests__/cluster.test.ts` (8 neue, 16 gesamt grün).
  - **Scope ehrlich:** (1) Der Diff vergleicht den **Per-Version-Patch** gegen den Parent-Patch (jede Version speichert ihr eigenes Delta), nicht die gefoldete *effektive* Config — letzteres bräuchte einen „effective config at version N"-Endpoint (nur die aktive Version ist heute foldbar via `GET /cluster/config`). (2) Sensible Felder kommen maskiert (`"***"`) zurück, solange der Aufrufer kein `CLUSTER_MANAGE` hält — bewusst, kein Reveal-Toggle im UI. (3) Backend (alle 5 `/cluster/config*`-Routen) war bereits geshippt; dies ist reine Frontend-Verdrahtung.

### A.9 Recovery-Tooling — *Phase 11* (~2 d)

`prexorctl cluster recover --i-have-only-survivor` — single-member Raft-Reset für Majority-Loss. Interaktive Confirmation, Audit-Eintrag der den Reset überlebt. Doku in `docs/runbooks/cluster-recovery.md`.

### A.10 ADR + Migration-Guide — *Phase 12* (~2 d) — ⏳ **teilweise shipped**: ADR 29 (embedded Ratis) + ADR 4 Update in `decisions.md` ✅; Migration-Runbook `docs/runbooks/v1.0-to-v1.1.md` und Recovery-Runbook noch ausstehend

ADR-Eintrag in `docs/decisions.md`: „Embedded Raft via Apache Ratis als Cluster-Control-Plane (statt Mongo-CAS / externem Coordinator)". Migration-Guide in `docs/runbooks/v1.0-to-v1.1.md`: Schritt-für-Schritt für die zwei Operator-Szenarien (Single-Controller-Upgrade, Multi-Controller-Upgrade).

**Track-A-Gesamt: ~25 eng-days. Block für v1.1-Release.**

---

## 3. Track B — Repo-Hygiene & Cleanup — ✅ **shipped**

**Ziel:** Onboarding-Reibung auf null. Tote Pfade entfernen, Konventionen festziehen.

**Stand 2026-05-31:** Alle Sub-Tracks erledigt. Was geliefert wurde, pro Abschnitt unten.

### B.1 Tote Verzeichnisse entfernen (~0.5 d) — ✅ **shipped**

| Pfad | Befund | Aktion |
|---|---|---|
| `java/cloud-module/` | 7 build.gradle.kts ohne settings.gradle.kts-Eintrag — Rename-Sediment | **DELETE** kompletter Tree |
| `java/cloud-modules-core/` | Nur build.gradle.kts, kein Source, nicht im Build | **DELETE** |
| `config/` (nur `controller.compose.yml`) | Dublette zu `deploy/compose/` | Datei nach `deploy/compose/` MOVEn, `config/` DELETEn |
| `infra/perf/baselines.json` | Einziger Inhalt unter `infra/` | MOVEn nach `java/cloud-test-harness/perf/`, `infra/` DELETEn |
| `java/cloud-controller/logs/*.log.gz` | Eingecheckte Logs (im Diff bereits gelöscht) | Commit + `.gitignore`-Eintrag für `logs/` |
| `java/cloud-controller/config/.initial-admin-password` | Wird bei jedem Boot überschrieben | `.gitignore`-Eintrag |
| `cluster/raft/{KeyValueStateMachine,KvOp}.java` | Phase-1-Spike, nicht mehr genutzt | **DELETE** nach Track A.1 |

**Geliefert:** `java/cloud-module/`, `java/cloud-modules-core/`, `config/`, `cluster/raft/KeyValueStateMachine.java`, `KvOp.java` sind im Repo nicht mehr vorhanden; eingecheckte Logs gepruned und in `.gitignore`. `infra/perf/baselines.json` bleibt unter `infra/perf/` (vom Test-Harness und perf-Workflow konsumiert; Move blieb aus).

### B.2 READMEs einziehen (~1 d) — ✅ **shipped**

Top-Level-Dirs ohne README → je ein 8–15-Zeilen-README mit Zweck, Layout, „How to add a new X":

- `java/README.md` — Modul-Hierarchie erklären, Build-Reihenfolge
- `dashboard/README.md` — Setup, dev-server, Storybook-Stories
- `website/README.md` — Astro/Starlight setup, OpenAPI-Sync
- `scripts/README.md` — was jedes Skript tut
- `tools/README.md` — Codegen-Skripte erklären

**Geliefert:** `java/README.md`, `dashboard/README.md`, `website/README.md`, `scripts/README.md`, `tools/README.md`, `installer/README.md` existieren.

### B.3 Gradle-Konventionen vereinheitlichen (~1 d) — ✅ **shipped**

- Alle `build.gradle.kts`-Dateien gegen `build-logic/` Konventionen prüfen (Java 25, Logback-Version, Jackson-Version aus Catalog).
- `libs.versions.toml` durchgehen — orphaned Aliases entfernen.
- `cloud-platform`-BOM auditieren: was wird referenziert, was nicht.

**Geliefert:** `java/build-logic/` Konventions-Plugins (`java21-api`, `java21-compat`, `java25-preview`) ziehen Versionen aus `libs.versions.toml`; `cloud-platform` BOM produktiv. Katalog-Audit zeigt keine orphaned Aliases — alle 30 deklarierten Libs/Plugins werden konsumiert (auch `junit-bom` und `geyser-api` via `versionCatalogs.named("libs").findLibrary(...)` aus `build-logic`).

### B.4 Memory-Einträge aktualisieren (~0.5 d) — ✅ **shipped**

`MEMORY.md` und Einzeleinträge gegen heutigen Stand:
- `project_stats_aggregator.md` — Pfad ist `java/cloud-modules/stats-aggregator/`, nicht `java/cloud-module/...`
- `project_cluster_join_plan.md` — v3-Stand reflektieren

**Geliefert:** `project_stats_aggregator.md` enthält den korrekten Pfad `java/cloud-modules/stats-aggregator/`. `project_cluster_join_plan.md` reflektiert v3-Stand (alle 12 Phasen shipped).

### B.5 Linter & Format-Konvention (~1 d) — ✅ **shipped**

- ✅ `.editorconfig` im Repo-Root (Java/Kotlin 4-space/120-col, Web 2-space, TOML 4-space).
- ✅ Spotless mit `palantirJavaFormat` + custom Import-Order in `prexorcloud.java-common` Konventions-Plugin; CI-Gate via `gradlew spotlessCheck build` (`.github/workflows/ci.yml` java job).
- ✅ Prettier `format`/`format:check`-Skripte in `installer/`, `website/`, `dashboard/`; CI-Gates hart in `ci.yml` (installer) und `website.yml` (website), weich (Warnung) in `ci.yml` (dashboard) bis zum dedizierten Normalize-Pass der 571 Dashboard-Source-Files.
- ✅ Root-Level `.prettierrc.json` + `.prettierignore` als single source of truth; `website/.prettierrc.json` ergänzt `prettier-plugin-astro`.
- ✅ `lefthook.yml` im Repo-Root: `spotlessApply` auf staged Java + `prettier --write` auf staged installer/website + `eslint --fix` auf staged dashboard. Setup via `lefthook install` ist optional und in `CONTRIBUTING.md` dokumentiert; CI bleibt der harte Gate.

**Track-B-Gesamt: ~4 eng-days. Komplett geliefert.**

---

## 4. Track C — Modul-Ökosystem zur Marktreife

**Ziel:** PrexorCloud-Module sind heute schon **konzeptionell besser** als CloudNet/SimpleCloud — aber als Plattform fehlen drei Dinge zur Reife: Registry, Sandboxing, und Lifecycle-UX.

### C.1 Modul-Registry mit signierter Distribution (~10 d) — ⏳ **Backend + CLI (inkl. `upgrade`) + Dashboard-UI shipped; Registry-Hosting offen**

**Geliefert (Backend + CLI):**
- `modules.registries` (Liste von Index-URLs) in `ModulesConfig` — abwärtskompatibel (3-arg-Ctor für Alt-Call-Sites).
- Registry-Index-Modell `RegistryIndex` / `RegistryModuleEntry` (`controller/module/registry/`), forward-kompatibel (`@JsonIgnoreProperties`).
- `ModuleRegistryClient`: aggregiert/searcht alle konfigurierten Registries, `resolve(id, version|latest)` mit numerischem Semver-Vergleich, `download()` mit **sha256-Pin-Verifikation** und Sidecar-Fetch (cosign-bundle/sig). `RegistryFetcher`-Seam (http(s)-only) für Tests.
- **Trust-Modell:** Registry ist nur Discovery, nie Trust-Anchor — zwei unabhängige Gates: sha256 gegen Index + Cosign-/Sig-Verifikation gegen den **eigenen** Trust-Root des Controllers (über den bestehenden `PlatformModuleManager.install`-Pfad, geteilt via `installPreparedModule`).
- **REST:** `GET /api/v1/modules/platform/registry[?q=]` (browse/search, `MODULES_VIEW`) + `POST /api/v1/modules/platform/registry/install {moduleId, version?, registryUrl?}` (`MODULES_MANAGE`). SSRF-Guard: `registryUrl` muss in der konfigurierten Liste sein. „installed/installedVersion"-Hinweis für Update-Indikator.
- **CLI:** `prexorctl module search [query]`, `prexorctl module install <id>[@<version>]` (auto-detektiert lokale Datei vs. Registry-Spec; `--registry` pinnt eine Quelle) und `prexorctl module upgrade <id> | --all` — Convenience über install@latest: liest den Registry-Katalog (der `version`/`installed`/`installedVersion` pro Modul liefert) und (re)installiert die neuere Version pinned auf die exakte Katalog-Version (gleicher verify-Pfad wie install). Up-to-date-Module bleiben unberührt; `--all` upgradet alle installierten mit neuerer Version (Summary + Non-Zero-Exit bei Teil-Fehlern); `--json`-Ausgabe. Entscheidungslogik (`decideUpgrade`/`selectUpgradable`/`parseCatalogEntries`) rein und unit-getestet.
- **Tests:** `ModuleRegistryClientTest` (8), `ModulesConfigRegistriesTest` (2), CLI `module_registry_install_test.go` (Detection + Spec-Parsing), `module_upgrade_test.go` (3: Katalog-Parsing, Upgrade-Klassifikation, `--all`-Auswahl).

**Dashboard-UI (geliefert):** Seite `/modules/registry` (`dashboard/app/pages/modules/registry.vue`) — Browse-Grid aus den konfigurierten Registries, Suche, Signed/Unsigned-Indikator, Install-Button mit Per-Eintrag-Loading, „Update verfügbar"-Badge (vergleicht `installedVersion` ≠ Registry-Version), No-Registries-Empty-State. Store-Methoden `fetchRegistryCatalog`/`installFromRegistry` (`stores/modules.ts`), Nav-Eintrag unter Configuration (perm `modules.view`), i18n en/de. Tests in `stores/__tests__/modules.test.ts`.

**Offen (Follow-up):** First-Party-Registry `registry.prexorcloud.dev` (Hosting). (ADR-Eintrag geliefert: ADR 31 in `decisions.md`.)

---

**Problem heute:** Module installiert man, indem man eine signierte JAR per REST hochlädt. Das skaliert nicht — Nutzer brauchen einen Discovery-Mechanismus.

**Lösung:** Eigene Modul-Registry — wir bauen *keine* npm-Klon, sondern einen schlanken Index:

- **Backend:** Statisches JSON-Index-File pro Registry (gehostet auf GitHub Pages oder S3): Liste mit `moduleId`, `version`, `jarUrl`, `sha256`, `cosignBundleUrl`, `manifestUrl`, `compatibleControllerVersions`, `tags`, `readme`.
- **First-Party-Registry:** `registry.prexorcloud.dev` mit den 5 First-Party-Modulen. Erweiterbar.
- **REST:** `POST /api/v1/modules/install-from-registry {registryUrl, moduleId, version}` → Controller lädt JAR, verifiziert Signatur gegen registrierte Trust-Roots, installiert.
- **Custom Registries:** Operator kann zusätzliche Registry-URLs konfigurieren (in `clusterConfig.moduleRegistries`).
- **Dashboard-UI:** Browse-View, Install-Button, „Updates verfügbar"-Indikator.
- **CLI:** `prexorctl module search`, `prexorctl module install <id>@<version>`, `prexorctl module upgrade`.

**Differenzierung:** Niemand sonst in der MC-Cloud-Welt hat einen signierten Modul-Index. Das wird ein USP.

**Risiko:** Wir wollen *nicht* Plugin-Manager-Hell wie Bukkit-Plugins. Strikt: nur signierte Module aus konfigurierten Trust-Roots, fail-closed-by-default.

### C.2 Modul-Sandboxing & Resource-Limits (~10 d)

**Problem heute:** Module laufen als normale Threads im Controller-Prozess, mit `URLClassLoader`-Isolation. Ein bösartiges oder verbuggtes Modul kann den Controller killen (OOM, Endless-Loop, File-System-Abuse).

**Lösung in drei Stufen:**

1. **Resource-Tracking** (~3 d) — ✅ **shipped**: Jedes Modul bekommt seinen eigenen benannten `ScheduledExecutorService` (`module-<id>-sched-N`) statt des bisherigen geteilten 2-Thread-Pools — der `ModuleResourceTracker` (`controller/module/resource/`) wird in `wireProductionModuleContext` verdrahtet und in den `ModuleContext` jedes Moduls gegeben. Periodischer Sampler: CPU-Time via `ThreadMXBean.getThreadCpuTime()`, Allocation via `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()` (degradiert auf 0 wenn nicht unterstützt), Live-Thread-Count. Tote Threads werden in einen „retired"-Akkumulator gefoldet → monotone Totals. Reconcile-on-Sample fährt Scheduler deinstallierter Module herunter. REST `GET /api/v1/modules/platform/{moduleId}/resources`. Tests: `ModuleResourceTrackerTest`. **Bewusst verschoben:** Heap-Footprint/OQL-Sampling und Micrometer-Gauges → zusammen mit Stufe 2 (dort lebt die `quota.exceeded`-Metrik).
2. **Soft-Limits** (~3 d) — ✅ **shipped**: Pro-Modul-Quota unter `modules.quotas.<id>` in `controller.yml` (`ModuleQuota`-Record: `maxCpuMillisPerMinute`, `maxAllocatedMbPerMinute`, `maxThreads`; jede `0` = unlimitiert). Der `ModuleQuotaEnforcer` (`controller/module/resource/`) tickt im Minutentakt, differenziert die kumulativen `ModuleResourceTracker.Snapshot`-Totals aus Stufe 1 zu Per-Minute-Raten und vergleicht gegen die Quota. Überschreitung → WARN (nur auf der steigenden Flanke, kein Log-Spam) + Counter `prexorcloud.module.quota.exceeded{module,resource}`. Rein **advisory** — nichts wird gedrosselt/gekillt (das ist Stufe 3). Das `GET /…/{moduleId}/resources`-REST liefert jetzt zusätzlich `quota` + `quotaEvaluation` (Raten + Breach-Flags). Wiring in `bootPlatformModules` (nach dem Context-Factory, sodass die `MetricsCollector`-Senke steht). Tests: `ModuleQuotaEnforcerTest` (7). **Abweichung vom Plan-Sketch ehrlich:** die dritte Dimension ist `maxThreads`, nicht `maxOpenFiles` — Stufe 1 sampelt Live-Threads, nicht File-Deskriptoren; fd-Limits bräuchten extra Sampling und sind deferred.
3. **Hard-Isolation** (~4 d): Optional: Modul in separatem JVM-Prozess starten, Kommunikation über gRPC-IPC. Aktivierbar per `clusterConfig.modules.<id>.isolation = process`. Erlaubt OS-Level-cgroups/quotas.

**Pragmatik:** Hard-Isolation ist optional, default bleibt In-Process. Nur kritische Module (oder welche aus untrusted-Registries) werden isoliert.

### C.3 Lifecycle-UX-Polish (~3 d) — ✅ **shipped** (Health-Checks Backend + Dashboard, Hot-Reload bereits vorhanden, Dependency-Resolution-UI)

- **Dependency-Resolution-UI** ✅ **shipped**: Die Registry-Index-Einträge tragen jetzt ein `provides`-Feld (`RegistryModuleEntry.Capability{id,version}`, forward-kompatibel via `@JsonIgnoreProperties`; 9-arg-Compat-Ctor für Alt-Call-Sites), das `GET …/registry` mitliefert. Auf der Module-Seite zeigt jeder unaufgelöste `requires`-Eintrag jetzt — falls ein **nicht installiertes** Katalog-Modul diese Capability `provides` — einen „<moduleId> provides this / Install"-Button (`providerFor()` + `installDependency()` in `pages/modules/index.vue`, lädt via bestehendem `installFromRegistry`). Best-effort-Matching per Capability-ID; die echte Versions-Kompatibilität entscheidet weiterhin der Controller-Resolver nach Install. Degradiert sauber: ohne `provides`-Daten im Index keine Suggestion. Tests: `ModuleRegistryClientTest#provides…` (2).
- **Hot-Reload:** Modul-Update ohne Controller-Restart. — **bereits vorhanden** über den `reloadable: true`-Pfad: `PlatformModule#onReload` + `ModuleLifecycleManager.reload()` (fast `ACTIVE → RELOADING → ACTIVE`, kein Stop/Unload der Vorgänger-Instanz). State-Handoff macht das Modul selbst in `onReload`; ein separates `PlatformModuleStateStore`-Interface wurde dafür bewusst nicht gebaut.
- **Health-Checks:** ✅ **shipped (Backend)** — optionale `default ModuleHealth healthCheck()` auf `PlatformModule` (cloud-api; `HEALTHY/DEGRADED/UNHEALTHY/UNKNOWN` + Detail, Default `UNKNOWN`). `ModuleLifecycleManager.pollHealth()` ruft die Probe für jedes `ACTIVE`-Modul **außerhalb des Lifecycle-Locks** auf (langsamer Check blockiert keine Installs; Wurf → `UNHEALTHY`). Der `ModuleHealthMonitor` (`controller/module/health/`) hält das jüngste Ergebnis pro Modul (Module, die aus dem Poll fallen, werden gedroppt — keine stale Health). Eigener Poller-Executor `module-health-monitor` (Intervall `max(5s, scheduler.evaluationIntervalSeconds)`), getrennt vom Reconciler. REST `GET /…/{moduleId}/health`; Metrik `prexorcloud.module.health{status}` (Gauge pro Status). Referenz-Override im `example`-Modul. Tests: `ModuleHealthMonitorTest` (4), `ModuleLifecycleManagerTest#pollHealth…` (2). **Dashboard ✅ shipped:** Health-Statuspunkt (grün/gelb/rot, UNKNOWN unterdrückt) auf jeder Modul-Karte (`pages/modules/index.vue`) + Resources/Quota-Block (Live-Threads, CPU- & MB/min aus `quotaEvaluation`, „Quota exceeded"-Badge bei Breach). Store-Actions `fetchModuleHealth`/`fetchModuleResources`/`fetchModuleDiagnostics` (`stores/modules.ts`, nur ACTIVE-Module gepollt), Typen in `types/api.ts`, Tests in `stores/__tests__/modules.test.ts` (4).

### C.4 Module-Scaffolder im CLI (~2 d) — ✅ **shipped**

```bash
prexorctl module scaffold my-cool-module \
  --capabilities prexor.smoke@1.0.0 \
  --requires prexor.player.journey@'[1.0,2.0)' \
  --mc-plugin paper,velocity \
  --no-frontend
```

**Geliefert:**
- `prexorctl module new` (mit `scaffold` als Cobra-Alias) erzeugt Modul-Skelette unter `java/cloud-modules/<name>/` aus dem `example/`-Template; vor diesem Commit war das Verzeichnislayout im Scaffolder noch das Pre-Track-B `cloud-module/cloud-module-<name>/` und damit **broken**.
- Composable non-interactive Flags: `--capabilities`, `--requires` (beide `StringArray`, damit Version-Ranges mit Komma — z.B. `[1.0,2.0)` — durchgehen), `--mc-plugin` (Alias für `--targets`), `--no-rest`, `--no-mongo`, `--no-frontend`, `--no-plugin`. Die `--no-rest`/`--no-mongo`-Flags fließen in die Wizard-Spec, deren Strip-Out für Storage/REST noch nicht implementiert ist (`scaffold.go` warnt, Template-Defaults bleiben).
- GitHub-Actions-Workflow-Template (`.github/workflows/build.yml`) im `example/`-Modul; cosign-keyless via OIDC, signiert JAR und published Signature-Bundle als Artefakt. Liegt unter `java/cloud-modules/<name>/.github/` — vom Monorepo-CI nicht gescannt (GH Actions scannt nur Repo-Root-`.github/workflows/`), aktiviert sich erst wenn ein Contributor den Modul-Subtree als eigenes Repo extrahiert.
- Pre-Link-Gate des Root-Kommandos (`prexorctl` ohne Cluster-Kontext) kennt jetzt `Annotations["local-only"]="true"`. `module new`/`module scaffold` ist damit ohne `prexorctl login` benutzbar — passt zu dem von der Plan-Aussage „5-Minuten-Hürde" geforderten First-Run-UX.
- Tests: `cli/cmd/module_scaffold_flags_test.go` lockt Version-Range-Parsing, `--no-frontend`/`--no-plugin`-Semantik und Default-Versions/Ranges fest. Scaffold-Suite (`internal/scaffold/`) blieb intakt, Paths-Sed durch alle Test-Fixtures gezogen.

**Nachgezogen (2026-06-05):**
- **Scaffolds sind jetzt durchgängig baubar (jede Target-Auswahl).** Der `extensions:`-Block in `module.yaml` **und** die `extensionArtifacts`-Map in `build.gradle.kts` werden in **Lockstep** auf die gewählten Plattformen geprunt (`filterManifestExtensions`/`pruneExtensionsBlock` + `filterBuildGradleArtifacts`/`pruneExtensionArtifacts`, Match über `target:`-Pfadsegment bzw. `:plugin:<target>`). Manifest + Build-Deklaration + `settings.gradle.kts`-Includes stimmen damit immer überein, sonst failt `preparePlatformManifest` („declared … not referenced").
- **`bedrock-geyser` ist jetzt ein erstklassiges Scaffolder-Target.** Das `example` zeigt alle **vier** Plattformen (inkl. `plugin/bedrock-geyser`), aber `AllTargets`/`patchSettings` kannten nur drei — der Scaffolder konnte also **gar kein** Bedrock-Modul erzeugen und ließ den Bedrock-Eintrag unverdrahtet. Beide kennen jetzt `bedrock-geyser`. Default-Scaffolds spiegeln das `example` (4 konsistente Plattformen), und die Auswahl kann Bedrock enthalten. End-to-end verifiziert: Default (4), `paper,folia,velocity` (3) und `velocity,bedrock-geyser` (2) bestehen alle `preparePlatformManifest`.
- **`--no-rest`-Strip-Out geliefert:** löscht `rest/` (main + test) und entfernt den `onRegisterRoutes`-Override (no-op-Default auf `PlatformModule`) + `.rest.`-Imports aus dem Entrypoint. Verifiziert durch Scaffold des echten `example` + `gradle compileJava/compileTestJava`.
- Tests: `TestPruneExtensionsBlock`, `TestPruneExtensionArtifacts`, `TestStripOnRegisterRoutes`, `TestGenerateStripsRest`, erweiterte `TestGenerateSelectiveTargets`/`TestGenerateAllTargetsByDefault`.

**Bewusst out of scope (eigenes follow-up):** `--no-mongo`-Strip-Out — die `repository`→`queryService`→`capabilityHandles`→`healthCheck`-Kette des `example`-Moduls ist zu verzahnt für sichere String-Surgery; braucht eine Template-Restrukturierung. (`--no-plugin` = WithPlugin=false strippt heute die Plugin-Extensions noch nicht vollständig — eigener kleiner Pass.)

### C.5 Erweiterung First-Party-Module (~3 d) — ⏳ **teilweise shipped**

Konkrete Module, die heute fehlen und Differenzierung schaffen:
- `cloud-module-discord-bridge` — ✅ **shipped (Outbound-Embed-Bridge)**: `java/cloud-modules/discord-bridge/`, abonniert dieselben Cloud-Events wie `webhook-alerts` und postet sie als Discord-Embeds (farb-kodiert nach Severity, strukturierte Fields) an konfigurierte Discord-Incoming-Webhooks (`DiscordTarget`-Repository über Mongo-Storage, Event-Filter pro Target, optionaler Username-Override). Differenzierung zu `webhook-alerts`: Discord-spezifisches Embed-Format statt generischem JSON. Kern ist der **reine** Formatter `DiscordEmbeds` (timestamp-injiziert → voll unit-getestet): `DiscordEmbedsTest` (4), `DiscordTargetTest` (2). **Offen (eigener Pass):** die bidirektionale Hälfte — Discord-Slash-Commands + MC-Chat ↔ Discord — braucht eine Gateway-Bot-Verbindung (JDA); dieses Modul bleibt bewusst ein zustandsloser Webhook-Poster.
- `cloud-module-grafana-bridge` — Read-only Grafana-Datenquelle — **gestrichen** per ADR 10 (no Grafana dashboard pack)
- `cloud-module-backup-orchestrator` — ✅ **shipped (config-Snapshot-Scope)**

**Backup-orchestrator (geliefert):**
- Neues Capability `InstanceFileAccess` (`prexor.instance.files`) in `cloud-api` — modules-public Interface über die bisher controller-internen `InstanceFileTreeService` + `InstanceFileContentService`. Built-in Handle wird in `PrexorCloudBootstrap.registerBuiltinCapabilities` registriert, bevor `loadStoredModules()` läuft (sodass abhängige Module schon beim Erststart resolven).
- Modul `cloud-modules/backup-orchestrator/`: walk → read → `tar.gz` in `<PREXORCLOUD_BACKUP_DIR | /var/lib/prexorcloud/snapshots>/<instance>/<timestamp>.tar.gz`, Metadaten in der eigenen Mongo-Storage. REST `/api/v1/modules/backup-orchestrator/snapshots` (GET/POST/{id} GET/DELETE).
- **Scope-Grenze ehrlich dokumentiert:** Daemon-RPC `ReadInstanceFile` deckelt Reads bei 64 KiB pro File und encodet als UTF-8. Brauchbar für `server.properties`, `ops.json`, Plugin-YAML, Whitelist/Banlist — **nicht** für Region-Files / NBT / Welt-Daten. Echte Welt-Snapshots brauchen daemon-side tar (`SnapshotInstance` proto-Erweiterung); ist als follow-up im Service-Javadoc und in der Module-`scope`-Sektion vermerkt. Truncierte Reads landen mit voller Pfadliste in `SnapshotMetadata.truncatedFiles`.
- ✅ **Periodische Scheduler-Anbindung (Folge-Commit) geliefert:** `BackupSchedule` (env-getrieben, reiner totaler Parser) + `scheduleAtFixedRate` in `onStart`. Opt-in über `PREXORCLOUD_BACKUP_INTERVAL_MINUTES` (>0) **und** `PREXORCLOUD_BACKUP_TARGETS` (Komma-Liste `nodeId/group/instanceId`-Triples; `PREXORCLOUD_BACKUP_INITIAL_DELAY_MINUTES` default 1). Fehlt eins → REST-only wie zuvor. Modul-Kontext kann Instanzen nicht enumerieren, daher **explizite** Target-Liste statt Discovery (passt für persistente Lobbys/Hubs). Per-Target-Fehler werden geloggt + übersprungen (eine unerreichbare Instanz killt den Task nicht). Periodischer Pfad nutzt das Default-Pattern-Set; Per-Target-Filter bleiben Follow-up. Tests: `BackupScheduleTest` (6). **Scope-Grenze unverändert:** weiterhin Config-Files only (64-KiB-Daemon-Read-Cap), keine Welt-Daten.

**Track-C-Gesamt: ~28 eng-days. Beginnt nach Track A. Hängt teilweise von Raft ab (Trust-Roots in `clusterFiles`).**

---

## 5. Track D — Observability Gen-2 (OpenTelemetry & Tracing)

**Ziel:** Verteilte Traces über Controller → Daemon → MC-Plugin. Heute fehlt das komplett.

### D.1 OpenTelemetry-SDK einziehen (~3 d) — ⏳ **Foundation + HTTP- + MongoDB- + Redis-Instrumentation shipped; gRPC-Auto-Instrumentation offen**

- ✅ `io.opentelemetry:opentelemetry-bom` (1.45.0) + `-api`/`-sdk`/`-exporter-otlp` (okhttp-Sender, kein gRPC-Konflikt) im `libs.versions.toml` + `cloud-controller`-Build; `-sdk-testing` als testImplementation.
- ✅ `TelemetryConfig` (`controller.yml` → `telemetry`: `enabled`/`otlpEndpoint`/`serviceName`/`samplerRatio`, Ratio auf `[0,1]` geklammert). Top-level statt `clusterConfig.telemetry`, da Tracing keine Raft-replizierte State ist (analog `modules.signing`).
- ✅ `Telemetry` (`controller/observability/telemetry/`): baut bei `enabled` ein `OpenTelemetrySdk` (BatchSpanProcessor → OTLP/gRPC, `parentBased(traceIdRatio)`-Sampler, `service.name`-Resource, W3C-Propagation); sonst `OpenTelemetry.noop()` → **Null-Overhead by default**. `tracer()`/`flush()`/`close()` (Flush+Shutdown im Shutdown-Hook, vor dem Quota-Enforcer). Exporter-Seam `fromExporter(...)` für Tests. Verdrahtet in `PrexorCloudBootstrap` → `controller.telemetry()`.
- ✅ Fallback Jaeger / Tempo / Honeycomb / Datadog via OTLP. Tests: `TelemetryConfigTest` (3), `TelemetryTest` (2, inkl. `InMemorySpanExporter`-Span-Roundtrip).
- ✅ **Javalin-HTTP-Instrumentation** (manuell, ohne Javaagent): `HttpServerTracing` (`controller/observability/telemetry/`) öffnet pro Request eine SERVER-Span (`HTTP <method>`, Attribute `http.request.method`/`url.path`/`http.response.status_code`, 5xx → ERROR), **extrahiert eingehenden W3C-`traceparent`** (inbound-Hälfte von D.3 — externe Traces / Dashboard laufen durch) und macht die Span für den Request-Thread current, sodass Domain-Spans (`auth.login` …) darunter nisten. Verdrahtet im `RestServer`-before/after-Filter, nur wenn `telemetry.enabled`; SSE/Stream-Pfade (`*stream*`, `/console`) werden übersprungen (deren Span würde nie enden). Tests: `HttpServerTracingTest` (2, inkl. Inbound-Trace-Continuation + 5xx-Status).
- ✅ **MongoDB-Instrumentation** (manuell, ohne Javaagent): `MongoCommandTracer` (`controller/observability/telemetry/`) ist ein `com.mongodb.event.CommandListener`, der pro Command eine CLIENT-Span (`mongodb <op> <collection>`, Attribute `db.system`/`db.operation`/`db.namespace`/`db.collection.name`) öffnet, geparentet an `Context.current()` — DB-Calls nisten so unter der HTTP-/Domain-Span, die sie auslöste. Registriert auf den `MongoClientSettings` zur Client-Bauzeit (vor dem Telemetry-SDK in Bootstrap), inert bis `attachTracer()` nach dem Telemetry-Boot läuft und **nur** wenn `telemetry.enabled` → ein volatile-Read pro Command wenn aus, **kein** Span-Overhead. Korrelation über `requestId` (synchroner Treiber: ein `succeeded`/`failed` pro `started` auf demselben Thread). Monitoring-Chatter (`hello`/`ping`/`endSessions` …) wird übersprungen. Tests: `MongoCommandTracerTest` (5, inkl. ERROR-Span + Disabled-Pfad).
- ✅ **Redis/Lettuce-Instrumentation** (manuell, ohne Javaagent): `RedisTracing` (`controller/observability/telemetry/`) adaptiert Lettuces **native** Tracing-SPI (`io.lettuce.core.tracing.Tracing`) auf OTel — pro Command eine CLIENT-Span (`redis <op>`, Attribute `db.system`/`db.operation`), geparentet an `Context.current()`. Auf `ClientResources` **nur** installiert, wenn `telemetry.enabled` (sonst wird der Client ohne den Adapter gebaut → identisch zu vorher; Lettuce gated alles Tracing über `isEnabled()`). Command-Args sind aus den Span-Tags ausgeschlossen (`includeCommandArgsInSpanTags()=false`), damit Redis-Werte (Tokens, Lease-Payloads, Revocation-Einträge) nicht in Traces lecken. Telemetry wird dafür direkt nach dem Auflösen der effektiven Cluster-Config gebaut (statt später), sodass Redis zur Connection-Zeit instrumentiert werden kann. Tests: `RedisTracingTest` (3, inkl. ERROR-Span + Args-Exclusion).
- ⏳ **Offen:** Auto-Instrumentation für gRPC (braucht den OTel-Javaagent; der Controller↔Daemon-gRPC ist zudem ein Long-lived-Bidi-Stream, für den ein generischer ServerInterceptor keine sinnvollen Per-Command-Spans liefert — der Trace-Context reist bereits im Payload, siehe D.3) — bewusst ausgeklammert.

### D.2 Eigene Spans für Domain-Flows (~3 d) — ✅ **shipped**

Wiederverwendbarer Helper `Spans.call/run` (`controller/observability/telemetry/Spans.java`): startet+aktiviert eine Span, setzt `ERROR`+`recordException` bei `RuntimeException` (rethrow), beendet immer. Getestet via `SpansTest` (3, `InMemorySpanExporter`). Tracer wird in jede Komponente per `setTracer()` aus `controller.telemetry().tracer()` injiziert (Default no-op → keine Konstruktor-Änderung, keine Test-Brüche, Null-Overhead wenn Telemetry aus). Lange Methoden via Extract-and-wrap (`doXxx`-Body unverändert) → minimaler Diff.

- ✅ Scheduler-Tick (`scheduler.tick`) — umschließt `Scheduler.evaluate()`, Attribut `scheduler.groups_evaluated`, Status OK/ERROR (inline, vor dem Helper).
- ✅ Instance-Placement (`placement.evaluate` = `placeResolvedInstance`, `placement.dispatch` = `dispatchStartMessage`) — `InstancePlacementCoordinator`; dispatch nistet unter evaluate, wenn evaluate dispatcht.
- ✅ Deployment-Reconcile (`deployment.reconcile` = `rollingRestart(_, stepGuard)`) — `DeploymentReconciler`.
- ✅ Auth-Login (`auth.login` = `AuthManager.login`) — Tracer nach Telemetry-Bau über `controller.authManager().setTracer()` injiziert (AuthManager wird vor dem Controller gebaut).
- ✅ Raft-`apply()` (`raft.apply`, Attribut `raft.entry_type`) — `ClusterControlStateMachine.applyTransaction` umschließt jeden committed Entry; Tracer lazy via `ClusterControlService.attachTracer()` (Catch-up-Applies vor dem Telemetry-Bau bleiben no-op). Status ERROR wenn `!reply.ok()`.
- ✅ `auth.token-verify` (per-Request im `JwtAuthMiddleware`) — wrappt JWT-Validierung + Revocation-Check. Auth-Fehlschläge sind ein Client-Outcome (401), kein Server-Fault, daher als `auth.outcome`-Attribut (`valid`/`invalid`/`revoked`) statt ERROR-Status — analog zur HTTP-Server-Span, die nur 5xx als ERROR markiert. Damit das nesten kann, wurde die HTTP-Server-Span-`before`-Registrierung an den **Anfang** der Filter-Kette gezogen (vorher als letzter `before` registriert, lief also *nach* dem Auth-Filter): die Server-Span umschließt jetzt CORS/Subnet/Rate-Limit/Auth, OPTIONS-Preflight wird übersprungen (CORS bleibt effektiv zuerst). Tests: `JwtAuthMiddlewareTracingTest` (3, inkl. Nesting-Assertion).

### D.3 Trace-Propagation Controller → Daemon → MC-Plugin (~2 d) — ✅ **Controller→Daemon- + MC-Plugin→Controller-Hop shipped; Dashboard-Deep-Link shipped**

- ✅ **Daemon-OTel-Foundation:** `cloud-daemon` hatte bisher kein OTel — Voraussetzung für die Daemon-Hälfte. Analog zur Controller-D.1-Foundation (Commit 61255d0) gebaut: OTel-Deps (`bom`/`api`/`sdk`/`exporter-otlp`, `sdk-testing` als testImpl), `TelemetryDaemonConfig` (`daemon/config/`, top-level `telemetry`-Block in `daemon.yml`, serviceName `prexorcloud-daemon`, samplerRatio geklammert), `DaemonTelemetry` (`daemon/observability/`): baut bei `enabled` ein `OpenTelemetrySdk` (BatchSpanProcessor → OTLP, `parentBased(traceIdRatio)`-Sampler, W3C-Propagation, `service.name` + `node.id`-Resource-Attribut); sonst `OpenTelemetry.noop()` → **Null-Overhead by default**. Verdrahtet in `PrexorDaemon.start()` (früh gebaut), Flush+Close im Shutdown. Tests: `TelemetryDaemonConfigTest` (3), `DaemonTelemetryTest` (2, inkl. `node.id`-Resource + InMemory-Span-Roundtrip).
- ✅ **gRPC-Trace-Context Controller → Daemon (W3C-Traceparent):** Additives Feld `ControllerMessage.traceparent = 17` (außerhalb der `oneof`, kein PROTOCOL_VERSION-Bump; `proto-contracts.sha256` aktualisiert). Da der Controller gRPC-*Server* ist und Commands über den Long-lived-Response-Stream pusht, reist der Trace-Context im Payload, nicht in Metadata. Controller stempelt am einzigen Outbound-Chokepoint `NodeSession.send()` via `TraceContextWire.currentTraceparent()` (stateless `W3CTraceContextPropagator`, kein SDK-Ref → identisch ob Telemetry an/aus; ohne aktive Span = leer, nichts wird gestempelt). Daemon extrahiert am Inbound-Chokepoint `MessageDispatcher.dispatch()` und umschließt die synchrone Dispatch in einer `daemon.command`-CONSUMER-Span (`rpc.command`-Attribut = PayloadCase), die die Controller-Trace fortsetzt — **gegated auf nicht-leeren traceparent**, sodass untracete Heartbeats/Pings keine Span-Noise erzeugen. **Scope-Grenze ehrlich:** mehrere Handler offloaden auf Virtual-Threads; die Span endet beim synchronen Hand-off, deckt also Command-Empfang/Annahme, nicht die Downstream-Async-Arbeit. Tests: `TraceContextWireTest` (2), `MessageDispatcherTracingTest` (2, inkl. Trace-Continuation + No-Span-ohne-traceparent).
- ✅ **Plugin-Token-Calls tragen Trace-Context im Header (SDK-frei):** `W3CTraceparent` (`cloud-plugins:internal`) mintet pro `get`/`postAsync` einen frischen, gesampleten W3C-`traceparent`; die Controller-seitige Inbound-Extraktion (`HttpServerTracing`) macht daraus eine Server-Span, sodass Player-Join/Metrics-Reports end-to-end traceable sind (Plugin-Aktion → Controller → Daemon). Bewusst **kein** OTel-SDK im Bukkit/Velocity-Classloader (schwergewichtig, classloader-fragil) — das Plugin ist Trace-Originator und trifft die Head-Sampling-Entscheidung (flags `01`). Inert wenn Controller-Telemetry aus (Header wird ignoriert, kein Collector-Flood). Tests: `W3CTraceparentTest` (3). **Scope ehrlich:** die Plugin-Span selbst wird nicht aufgezeichnet (kein SDK) — nur die Trace-ID originiert im Plugin; per-Plugin-Sampling-Ratio ist ein möglicher Follow-up.
- ✅ Dashboard „Trace ansehen"-Deep-Link — **Backend:** optionales `telemetry.traceUiTemplate` (URL mit `{traceId}`-Platzhalter, z.B. Jaeger `http://localhost:16686/trace/{traceId}`), via `GET /api/v1/system/settings` (`tracingEnabled` + `traceUiTemplate`) an die Dashboard exponiert. Jede getracete Response trägt den `X-Trace-Id`-Header (gesetzt im RestServer-after-Filter aus der Server-Span; CORS-`Access-Control-Expose-Headers` ergänzt, damit Cross-Origin-Dashboard-JS ihn lesen darf). **Frontend:** `recordTraceId`/`lastTraceId` (`app/lib/trace-context.ts`) + ein `traceMiddleware` im `useApiClient` erfassen den Header jeder Response; der `system`-Store leitet `tracingEnabled`/`traceUrl(id)`/`lastTraceUrl` aus den Settings + der zuletzt erfassten Trace-ID ab; die Observability-System-Seite zeigt eine „Tracing"-Sektion mit „Letzten Trace ansehen"-Deep-Link (i18n en/de). Tests: Backend `TelemetryConfigTest` (+1), `HttpServerTracingTest#exposesTraceId`, `SystemDtoMapperTest` (+2); Frontend `trace-context.test.ts` (4), `system.test.ts` (+2), `useApiClient.test.ts` (+2). **Scope ehrlich:** per-Action-Trace (die gerade ausgelöste Operation), nicht per-Instance-Historie — letzteres bräuchte Trace-ID-Persistenz pro Instanz-Operation.

**Track-D-Gesamt: ~8 eng-days. Unabhängig von Track A, kann parallel.**

**Bewusst nicht in Scope:** Logs-Aggregation (Loki/ELK) — das ist Ops-Konfiguration, nicht App-Code. Wir publishen strukturierte Logs (JSON über Logstash-Layout), Operator kann anschließen.

---

## 6. Track E — Frontend & Design-System Konsolidierung

> **Detailplan:** [`track-e-plan.md`](./track-e-plan.md) — phasiertes Vor-Start-Scoping (2026-06-05). Kernkorrektur: ADR 32 hat das gemeinsame Component-Package abgewählt, daher ist Track E **Tokens + Drift-Guard + A11y**, nicht Shared-Code; der messbare Hebel ist die A11y-Runtime-Härtung (E-P1). Reframte Schätzung ~13–14 d statt ~20 d.

**Ziel:** Heute existieren drei Vue-/JS-Stacks (Nuxt 4 für Dashboard, Vite-Vue für Installer, Astro + Vue-Islands für Website). Plus ein Design-System-Verzeichnis, das von keinem davon konsumiert wird. Konsolidieren.

### E.1 Design-System operationalisieren (~5 d)

- **Token-Pipeline:** ✅ `design-system/tokens.json` ist single source of truth.
  Statt Style-Dictionary ein dependency-freier Node-Generator (`build-tokens.mjs`)
  — die bespoke Token-Form (geschachtelte dark/light-Semantik + ANSI) bräuchte
  ohnehin Custom-Formate, und ein cosign/Rekor-gehärtetes Projekt spart sich die
  Toolchain in der Supply-Chain. Generiert:
  - `design-system/dist/tokens.css` — CSS-Variablen (`:root` / `.dark` / `.light`)
  - `design-system/dist/tokens.ts` — TypeScript-Constants (für JS-Logik in den Frontends)
  - `design-system/dist/tokens.json` — normalisierter Build-Output (für ggf. Figma-Sync)
  CI-Job `design-system` erzwingt Parität (`tokens.json` ↔ `colors_and_type.css`)
  und dist-Frische. Dabei zwei echte Drifts gefixt: Light-Primary `#0891b2`→`#0c8aa8`
  (cyan-8, wie alle Surfaces), Light-`glass-border-hover` `#a8a59c`→`#aba8a0` (sand-8).
- **NPM-Workspace:** _(entschieden gegen — siehe ADR 32.)_ Statt Surfaces auf ein `@prexorcloud/design-system`-Package zu migrieren, bleiben sie ge-mirror-t und werden per CI-Drift-Guard an den Canon gepinnt (`design-system/__tests__/surface-drift.test.mjs`). Das löste das eigentliche Problem (stilles Drift) zu einem Bruchteil der Kosten. Package-Stub + `dist/` stehen falls ein echter Consumer-Bedarf entsteht; die Mermaid-Palette konsumiert `dist/` bereits.
- **Komponenten-Library:** ✅ _Geliefert als **token-only CSS-Layer** (`design-system/components.css`), bewusst **keine** Vue/Radix-Runtime — formal entschieden in **ADR 33**._ Deckt jetzt button/input/**select**/**checkbox**/**switch**/badge/card/**table**/**tooltip** (E-P4, 2026-06-05: select/checkbox/switch/table/tooltip ergänzt). Dieselbe Referenz-Mechanik wie `colors_and_type.css` (Surfaces mirror-n); `__tests__/components.test.mjs` erzwingt „Styling nur über Tokens" (kein hardcoded Farbwert, keine dangling Token-Refs) — 16/16 grün. Echte Vue-Headless-Komponenten bleiben an die Workspace-Entscheidung (ADR 32/33 Boundary) gekoppelt, falls je ein echter Bedarf entsteht.
- **Histoire-Stories:** ✅ _bewusst gestrichen_ (ADR 33) — kein Component-Runtime, also keine Story-App; das zöge Vue+Histoire in das dependency-freie DS-Package. Surfaces stylen über den CSS-Layer + Tokens.

### E.2 Dashboard auf konsolidierten Stack (~10 d)

- **Optional, bewusst:** Bleibt auf Nuxt 4, importiert Design-System-Komponenten statt eigener Implementierungen.
- Komponenten-Migration: Buttons, Inputs, Cards überall durch Design-System-Versionen ersetzen.
- A11y-Pass: ARIA-Labels, Keyboard-Navigation, Color-Contrast-Audit (axe-core in CI).
- i18n-Foundation: ✅ **bereits vorhanden** (`@nuxtjs/i18n`, `i18n/locales/{en,de}.json`, 646 Keys). ⏳ Coverage-Lücke geschlossen: 40 untranslatete `de`-Keys (das komplette „shares"-Feature — Seite + Store-Messages) nachgezogen, und ein **harter CI-Gate** `pnpm i18n:check` (`i18n/check-locale-parity.mjs`, zero-dep) erzwingt jetzt identische Key-Sets en↔de, damit kein String mehr nur in einer Sprache landet. Offen: Voll-Audit auf noch hardcodete UI-Strings (vgl. Track H.3).

### E.3 Installer-Wizard auf gleichen Stack (~3 d)

- Vue 3 + Vite bleibt (Single-File-HTML-Constraint), aber Components aus `@prexorcloud/design-system` importieren.
- Vermeidet doppelte Button/Input-Implementationen.

### E.4 Website-Theme aus Design-System ziehen (~2 d) — ✅ **shipped (2026-06-05)**

- ✅ **Starlight-`--sl-color-*`-Block generiert:** `website/scripts/gen-starlight-theme.mjs` mappt die Starlight-Surface-Farben auf die DS-Semantik-Tokens (`dist/tokens.json`) → `src/styles/starlight-theme.generated.css`, via `astro.config` `customCss` nach `starlight.css` geladen. DS-Werte sind Canon; `predev`/`prebuild` + harter Freshness-Guard in `website.yml`; `astro check` clean. Richere Website-only-Tokens (Accent-Ramp/Spacing/`--z-*`) bleiben hand-gepflegt.
- Starlight-Custom-CSS aus `design-system/dist/tokens.css` generieren. _(Jetzt entsperrt: Canon wurde 2026-06-02 auf die Quiet-Studio/Reef-Palette reconciled — b736c50 —, also würde Generieren die Surfaces nicht mehr umfärben. Aber `website/src/styles/tokens.css` ist reicher als das DS-Token-Set (HSL-Tripletts, `--bg`/`--surface`/`--raised`, eigene Type-Scale), daher kein reiner Drop-in — erst das DS-Token-Set angleichen.)_
- ✅ Mermaid-Palette nicht mehr hardcoded: `website/scripts/gen-mermaid-theme.mjs` generiert sie aus `design-system/dist/tokens.json` (predev/prebuild), `mermaid.ts` konsumiert die generierte Datei. CI-Frische-Guard in `website.yml`. Erster Consumer der E.1-Pipeline (ea223ff).

**Track-E-Gesamt: ~20 eng-days. Parallel zu A/B/C möglich (Frontend-Team separat).**

---

## 7. Track F — MC-Plattform-Breite

**Ziel:** Wir unterstützen heute Paper/Folia/Spigot/Velocity/BungeeCord + Bedrock via Geyser-Beispielmodul. Erweitern.

### F.1 First-Class Bedrock-Routing (~7 d) — ⏳ **Spieler-Sichtbarkeit (Edition-Tracking + Dashboard-Split) shipped; Routing + dedizierter Geyser-Proxy offen**

- ✅ **Dashboard zeigt Bedrock-vs-Java-Spieler getrennt.** Der Controller leitet pro Spieler eine `edition` (`java`/`bedrock`) ab — **aus der UUID**: Floodgate vergibt Bedrock-Spielern eine UUID mit Null-High-Bits (`new UUID(0, xuid)`), der kanonische programmatische Bedrock-Check, also **kein** Plugin-/Transport-/Event-Change nötig (`PlayerEdition.detect`, `PlayerInfo.edition` via Compact-Ctor auch für Jackson-Rehydration alter Daten). Surfaced in den Player-REST-DTOs (main + workload) + `PlayerDto`-Schema (+ OpenAPI). Dashboard: `editionCounts`-Getter, „Edition"-Split-Stat-Card, Detail-Sheet-Zeile, Bedrock-Badge auf der `PlayerCard` (i18n en+de). Tests: `PlayerEditionTest`, erweiterte Mapper- + `players`-Store-Tests. **Scope ehrlich:** erkennt Floodgate-Bedrock (UUID-Konvention); Standalone-Geyser ohne Floodgate vergibt Java-UUIDs und ist nicht unterscheidbar → wird als `java` gemeldet.
- ⏳ **Offen:** BedrockProtocol-Adapter im `cloud-controller/network/` — Bedrock-Clients in `NetworkComposition` erstklassig behandeln (Lobby-Gruppen-Ein-/Auschecken, Fallback-Routing). Das ist Routing-**Verhalten** und braucht Laufzeit-Verifikation.
- ⏳ **Offen:** Dedizierter Proxy-Plugin `cloud-plugins:proxy:geyser` (Geyser-Spigot/Standalone-Sidecar).

### F.2 Fabric-Server-Plugin (~5 d) — ⏳ **baubar + runnable Mod-Jar (Deps gebundlet); nur Laufzeit-Verifikation offen**

- ✅ **Neues Modul `cloud-plugins:server:fabric`** (2026-06-07). Loom-gebaute Fabric-**Dedicated-Server-Mod** (Minecraft 1.21.1, `DedicatedServerModInitializer`), die sich beim Controller registriert und Player-Join/-Leave + alle 200 Ticks (~10 s) einen Metrics-Snapshot meldet — über Fabrics Event-API (`ServerLifecycleEvents`/`ServerPlayConnectionEvents`/`ServerTickEvents`) statt Bukkit, aber **denselben plattform-agnostischen `ServerControllerClient` + `InstanceMetricsPayload`** wiederverwendend. **Korrektur zum Plan:** die Server-Plugins sprechen **REST `/api/plugin/*`**, nicht gRPC. `FabricMetricsCollector` liest TPS/MSPT/Player/Worlds/Chunks aus `MinecraftServer`; JVM-Teil ist JMX-identisch zum Bukkit-Collector. **Kompiliert + `remapJar` gegen die echte Fabric-API** (Loom 1.16.3 auf Gradle 9.4, Yarn 1.21.1).
- ⚙️ **Build-Infra:** Loom-Plugin aus dem FabricMC-Maven (in `settings.gradle.kts pluginManagement`); der **Gradle-JVM muss ≥ 21 sein** (das Loom-Plugin lädt in die Daemon-JVM) — CI fährt JDK 25 ✓, lokal das Daemon-JDK auf 21+ setzen. Loom konfiguriert das Fabric-Projekt bei jeder Invocation (cacht aber nach dem ersten MC-Download).
- ✅ **Runtime-Dep-Bundling** (2026-06-07). `server:shared` + transitive Closure (cloud-api, internal, Jackson) werden via Shadow in die Mod-Jar geshadet, dann remapped Loom das Shadow-Ergebnis (`remapJar.inputFile = shadowJar`). `slf4j-api` ist ausgeschlossen (Loader stellt es), Minecraft bleibt extern. Ergebnis: `fabric.jar` (~4,2 MB) mit eigenen Klassen + 250 Cloud- + 1274 Jackson-Klassen, 0 slf4j, 0 Minecraft. → **läuft jetzt standalone in Fabric** (Code-/Packaging-seitig).
- ⏳ **Offen:** (a) Laufzeit-Verifikation auf einem echten Fabric-Server; (b) Spotless ist auf dem Loom-Modul nicht verdrahtet (Code von Hand palantir-konform).
- **Use-Case:** Modded-Server in der Cloud orchestrieren.

### F.3 Forge-Server-Plugin (~3 d)

- Wenn Fabric läuft, Forge mit etwa 70 % Aufwand machbar. Mod-Toolchain ist anders, aber Logik gleich.

**Track-F-Gesamt: ~15 eng-days. Niedrigere Priorität — kann auf v1.3 oder später.**

---

## 8. Track G — Doku & ADR-Vollständigkeit

**Ziel:** Architecture-Decision-Records (ADR) für alle nicht-trivialen Entscheidungen, sodass spätere Maintainer „warum" verstehen.

### G.1 ADR-Register füllen (~3 d) — ✅ **shipped** (Register lebt in `docs/engineering/decisions.md`, 31 ADRs)

Das Register deckt die unten skizzierten Entscheidungen ab (mit abweichender Nummerierung) und hält Reversals ehrlich nach: **ADR 30** dokumentiert die OpenTelemetry-Einführung (Teil-Reversal von ADR 9 „Prometheus only"), **ADR 31** die signierte Modul-Registry als reinen Index (Teil-Relaxierung von ADR 14 „No marketplace, no central index"). Beide Vorgänger-ADRs tragen jetzt einen Supersession-Hinweis. Die ursprüngliche Wunschliste:

- **ADR-001:** Embedded Raft via Apache Ratis (vs. externer Coordinator / Mongo-CAS)
- **ADR-002:** Module-Signing via cosign + Rekor (vs. self-signed)
- **ADR-003:** JVM 25 mit Preview-Features (vs. 21 LTS) — inklusive Risiko-Diskussion
- **ADR-004:** Mongo + Redis statt einer DB (Architektur-Klärung)
- **ADR-005:** gRPC mTLS für Daemon-Verbindung (vs. token-basiert)
- **ADR-006:** Drei Frontends (Dashboard, Installer, Website) — und warum nicht fusioniert
- **ADR-007:** Eigene Modul-Registry (vs. Maven Central / GitHub Releases)
- **ADR-008:** OpenAPI als Single Source of Truth für REST → CLI → SDK

### G.2 Runbooks (~2 d)

`docs/runbooks/` ergänzen:
- `cluster-recovery.md` — Majority-Loss-Recovery — ✅ geliefert als `recover-cluster.md`
- `v1.0-to-v1.1.md` — Migration mit Raft — ✅ geliefert als `upgrade-v1.0-to-v1.1.md`
- `module-trust-root-rotation.md` — ✅ **shipped** (Overlap-Rotation + Emergency-Revocation; restart-required dokumentiert)
- `ca-rotation.md` — ✅ **shipped** (Daemon-mTLS-CA vs. Raft-Cluster-CA getrennt; `rotate-secrets.md` verlinkt jetzt die Deep-Dives)
- `incident-postmortem-template.md` — ✅ geliefert als `incident.md`

**Track-G-Gesamt: ~5 eng-days. Runbook-Lücken geschlossen.**

---

## 9. Track H — Polish, Performance, A11y, i18n

**Ziel:** Letzter Schliff vor v1.3.

### H.1 Performance-Tuning (~4 d) — ⏳ **Audit-Log-Keyset-Pagination shipped; Perf-Trend-Review + Scheduler-Profiling offen**

- Perf-Baseline-Trend-Reports der letzten 60 Tage anschauen, Drift-Hotspots identifizieren. — **offen**
- Mongo-Indices auditieren — die Audit-Log-Queries sind die wahrscheinlichsten Bottlenecks bei großen Clustern.
  - ✅ **Audit-Log `skip(offset)`-Deep-Pagination ersetzt (2026-06-05):** Der in H.2 identifizierte Hotspot war nicht ein fehlender Index, sondern `getAuditLog(limit, offset)` mit `skip(offset)` — offset-proportionaler Scan-and-Discard auf tiefen Seiten. Neu: `StateStore.getAuditLogSeek(cursor, limit)` → `AuditLogPage(entries, nextCursor)`, Keyset/Seek über `Filters.lt("_id", cursor)` + `sort(_id desc)` (ObjectId ist insertion-monoton → „älter als Cursor" = Range-Scan über den primären `_id`-Index, **kein** `skip`, **kein** neuer Index → kein Index-Wildwuchs). Fetch von `limit+1` entscheidet `nextCursor` ohne Extra-Count. REST: `/api/v1/audit?cursor=<token>&pageSize=N` (blank = neueste Seite) liefert `{data, pageSize, nextCursor, total}`; ungültiger Cursor → 400 `BAD_CURSOR`. Der Legacy-`page`/`offset`-Pfad bleibt unverändert (Backward-Compat, dokumentierter OpenAPI-Contract). Dashboard-Audit-Seite migriert auf Cursor-Stack (Prev/Next ohne Backend-`skip`; loose-typed, da `cursor`/`nextCursor` noch nicht im SDK-Snapshot). Tests: Harness `AuditTest` (Seek-Walk deckt jeden Eintrag genau einmal, kein Overlap; Garbage-Cursor→400), Dashboard `audit.test.ts` (+2 Cursor-Stack) / `audit-index.test.ts`. **Nebenbei gefixt:** ein pre-existing Compile-Break im Test-Harness (`TestCluster` baute `DaemonConfig` ohne das von Track D.3 ergänzte `TelemetryDaemonConfig`-Feld) — `new TelemetryDaemonConfig()` (disabled) nachgereicht, sonst kompiliert die Harness-Test-Quelle nicht.
- Scheduler-Tick-Profiling: lässt sich der Tick unter 50 ms p99 halten bei 100 Groups? — **offen**

### H.2 Accessibility-Audit (~3 d) — ⏳ **statischer A11y-Gate + Icon-Control-Naming shipped; Runtime-axe/Contrast/Keyboard offen**

- ✅ **Statischer A11y-Lint (2026-06-03):** `dashboard/scripts/a11y-lint.mjs` (`pnpm a11y:check`), zero-dep, dieselbe Tag/Text/Quote-State-Machine wie der Hardcode-Lint. Prüft zwei statisch entscheidbare WCAG-Defekte, die ein authed-SPA mit Running-App-axe schlecht abdeckt (die meisten Routen liegen hinter Login): `<img>` ohne `alt` (1.1.1) und Icon-only-Controls ohne Accessible Name (4.1.2 — Name aus Text/Interpolation/`aria-label`/`title`/`sr-only`). **Harter CI-Gate** in `ci.yml` neben dem i18n-Gate.
- ✅ **26 Icon-only-Controls benannt:** Alle vom Lint gefundenen namenlosen Buttons/Links (Move-up/down, Remove, Passwort-Show/Hide, Back, Copy …) tragen jetzt ein gebundenes `:aria-label="t('…')"` — geteiltes `common.a11y.*`-Vokabular, en+de, durch den Hardcode-Gate erzwungen (kein hartkodiertes Label). 0 Bilder ohne alt. Keine neuen Typecheck-Fehler (Stash-Vergleich).
- ✅ **Runtime-axe-Job (soft, 2026-06-03):** `ci.yml` serviert das gebaute Dashboard (`pnpm preview`) und läuft `@axe-core/cli` gegen die unauthed-Oberfläche (Login + Root-Shell) — fängt Contrast/Computed-Role-Defekte, die der statische Lint nicht sieht. Soft-Signal (`continue-on-error`), analog zum Website-axe-Job; authed-Routen brauchen ein Backend zum Scannen.
- ⏳ **Offen:** axe/Lighthouse-A11y ≥95 als harter Gate über die authed-Flows (braucht Backend + Test-Login im CI), Color-Contrast-Voll-Audit gegen Design-System-Tokens, Voll-Tastatur-Navigation der Critical-Flows (Group Create, Deploy).
- **Mongo-Index-Audit (H.1, nebenbei geprüft):** Index-Coverage in `MongoStateStore.ensureIndexes()` ist bereits umfassend (deployments/crashes/auditLog/workflows/consoleLines/shares, inkl. TTLs). Der eigentliche Audit-Log-Skalierungs-Hotspot ist nicht ein fehlender Index, sondern die `skip(offset)`-Deep-Pagination (`getAuditLog`) — bewusst als eigener Refactor offen gelassen, kein blinder Index-Wildwuchs.

### H.3 i18n-Coverage (~3 d) — ⏳ **Dashboard vollständig extrahiert (Lint-verifiziert, harter Gate); Website-DE offen**

- Dashboard: alle `app/pages/**` + Leaf-Komponenten extrahiert in `i18n/en.json`, `i18n/de.json`.
  - **Gemessene Lücke (2026-06-02):** ~119 hardcodete user-facing Attribute (`placeholder`/`aria-label`/`title`) + grob ~250 Text-Node-Literale, verteilt über **44+ Komponenten** — viele davon (z.B. `CommandPalette`, `NotificationsPanel`, `InstanceConsole`) importierten `useI18n` noch gar nicht, d.h. Extraktion hieß nicht nur String→`t()`, sondern auch Composable-Verdrahtung + ~1100 neue Keys × 2 Locales.
  - **Geliefert (2026-06-03, 19 gescopte Batches):** **Alle 28 `app/pages/**`-Seiten UND alle Leaf-Komponenten** sind vollständig extrahiert (en+de) — inkl. Script-seitiger Toasts, nativer `confirm()`-Prompts, pluralisierter Counter (vue-i18n-Choice-Syntax), der großen Detail-Seiten (`nodes/[id]` 706, `modules/index` 655, `instances/[id]` 543, `groups/[name]` 488, `templates/[name]` 374) und der schwersten Komponenten (`CreateGroupDialog` 816-Zeilen-6-Step-Wizard, `SettingsThemePalette` 576, die Template-Editor-Panels, alle Appearance-Settings-Panels und die `ui/*`-Primitiven inkl. sr-only-A11y-Labels). Ein abschließender Sweep zeigt **0 verbleibende user-facing Hardcode-Strings**. Locale-Set von 646 → **1807 Keys**, harter `i18n:check`-Parity-Gate durchgehend grün (jeder Commit). Wiederkehrende Muster: `v-for="t in …"`-Loop-Vars kollidierten mit der i18n-`t()` → zu `tpl`/`tok` umbenannt; statische Options-Arrays (Presets, Scaling-Modes, Routing, Color-Var-Gruppen, Review-Cards) zu locale-reaktiven `computed`s konvertiert; Daten-getriebene Enum-Labels (`SCALING_MODE_CONFIG`, Git-Status-Buchstaben, logische Keys wie `'Custom'`/`'primary'`) und Einheiten (`ms`/`MB`/`%`/`GHz`/`px`) bewusst gelassen. Typecheck-Fehlerzahl pro Datei via Stash-Vergleich gegen Base verifiziert — keine neuen Fehler eingeführt.
  - **Hardcode-Lint gebaut + Inventar korrigiert (2026-06-03):** Der angekündigte „keine hardcodeten user-facing Strings"-Lint ist jetzt da — `i18n/check-hardcoded.mjs` (`pnpm i18n:check-hardcoded`), zero-dep wie der Parity-Checker. Statt Regex läuft eine kleine Tag/Text/Quote-State-Machine über die `<template>`-Blöcke (korrektes Handling von `>` in Bindings wie `v-if="a > 0"`, maskiert `<script>`/`<style>` vorab, dekodiert HTML-Entities, überspringt `<code>/<pre>/<kbd>`-Samples und Einheiten-Fragmente, `app/stories/**` exkludiert). Geprüft werden statische `placeholder`/`aria-label`/`title`/`alt`-Attribute (gebundene `:attr` sind ok) + Text-Nodes.
  - **Befund — die „0"-Behauptung oben stimmte nicht:** Der abschließende Sweep war seiten-fokussiert; der automatisierte Lint findet **104 echte user-facing Hardcode-Strings** in ~27 Dateien, die die Batches nie angefasst haben — ganze Komponenten (`NetworkDialog`, `NodeCachePanel`, `AddVersionDialog`, die Template-Dialoge `Create`/`Edit`/`Import`/`UploadFiles`/`VersionHistory`, `CommandPalette`) plus App-Chrome (`layouts/default.vue` Sidebar/Account/„Log out", `auth.vue`, `error.vue`, `pages/index.vue`). Viele dieser Komponenten importieren `useI18n` noch nicht.
  - **Residuen extrahiert (2026-06-03, Batches 20–22):** Alle 104 vom Lint gefundenen Strings sind raus, in drei gescopten Batches:
    - **Batch 20** — App-Chrome + Leaf-Komponenten: `layouts/default.vue` (Sidebar/Account/Skip-Link/„Log out"), `auth.vue`, `error.vue`, `NodeBox`, `DetailSheet`, `FileSearchPanel`, `SettingsAmbientGlows`, `InstanceCard`, `CreateGroupDialog`-Hint. Inkl. der Lint-unsichtbaren Einzelwort-Labels (`Profile`/`Settings`/`Home`). `useI18n` in den 4 Chrome-Dateien nachverdrahtet.
    - **Batch 21** — Template-Dialoge: `Create`/`Edit`/`Import`/`UploadFiles`/`VersionHistory`-Dialog + `TemplateOverviewPanel` + `TemplateVersionHistory`. Geteilte `templateForm.*`/`common.optional`/`versionHistory.empty`-Keys, pluralisierte Counter (`t(key, { count }, n)`).
    - **Batch 22** — `NetworkDialog`, `AddVersionDialog`, `NodeCachePanel`, `CommandPalette` (Theme-Items zu `computed`, `heading=`-Attribute), `overview`/`nodes`/`logs`-Seiten.
    - Verifikation pro Batch: Lint grün, Parity grün (646 → **2057 Keys**), jede `t()`-Referenz gegen `en.json` aufgelöst (0 missing), Typecheck-Fehlerzahl per Stash-Vergleich gegen Base = unverändert (keine neuen Fehler; die bestehenden `unknown`-Store-Typing-Fehler bleiben pre-existing Debt). Diffs minimal gehalten (kein Prettier-Reformat — der Dashboard-Tree ist noch nicht normalisiert).
  - **Gate jetzt HART:** `i18n:check-hardcoded` ist in `ci.yml` ein harter Fail-Gate (Inventar = 0). Language-neutrale Ausnahmen (Beispiel-Hostnames, CLI-Snippets, ARIA-Landmark-Namen) in der `ALLOW`-Liste des Linters dokumentiert.
- Website: Übersetzung der `docs/public/`-Inhalte ins Deutsche. — **offen**

**Track-H-Gesamt: ~10 eng-days. Vor v1.3-Release.**

---

## 10. Was wir NICHT machen (bewusst out of scope)

- **Cross-Region-Cluster.** Raft setzt low-latency same-region voraus. Multi-Region wäre eine eigene Layer, kein v1.x-Thema.
- **OIDC/SAML-Login für Operatoren.** Aus v1.0-Aufräum explizit gestrichen. Custom-Roles + Audit-Log decken die Enterprise-Use-Cases ausreichend ab.
- **Business-State in Raft.** Templates, Instances, Audit, Shares bleiben in Mongo. Raft ist ausschließlich Control-Plane.
- **Eigenes Logging-Backend.** Wir publishen strukturiert, Operator pluggt Loki/ELK an.
- **Eigene Metrics-DB.** Wir exponieren `/metrics` (Prometheus-Format), Operator betreibt Prometheus/VictoriaMetrics.
- **Plugin-Marketplace mit Bezahlfunktion.** Registry: ja. Marketplace mit Stripe: nein, das ist ein anderes Produkt.
- **Auto-Skalierung der Controller-Quorum-Größe.** Operator entscheidet 1/3/5 Member. Auto-Resize ist Komplexität ohne ausreichenden Nutzen.

---

## 11. Release-Milestones

### v1.1 — „HA-Foundation" (Block: Track A + B + G) — ✅ **shipped 2026-05-31**

**Inhalt:**
- ✅ Raft-basierte Control-Plane (Phasen 3–12 vollständig)
- ✅ Repo-Hygiene (tote Dirs raus, READMEs drin, `.editorconfig`, Prettier-Gates, lefthook)
- ✅ ADR-Register (29 ADRs in `docs/engineering/decisions.md`) + Migration-Runbook (`docs/runbooks/upgrade-v1.0-to-v1.1.md`)
- ✅ Phase 8 (Raft-Leases) shipped — `ClusterLeaseManager` trägt Scheduler / Deployment-Reconciler / Audit-Pruner
- ✅ **Loose-Ends-Nachzug (2026-06-03, Post-Audit):** `GET /api/v1/cluster/leases` nachgereicht (Dashboard-Lease-Panel rief eine nie geshippte Route → 404; jetzt `ClusterControlPlane.getLeases()`); `cluster.member.joined`-Audit-Event beim gRPC-`RequestJoin` ergänzt (vorher nur `.ejected`/`.leave` auditiert); die tote Pre-Raft-`ClusterJoinRoutes` (`/admin/cluster/join-template`) + ungenutzte `CLUSTER_JOIN`-Permission entfernt (`ClusterJoinTemplate` bleibt — speist die v1.0→v1.1-Migration). Tests + spotless grün.

**Erfolgs-Kriterien (Gates):**
- 3-Node-Cluster läuft, Killing-the-Leader führt zu Reelection in <5 s
- v1.0 → v1.1 Migration auf einer Single-Controller-Installation läuft ohne Datenverlust
- Repo-Audit zeigt keine toten Top-Level-Dirs mehr
- Alle Tracks-A-Doku in `docs/runbooks/` vorhanden

**Aufwand: ~34 eng-days. Realistisch: 6–8 Wochen bei 1 FTE.**

### v1.2 — „Ökosystem-Reife" (Track C + D + E)

**Inhalt:**
- Modul-Registry produktiv mit 5+ First-Party-Modulen
- Sandboxing-Stufe 1+2 (Tracking + Soft-Limits)
- OpenTelemetry + verteilte Traces
- Design-System operational, alle drei Frontends nutzen es

**Erfolgs-Kriterien:**
- 3 externe Module aus Community-Registry installiert + signaturgeprüft
- Trace-Pfad Controller → Daemon → MC-Plugin sichtbar in Jaeger/Tempo
- ✅ Lighthouse-A11y >= 90 auf Dashboard — **erfüllt (2026-06-06)**: authed-Flow-axe (E-P1.1) ist 0 serious/critical über alle 16 Critical-Routes und ein **harter** CI-Gate

**Aufwand: ~56 eng-days. Realistisch: 10–14 Wochen bei 1 FTE.**

### v1.3 — „Plattform-Ausbau" (Track F + H)

**Inhalt:**
- Bedrock-First-Class
- Fabric + Forge Server-Plugins
- Performance-Tuning, A11y >=95, i18n DE/EN vollständig

**Aufwand: ~25 eng-days. Realistisch: 5–7 Wochen.**

---

## 12. Risiken & Mitigationen

| Risiko | Wahrscheinlichkeit | Impact | Mitigation |
|---|---|---|---|
| **Apache Ratis hat undokumentierte Edge-Cases im Joint-Consensus** | Mittel | Hoch | Wochenend-Spike vor Phase 4. Notfall: MicroRaft als Plan B (Lib-Wechsel kostet ~5 Tage). |
| **JVM 25 Preview-Features blockieren Library-Updates** | Niedrig | Mittel | Vor v1.2 evaluieren: stable-Migration auf was JVM 26 LTS bietet (sobald released). Backup: Fallback auf JVM 21 LTS dokumentiert. |
| **Modul-Sandboxing-Hard-Isolation killt die DX** | Mittel | Mittel | Hard-Isolation bleibt **optional** per Modul, In-Process default. Erst aktivieren wenn echt nötig. |
| **Track-E (Frontend) wird größer als geschätzt** | Hoch | Niedrig | Backlog-Item, kein Release-Blocker. v1.2 kann auch mit hälftiger Migration shippen. |
| **Track-C-Registry braucht Hosting + Moderation** | Mittel | Mittel | First-Party-Registry auf GitHub Pages, kein eigener Server. Community-Registries: dezentral, kein Hosting-Aufwand für uns. |
| **Bedrock-Geyser-Integration zerbricht bei MC-Updates** | Hoch | Niedrig | Klare Versions-Matrix: PrexorCloud x.y unterstützt Geyser-Versions Liste Z. Updates folgen Geyser-Releases. |

---

## 13. Was wäre nötig, damit dieser Plan losgeht?

1. **Eine Person, die Track A komplett owned.** Raft-Arbeit darf nicht zwischen Personen springen — zu subtil. Ideal: 1 FTE für 6–8 Wochen.
2. **Entscheidung: Track-E parallel oder seriell?** Wenn ein Frontend-FTE verfügbar ist: parallel. Sonst: nach v1.1.
3. **Wochenend-Spike für Ratis-Joint-Consensus vor Phase 4 starten.** Reduziert Phase-4-Risiko deutlich.
4. **Entscheidung über Registry-Hosting:** GitHub Pages reicht für v1.2. Ist das ok, oder eigener Server geplant?
5. **Memory-Cleanup vorab:** `project_stats_aggregator.md` und ggf. weitere veraltete Einträge aktualisieren — kostet 30 Minuten, spart später Verwirrung.

---

## 14. Nicht-trivial: was am Plan unsicher ist

Ehrlichkeit über Schwächen dieses Plans:

- **Effort-Schätzungen sind grob.** Die ±30 % Variance pro Track ist normal, kumuliert kann das zu ±50 % auf Track-Ebene werden. Phase-4 (gRPC-Membership) ist besonders unsicher — könnte 5 oder 12 Tage dauern.
- **Track-E-Scope ist groß und schlecht definiert.** Frontend-Konsolidierung tendiert zu Scope-Creep. Mögliche Erweiterung: User-Settings, Theme-Switcher, Notifications-Inbox. Sollte vor Start sauber gescopet werden.
- **Track-C-Sandboxing hat einen Unknown-Unknown:** Wir wissen nicht, welche existierenden Module die neuen Resource-Limits sprengen würden. Erst nach Tracking-Phase ist klar, wie eng Limits sein dürfen.
- **Bedrock-First-Class hängt von Geyser-Maintainer-Schicksal ab.** Falls Geyser unmaintained wird, wird das aufwändiger.

Dieser Plan ist eine *Hypothese*. Nach v1.1 sollte ein Re-Review stattfinden, der die Schätzungen für v1.2 und v1.3 anhand der v1.1-Empirie kalibriert.

---

## 15. Was nach v1.3 kommt (Out-of-Plan-Vision)

Damit klar ist, wo das Ganze hinläuft — aber **nicht** Teil dieses Plans:

- **PrexorCloud-as-a-Service:** Hosted-Variante mit Multi-Tenancy. Eigenes Produkt, eigene Roadmap.
- **GitOps-Integration:** Group-Configs als Code-Repo, ArgoCD-Pattern. Reizvoll, aber separate Initiative.
- **Cloud-native-Modus:** Daemon als Kubernetes-Operator statt nativem Prozess. Ändert das gesamte Deployment-Modell.
- **AI-Assistant im Dashboard:** „Warum crasht meine Lobby-Group?" → LLM-basierte Trace-Auswertung. Cool, aber spekulativ.

Diese vier Ideen sind alle größer als das v1.x-Programm. Sie gehören auf eine eigene Liste, nicht in diesen Plan.

---

**Zusammenfassung in einem Satz:** Mit dieser Roadmap (~115 eng-days über v1.1–v1.3) bringt PrexorCloud sich von „branchenführend bei Security/Ops, mittelmäßig bei HA" zu „branchenführend in allen acht Nordstern-Dimensionen" — wobei der absolute Block für die ehrliche Selbstdarstellung Track A ist (Raft fertig verdrahten).
