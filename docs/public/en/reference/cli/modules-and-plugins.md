---
title: Modules + plugins
description: prexorctl module and plugin subcommands — install, upgrade, search, doctor, list, delete, and scaffold platform modules and standalone @CloudPlugin jars.
---

PrexorCloud has two extension surfaces. A **module** is a controller-side
extension: a signed jar the controller verifies, installs, and (where the
manifest declares plugin variants) streams to daemons. A **plugin** is a
single-platform jar that drops into one Minecraft server's `plugins/` folder
and connects back over `cloud-api`.

`prexorctl module` covers the full module lifecycle — install, upgrade,
search, validate, list, delete — plus local authoring (`new`, `dev`, `test`).
`prexorctl plugin` covers authoring standalone `@CloudPlugin` jars (`new`).

All `module` subcommands that talk to the controller require a linked context
(`prexorctl link`). The authoring subcommands (`module new`, `module dev`,
`module test`, `plugin new`) operate on files under the repo root and most do
not need a controller — `module new` is explicitly marked `local-only`, so it
runs before the CLI is linked.

## Global flags

These persistent flags apply to every subcommand below.

- `--json`, `-j` — emit JSON instead of the table/status renderer. Setting the
  env var `PREXOR_OUTPUT=json` has the same effect.
- `--controller <url>`, `-c` — override the controller URL for this invocation.
- `--token <token>`, `-t` — override the auth token.
- `--context <name>` — override the active context.
- `--no-color`, `--ascii`, `--verbose`/`-v` — output controls.

## `prexorctl module`

`prexorctl module` is a parent command with no action of its own. The
subcommands are `install`, `upgrade`, `search`, `doctor`, `list`, `upload`,
`delete`, `new`, `dev`, and `test`.

### `module install`

```
prexorctl module install <jar | bundle.tar | id[@version]>
```

Install a signed platform module. The single argument is auto-detected as one
of two sources:

- **Local bundle** — a `.jar`, or a `.tar` / `.tar.gz` / `.tgz` containing
  exactly one jar and at most one signature sidecar. For a bare jar, the
  signature sidecar is auto-detected as `<jar>.cosign.bundle` (preferred) or
  `<jar>.sig`, or set explicitly with `--signature`. The jar and sidecar are
  uploaded to `POST /api/v1/modules/platform/upload`; the controller verifies
  the signature before installing.
- **Registry spec** — a string like `stats-aggregator` or
  `stats-aggregator@1.2.0` that is not a path on disk. The controller pulls the
  signed jar from a configured registry (`modules.registries` in
  `controller.yml`), checks its sha256 against the index, and verifies the
  signature against its own trust root via
  `POST /api/v1/modules/platform/registry/install`. Omit the version (or use
  `@latest`) for the newest.

Source detection rule: a `.jar`/`.tar*` suffix, or any argument that `stat`s as
an existing file, is treated as a local bundle; everything else is a registry
spec.

Flags:

- `--signature <path>` — explicit path to the signature sidecar (`.sig` or
  `.cosign.bundle`). Default: autodetect `<jar>.cosign.bundle` then `<jar>.sig`.
- `--check-requires` — before uploading a local jar, read its
  `META-INF/prexor/module.yaml`, fetch
  `GET /api/v1/modules/platform/capabilities`, and print one line per required
  capability — a warning when no active controller module provides it, a
  success line naming the provider(s) otherwise. Non-fatal: the install
  proceeds regardless. Only applies to local jars.
- `--registry <url>` — for a registry install, pin one of the configured
  registry URLs instead of searching all of them.

Local install with an auto-detected cosign bundle:

```bash
prexorctl module install ./build/libs/cloud-module-stats-aggregator-1.2.0.jar
```

```
✓ Module "stats-aggregator" installed (signature: cloud-module-stats-aggregator-1.2.0.jar.cosign.bundle)
```

Registry install, pinned version:

```bash
prexorctl module install stats-aggregator@1.2.0
```

```
✓ Module "stats-aggregator" installed from registry (version 1.2.0)
```

Preflight a jar's capability requirements before uploading:

```bash
prexorctl module install ./build/libs/journey-1.0.0.jar --check-requires
```

```
✓ Required capability "player-stats" (range [1.0,2.0)) — provided by stats-aggregator v1.2.0.
```

