# Audit-Bericht RUNDE 2 — PrexorCloud Gesamtsystem

Branch: `rewrite/single-writer-control-plane` · Stand: 2026-06-21 · Scope: das gesamte System außerhalb des in R1 abgedeckten Control-Plane/HA-Kerns + Whole-System-Kohärenz. Severities sind die **verifikations-korrigierten** Werte (Verdicts eingearbeitet); wo ein Fund herabgestuft wurde, ist das markiert.

---

## 1. Executive Summary

**Gesamtzustand.** Das restliche System ist handwerklich überwiegend solide: der Daemon ist modern und korrekt im Happy-Path (StructuredTaskScope, virtuelle Threads, generation-gated Teardown, Epoch-Fence), der Plugin-/Proxy-Baum hat eine saubere Single-Cache/Single-Router-Architektur, die CLI hat einen sauberen getypten HTTP-Pfad mit korrekter Retry-Politik, die Supply-Chain-Trust-Kette (keyless cosign, inline verify, provenance/SBOM für Images und prexorctl) ist stark, und die in der 2026-06-17-Session gefixten fünf Restore-Bugs sind sauber geschlossen. Der Kern muss nirgends neu geschrieben werden.

**Aber die Stärke endet am Happy-Path.** Die schwerwiegenden neuen Befunde liegen konsistent an drei systemischen Achsen:

1. **Isolation als Papiergrenze.** Sowohl Server-Instanzen (Daemon) als auch Controller-Module laufen ohne echte OS-Isolation: nackter `ProcessBuilder` ohne cgroups/namespaces/User-Wechsel bzw. URLClassLoader-only mit advisory-Quota ohne Kill-Pfad. Reservierungen sind reine Hinweise. Ein bösartiges/übernommenes Server-Jar oder Modul hat de-facto vollen Zugriff (Node-Credentials bzw. Controller-Prozess).

2. **Autorisierung & Identität an den Rändern.** Modul-REST-Routen haben **kein** Autorisierungs-Gate (jeder gültige JWT ruft jede mutierende Modul-Route auf), die an Module durchgereichte Identität ist über `X-User-Id` spoofbar, und der Epoch-Fence wird ausgerechnet für die kontrollflusskritische HandshakeAck umgangen.

3. **Der Umbau ist nur halb fertig — und die Außenwelt weiß es nicht.** „Single-Writer, Mongo-autoritativ" gilt für Leadership/Membership/Groups/Networks, **nicht** für den wichtigsten Flow: den Instanz-Lifecycle (weiterhin Redis + In-Memory + Lease, Multi-Writer). Gleichzeitig beschreibt fast die gesamte öffentliche Doku noch das gelöschte Ratis/Raft als autoritativ — und widerspricht sich bereits selbst, weil `cluster-model.md` schon das neue Modell dokumentiert.

**Die größten neuen Hebel:** (a) echte OS-Isolation für Instanzen UND Module (löst Reservierungs-Enforcement, Env-Bereinigung, Orphan-Reaping, Modul-Blast-Radius in einem Zug); (b) ein Permission-Modell in der Modul-SPI + erzwungenes `requirePermission` im Dispatcher; (c) Instanz-Runtime-State auf denselben Mongo-Single-Writer-Pfad heben wie Leadership; (d) ein annotationsgetriebenes Event-Schema als Single Source of Truth (REST hat Codegen, der Realtime-Teil der API hat keinen); (e) die komplette Ratis-Doku-Schuld (CLI, Runbooks, öffentliche Website) als ein Paket bereinigen.

### Klare Antworten auf die offenen R1-Fragen

**Gibt es einen Ratis→Mongo-Migrationspfad?** **Nein — weder Code noch Runbook.** Repo-weite Suche nach jedem Migrations-Runner (`SchemaMigration`/`MigrationRunner`/`applyMigrations`/`migrateFrom`) liefert nichts; `upgrade.md` deckt nur In-Place-Binary-Swaps innerhalb der neuen Codebasis ab und erwähnt Ratis-Entfernung/Fencing-Init/Daten-Konversion mit keinem Wort. Schlimmer: das einzige DR-Runbook (`recover-cluster.md`) führt Operatoren weiterhin durch Ratis-interne Pfade (`data/raft/`, `sm/`-Snapshot, Raft-Group neu formen), die nach dem Rewrite nicht mehr existieren — ein Operator unter Druck arbeitet mit einem nicht mehr gültigen mentalen Modell. **Nuance (Verdict):** Der Daten-Verlust-Radius ist enger als „alles weg" — Business-State (Templates/Instances/Deployments/Groups) lag schon in v1.1 in Mongo (ADR 29); verloren/neu-generiert würden Cluster-Identität (clusterId/seedSecret), Cluster-CA, Mitgliedschaft und Join-Tokens, was Daemon-Trust bricht und ein Re-Bootstrap erzwingt. Daher **high statt critical**, aber ein hartes Release-Gate vor jedem Merge nach `main`: entweder ein getesteter One-Shot-Migrator (clusterId/seedSecret/CA-Carryover) ODER offiziell dokumentiertes wipe-and-rebuild + Refuse-to-start gegen Pre-Rewrite-Datenverzeichnisse.

**Wie ist das Tenancy-Modell?** Das ist eine **ehrliche Lücke dieser Runde** — Tenancy wurde nicht als eigene Dimension tief geprüft (siehe §7). Der einzige belastbare angrenzende Datenpunkt: Es gibt **kein** rollenbasiertes Gate auf Modul-Routen und die an Module durchgereichte Aufrufer-Identität ist client-kontrolliert (`X-User-Id`). Das deutet auf ein flaches, rollen- statt mandantenbasiertes Autorisierungsmodell hin, in dem jeder authentifizierte Principal potenziell fleet-weite, modul-exponierte Operationen auslösen kann. Ein echtes Multi-Tenant-Isolationsmodell (Mandanten-scoped Gruppen/Nodes/Backups/Module) ist in den geprüften Pfaden **nicht** erkennbar und muss separat auditiert werden, bevor PrexorCloud als mandantenfähig beworben wird.

---

## 2. Top-Risiken priorisiert

