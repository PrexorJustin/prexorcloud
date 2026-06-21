> Generiert durch Multi-Agent-Audit (37 Agenten, run wf_93f8e7cb-c89), 2026-06-21. Funde sind code-statisch verifiziert (nicht lokal gebaut/live-gefahren).

# PrexorCloud — Gesamt-Audit (Kontrollpunkt vor Clean-Point)

Branch: `rewrite/single-writer-control-plane` · Stand: 2026-06-20 · Bewertung: Principal-Engineer-Review über sieben Dimensionen, mit unabhängiger Code-Verifikation je Befund.

---

## 1. Executive Summary

**Gesamtlage: gesund im Kern, unsauber an den Rändern.** Der Rewrite-Kern — Mongo-Lease-Leadership mit Fencing-Epoch — ist der stärkste Teil des Systems und ernsthaft gut gebaut: server-autoritative Zeit (`$$NOW`) eliminiert Controller-Clock-Skew aus dem Safety-Argument, majority read/write überlebt Mongo-Primary-Failover, die Epoch wird end-to-end durchgereicht und der Daemon verwirft Befehle abgesetzter Leader (Defense-in-Depth gegen Split-Brain). Ratis ist aus dem Produktiv-Quellcode sauber entfernt. Die kryptografischen Grundlagen (Argon2id, JWT-Rotation, Join-Token-Hashing, Path-Traversal-Abwehr) sind solide. Observability ist für ein Projekt dieser Größe überraschend reif.

**Die teuren Probleme liegen NICHT im Konsens-Kern, sondern in vier Bereichen:**

1. **Runtime-State ist noch nicht migriert.** „Single-Writer, Mongo-autoritativ" gilt heute nur für *Desired State*. Nodes/Instanzen/Players/Plugin-Tokens leben weiter in Redis; cross-controller-Konvergenz reitet auf einem blockierenden Redis-SCAN pro Scheduler-Tick. Phase 6 hat aktuell **keinen Ersatzmechanismus** — Redis lässt sich nicht entfernen, ohne vorher die Runtime-Konvergenz auf Mongo/Change-Streams zu heben.

2. **Zwei echte Nebenläufigkeits-Bugs in der Platzierung** (beide verifiziert, beide HIGH): paralleler Tier-Fan-out ohne Node-Lock → deterministisch dieselbe Node + derselbe Port → **doppelte Port-Vergabe**; und der Daemon-Heartbeat überschreibt Controller-Reservierungen → **Memory-Überbuchung im Steady State** + Port-Kollisionen im Boot-Fenster.

3. **Der Test-Backstop ist schwächer als er aussieht.** Die volle E2E-Harness (HA-Failover, Recovery, Token/RBAC, Cosign-Install) läuft in **keiner** CI-Pipeline; sie ist im `build` sogar explizit deaktiviert, Nightly fährt nur 6 kuratierte Klassen. Genau die rewrite-kritischen Pfade sind ungetestet. Ein Failover-/Token-Bug würde heute durch CI rutschen.

4. **Dokumentation und Config widersprechen dem Rewrite frontal.** README, der öffentliche HA-Guide, die Referenz-Compose und das CLI beschreiben weiterhin Apache Ratis. Der Referenz-Compose startet Mongo zudem **ohne `--replSet`** — der rewritete Controller kann damit keine Change Streams öffnen.

**Größte Hebel (Reihenfolge nach Wirkung/Aufwand):**
- Platzierungs-Reservierung pro Node serialisieren + Daemon-NodeStatus als Telemetrie behandeln (2 HIGH-Bugs, mittlerer Aufwand).
- Spotless vom Test-Gate entkoppeln + schlanken Integrations-Job mit Mongo an PR-CI hängen (Testsignal wiederherstellen, klein-mittel).
- Compose auf `--replSet rs0` + Doc-/CLI-/Config-Raft-Sweep (Clean-Point-Voraussetzung, klein-mittel).
- Phase-6-Vorbedingung definieren: Runtime-Konvergenz auf Mongo, *bevor* Redis entfernt wird.

**Ist das Projekt in gutem Zustand?** Für einen Branch mitten in einer großen Migration: ja, der Kern ist tragfähig und live-bewiesen. Aber es ist **nicht merge-reif für `main`** ohne (a) die zwei Platzierungs-Bugs, (b) ein wiederhergestelltes Java-Testsignal und (c) den Doc/Config-Raft-Sweep. Der Rest ist bewusste, dokumentierte Tech-Schuld — kein verdeckter Verfall.

---

## 2. Top-Risiken (priorisiert, nur verifiziert/plausibel)

