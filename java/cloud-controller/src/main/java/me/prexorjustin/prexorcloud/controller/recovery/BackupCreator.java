package me.prexorjustin.prexorcloud.controller.recovery;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Writes a controller backup bundle to disk in the format
 * {@link RestoreExecutor} understands. Layout:
 *
 * <pre>
 * {bundleRoot}/
 *   manifest.json
 *   {scope.files}                 (e.g. config/controller.yml, config/security/ca.p12)
 *   {scope.directories}/...       (e.g. templates/, modules/)
 *   mongo/{db}/{collection}.jsonl + prefixes.txt
 *   redis/keys.jsonl              (only when redis prefixes are scoped)
 *   redis/prefixes.txt
 * </pre>
 */
public final class BackupCreator {

    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final JsonWriterSettings RELAXED_JSON =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Clock clock;

    public BackupCreator() {
        this(Clock.systemUTC());
    }

    public BackupCreator(Clock clock) {
        this.clock = clock;
    }

    public BackupManifest create(
            BackupScope scope,
            Path bundleRoot,
            Path workingDirectory,
            MongoDatabase mongo,
            RedisCommands<String, String> redis,
            String controllerId,
            String controllerVersion)
            throws IOException {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(bundleRoot, "bundleRoot");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(mongo, "mongo");
        if (!scope.redisKeyPrefixes().isEmpty() && redis == null) {
            throw new IllegalArgumentException("Redis commands required when scope contains redis prefixes");
        }

        Path normalizedBundle = bundleRoot.toAbsolutePath().normalize();
        Path normalizedSource = workingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedBundle);

        long fileCount = copyFilesystem(scope, normalizedSource, normalizedBundle);
        long mongoDocs = dumpMongo(scope, normalizedBundle, mongo);
        long redisKeys = dumpRedis(scope, normalizedBundle, redis);
        long sizeBytes = directorySize(normalizedBundle);

        BackupManifest manifest = new BackupManifest(
                generateId(),
                Instant.now(clock).toEpochMilli(),
                controllerId == null ? "" : controllerId,
                controllerVersion == null ? "" : controllerVersion,
                scope.mongoDatabase(),
                scope.mongoCollections(),
                scope.mongoCollectionPrefixes(),
                scope.redisKeyPrefixes(),
                scope.files().stream().map(Path::toString).toList(),
                scope.directories().stream().map(Path::toString).toList(),
                sizeBytes,
                mongoDocs,
                redisKeys,
                fileCount);
        JSON.writeValue(normalizedBundle.resolve("manifest.json").toFile(), manifest);
        return manifest;
    }

    public String generateId() {
        return ID_FORMAT.format(Instant.now(clock)) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private long copyFilesystem(BackupScope scope, Path sourceRoot, Path bundleRoot) throws IOException {
        long count = 0;
        for (Path file : scope.files()) {
            Path source = resolveInside(sourceRoot, file);
            Path target = resolveInside(bundleRoot, file);
            if (!Files.isRegularFile(source)) continue;
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            count++;
        }
        for (Path directory : scope.directories()) {
            Path source = resolveInside(sourceRoot, directory);
            Path target = resolveInside(bundleRoot, directory);
            if (!Files.isDirectory(source)) {
                Files.createDirectories(target);
                continue;
            }
            count += copyDirectory(source, target);
        }
        return count;
    }

    private long dumpMongo(BackupScope scope, Path bundleRoot, MongoDatabase database) throws IOException {
        Path mongoRoot = bundleRoot.resolve("mongo").resolve(scope.mongoDatabase());
        Files.createDirectories(mongoRoot);

        long count = 0;
        for (String collection : scope.mongoCollections()) {
            count += writeMongoCollection(mongoRoot, database.getCollection(collection), collection);
        }

        Set<String> prefixedCollections = new LinkedHashSet<>();
        for (String prefix : scope.mongoCollectionPrefixes()) {
            for (String name : database.listCollectionNames()) {
                if (name.startsWith(prefix)) {
                    prefixedCollections.add(name);
                }
            }
        }
        for (String collection : prefixedCollections) {
            count += writeMongoCollection(mongoRoot, database.getCollection(collection), collection);
        }
        writePrefixManifest(mongoRoot.resolve("prefixes.txt"), scope.mongoCollectionPrefixes());
        return count;
    }

    private long writeMongoCollection(Path mongoRoot, MongoCollection<Document> collection, String name)
            throws IOException {
        Path artifact = mongoRoot.resolve(name + ".jsonl");
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(artifact, StandardCharsets.UTF_8)) {
            try (var cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    writer.write(cursor.next().toJson(RELAXED_JSON));
                    writer.write('\n');
                    count++;
                }
            }
        }
        return count;
    }

    private long dumpRedis(BackupScope scope, Path bundleRoot, RedisCommands<String, String> redis) throws IOException {
        if (scope.redisKeyPrefixes().isEmpty()) return 0;
        Path redisRoot = bundleRoot.resolve("redis");
        Files.createDirectories(redisRoot);

        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(redisRoot.resolve("keys.jsonl"), StandardCharsets.UTF_8)) {
            for (String prefix : scope.redisKeyPrefixes()) {
                count += scanAndWritePrefix(redis, prefix, writer);
            }
        }
        writePrefixManifest(redisRoot.resolve("prefixes.txt"), scope.redisKeyPrefixes());
        return count;
    }

    private long scanAndWritePrefix(RedisCommands<String, String> redis, String prefix, BufferedWriter writer)
            throws IOException {
        long count = 0;
        var args = ScanArgs.Builder.matches(prefix + "*").limit(500);
        var cursor = redis.scan(args);
        while (true) {
            for (String key : cursor.getKeys()) {
                String value = redis.get(key);
                if (value == null) continue;
                long ttl = redis.ttl(key);
                StringBuilder line = new StringBuilder()
                        .append("{\"key\":")
                        .append(JSON.writeValueAsString(key))
                        .append(",\"value\":")
                        .append(JSON.writeValueAsString(value));
                if (ttl > 0) {
                    line.append(",\"ttlSeconds\":").append(ttl);
                }
                line.append('}');
                writer.write(line.toString());
                writer.write('\n');
                count++;
            }
            if (cursor.isFinished()) break;
            cursor = redis.scan(cursor, args);
        }
        return count;
    }

    private static void writePrefixManifest(Path target, List<String> prefixes) throws IOException {
        Files.createDirectories(target.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            for (String prefix : prefixes) {
                writer.write(prefix);
                writer.write('\n');
            }
        }
    }

    private static long copyDirectory(Path source, Path target) throws IOException {
        long count = 0;
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path)) {
                    try {
                        Files.createDirectories(destination);
                    } catch (FileAlreadyExistsException _) {
                    }
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(
                            path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    count++;
                }
            }
        }
        return count;
    }

    private static long directorySize(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sum();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Path resolveInside(Path root, Path relative) {
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Backup paths must be relative: " + relative);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Backup path escapes root: " + relative);
        }
        return resolved;
    }
}
