---
title: BootstrapService
description: First-contact gRPC service used by a fresh daemon to exchange a single-use join token for an mTLS PKCS#12 keystore, the controller's CA certificate, and an optional CLI token. Defined in cloud-protocol/proto.
---

:::caution[Internal cluster protocol — not a public API]
This page documents the wire contract between the controller and a fresh
daemon. **It is not a public API.** Message shapes, RPC names, and field
numbers change between minor releases without notice. Build against
[REST](/reference/rest-api/) or the [Java module SDK](/reference/module-sdk/)
instead — those carry stability guarantees. This page is for contributors
changing the protocol and operators debugging enrolment.
:::

`BootstrapService` is the daemon's one-shot first-contact RPC. A fresh
daemon has no certificate yet, so this is the one call it makes over
server-side TLS without presenting a client cert. It exchanges a join token
for the mTLS material every later call needs.

- **Served by** the controller (`BootstrapServiceImpl`, on the same gRPC
  listener as the other services — default port `9090`). The mTLS
  enforcement interceptor exempts `BootstrapService` precisely because the
  caller has no cert yet; the join token is the credential.
- **Called by** the daemon (`BootstrapManager`) once per enrolment, before
  it opens [`DaemonService.Connect`](/internals/protocol/daemon-service/#the-rpc).

The same logic backs the REST setup wizard's enrolment endpoint, so the
gRPC and HTTP paths produce identical artifacts.

## What you'll learn

- The single unary RPC and its request / response shapes.
- The trust model (CA cert returned in-band).
- The single-use / replay contract and the status codes.

## The RPC

### `ExchangeJoinToken`

```proto
service BootstrapService {
  rpc ExchangeJoinToken(ExchangeJoinTokenRequest) returns (ExchangeJoinTokenResponse);
}

message ExchangeJoinTokenRequest {
  string join_token = 1;
  string node_id    = 2;
}

message ExchangeJoinTokenResponse {
  bytes  pkcs12             = 1;   // keystore: daemon's private key + signed leaf cert
  string pkcs12_password    = 2;   // keystore password (random per exchange)
  bytes  ca_certificate_pem = 3;   // controller's internal CA cert (the trust anchor)
  string cli_token          = 4;   // optional DAEMON_HOST JWT; "" from older controllers
}
```

One unary RPC. The daemon presents the join token (minted by
[`AdminService.CreateJoinToken`](/internals/protocol/admin-service/#createjointoken)
or `prexorctl token create`) and its chosen `node_id`. On success the
controller mints a node certificate and returns:

- `pkcs12` — a PKCS#12 keystore holding the daemon's freshly minted private
  key and its CA-signed leaf certificate.
- `pkcs12_password` — a random password for that keystore.
- `ca_certificate_pem` — the controller's internal CA certificate, which the
  daemon installs as its trust anchor for future mTLS calls.
- `cli_token` — an optional JWT scoped to the `DAEMON_HOST` role, so
  `prexorctl` on the daemon's host can save a context and skip an explicit
  `prexorctl login`. It is empty when the controller predates this field;
  older daemons ignore it.

## Trust model

The CA certificate is returned in-band in the response. This is sound
because the join token is a high-entropy bearer secret delivered
out-of-band over an authenticated channel (operator → daemon host). An
attacker would need the join token to forge a matching response — the exact
secret the out-of-band channel protects.

As a side effect of a successful exchange, the controller registers the
caller's source IP as a `/32` (or `/128`) in its allowed-subnets list, so
the daemon's subsequent mTLS connections pass the subnet guard. Loopback
and unresolvable peers are skipped.

## Single-use and replay protection

Join tokens are single-use. After a successful exchange the controller
consumes the token (`JoinTokenStore.consume`) and registers the node. A
second `ExchangeJoinToken` with the same token finds nothing to validate
and is rejected. Tokens also carry a TTL and a recorded `node_id`; the
`node_id` is metadata captured at creation, not a per-call binding enforced
by this RPC.

## Status codes

`ExchangeJoinToken` collapses every credential failure into one status —
the controller does not distinguish "unknown" from "expired" from
"already consumed" on the wire.

| gRPC status | Cause |
|---|---|
| `OK` | Token valid; keystore, CA cert, and CLI token returned. |
| `UNAUTHENTICATED` | Token invalid, expired, or already consumed (`"Invalid or expired join token"`). |
| `INTERNAL` | Certificate minting or I/O failed server-side. |

After any failure the daemon should log clearly and exit non-zero — there
is no productive retry without a fresh token.

## After bootstrap

The daemon writes the keystore and CA cert to its `data/certs/` directory,
drops the join token from its config, optionally saves `cli_token` as a CLI
context, and switches to mTLS for every later call —
[`DaemonService.Connect`](/internals/protocol/daemon-service/) first.

## Generated reference

The field-by-field dump generated straight from the `.proto` is the
underlying truth for this page:

```text
docs/public/en/internals/protocol/_generated/bootstrap_service.md
```

Regenerate it with `tools/gen-grpc-docs.sh` after editing the proto. See
[gRPC protocol → generated reference](/internals/protocol/#generated-reference).

## Next up

- [DaemonService](/internals/protocol/daemon-service/) — the long-lived
  stream the daemon opens after bootstrap.
- [AdminService → CreateJoinToken](/internals/protocol/admin-service/#createjointoken)
  — how operators mint the tokens consumed here.
- [Installation](/getting-started/installation/) — the operator-side
  walkthrough that drives this RPC end to end.
