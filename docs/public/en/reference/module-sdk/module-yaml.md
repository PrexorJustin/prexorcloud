---
title: module.yaml Manifest
description: Schema reference for the module.yaml manifest — id, version, hosts, backend entrypoints, frontend bundle, capabilities, storage, and workload extensions.
---

Every module ships a `module.yaml` at `META-INF/prexor/module.yaml`
inside its shaded jar (the build plugin copies it from
`src/main/module/`). The manifest declares the module's identity, the
host JVM(s) it loads into, its entrypoints, and the contracts it
participates in.

## What you'll learn

- The full top-level schema.
- How `hosts` and `backend` interact for dual-host modules.
- The `capabilities`, `storage`, `frontend`, and `extensions`
  sections.

## Top-level schema

```yaml
manifestVersion: 1                          # required, currently 1
id: stats-aggregator                        # required, kebab-case, globally unique
version: 1.0.0-SNAPSHOT                     # required, semver
hosts: [controller]                         # default [controller]; or [daemon] or both
backend:                                    # required for any listed host
  controller:
    entrypoint: com.example.StatsModule
  daemon:
    entrypoint: com.example.StatsDaemon
frontend:                                   # optional
  sdkVersion: 1
  entry: index.js
storage:                                    # optional; default no storage
  mongo: true
  redis: false
  limits:
    mongoDocuments: 500000
    redisKeys: 0
capabilities:                               # optional
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
extensions: []                              # optional; workload-extension manifests
```

The Java mirror lives at
`me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest`.

## `manifestVersion`

Integer schema version. Currently `1`. The controller refuses to load
a manifest with a higher version than it supports.

## `id` / `version`

- `id` is the module's globally-unique kebab-case identifier. It
  doubles as the URL slug at `/api/v1/modules/<id>/...`, the Mongo
  collection prefix (`mod_<id>_*`), and the Redis key prefix
  (`mod:<id>:*`).
- `version` is semver. Two installed versions of the same id are not
  allowed; an upload with the same id replaces the previous version
  and triggers `onUpgrade` on the new code.

## `hosts`

```yaml
hosts: [controller]            # controller-only module
hosts: [daemon]                # daemon-only module
hosts: [controller, daemon]    # dual-host
```

Defaults to `[controller]` when omitted (preserves the shape of
pre-Layer-7 modules). Whichever hosts are listed must have the
matching `backend` field populated.

## `backend`

A per-host entrypoint spec.

```yaml
backend:
  controller:
    entrypoint: com.example.MyModule         # implements PlatformModule
  daemon:
    entrypoint: com.example.MyDaemonModule   # implements DaemonModule
```

For any host listed in `hosts`, the corresponding field must be
non-null and the named class must implement
[`PlatformModule`](/reference/module-sdk/platform-module/) (controller)
or [`DaemonModule`](/reference/module-sdk/daemon-module/) (daemon).

## `frontend`

Optional dashboard bundle:

```yaml
frontend:
  sdkVersion: 1
  entry: index.js
```

The bundle is shipped inside the jar under `META-INF/prexor/frontend/`
and served by the dashboard's plugin loader.

## `storage`

```yaml
storage:
  mongo: true
  redis: true
  limits:
    mongoDocuments: 500000
    redisKeys: 100000
```

Declares the persistent storage handles the module wants. The
controller refuses to call `requireMongoStorage()` /
`requireRedisStorage()` for a module that didn't request that
storage. Limits are quotas — exceeding them raises a
`StorageQuotaExceededException` from the next write.

See the [Storage API](/reference/module-sdk/storage-api/) page.

## `capabilities`

```yaml
capabilities:
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
```

`provides` entries must match the `id` of every handle returned by
`PlatformModule#capabilityHandles()` / `DaemonModule#capabilityHandles()`.
`requires` entries are resolved before the module transitions to
`ACTIVE`. See [Capability API](/reference/module-sdk/capability-api/).

## `extensions`

Workload-extension declarations — artifacts the module ships into
running instances (e.g. server-side jars, config patches). Each entry
is a `WorkloadExtensionManifest`:

```yaml
extensions:
  - id: stats-aggregator-paper
    runtimeTarget: PAPER
    activationPolicy: ALWAYS
    variants:
      - id: paper-1.21
        platformVersion: 1.21
        artifact: classpath:extensions/stats-aggregator-paper-1.21.jar
        installPath: plugins/stats-aggregator.jar
```

The daemon downloads the artifact (via `RuntimeArtifact.download_url`
in the gRPC composition plan) and unpacks it into the instance
directory before launch.

## Validation

- `manifestVersion` must be `1`.
- `id` must be non-blank kebab-case.
- For each host listed in `hosts`, the matching `backend` field must
  be present and the entrypoint class must be loadable + implement
  the right interface.
- A `provides` entry with an id used elsewhere in the cluster is a
  load failure.
- A `requires` entry whose `versionRange` is unparseable is a load
  failure.

The controller surfaces validation failures as
`POST /api/v1/modules/platform/upload` 422 responses with a JSON body
identifying the offending field.

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — entrypoint
  contract.
- [Capability API](/reference/module-sdk/capability-api/) — manifest
  capability section in detail.
- [Storage API](/reference/module-sdk/storage-api/) — manifest
  storage section in detail.
