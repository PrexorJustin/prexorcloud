---
title: Storage API
description: ModuleDataStore (Mongo) and PlatformRedisStorage (Redis) — namespaced document and key/value persistence handed to platform modules.
---

A platform module persists state through two controller-owned handles, both scoped to the module's id so collection and key names cannot collide across modules:

- `ModuleDataStore` — Mongo-backed document store, obtained from `ModuleContext.requireMongoStorage()`.
- `PlatformRedisStorage` — Redis/Valkey key/value store, obtained from `ModuleContext.requireRedisStorage()`.

Both handles are allocated by `PlatformModuleStorageManager` on the controller when the module declares `storage:` in its `module.yaml`. A module that calls `requireMongoStorage()` without `mongo: true`, or `requireRedisStorage()` without `redis: true`, gets an empty handle and the require-form throws `IllegalStateException`. Daemon-hosted contexts have no Mongo or Redis binding and return empty for both.

All types in this page live under `me.prexorjustin.prexorcloud.api.module.data` (the Mongo side) and `me.prexorjustin.prexorcloud.api.module.platform` (the Redis side and the storage request record).

## Obtaining the handles

`ModuleContext` exposes four accessors:

```java
Optional<ModuleDataStore>     findMongoStorage();
ModuleDataStore               requireMongoStorage();   // throws if mongo not granted
Optional<PlatformRedisStorage> findRedisStorage();
PlatformRedisStorage          requireRedisStorage();   // throws if redis not granted
```

`requireMongoStorage()` throws `IllegalStateException: mongo storage is not available for module '<id>'` when the module did not request Mongo (or Mongo is not configured on the controller). Use the `find*` form when storage is optional; use the `require*` form in `onEnable` for a module that cannot run without it.

```java
@Override
public void onEnable(ModuleContext ctx) {
    ModuleDataStore mongo = ctx.requireMongoStorage();
    this.repository = new PlaytimeRepository(mongo);

    ctx.findRedisStorage().ifPresent(redis ->
            this.rateLimiter = new RateLimiter(redis));
}
```

## ModuleDataStore — Mongo

`me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore`. A document-oriented store scoped to one module's collection namespace. Documents round-trip through Jackson — you pass and receive your own Java records, the implementation converts.

### Namespacing

```java
String collectionPrefix();
void   ensureCollection(String name);
void   createIndex(String collection, IndexSpec index);
```

`collectionPrefix()` returns the per-module prefix the controller allocated — `platform_<sanitized-id>_`, where the id is lowercased and every character outside `[a-z0-9_]` becomes `_`. For module id `example-playtime` the prefix is `platform_example_playtime_`.

Every `collection` argument to `find`, `insertOne`, `updateOne`, and so on is qualified by this prefix. Calling `insertOne("sessions", …)` writes to `platform_example_playtime_sessions`. A module cannot name a collection outside its prefix and so cannot read another module's data through this handle.

`ensureCollection(name)` creates the collection if it does not exist and is a no-op otherwise (it swallows Mongo error code 48, "collection already exists"). Safe to call on every `onEnable`.

`createIndex(collection, index)` creates the index described by an `IndexSpec`. `createIndex` is idempotent at the driver level — re-creating an identical index does nothing.

```java
store.ensureCollection("sessions");
store.createIndex("sessions", IndexSpec.asc("playerId"));
store.createIndex("sessions", IndexSpec.asc("sessionId").asUnique());
```

### Insert

```java
<T> String insertOne(String collection, T document);
<T> int    insertMany(String collection, List<T> documents);
```

`insertOne` serialises the document via Jackson and returns the generated Mongo `_id` as a hex string. Any `id`/`_id` field on your record is dropped before insert so Mongo generates the `ObjectId`. `insertMany` returns the number of documents inserted; an empty list returns `0` without touching the database.

```java
String id = store.insertOne("sessions",
        new Session(playerId, sessionId, Instant.now(), null, 0, "survival-lobby"));
// id -> "665f1c3a9d4b2e0017a3c8f1"
```

When you read a document back, the `ObjectId` `_id` is exposed to Jackson as a string field named `id`. Add an `id` field to your record (or ignore it) to capture it on deserialisation.

### Read

```java
<T> Optional<T> findOne(String collection, Query filter, Class<T> type);
<T> List<T>     find(String collection, Query filter, Sort sort, int limit, Class<T> type);
<T> List<T>     find(String collection, Query filter, Sort sort, int limit, int skip, Class<T> type);
long            count(String collection, Query filter);
```

