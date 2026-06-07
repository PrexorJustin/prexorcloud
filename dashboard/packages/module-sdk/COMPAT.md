# `@prexorcloud/module-sdk` — Compatibility & Versioning

Source of truth for what the dashboard module SDK guarantees, how versions
line up between the npm package and the runtime contract, and what counts as
a breaking change.

> **Two version numbers, two purposes.** The npm package version
> (`@prexorcloud/module-sdk@x.y.z`) tracks the *TypeScript surface* a module
> author compiles against. The integer `MODULE_SDK_VERSION` tracks the
> *runtime contract* a controller will accept. Both are pinned in this
> document — they evolve on different cadences and only one of them is
> validated at install time.

## TL;DR

| | npm `version` | `MODULE_SDK_VERSION` | manifest `frontend.sdkVersion` |
|---|---|---|---|
| What it tracks | TypeScript types + helper API | Runtime contract loaded into the dashboard | Required runtime contract for this module |
| Who consumes it | Module author (build time) | Dashboard runtime (load time) | Controller `PlatformModuleManifestParser` (install time) |
| Bump rule | Semver | Integer, +1 on any breaking change | Author writes the integer they need |
| Validated by | TypeScript compiler | `isSupportedModuleSdkVersion` | `MIN_FRONTEND_SDK_VERSION..MAX_FRONTEND_SDK_VERSION` |

Current pinned values:

- `@prexorcloud/module-sdk` — **0.1.0** (`dashboard/packages/module-sdk/package.json`).
- `MODULE_SDK_VERSION` — **1** (`dashboard/app/sdk/index.ts:31`).
- Controller-accepted range — **1..1**
  (`PlatformModuleManifestParser.MIN_FRONTEND_SDK_VERSION` /
  `MAX_FRONTEND_SDK_VERSION`).

## How a module declares its requirement

`META-INF/prexor/module.yaml`:

```yaml
manifestVersion: 1
id: example-module
version: 0.1.0
backend:
  entrypoint: com.example.ExampleModule
frontend:
  sdkVersion: 1
  entry: dashboard/entry.js
```

`frontend.sdkVersion` is the **integer** runtime contract the module needs.
`PlatformModuleManifestParser` rejects the install if the integer falls
outside `[MIN_FRONTEND_SDK_VERSION, MAX_FRONTEND_SDK_VERSION]` for the
controller version the operator is running.

The npm package version is *not* validated — modules pin the npm dep at
build time, the bundle ships the types they compiled against, and the
runtime contract is what the dashboard enforces.

## Compatibility matrix

| Controller line | Accepts `frontend.sdkVersion` | Bundled `MODULE_SDK_VERSION` | Recommended npm `@prexorcloud/module-sdk` |
|---|---|---|---|
| pre-v1 (current) | `1..1` | `1` | `^0.1.0` |

Future controller releases append a row; an entry only ever **widens** the
accepted range, never narrows it. A controller that drops support for an
older `sdkVersion` is a major-line release and gets flagged in the upgrade
runbook (`docs/runbooks/upgrade.md`).

## What counts as breaking — bump rules

### Bump the integer `MODULE_SDK_VERSION` (and widen the controller range)

Any change that can make a previously-installed module bundle stop working
at load time:

- Removing or renaming an export from `dashboard/app/sdk/index.ts`.
- Changing the call signature of an exported composable / helper.
- Removing or renaming a Vue component re-export, or changing its prop
  contract in a non-additive way.
- Changing the shape of a TypeScript interface that a module's compiled
  output depends on at runtime (e.g. a property the dashboard reads off a
  `defineModule` return value).
- Changing the bridge file resolution
  (`dashboard/public/sdk/prexorcloud.mjs`) in a way that breaks existing
  import maps.
- Replacing a re-exported library (e.g. swapping `pinia` for a different
  store) such that previously-compiled bundles can no longer find the
  symbols they imported.

When this happens:

1. Bump `MODULE_SDK_VERSION` in `dashboard/app/sdk/index.ts`.
2. Add the new integer to `SUPPORTED_MODULE_SDK_VERSIONS` (keep the old
   integer if the dashboard runtime can still serve the old contract; drop
   it only when the runtime genuinely cannot).
3. Bump `MAX_FRONTEND_SDK_VERSION` in
   `PlatformModuleManifestParser` (and `MIN_FRONTEND_SDK_VERSION` only
   when dropping support).
4. Bump the npm `version` to a new **major** (`0.x → 0.(x+1)` while
   pre-1.0; `1.x → 2.0` once stable).
5. Add a row to the matrix above.

### Bump the npm `version` only

Anything that changes only the *type surface* a module author compiles
against, without changing what the dashboard runtime serves:

- Adding a new export (minor bump).
- Tightening a type to be more specific while keeping the runtime
  call-compatible (minor bump).
- Adding new optional fields to an interface (minor bump).
- Documentation, JSDoc, internal refactors (patch bump).

These do **not** require a `MODULE_SDK_VERSION` bump — older bundles keep
working because the runtime surface is unchanged.

### Pre-1.0 caveat

While the npm package is `0.x`, **minor bumps may include breaking type
changes** per the standard npm pre-1.0 convention. Consumers should pin
with `~0.1.0` (or exact `0.1.0`) rather than `^0.1.0` if they want to
avoid type-surface drift. The runtime contract integer is the only thing
the *controller* enforces during this window — type breaks surface as
build errors, not install rejections.

## What the SDK guarantees right now (`MODULE_SDK_VERSION = 1`)

- All exports from `dashboard/app/sdk/index.ts` resolve at runtime against
  the dashboard's bridge (`/sdk/prexorcloud.mjs`).
- `defineModule({ components, setup? })` is the supported entry-point
  shape; the loader reads `components` for route → component resolution.
- The Vue runtime is re-exported wholesale; module SFCs may import any
  Vue compiler-runtime helper (`createElementVNode`, `openBlock`, …) via
  the alias.
- `useModuleEvents`, `useApiClient`, `useScopedApi`, `usePaginatedData`,
  `useFilteredList`, `useModuleGuard`, `useAuthStore`, `useSseEventBus`
  are stable composables.
- The shadcn-vue UI components re-exported from `index.ts` are stable in
  name and prop surface; visual tweaks are not breaking.
- `toast` is provided via `vue-sonner`.

Anything not listed here is **not** part of the v1 contract, even if it
ships in the bundle.

## Testing your module against a controller

1. Install: `prexorctl module install <bundle.tar>` — install fails fast
   if `frontend.sdkVersion` is outside the controller's accepted range.
2. Live-iterate: `prexorctl module dev` rebuilds and re-uploads on jar
   changes; the controller re-validates the manifest on every upload.
3. The dashboard logs `module-sdk: unsupported sdkVersion <n>` to the
   browser console if the runtime sees a manifest it cannot serve — this
   should not happen if step 1 passed, but it covers the case where the
   dashboard is older than the controller.

## Where the contract lives in the tree

- npm package surface — `dashboard/packages/module-sdk/src/`.
- Runtime contract — `dashboard/app/sdk/index.ts`
  (`MODULE_SDK_VERSION`, `SUPPORTED_MODULE_SDK_VERSIONS`,
  `isSupportedModuleSdkVersion`).
- Bridge served to module bundles — `dashboard/public/sdk/prexorcloud.mjs`
  (auto-generated from `dashboard/app/sdk/index.ts` by
  `app/modules/sdk-bridge.ts`).
- Manifest validation — `PlatformModuleManifestParser` in
  `java/cloud-controller/src/main/java/.../module/platform/`.