| Sev | Befund | Datei (Kernstelle) | Impact | Aufwand | Conf | Verdict |
|---|---|---|---|---|---|---|
| **high** | Kein Ratis→Mongo-Migrationspfad; DR-Runbook führt zu gelöschten Pfaden | `docs/runbooks/upgrade.md`, `recover-cluster.md` | Bestehende Cluster nicht migrierbar; DR-Anleitung gefährlich falsch | L | high | confirmed (critical→high) |
| **high** | Modul-REST-Dispatcher ohne Autorisierungs-Gate; Identität spoofbar | `RestServer.java:451-482`, `RouteRegistrar.java:9-39` | RBAC-Bypass: jede Rolle triggert mutierende Modul-Endpunkte (fleet-weiter File/Secret-Read, Backup-Delete) | M | high | confirmed |
| **high** | Server-Instanzen vollständig ungesandboxt; Daemon-Env vererbt; voller User-Kontext | `ServerProcess.java:89-90,136-143,161` | Node-Cert-Diebstahl (`node.p12`+`.node-password`)=Node-Impersonation; CPU/Disk-DoS | L | high | confirmed |
| **high** | Backup-Scope lässt autoritative `cluster_*`-Collections aus | `BackupScope.java:20-38`, `MongoClusterStore.java:72-76` | Restore nach Mongo-Wipe verliert Cluster-Identität/Roster/Join-Tokens | M | high | confirmed |
| **high** | Restore = destruktives drop+insertMany gegen LIVE-Mongo ohne Quiesce/Leader-Gate | `RestoreExecutor.java:584-604`, `BackupRoutes.java:253-303` | Change-Stream feuert in laufenden Reconciler → spawn/kill gegen halb-restaurierten State | M | high | confirmed |
| **high** | Routing ohne Load-Awareness; ignoriert maxPlayers/onlineCount (first-RUNNING wins) | `VelocityPlayerListener.java:100-112`, `BungeePlayerListener.java:107-119` | Horizontale Skalierung still ausgehebelt; eine Instanz überlastet, Rest idle | M | high | confirmed |
| **high** | Cross-Server-Transfer ACKt vor Zustellung (Velocity+Bungee) | `AbstractProxyCloudPlugin.java:96-113`, `VelocityCloudCore.java:70-80` | Transfer zu down/full Backend still verschluckt; Spieler bleibt; kein Retry | M | high | confirmed |
| **high** | Öffentliche Website dokumentiert gelöschtes Ratis/Redis als autoritativ + Selbstwiderspruch | `docs/public/en/guides/ha-controller.md`, `internals/*`, `concepts/architecture.md` vs `cluster-model.md` | HA-Setup unmöglich nach Anleitung; DR zeigt auf nicht-existente Pfade | L | high | confirmed |
| **high** | JWT in localStorage ohne CSP; same-origin Modul-Frontends | `auth-storage.ts:10-37`, `nuxt.config.ts:5` | Ein XSS = persistente Session-Hijacking der Fleet-Admin-Konsole | M | high | confirmed |
| **medium** | Instanz-Runtime-State Redis+In-Memory+Lease, nicht Mongo (widerspricht Single-Writer-These) | `ClusterState.java:166-200`, `RedisRuntimeStore.java` | Read-your-writes-Inkonsistenz cross-controller; Phase 6 = echte Migration | L | high | confirmed (high→med) |
| **medium** | NetworkComposition-Änderungen erzeugen kein Event → Routing aus veralteter Topologie | `NetworkManager.java`, `CloudStateStreamClient.java:140-154` | Topologie-Edit bei gesundem Stream propagiert nicht; Misrouting bis Stream-Drop | S | high | confirmed (high→med) |
| **medium** | Kindprozesse bei Crash/Self-Exit nie getötet | `ServerProcess.java:227-264`, `ProcessManager.java:589-601` | Orphan-CPU/Memory wenn Server langlebige Subprozesse forkt + JVM crasht | S | high | partially_confirmed (high→med) |
| **medium** | Kein daemon-seitiges TLS-Rotations-/Renewal-Verfahren | `PrexorDaemon.java:104-112`, `BootstrapManager.java:76` | Cert/CA-Ablauf legt Node permanent lahm; eingebaute Zeitbombe | M | high | (unverifiziert, hohe conf) |
| **medium** | Schema-Migrations-Framework dokumentiert, aber im Code abwesend | `upgrade.md:14-16`, `ClusterMeta.java:13` | Rollback-Entscheidung keyt auf nie emittiertes Signal; keine Schema-Evolution | M | high | confirmed (high→med) |
| **medium** | Große Live-API-Fläche fehlt in `openapi.json` (cluster/*, bootstrap/exchange) | `cluster.go`, `bootstrap.go:60`, `docs/openapi.json` | Spec ≠ Wahrheit; CLI/Dashboard driften unkontrolliert; sdk:check bleibt grün | L | high | confirmed (high→med) |
| **medium** | `logs --follow` beendet bei Stream-Ende still; kein Reconnect/Resume | `client.go:545-547`, `logs.go:287-290` | Bei Failover/Restart verpasst Operator alle Folge-Logs ohne Hinweis | M | high | partially_confirmed (high→med) |
| **medium** | Keyless-cosign bindet Signer-Identität nicht; Cert-Gültigkeit gegen Wall-Clock | `PlatformModuleSignatureVerifier.java:445-459` | Bei (Nicht-Default-)Fulcio-Root jede OIDC-Identität gültig; bei eigener CA keine Subject-Allowlist | S | med | partially_confirmed (high→low) |
| **medium** | Maintenance-Mode + Bypass-Liste am Proxy ungenutzt; Event tot beidseitig | `NetworkRouter.java:46-82`, `CloudStateStreamClient.java:140-154` | Latente Feature-Plumbing; Routing gated Maintenance nicht (per Doku gewollt) | S | high | partially_confirmed (high→low) |
| medium | Modul-Isolation Papiergrenze: EventBus.publish/subscribeAll + fleet-File-Read ohne Scope | `ModuleContext.java:70-82`, `InstanceFileAccess.java:43-57` | Geladenes Modul = voller Controller-Blast-Radius (PII, fake Events, Secrets) | L | high | (unverifiziert, hohe conf) |
| medium | Redis leckt in öffentlichen Modul-SPI-Contract | `ModuleContext.java:70-72`, `PlatformModuleManager.java:549-563` | Phase 6 bricht veröffentlichten SPI-Vertrag + still No-Op-Koordination | M | high | (unverifiziert, hohe conf) |
| medium | Kein Protokoll-Kompat-Fenster Controller↔Daemon (nur `>=1`) | `DaemonConnectionLifecycle.java:223-228` | Breaking change wird nicht am Handshake abgelehnt; stilles Mis-Execute | S | high | (unverifiziert, hohe conf) |
| medium | Controller-/Daemon-JARs ohne SBOM/Provenance | `release-jars.yml` | Sicherheitskritischste Artefakte am wenigsten attestiert | S | high | (unverifiziert, hohe conf) |
| medium | SSE-Eventvertrag 3-/4-fach handgepflegt, driftet | `events.ts`, `CloudStateStreamClient.java:140-154`, cloud-api | Rename bricht nicht am Build; tote/verschluckte Events | M | high | (unverifiziert, hohe conf) |
| weitere medium/low | siehe §3 | | | | | |

**Kennzeichnung Widerspruch/Korrektur zu R1:** R1-Top-Smell „NOOP-Verifier ist Produktiv-Default" trifft auf diesem Branch **nicht** zu — `PrexorCloudBootstrap.buildSignatureVerifier` (1674-1730) fällt fail-closed (`required=production`, `failClosed()` bei leerem trustRoot) und `ConfigValidator` (82-95) erzwingt einen harten Startup-Error. NOOP ist Test-/Dev-Pfad. Review-Fokus daher auf Identity-Pinning, nicht NOOP-Enforcement.

---

## 3. Befunde je Dimension

### 3.1 cloud-daemon

**F-D1 · Kindprozesse bei Crash/Self-Exit nie getötet · medium (high→med, partially_confirmed) · S · high**
`ServerProcess.monitorExit()` (227-264) ruft nur `onExit.accept(crashed)`; `ProcessManager.onProcessExited()` (589-601) macht `processes.remove()` + Dir-Cleanup. Der einzige Descendant-Kill `ProcessKiller.destroyProcessTree()` (86-97) ist nur über controller-initiiertes `stop()` erreichbar. **Korrektur (Verdict):** Der Port-Leak-Mechanismus ist schwach belegt — `buildCommand()` startet `java -jar` direkt, der JVM hält den Port, beim Tod gibt das OS ihn frei; JDK-Sockets sind O_CLOEXEC, Kinder erben den Listen-FD nicht. Realer Restschaden: CPU/Memory-Orphan nur wenn Server langlebige Companion-Prozesse forkt und die JVM crasht. **Fix:** in `onProcessExited()` `process.toHandle().descendants().forEach(destroyForcibly())`; sauberer: cgroup/systemd-scope killen (überlebt Re-Parenting).

**F-D2 · Instanzen vollständig ungesandboxt · high (confirmed) · L · high**
`ServerProcess.java:89-90` setzt cpu/disk nur als `CLOUD_*`-Env; `buildCommand()` (189-212) ist reines `java -Xmx -jar` — kein cgroup/systemd-scope/ulimit/namespace/seccomp (grep über gesamten Tree: null). Env-Blocking (136-143) iteriert nur die controller-gelieferte Map; `pb.environment()` enthält die komplette ererbte Daemon-Umgebung ungefiltert (kein `clear()`/Allowlist). `pb.start()` (161) ohne User-Wechsel → Server liest `node.p12`/`.node-password`/`ca.pem` (`BootstrapManager.java:64-72`) und Geschwister-Instanzen. **Impact:** Cert-Diebstahl = Node-Impersonation; CPU/Disk/FD-DoS (nur Heap via `-Xmx` begrenzt). **Fix:** transienter systemd-scope (CPUQuota/MemoryMax + DynamicUser) bzw. bwrap/OCI; Env-Allowlist statt Blocklist; unprivilegierter Service-User ohne Lesezugriff auf `config/security/`. *(LD_PRELOAD-Teilvektor ist der schwächste Beleg — setzt kompromittierten Daemon-Start voraus; der Allowlist-Punkt gilt uneingeschränkt.)*

**F-D3 · Epoch-Fence für HandshakeAck umgangen · medium · S · med**
`acceptEpoch()` gibt für `HANDSHAKE_ACK` bedingungslos `true` (`MessageDispatcher.java:148-150`) — die einzige Nachricht, die per `redirectToLeader()` umlenkt UND per `mergeAdvertisedControllers()` Seeds mischt (267-273), ohne `ack.getEpoch()` gegen `latestAcceptedEpoch` zu prüfen. Ein abgesetzter/zombie Controller kann den Daemon zu veraltetem Leader umleiten und die Seed-Rotation vergiften. **Fix:** stale Ack (`epoch != 0 && < latestAcceptedEpoch`) verwerfen; Redirect gegen bekannte Kandidatenmenge validieren statt jedes CA-gültige host:port zu übernehmen.

**F-D4 · Kein TLS-Rotations-/Renewal-Verfahren · medium · M · high**
SslContext einmal beim Start gebaut (`PrexorDaemon.java:104-112`), jeder `connect()` nutzt denselben. Re-Bootstrap ausgeschlossen sobald p12 existiert (`isBootstrapped()` prüft nur `Files.exists`). Cert/CA-Ablauf → permanenter Handshake-Fail, Node bleibt offline bis manuelles Re-Enroll. **Fix:** Renewal über bestehenden Bootstrap-Kanal + Laufzeit-Reload (DelegatingKeyManager-Muster wie controllerseitig); mindestens Cert-Restlaufzeit beim Start prüfen+warnen. Blockiert die in MEMORY notierte „renewable readiness".

**F-D5 · Console-Streaming ohne gRPC-Flow-Control auf geteiltem Stream · medium · M · med**
`sendConsoleOutput()` ruft `requestStream.onNext()` ohne `isReady()`-Gating (410-417); einziger Schutz ist per-Instanz `ConsoleRateLimiter` (Zeilen/s, keine Byte-Backpressure). Alle Instanzen + NodeStatus/StartAck/Crash/FileContent multiplexen denselben Stream. Log-Sturm → unbeschränktes Netty-Outbound-Buffer-Wachstum → Daemon-OOM (nimmt alle Instanzen mit) + Head-of-Line-Blocking für Steuerbefehle. **Fix:** `ClientCallStreamObserver.isReady()` respektieren, Console-Frames bei nicht-bereitem Stream verwerfen (verlustbar, Ring-Buffer hält Tail); optional Console auf separaten Call trennen.

**F-D6 · Hash-Verifikation auf JarCache/Runtime/Pre-Warm optional · medium · S · med**
`MessageDispatcher.java:439` setzt sha256 auf null bei leerem Wert; `jarCache.resolve()` akzeptiert null; ebenso `ArtifactProvisioner.resolveRuntimeArtifact` (`blankToNull`). Nur `ArtifactCache` erzwingt Hash. Integrität hängt allein an TLS der (ggf. externen) downloadUrl → Cache-Poisoning = RCE mit Daemon-Identität (siehe F-D2). **Fix:** sha256 für alle Runtime-/JAR-/Pre-Warm-Pfade verpflichtend; Controller führt pinned Hash je Plattform-Version.

**F-D7 · statusScheduler-Leak bei Reconnect ohne Disconnect · low · trivial · med**
`startStatusReporting()` (546-551) erstellt bedingungslos neuen Executor; `onHandshakeAckReceived()` ruft direkt. **Fix:** defensiv `stopStatusReporting()` voranstellen (idempotenter Start).

### 3.2 cloud-plugins / Proxy / Network Composition

**F-P1 · Routing ohne Load-Distribution · high (confirmed) · M · high**
`pickFromChain` gibt die ERSTE RUNNING+registrierte Instanz zurück (`VelocityPlayerListener.java:100-112`, `BungeePlayerListener.java:107-119`) über `Map.copyOf(HashMap)`-Iteration (deterministisch für feste Keymenge → dieselbe Instanz gewinnt immer). `NetworkRouter` baut nur GROUP-Chain (46-82), liest nie `playerCount`/`maxPlayers` (vorhanden in `GroupView`/`InstanceView`). **Verstärkung (Verdict):** Least-connections EXISTIERT bereits auf modul-getriebenen Transfer-APIs (`VelocityCloudPlayer.transfer` `.min(comparingInt(playerCount))`) — der Join-/Failover-Pfad wurde inkonsistent als first-wins gelassen. **Fix (trivial-mittel):** denselben `.min(playerCount)`-Selektor in `pickFromChain` wiederverwenden, Instanzen ≥maxPlayers überspringen, zufälliger Tie-Break.

**F-P2 · Transfer ACKt vor Zustellung (Velocity+Bungee) · high (confirmed) · M · high**
`pollPendingTransfers` ruft `ackTransfer` sobald `transferPlayer` `true` liefert (`AbstractProxyCloudPlugin.java:96-113`). Velocity: `createConnectionRequest(server).fireAndForget()` → `return true`, Future verworfen (`VelocityCloudCore.java:70-80`). Bungee: `connect()` fire-and-forget → `return true` (`BungeeCloudCore.java:53-72`). Controller-Seite (`WorkflowStateStore.ackTransfer` 44-46) entfernt Intent permanent — kein Retry/TTL. Transfer zu momentan down/full Backend wird als konsumiert markiert; Spieler bleibt. **Genau im Failure-Szenario, für das Transfers existieren** (Evakuierung drainender Server). **Fix:** erst nach bestätigter Zustellung acken (Velocity-Result-Future, Bungee-Connect-Callback), sonst queued lassen mit bounded retry/expiry; Ziel auf RUNNING+Kapazität prüfen. *(Geyser-Variante 81-85 ist konzeptuell gleich, gibt aber `connection.transfer()`-Bool zurück — kleine Ungenauigkeit im Originalfund.)*

**F-P3 · NetworkComposition-Änderungen erzeugen kein Event · medium (high→med, confirmed) · S · high**
`NetworkManager.create/update/delete` (49-76) mutieren nur In-Memory-Map + loggen; null Treffer für publish/eventBus. `NetworkRoutes` (89-183) publiziert kein Event → SSE-Sequenz steigt nicht. Plugin `applyEvent` (140-154) hat keinen NETWORK_*-Case. Networks nur refreshed bei `start()`, `refreshIfStreamInactive()` (nur `!isStreaming()`) oder RESYNC/Gap. **Korrektur (Verdict):** „unbegrenzt" überzeichnet — staleness self-healt bei jedem Stream-Disconnect (Restart/Leader-Change/Rolling-Upgrade = häufig); betrifft nur bereits verbundene Proxies bis zum nächsten Churn. **Fix:** `NETWORK_UPDATED/DELETED`-Event publizieren + `applyEvent`-Case → `refreshNetworks()`.

**F-P4 · Bedrock-Routing degradiert still zu JAVA ohne Floodgate · medium · S · high**
`PlayerEdition.detect` (31-33) liefert BEDROCK nur für Floodgate-UUIDs; Standalone-Geyser ohne Floodgate vergibt Java-UUIDs → JAVA für jeden Bedrock-Spieler; `NetworkRouter` (97-112) nimmt Bedrock-Pfad nur bei `edition==BEDROCK`. Kein Log/Metrik/Config-Assert. `bedrockLobbyGroup`/`bedrockFallbackGroups` werden ignoriert (Track F.1 still defeated). **Fix:** Einmal-Warnung/Metrik wenn Bedrock-konfigurierter Proxy nie BEDROCK sieht; Floodgate als harte Abhängigkeit dokumentieren; optional Startup-Assertion.

**F-P5 · Maintenance-Mode am Proxy ungenutzt · low (high→low, partially_confirmed) · S · high**
**Stark korrigiert (Verdict).** Faktisch korrekt: Routing konsultiert `maintenance`/`maintenanceBypassUuids` nie (`NetworkRouter` 46-82, `pickFromChain` filtert nur RUNNING); Felder im Cache (289-291) von nichts gelesen. **Aber:** (1) `GROUP_MAINTENANCE_CHANGED` wird nirgends publiziert (`new GroupMaintenanceChangedEvent` = 0 Treffer); Toggle läuft über `GroupRoutes.update` → `GROUP_UPDATED` → `refreshGroupsNow()` — Flag wird **nicht** stale. (2) `docs/.../scheduling-and-scaling.md:218` definiert Maintenance explizit als **scheduler-only** (skip Scaling/Replacement, laufende Instanzen bleiben) — Spieler erreichen laufende Maintenance-Instanzen ist **gewolltes** Verhalten. Verbleibt: `maintenanceBypassUuids` ist totes Plumbing + `GROUP_MAINTENANCE_CHANGED` tot beidseitig. **Fix (low):** Produktentscheid „soll Proxy-Maintenance auch Spieler gaten + Bypass honorieren?"; totes Event/Plumbing aufräumen.

**F-P6 · Initial-Connect-Fehler zeigt nicht den konfigurierten Kick-Message; Bungee fällt durch · low · trivial · high**
Bei kick-failover disconnecten beide mit `router.kickMessage()`; bei initial connect loggen sie nur und returnen (`VelocityPlayerListener.java:55-73`, `BungeePlayerListener.java:59-78`) → Velocity generischer Default, Bungee verbindet zum statisch konfigurierten Server (außerhalb Cloud-Kontrolle). **Fix:** bei leerer Initial-Chain `kickMessage(DEFAULT)` auf beiden; Bungee explizit deny.

**F-P7 · Player-Count-Deltas driften unter Burst (non-atomic RMW) · low · S · med**
`replaceInstance` (175-185) / `applyGroupOnlineDelta` (270-302) sind non-atomic read-modify-write auf volatile Map; SSE-Stream-Thread vs. refresh-Pool racen → last-write-wins, Delta verloren. Self-healt bei nächstem Full-Refresh, wird aber load-bearing sobald F-P1 Counts konsumiert. **Fix:** Mutationen serialisieren (single-thread executor/Lock) oder atomic compute auf ConcurrentHashMap.

### 3.3 cloud-modules / Modul-System

**F-M1 · Modul-REST-Dispatcher ohne Autorisierungs-Gate; Identität spoofbar · high (confirmed) · M · high**
`dispatchModuleRoute` (`RestServer.java:451-482`) ruft Handler ohne `requirePermission()`. Before-Filter (244-250) für `/api/v1/*`: nur Auth, kein AccessManager. `JwtAuthMiddleware` setzt nur `username`/`role`-Attribute; `requirePermission` (155-162) ist Opt-in, das der Dispatcher nie nutzt. `RouteRegistrar` (9-39) hat keinen Permission-Parameter → Module können keine Rolle deklarieren. Konkret: `BackupRoutes` POST `/snapshots` (47, fleet-weiter File/Secret-Read) und DELETE `/snapshots/{id}` (70) offen für jeden JWT inkl. read-only. Identität: `ApiRequest.userId()` liest Client-Header `X-User-Id` (37-39); `ModuleApiRequestAdapter.headers()` (522-527) reicht ihn unverändert durch; echter Principal wird Modulen nie übergeben. **Fix:** Permission-Deklaration in `RouteRegistrar`, `requirePermission()` im Dispatcher erzwingen (fail-closed, dediziertes `modules.invoke`-Recht), Principal server-gesetzt statt `X-User-Id`.

**F-M2 · Keyless-cosign bindet Signer-Identität nicht; Wall-Clock-Validität · medium (high→low, partially_confirmed) · S · med**
**Stark korrigiert (Verdict).** Code-Beobachtungen stimmen: `validateCertChain` (445-459) macht nur PKIX-Kette zum Anchor (`setRevocationEnabled(false)`), keine SAN/Subject/OIDC-Issuer-Prüfung; PKIX gegen Wall-Clock; `integratedTime` (416-443) ungenutzt. **Aber** die HIGH-RCE-Erzählung beruht auf falscher Prämisse: Default-Modus ist **KEYED** (`ModuleSigningConfig.java:16-21`, gepinnte Public-Keys, Cert-Pfad läuft nicht); der Cert-Pfad ist für operator-eigene CAs gedacht (`cosign-pipeline.md:442-455` lehnt keyless für Module **explizit** ab). „Jeder Google-Account → RCE" erfordert eine vom Design verbotene Nicht-Default-Konfiguration. **Echter Kern (low/Härtung):** bei operator-eigener CA fehlt eine Subject/SAN-Allowlist; Validierung gegen `integratedTime` wäre korrekter. **Fix:** bei `mode=COSIGN_BUNDLE`+Cert-Root nicht-leere Identitäts-Allowlist erzwingen; `PKIXParameters.setDate(integratedTime)`.

**F-M3 · Modul-Isolation Papiergrenze: EventBus + fleet-File-Read ohne Scope · medium · L · high**
`ModuleContext.events()` (82) liefert die volle EventBus-Instanz (`subscribeAll()` = PII Player-Join/Leave UND `publish()` = fake Events injizierbar). `prexor.instance.files` hat keinen Per-Modul-Scope: `walk`/`read` nehmen `nodeId`/`group`/`instanceId` als freie Parameter (43-57) → jedes Modul liest Configs/Secrets JEDER Instanz auf JEDEM Node. `ModuleQuotaEnforcer` (27-28) ist explizit nur Logging. Module laufen im Controller-Prozess (URLClassLoader). **Fix:** Capability-Scope im Manifest (erlaubte Gruppen/Nodes, controllerseitig erzwungen); EventBus-Bridge (kein subscribeAll auf PII, publish nur Custom-Typen); mittelfristig Stage-3-Isolation (separater Prozess/Loader + cgroup + Kill-Pfad).

**F-M4 · Redis im öffentlichen Modul-SPI-Contract · medium · M · high**
`ModuleContext` exponiert `findRedisStorage()`/`requireRedisStorage()`/`PlatformRedisStorage` als stabilen API-Contract (70-72); `withMutationLease()` nutzt weiter Redis-Lease mit stillem Null-Bypass (`PlatformModuleManager.java:549-563`). Phase 6 bricht damit einen **veröffentlichten** SPI-Vertrag + verliert still Cross-Controller-Koordination. **Fix:** Redis hinter generische `ModuleKeyValueStore`-Abstraktion legen BEVOR Module sich darauf verlassen; Mutation-Lease auf Mongo migrieren, Null-Bypass durch fail-closed ersetzen.

**F-M5 (info) · KORREKTUR zu R1: NOOP ist nicht Prod-Default** — `buildSignatureVerifier` (1674-1730) + `ConfigValidator` (82-95) fallen fail-closed in Produktion. R1-Eintrag zurücknehmen, Fokus auf F-M2.

### 3.4 cli / prexorctl

**F-C1 · Große Live-API-Fläche fehlt in openapi.json · medium (high→med, confirmed) · L · high**
Spec annotationsgetrieben (javalin-openapi). 0 Treffer für `cluster`/`bootstrap`/`seed`/`join-token`/`admin/cors`/`services/test`/`demo/upgrade`, weil die Route-Klassen 0 `@OpenApi`-Annotationen haben, aber live registriert sind (`ClusterMembersRoutes.java:54-57`). CLI handrollt hartcodiert (`bootstrap.go:60`, `cluster.go:36..344`). Dashboard generiert aus genau dieser Spec; CLI hat keine Codegen/Contract-Test. **Korrektur:** Drift-Risiko real (erhöht durch Rewrite-DTO-Änderungen), aber kein aktiver Bug/Security-Defekt → medium. **Fix:** Spec aus realen Routen generieren bzw. CI-Route-vs-Spec-Diff; mittelfristig Go-Client-Codegen.

**F-C2 · `logs --follow` beendet still bei Stream-Ende · medium (high→med, partially_confirmed) · M · high**
`client.go:545-547` returnt `nil` bei `io.EOF`; kein Reconnect in `SSEStreamWithTicket` (474-552); `id:` nie geparst, kein Last-Event-ID gesendet. **Korrektur:** Prozess exit-tet **nicht** 0 — `logstream.go:129-132` hält den Screen bewusst offen; aber ohne sichtbaren „reconnecting"-Hinweis, also Stream-Tod still, View wirkt lebendig (arguably schlimmer). Trifft genau Failover/Restart im neuen HA-Modus. **Fix:** ctx.Done von EOF unterscheiden, Auto-Reconnect (neues Ticket + Backoff), `id:` parsen + `sinceSeq` (Plumbing existiert `logs.go:280`) resumen, sichtbarer Reconnect-Indikator.

**F-C3 · SSE-Parser nicht spec-konform · medium · S · med**
`client.go:520-541` splittet nur an `\n` → CRLF (per Spec zulässig, CRLF-normalisierende Proxies in HA üblich) hängt `\r` an, leere Trennzeile wird `\r`, Events nie dispatcht. `data:` wird überschrieben statt mit `\n` gejoint. **Fix:** trailing `\r` strippen, `data:`-Zeilen pro Block akkumulieren (bufio.Scanner-Frame-Parser).

**F-C4 · `recover-cluster.md` Ratis-basiert · medium · M · high** (siehe auch F-R2 — gleiche Doku-Schuld) — `cluster.go:70` `RAFT ADDR`-Spalte, `:317` `raft=`, `:373-389` `back up data/raft/`-Prozedur. **Fix:** zusammen mit F-R2 als ein DR-Doku-Paket.

**F-C5 · Kein Token-Refresh trotz vorhandenem `/auth/refresh` · medium · S · high**
0 Treffer für `refresh` in cli/cmd/login.go, token.go, internal/api/; Endpunkt existiert serverseitig (in openapi.json). JWT-Ablauf (24h) → jedes Kommando Exit 2 `HTTP 401`, in CI/cron stiller Abbruch. **Fix:** bei 401 einmalig single-flight `/auth/refresh` + Token in Context zurücksichern; alternativ `prexorctl auth refresh` + actionable Fehlermeldung.

**F-C6 · Bootstrap-Exchange ohne Retry/Backoff bei transientem Einzel-Controller-Fehler · low · trivial · high**
`ExchangeJoinToken` (38-50) geht bei transientem Fehler nur zum nächsten Controller; bei nur einem → harter Fehlschlag. **Fix:** pro Controller wenige Versuche mit Backoff (permanent==false) analog `client.go`.

### 3.5 Backup / Restore / Disaster-Recovery

**F-B1 · Backup-Scope lässt `cluster_*` aus · high (confirmed) · M · high**
`BackupScope.MONGO_COLLECTIONS` (20-38) + Prefix `platform_` enthält keine der autoritativen `cluster_identity`/`cluster_members`/`cluster_join_tokens`/`cluster_files`/`cluster_config` (`MongoClusterStore.java:72-76`); sichert weiter legacy `config/security/join-tokens.json`. Nach Mongo-Wipe+Restore: regeneriert CA/seed, leeres Roster, verlorene Join-Tokens → manuelles Fleet-Re-Enroll. **Nuancen (Verdict):** clusterId-Verlust ist konditional (wird aus controller.yml reused, im Backup), `cluster_leadership` korrekt ausschließbar (ephemer), legacy join-tokens.json noch wired (`PrexorCloudBootstrap.java:487`) — vorsichtig löschen. **Fix:** `cluster_*` (außer leadership) in Scope; DrDrillTest erweitern.

**F-B2 · Backups controller-lokal, unerreichbar nach Failover · medium (high→med, partially_confirmed) · M · high**
**Korrigiert (Verdict).** Real: keine automatische Off-Host-Replikation (kein S3/restic im Java-Code; nur manuelles `age`+`aws s3 cp` im Runbook); Bundles lokal unter `catalog.bundleRoot` → Verlust bei Host-Verlust, neuer Leader hat sie nicht. **Aber** der Recovery-Katalog ist disk-truth, KEIN Mongo-Index (`BackupCatalog.java:16-17`) → keine Phantom-Bundles, get/verify liefern sauberes 404, nicht „NoSuchFile". Modulroute hat kein restore/verify. Der spezifische Mechanismus des Originalfunds ist falsch → medium. **Fix:** Off-Host-Transport oder Bundle-Bytes in GridFS; Recovery orthogonal zum Rewrite.

**F-B3 · Restore = destruktives drop+insertMany gegen LIVE-Mongo · high (confirmed) · M · high**
`applyRestore` (`BackupRoutes.java:253-303`) baut `new RestoreExecutor()` gegen laufenden Controller; `LiveMongoRestoreTarget.replaceCollection` (584-590) `drop()`+`insertMany`; `replaceCollectionPrefix` (592-604) droppt alle Prefix-Collections. Kein Leader-Gate, kein Maintenance/Quiesce. Leader-Reconcile (periodisch + Change-Stream auf {groups,deployments,workflow_*}) liest halb-restaurierte Mongo → kann spawn/kill gegen inkonsistenten State. **Korrektur:** Change-Stream-Set ist {groups, deployments, workflow_*}, NICHT nodes/composition_plans — aber periodischer `Scheduler.evaluate()`-Floor liest alles, Mechanismus hält. **Fix:** APPLY hinter Maintenance/Quiesce-State gaten (Reconcile+Change-Stream pausieren) oder One-Shot-Restore-Modus; Leader + Confirmation-Token erzwingen.

**F-B4 · Keine Point-in-Time/Cross-Store-Konsistenz · medium · M · high**
`create()` (88-91) läuft `copyFilesystem→dumpMongo→dumpRedis` sequenziell (drei Wall-Clock-Instants); `writeMongoCollection` (172) bare `find()` ohne causal session/snapshot readConcern → referenzielle Links zerreißbar. RS unterstützt snapshot reads, wird nicht genutzt. **Fix:** Mongo-Dump in eine causally-consistent Session mit readConcern snapshot; Redis-Scan dazu ordnen; Snapshot-Timestamp ins Manifest.

**F-B5 · „Backup" exkludiert World-Data, trunkiert Configs >64 KiB still · medium · M · high**
`SnapshotService` Javadoc (27-38): binäre World-Data out-of-scope; `READ_MAX_BYTES=256KiB` (47) über daemon-cap 64 KiB → Daemon trunkiert, Datei partiell im tar, nur in `truncatedFiles` vermerkt, Metadata zählt als written. Restore = config-only Shell. **Fix:** Feature auf „config snapshot" umbenennen ODER daemon-seitige SnapshotInstance-tar-RPC; over-cap als harte sichtbare Warnung.

**F-B6 · DR-Drill prüft nur groups+templates · medium · S · high**
`DrDrillTest.snapshot()` (131-168) liest nur `/groups`+`/templates`; nie clusterId/Roster/Instances/Runtime-State. Kann F-B1..B4 strukturell nicht fangen → falsches Vertrauen. **Fix:** clusterId+Roster, laufende Instanz+Placement, Redis-State asserten; Recovery auf ANDEREM Node.

**F-B7 · Rollback verschluckt IOExceptions · medium · S · med**
`rollback()` (407-421) `catch (IOException _) {}` (418-419); schlägt Rollback selbst fehl (disk full), bleibt working-dir torn, nur Original-Exception propagiert → ggf. nicht-bootender Controller. **Fix:** Rollback-Fehler aggregieren+in Exception/REST-Response surfacen, jeden Pfad auf error-level loggen.

**F-B8 (info) · Re-Audit: die fünf 2026-06-17-Bugs sauber gefixt** — WRONGTYPE-Skip (210), manifest-id==dir (98), optionale join-tokens (57/163), NoSuchFile-Skip, dry-run null-tolerant (279); Path-Traversal beidseitig via `resolveInside` (286/423). Restmaßnahmen sind architektonisch, nicht im Parse/IO-Layer.

### 3.6 Migration / Release / Supply-Chain

**F-R1 · Kein Ratis→Mongo-Migrationspfad · high (critical→high, confirmed) · L · high** — siehe §1. `upgrade.md` nur Binary-Swaps; `ClusterMeta.java:13` hardcoded `CURRENT_SCHEMA_VERSION=1` (Docstring noch „Raft state machine", stale); Mongo gegen v1.1-DB ohne neue ClusterMeta → bootstrappt NEUE Identität statt zu verweigern. **Fix:** One-Shot-Migrator (clusterId/seedSecret/CA-Carryover) ODER dokumentiertes wipe-and-rebuild + Refuse-to-start gegen Pre-Rewrite-Dir. **Release-Gate vor Merge.**

**F-R2 · `recover-cluster.md` vollständig Ratis-basiert · high (confirmed) · M · high**
21 Raft-Referenzen; catastrophic single-survivor-reset weist auf `data/raft/${GROUP_ID}` — unausführbar (keine Ratis-Dir auf Branch). `recover-controller.md`/`recover-mongo.md` sind bereits sauber (0 Treffer); Restdrift in `recover-cluster.md` + `ca-rotation.md`. **Scope-Caveat:** auf `main` existiert Ratis noch → dort korrekt; Defekt ist branch-lokal. **Fix:** auf Mongo-Lease/Fencing umschreiben.

**F-R3 · Schema-Migrations-Framework dokumentiert, abwesend · medium (high→med, confirmed) · M · high**
`upgrade.md:14-16/179` versprechen `migration applied:`-Log; grep = 0; kein Runner; `schemaVersion` persistiert aber nie verglichen (dead state). Rollback-Entscheidung keyt auf nie emittiertes Signal. **Korrektur:** kein Live-Hazard heute (resolviert sicher zu „kein Restop nötig") → medium. **Fix:** minimaler idempotenter Runner keyed off `schemaVersion` + Audit-Line, oder Docs interim korrigieren.

**F-R4 · Kein Protokoll-Kompat-Fenster Controller↔Daemon · medium · S · high**
`handleHandshake` (223-228) lehnt nur `protocol_version < 1`; empfangene daemonProtocolVersion nur debug-geloggt, nie gegen Controller verglichen. Source-Wire-Breakage via `ProtoContractDriftTest` (CI) gefangen, aber kein Runtime-Range im Rolling-Upgrade. **Fix:** MIN_SUPPORTED/CURRENT beidseitig; Daemon außerhalb Fensters refuse-to-schedule mit klarer node-status reason.

**F-R5 · Controller-/Daemon-JARs ohne SBOM/Provenance · medium · S · high**
`release-jars.yml` signiert nur SHA256SUMS (keine syft/cyclonedx/provenance); Images haben `provenance=max`+`sbom:true`, prexorctl hat syft. Die sicherheitskritischsten JVM-Artefakte am wenigsten attestiert. **Fix:** CycloneDX/syft pro JAR + `actions/attest-build-provenance`, SBOM in signierte SHA256SUMS falten.

**F-R6 · Toolchain-Versions-Skew · low · trivial · med**
`release-jars.yml:30` pnpm v9 vs `nightly.yml:227` pnpm v10; mongo:7 (perf-baselines) vs mongo:8 (harness/dr-drill). **Fix:** zentrale Tool-Versionen (`.tool-versions`/composite action), pnpm angleichen, mongo:8 überall.

### 3.7 Frontend / Dashboard / Docs / Design-System

**F-F1 · Öffentliche Website dokumentiert gelöschtes Ratis/Redis + Selbstwiderspruch · high (confirmed) · L · high**
`guides/ha-controller.md:6-44` (`raft:`-Block, Port 9190/tcp), `:320,339` (`back up data/raft/`); `internals/architecture.md`, `storage-schema.md`, `tech-stack.md` (Apache Ratis 3.1 als Core-Dep); `concepts/architecture.md` (Valkey/RedisEventBridge/Raft-Leases) — vs. `concepts/cluster-model.md` (neues Mongo-Modell, ADR 34). **Blast-Radius unterschätzt:** auch `getting-started/*`, `operations/ha-setup.md`, `operations/configuration.md:253-263`, `operations/upgrading.md`, `disaster-drill.md` betroffen. HA-Setup nach Anleitung unmöglich; DR zeigt auf nicht-existente Pfade. **Fix:** Architektur-Surface als Rewrite-Deliverable behandeln; Redis-Abschnitte als „transitional" markieren bis Phase 6; CI-grep auf `ratis|raft|9190` über `docs/public`.

**F-F2 · JWT in localStorage ohne CSP · high (confirmed) · M · high**
`auth-storage.ts:10-37` + `stores/auth.ts:17,73`; `nuxt.config.ts:5` ssr:false, keine CSP (kein `@nuxtjs/security`, kein routeRule, `nginx.conf` ohne CSP-Header), Google Fonts CDN (100-105), inline importmap (88-95). Ein XSS = persistente Hijacking. Verschärft, weil Controller dynamische Drittanbieter-Modul-Frontends same-origin serviert (echte XSS-Surface). **Fix:** Token in Secure/HttpOnly/SameSite=Strict-Cookie; sonst strikte CSP (`default-src 'self'; connect-src 'self' <api>; object-src 'none'; base-uri 'none'`); Fonts self-hosten.

**F-F3 · SSE token-in-URL-Fallback tot gegen ticket-only Controller · medium · S · high**
`useSseEventBus.ts:128-141`: bei Ticket-POST-Fail `params.set('token', token)` → `?token=<JWT>`. Rewrite-Controller `SseEventStreamer.java:88-102` liest NUR `ticket`, schließt sonst `Unauthorized` → Fallback authentifiziert nie, `onerror` (164-166) reconnectet ewig. Zwei Probleme: stiller Endlos-Reconnect (Stores stale) + JWT in URL (History/Proxy-Logs) ohne Nutzen. **Fix:** `else`-Branch löschen, expliziten Connection-Error + Backoff.

**F-F4 · Design-Token-Gate deckt Dashboard nicht ab · medium · M · high**
Parity-Gate (`ci.yml:209-225`) deckt nur Website; Dashboard hat keine `@prexorcloud/design-system`-Dep und hardcoded Palette (`main.css:93 --ink-1:#0a0a10`, reef primary) byte-identisch zu `dist/tokens.ts`. tokens.json-Änderung (ADR 32) updatet dist+Website, lässt Dashboard zurück, CI grün. **Fix:** Dashboard zum Consumer machen (`dist/tokens.css` importieren), Parity-Job auf Dashboard-Token-Layer erweitern.

**F-F5 · OpenAPI-Gate fängt unannotierte Routen nicht · medium · S · med**
**Korrektur zu R1/Map-Brief:** Gate existiert (`ci.yml:349-364` `syncOpenApi`). Blind spot: Processor emittiert nur `@OpenApi`-annotierte Routen → unannotierte Route serviert Traffic, fehlt in Spec, sdk:check grün; Dashboard castet schon (`stores/auth.ts:108-110`). **Fix:** CI-Assertion: jede registrierte Javalin-Route in openapi.json vorhanden. *(Überlappt F-C1.)*

**F-F6 · Kommentare/Runbooks versprechen Redis-Verhalten, das Phase 6 entfernt · low · S · high**
`stores/auth.ts:95-96` (Redis revocation list), `storage-schema.md:137-205`, `architecture.md:225`, `restore.md` (Valkey-Step). **Fix:** auf Phase-6-Checkliste; Docs+Kommentare im selben Change wie Code flippen.

### 3.8 Whole-System-Kohärenz

**F-W1 · Instanz-Runtime-State Redis+In-Memory+Lease, nicht Mongo · medium (high→med, confirmed) · L · high**
`ClusterState.reconcileInstancesFromRedis()` (166-200) lädt `runtimeStore.loadInstances()`, übernimmt fremde States via `updateInstanceState()` (180), pruned lokale Spiegel; `adoptInstanceFromRedis()` (218-230); `updateInstanceState()→saveInstance()` (246). KEIN `MongoInstanceStore` (MongoStateStore persistiert nur Templates/Deployments/Intents/Plans/Console, keinen InstanceState). Restore Redis-gekoppelt (`RestoreExecutor.java:107-150`). **Single-Writer-These gilt für den wichtigsten Flow nicht** → Read-your-writes-Inkonsistenz cross-controller (1 Reconcile-Tick lag), Phase 6 = Datenmodell-Migration + DR-Restore-Umbau. **Korrektur:** gemildert durch Node-Ownership-Partitionierung + Transition-Validator, dokumentierte geplante Arbeit, keine Korruption → medium. **Fix:** Instanz-State auf Mongo-Single-Writer (Leader schreibt, Follower via Change-Stream); Transition-Validator zu Vorab-Check degradieren; Restore von redisKeyPrefixes entkoppeln.

**F-W2 · NetworkComposition kein Event** — = F-P3 (Cross-Boundary-Sicht). medium.

**F-W3 · SSE-Eventvertrag 3-/4-fach handgepflegt, driftet · medium · M · high**
cloud-api Records (Quelle der type()-Strings) vs. Dashboard `events.ts` vs. Plugin `applyEvent`-switch (140-154) vs. api-sdk (nur REST). Konkrete Drift: `events.ts:8-10` deklariert `INSTANCE_SCHEDULED/STARTED/STOPPED` — cloud-api hat keine (nur `InstanceStateChangedEvent→INSTANCE_STATE_CHANGED`), kein Store abonniert (tot); Plugin konsumiert `PLAYER_TRANSFER`, das events.ts nicht deklariert; mehrere cloud-api-Events ohne Consumer. REST hat Codegen, der Realtime-Teil nicht. **Fix:** annotationsgetriebene Event-Registry in cloud-api als Single Source (events.json analog openapi.json) → TS/Java-Dispatch/Go generieren + sdk:check-äquivalentes Gate; tote Typen entfernen.

**F-W4 · Kein durchgängiger Versions-/Kompat-Vertrag über die drei Client-Oberflächen · medium · M · high**
Daemon (`>=1`, F-R4), Plugin REST/SSE (kein Versions-Header in `BaseControllerClient`), CLI (keine Server-Version-Prüfung). Alter Client gegen neuen Controller scheitert nicht sauber am Handshake, sondern später mit unklaren Symptomen — gerade in der Migrationsphase. **Fix:** gemeinsamer Min/Max-Bereich an allen drei Grenzen; `X-Prexor-Api-Version`-Header in REST/SSE; klare Mismatch-Fehlermeldung.

**F-W5 · Mehrere emittierte CloudEvents ohne Consumer · low · S · med**
`InstanceDrainingEvent`, `GroupMaintenanceChangedEvent`, `ClusterConfigChangedEvent`, `ChoreographyOverlay*`, `InstanceConsoleOutputEvent` — weder Plugin-switch noch `events.ts` reagieren; umgekehrt deklariert events.ts `NODE_HEARTBEAT_STALE/RESUMED`, `CAPABILITY_*`. Tote Bandbreite + Illusion von Funktionalität (z.B. Proxy könnte auf `INSTANCE_DRAINING` aktiv Joins abziehen). **Fix:** pro Event Consumer implementieren oder entfernen; Codegen (F-W3) macht ungenutzt/fehlend sichtbar.

---

## 4. Tech-Stack je Bereich (radikal hinterfragt)

| Bereich | Verdikt | Begründung |
|---|---|---|
| **Control-Plane-Store (Mongo-Lease/Fencing)** | **Behalten** | R1-Kern live-bewiesen. ABER: Single-Writer-These nur halb umgesetzt (F-W1). Postgres bleibt einzige ernsthafte Langzeit-Alternative (eine Engine, echte Transaktionen, native Migrations) — relevant, weil Mongo gerade KEINE Schema-Migrations-Story hat (F-R3) und der Backup point-in-time-Konsistenz vermissen lässt (F-B4), beides in Postgres trivial. Kein Rewrite, aber Postgres bei der nächsten großen Store-Entscheidung ernsthaft re-evaluieren. |
| **Redis/Valkey** | **Ersetzen (Phase 6 erzwingen)** | Bereits als SPOF/Multi-Writer-Quelle bestätigt. R2 zeigt: Redis steckt im öffentlichen Modul-SPI (F-M4), im DR-Restore-Pfad (F-B2/F-W1) und im Instanz-Lifecycle (F-W1) — Phase 6 ist Migration, nicht Deletion. Ziel: Plugin-Tokens + Instanz-Runtime-State + Revocation → Mongo+Leader-Cache. |
| **Daemon Prozess-Spawning (`ProcessBuilder`)** | **Verbessern → ersetzen** | Wurzel der Isolationsschwäche (F-D2), Orphan-Reaping (F-D1), Reservierungs-Enforcement. Jede Instanz in transienten systemd-scope (CPUQuota/MemoryMax/DynamicUser) oder bubblewrap/OCI-Runtime — löst alle drei in einem Zug. Daemon-Kern (gRPC/Stream/Epoch) behalten. |
| **Modul-System (URLClassLoader)** | **Verbessern, Stage-3 planen** | SPI-Ergonomie gut, Signaturkette fail-closed. Aber Isolation = Papiergrenze (F-M3) + fehlendes Permission-Modell (F-M1). Kurzfristig: SPI-Permissions + Capability-Scope. Mittelfristig: separater Prozess/Loader + cgroup + Kill-Pfad. |
| **Plugin↔Controller (REST-Poll + SSE)** | **Behalten, Protokoll erweitern** | SSE-Resync-Design solide. Aber: Transfer-Readiness braucht SSE-Push statt 2s-Poll; Routing braucht autoritative per-Instanz-Player-Counts vom Controller statt drift-anfälligem Proxy-Guess (F-P1/F-P7); NETWORK_*-Event fehlt (F-P3). |
| **Go-CLI (Cobra + hand-rolled REST)** | **Behalten, Contract teilen** | Sprachwahl/UX gut. Hauptrisiko ist der fehlende geteilte Contract (F-C1) — Go-Client-Codegen aus generierter Spec macht Drift zum Build-Fehler. |
| **Frontend (Nuxt SPA, ssr:false)** | **Behalten, härten** | Runtime-Plumbing gut. Token-Store (localStorage) + fehlende CSP (F-F2) sind die Schwachstelle, nicht das Framework. HttpOnly-Cookie + CSP + self-hosted fonts. |
| **Supply-Chain (cosign keyless)** | **Behalten, Parität herstellen** | Trust-Kette stark. Lücke: JAR-SBOM/Provenance (F-R5) + Toolchain-Skew (F-R6). |
| **Event-Vertrag (handgepflegt)** | **Ersetzen durch Codegen** | Der einzige Bereich, wo „ersetzen" ohne Vorbehalt gilt: REST hat Single Source + Codegen, Realtime nicht (F-W3). Annotationsgetriebene Event-Registry. |

---

## 5. Von „korrekt" zu „exzellent" — Whole-System-Hebel

1. **Eine Autorität für den Instanz-Lifecycle.** Eine einzige Zustandsmaschine auf Mongo (konsistent zur Leadership-Story): Leader schreibt, Follower lesen via Change-Stream. Beendet Multi-Writer-Semantik (F-W1), macht Phase 6 zum Daten-Move statt Mehrfront-Umbau, und liefert Read-your-writes-Konsistenz für Dashboard/CLI/Plugin.

2. **Event-Schema als Single Source of Truth.** Annotationsgetriebene Event-Registry in cloud-api → generiert TS (`events.ts`), Java-Dispatch-Tabelle, Go-Typen; CI-Gate analog `sdk:check`. Eliminiert F-W3, F-W5, F-P3, F-F5 strukturell (exhaustive switch macht fehlende Handler zum Build-Fehler).

3. **Durchgängiger Versions-/Kompat-Vertrag.** Min/Max-Fenster an allen drei Grenzen (Daemon-Handshake, REST/SSE-Header, CLI) mit klaren Mismatch-Fehlern (F-R4, F-W4). Macht die `upgrade.md`-Kompat-Garantie erzwingbar statt versprochen.

4. **Echte OS-Isolation als gemeinsames Primitiv.** systemd-scope/cgroup für Instanzen UND Module — ein Mechanismus, der Reservierungs-Enforcement, Env-Bereinigung, Orphan-Reaping (F-D1/D2) und Modul-Blast-Radius (F-M3) gemeinsam adressiert.

5. **Autorisierung als Querschnitt.** Permission-Deklaration in jeder Route-Registrierung (REST + Modul-SPI), fail-closed im Dispatcher, server-gesetzter Principal statt `X-User-Id` (F-M1). Voraussetzung für jedes spätere Tenancy-Modell.

6. **DR ehrlich und vollständig.** World-Data-tar-RPC + Off-Host-Transport + `cluster_*`-Scope + point-in-time-Session + Quiesce-gated Restore + ein DR-Drill, der clusterId/Roster/Instanzen/anderen Node prüft (F-B1..B7). Erst dann ist „Backup" mehr als ein Config-Snapshot.

7. **Ratis-Doku-Schuld als ein Paket tilgen** (CLI, Runbooks, öffentliche Website) + CI-grep-Gate gegen `ratis|raft|9190`, damit das alte Modell nicht still zurückkehrt (F-R2, F-C4, F-F1).

---

## 6. Clean-Point Aktionsplan — NUR Runde-2-Funde

**Klar getrennt von R1.** R1-Funde (Tier-Fan-out Port-Doppelvergabe, Daemon-NodeStatus überschreibt Reservierungen, E2E-Harness in keiner CI, spotlessCheck rot, Compose ohne `--replSet`, Redis-Readiness-SPOF, Join-Token nicht nodeId-gebunden, SSE-PII-Broadcast, Ratis-Doku-Basis) bleiben unverändert offen und sind hier nicht dupliziert.

### SOFORT (Sicherheit/Datensicherheit, klein-mittel)
- **F-M1** Permission-Gate für Modul-Dispatcher + Principal entkoppeln von `X-User-Id`.
- **F-F2** JWT aus localStorage → HttpOnly-Cookie ODER strikte CSP + self-hosted fonts (Modul-Frontends sind same-origin XSS-Surface).
- **F-F3** Token-in-URL-SSE-Fallback löschen (dead + leakt JWT).
- **F-D6** sha256 auf allen Runtime-/JAR-/Pre-Warm-Pfaden verpflichtend.
- **F-D7** statusScheduler idempotenter Start (trivial).

### VOR MERGE NACH MAIN (Release-Gates des Rewrites)
- **F-R1** Migrationsentscheidung treffen+dokumentieren (Migrator ODER wipe-and-rebuild + Refuse-to-start gegen Pre-Rewrite-Dir). **Hartes Gate.**
- **F-R2 + F-C4** `recover-cluster.md` + `cluster.go`-Output auf Mongo-Modell umschreiben (ein DR-Doku-Paket).
- **F-F1** Öffentliche Architektur-Doku auf Mongo-Single-Writer umschreiben; Redis als „transitional" markieren; CI-grep-Gate `ratis|raft|9190`.
- **F-B1** `cluster_*`-Collections in Backup-Scope (außer leadership).
- **F-B3** Restore hinter Quiesce/Leader-Gate (destruktiv gegen live Mongo).
- **F-P2** Transfer erst nach Zustellung acken (Velocity+Bungee) — Data-Loss-shaped Core-Feature.
- **F-P1** `pickFromChain` load-aware (existierenden `.min(playerCount)`-Selektor wiederverwenden).

### MITTEL (Korrektheit/Robustheit)
- **F-D1** Descendant-Kill im Exit-Pfad (oder cgroup-Kill).
- **F-D3** Epoch-Fence-Bypass für HandshakeAck schließen.
- **F-D4** TLS-Renewal-Pfad + Cert-Restlaufzeit-Warnung beim Start.
- **F-D5** Console-Streaming `isReady()`-Gating / separater Call.
- **F-P3/F-W2** `NETWORK_*`-Event + applyEvent-Case.
- **F-P4** Bedrock-ohne-Floodgate Warnung/Metrik + Doku.
- **F-C2** `logs --follow` Auto-Reconnect + Resume + sichtbarer Indikator.
- **F-C3** SSE-Parser spec-konform (CRLF + multi-line data).
- **F-C5** CLI Token-Refresh gegen vorhandenen `/auth/refresh`.
- **F-B4/B5/B6/B7** point-in-time-Session, World-Data ehrlich, DR-Drill erweitern, Rollback-Fehler surfacen.
- **F-R3** minimaler Schema-Migrations-Runner ODER Docs interim korrigieren.
- **F-R4/F-W4** Protokoll-/API-Kompat-Fenster an allen drei Grenzen.
- **F-R5** JAR-SBOM + Provenance (Parität zu Images).
- **F-M2** Subject/SAN-Allowlist + integratedTime-Validierung im Cert-Pfad.
- **F-M4** Redis aus öffentlichem Modul-SPI hinter Abstraktion; Mutation-Lease → Mongo.
- **F-F4/F-F5** Dashboard Design-System-Consumer; CI Route-vs-Spec-Vollständigkeit.
- **F-C1** openapi.json aus realen Routen / CI-Diff.

### LANGFRISTIG (Architektur — die „exzellent"-Hebel aus §5)
- **F-W1** Instanz-Runtime-State auf Mongo-Single-Writer + Change-Stream.
- **F-W3** Event-Schema-Codegen (Single Source) + Gate.
- **F-D2 / F-M3** Echte OS-Isolation (systemd-scope/bwrap/OCI) für Instanzen + Stage-3 für Module.
- **F-B2** Off-Host-Backup-Transport (S3/GridFS).
- **F-R6** zentrale Toolchain-Versionen.
- **F-P5/F-P6/F-P7/F-F6/F-W5** Cleanup-/Latent-Feature-Entscheide.

---

## 7. Was NICHT / nur unsicher geprüft wurde

- **Tenancy/Multi-Mandantenfähigkeit** — die im Brief als R2-Blindfleck genannte Dimension wurde **nicht** als eigene Tiefenanalyse erfasst. Einziger angrenzender Datenpunkt: fehlendes Authz-Gate + spoofbare Identität auf Modul-Routen (F-M1). Ein echtes Mandanten-Isolationsmodell (scoped Gruppen/Nodes/Backups/Module/Audit) ist in den geprüften Pfaden nicht erkennbar; separates Audit nötig, bevor PrexorCloud als mandantenfähig gilt.
- **Module-Sandbox-Laufzeit unter echter Last** — Quota-Enforcer, Classloader-Leak-Tracker (PhantomReference) und Capability-Proxy-Pinning wurden statisch beurteilt; kein Laufzeit-Leak-/Reload-Test (kein lokales Docker; testcontainers nur CI).
- **Restore end-to-end gegen Multi-Node-Disaster** — Mechanik (Parse/IO) verifiziert; ein echter Failover+Restore auf anderem Controller-Host wurde nicht live durchgespielt (genau die Lücke, die F-B6 am Drill kritisiert).
- **Daemon-Findings F-D4 (TLS-Renewal) und Module-F-M3/F-M4** tragen Verdict „nicht explizit verifiziert, hohe Confidence" — Code-Belege stark, aber kein abschließendes adversariales Re-Verify wie bei den confirmed/partially_confirmed Funden.
- **Verifikations-Korrekturen mit verbleibender Unsicherheit:** F-M2 (keyless-cosign) wurde auf low herabgestuft auf Basis dokumentierter Default-Konfiguration (KEYED) — falls Operatoren in der Praxis doch Fulcio-Roots als Modul-Trust-Root setzen, steigt die Severity wieder; das wurde nicht in Feld-Konfigurationen geprüft.
- **`main` vs. Branch:** F-R2/F-F1 (Ratis-Doku) sind auf `main` korrekt (Ratis existiert dort noch) — die Befunde gelten für den Rewrite-Branch. Nicht geprüft wurde, ob ein versehentlicher Doku-Merge in beide Richtungen Drift erzeugt.
- **Plugin-Orphan-Baum** (`java/cloud-plugins/cloud-plugins-*`) wurde bewusst ausgeklammert (nicht im Build); alle Plugin-Funde gegen die kurz benannten echten Verzeichnisse verifiziert.