If the source is a local file but is neither a `.jar` nor a recognized tar
bundle, the command fails with
`expected a .jar or .tar/.tar.gz/.tgz bundle, got <source>`. A tar bundle with
zero jars, more than one jar, more than one sidecar, or a path-traversal entry
is rejected before upload.

### `module upgrade`

```
prexorctl module upgrade [id]
prexorctl module upgrade --all
```

Upgrade an installed module to the newest version a configured registry
advertises — a convenience over `install <id>@latest`. The controller's
registry catalog (`GET /api/v1/modules/platform/registry`) reports, per module,
the latest advertised version and the currently installed version. `upgrade`
reinstalls the newer version pinned to that exact version, so the controller
re-verifies sha256 and signature exactly like a fresh install.

Pass either an `id` or `--all`, not both, and not neither.

Flags:

- `--all` — upgrade every installed module a configured registry offers a newer
  version for.
- `--registry <url>` — pin one of the configured registry URLs instead of
  searching all of them.

Decision table for `upgrade <id>`:

| Catalog state | Result |
|---|---|
| Not offered by any registry | error: `module "<id>" is not offered by any configured registry` |
| Offered but not installed | error: `module "<id>" is not installed — use \`prexorctl module install <id>\`` |
| Installed, version matches advertised | `✓ Module "<id>" is already up to date (<version>).` — no-op |
| Installed, advertised version differs | reinstall pinned, then `✓ Module "<id>" upgraded <from> → <to>.` |

When no registries are configured, the command prints a warning and exits 0:
`No registries configured. Set modules.registries in controller.yml.`

```bash
prexorctl module upgrade stats-aggregator
```

```
✓ Module "stats-aggregator" upgraded 1.1.0 → 1.2.0.
```

`--all` upgrades every installed module whose advertised version differs from
the installed one, printing a line per module. If any upgrade fails, the others
still run and the command exits non-zero with
`N of M module upgrade(s) failed`. With `--json`, the result is
`{"upgraded": [{"moduleId", "from", "to", "ok", "error?"}, …]}`.

```bash
prexorctl module upgrade --all
```

```
✓ Module "stats-aggregator" upgraded 1.1.0 → 1.2.0.
✓ All installed modules are up to date.
```

### `module search`

```
prexorctl module search [query]
```

List the modules offered by the controller's configured registries
(`GET /api/v1/modules/platform/registry`). The optional `query` is a
case-insensitive substring filter on the module id or its tags. Install one
with `prexorctl module install <id>[@<version>]`.

The table columns are `MODULE`, `VERSION`, `REGISTRY`, `INSTALLED`, `TAGS`. The
`REGISTRY` cell appends `(unsigned)` for modules the registry index marks
unsigned. The `INSTALLED` cell shows an `ENABLED` pill with the installed
version when the module is already installed, or `—` otherwise. With no
registries configured, prints the same warning as `upgrade` and exits 0.

```bash
prexorctl module search stats
```

```
Registry modules · controller.example

MODULE             VERSION  REGISTRY   INSTALLED       TAGS
stats-aggregator   1.2.0    official   ● ENABLED (1.1.0)  analytics, stats

1 module · 1 registry
```

`--json` returns the raw response: `{"registries": [...], "modules": [...]}`.

### `module doctor`

```
prexorctl module doctor <jar>
```

Validate a built module jar against the platform-module contract — locally, no
controller required. Reads `META-INF/prexor/module.yaml` and runs the same
shape checks the controller performs on install. Checks:

- **Identity** — `manifestVersion` is `1` or `2`; `id` matches
  `^[a-z][a-z0-9-]*$`; `version` is semver.
- **Backend** — `backend.entrypoint` is set and the corresponding `.class` file
  is present in the jar.
- **Capabilities** — each `provides`/`requires` `id` matches the id pattern;
  `provides.version` is semver; `requires.versionRange` is non-empty. For
  `manifestVersion: 2`, `deprecatedSince`/`removedIn` must be semver,
  `removedIn` requires `deprecatedSince`, and a deprecated provide raises a
  warning.
- **Extensions** — every extension id and variant id is unique; each variant's
  declared `artifact` exists in the jar; its `sha256` is a 64-char hex digest
  that matches the actual entry. An empty or `AUTO` sha256 is a warning (an
  unprocessed source dump, not a release artifact).
