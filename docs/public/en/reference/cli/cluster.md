---
title: Cluster Commands
description: prexorctl node, status, and version — daemon listing, drain control, and cluster overview commands for operators.
---

These commands give you situational awareness across the cluster: the
list of attached nodes, an at-a-glance overview, and the controller +
CLI version pair.

## What you'll learn

- How to list and drain nodes.
- The difference between the dashboard-style `status` overview and a raw
  REST query.
- How to obtain controller and CLI versions for support tickets and CI.

## `prexorctl status`

Cluster-wide overview. Renders three summary columns (cluster, nodes,
instances), a live-metrics card with sparklines, and a per-group
status table. With `--json`, returns the byte-identical
`/api/v1/overview` response so scripts continue to work.

```bash
prexorctl status
prexorctl status --json
```

Flags: none — uses the global `--json` / `--controller` / `--token`.

## `prexorctl node`

Cluster-node management. A "node" is a daemon process registered with
the controller; one daemon = one node.

### `node list`

```bash
prexorctl node list
prexorctl node list --state ONLINE
prexorctl node list --state DRAINING --json
```

Flags:

- `--state <state>` — filter by state. Valid values: `ONLINE`,
  `DRAINING`, `UNREACHABLE`, `OFFLINE`.

The default rendering shows id, status pill, CPU %, memory used/total,
running-instance count, and connected-since timestamp. Footer counts
group nodes by state.

### `node info`

```bash
prexorctl node info <id>
```

Detailed view: status, connected-since, resource card (CPU, memory,
free disk, instance count), and the per-instance table for instances
currently running on this node.

### `node drain`

```bash
prexorctl node drain <id>
```

Marks the node as `DRAINING`. The controller stops scheduling new
instances onto it; existing instances keep running until they drain
naturally or you stop them explicitly.

### `node undrain`

```bash
prexorctl node undrain <id>
```

Returns a draining node to `ONLINE`. The scheduler resumes placing new
instances.

## `prexorctl version`

Prints the CLI version, Go runtime, OS/arch, and — when authenticated —
the controller's reported version. Useful when filing bugs.

```bash
prexorctl version
prexorctl version --json
```

The JSON form merges CLI and `controller_*` keys into a single object
suitable for diagnostics bundles.

## Scripting examples

Drain a node, wait for instances to clear, then power it down:

```bash
prexorctl node drain node-3
while [ "$(prexorctl node info node-3 --json | jq '.instanceCount')" -gt 0 ]; do
  sleep 5
done
prexorctl node info node-3
```

Bail out of CI if the cluster isn't healthy:

```bash
HEALTHY=$(prexorctl status --json | jq -r '.degraded')
[ "$HEALTHY" = "false" ] || exit 1
```

## Next up

- [Group + Instance](/reference/cli/group-and-instance/) — managing what
  runs on those nodes.
- [Node lifecycle concept](/concepts/cluster-model/) — what each
  state means and how transitions are driven.
