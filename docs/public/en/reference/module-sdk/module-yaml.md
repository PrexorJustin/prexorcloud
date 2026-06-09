---
title: module.yaml manifest
description: Schema reference for the module.yaml platform-module manifest — manifestVersion, id, version, hosts, backend, frontend, storage, capabilities, and workload extensions/variants, with per-field validation rules.
---

Every platform module ships a `module.yaml` at
`META-INF/prexor/module.yaml` inside its shaded jar. The Gradle
`prexorcloud.module` plugin reads the template at
`src/main/module/module.yaml`, substitutes extension artifact checksums,
and copies the result into the jar at build time. The controller parses
it on upload with `PlatformModuleManifestParser` and rejects any manifest
that violates the rules below.

The manifest declares the module's identity, the host JVM(s) it loads
into, its entrypoints, the storage it requests, the capabilities it
provides/requires, and the workload extensions it ships into running
instances.

The Java mirror is the record
`me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest`.
The parser is
`me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser`.

## What you'll learn

- The full top-level schema and every nested field.
- Per-field types, defaults, and validation rules enforced by the parser.
- How `hosts` and `backend` interact for controller-only, daemon-only,
  and dual-host modules.
- The `manifestVersion: 2` fields (`reloadable`, `deprecatedSince`,
  `removedIn`) and how the parser gates them against the schema version.

## Parser constants

| Constant | Value |
| --- | --- |
| Manifest path in jar | `META-INF/prexor/module.yaml` |
| `CURRENT_MANIFEST_VERSION` | `2` |
| `MIN_MANIFEST_VERSION` | `1` |
| `MIN_FRONTEND_SDK_VERSION` | `1` |
| `MAX_FRONTEND_SDK_VERSION` | `1` |
| `id` / identifier pattern | `^[a-z][a-z0-9-]*$` |
| `version` pattern | `^\d+\.\d+\.\d+(-[a-zA-Z0-9.-]+)?$` |
| semver pattern (`provides.version`, `deprecatedSince`, `removedIn`) | `^\d+\.\d+\.\d+(?:[-+].*)?$` |
| `sha256` pattern | `^[a-fA-F0-9]{64}$` |

Unknown fields are rejected at every object level — the parser keeps an
allow-list per section and throws `PlatformModuleManifestException` for
any key it doesn't recognize.

## Top-level schema

```yaml
manifestVersion: 2                          # optional int; default CURRENT_MANIFEST_VERSION (2)
id: stats-aggregator                        # required; [a-z][a-z0-9-]*
version: 1.0.0-SNAPSHOT                     # required; semver-shaped
hosts: [controller]                         # optional; default [controller]
backend:                                    # required
  controller:
    entrypoint: com.example.StatsModule
    reloadable: false                       # optional (v2+); default false
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
      deprecatedSince: null                 # optional (v2+)
      removedIn: null                       # optional (v2+)
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
extensions: []                              # optional; workload-extension manifests
```

Allowed root keys: `manifestVersion`, `id`, `version`, `hosts`,
`backend`, `frontend`, `capabilities`, `storage`, `extensions`. Anything
else is a load failure (`root contains unknown field '<name>'`).

## `manifestVersion`

```yaml
manifestVersion: 2
```

Integer schema version. Optional; defaults to `CURRENT_MANIFEST_VERSION`
(`2`) when omitted. Must satisfy
`MIN_MANIFEST_VERSION <= manifestVersion <= CURRENT_MANIFEST_VERSION`
(`1..2`); anything outside that range fails with
`unsupported manifestVersion N (supported: 1..2)`.

The version gates which fields are accepted. Declaring a v2-only field on
a `manifestVersion: 1` manifest is a hard error, not a silent ignore:

- `backend.<host>.reloadable` — rejected on v1 (unknown field).
- `capabilities.provides[].deprecatedSince` / `removedIn` — rejected on v1
  (unknown field).

## `id`

```yaml
id: stats-aggregator
```

Required. Must match `^[a-z][a-z0-9-]*$` (lowercase kebab-case, leading
letter). Globally unique across the cluster. The `id` doubles as the URL
slug, the storage namespace key, and the module's identity for
install/upgrade. A blank or pattern-violating value fails with
`'id' must match [a-z][a-z0-9-]*: <value>`.

## `version`

```yaml
version: 1.0.0-SNAPSHOT
```

Required. Must match `^\d+\.\d+\.\d+(-[a-zA-Z0-9.-]+)?$` (semver
`x.y.z` with an optional `-prerelease` suffix). A non-matching value
fails with `'version' is not semver-shaped: <value>`. This pattern does
**not** accept a `+build` suffix (unlike the looser semver pattern used
for capability versions).

## `hosts`

