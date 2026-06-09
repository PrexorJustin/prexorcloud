---
title: gRPC protocol
description: Internal gRPC cluster protocol — the four services (DaemonService, BootstrapService, AdminService, ClusterMembership), message overview, proto source location, and the generated reference. Defined in cloud-protocol/proto.
---

:::caution[Internal cluster protocol — not a public API]
The gRPC protocol described on these pages is the wire contract between
the controller, daemons, joining controllers, and `prexorctl`. **It is not
a public API.** Message shapes, RPC names, and service splits change
between minor releases without notice. Build against [REST](/reference/rest-api/)
or the [Java module SDK](/reference/module-sdk/) instead — those carry
stability guarantees.

These pages exist for operators debugging the cluster, contributors
modifying the protocol itself, and tooling vendors who want a documented
escape hatch rather than a reverse-engineered one.
:::

## What you'll learn

- The four services that make up the protocol and what each is for.
- The message envelopes that carry the long-lived daemon stream, and the
  payload variants inside them.
- Where the proto definitions live in the source tree, and which Java and
  Go packages they generate into.
- The compatibility model: `protocol_version`, `ProtocolConstants`,
  additive `oneof` variants, and the contract hash that gates changes.

## The four services

The protocol is split across four `.proto` files, each declaring one
service. All four share the proto3 package
`me.prexorjustin.prexorcloud.protocol`.

