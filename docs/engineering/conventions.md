# PrexorCloud conventions

Patterns, conventions, and rules that contributors must understand to work correctly in this codebase.

---

## Naming conventions

### Java

- **Packages:** `me.prexorjustin.prexorcloud.{module}.{area}` — e.g., `controller.scheduler`, `daemon.process`, `security.ca`
- **Classes:** PascalCase. Suffixes indicate role:
  - `*Manager` — lifecycle management and coordination (e.g., `GroupManager`, `ProcessManager`)
  - `*Store` — persistence interface or implementation (e.g., `StateStore`, `MongoStateStore`)
  - `*Config` — configuration record (e.g., `ControllerConfig`, `DaemonConfig`)
  - `*Impl` — gRPC service implementation (e.g., `DaemonServiceImpl`)
  - `*Routes` — REST route group (e.g., `GroupRoutes`, `NodeRoutes`)
  - `*Middleware` — HTTP middleware (e.g., `JwtAuthMiddleware`)
  - `*Service` — domain service (e.g., `WebhookAlertService`, `PreWarmService`)
  - `*Adapter` — bridge between internal and API types (e.g., `ClusterStateAdapter`)
- **Records:** Used extensively for DTOs, configs, and value objects. Named as nouns without suffix.
- **Interfaces:** No `I` prefix. Named as capabilities (e.g., `StateStore`, `NodeSelector`, `CloudModuleBase`).
- **Constants:** `SCREAMING_SNAKE_CASE` for static finals.
- **Instance IDs:** `{groupName}-{number}` for static groups, `{groupName}-{gapFilledNumber}` for dynamic.
- **Plugin tokens:** `ptk_` prefix + UUID without hyphens.

### Go

- **Packages:** Lowercase, single-word (e.g., `api`, `config`, `output`, `setup`)
- **Exports:** PascalCase for public, camelCase for private
- **Files:** Lowercase with underscores avoided (e.g., `client.go`, `format.go`)

### Vue/TypeScript

- **Components:** PascalCase files matching component name (e.g., `FilterToolbar.vue`, `NodeCard.vue`)
- **Composables:** `use` prefix (e.g., `useApiClient.ts`, `useSseEventBus.ts`)
- **Stores:** Named by domain (e.g., `auth.ts`, `groups.ts`, `instances.ts`)
- **Pages:** File-based routing with `[param]` for dynamic segments and `[...slug]` for catch-all

---

## Architectural conventions

### Dependency injection

**Rule:** Constructor injection only. No annotation-based DI frameworks (no Spring, no Guice, no Dagger).

The `PrexorCloudBootstrap` class is the sole composition root. All dependencies are wired explicitly:

```java
// ✓ Correct
var eventBus = new EventBus();
var clusterState = new ClusterState(eventBus, redisRuntimeStore);
var sessionManager = new NodeSessionManager();

// ✗ Wrong — no @Inject, @Autowired, or @Component
```

**Rationale:** Explicit wiring makes the dependency graph visible and startup order deterministic. The tradeoff is a verbose bootstrap, but every dependency is traceable.

### Service grouping (nested records)

`PrexorController` groups related services into nested records to manage high collaborator counts:

```java
record CoreServices(EventBus eventBus, ClusterState clusterState, ...) {}
record SecurityServices(CertificateAuthority ca, JwtManager jwt, ...) {}
```

This pattern avoids constructors with 20+ parameters while keeping dependencies explicit.

### Circular dependency resolution

Some dependencies are circular (e.g., `Scheduler` needs `PrexorController`, which is constructed before `Scheduler`). These are resolved via deferred setters:

```java
controller.setScheduler(scheduler);  // Set after both are constructed
```

---

## Error handling patterns

### REST API errors

Errors use standard HTTP status codes with consistent JSON response format:

```json
{
  "code": "NOT_FOUND",
  "message": "Instance 'lobby-1' not found",
  "status": 404
}
```

Exception types mapped to status codes:
- `NotFoundException` → 404
- `IllegalArgumentException` → 422 (validation errors)
- `IllegalStateException` → 409 (conflict)
- `UnauthorizedResponse` → 401
- `ForbiddenResponse` → 403
- Uncaught `Exception` → 500

Helper method pattern:

```java
requireFound(controller.clusterState().getInstance(id), "Instance", id);
// Throws NotFoundException if Optional is empty
```

### gRPC errors

gRPC handlers log errors but do not propagate exceptions to the stream. Failed operations are acknowledged via ACK messages with error details:

```java
sendStartAck(instanceId, false, "Insufficient memory on node");
```

### Daemon process errors

Process crashes are classified, not thrown:
- Exit code analysis → `CrashClassifier.classify()`
- Console log tail preserved in `CrashReport`
- Reported to controller via gRPC
- Controller stores, logs, and detects loops

---

## Configuration patterns

### YAML configuration

All configuration uses YAML (Jackson dataformat-yaml):

```yaml
http:
  host: "0.0.0.0"
  port: 8080
grpc:
  host: "0.0.0.0"
  port: 9090
database:
  uri: "mongodb://localhost:27017"
```

### Record-based config with defaults

Configuration classes are Java records with compact constructors that apply defaults:

```java
record HttpConfig(String host, int port) {
    HttpConfig {
        if (host == null) host = "127.0.0.1";
        if (port == 0) port = 8080;
    }
}
```

This pattern ensures configs are always valid and immutable.

### Environment variable overrides

Docker Compose uses `PREXORCLOUD_*` environment variables. The daemon supports `PREXORCLOUD_CONTROLLER_HOST` and `PREXORCLOUD_CONTROLLER_GRPC_PORT` for service discovery.

### CLI configuration hierarchy

Priority (highest to lowest):
1. Command-line flags (`--controller`, `--token`)
2. Environment variables (`PREXOR_CONTROLLER`, `PREXOR_TOKEN`)
3. Config file (`~/.prexorcloud/config.yml`)

---

## Serialization conventions

### Jackson (JSON/YAML/TOML)

- **Jackson only** — No Gson, no manual JSON construction
- `JavaTimeModule` registered for `java.time` types
- `WRITE_DATES_AS_TIMESTAMPS` disabled (ISO-8601 strings)
- `jackson-module-parameter-names` for record deserialization without `@JsonProperty`
- Shared `ObjectMapper` instances (not per-request)

### Protobuf

- All gRPC messages defined in `.proto` files under `cloud-protocol/src/main/proto/`
- Java code generated by protobuf-gradle-plugin
- `oneof` for polymorphic message payloads (e.g., `DaemonMessage.payload`)
- Enums with `UNSPECIFIED` as default value

---

## Persistence conventions

### MongoDB (primary store)

- Direct driver usage — no ORM, no Morphia, no Spring Data
- `Document`-based CRUD with manual mapping
- Indexes created in `initialize()`
- Transactions via `runInTransaction(Runnable)` wrapper

### MongoDB (module data)

- Fluent API via `ModuleDataStore` interface
- Collection prefix isolation per module (`mod_{name}_`)
- `insertOne()` / `find()` / `updateOne()` with `Query`, `Update`, `Sort`, `IndexSpec` builders
- `withTransaction()` for transactional operations

### No ORM rule

**Rule:** No ORMs. Use MongoDB driver for all data stores.

```java
// ✓ Correct
dataStore.insertOne("messages", new Document("sender", sender).append("text", text));

// ✗ Wrong
entityManager.persist(new MessageEntity(sender, text));
```

---

## Logging conventions

### SLF4J only

**Rule:** Use `SLF4J` for all logging. No `System.out.println`.

```java
// ✓ Correct
private static final Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.info("Instance {} started on node {}", instanceId, nodeId);

// ✗ Wrong
System.out.println("Instance started");
```

### Structured logging

`JsonLogEncoder` provides structured JSON logging for production. Configured via `LoggingSetup` using Logback programmatically.

### Log levels

- `ERROR` — Unexpected failures requiring attention
- `WARN` — Recoverable issues (crash reports, reconnection, stale data)
- `INFO` — Lifecycle events (node connected, instance started, module loaded)
- `DEBUG` — Detailed operational data (gRPC messages, template hashing)

### MDC context

`RequestIdMiddleware` adds a correlation ID to MDC for every REST request, enabling request tracing in logs.

---

## Testing conventions

### Mock only external boundaries

**Rule:** Mock only external boundaries (database, network, filesystem). Never mock internal services.

The `cloud-test-harness` module tests the full stack:
- `TestCluster` starts real controller + daemon
- `FakeMinecraftServer` / `FakeProxy` simulate server instances
- `RestClient` makes real HTTP requests
- `SseListener` captures real SSE events

### Integration test structure

Tests use JUnit 5 with `@TestMethodOrder(OrderAnnotation.class)` for ordered execution. `TestReportExtension` generates HTML test reports.

### Dashboard tests