| Severity | Befund | Datei:Zeile | Impact | Aufwand | Conf. | Verdict |
|---|---|---|---|---|---|---|
| **HIGH** | Nebenläufige Platzierung ohne Node-Lock → doppelte Port-Vergabe + überschriebene Memory-Reservierung | `InstancePlacementCoordinator.java:119-202`, `Scheduler.java:313-329`, `NodeRegistry.java:61-74` | Zwei Gruppen (gleicher Tier) skalieren auf dieselbe Node → identischer `--port` → Daemon-Bind-Konflikt/Crash; Node überbucht | M | hoch | **confirmed** |
| **HIGH** | Daemon-NodeStatus-Heartbeat überschreibt Controller-Port/Memory-Reservierungen (Full-Overwrite, kein Merge) | `DaemonServiceImpl.java:316-324`, `DaemonGrpcClient.java:552-565` | Reservierter Port erscheint nach Heartbeat wieder „frei" → erneute Vergabe; Memory-Reservierung wird auf physisches Ist zurückgesetzt → Über-Subscription im Steady State | M | hoch | **confirmed** |
| **HIGH** | Volle E2E-Harness (Failover/Recovery/Token/RBAC/Cosign) läuft in keiner CI; im `build` deaktiviert, Nightly nur 6 Klassen | `ci.yml:32`, `cloud-test-harness/build.gradle.kts:141`, `java/build.gradle.kts:25-34` | Rewrite-kritische Pfade ungetestet; Regression merged unbemerkt | M | hoch | **confirmed** |
| **HIGH** | Redis ist hartes, clusterweites Readiness-Kriterium → ein Redis-Blip nimmt die GESAMTE Controller-Flotte aus dem LB | `ControllerReadinessProbe.java:13-19,54`, `PrexorCloudBootstrap.java:1574-1583` | Redis-Ausfall eskaliert von „Plugin-Auth degradiert" zu „Control-Plane flotten-weit 503"; Liveness bleibt korrekt 200 | S | hoch | **partially_confirmed** |
| **HIGH→Blocker** | Referenz-Compose startet Mongo ohne `--replSet` | `deploy/compose/compose.yml:24-25` | Rewriteter Controller kann keine Change Streams/Lease betreiben; stiller Produktionsdefekt der Referenz-Anleitung | S | hoch | bestätigt via Mongo-SoT-Fund |
| **MED** | Crash-Fenster Dual-Write: Redis-Instanz vor Mongo-Plan → dauerhaft gestrandete Instanz (kein Reaper, kein TTL) | `InstancePlacementCoordinator.java:189-207`, `RecoveryOrchestrator.java:142-146` | Crash zwischen zwei Writes strandet SCHEDULED-Instanz für immer, hält Reservierung; keine Selbstheilung | S | hoch | **confirmed** (sev↓) |
| **MED** | Join-Token nicht an `nodeId` gebunden → beliebige Node-Identität aus gültigem Token herleitbar | `FileJoinTokenStore.java:62-69`, `BootstrapServiceImpl.java:154`, `BootstrapRoutes.java:87` | Geleakter Token erlaubt Impersonation einer beliebigen Node + IP-Self-Whitelisting; trivialer Fix | S | hoch | **confirmed** (sev↓) |
| **MED** | Klartext-REST für gesamte Control-Plane inkl. Bootstrap-Secrets (PKCS12-Passwort, Node-Cert, cliToken) | `HttpConfig.java:5-18`, `RestServer.java:180,418`, `BootstrapRoutes.java:94-98` | Mitleser auf Hop Reverse-Proxy→Controller erbeutet mTLS-Material; per Doku TLS-Proxy Pflicht (bewusste Entscheidung) | M | hoch | **partially_confirmed** (sev↓) |
| **MED** | Recovery ohne Attempt-Cap, nur In-Memory-Backoff → Thundering-Herd nach Failover | `RecoveryOrchestrator.java:62,117-163` | Neuer Leader re-dispatcht alle in-flight Starts im ersten Tick; stuck-STARTING ungecappt (idempotent gemildert) | M | hoch | **partially_confirmed** (sev↓) |
| **MED** | SSE-Stream broadcastet ALLE Cluster-Events an jeden authentifizierten Client (auch Plugin-Token) | `SseEventStreamer.java:81,110-160`, `PluginRoutes.java:144-160` | Einzelnes Plugin liest gesamten Fleet-Event-Strom inkl. Spieler-PII; bricht Multi-Tenant-Isolation | M | hoch | (kein Verdict; Code-belegt) |
| **MED** | Öffentlicher HA-Guide + README + CLI beschreiben gelöschtes Ratis | `docs/public/en/guides/ha-controller.md:6-19`, `README.md:50`, `cli/cmd/cluster.go:70-390` | Operatoren bauen nicht-funktionierendes Setup; auf `main` noch korrekt (Ratis dort live), bricht beim Rewrite-Release | M | hoch | **partially_confirmed / confirmed** |
| **LOW** | Plugin-Token-Refresh ohne Re-Enrollment-Fallback | `BaseControllerClient.java:177-226` | Echter Gap nur jenseits des 2×TTL-Grace-Fensters + Redis-down; Caller-Retry-Loops + Grace-Window mildern stark | M | hoch | **partially_confirmed** (sev↓↓) |

---

## 3. Tech-Stack-Urteil

### 3a. PRAGMATISCH — im jetzigen Stack verbessern (empfohlen)

Der Stack ist kohärent und für eine selbst-gehostete Minecraft-Control-Plane richtig gewählt. **Es gibt keinen Grund für einen Kern-Rewrite.** Die lohnenden Verbesserungen ohne Technologiewechsel:

1. **`--enable-preview` aus den Produktiv-Artefakten entfernen** (`prexorcloud.java25-preview.gradle.kts:14`). Korrektur zur Verifikation: die dramatische Behauptung „bricht bei jedem JDK-Patch (25.0.1→25.0.2)" ist **falsch** — Preview-Bytecode ist an die Class-File-Major-Version (Java 25) gekoppelt, nicht an Patch/Minor; alle 25.x teilen Major 69. Der Flag ist konsistent in allen Launch-Pfaden (systemd, Dockerfile via jlink-JRE) vorhanden. Der **echte** Preis ist Wartungs-Lock-in: kein Forward-Kompat-Bytecode, jeder JDK-Major-Sprung erzwingt Recompile, Preview-API-Churn (`StructuredTaskScope`-Signatur). Treiber ist `StructuredTaskScope` (JEP 505, in 25 weiter Preview); `ScopedValue` ist in 25 final. → Mittelfristig auf stabile APIs zurückportieren oder Preview nur in Test-Modulen. **Severity: medium, nicht high.**

2. **Go-CLI-Client aus `docs/openapi.json` generieren** (`oapi-codegen`) und in den bestehenden Drift-Gate aufnehmen — analog zum `sdk:check` des Dashboards. Heute werden 63+ Endpunkt-Pfade von Hand codiert (`cli/internal/api/client.go`, `types.go`), das Dashboard ist generiert + CI-gegated → Contract-Drift by design. Den toten `go_package` im Proto (`daemon_service.proto:6`, kein `cli/proto`, kein gRPC in `go.mod`) entweder belegen oder entfernen. **Eliminiert eine ganze Fehlerklasse.**

3. **Phase 6 als geplante Operation ziehen** (25 Controller-Dateien referenzieren Redis): Plugin-Tokens + Runtime-Instanz-State nach Mongo (+ Leader-In-Memory-Cache als Read-Path). Vorbedingung: Runtime-Konvergenz auf Change-Streams heben (sonst Konvergenz-Lücke). Korrektur: „Redis-Ausfall trennt ALLE Verbindungen" ist überzogen — same-controller-Tokens validieren In-Process ohne Redis; betroffen sind Cross-Controller + Restart-Rehydration.

4. **SSE-Wire-Contract spec-konform vereinheitlichen** (drei handgerollte Parser: Controller, Java-Plugin, Go-CLI). Der Go-Parser (`client.go:511-551`) unterstützt nur LF (SSE verlangt CRLF), ignoriert `id:`/`retry:`, hat keinen Reconnect → `prexorctl logs` verliert den Stream still. Den hartcodierten `/api/v1/events/stream`-Pfad an den rollen-spezifischen Ticket-Prefix angleichen (`BaseControllerClient.java:117-135`).

