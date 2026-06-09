package me.prexorjustin.prexorcloud.controller.template;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TemplateManager")
@ExtendWith(MockitoExtension.class)
class TemplateManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    StateStore stateStore;

    TemplateManager manager;

    @BeforeEach
    void setUp() {
        lenient()
                .doAnswer(inv -> {
                    inv.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(stateStore)
                .runInTransaction(any());
        manager = new TemplateManager(tempDir, stateStore, new EventBus());
    }

    @Nested
    @DisplayName("Save and retrieval")
    class SaveAndRetrieval {

        @Test
        @DisplayName("save() creates the files directory and persists metadata")
        void saveCreatesDirAndPersists() throws IOException {
            manager.save(new TemplateConfig("lobby", "lobby template", "", "", 0));

            assertTrue(manager.exists("lobby"));
            assertTrue(Files.isDirectory(tempDir.resolve("lobby/files")));
            verify(stateStore).saveTemplate(argThat(t -> t.name().equals("lobby")));
        }

        @Test
        @DisplayName("get() returns saved template")
        void getReturnsSaved() throws IOException {
            manager.save(new TemplateConfig("hub", "hub server", "", "", 0));

            var result = manager.get("hub");
            assertTrue(result.isPresent());
            assertEquals("hub", result.get().name());
            assertEquals("hub server", result.get().description());
        }

        @Test
        @DisplayName("get() returns empty for unknown template")
        void getReturnsEmptyForUnknown() {
            assertTrue(manager.get("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("exists() returns true after save, false before")
        void existsCheck() throws IOException {
            assertFalse(manager.exists("game"));
            manager.save(new TemplateConfig("game", "", "", "", 0));
            assertTrue(manager.exists("game"));
        }

        @Test
        @DisplayName("getAll() returns all saved templates")
        void getAllReturnsSaved() throws IOException {
            manager.save(new TemplateConfig("a", "", "", "", 0));
            manager.save(new TemplateConfig("b", "", "", "", 0));

            var all = manager.getAll();
            assertEquals(2, all.size());
        }
    }

    @Nested
    @DisplayName("Delete")
    class Delete {

        @Test
        @DisplayName("delete() removes template from in-memory store")
        void deleteRemovesFromMemory() throws IOException {
            manager.save(new TemplateConfig("tmp", "", "", "", 0));
            assertTrue(manager.exists("tmp"));

            manager.delete("tmp");

            assertFalse(manager.exists("tmp"));
        }

        @Test
        @DisplayName("delete() calls stateStore.deleteTemplate()")
        void deleteCallsStateStore() throws IOException {
            manager.save(new TemplateConfig("tmp", "", "", "", 0));
            manager.delete("tmp");

            verify(stateStore).deleteTemplate("tmp");
        }
    }

    @Nested
    @DisplayName("Hash computation")
    class HashComputation {

        @Test
        @DisplayName("computeHash() returns empty string when files directory does not exist")
        void emptyHashForMissingDir() throws IOException {
            String hash = manager.computeHash("nonexistent");
            assertEquals("", hash);
        }

        @Test
        @DisplayName("computeHash() returns consistent hash for same file content")
        void consistentHashForSameContent() throws IOException {
            manager.save(new TemplateConfig("stable", "", "", "", 0));
            Path files = tempDir.resolve("stable/files");
            Files.writeString(files.resolve("server.properties"), "port=25565");

            String h1 = manager.computeHash("stable");
            String h2 = manager.computeHash("stable");

            assertEquals(h1, h2);
            assertFalse(h1.isEmpty());
        }

        @Test
        @DisplayName("computeHash() returns different hashes for different content")
        void differentHashForDifferentContent() throws IOException {
            manager.save(new TemplateConfig("tpl1", "", "", "", 0));
            manager.save(new TemplateConfig("tpl2", "", "", "", 0));

            Files.writeString(tempDir.resolve("tpl1/files/data.txt"), "content-a");
            Files.writeString(tempDir.resolve("tpl2/files/data.txt"), "content-b");

            assertNotEquals(manager.computeHash("tpl1"), manager.computeHash("tpl2"));
        }
    }

    @Nested
    @DisplayName("Rehash")
    class Rehash {

        @Test
        @DisplayName("rehash() records a new template version when content changes")
        void recordsNewVersionOnChange() throws IOException {
            manager.save(new TemplateConfig("myt", "", "", "", 0));
            reset(stateStore); // clear the save() calls
            doAnswer(inv -> {
                        inv.<Runnable>getArgument(0).run();
                        return null;
                    })
                    .when(stateStore)
                    .runInTransaction(any());

            Path files = tempDir.resolve("myt/files");
            Files.writeString(files.resolve("server.properties"), "port=25565");

            manager.rehash("myt");

            verify(stateStore).recordTemplateVersion(eq("myt"), anyString(), anyLong());
        }

        @Test
        @DisplayName("rehash() creates a snapshot file when content changes")
        void createsSnapshotOnChange() throws IOException {
            manager.save(new TemplateConfig("tpl", "", "", "", 0));

            Path files = tempDir.resolve("tpl/files");
            Files.writeString(files.resolve("data.txt"), "hello");

            manager.rehash("tpl");

            String newHash = manager.get("tpl").orElseThrow().hash();
            assertFalse(newHash.isEmpty());
            Path snapshotDir = tempDir.resolve("tpl/snapshots");
            assertTrue(Files.exists(snapshotDir.resolve(newHash + ".tar.gz")));
        }

        @Test
        @DisplayName("rehash() is a no-op when content has not changed")
        void noOpWhenUnchanged() throws IOException {
            manager.save(new TemplateConfig("stable", "", "", "", 0));
            Path files = tempDir.resolve("stable/files");
            Files.writeString(files.resolve("cfg.yml"), "key: val");
            manager.rehash("stable"); // first rehash records version
            reset(stateStore);

            manager.rehash("stable"); // second rehash -- same content

            verify(stateStore, never()).recordTemplateVersion(any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("Snapshot and restore")
    class SnapshotAndRestore {

        @Test
        @DisplayName("restoreSnapshot() restores file content from a previous version")
        void restoresFileContent() throws IOException {
            manager.save(new TemplateConfig("snap", "", "", "", 0));
            Path files = tempDir.resolve("snap/files");

            // Version A
            Files.writeString(files.resolve("server.properties"), "port=25565");
            manager.rehash("snap");
            String hashA = manager.get("snap").orElseThrow().hash();

            // Version B (modify the file)
            Files.writeString(files.resolve("server.properties"), "port=19132");
            manager.rehash("snap");
            String hashB = manager.get("snap").orElseThrow().hash();
            assertNotEquals(hashA, hashB);

            // Restore to version A
            manager.restoreSnapshot("snap", hashA);

            assertEquals("port=25565", Files.readString(files.resolve("server.properties")));
        }

        @Test
        @DisplayName("restoreSnapshot() updates the in-memory hash after restore")
        void updatesHashAfterRestore() throws IOException {
            manager.save(new TemplateConfig("snap2", "", "", "", 0));
            Path files = tempDir.resolve("snap2/files");

            Files.writeString(files.resolve("cfg.yml"), "original");
            manager.rehash("snap2");
            String hashOriginal = manager.get("snap2").orElseThrow().hash();

            Files.writeString(files.resolve("cfg.yml"), "modified");
            manager.rehash("snap2");

            manager.restoreSnapshot("snap2", hashOriginal);

            assertEquals(hashOriginal, manager.get("snap2").orElseThrow().hash());
        }

        @Test
        @DisplayName("restoreSnapshot() throws IllegalArgumentException for unknown hash")
        void throwsForUnknownHash() throws IOException {
            manager.save(new TemplateConfig("nosnap", "", "", "", 0));

            assertThrows(IllegalArgumentException.class, () -> manager.restoreSnapshot("nosnap", "deadbeef"));
        }

        @Test
        @DisplayName("createSnapshot() is idempotent for the same hash")
        void createSnapshotIdempotent() throws IOException {
            manager.save(new TemplateConfig("idem", "", "", "", 0));
            Path files = tempDir.resolve("idem/files");
            Files.writeString(files.resolve("data.txt"), "abc");
            manager.rehash("idem");

            String hash = manager.get("idem").orElseThrow().hash();
            Path snapshot = tempDir.resolve("idem/snapshots/" + hash + ".tar.gz");
            long sizeBefore = Files.size(snapshot);

            // Second createSnapshot with same hash should no-op
            manager.createSnapshot("idem", hash);

            assertEquals(sizeBefore, Files.size(snapshot));
        }
    }
}
