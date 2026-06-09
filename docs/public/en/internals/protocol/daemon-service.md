---
title: DaemonService
description: The long-lived bidirectional gRPC stream between daemon and controller — handshake, heartbeats, instance lifecycle, console output, crash reports, template and cache transfer, module distribution, event forwarding, and instance file access. Defined in cloud-protocol/proto.
---

:::caution[Internal cluster protocol — not a public API]
This page documents the wire contract between the controller and a daemon.
**It is not a public API.** Message shapes, RPC names, and field numbers
change between minor releases without notice. Build against
[REST](/reference/rest-api/) or the [Java module SDK](/reference/module-sdk/)
instead — those carry stability guarantees. This page is for contributors
changing the protocol and operators debugging the cluster.
:::

`DaemonService` is the single long-lived stream that carries every
controller ↔ daemon interaction after the daemon has its certificate. It
opens once, immediately after
[`BootstrapService.ExchangeJoinToken`](/internals/protocol/bootstrap-service/#exchangejointoken),
and stays open for the life of the connection.

- **Served by** the controller (`DaemonServiceImpl`, registered on the
  controller's gRPC listener — default port `9090`).
- **Called by** the daemon (`DaemonGrpcClient`), over mTLS using the
  keystore minted during bootstrap.

Instance start and stop, console streaming, template fetches, crash
reports, cache management, module distribution, event forwarding, and
instance file reads all multiplex over this one stream. There is no second
RPC — to add a message type you add a `oneof` variant, not a method.

## What you'll learn

- The single streaming RPC and the two envelope messages.
- Every `oneof` payload variant in each direction.
- The handshake, heartbeat, lifecycle, and shutdown sequences.
- The enums and the compatibility rules.

## The RPC

```proto
service DaemonService {
  rpc Connect(stream DaemonMessage) returns (stream ControllerMessage);
}
```

One bidirectional streaming RPC. Both sides start sending as soon as the
stream is established. The daemon's first frame must be a `Handshake`; the
controller answers with a `HandshakeAck`. After that, frames flow in both
directions in any order.

## The two envelopes

Each direction wraps its payloads in a single envelope message with a
`oneof payload`. The daemon sends `DaemonMessage`; the controller sends
`ControllerMessage`.

### `DaemonMessage` (daemon → controller)

```proto
message DaemonMessage {
  oneof payload {
    Handshake            handshake             =  1;
    NodeStatus           node_status           =  2;
    InstanceStatusUpdate instance_status       =  3;
    ConsoleOutput        console_output        =  4;
    CrashReport          crash_report          =  5;
    Pong                 pong                  =  6;
    TemplateRequest      template_request      =  7;
    CacheStatus          cache_status          =  8;
    ErrorReport          error_report          =  9;
    ShutdownNodeAck      shutdown_node_ack     = 10;
    StartInstanceAck     start_instance_ack    = 11;
    StopInstanceAck      stop_instance_ack     = 12;
    DaemonLogRecord      daemon_log_record     = 13;
    ModuleStateUpdate    module_state_update   = 14;
    EventSubscribe       event_subscribe       = 15;
    EventUnsubscribe     event_unsubscribe     = 16;
    InstanceFileTree     instance_file_tree    = 17;
    InstanceFileContent  instance_file_content = 18;
  }
}
```

### `ControllerMessage` (controller → daemon)

```proto
message ControllerMessage {
  oneof payload {
    HandshakeAck         handshake_ack         =  1;
    StartInstance        start_instance        =  2;
    StopInstance         stop_instance         =  3;
    SendCommand          send_command          =  4;
    Ping                 ping                  =  5;
    TemplateData         template_data         =  6;
    TemplateUpToDate     template_up_to_date   =  7;
    ShutdownNode         shutdown_node         =  8;
    PreWarmCache         pre_warm_cache        =  9;
    RequestCacheStatus   request_cache_status  = 10;
    ErrorReport          error_report          = 11;
    ModuleInstall        module_install        = 12;
    ModuleUninstall      module_uninstall      = 13;
    ModuleEvent          module_event          = 14;
    WalkInstanceFiles    walk_instance_files   = 15;
    ReadInstanceFile     read_instance_file    = 16;
  }
  string traceparent = 17;
}
```

`traceparent` (field 17) is a top-level scalar, not a payload variant. It
carries the W3C trace context of the controller span that produced the
message, and is empty when tracing is off. Because it is additive and not
part of the `oneof`, older daemons ignore it and no protocol-version bump
is needed.

## Handshake

The daemon opens the stream with `Handshake`. The controller validates the
protocol version and replies with `HandshakeAck` carrying the session id,
heartbeat cadence, and REST API port.

```proto
message Handshake {
  string node_id                             = 1;   // REQUIRED
  string version                             = 2;   // REQUIRED: daemon software version
  int64  total_memory_mb                     = 3;
  int32  available_cpus                      = 4;
  map<string, string> labels                 = 5;   // region, tier, …
  repeated RunningInstance running_instances = 6;   // for reconciliation
  string advertise_address                   = 7;   // empty = auto-detect from gRPC peer
  HostInfo host_info                         = 8;   // OS / CPU / JVM facts for observability
  int32  protocol_version                    = 9;   // REQUIRED
}

message HandshakeAck {
  string session_id            = 1;   // REQUIRED
  int64  heartbeat_interval_ms = 2;   // NodeStatus cadence (default 30000)
  int32  controller_api_port   = 3;   // REST API port for JAR downloads
  int32  protocol_version      = 4;
  bool   protocol_compatible   = 5;   // false → daemon disconnects and upgrades
}
```

`running_instances` lets the controller reconcile against a daemon that was
already running servers before the connection — for example after a
controller restart. `host_info` (`HostInfo`) carries OS, CPU, and JVM
detail used only for observability.

When `protocol_compatible` is `false`, the daemon disconnects and surfaces
an "upgrade required" log line. The controller sets it to `(daemon
protocol_version >= 1)`; both sides currently send `1`. See the
[compatibility model](/internals/protocol/#compatibility-model) for how the
`int32` wire version relates to `ProtocolConstants.PROTOCOL_VERSION`.

## Heartbeats

The controller sends `Ping(sequence=N)`; the daemon must answer with
`Pong(sequence=N)`. Three consecutive missed pongs (90 s by default, from
`ProtocolConstants.DEFAULT_NODE_TIMEOUT_MS`) mark the node `UNREACHABLE`.

```proto
message Ping { int64 sequence = 1; }
message Pong { int64 sequence = 1; }
```

Separately, the daemon pushes unsolicited `NodeStatus` frames every
`heartbeat_interval_ms` carrying CPU, memory, free disk, instance count,
and the set of used ports.

```proto
message NodeStatus {
  double cpu_usage     = 1;   // 0.0–1.0
  int64  total_memory_mb = 2;
  int64  used_memory_mb  = 3;
  int64  free_disk_mb    = 4;
  int32  instance_count  = 5;
  repeated int32 used_ports = 6;
  int64  total_disk_mb   = 7;
}
```

## Instance lifecycle

The controller schedules and stops instances; the daemon acks each command
and then streams state transitions.

### Controller → daemon

```proto
message StartInstance {
  string instance_id            =  1;   // REQUIRED
  string group                  =  2;   // REQUIRED
  reserved 3;                           // was template_name
  int32  port                   =  4;   // REQUIRED
  int32  memory_mb              =  5;   // 0 = default 512
  repeated string jvm_args      =  6;
  map<string, string> env       =  7;
  string jar_file               =  8;   // REQUIRED
  string plugin_token           =  9;   // plugin ↔ controller auth
  repeated TemplateRef templates= 10;
  int32  startup_timeout_seconds= 11;   // 0 = default 60s
  int32  shutdown_grace_seconds = 12;
  int32  max_lifetime_seconds   = 13;   // 0 = no limit
  int32  deployment_revision    = 14;
  bool   static_instance        = 15;   // preserve instance dir across restarts
  repeated string protected_paths = 16; // not overwritten on template re-apply
  InstanceCategory category     = 17;   // SERVER | PROXY
  string download_url           = 18;
  string platform               = 19;   // PAPER, PURPUR, VELOCITY, …
  string platform_version       = 20;   // e.g. 1.21.4
  reserved 21;                          // was proxy_format
  int32  max_players            = 22;   // 0 = default 100
  ConfigFormat config_format    = 23;
  CompositionPlan composition_plan = 24; // controller-resolved runtime/templates/extensions
  RuntimeIsolation isolation    = 25;
}

message StopInstance {
  string instance_id = 1;   // REQUIRED
  bool   force       = 2;   // true = SIGKILL; false = graceful stop
}

message SendCommand {
  string instance_id = 1;   // REQUIRED
  string command     = 2;   // REQUIRED: written to the server's stdin
}
```

`StartInstance` carries the full resolved `CompositionPlan` (field 24) — the
runtime artifact, ordered templates, extension artifacts, config patches,
and runtime-isolation hints the daemon needs to assemble the working
directory. The `reserved 3` and `reserved 21` slots are retired fields;
their numbers must never be reused (doing so is a breaking change).

### Daemon → controller

```proto
message StartInstanceAck {
  string instance_id                          = 1;   // mirrors StartInstance.instance_id
  bool   accepted                             = 2;   // false = rejected
  string error_message                        = 3;   // set when accepted=false
  string plan_hash                            = 4;   // mirrors CompositionPlan.plan_hash
  StartPreparationStage   stage               = 5;   // last completed/failed stage
  string error_code                           = 6;   // machine-readable failure code
  StartFailureDisposition failure_disposition = 7;   // PERMANENT vs TRANSIENT
  int32  retry_after_seconds                  = 8;   // hint when failure_disposition=TRANSIENT
}

message StopInstanceAck {
  string instance_id   = 1;   // mirrors StopInstance.instance_id
  bool   accepted      = 2;
  string error_message = 3;
}

message InstanceStatusUpdate {
  string instance_id = 1;   // REQUIRED
  InstanceState state = 2;  // REQUIRED
  int32  port         = 3;
  int32  player_count = 4;  // 0 = no players
  int64  uptime_ms    = 5;
}
```

`StartInstanceAck` is the delivery confirmation for a `StartInstance`
command: `accepted` reports whether the daemon began the launch, `stage`
names the last `StartPreparationStage` reached, and
`failure_disposition` tells the controller whether to retry. After
acceptance the daemon streams `InstanceStatusUpdate` frames as the instance
walks through `InstanceState`.

## Console output and crashes

```proto
message ConsoleOutput {
  string instance_id  = 1;
  string line         = 2;
  int64  timestamp_ms = 3;
}

message CrashReport {
  string instance_id       = 1;   // REQUIRED
  string group             = 2;   // REQUIRED
  int32  exit_code         = 3;
  repeated string log_tail = 4;   // last N stdout/stderr lines
  int64  uptime_ms         = 5;   // alive time before the crash
}
```

`ConsoleOutput` streams a server's stdout/stderr line by line while it
runs. `CrashReport` is sent once on an unexpected exit; the controller
persists it for `prexorctl crash list`.

## Templates

Daemons fetch templates lazily and cache them by hash. A `TemplateRequest`
carries the daemon's `known_hash`; the controller answers with the full
`TemplateData` archive, or a `TemplateUpToDate` no-op when the hash already
matches.

```proto
message TemplateRequest  { string template_name = 1; string known_hash = 2; }
message TemplateData     { string template_name = 1; string hash = 2; bytes tar_gz = 3; }
message TemplateUpToDate { string template_name = 1; }
```

## Cache visibility

The controller can pre-warm and inspect daemon-side caches (templates,
runtime jars, bootstrap artifacts) so the first instance on a fresh node
doesn't pay cold-start latency.

- `PreWarmCache` (controller → daemon) — a list of `PreWarmEntry` artifacts
  to fetch ahead of time.
- `RequestCacheStatus` (controller → daemon) — ask for a snapshot.
- `CacheStatus` (daemon → controller) — the snapshot:
  `TemplateCacheEntry`, `JarCacheEntry`, and `BootstrapCacheEntry` lists
  plus a total size.

## Daemon log forwarding

```proto
message DaemonLogRecord {
  int64  timestamp_ms = 1;
  string level        = 2;   // TRACE | DEBUG | INFO | WARN | ERROR
  string logger       = 3;
  string thread       = 4;
  string message      = 5;
  string throwable    = 6;
  map<string, string> mdc = 7;
}
```

Each `DaemonLogRecord` mirrors one Logback event from the daemon JVM up to
the controller so `prexorctl logs daemon <node-id>` renders it through the
same ring-buffer surface used for controller logs. The daemon does not
buffer these locally — when the controller stream is down the records are
dropped, and the daemon's rolling FILE appender keeps the disk-side history
for forensics.

## Module distribution

The controller pushes daemon-host platform modules over the stream and the
daemon reports their lifecycle state back.

```proto
message ModuleInstall {
  string module_id        = 1;   // REQUIRED
  string version          = 2;   // REQUIRED
  string sha256           = 3;   // REQUIRED: hex SHA-256 of jar_bytes
  bytes  jar_bytes        = 4;   // REQUIRED: raw module jar
  bytes  signature_bytes  = 5;   // optional .sig / cosign bundle
  string signature_kind   = 6;   // "sig" | "cosign-bundle" | ""
  string manifest_yaml    = 7;
  bool   is_upgrade       = 8;
  string previous_version = 9;   // set when is_upgrade=true
}

message ModuleUninstall { string module_id = 1; }

message ModuleStateUpdate {
  string module_id   = 1;
  string state       = 2;   // INSTALLED | WAITING | ACTIVE | STOPPING | UNLOADED | FAILED
  string last_error  = 3;   // non-empty only when state=FAILED
  int64  updated_at_ms = 4;
}
```

`ModuleInstall.jar_bytes` inlines the artifact, so a module must fit inside
`ProtocolConstants.MAX_MESSAGE_SIZE` (100 MB). Chunked transfer for larger
artifacts is deferred. The daemon sends a `ModuleStateUpdate` on every
lifecycle transition and a reconciliation snapshot on handshake.

## Event forwarding

A daemon-host module subscribes to controller-bus events by class name; the
controller forwards matching events as JSON.

```proto
message EventSubscribe   { repeated string event_types = 1; }
message EventUnsubscribe { repeated string event_types = 1; }

message ModuleEvent {
  string event_type   = 1;   // fully-qualified CloudEvent class name
  bytes  payload_json = 2;   // Jackson-serialized payload
}
```

`event_types` are fully-qualified Java class names (for example
`me.prexorjustin.prexorcloud.api.event.GroupCreatedEvent`). The controller
subscribes its own `EventBus` on the first `EventSubscribe` for a type and
forwards later events as `ModuleEvent`. Unknown class names come back as an
`ErrorReport`. On disconnect the controller cleans up subscriptions
automatically; `EventUnsubscribe` exists for dropping interest inside a
still-connected session.

## Instance file access

The controller can walk an instance's working directory and read bounded
slices of individual files — used by paste-share and the diagnostics
bundle. Every request carries a `request_id` the matching reply echoes.

```proto
message WalkInstanceFiles {
  string request_id          = 1;   // REQUIRED: matched by InstanceFileTree.request_id
  string group               = 2;   // REQUIRED
  string instance_id         = 3;   // REQUIRED
  int32  max_entries         = 4;   // 0 = daemon default
  int32  max_depth           = 5;   // 0 = daemon default
  int32  summarize_threshold = 6;   // dirs above this are summarized (0 = daemon default)
}

message InstanceFileTree {
  string request_id        = 1;   // mirrors WalkInstanceFiles.request_id
  repeated FileEntry entries = 2;
  bool   truncated         = 3;   // max_entries / max_depth hit
  string error             = 4;   // "" | INSTANCE_NOT_FOUND | DIR_UNREADABLE | …
}

message ReadInstanceFile {
  string request_id = 1;   // REQUIRED: matched by InstanceFileContent.request_id
  string group      = 2;   // REQUIRED
  string instance_id= 3;   // REQUIRED
  string path       = 4;   // REQUIRED: relative path, forward slashes
  int32  max_bytes  = 5;   // 0 = daemon default (typically 64 KiB)
  bool   tail       = 6;   // true = last max_bytes instead of first
}

message InstanceFileContent {
  string request_id      = 1;   // mirrors ReadInstanceFile.request_id
  bytes  content         = 2;   // UTF-8 bytes; empty when error != ""
  int64  total_size_bytes= 3;   // full on-disk size, for "X of Y" reporting
  bool   truncated       = 4;   // max_bytes hit
  string error           = 5;   // "" | INSTANCE_NOT_FOUND | FILE_NOT_FOUND | NOT_REGULAR_FILE | FILE_UNREADABLE | PATH_OUTSIDE_INSTANCE
}
```

The daemon caps depth and entry count, summarizes directories with more
than `summarize_threshold` children into a single `FileEntry` with
`summary=true` (so a 30k-leaf world folder doesn't blow the size budget),
caps `max_bytes` on top of the requested value, and refuses any `path` that
escapes the instance working directory (`PATH_OUTSIDE_INSTANCE`).

## Shutdown

```proto
message ShutdownNode    { string reason = 1; }
message ShutdownNodeAck {
  int32 running_instances       = 1;
  int32 estimated_drain_seconds = 2;
}
```

When an operator drains a node the controller sends `ShutdownNode`; the
daemon acks with the count of still-running instances and a drain-time
estimate, then begins a graceful shutdown of each instance.

## Error reporting

```proto
message ErrorReport {
  string error_code         = 1;   // e.g. CACHE_DOWNLOAD_FAILED
  string error_message      = 2;
  string context            = 3;   // related instance id, template name, …
  int32  retry_after_seconds= 4;   // 0 = no retry hint
}
```

Either side sends `ErrorReport` for a non-fatal partial failure that does
not warrant tearing down the stream. Unlike a gRPC status error, the stream
stays open.

## Enums

| Enum | Values |
|---|---|
| `InstanceState` | `SCHEDULED`, `PREPARING`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `CRASHED`, `DRAINING` |
| `InstanceCategory` | `SERVER`, `PROXY` |
| `ConfigFormat` | `PAPER`, `SPIGOT`, `VELOCITY`, `BUNGEECORD`, `GEYSER` |
| `StartPreparationStage` | `VALIDATION`, `TEMPLATE_APPLY`, `RUNTIME_PROVISION`, `EXTENSION_PROVISION`, `BOOTSTRAP_WARMUP`, `VARIABLE_SUBSTITUTION`, `CONFIG_PATCH`, `PROCESS_START` |
| `StartFailureDisposition` | `PERMANENT`, `TRANSIENT` |

Every enum reserves `0` for its `*_UNSPECIFIED` member, as proto3 requires.

## Compatibility

- Adding a `oneof` variant is backward-compatible — receivers ignore
  unknown variants. **Do not bump the protocol version** for an additive
  variant.
- Adding a non-`oneof` scalar or message field is backward-compatible too —
  `traceparent` is the worked example.
- Removing a field, reusing a reserved number, or changing a field type is a
  breaking change. Bump the wire version and update
  `java/cloud-protocol/contracts/proto-contracts.sha256`.

See the [compatibility model](/internals/protocol/#compatibility-model) on
the parent page for the full rules and the version constants.

## Generated reference

The field-by-field dump generated straight from the `.proto` is the
underlying truth for this page:

```text
docs/public/en/internals/protocol/_generated/daemon_service.md
```

Regenerate it with `tools/gen-grpc-docs.sh` after editing the proto. See
[gRPC protocol → generated reference](/internals/protocol/#generated-reference).

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — the one-shot
  RPC that runs before this stream opens.
- [AdminService](/internals/protocol/admin-service/) — operator RPCs.
- [Concepts → Cluster model](/concepts/cluster-model/) — how these messages
  map to operator-visible node state.