`findOne` returns the first matching document or `Optional.empty()`. `find` returns matches ordered by `sort`. `limit` and `skip` of `0` mean unlimited / no offset — pass `Integer.MAX_VALUE` to read an entire collection. The five-argument `find` is the two-argument-paginated form; the four-argument overload delegates to it with `skip = 0`.

```java
Optional<TopEntry> total = store.findOne("totals",
        Query.where("playerId").eq(playerId.toString()), TopEntry.class);

List<Session> recent = store.find("sessions",
        Query.where("playerId").eq(playerId.toString()),
        Sort.desc("joinAt"), 20, Session.class);

long open = store.count("sessions", Query.where("quitAt").exists(false));
```

### Update

```java
int     updateOne(String collection, Query filter, Update update);
int     updateMany(String collection, Query filter, Update update);
boolean upsertOne(String collection, Query filter, Update update);
```

`updateOne` and `updateMany` return the modified-document count (matched-but-unchanged documents do not count). `upsertOne` updates the first match or inserts a new document if none matches; it returns `true` when a new document was created, `false` when an existing one was updated. An `Update` with no operations throws `DataStoreException("Empty update — nothing to apply")`.

```java
int closed = store.updateOne("sessions",
        Query.where("sessionId").eq(sessionId.toString()),
        Update.set("quitAt", quitAt).andSet("durationMs", durationMs));

boolean created = store.upsertOne("totals",
        Query.where("playerId").eq(playerId.toString()),
        Update.inc("totalMs", durationMs)
              .andSetOnInsert("playerId", playerId.toString()));
```

### Delete

```java
boolean deleteOne(String collection, Query filter);
int     deleteMany(String collection, Query filter);
```

`deleteOne` returns `true` when a document was deleted. `deleteMany` returns the count deleted.

```java
int purged = store.deleteMany("sessions",
        Query.where("joinAt").lt(cutoff));
```

### Transaction

```java
@FunctionalInterface
interface TransactionWork {
    void execute(ModuleDataStore txStore) throws Exception;
}

void withTransaction(TransactionWork work);
```

`withTransaction` runs `work` inside a single multi-document ACID transaction. The `txStore` passed to the lambda is a short-lived store bound to the transaction's Mongo session — every operation you issue on it joins the transaction. Do not capture `txStore` past the lambda, and do not issue operations on the outer store inside the lambda (those run outside the transaction). A thrown exception aborts the transaction and is rethrown wrapped in `DataStoreException("Transaction failed", e)`.

```java
store.withTransaction(tx -> {
    tx.deleteMany("totals", Query.all());
    tx.insertMany("totals", rebuiltLeaderboard);
});
```

### Query builder

`me.prexorjustin.prexorcloud.api.module.data.Query` — a fluent, driver-agnostic filter. Start with `where(field)` or a composite (`and`/`or`/`all`), then terminate each field with an operator.

Entry points:

```java
static Query where(String field);    // start a field condition
static Query all();                  // empty filter — matches every document
static Query and(Query... queries);  // AND of sub-queries
static Query or(Query... queries);   // OR of sub-queries
```

Operators (each terminates the pending field set by `where`/`and`):

| Method | Mongo operator | Argument |
|---|---|---|
| `eq(Object)` | `$eq` | any value |
| `ne(Object)` | `$ne` | any value |
| `gt(Object)` | `$gt` | comparable value |
| `gte(Object)` | `$gte` | comparable value |
| `lt(Object)` | `$lt` | comparable value |
| `lte(Object)` | `$lte` | comparable value |
| `in(Collection<?>)` | `$in` | a collection (copied) |
| `exists(boolean)` | `$exists` | presence flag |
| `regex(String)` | `$regex` | pattern string |

Chaining: `and(String field)` adds another condition on the same query, ANDed together. A field with no operator (`Query.where("x").and("y")`) throws `IllegalStateException`, as does an operator with no pending field.

```java
Query.where("status").eq("QUEUED").and("to_uuid").in(uuids);

Query.or(
    Query.where("from_uuid").eq(a).and("to_uuid").eq(b),
    Query.where("from_uuid").eq(b).and("to_uuid").eq(a));
```

A condition on field `_id` whose value is a valid `ObjectId` hex string is coerced to an `ObjectId` automatically, so you can filter by the id returned from `insertOne`.

### Update builder

`me.prexorjustin.prexorcloud.api.module.data.Update` — a fluent update spec. Start with a static factory, chain with the `and*` methods.

Static entry points:

```java
static Update set(String field, Object value);          // $set
static Update setOnInsert(String field, Object value);  // $setOnInsert (upsert only)
static Update inc(String field, Number value);          // $inc
static Update unset(String field);                       // $unset
```

