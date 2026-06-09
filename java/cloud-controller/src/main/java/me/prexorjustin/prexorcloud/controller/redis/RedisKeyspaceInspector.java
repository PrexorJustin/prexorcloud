package me.prexorjustin.prexorcloud.controller.redis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Operator-facing Redis keyspace audit helper. It reports counts and bounded
 * samples for known controller prefixes without exposing key values.
 */
public final class RedisKeyspaceInspector {

    private static final int SCAN_LIMIT = 500;
    private static final int SAMPLE_LIMIT = 5;

    public record PrefixSummary(String prefix, long keyCount, List<String> sampleKeys) {
        public PrefixSummary {
            sampleKeys = List.copyOf(sampleKeys);
        }
    }

    public record KeyspaceReport(boolean available, long totalKeys, List<PrefixSummary> prefixes, String error) {
        public KeyspaceReport {
            prefixes = List.copyOf(prefixes);
        }
    }

    @FunctionalInterface
    interface PrefixScanner {
        List<String> scan(String prefix);
    }

    private final PrefixScanner scanner;

    public RedisKeyspaceInspector(RedisCommands<String, String> commands) {
        this(prefix -> scanPrefix(commands, prefix));
    }

    RedisKeyspaceInspector(PrefixScanner scanner) {
        this.scanner = scanner;
    }

    public KeyspaceReport inspect(List<String> prefixes) {
        try {
            var summaries = new ArrayList<PrefixSummary>();
            long total = 0;
            for (String prefix : prefixes.stream().sorted().toList()) {
                List<String> keys = scanner.scan(prefix).stream()
                        .sorted(Comparator.naturalOrder())
                        .toList();
                total += keys.size();
                summaries.add(new PrefixSummary(
                        prefix, keys.size(), keys.stream().limit(SAMPLE_LIMIT).toList()));
            }
            return new KeyspaceReport(true, total, summaries, null);
        } catch (Exception e) {
            return new KeyspaceReport(false, 0, List.of(), e.getMessage());
        }
    }

    private static List<String> scanPrefix(RedisCommands<String, String> commands, String prefix) {
        var keys = new ArrayList<String>();
        var scanArgs = ScanArgs.Builder.matches(prefix + "*").limit(SCAN_LIMIT);
        KeyScanCursor<String> cursor = commands.scan(scanArgs);
        while (true) {
            keys.addAll(cursor.getKeys());
            if (cursor.isFinished()) {
                return List.copyOf(keys);
            }
            cursor = commands.scan(cursor, scanArgs);
        }
    }
}
