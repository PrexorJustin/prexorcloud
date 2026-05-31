# PrexorCloud — Northstar-Plan: bestes & modernstes MC-Cloud-System

**Status:** v1 — gezeichnet am 2026-05-31 nach Gesamt-Audit.
**Owner:** unzugewiesen.
**Zielhorizont:** v1.1 (HA-Foundation) → v1.2 (Ökosystem-Reife) → v1.3 (Plattform-Ausbau).
**Verwandte Dokumente:**
- [`cluster-join-plan.md`](./cluster-join-plan.md) — Raft-Control-Plane-Detailplan (in diesen Plan eingebettet als **Track A**)
- [`MASTER_PLAN.md`](./MASTER_PLAN.md) — bisheriger Rahmenplan
- [`WEBSITE_PLAN.md`](./WEBSITE_PLAN.md) — Public-Site (geliefert v1.0)

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

### A.5 Live-Reload über Raft `apply()` — *Phase 7* (~2 d) — ✅ **Foundation shipped (Commit c69ff6c)** — konkrete Subscriber (CorsAllowList, JwtManager, RateLimiter, SigningPolicyManager) folgen je Subsystem

`ClusterControlStateMachine.apply()` feuert pro committed Entry ein Event auf den internen `EventBus`. Subscriber (`CorsAllowList`, `RateLimiter`, `JwtManager`, `SigningPolicyManager`) reagieren in-Process.

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

### A.8 Dashboard „Cluster"-Page — *Phase 10* (~3 d)

Neue Seite `/cluster` im Dashboard:
- Mitglieder-Tabelle mit Leader-Indikator, Raft-Term, Log-Index-Lag
- Config-Version-History mit Diff-Viewer (CodeMirror diff-mode)
- Lease-Holder-Übersicht
- Join-Token-Management
- Force-Eject-Button mit Confirmation-Modal

### A.9 Recovery-Tooling — *Phase 11* (~2 d)

`prexorctl cluster recover --i-have-only-survivor` — single-member Raft-Reset für Majority-Loss. Interaktive Confirmation, Audit-Eintrag der den Reset überlebt. Doku in `docs/runbooks/cluster-recovery.md`.

### A.10 ADR + Migration-Guide — *Phase 12* (~2 d) — ⏳ **teilweise shipped**: ADR 29 (embedded Ratis) + ADR 4 Update in `decisions.md` ✅; Migration-Runbook `docs/runbooks/v1.0-to-v1.1.md` und Recovery-Runbook noch ausstehend

ADR-Eintrag in `docs/decisions.md`: „Embedded Raft via Apache Ratis als Cluster-Control-Plane (statt Mongo-CAS / externem Coordinator)". Migration-Guide in `docs/runbooks/v1.0-to-v1.1.md`: Schritt-für-Schritt für die zwei Operator-Szenarien (Single-Controller-Upgrade, Multi-Controller-Upgrade).

**Track-A-Gesamt: ~25 eng-days. Block für v1.1-Release.**

---

## 3. Track B — Repo-Hygiene & Cleanup

**Ziel:** Onboarding-Reibung auf null. Tote Pfade entfernen, Konventionen festziehen.

### B.1 Tote Verzeichnisse entfernen (~0.5 d)

| Pfad | Befund | Aktion |
|---|---|---|
| `java/cloud-module/` | 7 build.gradle.kts ohne settings.gradle.kts-Eintrag — Rename-Sediment | **DELETE** kompletter Tree |
| `java/cloud-modules-core/` | Nur build.gradle.kts, kein Source, nicht im Build | **DELETE** |
| `config/` (nur `controller.compose.yml`) | Dublette zu `deploy/compose/` | Datei nach `deploy/compose/` MOVEn, `config/` DELETEn |
| `infra/perf/baselines.json` | Einziger Inhalt unter `infra/` | MOVEn nach `java/cloud-test-harness/perf/`, `infra/` DELETEn |
| `java/cloud-controller/logs/*.log.gz` | Eingecheckte Logs (im Diff bereits gelöscht) | Commit + `.gitignore`-Eintrag für `logs/` |
| `java/cloud-controller/config/.initial-admin-password` | Wird bei jedem Boot überschrieben | `.gitignore`-Eintrag |
| `cluster/raft/{KeyValueStateMachine,KvOp}.java` | Phase-1-Spike, nicht mehr genutzt | **DELETE** nach Track A.1 |