- **Signature sidecar** — a soft check; warns when no `<jar>.cosign.bundle` or
  `<jar>.sig` sits next to the jar, since the controller rejects unsigned
  modules when `modules.signing.required=true`.

Exit codes are CI-friendly:

| Code | Meaning |
|---|---|
| `0` | clean — no findings |
| `1` | warnings only |
| `2` | errors — the controller would reject the jar |

```bash
prexorctl module doctor ./build/libs/cloud-module-stats-aggregator-1.2.0.jar
```

```
✓ doctor: cloud-module-stats-aggregator-1.2.0.jar is clean.
```

Directory inputs are not supported; pass a built `.jar`. A non-`.jar` argument
fails with `doctor: expected a .jar, got <path>`.

### `module list`

```
prexorctl module list
prexorctl module list --json
```

List modules installed on the controller (`GET /api/v1/modules`). Table columns
are `NAME`, `ENABLED`, `FRONTEND`, `PLUGINS`: the module id, an `ENABLED` or
`DISABLED` status pill, whether a dashboard frontend bundle is present, and the
count of platform plugins shipped inside the module artifact. The footer
summarizes the enabled/disabled split.

```bash
prexorctl module list
```

```
Listing modules · controller.example

NAME               ENABLED      FRONTEND  PLUGINS
stats-aggregator   ● ENABLED    yes       3

1 module · 1 enabled · 0 disabled
```

`--json` returns the raw module objects.

> There is no `module enable` / `module disable` subcommand in this build.
> Enabled/disabled is reported by `list` and `search`; it is not toggled from
> the CLI.

### `module upload`

```
prexorctl module upload <file.jar>
```

Upload a shaded module jar to `POST /api/v1/modules/platform/upload` and
install it. Only `.jar` files are accepted; any other suffix fails with
`only .jar files are accepted`. This is the no-signature, jar-only path —
prefer `module install`, which auto-detects a signature sidecar and supports
tar bundles and registry specs. Cosign verification applies when
`modules.signing.required=true` on the controller.

```bash
prexorctl module upload ./build/libs/cloud-module-stats-aggregator-1.2.0.jar
```

```
✓ Module "stats-aggregator" installed
```

`--json` returns the install result (`{"moduleId": …}`).

### `module delete`

```
prexorctl module delete <name>
```

Remove the module from the controller (`DELETE /api/v1/modules/platform/<name>`).
Daemons holding a daemon-host copy of the module receive a `ModuleUninstall`
over the gRPC stream and unload it on their side.

```bash
prexorctl module delete stats-aggregator
```

```
✓ Module stats-aggregator removed
```

### `module new` (alias: `module scaffold`)

```
prexorctl module new [name]
```

Scaffold a new cloud-module under `java/cloud-modules/<name>/`. Local-only:
operates entirely on files under the repo root and never contacts a controller,
so a fresh contributor can scaffold before linking the CLI. The `name` argument
is optional only in the default wizard flow (the wizard prompts for it);
every non-wizard flow requires it.

Four flows, picked by flag (evaluated in this order):

- **`--browser`** — reserved for a future browser wizard (Phase B.2). Returns a
  clear error in this build.
- **`--interactive`** — compact prompt that asks only which platform-plugin
  targets to ship (Paper, Folia, Velocity), then copies the example template
  with those targets. Requires `name`.
- **Composable spec flags** — any of `--capabilities`, `--requires`,
  `--no-rest`, `--no-mongo`, `--no-frontend`, `--no-plugin` builds a full module
  spec from flags and runs the same override pass as the TUI wizard. Requires
  `name`.
- **`--targets` / `--mc-plugin` / `--all-defaults`** — non-interactive
  template copy, no prompts. Requires `name`.
- **Default (no non-wizard flags)** — the full TUI wizard. Asks identity,
  storage, REST, frontend, plugin yes/no, per-platform multi-version strategy,
  and capabilities.

```bash
# Default: full TUI wizard (name optional — wizard prompts).
prexorctl module new

# Compact: targets-only prompt.
prexorctl module new leaderboards --interactive

# Non-interactive subset.
prexorctl module new leaderboards --targets paper,velocity

# Full example template, no prompts, all targets.
prexorctl module new leaderboards --all-defaults

# Backend-only module that consumes a capability.
prexorctl module new alerts --no-frontend --no-plugin --requires player-stats@[1.0,2.0)
```