```yaml
hosts: [controller]            # controller-only (the default)
hosts: [daemon]                # daemon-only
hosts: [controller, daemon]    # dual-host
```

Optional array of host names; defaults to `[controller]` when omitted or
`null` (preserves the shape of pre-Layer-7 modules). Each entry is parsed
case-insensitively into the `ModuleHost` enum: `CONTROLLER` or `DAEMON`.

Validation:

- When present, must be a non-empty array
  (`'hosts' must be a non-empty array`).
- Each entry must be a non-blank string mapping to a known host
  (`'hosts[i]' is not a known host: <value>`).
- Duplicates are rejected
  (`'hosts' declares '<host>' more than once`).

Whichever hosts are listed must have the matching `backend` field
populated (see below). At runtime a module reads its current host via
`ModuleContext#host()`.

## `backend`

Required. Carries a per-host entrypoint spec. Two forms are accepted.

### Object form (preferred)

```yaml
backend:
  controller:
    entrypoint: com.example.MyModule         # implements PlatformModule
    reloadable: true                         # optional (v2+); default false
  daemon:
    entrypoint: com.example.MyDaemonModule   # implements DaemonModule
```

Allowed `backend` keys: `entrypoint` (legacy form, below), `controller`,
`daemon`. Each of `controller` / `daemon` is an `EntrypointSpec` object
with these fields:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `entrypoint` | string | yes | Fully-qualified class name. |
| `reloadable` | boolean | no | v2+ only; default `false`. Opts a controller-hosted module into the `RELOADING` fast path (ADR 28, `PlatformModule#onReload`). |

Cross-field rules enforced against `hosts`:

- `hosts` includes `controller` ⇒ `backend.controller.entrypoint` is
  required, else
  `'backend.controller.entrypoint' is required when 'hosts' includes 'controller'`.
- `hosts` includes `daemon` ⇒ `backend.daemon.entrypoint` is required,
  else
  `'backend.daemon.entrypoint' is required when 'hosts' includes 'daemon'`.
- At least one of `controller` / `daemon` must be present
  (`'backend' must declare at least one of 'controller' or 'daemon'`).

The named class must be loadable and implement
[`PlatformModule`](/reference/module-sdk/platform-module/) (controller
host) or [`DaemonModule`](/reference/module-sdk/daemon-module/) (daemon
host); the interface check happens later in the install pipeline.

### Legacy single-string form

```yaml
backend:
  entrypoint: com.example.MyModule    # treated as the controller entrypoint
```

`backend.entrypoint` is shorthand for `backend.controller.entrypoint`
with `reloadable: false`. It cannot be combined with `controller` /
`daemon` keys (`'backend' cannot mix legacy 'entrypoint' with
'controller'/'daemon' fields`), and requires `hosts` to include
`controller` (`'backend.entrypoint' is set but 'hosts' does not include
'controller'`).

## `frontend`

Optional dashboard bundle.

```yaml
frontend:
  sdkVersion: 1
  entry: index.js
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `sdkVersion` | int | yes | Must be in `MIN..MAX_FRONTEND_SDK_VERSION` (`1..1`). |
| `entry` | string | yes | Non-blank entry filename. |

The whole `frontend` block is optional (`null` ⇒ no frontend). A
`sdkVersion` outside the supported range fails with
`'frontend.sdkVersion' N is not supported by this controller
(supported: 1..1)`.

The built bundle ships in the jar under `META-INF/frontend/` (with a
`module-frontend.json` at that prefix), separate from the
`META-INF/prexor/module.yaml` manifest. The Gradle plugin builds it from
a `frontend/` directory (via `pnpm build`) and copies `frontend/dist`
into `META-INF/frontend/` automatically when a `frontend/package.json`
exists.

## `storage`

Optional persistent-storage request. Maps to `ModuleStorageRequest`.

```yaml
storage:
  mongo: true
  redis: true
  limits:
    mongoDocuments: 500000
    redisKeys: 100000