### B.2 READMEs einziehen (~1 d)

Top-Level-Dirs ohne README → je ein 8–15-Zeilen-README mit Zweck, Layout, „How to add a new X":

- `java/README.md` — Modul-Hierarchie erklären, Build-Reihenfolge
- `dashboard/README.md` — Setup, dev-server, Storybook-Stories
- `website/README.md` — Astro/Starlight setup, OpenAPI-Sync
- `scripts/README.md` — was jedes Skript tut
- `tools/README.md` — Codegen-Skripte erklären

### B.3 Gradle-Konventionen vereinheitlichen (~1 d)

- Alle `build.gradle.kts`-Dateien gegen `build-logic/` Konventionen prüfen (Java 25, Logback-Version, Jackson-Version aus Catalog).
- `libs.versions.toml` durchgehen — orphaned Aliases entfernen.
- `cloud-platform`-BOM auditieren: was wird referenziert, was nicht.

### B.4 Memory-Einträge aktualisieren (~0.5 d)

`MEMORY.md` und Einzeleinträge gegen heutigen Stand:
- `project_stats_aggregator.md` — Pfad ist `java/cloud-modules/stats-aggregator/`, nicht `java/cloud-module/...`
- `project_cluster_join_plan.md` — v3-Stand reflektieren

### B.5 Linter & Format-Konvention (~1 d)

- `.editorconfig` finalisieren
- Spotless für Java aktivieren (Google-Style + Imports-Order) → CI-Gate
- Prettier für Dashboard/Installer/Website → CI-Gate
- `pre-commit`-Hook im Repo (per `lefthook`) für lokale Validierung

**Track-B-Gesamt: ~4 eng-days. Parallel zu Track A möglich.**

---

## 4. Track C — Modul-Ökosystem zur Marktreife

**Ziel:** PrexorCloud-Module sind heute schon **konzeptionell besser** als CloudNet/SimpleCloud — aber als Plattform fehlen drei Dinge zur Reife: Registry, Sandboxing, und Lifecycle-UX.

### C.1 Modul-Registry mit signierter Distribution (~10 d)

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

1. **Resource-Tracking** (~3 d): Pro Modul ein eigener `ScheduledExecutorService` mit benannten Threads → JFR-Profiling pro Modul möglich. CPU-Time via `ThreadMXBean.getThreadCpuTime()`. Allocation-Tracking via `ThreadMXBean.getThreadAllocatedBytes()`. Heap-Footprint via OQL-basiertem Sampling.
2. **Soft-Limits** (~3 d): Pro-Modul-Quota in `clusterConfig.modules.<id>.resources`: `maxCpuMillisPerMinute`, `maxAllocatedMbPerMinute`, `maxOpenFiles`. Überschreitung → Warn + Metrik (`prexorcloud.module.quota.exceeded`).
3. **Hard-Isolation** (~4 d): Optional: Modul in separatem JVM-Prozess starten, Kommunikation über gRPC-IPC. Aktivierbar per `clusterConfig.modules.<id>.isolation = process`. Erlaubt OS-Level-cgroups/quotas.

**Pragmatik:** Hard-Isolation ist optional, default bleibt In-Process. Nur kritische Module (oder welche aus untrusted-Registries) werden isoliert.

### C.3 Lifecycle-UX-Polish (~3 d)

- **Dependency-Resolution-UI** im Dashboard: „Modul X braucht stats-aggregator v2.1+ — fehlt" → Vorschlag, das fehlende Modul mitzuinstallieren.
- **Hot-Reload:** Modul-Update ohne Controller-Restart. State-Erhalt über `PlatformModuleStateStore`-Interface, das Module beim Stop persistieren und beim Start lesen.
- **Health-Checks:** Module exposen optional `healthCheck()`-Methode; Controller pollt, Dashboard zeigt grünen/gelben/roten Punkt.

### C.4 Module-Scaffolder im CLI (~2 d)

```bash
prexorctl module scaffold my-cool-module \
  --capabilities prexor.player.journey \
  --rest-routes \
  --mc-plugin paper,velocity
```

Generiert komplette Modul-Skelette inkl. MC-Plugin-Anbindung, build.gradle.kts, manifest.json, GitHub-Actions-Workflow für signed Builds. Senkt die Hürde für externe Contributors auf 5 Minuten.

### C.5 Erweiterung First-Party-Module (~3 d)