| Service | Direction | Transport | Use |
|---|---|---|---|
| [BootstrapService](/internals/protocol/bootstrap-service/) | Daemon → controller | TLS, no client cert | One unary RPC. Exchange a join token for an mTLS PKCS#12 keystore plus the controller's CA cert. Called once per daemon enrolment. |
| [DaemonService](/internals/protocol/daemon-service/) | Daemon ↔ controller | mTLS, bidirectional stream | The long-lived control stream: handshake, instance lifecycle, console output, crash reports, cache management, module distribution, event forwarding, and instance file access. |
| [AdminService](/internals/protocol/admin-service/) | `prexorctl` / dashboard → controller | mTLS or bearer token | Three unary RPCs for operator administration. Currently join-token CRUD. |
| ClusterMembership | Controller → controller | mTLS | One unary RPC. A joining controller exchanges a cluster join token for a CA-signed leaf cert and the current Raft peer set. See the generated [`cluster_membership_service`](#generated-reference) reference and the [cluster model](../../concepts/cluster-model.md) concept page. |

`ClusterMembership` is the newest of the four and exists only in
multi-controller (HA) deployments. It has no hand-curated page yet; the
generated reference under `_generated/` is the source of truth for its
messages.

## The daemon stream envelopes

`DaemonService` has a single RPC:

```proto
service DaemonService {
  rpc Connect(stream DaemonMessage) returns (stream ControllerMessage);
}
```

Both directions multiplex unrelated messages over one bidirectional
stream using a `oneof payload`. The daemon sends `DaemonMessage`; the
controller sends `ControllerMessage`. To add a new message type you add a
variant to the relevant `oneof` — you do not add a new RPC.

`DaemonMessage` (daemon → controller) carries these payload variants:

| Variant | Purpose |
|---|---|
| `Handshake` | First frame on the stream. Node identity, capacity, labels, running instances, host info, protocol version. |
| `NodeStatus` | Periodic resource heartbeat (CPU, memory, disk, ports). |
| `InstanceStatusUpdate` | Per-instance state transition, port, player count, uptime. |
| `ConsoleOutput` | A single line of an instance's stdout/stderr. |
| `CrashReport` | Exit code plus a log tail when an instance dies unexpectedly. |
| `Pong` | Echoes the sequence from a controller `Ping`. |
| `TemplateRequest` | Asks the controller for a template archive, optionally gated by a known hash. |
| `CacheStatus` | Snapshot of the daemon's template / jar / bootstrap caches. |
| `ErrorReport` | Non-fatal partial-failure report that does not terminate the stream. |
| `ShutdownNodeAck` | Acknowledges a `ShutdownNode` request with a drain estimate. |
| `StartInstanceAck` / `StopInstanceAck` | Delivery and outcome confirmation for the matching controller command. |
| `DaemonLogRecord` | A single Logback event mirrored from the daemon JVM. |
| `ModuleStateUpdate` | Daemon-host platform-module lifecycle state report. |
| `EventSubscribe` / `EventUnsubscribe` | Register or drop interest in controller-bus event types. |
| `InstanceFileTree` / `InstanceFileContent` | Replies to the controller's file-walk and file-read requests. |

`ControllerMessage` (controller → daemon) carries:

| Variant | Purpose |
|---|---|
| `HandshakeAck` | Session id, heartbeat interval, REST API port, protocol-compatibility verdict. |
| `StartInstance` / `StopInstance` | Schedule or stop an instance. `StartInstance` carries the full resolved `CompositionPlan`. |
| `SendCommand` | Write a command to an instance's stdin. |
| `Ping` | Liveness probe; the daemon must answer with `Pong(sequence)`. |
| `TemplateData` / `TemplateUpToDate` | Template archive, or a no-op when the daemon's `known_hash` already matches. |
| `ShutdownNode` | Ask the daemon to drain and shut down. |
| `PreWarmCache` / `RequestCacheStatus` | Pre-warm jar/bootstrap caches; request a cache snapshot. |
| `ErrorReport` | Non-fatal error notification from the controller. |
| `ModuleInstall` / `ModuleUninstall` | Push or drop a daemon-host platform module (raw jar bytes plus optional signature sidecar). |
| `ModuleEvent` | Forward a controller-bus `CloudEvent` the daemon subscribed to, as Jackson JSON. |
| `WalkInstanceFiles` / `ReadInstanceFile` | Request a structure-only file tree, or the bounded bytes of one file, under an instance directory. |

`ControllerMessage` also carries a top-level `traceparent` scalar (field
17) holding the W3C trace context of the controller span that produced
the message. It is not a payload variant, so older daemons ignore it.

Full field-by-field definitions for every message and enum
(`InstanceState`, `InstanceCategory`, `ConfigFormat`,
`StartPreparationStage`, `StartFailureDisposition`) are on the
[DaemonService](/internals/protocol/daemon-service/) page and in the
generated reference.

## Source location

The canonical definitions are four `.proto` files:

```text
java/cloud-protocol/src/main/proto/prexorcloud/
  bootstrap_service.proto
  daemon_service.proto
  admin_service.proto
  cluster_membership_service.proto
```

Shared constants live next to them in Java, not in proto:

```text
java/cloud-protocol/src/main/java/me/prexorjustin/prexorcloud/protocol/
  ProtocolConstants.java
```

The Java sources are generated into the package
`me.prexorjustin.prexorcloud.protocol` (`java_multiple_files = true`, so
each message is its own top-level class). The Go sources land under
`github.com/prexorcloud/prexorctl/proto/prexorcloud`, set by the
`go_package` option in every file.

## Generated reference

The `_generated/` directory holds a per-service Markdown dump produced by
`tools/gen-grpc-docs.sh` directly from the `.proto` files:

```text
docs/public/en/internals/protocol/_generated/
  daemon_service.md
  bootstrap_service.md
  admin_service.md
  cluster_membership_service.md
```

These files are not published — the Astro content collection excludes any
directory whose name starts with `_`. They are the underlying truth for
diffing the hand-curated service pages when proto changes land. Regenerate
them with `tools/gen-grpc-docs.sh` after editing any `.proto` file.

## Compatibility model

The wire-level version lives on the handshake:

- `Handshake.protocol_version` and `HandshakeAck.protocol_version` are
  `int32`. Both the daemon (`DaemonGrpcClient`) and the controller
  (`DaemonConnectionLifecycle`) currently send `1`.
- The controller sets `HandshakeAck.protocol_compatible = (daemon
  protocol_version >= 1)`. When it is `false`, the daemon disconnects and
  surfaces an "upgrade required" log to the operator.
- `ProtocolConstants.PROTOCOL_VERSION` is a separate, human-readable
  string constant (`"1.0"`) used in the daemon startup contract drift
  test. Do not confuse it with the `int32` wire field above — they track
  the same protocol generation but are different values and types.

`ProtocolConstants` also pins the transport defaults referenced
throughout the protocol:

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_GRPC_PORT` | `9090` | Controller gRPC listen port. |
| `DEFAULT_HEARTBEAT_INTERVAL_MS` | `30000` | Default `NodeStatus` cadence advertised in `HandshakeAck`. |
| `DEFAULT_NODE_TIMEOUT_MS` | `90000` | Three missed pongs (90 s) mark a node `UNREACHABLE`. |
| `MAX_MESSAGE_SIZE` | `100 * 1024 * 1024` (100 MB) | Max gRPC message size, sized for inline template and module-jar transfers. |

What is and is not a breaking change:

- Adding a new `oneof` payload variant does **not** bump the protocol
  version. Receivers ignore unknown variants, so an old daemon and a new
  controller (or vice versa) stay compatible.
- Adding a new non-`oneof` scalar or message field is backward-compatible
  by proto3's rules — the `traceparent` field on `ControllerMessage` is a
  worked example.
- Removing a field, reusing a field number (note the `reserved 3` and
  `reserved 21` slots in `StartInstance`), or changing a field's type is a
  **breaking change** and must bump the protocol version.

## The contract hash

Every `.proto` change is gated by a checked-in hash:

```text
java/cloud-protocol/contracts/proto-contracts.sha256
```

Update this file whenever you edit any `.proto` so the contract-drift
check stays green. Pair it with regenerating the `_generated/` reference
(`tools/gen-grpc-docs.sh`) and reconciling the hand-curated service pages
against the new dump.

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — first
  contact from a fresh daemon.
- [DaemonService](/internals/protocol/daemon-service/) — the bidirectional
  control stream and its full message catalogue.
- [AdminService](/internals/protocol/admin-service/) — operator RPCs.
