---
title: Storage API
description: ModuleDataStore (Mongo) and PlatformRedisStorage (Redis) — namespaced persistence and key-value storage handed to platform modules.
---

Modules get two persistence handles, both namespaced to the module's
id so that collection and key names cannot collide across modules:

- **`ModuleDataStore`** — Mongo-backed document store, accessed through
  `ModuleContext.requireMongoStorage()`.
- **`PlatformRedisStorage`** — Redis/Valkey key/value store, accessed
  through `ModuleContext.requireRedisStorage()`.

Daemon-host contexts return empty for both — daemons have no Mongo
binding in v1.

## What you'll learn

- `ModuleDataStore` CRUD, indexing, and transactions.
- `PlatformRedisStorage` get/set/increment/TTL.
- The collection-prefix and key-prefix contracts.

## ModuleDataStore — Mongo

The interface lives at
`me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore`.

### Namespacing

```java
String collectionPrefix();          // e.g. "mod_stats_aggregator_"
void   ensureCollection(String name);
void   createIndex(String collection, IndexSpec index);
```

Every collection name you pass through `find`, `insert*`, etc. is
qualified by the prefix. Calling `insertOne("sessions", ...)` writes to
`mod_<id>_sessions` under the hood. Modules cannot read other modules'
collections through this handle.

### Insert

```java
<T> String  insertOne(String collection, T document);
<T> int     insertMany(String collection, List<T> documents);
```

Documents are serialised through Jackson — your records / DTOs round-trip
via the same `ObjectMapper` exposed on `ModuleContext.json()`.
`insertOne` returns the generated id as string.

### Read

```java
<T> Optional<T> findOne(String collection, Query filter, Class<T> type);
<T> List<T>     find(String collection, Query filter, Sort sort, int limit, Class<T> type);
<T> List<T>     find(String collection, Query filter, Sort sort, int limit, int skip, Class<T> type);
long            count(String collection, Query filter);
```

`Query` is a fluent builder — see below.

### Update

```java
int     updateOne(String collection, Query filter, Update update);
int     updateMany(String collection, Query filter, Update update);
boolean upsertOne(String collection, Query filter, Update update);   // true = inserted
```

### Delete

```java
boolean deleteOne(String collection, Query filter);     // true = a doc was deleted
int     deleteMany(String collection, Query filter);
```

### Transaction

```java
@FunctionalInterface
interface TransactionWork {
    void execute(ModuleDataStore txStore) throws Exception;
}

void withTransaction(TransactionWork work);
```

Multi-document ACID transaction. The `txStore` argument is a
short-lived store bound to the transaction; do not capture it past
the lambda.

### Query builder

```java
Query.where("status").eq("QUEUED").and("to_uuid").in(uuids)

Query.or(
    Query.where("from_uuid").eq(a).and("to_uuid").eq(b),
    Query.where("from_uuid").eq(b).and("to_uuid").eq(a))
```

Driver-agnostic; the implementation translates conditions into
Mongo filter documents under the hood.

## PlatformRedisStorage — Redis / Valkey

The interface lives at
`me.prexorjustin.prexorcloud.api.module.platform.PlatformRedisStorage`.

```java
String           keyPrefix();                                  // e.g. "mod:stats:"
default String   qualify(String key);                          // keyPrefix + key
Optional<String> get(String key);
void             set(String key, String value);
void             set(String key, String value, Duration ttl);
long             increment(String key);
long             decrement(String key);
boolean          delete(String key);
```

Same namespacing pattern as Mongo — every key you read or write is
prefixed. Use Redis for short-lived counters, rate limits, distributed
locks, and per-instance caches; use Mongo for durable state.

## Example

```java
public final class StatsRepository {

    private final ModuleDataStore mongo;

    public StatsRepository(ModuleDataStore mongo) {
        this.mongo = mongo;
        mongo.ensureCollection("sessions");
        mongo.ensureCollection("player_stats");
        mongo.createIndex("sessions",
                IndexSpec.of("player_id", IndexSpec.Order.ASC));
    }

    public void recordSession(SessionRecord record) {
        mongo.insertOne("sessions", record);
    }

    public Optional<PlayerStat> playerStat(UUID playerId) {
        return mongo.findOne("player_stats",
                Query.where("playerId").eq(playerId.toString()),
                PlayerStat.class);
    }

    public List<SessionRecord> recentSessionsForPlayer(UUID playerId, int limit) {
        return mongo.find("sessions",
                Query.where("player_id").eq(playerId.toString()),
                Sort.desc("quit_at"),
                limit,
                SessionRecord.class);
    }
}
```

The repository takes the store as a constructor argument — no
field injection.

## Manifest declaration

```yaml
storage:
  mongo: true
  redis: true
  limits:
    mongoDocuments: 500000
    redisKeys: 100000
```

The controller refuses to load a module that calls
`requireMongoStorage()` without `mongo: true` in its manifest.

## Next up

- [ModuleContext](/reference/module-sdk/module-context/) — how the
  storage handles are obtained.
- [module.yaml](/reference/module-sdk/module-yaml/) — `storage:` schema.
- [Concepts → Module storage](/reference/module-sdk/storage-api/)