Konkrete Module, die heute fehlen und Differenzierung schaffen:
- `cloud-module-discord-bridge` — Discord-Webhooks + Slash-Commands → MC, MC-Chat → Discord
- `cloud-module-grafana-bridge` — Read-only Grafana-Datenquelle, die `/metrics` aggregiert exponiert
- `cloud-module-backup-orchestrator` — automatische Periodische Welt-Snapshots mit Restic/Borg

**Track-C-Gesamt: ~28 eng-days. Beginnt nach Track A. Hängt teilweise von Raft ab (Trust-Roots in `clusterFiles`).**

---

## 5. Track D — Observability Gen-2 (OpenTelemetry & Tracing)

**Ziel:** Verteilte Traces über Controller → Daemon → MC-Plugin. Heute fehlt das komplett.

### D.1 OpenTelemetry-SDK einziehen (~3 d)

- `io.opentelemetry:opentelemetry-bom` ins `libs.versions.toml`.
- Auto-Instrumentation für: Javalin-HTTP, gRPC-Calls, MongoDB-Driver, Lettuce-Redis.
- OTLP-Exporter mit konfigurierbarem Endpoint (`clusterConfig.telemetry.otlpEndpoint`).
- Fallback: Jaeger / Tempo / Honeycomb / Datadog — alles OTLP-kompatibel.

### D.2 Eigene Spans für Domain-Flows (~3 d)

Manuelle Span-Instrumentierung an den interessanten Stellen:
- Scheduler-Tick (`scheduler.tick`)
- Instance-Placement (`placement.evaluate`, `placement.dispatch`)
- Deployment-Reconcile (`deployment.reconcile`)
- Modul-`apply()` für Raft-Entries (`raft.apply.<entry-type>`)
- Auth-Flows (`auth.login`, `auth.token-verify`)

### D.3 Trace-Propagation Controller → Daemon → MC-Plugin (~2 d)

- gRPC-Trace-Context propagieren (W3C-Traceparent).
- Plugin-Token-Calls vom MC-Plugin tragen Trace-Context im Header.
- Dashboard zeigt „Trace ansehen"-Button auf Instance-Detailseite (Deep-Link zu Jaeger/Tempo).

**Track-D-Gesamt: ~8 eng-days. Unabhängig von Track A, kann parallel.**

**Bewusst nicht in Scope:** Logs-Aggregation (Loki/ELK) — das ist Ops-Konfiguration, nicht App-Code. Wir publishen strukturierte Logs (JSON über Logstash-Layout), Operator kann anschließen.

---

## 6. Track E — Frontend & Design-System Konsolidierung

**Ziel:** Heute existieren drei Vue-/JS-Stacks (Nuxt 4 für Dashboard, Vite-Vue für Installer, Astro + Vue-Islands für Website). Plus ein Design-System-Verzeichnis, das von keinem davon konsumiert wird. Konsolidieren.

### E.1 Design-System operationalisieren (~5 d)