Instance chaining:

```java
Update andSet(String field, Object value);
Update andSetOnInsert(String field, Object value);
Update andInc(String field, Number value);
Update andUnset(String field);
```

`setOnInsert` fields apply only when an `upsertOne` inserts a new document — use them to seed immutable fields (creation timestamp, owning id) without overwriting them on later updates.

```java
Update.set("status", "DELIVERED").andSet("delivered_at", Instant.now());
Update.inc("counter", 1);
Update.setOnInsert("created_at", now).andInc("hits", 1);
```

### Index builder

`me.prexorjustin.prexorcloud.api.module.data.IndexSpec` — an immutable index spec (a record of `keys`, `unique`, `name`).

```java
static IndexSpec asc(String... fields);              // single or compound, ascending
static IndexSpec desc(String... fields);             // single or compound, descending
static IndexSpec compound(Map<String,Integer> keys); // explicit field -> direction (1 / -1)

IndexSpec asUnique();          // copy marked unique
IndexSpec withName(String n);  // copy with an explicit index name
```

`asUnique()` and `withName()` return copies — chain them off a factory.

```java
store.createIndex("sessions", IndexSpec.asc("playerId"));
store.createIndex("sessions", IndexSpec.asc("sessionId").asUnique());
store.createIndex("totals",  IndexSpec.desc("totalMs"));
store.createIndex("messages",
        IndexSpec.compound(Map.of("to_uuid", 1, "status", 1)).withName("inbox_idx"));
```

### Sort builder

`me.prexorjustin.prexorcloud.api.module.data.Sort` — an immutable sort spec.

```java
static Sort asc(String field);
static Sort desc(String field);
static Sort none();              // no ordering

Sort thenAsc(String field);      // append a secondary key
Sort thenDesc(String field);
```

`thenAsc`/`thenDesc` return new instances, so chain them.

```java
Sort.desc("joinAt");
Sort.desc("totalMs").thenAsc("playerId");
Sort.none();   // pass with limit = Integer.MAX_VALUE to stream a whole collection unordered
```

### Errors

- `DataStoreException` — a `RuntimeException` raised for store-level failures: collection creation failures, an empty `Update`, an unsupported operator, or a transaction body that threw.
- `IllegalStateException` — raised by the `Query` builder for a field without an operator, or an operator without a field; and by `requireMongoStorage()` when Mongo was not granted.

## PlatformRedisStorage — Redis / Valkey

`me.prexorjustin.prexorcloud.api.module.platform.PlatformRedisStorage`. A key/value store scoped to one module. Backed by Lettuce; available only when controller coordination (Redis/Valkey) is enabled.

```java
String           keyPrefix();
default String   qualify(String key);     // keyPrefix() + key
Optional<String> get(String key);
void             set(String key, String value);
void             set(String key, String value, Duration ttl);
long             increment(String key);
long             decrement(String key);
boolean          delete(String key);
```

`keyPrefix()` returns the per-module prefix the controller allocated: `prexor:v1:platform:<sanitized-id>:`. Same sanitisation as Mongo (lowercase, non-`[a-z0-9_]` → `_`). `qualify(key)` prepends the prefix; it rejects a `null` or blank key with `IllegalArgumentException`. Every read and write below qualifies the key, so a module's keys never collide with another's.

| Method | Behaviour | Returns |
|---|---|---|
| `get(key)` | `GET prefix+key` | the value, or `Optional.empty()` if absent |
| `set(key, value)` | `SET prefix+key value` | — |
| `set(key, value, ttl)` | `SETEX prefix+key ttl.toSeconds() value` | — |
| `increment(key)` | `INCR prefix+key` | the value after increment |
| `decrement(key)` | `DECR prefix+key` | the value after decrement |
| `delete(key)` | `DEL prefix+key` | `true` if a key was removed |

`set` with a `Duration` rejects a `null` ttl with `NullPointerException`; the ttl is applied at second granularity. `increment`/`decrement` create the key at `0` if absent (standard Redis `INCR`/`DECR`).

```java
PlatformRedisStorage redis = ctx.requireRedisStorage();

long hits = redis.increment("ratelimit:" + playerId);          // 1, 2, 3, …
redis.set("session:" + sessionId, token, Duration.ofMinutes(30));
Optional<String> tok = redis.get("session:" + sessionId);
boolean dropped = redis.delete("session:" + sessionId);
```

Use Redis for short-lived counters, rate limits, distributed locks, and per-instance caches; use Mongo for durable state.

## Storage quotas