```

| Field | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `mongo` | boolean | no | `false` | Request a scoped Mongo namespace. |
| `redis` | boolean | no | `false` | Request a scoped Redis prefix. |
| `limits.mongoDocuments` | long | no | `0` (unlimited) | If set, must be `> 0` and requires `mongo: true`. |
| `limits.redisKeys` | long | no | `0` (unlimited) | If set, must be `> 0` and requires `redis: true`. |

Omitting `storage` (or setting it to `null`) yields
`ModuleStorageRequest.NONE`. Allowed `storage` keys: `mongo`, `redis`,
`limits`; allowed `storage.limits` keys: `mongoDocuments`, `redisKeys`.
Validation:

- A non-zero `limits.mongoDocuments` without `mongo: true` fails with
  `'storage.limits.mongoDocuments' requires 'storage.mongo: true'`.
- A non-zero `limits.redisKeys` without `redis: true` fails with
  `'storage.limits.redisKeys' requires 'storage.redis: true'`.
- A limit value `<= 0` fails with
  `'storage.limits.<field>' must be > 0`.

The controller refuses storage handle requests
(`requireMongoStorage()` / `requireRedisStorage()`) for a module that
didn't declare the corresponding storage. Limits are quotas; exceeding
them raises `StorageQuotaExceededException`. See the
[Storage API](/reference/module-sdk/storage-api/).

## `capabilities`

Optional capability linkage. Maps to `CapabilityDeclaration`.

```yaml
capabilities:
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
      deprecatedSince: 1.3.0      # optional (v2+)
      removedIn: 2.0.0            # optional (v2+); requires deprecatedSince
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
```

Allowed `capabilities` keys: `provides`, `requires`. Both are optional
arrays; omitting the block yields `CapabilityDeclaration.EMPTY`.

### `provides[]`

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | Must match `^[a-z][a-z0-9-]*$`. |
| `version` | string | yes | Must match the semver pattern `x.y.z` with optional `+build` / `-prerelease`. |
| `deprecatedSince` | string | no (v2+) | Semver-shaped provider version where deprecation began. Non-null marks the capability deprecated. |
| `removedIn` | string | no (v2+) | Semver-shaped provider version where it will be removed. Requires `deprecatedSince`. |

Rules:

- Duplicate `provides` ids within a manifest are rejected
  (`capabilities.provides declares '<id>' more than once`).
- `removedIn` without `deprecatedSince` fails with
  `capabilities.provides[i].removedIn requires .deprecatedSince to also
  be set`.
- On a `manifestVersion: 1` manifest, `deprecatedSince` / `removedIn` are
  unknown fields and rejected.

Every `provides.id` must match the `id` of a handle returned by the
module's `capabilityHandles()`.

### `requires[]`

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | Must match `^[a-z][a-z0-9-]*$`. |
| `versionRange` | string | yes | Must parse via `SemverRange.parse`. |

An unparseable `versionRange` fails with
`capabilities.requires[i].versionRange is invalid: <value>`. `requires`
entries are resolved before the module transitions to `ACTIVE`. See the
[Capability API](/reference/module-sdk/capability-api/).

## `extensions`

Optional array of workload extensions — artifacts the module ships into
running instances (server/proxy jars). Each entry maps to
`WorkloadExtensionManifest`.

```yaml
extensions:
  - id: protocol-tap-paper
    target: server/paper
    activation: explicit-group-attach
    conflicts: []
    variants:
      - id: protocol-tap-paper-1-21
        mcVersionRange: "[1.21,1.22)"
        runtimeApiVersion: 1
        artifact: extensions/server/paper/1.21/protocol-tap-paper-v1_21.jar
        sha256: AUTO
        installPath: plugins/
