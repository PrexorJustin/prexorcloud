package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Update;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.StorageQuotaExceededException;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformModuleStorageManager")
class PlatformModuleStorageManagerTest {

    private static final String CHAT_PREFIX = RedisKeys.platformModulePrefix("chat");
    private static final String MATCH_MAKING_PREFIX = RedisKeys.platformModulePrefix("match_making");

    private static RuntimeServices runtimeFor(RedisCommands<String, String> redis) {
        if (redis == null) {
            return new InMemoryRuntimeServices();
        }
        RuntimeServices runtime = mock(RuntimeServices.class);
        when(runtime.coordinationEnabled()).thenReturn(true);
        when(runtime.redisCommands()).thenReturn(redis);
        return runtime;
    }

    @Test
    @DisplayName("describes stable Mongo and Redis prefixes for one module id")
    void describesStablePrefixes() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());

        PlatformModuleStorageManager.StorageAllocation allocation =
                storageManager.describe("Match-Making", new ModuleStorageRequest(true, true));

        assertEquals("platform_match_making_", allocation.mongoCollectionPrefix());
        assertEquals(MATCH_MAKING_PREFIX, allocation.redisKeyPrefix());
        assertTrue(allocation.mongoAssigned());
        assertTrue(allocation.redisAssigned());
    }

    @Test
    @DisplayName("rejects requested Redis storage when Redis is unavailable")
    void rejectsUnavailableRedisStorage() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(null), new ObjectMapper());

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> storageManager.resolve(manifest("chat", new ModuleStorageRequest(false, true))));

        assertTrue(thrown.getMessage().contains("Redis"));
    }

    @Test
    @DisplayName("resolved Redis storage qualifies all keys with the module prefix")
    void resolvedRedisStorageQualifiesKeys() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        when(redisCommands.get(CHAT_PREFIX + "counter")).thenReturn("7");
        when(redisCommands.incr(CHAT_PREFIX + "counter")).thenReturn(8L);

        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());
        var storage = storageManager.resolve(manifest("chat", new ModuleStorageRequest(false, true)));

        storage.requireRedis().set("counter", "7");
        assertEquals("7", storage.requireRedis().get("counter").orElseThrow());
        assertEquals(8L, storage.requireRedis().increment("counter"));

        verify(redisCommands).set(CHAT_PREFIX + "counter", "7");
        verify(redisCommands).get(CHAT_PREFIX + "counter");
        verify(redisCommands).incr(CHAT_PREFIX + "counter");
    }

    @Test
    @DisplayName("Mongo storage enforces manifest document soft limit before inserts")
    void mongoStorageEnforcesDocumentSoftLimit() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        mockCollectionNames(mongoDatabase, "platform_chat_messages", "platform_chat_stats", "other");
        @SuppressWarnings("unchecked")
        MongoCollection<Document> messages = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> stats = mock(MongoCollection.class);
        when(messages.countDocuments()).thenReturn(2L);
        when(stats.countDocuments()).thenReturn(1L);
        when(mongoDatabase.getCollection("platform_chat_messages")).thenReturn(messages);
        when(mongoDatabase.getCollection("platform_chat_stats")).thenReturn(stats);

        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(true, false, new ModuleStorageRequest.StorageLimits(3, 0))));

        StorageQuotaExceededException thrown = assertThrows(
                StorageQuotaExceededException.class,
                () -> storage.requireMongo().insertOne("messages", new TestDocument("hello")));

        assertTrue(thrown.getMessage().contains("Mongo document soft limit"));
        verify(messages, never()).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("Mongo upsert checks quota only when it would create a document")
    void mongoUpsertChecksQuotaOnlyForCreates() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        mockCollectionNames(mongoDatabase, "platform_chat_messages");
        @SuppressWarnings("unchecked")
        MongoCollection<Document> messages = mock(MongoCollection.class);
        when(messages.countDocuments()).thenReturn(3L);
        when(mongoDatabase.getCollection("platform_chat_messages")).thenReturn(messages);

        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(true, false, new ModuleStorageRequest.StorageLimits(3, 0))));

        StorageQuotaExceededException thrown = assertThrows(
                StorageQuotaExceededException.class,
                () -> storage.requireMongo().upsertOne("messages", Query.all(), Update.set("body", "hello")));

        assertTrue(thrown.getMessage().contains("Mongo document soft limit"));
    }

    @Test
    @DisplayName("Redis storage enforces manifest key soft limit for new keys")
    void redisStorageEnforcesKeySoftLimit() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        when(redisCommands.exists(CHAT_PREFIX + "new-key")).thenReturn(0L);
        io.lettuce.core.KeyScanCursor<String> redisCursor = new io.lettuce.core.KeyScanCursor<>();
        redisCursor.setCursor("0");
        redisCursor.setFinished(true);
        redisCursor.getKeys().addAll(List.of(CHAT_PREFIX + "one", CHAT_PREFIX + "two"));
        when(redisCommands.scan(any(io.lettuce.core.ScanArgs.class))).thenReturn(redisCursor);

        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(false, true, new ModuleStorageRequest.StorageLimits(0, 2))));

        StorageQuotaExceededException thrown = assertThrows(
                StorageQuotaExceededException.class,
                () -> storage.requireRedis().set("new-key", "value"));

        assertTrue(thrown.getMessage().contains("key soft limit"));
        verify(redisCommands, never()).set(CHAT_PREFIX + "new-key", "value");
    }

    @Test
    @DisplayName("Redis storage allows updates to existing keys at the soft limit")
    void redisStorageAllowsExistingKeyUpdateAtSoftLimit() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        when(redisCommands.exists(CHAT_PREFIX + "existing")).thenReturn(1L);

        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(false, true, new ModuleStorageRequest.StorageLimits(0, 1))));

        storage.requireRedis().set("existing", "value");

        verify(redisCommands).set(CHAT_PREFIX + "existing", "value");
        verify(redisCommands, never()).scan(any(io.lettuce.core.ScanArgs.class));
    }

    @Test
    @DisplayName("drop removes only prefixed Mongo collections and Redis keys")
    void dropRemovesOnlyScopedStorage() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        mockCollectionNames(mongoDatabase, "platform_chat_users", "platform_chat_stats", "other");
        when(mongoDatabase.getCollection(any(String.class))).thenAnswer(invocation -> mock(MongoCollection.class));
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        io.lettuce.core.KeyScanCursor<String> redisCursor = new io.lettuce.core.KeyScanCursor<>();
        redisCursor.setCursor("0");
        redisCursor.setFinished(true);
        redisCursor.getKeys().addAll(List.of(CHAT_PREFIX + "one", CHAT_PREFIX + "two"));
        when(redisCommands.scan(any(io.lettuce.core.ScanArgs.class))).thenReturn(redisCursor);
        when(redisCommands.del(any(String[].class))).thenReturn(2L);

        PlatformModuleStorageManager storageManager = new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), runtimeFor(redisCommands), new ObjectMapper());

        PlatformModuleStorageManager.StorageDropResult dropped = storageManager.drop("chat");

        assertEquals(2, dropped.mongoCollectionsDropped());
        assertEquals(2, dropped.redisKeysDropped());
        verify(mongoDatabase).getCollection("platform_chat_users");
        verify(mongoDatabase).getCollection("platform_chat_stats");
        verify(redisCommands).del(CHAT_PREFIX + "one", CHAT_PREFIX + "two");
    }

    private static void mockCollectionNames(MongoDatabase mongoDatabase, String... names) {
        @SuppressWarnings("unchecked")
        ListCollectionNamesIterable listCollectionNames = mock(ListCollectionNamesIterable.class);
        @SuppressWarnings("unchecked")
        MongoCursor<String> mongoCursor = mock(MongoCursor.class);
        when(mongoDatabase.listCollectionNames()).thenReturn(listCollectionNames);
        when(listCollectionNames.iterator()).thenReturn(mongoCursor);
        var iterator = List.of(names).iterator();
        when(mongoCursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(mongoCursor.next()).thenAnswer(invocation -> iterator.next());
    }

    private record TestDocument(String body) {}

    private static PlatformModuleManifest manifest(String id, ModuleStorageRequest storageRequest) {
        return new PlatformModuleManifest(
                1,
                id,
                "1.0.0",
                new PlatformModuleManifest.Backend("example." + id + ".Main"),
                null,
                null,
                storageRequest,
                List.of());
    }
}