5. **JAR-Auslieferung aus dem Steuer-Stream nehmen.** `ModuleInstall` transportiert die shadowJar inline (`daemon_service.proto:432`), Chunking ist „deferred", `MAX_MESSAGE_SIZE` auf 100 MB hochgezogen — materialisiert ganze Jars in Controller- UND Daemon-Heap. Besser: Daemon zieht per HTTP (SHA-256 + Signatur), Steuer-Stream bleibt frei von Großdaten.

### 3b. RADIKAL — größere Alternativen (auf expliziten Wunsch, mit Kosten/Risiko)

- **Postgres statt Mongo+Redis als einzige SoT.** Eine Engine statt zwei; echte Transaktionen über Intent+Runtime+Token (das Dual-Write-Crash-Fenster verschwindet *strukturell*); `SELECT … FOR UPDATE`/Advisory-Locks für Leadership; `LISTEN/NOTIFY` statt Change-Stream+SSE. **Kosten: groß** (Storage-Layer-Rewrite, Re-Test der frisch fertigen Lease-Mechanik). **Risiko: hoch** — der Mongo-Lease ist gerade live-bewiesen; ein Wechsel jetzt wäre Verschwendung. **Empfehlung: NICHT jetzt.** Nur evaluieren, falls der HA-Anspruch über das aktuelle Maß wächst.

- **etcd/Consul für Leadership.** Domänen-passenderes Werkzeug (linearer Konsens, native Leases+Watches) als eine Dokument-DB. Aber: der findOneAndUpdate-Lease ist korrekt und fertig; ein dritter Infra-Dienst erhöht die Betriebslast. **Empfehlung: NICHT** — der Vorteil rechtfertigt die Migration nicht.

- **JVM-CLI (GraalVM-native) statt Go.** Würde den Contract teilen. **Empfehlung: NICHT** — wäre ein Rückschritt; Go ist für ein single-binary, cross-compilierbares, cosign-signiertes Operator-CLI exzellent. Der einzige Preis (geteilter Contract) ist über OpenAPI-Codegen lösbar (3a.2).

### 3c. Bewusst BEHALTEN

- **Java 25 mit Records/Virtual-Threads/StructuredTaskScope** — alternativlos sinnvoll (Minecraft = JVM-Welt; Plugin-API + Control-Plane teilen Sprache + Klassenlader). Einzige Einschränkung ist der Preview-Flag (3a.1), nicht die Sprachwahl.
- **gRPC-bidi-Single-Stream** für die Daemon-Verbindung — richtig für langlebige Verbindungen (eine mTLS-Verbindung, Server-Push, Reconnect an einer Stelle). Der additive-oneof-Stil ist protobuf-idiomatisch. Nur: reine Request/Reply-Operationen künftig als unäre RPCs modellieren, nicht als neue oneof-Varianten.
- **Javalin + SSE** für Plugin/Dashboard-REST — genau das richtige Maß, kein WebSocket/gRPC-web-Overengineering nötig.
- **Mongo als SoT** (kurzfristig) — vertretbar, frisch live-bewiesen. Aber: in einem ADR mit den Konsistenz-Garantien (read/write-concern majority) explizit begründen und Replica-Set als harte Voraussetzung dokumentieren.
- **Gradle-Convention-Plugins + Version-Catalog** — skalieren das 35-Modul-Monorepo sauber.
- **Go-CLI** als eigenständige Codebasis.

---

## 4. Befunde je Dimension

### 4.1 Architektur & Konsistenzmodell

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| MED (war high) | **Bifurkierte Autorität:** Runtime-State hat keine Single Source of Truth — Mongo=Intent, Redis=Runtime, Daemon=Ist. Change-Stream watcht nur Desired-Collections. | `PrexorCloudBootstrap.java:1220-1229`, `ClusterState.java:127-303` | partially_confirmed |
| MED (war high) | **Reaktive Reconciliation deckt Runtime-Konvergenz nicht ab** — blockierender Redis-SCAN (limit=500) pro Tick bleibt load-bearing; Change-Stream ist „strictly additive". | `ClusterState.java:166-200`, `ChangeStreamReconciler.java:24-53` | partially_confirmed |
| MED | **Cross-Controller-Relay fire-and-forget** über Redis pub/sub ohne Ack/Retry; verlorene StopInstance hat KEINEN Recovery-Pfad → weiterlaufende Instanz. | `NodeMessageDispatcher.java:61-99` | (kein Verdict) |
| MED | Redis-Mirror-Konvergenz kann lokal-frischere Instanz rückwärts demoten (STARTING→SCHEDULED). | `ClusterState.java:177-181`, `InstanceTransitionValidator.java:29-32` | (medium) |
| MED | Follower ohne bekannten Leader behält Daemon-Stream still, akzeptiert Status ohne Redirect/Timeout → lautlose Divergenz. | `DaemonConnectionLifecycle.java:163-172,308-314` | (kein Verdict) |
| LOW | Lokaler Liveness-Guard hängt an Annahme Renew-RTT < safetyMargin (5s); durch Daemon-Epoch-Fence als Defense-in-Depth abgedeckt, aber Annahme nicht validiert. | `MongoLeaderElector.java:112-120,318-348` | (medium) |
| LOW | Change-Stream Resume-Token nur im Heap; `fullResyncs`/`streamOpens` nicht als Metrik exponiert → stille Dauer-Degradierung unsichtbar. | `ChangeStreamReconciler.java:71,143-170` | (high conf) |
| LOW | Adopt/Resurrect re-rät RUNNING mit playerCount=0/uptime=0 → kurzzeitig falsche Scale-Aggregate bis erster Heartbeat. | `DaemonConnectionLifecycle.java:442-516` | (medium) |
| LOW | REAP beruht auf In-Memory `GroupManager.exists` — Cold-Leader-Fenster könnte echte Instanz als Orphan stoppen (eng, da Groups vor Leadership laden). | `DaemonConnectionLifecycle.java:442-489` | (low conf) |

