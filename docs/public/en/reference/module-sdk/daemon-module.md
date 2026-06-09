---
title: DaemonModule
description: Daemon-side module entrypoint — per-node lifecycle and instance-launch hooks, plus the controller-event bridge available to a daemon module.
---

`DaemonModule` is the per-node backend entrypoint, the daemon-side sibling of
[`PlatformModule`](/reference/module-sdk/platform-module/). One instance runs
inside the daemon process on every node where the module is installed. A daemon
module observes and mutates instance launches on its node, exports node-local
capability handles, and subscribes to controller-bus events through the daemon's
gRPC bridge.

The interface is
`me.prexorjustin.prexorcloud.api.module.platform.DaemonModule`. Every method has
a default empty (or empty-list) body, so a daemon module overrides only the
hooks it needs.

A daemon module has **no Mongo and no Redis storage**:
`ModuleContext.findMongoStorage()` and `findRedisStorage()` both return
`Optional.empty()`, and the `require*` variants throw `IllegalStateException`.
Daemon modules are node-local in v1.

## What you'll learn

- The five module-lifecycle hooks and the four instance-lifecycle hooks, with exact signatures.
- The fields on `InstanceSpec`, `InstanceHandle`, and `ExitInfo`.
- Which `InstanceSpec` fields you may mutate, and what the daemon does with mutations.
- How a daemon module subscribes to controller events through the gRPC bridge.
- How daemon modules pair with platform modules in a dual-host module.

## How the daemon runs your module

The controller pushes the module jar to the daemon over its gRPC stream as a
`ModuleInstall` message. `DaemonModuleManager` then:

1. Parses the `module.yaml` manifest. The install is ignored unless `hosts`
   contains `daemon`, and rejected if `hosts` declares `daemon` but
   `backend.daemon.entrypoint` is absent.
2. Verifies the jar signature (when a verifier is configured).
3. Opens an isolated `URLClassLoader` whose parent only delegates the prefixes
   `java.`, `javax.`, `jdk.`, `sun.`, `org.slf4j.`, and
   `me.prexorjustin.prexorcloud.api.`. Anything else your module needs must be in
   the jar. cloud-api types are shared with the daemon; your own classes are not
   visible to other modules.
4. Instantiates the `backend.daemon.entrypoint` class via its no-arg
   constructor and verifies it implements `DaemonModule`.
5. Drives the module-lifecycle hooks (`onLoad` → `onStart`) through the shared
   `ModuleLifecycleManager`. The lifecycle manager only knows `PlatformModule`,
   so a `DaemonModuleAdapter` forwards those five hooks to your `DaemonModule`.
6. On reaching `ACTIVE`, registers your module's capability handles in the
   node-local registry and adds the module to `DaemonModuleHost` — the
   dispatcher that fires the instance-lifecycle hooks.

State transitions (`ACTIVE`, `WAITING`, `FAILED`, `UNLOADED`) are reported back
to the controller as `ModuleStateUpdate` messages.

The instance-lifecycle hooks are dispatched separately from the module-lifecycle
hooks: `ProcessManager` calls `DaemonModuleHost` around the spawn and exit path,
fanning each hook out to every active daemon module on the node.

## Module lifecycle

```java
default void onLoad(ModuleContext context)    throws Exception {}
default void onStart(ModuleContext context)   throws Exception {}
default void onStop(ModuleContext context)    throws Exception {}
default void onUnload(ModuleContext context)  throws Exception {}
default void onUpgrade(ModuleContext context) throws Exception {}
```

