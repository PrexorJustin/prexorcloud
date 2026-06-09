---
title: AdminService
description: Controller administrative gRPC service — three unary RPCs for join-token CRUD inside the cluster's mTLS perimeter. Mirrors the REST /admin/tokens endpoints. Defined in cloud-protocol/proto.
---

:::caution[Internal cluster protocol — not a public API]
This page documents the gRPC contract for administrative operations inside
the cluster's mTLS perimeter. **It is not a public API.** Message shapes,
RPC names, and field numbers change between minor releases without notice.
Build against [REST](/reference/rest-api/) or the
[Java module SDK](/reference/module-sdk/) instead — those carry stability
guarantees. This page is for contributors changing the protocol and
operators debugging the cluster.
:::

`AdminService` exposes the operator-facing RPCs that don't ride the daemon
control stream. In v1 that is join-token management: create, revoke, list.
The same operations are available over REST under `/api/v1/admin/tokens`;
the gRPC service exists so tooling already inside the mTLS perimeter (the
controller's own REST layer, another controller) can avoid the HTTP hop.

- **Served by** the controller (`AdminServiceImpl`, on the gRPC listener —
  default port `9090`), backed by the same `JoinTokenStore` as the REST
  routes.
- **Called by** intra-cluster tooling holding a controller-issued client
  certificate. Operator-facing `prexorctl token` commands use the REST path
  with a JWT bearer, not this service.

## What you'll learn

- The three unary join-token RPCs.
- The mTLS-only authorization model.
- The status codes and the REST equivalents.

## The RPCs

```proto
service AdminService {
  rpc CreateJoinToken(CreateJoinTokenRequest) returns (CreateJoinTokenResponse);
  rpc RevokeJoinToken(RevokeJoinTokenRequest) returns (RevokeJoinTokenResponse);
  rpc ListJoinTokens(ListJoinTokensRequest) returns (ListJoinTokensResponse);
}
```

Three unary RPCs.

### `CreateJoinToken`

Mint a new join token.

```proto
message CreateJoinTokenRequest {
  string node_id     = 1;   // optional: recorded with the token as metadata
  int32  ttl_seconds = 2;   // <= 0 falls back to the controller default (3600s)
}

message CreateJoinTokenResponse {
  string token_id            = 1;
  string join_token          = 2;   // raw token — returned exactly once
  int64  expires_at_epoch_ms = 3;
}
```

The raw `join_token` is returned exactly once — the controller persists only
its hash. Capture it at the call site. `node_id` is stored as metadata on
the token, not enforced as a binding when the token is later exchanged.

REST equivalent: `POST /api/v1/admin/tokens`.

### `RevokeJoinToken`

Drop a token by id.

```proto
message RevokeJoinTokenRequest  { string token_id = 1; }
message RevokeJoinTokenResponse {}
```

Revocation is idempotent: the controller removes the matching token if it
exists and returns `OK` either way. An unknown `token_id` is not an error.
Once a token is gone, an [`ExchangeJoinToken`](/internals/protocol/bootstrap-service/#exchangejointoken)
that presents it fails with `UNAUTHENTICATED`.

REST equivalent: `DELETE /api/v1/admin/tokens/{token_id}`.

### `ListJoinTokens`

List tracked tokens, including expired ones.

```proto
message ListJoinTokensRequest  {}
message ListJoinTokensResponse { repeated JoinTokenInfo tokens = 1; }

message JoinTokenInfo {
  string token_id            = 1;
  string node_id             = 2;
  int64  expires_at_epoch_ms = 3;
  bool   expired             = 4;
}
```

The listing carries token metadata only — the raw token value is
unrecoverable after `CreateJoinToken` returns.

REST equivalent: `GET /api/v1/admin/tokens`.

## Authorization

`AdminService` is authorized purely by the caller's mTLS client
certificate. The gRPC listener's mTLS enforcement interceptor rejects any
call without a valid client cert, and `AdminServiceImpl` then checks the
certificate's Common Name (CN): the call is allowed only when the CN
contains `controller` (the controller's own certificate, used by the REST
layer) or is in the configured admin node-id allowlist.

There is no bearer-token path on this service. Operator tooling that
authenticates with a JWT (`Bearer prx_…`) and the `tokens.manage`
permission uses the REST endpoints instead; those routes share the same
`JoinTokenStore`.

## Status codes

| gRPC status | Cause |
|---|---|
| `OK` | Success. `RevokeJoinToken` returns `OK` even for an unknown id. |
| `UNAUTHENTICATED` | No TLS session, no client certificate, or a revoked certificate. |
| `PERMISSION_DENIED` | Client cert present but its CN is not authorized for admin operations. |

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — the consumer
  of the tokens minted here.
- [Setup + auth → `prexorctl token`](/reference/cli/setup-and-auth/#prexorctl-token)
  — the CLI commands that drive the REST equivalents.