**Empfehlung (zusammengefasst):** Vor Phase 6 die Runtime-Konvergenz auf Mongo-Change-Streams (oder Owner→Mongo write-through) heben und im Worklog als harte Vorbedingung markieren. Relay mit Ack/Re-Dispatch absichern (Stop braucht Backstop). `STARTING/PREPARING→SCHEDULED` aus dem Validator entfernen; Reschedule nur über dedizierten Recovery-Pfad. Bei unbekanntem Leader Daemon-Handshake aktiv ablehnen (Backoff-Reconnect) statt still parken.

### 4.2 Korrektheit & Nebenläufigkeit

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| **HIGH** | Nebenläufige Platzierung: verlorene Reservierungen + doppelte Port-Vergabe (kein Node-Lock; deterministischer NodeSelector + niedrigster-freier-Port macht Kollision *wahrscheinlich*). | `Scheduler.java:313-329`, `InstancePlacementCoordinator.java:119-202`, `NodeRegistry.java:61-74` | **confirmed** |
| **HIGH** | Daemon-NodeStatus-Heartbeat überschreibt Reservierungen (Full-Overwrite). Memory-Fall ist Steady-State (ResourceMonitor meldet physisches Ist) → Über-Subscription ohne Timing-Fenster. | `DaemonServiceImpl.java:316-324`, `DaemonGrpcClient.java:552-565` | **confirmed** |
| MED (war high) | Crash-Fenster Dual-Write strandet Instanz dauerhaft (kein TTL, kein Reaper). | `InstancePlacementCoordinator.java:189-207`, `RecoveryOrchestrator.java:142-146` | **confirmed** |
| MED (war high) | Recovery ohne Cap + In-Memory-Backoff → Thundering-Herd nach Failover; NACK-Pfad markiert CRASHED (gemildert), nur stuck-STARTING ungecappt. | `RecoveryOrchestrator.java:62,117-163` | **partially_confirmed** |
| MED | InstanceTransitionValidator zu permissiv (Rückwärts-Arcs PREPARING/STARTING→SCHEDULED); Kommentar „Validator lehnt Rückwärts ab" ist für diese Arcs falsch. | `InstanceTransitionValidator.java:22-36`, `ClusterState.java:177-180` | (medium) |
| MED | Stale `controllerApiUrl` nach Leader-Failover in Server-Prozess-Env eingebrannt; `setControllerApiPort` liest `target` ohne `connectLock` (Torn-Read). | `ServerProcess.java:148-158`, `DaemonGrpcClient.java:462-466` | (medium) |
| MED | Blockierender Redis-Read im gRPC-Hotpath (`verifyNodeOwnership`→`adoptInstanceFromRedis`), je INSTANCE_STATUS/CONSOLE_OUTPUT unbekannter Instanz. | `DaemonServiceImpl.java:270-295`, `ClusterState.java:218-230` | (high conf) |
| MED | CloudStateCache: nicht-atomares RMW zwischen SSE- und Poll-Thread → verlorene Deltas + Refresh-Kaskade unter Reconnect-Last. | `CloudStateCache.java:120-185` | (medium) |
| MED | ScalingEvaluator-Cooldown geht bei Failover verloren (lokale Map), wenn Redis-Koordination aus ist (= Phase-6-Zielzustand). | `ScalingEvaluator.java:29,127-157` | (medium) |
| LOW | `ReconnectManager.currentDelay` nicht volatile (thread-übergreifend). | `ReconnectManager.java:28,79,89` | (medium) |
| LOW | `ServerProcess.monitorExit`: State-Read-after-Write → toter „sauber gestoppt"-Log-Zweig + Stop/Crash-Race-Fehlklassifizierung. | `ServerProcess.java:237-246` | (high conf) |

**Empfehlung (Kern):** Platzierung pro Node serialisieren (per-Node-Lock um select+port+updateNodeStatus) ODER atomares Delta in `NodeRegistry.computeIfPresent` (Port-Set/Memory innerhalb der Lambda aus dem aktuellen Wert ableiten). Daemon-NodeStatus mit Controller-Reservierungen *unionen* statt überschreiben (oder als reine Telemetrie behandeln und Port-Belegung aus eigenen Instanzrecords ableiten). Schreibreihenfolge umdrehen: **Mongo-Plan zuerst, dann Redis-Record** — macht den Recovery-Pfad immer gangbar; verwaiste SCHEDULED nach Grace-Periode reapen.

### 4.3 Security

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| MED (war high) | Klartext-REST für gesamte Control-Plane inkl. Bootstrap-Secrets + Admin-Login. **Bewusst:** TLS-Reverse-Proxy per Doku Pflicht; Restrisiko ist Hop Proxy→Controller + direkte :8080-Exposition. | `HttpConfig.java:5-18`, `RestServer.java:180,418`, `BootstrapRoutes.java:94-98` | partially_confirmed |
| MED (war high) | Join-Token nicht an `nodeId` gebunden → Impersonation beliebiger Node + IP-Self-Whitelisting. **Trivialer Fix.** Mit Klartext-Bootstrap kombiniert wäre es high. | `FileJoinTokenStore.java:62-69`, `BootstrapServiceImpl.java:154`, `BootstrapRoutes.java:36-48` | **confirmed** |
| MED | SSE-Stream broadcastet ALLE Events an jeden authentifizierten Client → Plugin-Token liest Fleet-Event-Strom inkl. Spieler-PII. | `SseEventStreamer.java:81,110-160`, `ProxyRoutes.java:95-110`, `PluginRoutes.java:144-160` | (high conf) |
| MED | Workload-Token-Replay-Schutz opt-in pro Route + lokal-map ohne Valkey → HA-untauglich; abgefangenes (token,seq)-Paar cross-controller replaybar. | `WorkloadAuthFilter.java:52`, `WorkloadIdentityRegistry.java:86-90,323-339` | (high conf) |
| MED | Plugin-Auth Redis-SPOF (Phase-6-Schuld, sicherheits-/verfügbarkeitsrelevant im heißen Auth-Pfad). | `RedisRuntimeStore.java:107-138`, `ClusterState.java:406,441` | (high conf) |
| MED | Modul-Signaturprüfung kann in Prod still auf NOOP herabgestuft werden (nur Log-Warnung statt harter Abbruch bei `required=false`). | `PrexorCloudBootstrap.java:1694-1703`, `ModuleSigningConfig.java:77-90` | (med conf) |
| LOW | RBAC pro Handler manuell, nicht strukturell erzwungen; belegt: `/api/v1/overview/timeseries` + OverviewRoutes nur auth-, nicht permission-gegated (geringer Datenwert). | `JwtAuthMiddleware.java:118-121`, `TimeseriesRoutes.java:31-36`, `OverviewRoutes.java:24-26` | (high conf) |
| LOW | Login: User-Enumeration via Timing (kein Dummy-Argon2 bei unbekanntem User) + `toLowerCase()` ohne Locale. | `AuthManager.java:120-150` | (med conf) |
| INFO | Irreführende Raft-Kommentare im sicherheitskritischen CA-Code. | `CertificateAuthority.java:115,143` | (high conf) |

