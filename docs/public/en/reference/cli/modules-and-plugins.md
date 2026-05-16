---
title: Modules + Plugins
description: prexorctl module and plugin — install platform modules to the controller and scaffold standalone CloudPlugin jars.
---

PrexorCloud has two extension surfaces — **modules** (cluster-wide,
loaded by the controller and/or daemon) and **plugins** (single-platform
jars that drop into a Minecraft server's `cloud-plugins/` folder). The
relevant CLI subcommands handle install, listing, and scaffolding for
both.

## What you'll learn

- How to install and remove platform modules on the controller.
- How to scaffold a new module project from the wizard or non-interactive
  flags.
- How to scaffold a standalone `@CloudPlugin` jar for a specific platform.

## `prexorctl module`

### `module list`

```bash
prexorctl module list
prexorctl module list --json
```

Shows installed module name, enabled status, whether a frontend bundle
is present, and the count of platform plugins shipped inside the
module artifact.

### `module upload`

```bash
prexorctl module upload ./build/libs/cloud-module-stats-aggregator-1.0.0.jar
```

Uploads a shaded module jar to `POST /api/v1/modules/platform/upload`
and installs it. Only `.jar` files are accepted. Cosign verification
applies when `modules.signing.required=true` on the controller.

### `module delete`

```bash
prexorctl module delete <name>
```

Removes the module from the controller. Daemons holding a daemon-host
copy of the module receive a `ModuleUninstall` over the gRPC stream
and unload it on their side.

### `module new`

Scaffold a fresh module project under
`java/cloud-modules/<name>/`. Three flavours:

```bash
# Default: full TUI wizard.
prexorctl module new

# Compact: only asks which platform-plugin targets to ship.
prexorctl module new my-module --interactive

# Non-interactive subset for scripts.
prexorctl module new my-module --targets paper,velocity

# No prompts at all — drops the example template, all targets emitted.
prexorctl module new my-module --all-defaults
```

Flags:

- `--package <dotted>` — override the generated Java package. Default
  `me.prexorjustin.prexorcloud.modules.<name>`.
- `--repo-root <path>` — repo root for the scaffold. Default: discovered
  upward from the cwd.
- `--strip-comments` — remove the `// STEP …` teaching comments from
  generated sources.
- `--force` — overwrite an existing module directory.
- `--dry` — walk the template and print what would happen, without
  writing anything.
- `--interactive` — compact targets-only prompt.
- `--wizard` — force the full wizard even if `--targets` /
  `--all-defaults` are passed.
- `--browser` — reserved for a future browser-based wizard (returns a
  clear error in this build).
- `--targets <list>` — comma-separated subset of
  `paper,folia,velocity,bungeecord,bedrock-geyser`. Skips the wizard.
- `--all-defaults` — emit the full example template; no prompts.

## `prexorctl plugin`

### `plugin new`

Scaffold a standalone single-platform plugin under
`java/cloud-plugin/cloud-plugin-<name>/`. A plugin here is a single
shaded jar with a `@CloudPlugin` annotation that drops directly into
a Minecraft server's `cloud-plugins/` folder. Use this when you only need
in-game / in-proxy behaviour on one platform; use `module new` when
you need cluster-wide state, REST endpoints, or a dashboard UI.

```bash
prexorctl plugin new welcome --platform paper --mc-version 1.21
prexorctl plugin new my-proxy --platform velocity --author "Acme Corp"
```

Flags:

- `--platform <p>` *(required)* — `paper`, `spigot`, `folia`, `velocity`,
  or `bungeecord`.
- `--mc-version <ver>` — paper only (`1.20` or `1.21`). Default `1.20`.
- `--package <dotted>` — override the Java package. Default
  `me.prexorjustin.prexorcloud.plugins.<name>`.
- `--repo-root <path>` — repo root override.
- `--description <str>` — written into the `@CloudPlugin` annotation.
- `--author <name>` — author field for `@CloudPlugin`. Default
  `PrexorCloud`.
- `--force` — overwrite an existing plugin dir.
- `--dry` — dry-run; print actions without writing.

Build the resulting plugin with:

```bash
cd java && ./gradlew :cloud-plugin:cloud-plugin-<name>:shadowJar
```

## Next up

- [Module SDK](/reference/module-sdk/) — the Java API consumed by
  scaffolded modules.
- [Plugin SDK](/reference/plugin-sdk/) — `@CloudPlugin` and the
  `VersionDispatcher` pattern for multi-version support.
- [Modules vs Plugins](/concepts/plugins/) — when
  to reach for which.
