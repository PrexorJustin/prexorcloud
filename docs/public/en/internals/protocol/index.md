---
title: gRPC Protocol
description: Internal gRPC cluster protocol — overview of DaemonService, BootstrapService, and AdminService. Generated from cloud-protocol/proto.
---

:::caution[Internal cluster protocol — Not a public API]
The gRPC protocol described on these pages is the wire contract between
the controller, daemons, and `prexorctl`. **It is not a public API.**
Message shapes, RPC names, and service splits are subject to change
between minor releases. Build against [REST](/reference/rest-api/) or the
[Java SDK](/reference/module-sdk/) instead — those carry stability
guarantees.

These pages exist for operators debugging the cluster, contributors
modifying the protocol itself, and tooling vendors with a documented
escape hatch.
:::

## What you'll learn

- The three services that make up the protocol and what each is for.
- Where the proto definitions live in the source tree.
- The compatibility model (`PROTOCOL_VERSION`, additive oneof
  variants).

## The three services

| Service | Direction | Transport | Use |
|---|---|---|---|
| [BootstrapService](/internals/protocol/bootstrap-service/) | Daemon → controller | mTLS-less | Exchange a join token for an mTLS PKCS#12 + the controller's CA cert. One-shot, called once per daemon lifetime. |
| [DaemonService](/internals/protocol/daemon-service/) | Daemon ↔ controller | mTLS, bidi-stream | The long-lived control stream — handshakes, instance lifecycle, console output, crash reports, module install / event forwarding. |
| [AdminService](/internals/protocol/admin-service/) | `prexorctl` / dashboard → controller | mTLS or token | Operator administrative RPCs — currently join-token CRUD. |

## Source location

```
java/cloud-protocol/src/main/proto/prexorcloud/
  bootstrap_service.proto
  daemon_service.proto
  admin_service.proto
```

The Java sources are generated into
`me.prexorjustin.prexorcloud.protocol`; the Go sources land under
`github.com/prexorcloud/prexorctl/proto/prexorcloud`.

## Compatibility model

- `Handshake.protocol_version` and `HandshakeAck.protocol_version`
  carry the integer version. Current value: **1**.
- Additive `oneof` variants do **not** bump the protocol version —
  receivers ignore unknown variants.
- Adding a non-oneof field is similarly backward-compatible by
  proto3's rules.
- Removing a field, repurposing a field number, or changing a field
  type is a **breaking change** and bumps `PROTOCOL_VERSION`.
- The hash in `java/cloud-protocol/contracts/proto-contracts.sha256` must be updated
  whenever the proto files change.

When `HandshakeAck.protocol_compatible=false`, the daemon is expected
to disconnect and surface an "upgrade required" log to the operator.

## Disclaimer banner

Every page below repeats the "Internal cluster protocol — not a public
API" disclaimer in its lede. If you find yourself wanting to depend
on these messages from third-party code, file an issue first — we'd
rather expose a stable surface than have you reverse-engineer a
moving target.

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — first
  contact from a fresh daemon.
- [DaemonService](/internals/protocol/daemon-service/) — the bidi
  control stream.
- [AdminService](/internals/protocol/admin-service/) — operator
  RPCs.
