---
title: Module SDK
description: Java SDK reference for PrexorCloud platform and daemon modules — entrypoints, ModuleContext, EventBus, capabilities, storage, REST routes, and module.yaml.
---

The **Module SDK** is the Java API surface modules use to participate
in the cluster. A module is shipped as a single shaded jar plus a
`module.yaml` manifest; the controller (and optionally each daemon)
loads it inside its own JVM and hands the entrypoint a
[`ModuleContext`](/reference/module-sdk/module-context/) covering
storage, the event bus, capabilities, scheduling, HTTP, and JSON.

## What you'll learn

- The two entrypoint contracts: `PlatformModule` and `DaemonModule`.
- The shared `ModuleContext` surface.
- The orthogonal subsystems — events, capabilities, storage, REST.
- The on-disk `module.yaml` schema.

## SDK pages

| Page | Surface |
|---|---|
| [PlatformModule](/reference/module-sdk/platform-module/) | Controller-side lifecycle + REST + capability handles. |
| [DaemonModule](/reference/module-sdk/daemon-module/) | Daemon-side lifecycle + per-instance hooks. |
| [ModuleContext](/reference/module-sdk/module-context/) | Shared context: storage, events, scheduler, HTTP, JSON, capabilities. |
| [EventBus](/reference/module-sdk/event-bus/) | Subscribing to and publishing cluster events. |
| [Capability API](/reference/module-sdk/capability-api/) | The `provides` / `requires` graph and `CapabilityHandle`. |
| [Storage API](/reference/module-sdk/storage-api/) | Mongo `ModuleDataStore` + Redis `PlatformRedisStorage`. |
| [REST Routes](/reference/module-sdk/rest-routes/) | `onRegisterRoutes` and the per-module wildcard dispatcher. |
| [module.yaml](/reference/module-sdk/module-yaml/) | Manifest schema. |

## Hello-world platform module

Minimum viable module that owns one Mongo collection, registers one
GET route, and uses SLF4J for logging:

```java
package com.example.hello;

import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;

public final class HelloModule implements PlatformModule {

    private ModuleContext context;

    @Override
    public void onLoad(ModuleContext context) {
        this.context = context;
        context.requireMongoStorage().ensureCollection("greetings");
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar routes) {
        routes.get("/greetings", (req, res) -> {
            long count = context.requireMongoStorage()
                .count("greetings", null);
            res.json(Map.of("count", count));
        });
    }

    @Override
    public void onStart(ModuleContext context) {
        context.logger().info("hello module started");
    }
}
```

```yaml
# src/main/module/module.yaml
manifestVersion: 1
id: hello
version: 1.0.0
hosts: [controller]
backend:
  controller:
    entrypoint: com.example.hello.HelloModule
storage:
  mongo: true
```

The route lives at `/api/v1/modules/hello/greetings`; the collection is
namespaced as `mod_hello_greetings` under the hood.

## Conventions

- **Logging**: SLF4J only (`org.slf4j.Logger`). The context exposes a
  pre-namespaced logger via `context.logger()`.
- **JSON**: use `context.json()` (Jackson, configured for ISO-8601 +
  `NON_NULL`). No Gson, no manual serialisation.
- **Persistence**: Mongo via `ModuleDataStore` and Redis via
  `PlatformRedisStorage`. JDBC is acceptable for advanced cases but
  not exposed by the SDK; ORMs are out of scope.
- **DI**: constructor injection only — pass dependencies into your
  service classes from the entrypoint's `onLoad`.

## Reference module

[`cloud-module-stats-aggregator`](/concepts/modules/)
ships in the repo and exercises every surface listed here. Code
samples on the SDK pages are drawn from it.

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — start here
  if you're writing controller-side logic.
- [Concepts → Modules](/concepts/modules/) — the architectural
  picture behind these contracts.
