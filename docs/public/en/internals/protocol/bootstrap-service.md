---
title: BootstrapService
description: First-contact gRPC service used by a fresh daemon to exchange a join token for an mTLS PKCS#12 keystore and the controller's CA certificate.
---

:::caution[Internal cluster protocol — Not a public API]
This page documents the gRPC contract between the controller and a
fresh daemon. It is not a public API and is subject to change between
minor releases. Build against the [REST API](/reference/rest-api/) or the
[Java SDK](/reference/module-sdk/) for stable surfaces.
:::

`BootstrapService` is the daemon's one-shot first-contact RPC. It runs
**without mTLS** because the daemon doesn't have a certificate yet —
that's the whole point of the call.

## What you'll learn

- The single RPC and its request / response shapes.
- The trust model (CA cert returned in-band).
- The replay-protection contract.

## RPCs

### `ExchangeJoinToken`

```protobuf
service BootstrapService {
  rpc ExchangeJoinToken(ExchangeJoinTokenRequest) returns (ExchangeJoinTokenResponse);
}

message ExchangeJoinTokenRequest {
  string join_token = 1;
  string node_id    = 2;
}

message ExchangeJoinTokenResponse {
  bytes  pkcs12              = 1;
  string pkcs12_password     = 2;
  bytes  ca_certificate_pem  = 3;
}
```

The daemon presents the join token (issued through
[`AdminService.CreateJoinToken`](/internals/protocol/admin-service/#createjointoken)
or `prexorctl token create`) along with its chosen `node_id`. On
success, the controller returns:

- `pkcs12` — a PKCS#12 keystore containing the daemon's freshly
  minted private key + signed certificate.
- `pkcs12_password` — the keystore password.
- `ca_certificate_pem` — the controller's internal CA cert; the
  daemon installs this as its trust anchor for future mTLS calls.

## Trust model

The CA cert is returned **in-band** in the response. This is safe
because the join token itself is a high-entropy bearer secret issued
out-of-band over an authenticated channel (operator → daemon host).
A man-in-the-middle would need to know the join token to forge a
matching response, which is precisely the threat model we trust the
out-of-band channel to defend against.

## Replay protection

Join tokens are **single-use** and the controller persists their
hash plus a consumed-at timestamp. A second `ExchangeJoinToken` call
with the same token is rejected with `FAILED_PRECONDITION`.
Optional `node_id` binding (set when the operator issues the token
with `--node`) further restricts which daemon may consume the token.

## Failure modes

| gRPC status | Meaning |
|---|---|
| `NOT_FOUND` | Token does not exist. |
| `FAILED_PRECONDITION` | Token expired, revoked, or already consumed. |
| `PERMISSION_DENIED` | Token bound to a different `node_id` than the caller supplied. |
| `INVALID_ARGUMENT` | Missing `join_token` or `node_id`. |

After any failure the daemon should surface a clear log line and
exit non-zero — there's no productive retry without a fresh token.

## After bootstrap

The daemon writes the keystore + CA cert to `data/certs/`, deletes
the join token from its config, and switches to mTLS for every future
gRPC call (`DaemonService.Connect`, etc.).

## Next up

- [DaemonService](/internals/protocol/daemon-service/) — the long-lived
  stream the daemon opens after bootstrap.
- [AdminService → CreateJoinToken](/internals/protocol/admin-service/#createjointoken)
  — how operators mint the tokens consumed here.
- [Installation guide](/getting-started/installation/) — operator-side
  walkthrough that drives this RPC end-to-end.