Same semantics and ordering as `PlatformModule`, executed inside the daemon JVM
and forwarded through `DaemonModuleAdapter`. The `ModuleContext` handed to these
hooks is a `DaemonModuleContext`: `host()` returns `ModuleHost.DAEMON`, storage
is unavailable, and `events()` returns the daemon-side bridge (see
[Event bridge](#event-bridge)).

| Hook | When | Throws |
|---|---|---|
| `onLoad` | After classloading, before activation. Resolve required capabilities, read config. | Failure marks the module `FAILED`; it does not reach `ACTIVE`. |
| `onStart` | On activation. Start background work. | Failure marks the module `FAILED`. |
| `onStop` | On uninstall or daemon shutdown, before `onUnload`. | Logged; uninstall continues. |
| `onUnload` | After `onStop`. Release resources. | Logged; uninstall continues. |
| `onUpgrade` | On re-install of an already-installed module id with a new jar, in place of a fresh `onLoad`/`onStart`. Read `context.previousVersion()` / `context.isUpgrade()`. | Failure marks the module `FAILED`. |

Use `context.host()` to branch shared code; in a daemon module it is always
`ModuleHost.DAEMON`.

## Instance lifecycle

These hooks fire only for instances on the daemon's own node, dispatched by
`DaemonModuleHost`. Each dispatch is wrapped in `try/catch` plus an SLF4J `WARN`,
so a throwing hook is logged and ignored — a misbehaving module cannot wedge the
daemon or abort an instance. See [Misbehaviour contract](#misbehaviour-contract).

### `onInstanceStarting`

```java
default void onInstanceStarting(InstanceSpec spec) throws Exception {}
```

Pre-launch hook for an instance about to start on this node. Mutate
`spec.jvmArgs()` or `spec.env()` to inject JVM flags or environment variables
before the daemon builds the start command. All other `InstanceSpec` fields are
read-only.

The daemon reads `spec.jvmArgs()` and `spec.env()` back after the hook returns
and copies them into the resolved start spec, so additions and removals on those
two collections take effect. Other fields are ignored even if you reach into
their backing values.

> **Throwing does not abort the launch.** The interface Javadoc states that
> throwing from `onInstanceStarting` "aborts the start with an error report,"
> but the current daemon wiring (`DaemonModuleHost.dispatchInstanceStarting` →
> `ProcessManager.applyModuleStartingHooks`) swallows the exception with a `WARN`
> and proceeds with whatever mutations were applied before the throw. Treat this
> hook as advisory, not as a launch gate. (See `unverifiedClaims`.)

### `onInstanceStarted`

```java
default void onInstanceStarted(InstanceHandle handle) throws Exception {}
```

Fired after the instance process is spawned and the daemon has a PID.

### `onInstanceStopping`

```java
default void onInstanceStopping(InstanceHandle handle) throws Exception {}
```

Fired before the daemon stops the instance process (graceful or forced).

### `onInstanceStopped`

```java
default void onInstanceStopped(InstanceHandle handle, ExitInfo exit) throws Exception {}
```

Fired after the instance process has exited (clean or crashed). `ExitInfo`
carries the exit duration and the crash flag — see the field notes below for
which `ExitInfo` fields the current wiring populates.

## Hook argument types

### `InstanceSpec`

`me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec` — a mutable view
of an instance about to launch. Constructor order:

```java
InstanceSpec(
    String instanceId,
    String group,
    int port,
    int memoryMb,
    List<String> jvmArgs,
    Map<String, String> env,
    String platform,
    String platformVersion,
    String jarFile,
    String planHash)
```

| Accessor | Type | Mutable | Notes |
|---|---|---|---|
| `instanceId()` | `String` | read-only | Non-null. |
| `group()` | `String` | read-only | Non-null. |
| `port()` | `int` | read-only | |
| `memoryMb()` | `int` | read-only | |
| `jvmArgs()` | `List<String>` | **mutable** | Add or remove entries; read back by the daemon. Backed by an `ArrayList`. |
| `env()` | `Map<String, String>` | **mutable** | Add or replace entries; read back by the daemon. Backed by a `LinkedHashMap`. |
| `platform()` | `String` | read-only | Non-null. e.g. `paper`, `velocity`, `fabric`. |
| `platformVersion()` | `String` | read-only | Non-null. |
| `jarFile()` | `String` | read-only | May be `null`. |
| `planHash()` | `String` | read-only | May be `null`. |

The constructor copies the supplied `jvmArgs`/`env` into fresh mutable
collections (null becomes empty), so `jvmArgs()` and `env()` are always non-null.

### `InstanceHandle`

`me.prexorjustin.prexorcloud.api.module.platform.InstanceHandle` — a read-only
record handed to `onInstanceStarted`, `onInstanceStopping`, and
`onInstanceStopped`.

```java
record InstanceHandle(
    String instanceId,
    String group,
    int port,
    long pid,
    Instant startedAt,
    String state)
```

`state` mirrors the daemon's local lifecycle state and is informational; the
authoritative cluster state lives on the controller. In the `onInstanceStopped`
path where no live process record remains, the daemon synthesizes a handle with
`port=0`, `pid=-1`, `startedAt=Instant.EPOCH`, and `state="STOPPED"`.

### `ExitInfo`

`me.prexorjustin.prexorcloud.api.module.platform.ExitInfo` — a process-exit
summary record handed to `onInstanceStopped`.

```java
record ExitInfo(
    int exitCode,
    long durationMs,
    boolean crashed,
    String crashSummary)
```

| Field | Type | Notes |
|---|---|---|
| `exitCode()` | `int` | Per the record contract, the process exit code. **Current `ProcessManager` wiring always passes `0`** regardless of the real exit code. |
| `durationMs()` | `long` | Process uptime in ms (`0` when no live process record remained at stop). |
| `crashed()` | `boolean` | `true` when the daemon's crash detector classified the exit as a crash. |
| `crashSummary()` | `String` | One-line crash summary; non-null only when `crashed` is true and the detector produced one. **Current wiring always passes `null`.** |

Use `crashed()` to distinguish a crash from a managed stop. Do not rely on
`exitCode()` or `crashSummary()` until the wiring populates them. (See
`unverifiedClaims`.)

## `capabilityHandles`

```java
default List<CapabilityHandle<?>> capabilityHandles() {
    return List.of();
}
```

Returns the capability handles this module exports after activation. Same
contract as `PlatformModule.capabilityHandles()`, but the binding is
**node-local** — cross-node capability visibility is out of scope for v1.

Build a handle with `CapabilityHandle.of(id, type, value)`; the factory throws
`IllegalArgumentException` if `id` is blank or `value` is not an instance of
`type`.

```java
@Override
public List<CapabilityHandle<?>> capabilityHandles() {
    return List.of(CapabilityHandle.of(
            "agent-injector", AgentInjector.class, this.injector));
}
```

## Event bridge

`context.events()` on a daemon module returns `DaemonEventBus`, an `EventBus`
that bridges to the controller's bus. The bridge is **subscribe-registered**:

- When a daemon module subscribes to a `Class<? extends CloudEvent>` and that
  class has no other local subscribers yet, the bus sends an `EventSubscribe`
  message to the controller so future events of that type are forwarded back to
  this daemon as `ModuleEvent`.
- When the last subscriber for a class unsubscribes, the bus sends
  `EventUnsubscribe` so the controller stops forwarding that type to this daemon.
- On gRPC reconnect, the bus re-sends `EventSubscribe` for every
  currently-subscribed class so a stream blip does not desync the controller.

The `EventBus` surface (`me.prexorjustin.prexorcloud.api.event.EventBus`):

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
<T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler);
EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
EventSubscription subscribeAll(EventHandler<CloudEvent> handler);
void publish(CloudEvent event);
```

Behavior notes specific to the daemon bridge:

- Inbound controller events are deserialized with the daemon's classloader.
  Event types not on the daemon's classpath are logged at `WARN` and dropped, so
  a module that subscribes to its own `CloudEvent` subclass must ship that class
  in its jar.
- Handlers run on a fresh virtual thread per event; a throwing handler is logged
  at `WARN` and does not block other handlers.
- `subscribe(...)` returns an `EventSubscription`; call `unsubscribe()` (the
  functional method) to drop the handler and, if it was the last for that class,
  emit `EventUnsubscribe`.
- `subscribeByType` only fires for `CustomCloudEvent`; `subscribeAll` is a local
  catch-all over events that reach this daemon. `publish(...)` dispatches to
  **local** subscribers on this daemon only — it does not push back to the
  controller bus.

```java
@Override
public void onStart(ModuleContext context) {
    context.events()
            .on(InstanceStateChangedEvent.class)
            .filter(e -> e.group().equals("lobby"))
            .subscribe(e -> context.logger().info("lobby instance {} -> {}",
                    e.instanceId(), e.state()));
}
```

## Misbehaviour contract

Each instance-lifecycle hook is wrapped by `DaemonModuleHost` with a `try/catch`
plus an SLF4J `WARN`. Throwing from `onInstanceStarting`, `onInstanceStarted`,
`onInstanceStopping`, or `onInstanceStopped` is logged and ignored — none of the
four aborts an instance or wedges the daemon. The module-lifecycle hooks differ:
throwing from `onLoad`, `onStart`, or `onUpgrade` marks the module `FAILED` (it
never reaches `ACTIVE`), while exceptions from `onStop`/`onUnload` are logged and
uninstall continues.

## Example

A daemon module that injects a `-javaagent:` flag into every starting instance.
Daemon modules have no storage, so configuration arrives over a capability or the
manifest, not Mongo/Redis.

```java
package com.example.agent;

import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;

public final class AgentInjectorModule implements DaemonModule {

    private final String agentPath = "/opt/prexor/agents/observer.jar";

    @Override
    public void onLoad(ModuleContext context) {
        // host() is always ModuleHost.DAEMON here; storage is unavailable.
        context.logger().info("agent injector loaded on {}", context.host());
    }

    @Override
    public void onInstanceStarting(InstanceSpec spec) {
        // jvmArgs() is mutable and read back by the daemon before launch.
        spec.jvmArgs().add("-javaagent:" + agentPath);
    }
}
```

The matching manifest declares the daemon host and entrypoint:

```yaml
manifestVersion: 1
id: agent-injector
version: 1.0.0
hosts: [daemon]
backend:
  daemon:
    entrypoint: com.example.agent.AgentInjectorModule
```

## Dual-host modules

A module that needs both controller- and daemon-side behavior declares both
hosts and ships an entrypoint for each:

```yaml
hosts: [controller, daemon]
backend:
  controller:
    entrypoint: com.example.MyControllerModule
  daemon:
    entrypoint: com.example.MyDaemonModule
```

- the `PlatformModule` is `backend.controller.entrypoint`
- the `DaemonModule` is `backend.daemon.entrypoint`

The two halves run in different processes and do not share heap state. They
communicate through the controller bus: the daemon half subscribes to event
types, the controller forwards matching events to the daemon over the gRPC
bridge described in [Event bridge](#event-bridge). At least one of `controller`
or `daemon` must be present in `backend`.

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — controller-side sibling contract.
- [ModuleContext](/reference/module-sdk/module-context/) — what's available inside a daemon-module hook.
- [Concepts → Daemon modules](/concepts/modules/daemon/)
