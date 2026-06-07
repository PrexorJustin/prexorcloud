package me.prexorjustin.prexorcloud.controller.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BackupCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void listReturnsManifestsNewestFirst() throws Exception {
        var catalog = new BackupCatalog(tempDir);
        writeManifest("20260101-000000-aaaa", 1_700_000_000_000L);
        writeManifest("20260201-000000-bbbb", 1_800_000_000_000L);
        writeManifest("20260301-000000-cccc", 1_900_000_000_000L);

        List<BackupManifest> manifests = catalog.list();

        assertEquals(3, manifests.size());
        assertEquals("20260301-000000-cccc", manifests.get(0).id());
        assertEquals("20260201-000000-bbbb", manifests.get(1).id());
        assertEquals("20260101-000000-aaaa", manifests.get(2).id());
    }

    @Test
    void listSkipsDirectoriesWithoutManifest() throws Exception {
        var catalog = new BackupCatalog(tempDir);
        writeManifest("good", 1_000L);
        Files.createDirectories(tempDir.resolve("orphan"));

        assertEquals(1, catalog.list().size());
    }

    @Test
    void deleteRemovesBundleAndReportsTrue() throws Exception {
        var catalog = new BackupCatalog(tempDir);
        writeManifest("doomed", 1_000L);
        Files.writeString(tempDir.resolve("doomed").resolve("payload.txt"), "data");

        assertTrue(catalog.delete("doomed"));
        assertFalse(Files.exists(tempDir.resolve("doomed")));
        assertFalse(catalog.delete("doomed"));
    }

    @Test
    void pruneKeepsRetentionCountNewest() throws Exception {
        var catalog = new BackupCatalog(tempDir);
        writeManifest("a", 100L);
        writeManifest("b", 200L);
        writeManifest("c", 300L);
        writeManifest("d", 400L);

        var pruned = catalog.prune(2);

        assertEquals(2, pruned.size());
        assertEquals(List.of("b", "a"), pruned.stream().map(BackupManifest::id).toList());
        assertEquals(2, catalog.list().size());
    }

    @Test
    void rejectsTraversalIdentifiers() {
        var catalog = new BackupCatalog(tempDir);
        assertThrows(IllegalArgumentException.class, () -> catalog.bundleRoot("../escape"));
        assertThrows(IllegalArgumentException.class, () -> catalog.bundleRoot("ok/then"));
        assertThrows(IllegalArgumentException.class, () -> catalog.bundleRoot(""));
    }

    private void writeManifest(String id, long createdAtMs) throws Exception {
        Path bundle = tempDir.resolve(id);
        Files.createDirectories(bundle);
        BackupManifest manifest = new BackupManifest(
                id,
                createdAtMs,
                "controller-uuid",
                "test",
                "db",
                List.of("groups"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                42L,
                7L,
                3L,
                2L);
        new ObjectMapper().writeValue(bundle.resolve("manifest.json").toFile(), manifest);
    }
}
