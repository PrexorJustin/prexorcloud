---
title: prexorctl cluster
description: Reference for prexorctl cluster — controller-cluster status, members, join tokens, seed rotation, and degraded-cluster recovery.
---

`prexorctl cluster` manages the controller-to-controller cluster control plane: the Raft group of controllers that share one replicated state. Each subcommand is a thin wrapper over the controller's `/api/v1/cluster/*` REST surface. Permission gating and audit recording happen controller-side.

This page covers the controller cluster. For per-node daemons (the `node` subtree: `drain`, `undrain`, `list`, `info`) and node join tokens (the `token` subtree), see [Node and token commands](#node-and-token-commands) at the end.

## Global flags

Every command below inherits the root persistent flags:

- `--json`, `-j` — emit the raw API response as JSON instead of the rendered view.
- `--controller <url>`, `-c` — override the controller URL for this invocation.
- `--token <token>`, `-t` — override the auth token for this invocation.
- `--context <name>` — override the active context for this invocation.
- `--no-color` — disable colored output.
- `--ascii` — ASCII glyphs only (no box drawing or sparklines).
- `--verbose`, `-v` — print HTTP request/response details.

All `cluster` subcommands require authentication. They call `requireAuth()` and fail with the standard auth error if no valid token resolves from the context, `--token`, or environment.

## `prexorctl cluster status`

Print the cluster identity, member count, and active config version.

```bash
prexorctl cluster status
```

```
Cluster status
  Cluster ID             cl-7f3a2b
  Members                3
  Active config version  42
  Created at             2026-05-31T09:14:22Z
```

Backed by `GET /api/v1/cluster`. Fields rendered: `clusterId`, `memberCount`, `activeConfigVersion`, `createdAt`.

With `--json`, the byte-identical response object is printed:

```bash
prexorctl cluster status --json
```

Flags: none beyond the global set.

## `prexorctl cluster members`

List the controllers in the Raft group.

```bash
prexorctl cluster members
```

```
Listing cluster members on controller-1

  NODE ID      RAFT ADDR        REST ADDR         GRPC ADDR         LABEL         JOINED AT
  cl-7f3a2b    10.0.0.1:9870    10.0.0.1:8080     10.0.0.1:9090     controller-1  2026-05-31T09:14:22Z
  cl-9c1d4e    10.0.0.2:9870    10.0.0.2:8080     10.0.0.2:9090     controller-2  2026-05-31T10:02:51Z
  cl-3a8f60    10.0.0.3:9870    10.0.0.3:8080     10.0.0.3:9090     controller-3  2026-05-31T10:05:13Z

3 members
```

Backed by `GET /api/v1/cluster/members`, which returns `{ "members": [...] }`. Per-member fields rendered: `nodeId`, `raftAddr`, `restAddr`, `gRPCAddr`, `label`, `joinedAt`.

With `--json`, the `members` array is printed directly (the response is unwrapped to the array).

Flags: none beyond the global set.

## `prexorctl cluster eject <nodeId>`

Force-remove a controller from the Raft group. Irreversible. Use this when a peer is dead and will not return; the surviving controllers drop it from the member list so quorum is recomputed over the remaining peers.

```bash
prexorctl cluster eject cl-9c1d4e
```

```
  Force-eject controller "cl-9c1d4e"? It will be removed from the Raft group. [y/N] y
✓ Ejected controller cl-9c1d4e
```

Argument: exactly one `<nodeId>` (`cobra.ExactArgs(1)`).

Backed by `DELETE /api/v1/cluster/members/<nodeId>`. When `--reason` is set, it is appended as the `reason` query parameter and recorded in the controller audit log.

Flags:

- `--yes` — skip the interactive confirmation. Default `false`.
- `--reason <text>` — audit reason recorded with the ejection. Default empty.

## `prexorctl cluster leave`

Have the targeted controller gracefully leave the cluster, then shut down. Unlike `eject` (which removes a *different*, dead peer), `leave` is issued against the controller you are talking to: it commits its own departure through Raft and stops once the leave entry replicates.

```bash
prexorctl cluster leave
```

```
  Have this controller leave the cluster? It will shut down after the leave commits. [y/N] y
✓ Controller cl-9c1d4e leaving cluster cl-7f3a2b
```

Backed by `POST /api/v1/cluster/leave` with an empty body. Response fields rendered: `nodeId`, `clusterId`. `--json` prints the raw response.

Flags:

- `--yes` — skip the interactive confirmation. Default `false`.

## `prexorctl cluster join-token`

Manage cluster join tokens — the one-time secrets a new controller presents to join an existing Raft group. This is distinct from the `token` subtree, which issues *node* (daemon) join tokens.

The bare command has no behavior; use one of the subcommands below.

### `prexorctl cluster join-token create`

Issue a new cluster join token. The wire token is printed once and never again.

```bash
prexorctl cluster join-token create \
  --join-addr 10.0.0.1:9090 \
  --join-addr 10.0.0.2:9090 \
  --label controller-4 \
  --ttl-seconds 3600
```

```
Cluster join token issued
  JTI         jt-4b21fa
  Token       eyJhbGciOi...<redacted>
  Expires at  2026-06-07T15:30:00Z

⚠ This is the only time the token is shown. Save it now.
```

Backed by `POST /api/v1/cluster/join-tokens`. The request body carries `ttlSeconds`, `joinAddrs`, and (when set) `label`. Response fields rendered: `jti`, `token`, `expiresAt`. `--json` prints the raw response.

At least one `--join-addr` is required. With none supplied, the command fails before any request with:

```
at least one --join-addr is required (gRPC host:port of an existing controller)
```

Flags:

- `--join-addr <host:port>` — gRPC address of an existing controller. Repeat for multiple. Required (at least one).
- `--ttl-seconds <int>` — token TTL in seconds. Default `86400` (24 hours).
- `--label <text>` — human-readable label, e.g. `controller-2`. Default empty.

### `prexorctl cluster join-token list`

List outstanding cluster join tokens.

```bash
prexorctl cluster join-token list
```

```
Listing cluster join tokens on controller-1

  JTI         LABEL         STATUS    CREATED AT             EXPIRES AT
  jt-4b21fa   controller-4  ● ACTIVE  2026-06-07T14:30:00Z   2026-06-07T15:30:00Z

1 tokens
```

Backed by `GET /api/v1/cluster/join-tokens`, which returns `{ "tokens": [...] }`. Per-token fields rendered: `jti`, `label`, `status`, `createdAt`, `expiresAt`. The raw token value is not returned here — only at creation. `--json` prints the `tokens` array.

Flags: none beyond the global set.

### `prexorctl cluster join-token revoke <jti>`

Revoke an outstanding cluster join token by its `jti`.

```bash
prexorctl cluster join-token revoke jt-4b21fa
```

```
✓ Revoked join token jt-4b21fa
```

Argument: exactly one `<jti>` (`cobra.ExactArgs(1)`). Backed by `DELETE /api/v1/cluster/join-tokens/<jti>`.

Flags: none beyond the global set.

## `prexorctl cluster seed`

Manage the cluster seed secret — the HMAC key the controller uses to mint and verify join tokens.

The bare command has no behavior; use the subcommand below.

### `prexorctl cluster seed rotate`

Rotate the seed secret. Every outstanding cluster join token becomes invalid immediately, because they were signed with the previous seed.

```bash
prexorctl cluster seed rotate
```

```
  Rotate the cluster seed? Every outstanding join token will become invalid. [y/N] y
✓ Seed rotated for cluster cl-7f3a2b by admin at 2026-06-07T15:45:10Z
```

Backed by `POST /api/v1/cluster/seed/rotate` with an empty body. Response fields rendered: `clusterId`, `rotatedBy`, `rotatedAt`. `--json` prints the raw response.

Flags:

- `--yes` — skip the interactive confirmation. Default `false`.

## `prexorctl cluster recover`

Recover a degraded cluster. The command branches on whether quorum is still held.

```bash
prexorctl cluster recover
```

There are two scenarios.

**Quorum-preserved** (no more than `floor((N-1)/2)` controllers failed). The surviving majority can still commit, so recovery is force-ejecting the dead peers from the member list. This path lists members via `GET /api/v1/cluster/members`, then issues `DELETE /api/v1/cluster/members/<nodeId>?reason=cluster+recover` for each peer to drop — the same operation as `cluster eject`, applied in a batch.

With no `--eject` flag, the command prints the member list and prompts for the dead node IDs interactively:

```
Cluster members
  cl-7f3a2b   raft=10.0.0.1:9870   last-seen=2026-06-07T15:50:01Z
  cl-9c1d4e   raft=10.0.0.2:9870   last-seen=2026-06-07T15:12:44Z

  Enter dead nodeIds to eject (comma-separated, blank to cancel): cl-9c1d4e
  Force-eject 1 peer(s)? [cl-9c1d4e] [y/N] y
✓ Ejected cl-9c1d4e

⚠ Consider rotating the cluster seed: prexorctl cluster seed rotate
```

A blank line at the prompt aborts with no peers ejected. If any ejection fails, the command reports each failure and exits with `eject failed for: [...]`. After a successful run it suggests rotating the seed.

If listing members itself fails, the error suggests the catastrophic path:

```
could not list cluster members (is quorum lost? rerun with --i-have-only-survivor): ...
```

**Catastrophic** (`--i-have-only-survivor`, quorum lost). The command automates nothing. It prints the offline single-survivor reset procedure — destructive filesystem surgery on a *stopped* controller — and points at the canonical runbook `docs/runbooks/recover-cluster.md`:

```bash
prexorctl cluster recover --i-have-only-survivor
```

```
Catastrophic recovery — single-survivor reset
  This is destructive filesystem surgery. Read the playbook before continuing:
    docs/runbooks/recover-cluster.md

  Summary:
    1. Stop the controller on the survivor.
    2. Back up data/raft/ and config/security/cluster/.
    3. Under data/raft/<groupId>/, preserve sm/ and rename current,
       log_inprogress, raft-meta* to .broken-<ts> sidecars.
    4. Start the controller — it boots as a single-member group,
       the state machine replays from the preserved snapshot.
    5. Verify: prexorctl cluster status and prexorctl cluster members (count == 1).
    6. prexorctl cluster seed rotate — invalidate any in-flight join tokens.
    7. Issue fresh join tokens to grow back to HA.

⚠ Anything in flight that hadn't replicated to the survivor is lost.
```

With `--json`, the catastrophic path emits the procedure as structured data (`scenario`, `playbook`, `steps`).

Flags:

- `--eject <ids>` — comma-separated dead node IDs to eject. Skips the interactive prompt. Default empty.
- `--i-have-only-survivor` — print the catastrophic single-survivor reset playbook (quorum is lost). Default `false`.
- `--yes` — skip the interactive confirmation on the quorum-preserved path. Default `false`.

Non-interactive quorum-preserved recovery:

```bash
prexorctl cluster recover --eject cl-9c1d4e,cl-3a8f60 --yes
```

## Node and token commands

These subtrees are separate from `cluster` but manage the same topology — the per-node daemons and the tokens daemons present to attach. A "node" is one daemon process registered with the controller.

### `prexorctl node drain <id>`

Mark a node as `DRAINING`. The controller stops scheduling new instances onto it; running instances keep running until they stop. Argument: exactly one `<id>` (`cobra.ExactArgs(1)`).

```bash
prexorctl node drain node-fra-1
```

```
✓ Node node-fra-1 set to DRAINING
```

Backed by `POST /api/v1/nodes/<id>/drain`. No flags beyond the global set.

### `prexorctl node undrain <id>`

Return a draining node to `ONLINE`; the scheduler resumes placing instances on it. Argument: exactly one `<id>` (`cobra.ExactArgs(1)`).

```bash
prexorctl node undrain node-fra-1
```

```
✓ Node node-fra-1 set to ONLINE
```

Backed by `POST /api/v1/nodes/<id>/undrain`. No flags beyond the global set.

`node` also provides `list` (with `--state` filtering on `ONLINE`, `DRAINING`, `UNREACHABLE`, `OFFLINE`, backed by `GET /api/v1/nodes`) and `info <id>` (backed by `GET /api/v1/nodes/<id>`).

### `prexorctl token`

The `token` subtree manages *node* join tokens — the secrets a new daemon presents to register with the controller. It is distinct from `cluster join-token`, which admits a new *controller* to the Raft group. It exposes:

- `token create` — `--node <id>` (optional), `--ttl <dur>` (default `1h`). Backed by `POST /api/v1/admin/tokens`.
- `token list` — backed by `GET /api/v1/admin/tokens`.
- `token revoke <id>` — `cobra.ExactArgs(1)`, backed by `DELETE /api/v1/admin/tokens/<id>`.

## Command summary

| Command | API | Destructive |
|---|---|---|
| `cluster status` | `GET /api/v1/cluster` | no |
| `cluster members` | `GET /api/v1/cluster/members` | no |
| `cluster eject <nodeId>` | `DELETE /api/v1/cluster/members/<nodeId>` | yes |
| `cluster leave` | `POST /api/v1/cluster/leave` | yes |
| `cluster join-token create` | `POST /api/v1/cluster/join-tokens` | no |
| `cluster join-token list` | `GET /api/v1/cluster/join-tokens` | no |
| `cluster join-token revoke <jti>` | `DELETE /api/v1/cluster/join-tokens/<jti>` | yes |
| `cluster seed rotate` | `POST /api/v1/cluster/seed/rotate` | yes |
| `cluster recover` | `GET /api/v1/cluster/members` + `DELETE …/members/<id>` | yes |
| `node drain <id>` | `POST /api/v1/nodes/<id>/drain` | no |
| `node undrain <id>` | `POST /api/v1/nodes/<id>/undrain` | no |
| `token create` | `POST /api/v1/admin/tokens` | no |
| `token list` | `GET /api/v1/admin/tokens` | no |
| `token revoke <id>` | `DELETE /api/v1/admin/tokens/<id>` | yes |