**Empfehlung (Kern):** `nodeId`-Bindung in `validate()`/`exchange()` erzwingen (trivial, hochwirksam). SSE serverseitig nach Rolle/Scope filtern (`validateHolder()` + Filter-Prädikat: WORKLOAD nur eigene Instanz/Gruppe). Native TLS-Option in `HttpConfig`/`RestServer` ODER Bootstrap/Login auf mTLS-Pfad. In Prod `modules.signing.required=false` als ConfigValidator-Fehler behandeln.

### 4.4 Tests, CI & Build-Gesundheit

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| **HIGH** | Volle E2E-Harness läuft in keiner CI; im `build` deaktiviert (`harnessExplicitlyRequested`), Nightly nur 6 Klassen. | `ci.yml:32`, `build.gradle.kts:141`, `java/build.gradle.kts:25-34` | **confirmed** |
| MED (war critical) | `spotlessCheck build` als ein Invoke killt den Java-Test-Gate; Spotless ist JETZT rot (`BaseTemplateGenerator.java`). **Nuance:** CI läuft nur bei PR auf main/master, nicht auf Rewrite-Branch-Push; bekannte Schuld. | `ci.yml:49`, `java-common.gradle.kts:21` | **confirmed** |
| MED (war high) | `assumeTrue`/Docker-Guards maskieren Nicht-Ausführung — kein muss-gelaufen-Floor. Korrektur: Docker auf ubuntu-latest aktuell zuverlässig → ITs laufen heute; latente Resilienz-Lücke. | `MongoLeaderElectorTest.java:59`, `ChangeStreamReconcilerTest.java:52` | partially_confirmed |
| MED | Fault-Injection-/Stress-Suiten laufen nirgends (`@Tag("stress")` von Nightly ausgeschlossen, in PR-CI mangels Mongo geskippt). | `FaultInjectionTest.java:18`, `build.gradle.kts:137` | (high conf) |
| MED | Cosign-Signatur-Install-Pfad im CI ungetestet (nicht im Nightly-Filter, in PR-CI geskippt). | `CosignSignedModuleInstallTest.java:1`, `build.gradle.kts:141` | (med conf) |
| MED | SSE-Delta-Fan-out (Group-Events) dauerhaft `@Disabled` (HttpClient kann kein echtes SSE-Streaming) → Kernpfad ungetestet. | `SseEventTest.java:157,197,235` | (high conf) |
| MED | Kein jacoco-Coverage-Threshold — Coverage ist Reportkosmetik; Regression während Rewrite unsichtbar. | `java-common.gradle.kts:51` | (high conf) |
| MED | Integrationssignal nur per Nightly-Cron (bis 24h Latenz) + nur single-member RS → Multi-Member-Lease/Change-Stream-Effekte nie getestet. | `nightly.yml:18`, `ci.yml:32` | (med conf) |
| LOW | Snapshot-Repo (sonatype-snapshots) + fehlende dependency-verification → Reproduzierbarkeitsrisiko. | `java-common.gradle.kts:10` | (med conf) |
| LOW | Orphan-Duplikatbaum `cloud-plugins-*` enthält eigene `src/test` außerhalb des Builds → täuscht Abdeckung vor. | `cloud-plugins/cloud-plugins-internal/src/test` | (high conf) |
| INFO (positiv) | `contracts`-Job ist von Spotless entkoppelt → liefert Proto/Startup-Drift-Signal auch bei rotem java-Job; Vorlage für die Entkopplung. | `ci.yml:17` | (high conf) |

**Empfehlung (Kern):** Spotless in eigenen Job/Step (`continue-on-error` bis Schuld getilgt) + sofort `spotlessApply`. Schlanken `integration`-Job an PR-CI hängen (Mongo single-node replSet, kritische Klassen Recovery/Instance/Token/Cosign). Fail-on-skip-Floor für die Rewrite-ITs. Konservativen jacoco-Threshold auf `cloud-controller`/`cloud-security` — nachdem das Testsignal wieder steht.

### 4.5 Ops, Reliability & Observability

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| **HIGH** | Redis hartes, clusterweites Readiness-Gate → ein Blip nimmt ganze Flotte aus dem LB. Korrektur: `/health` bleibt 200 (Liveness bereits entkoppelt, kein Prozess-Restart). | `ControllerReadinessProbe.java:13-19,54`, `PrexorCloudBootstrap.java:1574-1583`, `RestServer.java:348-352` | partially_confirmed |
| MED | Zwei divergierende Readiness-Endpunkte: `/api/v1/system/ready` pingt nichts (`stateStore()!=null` + Config-Flag) → meldet bei toter Mongo weiter READY. | `SystemRoutes.java:63-69,152-156`, `PrexorCloudBootstrap.java:1558-1572` | (high conf) |
| MED | `scheduler.last_tick.lag.millis` nicht leader-aware → wächst unbegrenzt auf demoteten Followern → flotten-weite Fehlalarme nach jedem Failover. | `MetricsCollector.java:75-80,147-159` | (high conf) |
| MED | Backup-Archive Controller-lokal → nach Failover unerreichbar/verloren; Weltdaten ohnehin ausgeschlossen (nur Config, 256-KiB-Cap). | `SnapshotService.java:25,43-47,51,77-89` | (med conf) |
| MED | Rolling-Deploy fährt nach Replacement-Timeout „continuing anyway" fort → bei deaktiviertem Health-Gate kann eine ganze Gruppe leergestoppt werden. | `DeploymentReconciler.java:86-135,185-212` | (med conf) |
| LOW | `/metrics` von Auth UND Subnet-Guard ausgenommen → interne Topologie/Lease/Leader-Identität offen lesbar. | `JwtAuthMiddleware.java:72`, `SubnetGuardMiddleware.java:41-47` | (high conf) |
| INFO (positiv) | NaN-Gauge-Problem (Leadership/SSE) auf diesem Branch bereits behoben (`retainedGaugeProbes`); nur live nachzuverifizieren. | `MetricsCollector.java:45-48,263-325` | (med conf) |