When the manifest declares `limits.mongoDocuments` or `limits.redisKeys`, the controller wraps the handle in a quota-enforcing decorator. These are soft limits checked before a write.

- Mongo: every `insertOne`/`insertMany`, and an `upsertOne` that will insert, counts the documents currently held under the module's collection prefix. Exceeding `mongoDocuments` throws `StorageQuotaExceededException` and the write does not happen. Reads, deletes, and updates that do not grow the document count are never blocked.
- Redis: every write to a key that does not already exist (`set`, `increment`, `decrement` on a new key) counts the keys under the module's prefix. Exceeding `redisKeys` throws `StorageQuotaExceededException`. Writes to an existing key, `get`, and `delete` are never blocked.

`StorageQuotaExceededException` is a `RuntimeException` (`me.prexorjustin.prexorcloud.api.module.platform`). A zero or absent limit means unlimited.

## Manifest declaration

The `storage:` block in `module.yaml` maps to `ModuleStorageRequest`:

```yaml
storage:
  mongo: true
  redis: true
  limits:
    mongoDocuments: 100000
    redisKeys: 100000
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `mongo` | bool | `false` | request a Mongo `ModuleDataStore` handle |
| `redis` | bool | `false` | request a `PlatformRedisStorage` handle |
| `limits.mongoDocuments` | long | `0` (unlimited) | soft cap on documents under the module's prefix |
| `limits.redisKeys` | long | `0` (unlimited) | soft cap on keys under the module's prefix |

Both limits must be `>= 0`. A `mongoDocuments` limit without `mongo: true`, or a `redisKeys` limit without `redis: true`, fails manifest validation (`IllegalArgumentException`). Requesting a backend the controller has not configured fails at load: `module '<id>' requested Mongo storage but Mongo is not configured`.

## Worked example

The reference `example-playtime` module keeps all persistence in one repository, takes the store as a constructor argument (no field injection), and bootstraps its schema once on enable. Collection names are written bare — the store qualifies them to `platform_example_playtime_sessions` / `…_totals`.

```java
public final class PlaytimeRepository {

    public static final String SESSIONS = "sessions";
    public static final String TOTALS = "totals";

    private final ModuleDataStore store;

    public PlaytimeRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(SESSIONS);
        store.ensureCollection(TOTALS);
        store.createIndex(SESSIONS, IndexSpec.asc("playerId"));
        store.createIndex(SESSIONS, IndexSpec.asc("sessionId").asUnique());
        store.createIndex(SESSIONS, IndexSpec.desc("joinAt"));
        store.createIndex(TOTALS,  IndexSpec.asc("playerId").asUnique());
        store.createIndex(TOTALS,  IndexSpec.desc("totalMs"));
    }

    public void openSession(Session session) {
        store.insertOne(SESSIONS, session);
    }

    public void closeSession(UUID sessionId, Instant quitAt, long durationMs) {
        store.updateOne(SESSIONS,
                Query.where("sessionId").eq(sessionId.toString()),
                Update.set("quitAt", quitAt).andSet("durationMs", durationMs));
    }

    public List<Session> recentSessions(UUID playerId, int limit) {
        return store.find(SESSIONS,
                Query.where("playerId").eq(playerId.toString()),
                Sort.desc("joinAt"), limit, Session.class);
    }

    public List<TopEntry> top(int limit) {
        return store.find(TOTALS, Query.all(), Sort.desc("totalMs"), limit, TopEntry.class);
    }

    // Rebuild the totals cache atomically so a reader never sees a half-rebuilt leaderboard.
    public int rebuildTotals(List<TopEntry> rebuilt) {
        store.withTransaction(tx -> {
            tx.deleteMany(TOTALS, Query.all());
            tx.insertMany(TOTALS, rebuilt);
        });
        return rebuilt.size();
    }

    public int deleteOlderThan(Instant cutoff) {
        return store.deleteMany(SESSIONS, Query.where("joinAt").lt(cutoff));
    }
}
```

`Session` is a plain Jackson-serialisable record — no manual mapping:

```java
public record Session(
        UUID playerId, UUID sessionId, Instant joinAt,
        Instant quitAt, long durationMs, String serverName) {}
```

## See also

- [ModuleContext](/reference/module-sdk/module-context/) — how the storage handles are obtained, alongside events, scheduler, and HTTP client.
- [module.yaml](/reference/module-sdk/module-yaml/) — the full manifest schema including `storage:`.
- [PlatformModule](/reference/module-sdk/platform-module/) — the module lifecycle (`onEnable`/`onDisable`) where storage is wired.