Vitest with `happy-dom` environment. Tests cover:
- Components (ConfirmDialog, FilterToolbar, ModuleErrorBoundary)
- Composables (useFilteredList)
- Stores (auth, groups, nodes, overview, crashes)

### CLI tests

Go standard `testing` package with `go test ./...`. Tests cover:
- API client (HTTP responses, error handling)
- Config (YAML persistence)
- Output formatting
- Upload multipart handling

---

## Concurrency patterns

### Virtual threads

Used extensively for I/O-bound operations:
- REST handlers (Javalin `useVirtualThreads()`)
- Event dispatch (virtual thread per-task executor)
- Instance startup (process launch, console capture)
- Template requests (gRPC handling)

### StructuredTaskScope (JEP 505)

Used for parallel operations with lifecycle guarantees:
- Scheduler tier evaluation
- Process preparation (template + JAR + bootstrap)
- Server process (console capture + exit monitor)
- Event handler invocation

### ConcurrentHashMap + CopyOnWriteArrayList

Standard concurrent collections for thread-safe state:
- `ClusterState` data structures: `ConcurrentHashMap`
- `EventBus` handler lists: `CopyOnWriteArrayList`
- `NodeSessionManager` session maps: `ConcurrentHashMap`

### Volatile map references (lock-free reads)

`CloudStateCache` uses volatile map references swapped atomically:

```java
private volatile Map<String, InstanceView> instances = Map.of();
// Reads are lock-free; writes create new immutable map and swap reference
instances = Map.copyOf(newCache);
```

---

## REST API conventions

### URL patterns

- `/api/v1/{resource}` — Collection endpoints
- `/api/v1/{resource}/{id}` — Individual resource endpoints
- `/api/v1/{resource}/{id}/{action}` — Action endpoints (POST)
- `/api/proxy/` — Plugin proxy endpoints (plugin token auth)
- `/api/plugin/` — Plugin-specific endpoints (plugin token auth)

### HTTP methods

- `GET` — Read operations
- `POST` — Create or action operations
- `PATCH` — Partial updates (only sent fields are applied)
- `DELETE` — Resource deletion

### Partial updates

PATCH endpoints parse raw JSON to detect which fields were explicitly sent, then merge only those fields with existing values. Omitted fields retain their current values.

### Audit logging

State-changing operations record audit entries via `RestServer.audit()`:

```java
audit(ctx, controller.stateStore(), "UPDATE", "GROUP", name, Map.of("changes", sentFields));
```

### Permission constants

Defined as string constants in `Permission` class:

```java
GROUPS_VIEW, GROUPS_CREATE, GROUPS_UPDATE, GROUPS_DELETE, GROUPS_START
INSTANCES_VIEW, INSTANCES_STOP, INSTANCES_COMMAND, INSTANCES_CONSOLE
NODES_VIEW, NODES_DRAIN
// ... 28+ permissions
```

---

## Build conventions

### Multi-target Java versions

The project spans three Java targets:
- **Java 25 (preview)** — Controller, daemon, common, protocol, security (preview language features)
- **Java 21** — API module (`prexorcloud.java21-api`; stable, no preview — modules and plugins compile against it without preview flags)
- **Java 21** — In-server and proxy plugins (`prexorcloud.java21-compat`; matches the Minecraft host runtime, Paper/Velocity on Java 21)

### Shadow JARs

`com.gradleup.shadow` plugin creates fat JARs for deployment:
- Controller and daemon produce standalone JARs
- Module builds include frontend assets and embedded plugin JARs

### Code formatting

Spotless plugin with Eclipse formatter (`spotless/eclipse-formatter.xml`). Enforced in CI via `./gradlew spotlessCheck`. Format with `./gradlew spotlessApply`.

### Version catalog

All dependency versions centralized in `java/gradle/libs.versions.toml`. Referenced in build files as `libs.{library-name}`.

---

## Frontend conventions

### Component organization

- `ui/` — shadcn-vue primitives (auto-imported)
- Feature directories (`groups/`, `nodes/`, etc.) — domain-specific components
- `layout/` — App-level layout components

### State management

Pinia stores use Composition API style:

```typescript
export const useGroupsStore = defineStore('groups', () => {
    const groups = ref<Group[]>([]);
    const loading = ref(false);
    async function fetchGroups() { ... }
    return { groups, loading, fetchGroups };
});
```

### Permission guards

UI elements check permissions via `useCan` composable:

```typescript
const can = useCan();
if (can('groups.create')) { ... }
```

### Real-time updates

SSE events are consumed by stores that subscribe to relevant event types. Stores update reactive state, which automatically propagates to components.
