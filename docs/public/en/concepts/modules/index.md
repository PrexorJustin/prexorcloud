---
title: Module System
description: How PrexorCloud loads jars at runtime — platform modules in the controller, daemon modules on each host, capabilities for cross-module wiring.
---

A module is a JVM jar that the cluster loads at runtime to add features
without forking the codebase. Modules can register REST routes, subscribe
to events, store per-module state, expose typed capabilities, contribute
dashboard pages, and ship workload extensions for Minecraft servers.
This page is the orientation across the module system; the four pages
that follow are the depth on each axis.

## What you'll learn

- The two module hosts (controller, daemon) and what each can do.
- How modules link to each other through capabilities, never through
  classloaders.
- The shape of a module manifest and the lifecycle the controller drives
  it through.
- Where to look next for platform modules, daemon modules, the
  capability registry, and the lifecycle FSM.

## What a module is

A module is:

- A jar built against `cloud-api` only.
- Containing a `META-INF/prexor-module.json` manifest with id, version,
  hosts, dependencies, capabilities, extensions, and frontend manifest
  references.
- Optionally accompanied by a `<jar>.cosign.bundle` (or legacy
  `<jar>.sig`) signature.
- Installed via `prexorctl module install <bundle>` against the
  controller.

The reference module is `stats-aggregator` under
`java/cloud-modules/stats-aggregator/`. Anything described
in this section of the docs is exercised by it end-to-end.

## Two hosts

Modules declare which hosts they target in their manifest:

```yaml
manifestVersion: 1
id: my-module
hosts: [controller]              # or [daemon] or [controller, daemon]
backend:
  controller:
    entrypoint: com.example.MyControllerModule
  daemon:
    entrypoint: com.example.MyDaemonModule
```

| Host | Process | Loads | Has access to |
|---|---|---|---|
| `controller` | controller JVM | `PlatformModule` implementation | EventBus, MongoDB storage, Valkey storage (production), capability registry, REST route registry, ClusterView |
| `daemon` | daemon JVM | `DaemonModule` implementation | Instance lifecycle hooks, node-local capability registry, scoped EventBus subscriptions over the controller stream |

A module that lists both hosts ships *one jar* with two entrypoints. The
controller installs it normally, then fans the same jar out to every
connected daemon. Each side instantiates its own entrypoint.

See:

- [Platform Modules](/concepts/modules/platform/) — the controller-side
  contract.
- [Daemon Modules](/concepts/modules/daemon/) — the daemon-side contract
  and what's *not* available there (no MongoDB, no REST routes).

## Capabilities, not classpaths

The single mechanism by which modules link to each other is the
**capability registry**. A capability is a named, typed contract:
`CapabilityHandle<T>` where `T` is an interface defined in `cloud-api`.

```java
// In cloud-api: define the contract
public interface PlayerJourneyTracker {
    List<PlayerJourneyEvent> getJourney(UUID player, int limit);
    PlayerJourneyEvent getLatest(UUID player);
}
```

```java
// In a consumer module: resolve the handle, use it
CapabilityHandle<PlayerJourneyTracker> handle =
    registry.resolve("prexor.player.journey", PlayerJourneyTracker.class);
PlayerJourneyTracker tracker = handle.get();   // null if no provider
```

Cross-module classloader exposure is forbidden. A module that imports
another module's internal class will fail to load because the parent
classloader (the controller / daemon classloader) does not see other
modules' jars. This is the rule that lets you upgrade, disable, or
unload a module without breaking the rest of the system.

See [Capabilities](/concepts/modules/capabilities/) for the full registry
contract and the dynamic-handle behaviour.

## The lifecycle

Every module moves through a deterministic state machine:

```
INSTALLED → WAITING → ACTIVE → STOPPING → UNLOADED
                                   ↘
                                    FAILED
```

Transitions are persisted to MongoDB (`module_packages` collection) and
propagated as SSE events. The dashboard module page reflects the state
in real time. A module can be paused mid-lifecycle (e.g. when a
capability dependency cannot be resolved); the reason is stored
alongside the state so operators can see *why* a module is in `WAITING`
rather than `ACTIVE`.

See [Lifecycle](/concepts/modules/lifecycle/) for the full state
machine, including the install / upgrade / uninstall transitions.

## Where modules cannot reach

The controller deliberately does not expose:

- The internal `ClusterState` model (modules see read-only `ClusterView`).
- The internal `EventBus` write side (modules can publish their own
  events through the SDK, not arbitrary controller-internal events).
- Other modules' classloaders, fields, or MongoDB collections.
- The mTLS material, the JWT signing key, or any plugin token.

If you find yourself wanting one of these, the answer is a new
capability plus a new audit on its design. Not a hack.

## Authoring and shipping

The CLI ships everything an author needs:

```bash
# Scaffold a new module from the template
prexorctl module new my-module

# Watch + reload during development (auto-uploads on rebuild)
prexorctl module dev my-module

# Run gradle tests
prexorctl module test my-module

# Build for release
cd java && ./gradlew :cloud-modules:my-module:shadowJar

# Sign with cosign (your key + Sigstore identity flow)
cosign sign-blob --bundle my-module.cosign.bundle path/to/my-module.jar

# Upload to a controller
prexorctl module install my-module.jar  # auto-detects sibling .cosign.bundle
```

Module bundles are signed via cosign. Production controllers verify
fail-closed against a configured trust root. See
[Security](/concepts/security/) and the module signing
reference
for the verification flow.

## Module vs plugin

Modules are not the only way to extend a Minecraft network. A
`@CloudPlugin` jar (Path A) lives in a server's `cloud-plugins/` directory and
runs inside the Minecraft JVM. A module (Path B) lives on the controller
and may bundle a workload extension that gets fanned out to instances.

Picking between them is its own decision — see [Plugins](/concepts/plugins/)
for the side-by-side comparison.

## Next up

- [Platform Modules](/concepts/modules/platform/) — the controller-side
  contract: REST routes, EventBus, storage, frontend manifests.
- [Daemon Modules](/concepts/modules/daemon/) — the host-side contract:
  instance lifecycle hooks, node-local state, scoped event subscriptions.
- [Capabilities](/concepts/modules/capabilities/) — registering and
  resolving capability handles.
- [Lifecycle](/concepts/modules/lifecycle/) — the state machine, the
  classloader rules, what cleanup runs on unload.
- [Plugins](/concepts/plugins/) — Path A vs Path B, when to pick which.