Flags:

- `--package <dotted>` — override the generated Java package. Default
  `me.prexorjustin.prexorcloud.modules.<name>`.
- `--repo-root <path>` — repo root for the scaffold. Default: discovered upward
  from the working directory.
- `--strip-comments` — remove `// STEP …` and `/** STEP … */` teaching comments
  from generated sources.
- `--force` — overwrite an existing module directory instead of failing.
- `--dry` — walk the template and print what would happen without writing.
- `--interactive` — compact targets-only prompt; skips the full wizard.
- `--wizard` — force the full wizard even when `--targets` / `--all-defaults`
  are passed. (The wizard is already the default with no flags.)
- `--browser` — open the wizard in a local browser. Phase B.2; returns a clear
  error in this build.
- `--targets <list>` — comma-separated subset of
  `paper,folia,velocity,bungeecord,bedrock-geyser`. Skips the wizard.
- `--mc-plugin <list>` — alias for `--targets`. Passing both fails with
  `--targets and --mc-plugin are aliases; pass only one`.
- `--all-defaults` — skip the wizard and emit the full example template.
- `--capabilities <id[@version]>` — a capability the module provides. Default
  version `1.0.0` when `@version` is omitted. Repeatable (pass the flag once per
  capability — version strings are not comma-split).
- `--requires <id[@range]>` — a capability the module consumes. Default range
  `[1.0,2.0)` when `@range` is omitted. Repeatable.
- `--no-rest` — strip the `rest/` package.
- `--no-mongo` — strip the storage scaffold.
- `--no-frontend` — strip the `frontend/` Vue package.
- `--no-plugin` — backend-only module; skips every `plugin/<target>` subdir.

When `--no-plugin` is unset and no targets are given, the composable-spec flow
defaults to all targets: `paper`, `folia`, `velocity`, `bedrock-geyser`.

Output names the generated module, package, destination, file count, and
whether `settings.gradle.kts` was patched, then prints the next step:

```
→ new module: leaderboards
  package:  me.prexorjustin.prexorcloud.modules.leaderboards
  dest:     /repo/java/cloud-modules/leaderboards
  37 files
  patched settings.gradle.kts (+4 includes)

next:
  cd java && ./gradlew :cloud-modules:leaderboards:build
  (run pnpm install from the dashboard workspace to pick up the new frontend package)
```

### `module dev`

```
prexorctl module dev <name>
```

Watch a module's jar and reupload to the linked controller on change — the tight
inner loop for module development. Resolves the module at
`java/cloud-modules/<name>`, reads its `archiveName` from `build.gradle.kts`,
and polls `build/libs/<archiveName>.jar` for mtime/size changes. On the first
change it uploads to `POST /api/v1/modules/platform/upload`; on every subsequent
change it uploads to `POST /api/v1/modules/platform/{moduleId}/upgrade`, falling
back to a fresh install if the controller reports the module is gone (404).

If the module has a `frontend/` subtree, `frontend/dist/` is watched
separately. A change there triggers a frontend-only
`POST /api/v1/modules/platform/{moduleId}/frontend/reload` that re-stages just
the dashboard bundle without touching the platform module's classloader. A jar
change takes priority on the same tick — its shaded jar already bundles the
latest frontend — so the frontend-only path is short-circuited.

By default `module dev` spawns `./gradlew :cloud-modules:<name>:assemble -t` in
the background and forwards its output. Pass `--no-build` to run Gradle
yourself. Stop with Ctrl+C.

Flags:

- `--repo-root <path>` — repo root. Default: discovered upward from the cwd.
- `--poll <duration>` — how often to stat the jar. Default `750ms`.
- `--no-build` — don't spawn the continuous Gradle build; assume something else
  rebuilds the jar.

```bash
prexorctl module dev leaderboards
```

```
module dev — leaderboards
  dir         /repo/java/cloud-modules/leaderboards
  jar         /repo/java/cloud-modules/leaderboards/build/libs/leaderboards-1.0.0.jar
  controller  https://controller.example:8443
  poll        750ms

✓ [14:02:17] reloaded leaderboards (4821330 bytes)
✓ [14:03:48] frontend hot-reloaded (hash=9f3c…)
```

### `module test`

