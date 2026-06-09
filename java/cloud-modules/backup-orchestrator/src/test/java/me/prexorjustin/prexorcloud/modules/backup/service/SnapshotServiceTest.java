package me.prexorjustin.prexorcloud.modules.backup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;
import me.prexorjustin.prexorcloud.api.module.data.Update;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotMetadata;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotRepository;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@DisplayName("SnapshotService")
class SnapshotServiceTest {

    @Test
    @DisplayName("globMatch handles trailing-* and literal suffix patterns")
    void globMatcherCoversTheCommonCases() {
        assertTrue(SnapshotService.globMatch("*.json", "ops.json"));
        assertTrue(SnapshotService.globMatch("*.json", "server.properties.json"));
        assertFalse(SnapshotService.globMatch("*.json", "server.properties"));
        assertTrue(SnapshotService.globMatch("server.*", "server.properties"));
        assertTrue(SnapshotService.globMatch("*", "anything-at-all"));
        assertFalse(SnapshotService.globMatch("ops.json", "ops.jsonp"));
    }

    @Test
    @DisplayName("filterByPatterns drops dirs and unmatched extensions")
    void filterRespectsPatterns() {
        var entries = List.of(
                new InstanceFileAccess.InstanceFileEntry("server.properties", 1024L, false, 0L),
                new InstanceFileAccess.InstanceFileEntry("world", 0L, true, 0L),
                new InstanceFileAccess.InstanceFileEntry("world/region/r.0.0.mca", 4096L, false, 0L),
                new InstanceFileAccess.InstanceFileEntry("ops.json", 64L, false, 0L));

        var filtered = SnapshotService.filterByPatterns(entries, List.of("*.properties", "*.json"));
        var paths = filtered.stream()
                .map(InstanceFileAccess.InstanceFileEntry::path)
                .toList();
        assertEquals(List.of("server.properties", "ops.json"), paths);
    }

    @Test
    @DisplayName("snapshotInstance walks, reads, and writes a non-empty tar.gz")
    void snapshotsAnInstanceEndToEnd(@TempDir Path archiveRoot) throws IOException {
        var files = Map.of(
                "server.properties", "level-name=world\nmotd=PrexorCloud\n",
                "ops.json", "[]\n",
                "logs/latest.log", "ignored — not in patterns\n");
        StubFileAccess stub = new StubFileAccess(files, false);
        FakeStore store = new FakeStore();
        SnapshotRepository repo = new SnapshotRepository(store);

        SnapshotService service =
                new SnapshotService(stub, repo, archiveRoot, LoggerFactory.getLogger("snapshot-test"));

        SnapshotMetadata md = service.snapshotInstance("node-1", "lobby", "inst-1", List.of("*.properties", "*.json"));

        assertTrue(md.ok(), () -> "unexpected error: " + md.error());
        assertEquals(2, md.fileCount());
        assertTrue(md.archiveSizeBytes() > 0L);

        Path archive = Path.of(md.archivePath());
        assertTrue(Files.exists(archive));
        Set<String> packed = readTarPaths(archive);
        assertEquals(Set.of("server.properties", "ops.json"), packed);

        // Metadata was persisted.
        assertEquals(1, store.docs.getOrDefault("snapshots", List.of()).size());
    }

    @Test
    @DisplayName("walk error short-circuits and the error is persisted")
    void walkErrorIsRecorded(@TempDir Path archiveRoot) {
        StubFileAccess stub = new StubFileAccess(Map.of(), false);
        stub.walkError = "DAEMON_UNREACHABLE";
        FakeStore store = new FakeStore();
        SnapshotRepository repo = new SnapshotRepository(store);

        SnapshotService service =
                new SnapshotService(stub, repo, archiveRoot, LoggerFactory.getLogger("snapshot-test"));

        SnapshotMetadata md = service.snapshotInstance("node-1", "lobby", "inst-1", null);

        assertFalse(md.ok());
        assertEquals("DAEMON_UNREACHABLE", md.error());
        assertEquals(0, md.fileCount());
        // Error path still persists metadata so the operator can see the failure.
        assertEquals(1, store.docs.getOrDefault("snapshots", List.of()).size());
    }

