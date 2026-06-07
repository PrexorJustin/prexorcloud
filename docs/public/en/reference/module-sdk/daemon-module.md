---
title: DaemonModule
description: Daemon-side module entrypoint — per-instance lifecycle hooks for modules that need to observe or mutate instance launches.
---

`DaemonModule` is the per-node sibling of
[`PlatformModule`](/reference/module-sdk/platform-module/). It runs
inside the daemon process on every node where the module is installed,
participates in instance-lifecycle hooks, and observes the local node's
capability registry. Daemon modules have no Mongo storage; their
`ModuleContext.findMongoStorage()` returns empty.

## What you'll learn

- The four module-lifecycle hooks plus four instance-lifecycle hooks.
- How to mutate a launch spec from `onInstanceStarting`.
- How daemon modules pair with platform modules in a dual-host module.

## API surface

The interface lives at
`me.prexorjustin.prexorcloud.api.module.platform.DaemonModule`.

### Module lifecycle

```java
default void onLoad(ModuleContext context)    throws Exception
default void onStart(ModuleContext context)   throws Exception
default void onStop(ModuleContext context)    throws Exception
default void onUnload(ModuleContext context)  throws Exception
default void onUpgrade(ModuleContext context) throws Exception
```

Identical semantics to `PlatformModule`, but executed inside the daemon
JVM. The daemon receives the module artifact over its gRPC stream
(`ModuleInstall`); the host classloads it and drives these hooks.

### Instance lifecycle

#### `onInstanceStarting`

```java
default void onInstanceStarting(InstanceSpec spec) throws Exception
```

Pre-launch hook for an instance about to start on this node. You may
mutate `spec.jvmArgs()` or `spec.env()` to inject flags or environment
variables; mutations are observed by the daemon when it builds the
launch command. **Throwing aborts the start with an error report**.

#### `onInstanceStarted`

```java
default void onInstanceStarted(InstanceHandle handle) throws Exception
```

Fired after the instance process is spawned and the daemon has a PID.

#### `onInstanceStopping`

```java
default void onInstanceStopping(InstanceHandle handle) throws Exception
```

Fired before the daemon stops the instance process (graceful or forced).

#### `onInstanceStopped`

```java
default void onInstanceStopped(InstanceHandle handle, ExitInfo exit) throws Exception
```

Fired after the instance process has exited (clean or crashed).
`ExitInfo` carries the exit code, classification, and whether this was
a crash vs. a managed stop.

### `capabilityHandles`

```java
default List<CapabilityHandle<?>> capabilityHandles()
```

Same contract as `PlatformModule`, but the binding is **node-local** —
cross-node visibility is out of scope for v1.

## Misbehaviour contract

Each instance-lifecycle hook is wrapped by the daemon with a try-catch
+ SLF4J `WARN`. A misbehaving module cannot wedge the daemon: throwing
from `onInstanceStarted` / `onInstanceStopping` / `onInstanceStopped`
is logged and ignored. `onInstanceStarting` is the one exception —
since it's a pre-launch gate, throwing there aborts the launch.

## Example

A daemon module that injects a `-javaagent:` flag into every starting
instance:

```java
package com.example.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.prexorjustin.prexorcloud.api.module.platform.DaemonModule;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;

public final class AgentInjectorModule implements DaemonModule {

    private static final Logger LOG = LoggerFactory.getLogger(AgentInjectorModule.class);

    private String agentPath;

    @Override
    public void onLoad(ModuleContext context) {
        this.agentPath = context.findRedisStorage()
                .flatMap(r -> r.get("agent.path"))
                .orElse("/opt/prexor/agents/observer.jar");
    }

    @Override
    public void onInstanceStarting(InstanceSpec spec) {
        spec.jvmArgs().add("-javaagent:" + agentPath);
        LOG.info("attached agent to {}", spec.instanceId());
    }
}
```

## Dual-host modules

A module that needs both controller- and daemon-side behaviour
declares `hosts: [controller, daemon]` in its manifest and ships:

- a `PlatformModule` as `backend.controller.entrypoint`
- a `DaemonModule` as `backend.daemon.entrypoint`

The two halves do not share heap state; they communicate through the
controller-bus events forwarded to the daemon (subscribe-registration
model — see Layer 7 in the engineering docs).

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — sibling
  contract.
- [ModuleContext](/reference/module-sdk/module-context/) — what's
  available inside a daemon-module hook.
- [Concepts → Daemon modules](/concepts/modules/daemon/)