- **Token-Pipeline:** `design-system/tokens.json` als single source of truth. Via [Style-Dictionary](https://amzn.github.io/style-dictionary) generieren:
  - `design-system/dist/tokens.css` — CSS-Variablen (für alle drei Frontends)
  - `design-system/dist/tokens.ts` — TypeScript-Constants (für JS-Logik in den Frontends)
  - `design-system/dist/tokens.json` — Build-Output (für ggf. Figma-Sync)
- **NPM-Workspace:** `@prexorcloud/design-system` als pnpm-Workspace-Package — Dashboard, Installer, Website importieren von dort.
- **Komponenten-Library:** Erweiterung des Design-Systems um echte Komponenten (Button, Input, Card, Modal, Toast, Table). Headless-Pattern (Radix-Vue oder eigenes) damit Styling nur über Tokens kommt.
- **Histoire-Stories** für jede Komponente in `design-system/stories/`.

### E.2 Dashboard auf konsolidierten Stack (~10 d)

- **Optional, bewusst:** Bleibt auf Nuxt 4, importiert Design-System-Komponenten statt eigener Implementierungen.
- Komponenten-Migration: Buttons, Inputs, Cards überall durch Design-System-Versionen ersetzen.
- A11y-Pass: ARIA-Labels, Keyboard-Navigation, Color-Contrast-Audit (axe-core in CI).
- i18n-Foundation: Strings in `i18n/`-Files extrahieren, `vue-i18n`. Default `en`, `de`.

### E.3 Installer-Wizard auf gleichen Stack (~3 d)

- Vue 3 + Vite bleibt (Single-File-HTML-Constraint), aber Components aus `@prexorcloud/design-system` importieren.
- Vermeidet doppelte Button/Input-Implementationen.

### E.4 Website-Theme aus Design-System ziehen (~2 d)

- Starlight-Custom-CSS aus `design-system/dist/tokens.css` generieren.
- Mermaid-Palette nicht hardcoden, sondern aus `tokens.json` ziehen (heute hardcoded in `website/src/scripts/mermaid.ts`).

**Track-E-Gesamt: ~20 eng-days. Parallel zu A/B/C möglich (Frontend-Team separat).**

---

## 7. Track F — MC-Plattform-Breite

**Ziel:** Wir unterstützen heute Paper/Folia/Spigot/Velocity/BungeeCord + Bedrock via Geyser-Beispielmodul. Erweitern.

### F.1 First-Class Bedrock-Routing (~7 d)

- Dedicated Proxy-Plugin: `cloud-plugins:proxy:geyser` — eigene Implementierung über Geyser-Spigot oder Geyser-Standalone als Sidecar.
- BedrockProtocol-Adapter im `cloud-controller/network/`: Bedrock-Clients in `NetworkComposition` erstklassig behandeln (ein-/auschecken in Lobby-Gruppen, Fallback-Routing).
- Dashboard zeigt Bedrock-vs-Java-Spieler getrennt.

### F.2 Fabric-Server-Plugin (~5 d)

- Neues Modul: `cloud-plugins:server:fabric` — Fabric-Loader-Mod, die mit Controller-gRPC spricht.
- Fabric läuft anders als Paper (kein Bukkit-API), erfordert Custom-Event-Bridge.
- Use-Case: Modded-Server in der Cloud orchestrieren.

### F.3 Forge-Server-Plugin (~3 d)

- Wenn Fabric läuft, Forge mit etwa 70 % Aufwand machbar. Mod-Toolchain ist anders, aber Logik gleich.

**Track-F-Gesamt: ~15 eng-days. Niedrigere Priorität — kann auf v1.3 oder später.**

---

## 8. Track G — Doku & ADR-Vollständigkeit

**Ziel:** Architecture-Decision-Records (ADR) für alle nicht-trivialen Entscheidungen, sodass spätere Maintainer „warum" verstehen.

### G.1 ADR-Register füllen (~3 d)

Heute existiert `docs/decisions.md` — ergänzen um (jeweils 1 ADR):

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
- `cluster-recovery.md` — Majority-Loss-Recovery
- `v1.0-to-v1.1.md` — Migration mit Raft
- `module-trust-root-rotation.md`
- `ca-rotation.md` (existiert eventuell schon — auditieren)
- `incident-postmortem-template.md`

**Track-G-Gesamt: ~5 eng-days.**

---

## 9. Track H — Polish, Performance, A11y, i18n

**Ziel:** Letzter Schliff vor v1.3.

### H.1 Performance-Tuning (~4 d)

- Perf-Baseline-Trend-Reports der letzten 60 Tage anschauen, Drift-Hotspots identifizieren.
- Mongo-Indices auditieren — die Audit-Log-Queries sind die wahrscheinlichsten Bottlenecks bei großen Clustern.
- Scheduler-Tick-Profiling: lässt sich der Tick unter 50 ms p99 halten bei 100 Groups?

### H.2 Accessibility-Audit (~3 d)

- Dashboard durch axe-core und Lighthouse-A11y. Mindest-Score 95.
- Tastatur-Navigation für alle Critical-Flows (Login, Group Create, Deploy).
- Color-Contrast WCAG-AA gegen Design-System-Tokens validieren.

### H.3 i18n-Coverage (~3 d)

- Dashboard: 100 % der UI-Strings extrahiert in `i18n/en.json`, `i18n/de.json`.
- Website: Übersetzung der `docs/public/`-Inhalte ins Deutsche.

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

### v1.1 — „HA-Foundation" (Block: Track A + B + G)

**Inhalt:**
- Raft-basierte Control-Plane (Phasen 3–11 vollständig)
- Repo-Hygiene (tote Dirs raus, READMEs drin)
- ADR-Register + Migration-Runbook
- Phase 8 (Raft-Leases) optional — Redis-Leases bleiben Fallback

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
- Lighthouse-A11y >= 90 auf Dashboard

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