    @Test
    @DisplayName("truncated reads are noted but the archive still lands")
    void truncatedReadsAreTracked(@TempDir Path archiveRoot) throws IOException {
        StubFileAccess stub = new StubFileAccess(Map.of("ops.json", "abc"), true);
        FakeStore store = new FakeStore();
        SnapshotRepository repo = new SnapshotRepository(store);

        SnapshotService service =
                new SnapshotService(stub, repo, archiveRoot, LoggerFactory.getLogger("snapshot-test"));

        SnapshotMetadata md = service.snapshotInstance("node-1", "lobby", "inst-1", List.of("*.json"));

        assertTrue(md.ok());
        assertEquals(List.of("ops.json"), md.truncatedFiles());
        assertTrue(Files.exists(Path.of(md.archivePath())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Set<String> readTarPaths(Path archive) throws IOException {
        Set<String> out = new HashSet<>();
        try (InputStream raw = Files.newInputStream(archive);
                GZIPInputStream gz = new GZIPInputStream(raw);
                TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                out.add(entry.getName());
            }
        }
        return out;
    }

    /**
     * Hand-rolled fake for {@link InstanceFileAccess} — Mockito would also
     * work but the contract is small enough that an explicit stub keeps the
     * test readable and matches the "no mocks beyond external boundaries"
     * project convention.
     */
    private static final class StubFileAccess implements InstanceFileAccess {
        private final Map<String, String> files;
        private final boolean truncate;
        String walkError = "";

        StubFileAccess(Map<String, String> files, boolean truncate) {
            this.files = files;
            this.truncate = truncate;
        }

        @Override
        public InstanceFileTree walk(String nodeId, String group, String instanceId) {
            if (!walkError.isBlank()) {
                return new InstanceFileTree(List.of(), false, walkError);
            }
            List<InstanceFileEntry> entries = new ArrayList<>();
            for (var e : files.entrySet()) {
                entries.add(new InstanceFileEntry(e.getKey(), e.getValue().length(), false, 0L));
            }
            return new InstanceFileTree(List.copyOf(entries), false, "");
        }

        @Override
        public InstanceFileBytes read(String nodeId, String group, String instanceId, String relPath, int maxBytes) {
            String content = files.get(relPath);
            if (content == null) return new InstanceFileBytes("", 0L, false, "FILE_NOT_FOUND");
            return new InstanceFileBytes(content, content.length(), truncate, "");
        }
    }

    /**
     * In-memory {@link ModuleDataStore} just sufficient for SnapshotRepository's
     * insert/find/delete usage. Anything beyond what we exercise throws so
     * accidental extensions surface immediately.
     */
    private static final class FakeStore implements ModuleDataStore {
        final Map<String, List<Object>> docs = new HashMap<>();

        @Override
        public String collectionPrefix() {
            return "mod_test_";
        }

        @Override
        public void ensureCollection(String name) {
            docs.computeIfAbsent(name, ignored -> new ArrayList<>());
        }

        @Override
        public void createIndex(String collection, IndexSpec index) {
            // no-op: this fake doesn't model indexes.
        }

        @Override
        public <T> String insertOne(String collection, T document) {
            docs.computeIfAbsent(collection, ignored -> new ArrayList<>()).add(document);
            return "id-" + docs.get(collection).size();
        }

        @Override
        public <T> int insertMany(String collection, List<T> documents) {
            docs.computeIfAbsent(collection, ignored -> new ArrayList<>()).addAll(documents);
            return documents.size();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Optional<T> findOne(String collection, Query filter, Class<T> type) {
            // Tests don't exercise findOne yet — keep simple by returning the first row, untyped cast.
            var rows = docs.getOrDefault(collection, List.of());
            return rows.isEmpty() ? Optional.empty() : Optional.of((T) rows.get(0));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<T> find(String collection, Query filter, Sort sort, int limit, Class<T> type) {
            var rows = docs.getOrDefault(collection, List.of());
            List<T> out = new ArrayList<>();
            for (var row : rows) out.add((T) row);
            return out;
        }

        @Override
        public <T> List<T> find(String collection, Query filter, Sort sort, int limit, int skip, Class<T> type) {
            return find(collection, filter, sort, limit, type);
        }

        @Override
        public long count(String collection, Query filter) {
            return docs.getOrDefault(collection, List.of()).size();
        }

        @Override
        public int updateOne(String collection, Query filter, Update update) {
            throw new UnsupportedOperationException("not used by snapshot tests");
        }

        @Override
        public int updateMany(String collection, Query filter, Update update) {
            throw new UnsupportedOperationException("not used by snapshot tests");
        }

        @Override
        public boolean upsertOne(String collection, Query filter, Update update) {
            throw new UnsupportedOperationException("not used by snapshot tests");
        }

        @Override
        public boolean deleteOne(String collection, Query filter) {
            var rows = docs.get(collection);
            if (rows == null || rows.isEmpty()) return false;
            rows.remove(0);
            return true;
        }

        @Override
        public int deleteMany(String collection, Query filter) {
            var rows = docs.get(collection);
            if (rows == null) return 0;
            int n = rows.size();
            rows.clear();
            return n;
        }

        @Override
        public void withTransaction(TransactionWork work) {
            try {
                work.execute(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // Used variable to silence the unused-import linter on AtomicReference
        // in earlier drafts; intentionally inert.
        @SuppressWarnings("unused")
        private final AtomicReference<ByteArrayInputStream> ignored = new AtomicReference<>();
    }
}
