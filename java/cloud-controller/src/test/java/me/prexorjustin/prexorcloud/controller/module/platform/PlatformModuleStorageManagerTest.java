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
import me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformModuleStorageManager")
class PlatformModuleStorageManagerTest {

    private static PlatformModuleStorageManager managerFor(MongoDatabase mongoDatabase) {
        return new PlatformModuleStorageManager(
                mongoDatabase, mock(MongoClient.class), new InMemoryRuntimeServices(), new ObjectMapper());
    }

    @Test
    @DisplayName("describes a stable Mongo prefix")
    void describesStablePrefixes() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        PlatformModuleStorageManager storageManager = managerFor(mongoDatabase);

        PlatformModuleStorageManager.StorageAllocation allocation =
                storageManager.describe("Match-Making", new ModuleStorageRequest(true));

        assertEquals("platform_match_making_", allocation.mongoCollectionPrefix());
        assertTrue(allocation.mongoAssigned());
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

        PlatformModuleStorageManager storageManager = managerFor(mongoDatabase);
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(true, new ModuleStorageRequest.StorageLimits(3))));

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

        PlatformModuleStorageManager storageManager = managerFor(mongoDatabase);
        var storage = storageManager.resolve(
                manifest("chat", new ModuleStorageRequest(true, new ModuleStorageRequest.StorageLimits(3))));

        StorageQuotaExceededException thrown = assertThrows(
                StorageQuotaExceededException.class,
                () -> storage.requireMongo().upsertOne("messages", Query.all(), Update.set("body", "hello")));

        assertTrue(thrown.getMessage().contains("Mongo document soft limit"));
    }

    @Test
    @DisplayName("drop removes only prefixed Mongo collections")
    void dropRemovesOnlyScopedStorage() {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoDatabase.getName()).thenReturn("prexorcloud");
        mockCollectionNames(mongoDatabase, "platform_chat_users", "platform_chat_stats", "other");
        when(mongoDatabase.getCollection(any(String.class))).thenAnswer(invocation -> mock(MongoCollection.class));

        PlatformModuleStorageManager storageManager = managerFor(mongoDatabase);

        PlatformModuleStorageManager.StorageDropResult dropped = storageManager.drop("chat");

        assertEquals(2, dropped.mongoCollectionsDropped());
        verify(mongoDatabase).getCollection("platform_chat_users");
        verify(mongoDatabase).getCollection("platform_chat_stats");
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
