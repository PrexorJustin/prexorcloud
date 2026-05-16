---
title: DaemonService
description: Long-lived bidirectional gRPC stream between daemon and controller — handshake, instance lifecycle, console output, crash reports, module distribution, and event forwarding.
---

:::caution[Internal cluster protocol — Not a public API]
This page documents the bidi gRPC stream that carries every
controller ↔ daemon interaction after the bootstrap exchange. It is
not a public API and is subject to change between minor releases.
Build against the [REST API](/reference/rest-api/) or the
[Java SDK](/reference/module-sdk/) for stable surfaces.
:::

`DaemonService` is the long-lived bidi stream that opens immediately
after [`BootstrapService.ExchangeJoinToken`](/internals/protocol/bootstrap-service/#exchangejointoken).
Every later interaction — instance start / stop, console streaming,
template fetches, crash reports, module distribution, controller-bus
event forwarding — multiplexes over this single stream.

## What you'll learn

- The single RPC and the two envelope types.
- The full set of `oneof` payloads in each direction.
- The handshake, heartbeat, and shutdown sequences.

## RPC

```protobuf
service DaemonService {
  rpc Connect(stream DaemonMessage) returns (stream ControllerMessage);
}
```

Both sides start sending immediately. The first daemon message must be
a `Handshake`; the controller responds with `HandshakeAck`.

## Envelopes

### `DaemonMessage` (daemon → controller)

```protobuf
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
  }
}
```

### `ControllerMessage` (controller → daemon)

```protobuf
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
  }
}
```

## Handshake

The daemon's first message is `Handshake`. The controller validates the
protocol version and replies with `HandshakeAck` carrying the session
id and the heartbeat interval.

```protobuf
message Handshake {
  string node_id                                = 1;   // REQUIRED
  string version                                = 2;   // REQUIRED
  int64  total_memory_mb                        = 3;
  int32  available_cpus                         = 4;
  map<string, string> labels                    = 5;
  repeated RunningInstance running_instances    = 6;   // for reconciliation
  string advertise_address                      = 7;
  HostInfo host_info                            = 8;
  int32  protocol_version                       = 9;   // REQUIRED
}

message HandshakeAck {
  string session_id            = 1;
  int64  heartbeat_interval_ms = 2;
  int32  controller_api_port   = 3;
  int32  protocol_version      = 4;
  bool   protocol_compatible   = 5;   // false → daemon should disconnect
}
```

If `protocol_compatible=false`, the daemon disconnects and surfaces an
"upgrade required" log line.

## Heartbeats

The controller sends `Ping(sequence=N)` every `heartbeat_interval_ms`
(default 30s). The daemon must respond with `Pong(sequence=N)`. **Three
consecutive missed pongs (90s default)** mark the node as
`UNREACHABLE`.

The daemon also pushes unsolicited `NodeStatus` updates carrying CPU,
memory, free disk, instance count, and used ports.

## Instance lifecycle

### Controller → daemon

```protobuf
message StartInstance {
  string instance_id            =  1;   // REQUIRED
  string group                  =  2;   // REQUIRED
  int32  port                   =  4;   // REQUIRED
  int32  memory_mb              =  5;
  repeated string jvm_args      =  6;
  map<string, string> env       =  7;
  string jar_file               =  8;   // REQUIRED
  string plugin_token           =  9;
  repeated TemplateRef templates= 10;
  int32  startup_timeout_seconds= 11;
  int32  shutdown_grace_seconds = 12;
  int32  max_lifetime_seconds   = 13;
  int32  deployment_revision    = 14;
  bool   static_instance        = 15;
  repeated string protected_paths = 16;
  InstanceCategory category     = 17;
  string download_url           = 18;
  string platform               = 19;
  string platform_version       = 20;
  int32  max_players            = 22;
  ConfigFormat config_format    = 23;
  CompositionPlan composition_plan = 24;
  RuntimeIsolation isolation    = 25;
}

message StopInstance {
  string instance_id = 1;        // REQUIRED
  bool   force       = 2;        // true = SIGKILL
}

message SendCommand {
  string instance_id = 1;        // REQUIRED
  string command     = 2;        // REQUIRED
}
```

### Daemon → controller

```protobuf
message StartInstanceAck {
  string instance_id                                 = 1;
  bool   accepted                                    = 2;
  string error_message                               = 3;
  string plan_hash                                   = 4;
  StartPreparationStage stage                        = 5;
  string error_code                                  = 6;
  StartFailureDisposition failure_disposition        = 7;
  int32  retry_after_seconds                         = 8;
}

message StopInstanceAck {
  string instance_id   = 1;
  bool   accepted      = 2;
  string error_message = 3;
}

message InstanceStatusUpdate {
  string instance_id = 1;        // REQUIRED
  InstanceState state = 2;       // REQUIRED
  int32  port         = 3;
  int32  player_count = 4;
  int64  uptime_ms    = 5;
}
```

`InstanceState` covers `SCHEDULED`, `PREPARING`, `STARTING`, `RUNNING`,
`STOPPING`, `STOPPED`, `CRASHED`, `DRAINING`.

## Console output and crashes

```protobuf
message ConsoleOutput {
  string instance_id  = 1;
  string line         = 2;
  int64  timestamp_ms = 3;
}

message CrashReport {
  string instance_id          = 1;   // REQUIRED
  string group                = 2;   // REQUIRED
  int32  exit_code            = 3;
  repeated string log_tail    = 4;
  int64  uptime_ms            = 5;
}
```

Console output is line-streamed continuously while an instance is
running. `CrashReport` is sent once on unexpected exit; the controller
persists it for `prexorctl crash list`.

## Templates

```protobuf
message TemplateRequest {
  string template_name = 1;
  string known_hash    = 2;   // empty = no cache
}

message TemplateData {
  string template_name = 1;
  string hash          = 2;   // SHA-256 of tar.gz content
  bytes  tar_gz        = 3;
}

message TemplateUpToDate {
  string template_name = 1;
}
```

Daemons request templates lazily; the controller responds with either
the full `TemplateData` blob or a `TemplateUpToDate` cache-hit ack.

## Cache visibility

`CacheStatus`, `RequestCacheStatus`, and `PreWarmCache` let the
controller pre-warm and inspect daemon-side caches (templates, JARs,
bootstrap artifacts). See the proto file for full message shapes.

## Daemon log forwarding

```protobuf
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

Single Logback events mirrored from the daemon JVM up to the controller
so `prexorctl logs daemon <node-id>` can render them through the same
ring buffer surface used for controller logs. The daemon does **not**
buffer locally — when the controller stream is down, records are
dropped (the daemon's rolling FILE appender keeps disk-side history).

## Module distribution (Layer 7)

```protobuf
message ModuleInstall {
  string module_id          = 1;   // REQUIRED
  string version            = 2;   // REQUIRED
  string sha256             = 3;   // REQUIRED
  bytes  jar_bytes          = 4;   // REQUIRED
  bytes  signature_bytes    = 5;   // optional cosign sidecar
  string signature_kind     = 6;   // "sig" | "cosign-bundle" | ""
  string manifest_yaml      = 7;
  bool   is_upgrade         = 8;
  string previous_version   = 9;
}

message ModuleUninstall {
  string module_id = 1;
}

message ModuleStateUpdate {
  string module_id    = 1;
  string state        = 2;   // INSTALLED | WAITING | ACTIVE | STOPPING | UNLOADED | FAILED
  string last_error   = 3;
  int64  updated_at_ms= 4;
}
```

`jar_bytes` inlines the artifact for simplicity; modules larger than
`ProtocolConstants.MAX_MESSAGE_SIZE` will switch to chunked transfer
in a future revision.

## Event forwarding

```protobuf
message EventSubscribe   { repeated string event_types = 1; }
message EventUnsubscribe { repeated string event_types = 1; }

message ModuleEvent {
  string event_type   = 1;   // fully-qualified Java class name
  bytes  payload_json = 2;   // Jackson-serialised event payload
}
```

A daemon module that subscribes to controller-bus events sends
`EventSubscribe` with the fully-qualified `CloudEvent` class names.
The controller subscribes its own bus on first arrival and forwards
matching events as `ModuleEvent` messages, which the daemon's local
bus re-publishes. On daemon disconnect the controller cleans up
automatically; `EventUnsubscribe` is for live live-unsubscribe in a
connected session.

## Shutdown

```protobuf
message ShutdownNode {
  string reason = 1;
}

message ShutdownNodeAck {
  int32 running_instances        = 1;
  int32 estimated_drain_seconds  = 2;
}
```

The controller sends `ShutdownNode` when an operator drains a node;
the daemon acks with the count of still-running instances and an
estimate of drain time, then begins graceful instance shutdown.

## Error reporting

```protobuf
message ErrorReport {
  string error_code         = 1;
  string error_message      = 2;
  string context            = 3;
  int32  retry_after_seconds= 4;
}
```

Either side can send `ErrorReport` for non-fatal partial failures
(e.g. `CACHE_DOWNLOAD_FAILED`) without terminating the stream.

## Compatibility notes

- Adding a new variant to either `oneof` is backward-compatible —
  receivers ignore unknown variants. **Do not bump
  `PROTOCOL_VERSION`** for additive variants.
- Removing a variant or repurposing a field number is a breaking
  change and requires a `PROTOCOL_VERSION` bump plus regenerated
  `java/cloud-protocol/contracts/proto-contracts.sha256`.

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — the
  one-shot RPC that runs before this stream opens.
- [AdminService](/internals/protocol/admin-service/) — operator RPCs.
- [Concepts → Daemon lifecycle](/concepts/cluster-model/) — how
  these messages map to operator-visible node state.
