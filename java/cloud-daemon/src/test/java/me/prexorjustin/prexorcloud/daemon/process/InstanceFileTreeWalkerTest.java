package me.prexorjustin.prexorcloud.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.prexorjustin.prexorcloud.protocol.FileEntry;
import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;
import me.prexorjustin.prexorcloud.protocol.WalkInstanceFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceFileTreeWalkerTest {

    private static WalkInstanceFiles request(String group, String instanceId, int maxEntries, int summarizeThreshold) {
        return WalkInstanceFiles.newBuilder()
                .setRequestId("req-1")
                .setGroup(group)
                .setInstanceId(instanceId)
                .setMaxEntries(maxEntries)
                .setMaxDepth(8)
                .setSummarizeThreshold(summarizeThreshold)
                .build();
    }

    private static Path makeInstance(Path root, String group, String instanceId) throws IOException {
        Path dir = root.resolve(group).resolve(instanceId);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void basicWalkListsFilesAndDirs(@TempDir Path tmp) throws Exception {
        Path inst = makeInstance(tmp, "lobby", "lobby-1");
        Files.writeString(inst.resolve("server.jar"), "x");
        Files.createDirectories(inst.resolve("plugins"));
        Files.writeString(inst.resolve("plugins").resolve("a.jar"), "y");

        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("lobby", "lobby-1", 0, 0));
        assertEquals("", reply.getError());
        assertFalse(reply.getTruncated());
        assertTrue(
                reply.getEntriesCount() >= 3,
                "expected ≥3 entries (root + 2 files + dir), got " + reply.getEntriesCount());
    }

    @Test
    void missingInstanceReturnsInstanceNotFound(@TempDir Path tmp) {
        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("lobby", "lobby-1", 0, 0));
        assertEquals("INSTANCE_NOT_FOUND", reply.getError());
    }

    @Test
    void pathTraversalAttemptReturnsInvalidRequest(@TempDir Path tmp) {
        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("../escape", "lobby-1", 0, 0));
        assertEquals("INVALID_REQUEST", reply.getError());
    }

    @Test
    void maxEntriesCapTriggersTruncation(@TempDir Path tmp) throws Exception {
        Path inst = makeInstance(tmp, "lobby", "lobby-1");
        for (int i = 0; i < 50; i++) {
            Files.writeString(inst.resolve("f-" + i + ".txt"), "x");
        }
        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("lobby", "lobby-1", 10, 1000));
        assertEquals(10, reply.getEntriesCount());
        assertTrue(reply.getTruncated(), "expected truncated=true when entries hit cap");
    }

    @Test
    void directorySummarisedAboveThreshold(@TempDir Path tmp) throws Exception {
        Path inst = makeInstance(tmp, "lobby", "lobby-1");
        Path region = Files.createDirectories(inst.resolve("world").resolve("region"));
        for (int i = 0; i < 12; i++) {
            Files.writeString(region.resolve("r" + i + ".mca"), "x".repeat(100));
        }
        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("lobby", "lobby-1", 0, 5));

        FileEntry summary = reply.getEntriesList().stream()
                .filter(FileEntry::getSummary)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a summary entry, got: " + reply));
        assertEquals(12, summary.getChildCount());
        assertTrue(summary.getSizeBytes() >= 12 * 100L, "size should reflect recursive total");
        assertTrue(summary.getPath().endsWith("region"), summary.getPath());
    }

    @Test
    void symlinkRecordedNotFollowed(@TempDir Path tmp) throws Exception {
        Path inst = makeInstance(tmp, "lobby", "lobby-1");
        Files.writeString(inst.resolve("real.txt"), "x");
        try {
            Files.createSymbolicLink(inst.resolve("link"), inst.resolve("real.txt"));
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // skip on platforms without symlink support
        }
        var walker = new InstanceFileTreeWalker(tmp);
        InstanceFileTree reply = walker.walk(request("lobby", "lobby-1", 0, 1000));
        // The link entry must be reported as a non-dir leaf — never recursed.
        FileEntry linkEntry = reply.getEntriesList().stream()
                .filter(e -> e.getPath().endsWith("link"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("link entry missing"));
        assertFalse(linkEntry.getIsDir());
    }
}
