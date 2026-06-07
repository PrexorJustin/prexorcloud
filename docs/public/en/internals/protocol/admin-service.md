---
title: AdminService
description: Controller administrative gRPC service — currently exposes join-token CRUD for operator tooling. Mirrors the REST /admin/tokens endpoints.
---

:::caution[Internal cluster protocol — Not a public API]
This page documents the gRPC contract used by `prexorctl` and the
dashboard for administrative operations. It is not a public API and
is subject to change between minor releases. Build against the
[REST API](/reference/rest-api/) for stable surfaces; the gRPC version
exists primarily so internal tooling can avoid REST when running
inside the cluster's mTLS perimeter.
:::

`AdminService` exposes the slice of operator-facing RPCs that aren't
tied to the daemon control loop. In v1 that's join-token management;
future operations (user CRUD, role management) will land here as
they're proven in REST first.

## What you'll learn

- The three join-token RPCs.
- How they map to the REST `/api/v1/admin/tokens` endpoints.

## RPCs

### `CreateJoinToken`

Issue a new join token.

```protobuf
service AdminService {
  rpc CreateJoinToken(CreateJoinTokenRequest) returns (CreateJoinTokenResponse);
}

message CreateJoinTokenRequest {
  string node_id     = 1;   // optional: bind token to a specific node id
  int32  ttl_seconds = 2;   // optional: lifetime; controller falls back to default
}

message CreateJoinTokenResponse {
  string token_id            = 1;
  string join_token          = 2;
  int64  expires_at_epoch_ms = 3;
}
```

The raw `join_token` string is returned **exactly once** — the
controller persists only its hash. Pair it with the `node_id` field
when you want to restrict bootstrap to a specific daemon.

REST equivalent: `POST /api/v1/admin/tokens`.

### `RevokeJoinToken`

Revoke a token by id (not by raw value).

```protobuf
rpc RevokeJoinToken(RevokeJoinTokenRequest) returns (RevokeJoinTokenResponse);

message RevokeJoinTokenRequest {
  string token_id = 1;
}

message RevokeJoinTokenResponse {}
```

After revocation the controller rejects any
[`BootstrapService.ExchangeJoinToken`](/internals/protocol/bootstrap-service/#exchangejointoken)
call presenting that token with `FAILED_PRECONDITION`.

REST equivalent: `DELETE /api/v1/admin/tokens/{token_id}`.

### `ListJoinTokens`

List currently tracked tokens.

```protobuf
rpc ListJoinTokens(ListJoinTokensRequest) returns (ListJoinTokensResponse);

message ListJoinTokensRequest {}

message ListJoinTokensResponse {
  repeated JoinTokenInfo tokens = 1;
}

message JoinTokenInfo {
  string token_id            = 1;
  string node_id             = 2;
  int64  expires_at_epoch_ms = 3;
  bool   expired             = 4;
}
```

Note: the listing only carries token **metadata** — the raw token
value is unrecoverable after `CreateJoinToken` returns.

REST equivalent: `GET /api/v1/admin/tokens`.

## Authentication

`AdminService` is mounted on the controller's mTLS gRPC listener. The
caller must present either:

- A valid mTLS client cert from the controller's CA (e.g. another
  controller node), or
- A bearer token in the `authorization` metadata header
  (`Bearer prx_xxx`) belonging to a user with the `tokens.manage`
  permission.

`prexorctl` uses the bearer token path; intra-cluster tooling uses the
mTLS path.

## Status mapping

| gRPC status | Cause |
|---|---|
| `OK` | Success. |
| `NOT_FOUND` | `RevokeJoinToken` against an unknown id. |
| `INVALID_ARGUMENT` | `ttl_seconds < 0`, blank `token_id`, or invalid `node_id`. |
| `PERMISSION_DENIED` | Caller lacks the `tokens.manage` permission. |
| `UNAUTHENTICATED` | No mTLS cert and no bearer token. |

## Next up

- [BootstrapService](/internals/protocol/bootstrap-service/) — the
  consumer of tokens minted here.
- [Setup + Auth → token](/reference/cli/setup-and-auth/#prexorctl-token)
  — `prexorctl` commands that drive these RPCs.