```

Allowed extension keys: `id`, `target`, `activation`, `conflicts`,
`variants`.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | Must match `^[a-z][a-z0-9-]*$`; unique across `extensions`. |
| `target` | string | yes | `<type>/<runtime>`, e.g. `server/paper`, `proxy/velocity`. Parsed by `RuntimeTarget.parse`. |
| `activation` | string | yes | One of the `ActivationPolicy` wire values (below). |
| `conflicts` | string[] | no | Default `[]`. Non-blank string entries. |
| `variants` | array | yes | Non-empty list of variants (below). |

A duplicate extension `id` fails with `duplicate extension id: <id>`.

### `target`

`RuntimeTarget` is `<workloadType>/<runtimeFamily>`, each part matching
`^[a-z][a-z0-9-]*$`. A value without exactly one `/` fails with
`runtime target must be '<type>/<runtime>'`. Examples seen in first-party
modules: `server/paper`, `server/folia`, `server/bedrock-geyser`,
`proxy/velocity`.

The Gradle plugin applies a stricter build-time check, requiring
`^(server|proxy)/[a-z0-9-]+$`.

### `activation`

`ActivationPolicy` wire values:

| YAML value | Enum |
| --- | --- |
| `explicit-group-attach` | `EXPLICIT_GROUP_ATTACH` |
| `default-enabled` | `DEFAULT_ENABLED` |
| `always` | `ALWAYS` |

An unknown value fails with `unknown activation policy: <value>`.

### `variants[]`

Each variant maps to `WorkloadExtensionVariant`. Allowed keys: `id`,
`mcVersionRange`, `runtimeApiVersion`, `artifact`, `sha256`,
`installPath`. The `variants` array must be non-empty.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | Must match `^[a-z][a-z0-9-]*$`; unique within the extension. |
| `mcVersionRange` | string | yes | Minecraft version range; parsed via `SemverRange.parse`. `*` matches any. |
| `runtimeApiVersion` | int | yes | Must be `>= 1`. |
| `artifact` | string | yes | Relative path inside the jar; no leading `/` or `\`, no `..`. Must end in `.jar` (build-time check). |
| `sha256` | string | yes | 64-char hex digest. Use `AUTO` in the source template — the build plugin replaces it. |
| `installPath` | string | yes | Relative install path in the instance dir; no leading `/` or `\`, no `..`, e.g. `plugins/`. |

Validation details:

- Duplicate variant `id` within an extension fails with
  `<ext>.variants declares duplicate variant id: <id>`.
- `runtimeApiVersion < 1` fails with `runtimeApiVersion must be >= 1`.
- An absolute or `..`-containing `artifact` / `installPath` fails with
  `must be a relative path without '..': <value>`.
- A `sha256` that isn't 64 hex chars fails with
  `sha256 must be a 64-char hex string`. The literal `AUTO` in the source
  template is **not** valid to the parser — the Gradle plugin overwrites
  it with the real digest of the resolved artifact before the manifest is
  written into the jar.

### `sha256: AUTO` and the build plugin

The `prexorcloud.module` Gradle plugin's `preparePlatformManifest` task
resolves each `artifact` path against the project's declared
`prexorcloudModule.extensionArtifacts` map, computes the SHA-256 of the
built jar, and writes it into the variant's `sha256` field. It also fails
the build if:

- An extension `target` doesn't match `^(server|proxy)/[a-z0-9-]+$`.
- A variant `artifact` is blank or doesn't end in `.jar`.
- A variant `installPath` is blank.
- An `artifact` path isn't declared in `extensionArtifacts`.
- A declared `extensionArtifacts` entry isn't referenced by any variant.

So in `src/main/module/module.yaml` you write `sha256: AUTO`; the jar
that ships contains the real digest.

## Worked example — controller module with extensions

From `cloud-modules/protocol-tap/src/main/module/module.yaml`:

```yaml
manifestVersion: 1
id: protocol-tap
version: 1.0.0-SNAPSHOT
hosts: [controller]
backend:
  controller:
    entrypoint: me.prexorjustin.prexorcloud.modules.protocoltap.platform.ProtocolTapModule
storage:
  mongo: true
  limits:
    mongoDocuments: 50000
extensions:
  - id: protocol-tap-paper
    target: server/paper
    activation: explicit-group-attach
    variants:
      - id: protocol-tap-paper-1-20
        mcVersionRange: "[1.20,1.21)"
        runtimeApiVersion: 1
        artifact: extensions/server/paper/1.20/protocol-tap-paper-v1_20.jar
        sha256: AUTO
        installPath: plugins/
      - id: protocol-tap-paper-1-21
        mcVersionRange: "[1.21,1.22)"
        runtimeApiVersion: 1
        artifact: extensions/server/paper/1.21/protocol-tap-paper-v1_21.jar
        sha256: AUTO
        installPath: plugins/
  - id: protocol-tap-folia
    target: server/folia
    activation: explicit-group-attach
    variants:
      - id: protocol-tap-folia
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/folia/protocol-tap-folia.jar
        sha256: AUTO
        installPath: plugins/
```

## Worked example — daemon-only module

From `test-fixtures/test-daemon-module/src/main/module/module.yaml`:

```yaml
manifestVersion: 1
id: test-daemon-module
version: 1.0.0
hosts: [daemon]
backend:
  daemon:
    entrypoint: me.prexorjustin.prexorcloud.modules.testdaemon.TestDaemonModule
```

## Validation and the upload route

Manifest parse failures surface on install/upgrade:

- `POST /api/v1/modules/platform/upload` returns `422` on validation or
  signature failure (error code `PLATFORM_INSTALL_FAILED`), `400` for a
  missing/invalid upload, `409` on install conflict, `201` on success.
- `POST /api/v1/modules/platform/{moduleId}/upgrade` returns `422`
  (validation/signature, `PLATFORM_UPGRADE_FAILED`), `404` if the module
  isn't installed, `409` on upgrade conflict, `200` on success.

Both require the `MODULES_MANAGE` permission. The `422` body is an
`ErrorResponse` JSON object whose message names the offending field
(e.g. `'backend' is required`,
`extensions[0].variants[1].sha256 must be a 64-char hex string`).

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — controller
  entrypoint contract.
- [DaemonModule](/reference/module-sdk/daemon-module/) — daemon
  entrypoint contract.
- [Capability API](/reference/module-sdk/capability-api/) — capability
  handles behind `provides` / `requires`.
- [Storage API](/reference/module-sdk/storage-api/) — handles behind the
  `storage` block.