```
prexorctl module test <name>
```

Run a module's Gradle test task. A wrapper for
`./gradlew :cloud-modules:<name>:test` executed from the repo's `java/`
directory, forwarding stdin/stdout/stderr and propagating Gradle's exit code so
CI and shell pipelines can branch on it.

Flags:

- `--repo-root <path>` — repo root. Default: discovered upward from the cwd.
- `--gradle-arg <arg>` — extra argument forwarded to Gradle. Repeatable.

```bash
prexorctl module test leaderboards --gradle-arg --tests --gradle-arg "*RepositoryTest"
```

## `prexorctl plugin`

Tooling for the standalone `@CloudPlugin` path. A plugin here is a
single-platform jar that drops into a Paper / Spigot / Folia / Velocity /
BungeeCord server's `plugins/` folder and connects to the controller via
`cloud-api`. It is not a module — no `module.yaml`, no frontend, no per-platform
variants. Reach for `plugin new` when you only need in-game / in-proxy behaviour
on one platform; reach for `module new` when you need cluster-wide state, REST
endpoints, dashboard UI, or coordination across nodes.

### `plugin new`

```
prexorctl plugin new <name> --platform <p>
```

Scaffold a standalone `@CloudPlugin` subproject under
`java/cloud-plugin/cloud-plugin-<name>/`. The generated subproject applies the
matching `prexorcloud.plugin-<platform>` convention plugin, contains one Java
file — `<Pascal>Plugin extends CloudPluginBase` annotated with `@CloudPlugin` —
and is wired into `java/settings.gradle.kts` under the `// ---- PLUGINS ---- //`
anchor. The `name` is required, passed as a positional argument.

`--platform` is required and accepts `paper`, `spigot`, `folia`, `velocity`, or
`bungeecord` (case-insensitive). The platform selects the convention plugin:

| `--platform` | `--mc-version` | Convention plugin |
|---|---|---|
| `paper` | `1.20` (default) | `prexorcloud.plugin-paper` |
| `paper` | `1.21` | `prexorcloud.plugin-paper-1-21` |
| `spigot` | — | `prexorcloud.plugin-spigot` |
| `folia` | — | `prexorcloud.plugin-folia` |
| `velocity` | — | `prexorcloud.plugin-velocity` |
| `bungeecord` | — | `prexorcloud.plugin-bungeecord` |

`--mc-version` applies to `paper` only and accepts `1.20` or `1.21`; any other
value fails with
`--mc-version=<v> not supported for paper (use 1.20 or 1.21)`. It is ignored on
other platforms.

Flags:

- `--platform <p>` *(required)* — `paper` | `spigot` | `folia` | `velocity` |
  `bungeecord`. Empty or unknown values are rejected.
- `--mc-version <ver>` — Minecraft API version for paper (`1.20` | `1.21`).
  Default `1.20`. Ignored on other platforms.
- `--package <dotted>` — override the generated Java package. Default
  `me.prexorjustin.prexorcloud.plugins.<name>`.
- `--repo-root <path>` — repo root. Default: discovered upward from the cwd.
- `--description <str>` — written into the `@CloudPlugin` annotation.
- `--author <name>` — author field for `@CloudPlugin`. Default `PrexorCloud`.
- `--force` — overwrite an existing plugin directory instead of failing.
- `--dry` — walk the scaffold and print what would happen without writing.

```bash
prexorctl plugin new welcome --platform paper --mc-version 1.21
```

```
→ new plugin: welcome
  platform  paper (prexorcloud.plugin-paper-1-21)
  package   me.prexorjustin.prexorcloud.plugins.welcome
  dest      /repo/java/cloud-plugin/cloud-plugin-welcome
  files     4
  ✓ patched settings.gradle.kts (+1 include)

next:
  cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

Build the shaded jar and drop it into a server's `plugins/` folder:

```bash
cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

## Next up

- [Module SDK](/reference/module-sdk/) — `PlatformModule`, `DaemonModule`,
  `ModuleContext`, capability handles, and the `module.yaml` manifest schema
  consumed by scaffolded modules.
- [Plugin SDK](/reference/plugin-sdk/) — `@CloudPlugin`, `CloudPluginBase`, and
  the `VersionDispatcher` pattern for multi-version support.
- [Modules vs plugins](/concepts/plugins/) — when to reach for which.