**Empfehlung (Kern):** Redis aus dem harten Readiness-Gate nehmen (degraded statt not-ready: eigenes Feld, 200 behalten solange Mongo+Scheduler ok). Den **einen** echten ControllerReadinessProbe (mit Pings) als Singleton durch Bootstrap konstruieren und in `SystemRoutes` injizieren — den Schein-Probe entfernen. Lag-Gauge mit Leadership gaten oder `leader`-Label. Backups auf geteilten/objektbasierten Storage.

### 4.6 Rewrite-Churn, toter Code & Konsistenz

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| MED (war high) | Öffentlicher HA-Guide beschreibt komplett Ratis („kein Shared-DB nötig") — Gegenteil der Mongo-Architektur. **Nuance:** identisch zu `main`, wo Ratis noch live ist; bricht erst beim Rewrite-Release. | `docs/public/en/guides/ha-controller.md:6-19`, `website/astro.config.mjs:121-123` | partially_confirmed |
| MED (war high) | README + Compose-Referenz + java/README bewerben embedded Ratis als HA-Mechanismus. | `README.md:50`, `deploy/compose/compose.yml:72-76`, `deploy/compose/README.md:86` | **confirmed** |
| MED | `RaftConfig`-Klasse + `raft:`-Block leben weiter, umgewidmet als gRPC-Advertise-Adresse; Javadoc (joint consensus, Ratis-Bind) irreführend. | `RaftConfig.java:7-42`, `controller.yml:19-36`, `Member.java:6-13` | (high conf) |
| MED | Stale Ratis-Kommentare in aktiv genutztem Produktivcode (CA, ClusterConfigChangedEvent, MetricsCollector); `org.apache.ratis`-Logger-Suppression toter Ballast. | `ClusterConfigChangedEvent.java:6-9`, `LoggingSetup.java:31-40`, `MetricsCollector.java:281` | (high conf) |
| MED | CLI präsentiert Raft-Vokabular („RAFT ADDR", `recover-cluster` → `data/raft/`, Quorum-Mathematik) für nun Mongo-gestützten Cluster. | `cli/cmd/cluster.go:70,102,317,373-390`, `install.go:884-892` | (high conf) |
| MED | Orphan-Duplikat-Plugin-Baum `cloud-plugins-{internal,proxy,server}`, 107 Java-Dateien, nicht im Build. | `java/cloud-plugins/cloud-plugins-internal` | (high conf) |
| MED | Redis/Valkey-Schuld reicht bis in öffentliche `cloud-api` (`PlatformRedisStorage` SPI-Vertrag) + stabile CLI-Flags. | `PlatformRedisStorage.java`, `DistributedLeaseManager.java`, `cli/cmd/setup.go:88-89,298-299` | (high conf) |
| LOW | `RaftConfig.dataDir` totes Feld (nirgends gelesen); CLI verweist auf nicht existierendes `data/raft/`. | `RaftConfig.java:29-42`, `cli/cmd/cluster.go:373-390` | (high conf) |
| LOW | Verwaiste Doc-Referenzen: `cluster-join-plan.md`, `docs/operations/ha-setup` fehlen. | `RaftConfig.java:11,19`, `controller.yml:23,29` | (high conf) |
| LOW | Test-Scaffolding erzeugt weiter `RaftConfig` + `data/raft`-Dirs mit Port 9190. | `TestCluster.java:383-394`, `ClusterControlServiceTest.java:100-123` | (high conf) |
| INFO | Veraltete Phasen-Nummern in Code-Kommentaren; uncommitted `cli/.audit/` + `docs/engineering/cli-*.md` (tragen teils altes Raft-Modell). | `Permission.java:102`, `cli/.audit/deep/RUBRIC.md:10` | (med conf) |

### 4.7 Code-Qualität, API-Ergonomie & Wartbarkeit

| Sev | Befund | Datei:Zeile | Verdict |
|---|---|---|---|
| MED (war high) | GroupConfig: 54-Komponenten-Record (Fund sagte 53) + 3 positionale Bruecken-Ctors + positionale `merge`/`mergePatch`/`EventChoreographer`-Re-Listings → latentes Swap-Risiko. **Korrektur:** „JsonIgnore-Felder tot" ist falsch — sie werden weiter positional durchgereicht. | `GroupConfig.java:13-84,140-457`, `GroupManager.java:152,218-277` | partially_confirmed |
| MED (war high) | Plugin-API: `ClusterView.nodes()/node()/onlinePlayers()` still leer gestubbt (`List.of()`, kein Doku/Warnung). **Korrektur:** „aus Cache bedienen" unmöglich — Cache hat keinen Node/Player-Feed; nur Fail-fast/Doku oder neuer Endpoint. Kein First-Party-Consumer. | `AbstractCloudApi.java:104-117` | partially_confirmed |
| MED | ClusterState God-Objekt (~60 Methoden, 793 Zeilen, 5 Bereiche) — Token/Auth + Aggregat-Publishing herausziehen. | `ClusterState.java:32-480` | (high conf) |
| MED | `PrexorCloudBootstrap` 1771-Zeilen-Hand-Verdrahtungs-Monolith, ~48 init-Methoden, implizite Reihenfolge. | `PrexorCloudBootstrap.java:341-1371` | (high conf) |
| MED | Unsichere Verdrahtung: `MessageDispatcher` Null-Bag (9 Setter, keine Ctor-Garantie → NPE im Hot-Path); `RestServer` 5-Overload-Kette pinnt still No-Op `InMemoryRuntimeServices`. | `MessageDispatcher.java:52-100`, `RestServer.java:67-101` | (high conf) |

**Empfehlung (Kern):** GroupConfig auf Builder + verschachtelte Sub-Records, present/absent-Modell für `merge` (leere Liste ≠ „erben"). Pflichtfelder von `MessageDispatcher` final in Ctor. `ClusterView`-Stubs entweder echt bedienen (Endpoint+SSE) oder Fail-fast.

---

## 5. Clean-Point Aktionsplan

### SOFORT (Stunden, vor allem anderen — stellt das Signal wieder her)
1. `spotlessApply` laufen lassen; `spotlessCheck` in eigenen CI-Step/Job mit `continue-on-error` entkoppeln (`ci.yml:49`). Vorlage: der bestehende `contracts`-Job.
2. **Join-Token an `nodeId` binden** — `validate()`/`exchange()` erzwingen `request.nodeId == token.nodeId()` (trivial, schließt eine echte Autorisierungslücke).
3. Compose-Referenz auf `--replSet rs0` + Init-Job fixen (`deploy/compose/compose.yml:24-25`) — sonst bootet der rewritete Controller nicht.
4. Orphan-Baum `cloud-plugins-*` löschen (107 Dateien, separater Commit) — entfernt Such-/Audit-Rauschen sofort.

### VOR-MERGE (`main`) — die zwei Korrektheits-Bugs + Test-Floor
5. **Platzierung pro Node serialisieren** (per-Node-Lock oder atomares Delta in `NodeRegistry`) — behebt doppelte Port-Vergabe (HIGH).
6. **Daemon-NodeStatus mit Controller-Reservierungen unionen** statt überschreiben (HIGH) — behebt Memory-Über-Subscription + Port-Recycling.
7. **Schreibreihenfolge Placement umdrehen** (Mongo-Plan zuerst, dann Redis) + Reaper für verwaiste SCHEDULED (behebt Strand-Bug).
8. Schlanken `integration`-Job an PR-CI: Mongo single-node replSet + Recovery/Instance/Token/Cosign-Klassen; Fail-on-skip-Floor.
9. Redis aus dem harten Readiness-Gate nehmen (degraded statt 503); den doppelten Schein-Probe in `SystemRoutes` durch den echten Singleton ersetzen.
10. SSE-Stream serverseitig nach Rolle/Scope filtern (PII-Leak).

### MITTELFRISTIG (Wochen)
11. **Doc-/CLI-/Config-Raft-Sweep:** HA-Guide neu (Mongo-Lease/Replica-Set), README/java-README/Compose-Kommentare, `RaftConfig→ClusterAdvertiseConfig` (JSON-Alias), CLI „RAFT ADDR"→„GRPC ADDR", `recover-cluster` auf Mongo umschreiben, verwaiste Doc-Refs entfernen, `org.apache.ratis`-Logger streichen, `dataDir`-Feld entfernen. **Erst kurz vor dem Rewrite-Release auf `main` mergen** (auf `main` ist die Ratis-Doc noch korrekt).
12. Go-CLI-Client aus `openapi.json` generieren + Drift-Gate (3a.2).
13. `--enable-preview` aus Produktiv-Artefakten entfernen (3a.1).
14. Recovery: Cap → `StartRetryIntent` + gestaffeltes Re-Dispatch (Jitter) beim `onAcquired`.
15. jacoco-Threshold auf Kernmodulen; Fault-Injection/Cosign/Stress in dedizierten Job; SSE-Tests reaktivieren (echter SSE-Client).
16. `MessageDispatcher`/`RestServer`-Verdrahtung in Ctor-Pflichtfelder; GroupConfig auf Builder.

### LANGFRISTIG (eigene Phase)
17. **Phase 6:** Runtime-Konvergenz zuerst auf Mongo-Change-Streams/Owner→Mongo write-through heben (Vorbedingung!), dann Plugin-Tokens + Runtime-State nach Mongo+Leader-Cache, `redis/`-Paket + `PlatformRedisStorage` (deprecaten *bevor* Konsumenten) + CLI-Flags + lettuce entfernen. Damit fallen Redis-SPOF, Readiness-Kopplung und Replay-Map-Problem zusammen weg.
18. Backups auf geteilten/objektbasierten Storage; Weltdaten-Snapshot (`SnapshotInstance`) bevor „Backup" als Feature beworben wird.
19. JAR-Auslieferung aus dem Steuer-Stream (HTTP-Pull mit SHA-256+Signatur).

---

## 6. Grenzen — was NICHT oder nur unsicher geprüft wurde

- **Kein lokaler Build/Test gelaufen.** Alle Befunde sind statisch/code-gelesen verifiziert; ich habe Controller/Daemon nicht kompiliert oder live gefahren. Die Spotless-Rot-Aussage stammt aus der Verifikation eines anderen Agenten (`spotlessCheck` → BUILD FAILED reproduziert), nicht aus meiner eigenen Ausführung.
- **NaN-Gauge-Fix** ist als „behoben auf diesem Branch" markiert, aber nur **live** abschließend verifizierbar (GC-Runde + Failover, dann `/metrics` gegen `prexorcloud.leadership.*`/`changestream.*` prüfen). Das MEMORY-Item bleibt offen bis zur Live-Gegenprobe.
- **Liveness-Guard vs. Mongo-Latenz** (Renew-RTT < safetyMargin): die Safety-Annahme ist plausibel und durch den Epoch-Fence als Defense-in-Depth abgedeckt, aber nicht unter realer p99-Mongo-Latenz/STW-GC gemessen. `confidence: medium`.
- **REAP auf In-Memory `GroupManager.exists`** (Cold-Leader-Fenster): `confidence: low` — Szenario eng, da Groups vor Leadership laden; nicht durch einen konkreten Repro belegt.
- **Befunde ohne explizites Verdict** (z.B. SSE-Broadcast, fire-and-forget-Relay, blockierender Redis-Read, InstanceTransitionValidator, CloudStateCache-RMW) sind code-belegt mit hoher/mittlerer Confidence, aber ohne unabhängige Zweit-Verifikation — als „plausibel/offen" behandeln.
- **Performance-Behauptungen** (Redis-SCAN-Latenz „mit hunderten Instanzen") sind nicht unter Last gemessen; die Mechanik (blockierend, O(N), single-thread) ist verifiziert, die quantitative Schwere nicht.

**Widersprüche zwischen Agenten (gekennzeichnet, nicht geglättet):**
- *spotlessCheck:* ein Agent „critical", Verifikation → „medium" (CI läuft nur bei PR auf main/master, nicht auf Rewrite-Branch-Push; bekannte Vorab-Schuld). Übernommen: medium.
- *HA-Guide Ratis:* Churn-Agent „high", Verifikation → „medium" (Doc identisch zu `main`, wo Ratis noch live ist → aktuell *korrekt* für das deployte Produkt; bricht erst beim Rewrite-Release). Übernommen: medium, mit Release-Koordinations-Hinweis.
- *Crash-Window Dual-Write* erscheint sowohl in „Architektur" als auch „Korrektheit" — konsistent, beide medium nach Korrektur.
- *Compose ohne `--replSet`* taucht in drei Dimensionen auf (Build/Tech-Stack/Churn) — kein Widerspruch, einheitlich als Blocker behandelt.


---

## 7. Completeness-Critic — blinde Flecken & Folgepruefungen

Verifiziert: Tier-Fan-out läuft tatsächlich parallel (`Scheduler.java:313-329`, `StructuredTaskScope.fork` pro Gruppe) und `NodeRegistry.updateStatus` ersetzt das `usedPorts`-Set komplett via `withResourceUpdate` (`NodeRegistry.java:61-74`) — beide HIGH-Bugs sind im Mechanismus belegt. Anmerkung: der Heartbeat-Write selbst ist `computeIfPresent`-atomar; der Bug ist ein *Modell*-Defekt (Overwrite-Semantik), kein Heartbeat-interner Race — die Einsortierung unter „Nebenläufigkeit" ist leicht schief.

## Blinde Flecken — was NICHT (ausreichend) untersucht wurde

- **Die gesamte Daemon-Seite fehlt als Dimension.** `cloud-daemon` erscheint nur dort, wo der Controller ihn berührt (`ServerProcess`, `ProcessKiller`, `ResourceMonitor`). Nicht geprüft: Prozess-Sandboxing/Isolation der gestarteten Server-Jars, Korrektheit des `destroyProcessTree` (Zombie-/Orphan-Risiko), Daemon-Secrets at rest (PKCS12/JWT auf Node-Disk), und ob der Daemon die Controller-Identität wirklich mTLS-pinnt oder jedem Redirect-Ziel vertraut. Das ist die halbe Laufzeit-Kritikalität und praktisch unauditiert.
- **Kein Wort zum Rewrite-Migrationspfad für bestehende Cluster.** Der Bericht behandelt Doku-Churn (Ratis-Texte), aber nicht die *Daten*-Migration: Wie kommt eine LIVE deployte Ratis-Cluster-Installation auf Mongo-Lease? `docs/runbooks/upgrade.md` deckt nur v1.0→v1.1 (Ratis-HA), nicht den Lease-Cutover. Existiert Migrationscode, oder ist es ein Wipe-and-rebuild? Für ein „Rewrite-Audit" ist das die größte fehlende Frage — potenziell Blocker, nicht nur Doku.
- **Plugin-/Proxy-Tree + Network Composition praktisch leer.** Außer dem Orphan-Baum keine Befunde zu den *echten* `proxy/{velocity,bungeecord,geyser}`- und `server/`-Plugins: Player-Routing, Proxy-seitige Auth, plugin-seitiger SSE-Cache-Konsum, und das kürzlich gelieferte Network-Composition-Feature (`InstanceCompositionPlanner`) — null Findings.
- **Modul-Sicherheitsmodell (Capability/Sandbox) ungeprüft.** Classloader-Leaks nur als Metrik erwähnt; `ModuleQuotaEnforcer`, das Capability-Modell `prexor.instance.files` (Remote-File-Read!) und die Frage „was kann ein bösartiges signiertes Modul" fehlen. Der Signatur-NOOP-Downgrade ist genannt, aber nicht das Threat-Model dahinter.
- **Restore-Pfad + Frontend.** Backups nur als „lokale Disk/256KiB". Der Restore-Pfad (hatte zuletzt 5 reale Bugs) ist nicht re-auditiert; CA-Cert-Rotation/-Expiry/`reconcileSelfClusterTls` nur über Stale-Kommentare berührt. Dashboard/Astro nur als Codegen-Contract, keine Frontend-Security (Token-Storage/XSS/CSP).

## Dünne Dimensionen / zweifelhafte Behauptungen

- **Performance/Scale ist Selbsteinschätzung, keine Messung** (im Bericht offen eingeräumt) — die „Redis-SCAN mit hunderten Instanzen"-Schwere bleibt unbelegt.
- **„Doppelte Port-Vergabe wahrscheinlich" hängt an einer UNgeprüften Prämisse:** dass `NodeSelector` deterministisch dieselbe Node UND der Port-Picker den „niedrigsten freien" wählt. Den Fan-out habe ich verifiziert, die Selektions-/Port-Determinismus-Annahme NICHT — ohne sie wäre „wahrscheinlich" → „möglich". Das ist die tragende Annahme zweier HIGH und sollte zuerst hart belegt werden.
- **Tenancy-Modell ungeklärt → SSE-PII- und Redis-Readiness-Severity wackelig.** „Bricht Multi-Tenant-Isolation" (SSE-Broadcast) und „nimmt die GESAMTE Flotte aus dem LB" (Redis) setzen implizit Multi-Tenant bzw. shared-Redis + identische Probe je Controller voraus. Ist PrexorCloud single-tenant-pro-Deployment, kollabiert die SSE-Severity; ist Redis per-Controller, kollabiert das „flotten-weit". Beides nicht festgestellt.
- **GroupConfig 53-vs-54:** der Bericht streitet mit sich selbst über die Komponentenzahl — niemand hat gezählt. Untergräbt die Präzisions-Aura; trivial verifizierbar.

## Top-Folgeprüfungen (max. Hebel)

1. **`NodeSelector` + Port-Zuteilungsalgorithmus lesen** — Determinismus belegen oder die zwei HIGH-Bugs von „wahrscheinlich" auf „möglich" zurückstufen.
2. **`cloud-daemon` end-to-end auditieren** — Prozess-Exec/Sandbox, Controller-Identitäts-Pinning, Secrets at rest, `destroyProcessTree`. Die unauditierte Hälfte des Systems.
3. **Ratis→Mongo Live-Migration belegen** — Runbook + Migrationscode suchen; falls keiner existiert, als Release-Blocker hochstufen.
4. **Tenancy-Modell feststellen** (single- vs multi-tenant; Redis shared vs per-Controller) — um SSE-Broadcast und Redis-Readiness korrekt zu severity-raten.
5. **Restore-Pfad + Modul-Capability/Sandbox** gegen ein „bösartiges Modul/böser Operator"-Threat-Model prüfen — die einzige Stelle mit Remote-File-Read.
