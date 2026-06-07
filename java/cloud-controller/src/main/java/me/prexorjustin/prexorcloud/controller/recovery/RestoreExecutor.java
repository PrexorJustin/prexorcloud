package me.prexorjustin.prexorcloud.controller.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.Document;
import org.bson.RawBsonDocument;

/**
 * Executes controller restore work after {@link RestoreValidator} has accepted
 * the backup bundle.
 *
 * <p>
 * Filesystem restores keep rollback snapshots of overwritten local entries.
 * Mongo and Redis imports are destructive replacements of the scoped backup
 * data; operators must treat the backup bundle as the rollback source.
 * </p>
 */
public final class RestoreExecutor {

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> MONGO_ARTIFACT_EXTENSIONS = List.of(".jsonl", ".json", ".bson");

    private final RestoreValidator validator;
    private final Clock clock;

    public RestoreExecutor() {
        this(new RestoreValidator(), Clock.systemUTC());
    }

    RestoreExecutor(RestoreValidator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    public RestoreReport restoreFilesystem(BackupScope scope, Path backupRoot, Path targetRoot, RestoreMode mode)
            throws IOException {
        var validation = validator.validate(scope, backupRoot);
        if (!validation.valid()) {
            throw new RestoreRejectedException(validation);
        }

        Path normalizedBackupRoot = backupRoot.toAbsolutePath().normalize();
        Path normalizedTargetRoot = targetRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTargetRoot);

        var entries = restoreEntries(scope, normalizedBackupRoot);
        if (mode == RestoreMode.DRY_RUN) {
            return new RestoreReport(false, entries, null);
        }

        Path rollbackRoot = normalizedTargetRoot
                .resolve(".prexor-restore-backups")
                .resolve(BACKUP_TIMESTAMP.format(Instant.now(clock)));
        Files.createDirectories(rollbackRoot);

        var promoted = new ArrayList<RestoreEntry>();
        try {
            for (RestoreEntry entry : entries) {
                Path source = resolveInside(normalizedBackupRoot, entry.relativePath());
                Path target = resolveInside(normalizedTargetRoot, entry.relativePath());
                Path rollback = resolveInside(rollbackRoot, entry.relativePath());
                promoted.add(entry);
                promote(source, target, rollback, entry.type());
            }
            return new RestoreReport(true, entries, rollbackRoot);
        } catch (IOException | RuntimeException e) {
            rollback(normalizedTargetRoot, rollbackRoot, promoted);
            throw e;
        }
    }

    public DataRestoreReport restoreDatastores(
            BackupScope scope,
            Path backupRoot,
            MongoDatabase mongoDatabase,
            RedisCommands<String, String> redisCommands,
            RestoreMode mode)
            throws IOException {
        Objects.requireNonNull(mongoDatabase, "mongoDatabase");
        if (!scope.redisKeyPrefixes().isEmpty() && redisCommands == null) {
            throw new IllegalArgumentException("redisCommands is required when the backup scope contains Redis keys");
        }
        return restoreDatastores(
                scope,
                backupRoot,
                new LiveMongoRestoreTarget(mongoDatabase),
                redisCommands == null ? null : new LiveRedisRestoreTarget(redisCommands),
                mode);
    }

    DataRestoreReport restoreDatastores(
            BackupScope scope,
            Path backupRoot,
            MongoRestoreTarget mongoTarget,
            RedisRestoreTarget redisTarget,
            RestoreMode mode)
            throws IOException {
        var validation = validator.validate(scope, backupRoot);
        if (!validation.valid()) {
            throw new RestoreRejectedException(validation);
        }

        Path normalizedBackupRoot = backupRoot.toAbsolutePath().normalize();
        var mongoImports = readMongoImports(scope, normalizedBackupRoot);
        var mongoPrefixImports = readMongoPrefixImports(scope, normalizedBackupRoot);
        var redisImports = readRedisImports(scope, normalizedBackupRoot);

        if (mode == RestoreMode.DRY_RUN) {
            return new DataRestoreReport(false, mongoImports, mongoPrefixImports, redisImports);
        }

        Objects.requireNonNull(mongoTarget, "mongoTarget");
        for (MongoCollectionImport mongoImport : mongoImports) {
            mongoTarget.replaceCollection(mongoImport.collection(), mongoImport.documents());
        }
        for (MongoCollectionPrefixImport prefixImport : mongoPrefixImports) {
            mongoTarget.replaceCollectionPrefix(prefixImport.prefix(), prefixImport.collections());
        }

        if (!redisImports.isEmpty()) {
            Objects.requireNonNull(redisTarget, "redisTarget");
            for (RedisPrefixImport redisImport : redisImports) {
                redisTarget.replacePrefix(redisImport.prefix(), redisImport.entries());
            }
        }
        return new DataRestoreReport(true, mongoImports, mongoPrefixImports, redisImports);
    }

    private List<RestoreEntry> restoreEntries(BackupScope scope, Path backupRoot) throws IOException {
        var entries = new ArrayList<RestoreEntry>();
        for (Path file : scope.files()) {
            Path source = resolveInside(backupRoot, file);
            entries.add(new RestoreEntry(file, RestoreEntryType.FILE, Files.size(source)));
        }
        for (Path directory : scope.directories()) {
            Path source = resolveInside(backupRoot, directory);
            entries.add(new RestoreEntry(directory, RestoreEntryType.DIRECTORY, directorySize(source)));
        }
        return List.copyOf(entries);
    }

    private List<MongoCollectionImport> readMongoImports(BackupScope scope, Path backupRoot) throws IOException {
        Path mongoRoot = resolveInside(backupRoot, Path.of("mongo", scope.mongoDatabase()));
        var imports = new ArrayList<MongoCollectionImport>();
        for (String collection : scope.mongoCollections()) {
            imports.add(new MongoCollectionImport(collection, readMongoCollection(mongoRoot, collection)));
        }
        return List.copyOf(imports);
    }

    private List<MongoCollectionPrefixImport> readMongoPrefixImports(BackupScope scope, Path backupRoot)
            throws IOException {
        if (scope.mongoCollectionPrefixes().isEmpty()) {
            return List.of();
        }
        Path mongoRoot = resolveInside(backupRoot, Path.of("mongo", scope.mongoDatabase()));
        var imports = new ArrayList<MongoCollectionPrefixImport>();
        for (String prefix : scope.mongoCollectionPrefixes()) {
            var collections = new ArrayList<MongoCollectionImport>();
            for (String collection : prefixedCollectionNames(mongoRoot, prefix)) {
                collections.add(new MongoCollectionImport(collection, readMongoCollection(mongoRoot, collection)));
            }
            imports.add(new MongoCollectionPrefixImport(prefix, collections));
        }
        return List.copyOf(imports);
    }

    private Set<String> prefixedCollectionNames(Path mongoRoot, String prefix) throws IOException {
        if (!Files.isDirectory(mongoRoot)) {
            return Set.of();
        }
        var names = new LinkedHashSet<String>();
        try (var stream = Files.list(mongoRoot)) {
            for (Path artifact : stream.sorted().toList()) {
                String name = artifact.getFileName().toString();
                String collection = collectionNameFromArtifact(name);
                if (collection != null && collection.startsWith(prefix)) {
                    names.add(collection);
                }
            }
        }
        return Set.copyOf(names);
    }

    private static String collectionNameFromArtifact(String artifactName) {
        for (String extension : MONGO_ARTIFACT_EXTENSIONS) {
            if (artifactName.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return artifactName.substring(0, artifactName.length() - extension.length());
            }
        }
        return artifactName;
    }

    private List<Document> readMongoCollection(Path mongoRoot, String collection) throws IOException {
        Path directory = mongoRoot.resolve(collection).normalize();
        if (Files.isDirectory(directory)) {
            var documents = new ArrayList<Document>();
            try (var stream = Files.walk(directory)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    if (isMongoArtifact(file)) {
                        documents.addAll(readMongoArtifact(file));
                    }
                }
            }
            return List.copyOf(documents);
        }

        for (String extension : MONGO_ARTIFACT_EXTENSIONS) {
            Path artifact = mongoRoot.resolve(collection + extension).normalize();
            if (Files.isRegularFile(artifact)) {
                return readMongoArtifact(artifact);
            }
        }
        throw new IOException("Missing Mongo backup artifact for collection " + collection);
    }

    private static boolean isMongoArtifact(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return MONGO_ARTIFACT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private List<Document> readMongoArtifact(Path artifact) throws IOException {
        String name = artifact.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsonl")) {
            return readJsonLines(artifact);
        }
        if (name.endsWith(".json")) {
            return readJsonDocuments(artifact);
        }
        if (name.endsWith(".bson")) {
            return readBsonDocuments(artifact);
        }
        return List.of();
    }

    private List<Document> readJsonLines(Path artifact) throws IOException {
        var documents = new ArrayList<Document>();
        for (String line : Files.readAllLines(artifact, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            documents.add(Document.parse(trimmed));
        }
        return List.copyOf(documents);
    }

    private List<Document> readJsonDocuments(Path artifact) throws IOException {
        String raw = Files.readString(artifact, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        JsonNode root = JSON.readTree(raw);
        var documents = new ArrayList<Document>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                documents.add(Document.parse(JSON.writeValueAsString(node)));
            }
        } else {
            documents.add(Document.parse(JSON.writeValueAsString(root)));
        }
        return List.copyOf(documents);
    }

    private List<Document> readBsonDocuments(Path artifact) throws IOException {
        byte[] bytes = Files.readAllBytes(artifact);
        var documents = new ArrayList<Document>();
        int offset = 0;
        while (offset < bytes.length) {
            if (bytes.length - offset < Integer.BYTES) {
                throw new IOException("Invalid BSON artifact " + artifact + ": truncated document length");
            }
            int length = ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (length < 5 || offset + length > bytes.length) {
                throw new IOException("Invalid BSON artifact " + artifact + ": invalid document length " + length);
            }
            byte[] documentBytes = Arrays.copyOfRange(bytes, offset, offset + length);
            documents.add(Document.parse(new RawBsonDocument(documentBytes).toJson()));
            offset += length;
        }
        return List.copyOf(documents);
    }

    private List<RedisPrefixImport> readRedisImports(BackupScope scope, Path backupRoot) throws IOException {
        if (scope.redisKeyPrefixes().isEmpty()) {
            return List.of();
        }
        var allEntries = readRedisEntries(resolveInside(backupRoot, Path.of("redis")));
        var imports = new ArrayList<RedisPrefixImport>();
        for (String prefix : scope.redisKeyPrefixes()) {
            var entries = allEntries.stream()
                    .filter(entry -> entry.key().startsWith(prefix))
                    .toList();
            imports.add(new RedisPrefixImport(prefix, entries));
        }
        return List.copyOf(imports);
    }

    private List<RedisImportEntry> readRedisEntries(Path redisRoot) throws IOException {
        if (!Files.isDirectory(redisRoot)) {
            return List.of();
        }
        Set<Path> importFiles = new LinkedHashSet<>();
        for (String filename : List.of("keys.jsonl", "dump.jsonl", "entries.jsonl")) {
            Path candidate = redisRoot.resolve(filename).normalize();
            if (Files.isRegularFile(candidate)) {
                importFiles.add(candidate);
            }
        }
        try (var stream = Files.list(redisRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".jsonl"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("prefixes.txt") && !name.equals("manifest.txt");
                    })
                    .sorted()
                    .forEach(importFiles::add);
        }

        var entries = new ArrayList<RedisImportEntry>();
        for (Path importFile : importFiles) {
            entries.addAll(readRedisJsonLines(importFile));
        }
        return List.copyOf(entries);
    }

    private List<RedisImportEntry> readRedisJsonLines(Path importFile) throws IOException {
        var entries = new ArrayList<RedisImportEntry>();
        for (String line : Files.readAllLines(importFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            JsonNode node = JSON.readTree(trimmed);
            String key = requireText(node, "key", importFile);
            String value = node.hasNonNull("value") ? node.get("value").asText() : "";
            Long ttlSeconds = optionalPositiveLong(node, "ttlSeconds");
            if (ttlSeconds == null) {
                ttlSeconds = optionalPositiveLong(node, "ttl");
            }
            entries.add(new RedisImportEntry(key, value, ttlSeconds));
        }
        return List.copyOf(entries);
    }

    private static String requireText(JsonNode node, String field, Path source) throws IOException {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IOException("Redis import entry in " + source + " is missing '" + field + "'");
        }
        return node.get(field).asText();
    }

    private static Long optionalPositiveLong(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        long value = node.get(field).asLong();
        return value > 0 ? value : null;
    }

    private void promote(Path source, Path target, Path rollback, RestoreEntryType type) throws IOException {
        if (Files.exists(target)) {
            Files.createDirectories(rollback.getParent());
            moveReplacing(target, rollback);
        }
        Files.createDirectories(target.getParent());
        if (type == RestoreEntryType.FILE) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            copyDirectory(source, target);
        }
    }

    private void rollback(Path targetRoot, Path rollbackRoot, List<RestoreEntry> promoted) {
        for (int i = promoted.size() - 1; i >= 0; i--) {
            RestoreEntry entry = promoted.get(i);
            try {
                Path target = resolveInside(targetRoot, entry.relativePath());
                Path rollback = resolveInside(rollbackRoot, entry.relativePath());
                deleteIfExists(target);
                if (Files.exists(rollback)) {
                    Files.createDirectories(target.getParent());
                    moveReplacing(rollback, target);
                }
            } catch (IOException _) {
            }
        }
    }

    private static Path resolveInside(Path root, Path relativePath) {
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Restore paths must be relative: " + relativePath);
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Restore path escapes root: " + relativePath);
        }
        return resolved;
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

    private static void copyDirectory(Path source, Path target) throws IOException {
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
                }
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException _) {
            copyPath(source, target);
            deleteIfExists(source);
        }
    }

    private static void copyPath(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            copyDirectory(source, target);
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    public enum RestoreMode {
        DRY_RUN,
        APPLY
    }

    public enum RestoreEntryType {
        FILE,
        DIRECTORY
    }

    public record RestoreEntry(Path relativePath, RestoreEntryType type, long bytes) {}

    public record MongoCollectionImport(String collection, List<Document> documents) {

        public MongoCollectionImport {
            documents = List.copyOf(documents);
        }

        public int documentCount() {
            return documents.size();
        }
    }

    public record MongoCollectionPrefixImport(String prefix, List<MongoCollectionImport> collections) {

        public MongoCollectionPrefixImport {
            collections = List.copyOf(collections);
        }

        public int collectionCount() {
            return collections.size();
        }
    }

    public record RedisImportEntry(String key, String value, Long ttlSeconds) {}

    public record RedisPrefixImport(String prefix, List<RedisImportEntry> entries) {

        public RedisPrefixImport {
            entries = List.copyOf(entries);
        }

        public int keyCount() {
            return entries.size();
        }
    }

    public record DataRestoreReport(
            boolean applied,
            List<MongoCollectionImport> mongoImports,
            List<MongoCollectionPrefixImport> mongoPrefixImports,
            List<RedisPrefixImport> redisImports) {

        public DataRestoreReport {
            mongoImports = List.copyOf(mongoImports);
            mongoPrefixImports = List.copyOf(mongoPrefixImports);
            redisImports = List.copyOf(redisImports);
        }
    }

    public record RestoreReport(boolean applied, List<RestoreEntry> entries, Path rollbackRoot) {

        public RestoreReport {
            entries = List.copyOf(entries);
        }
    }

    interface MongoRestoreTarget {

        void replaceCollection(String collection, List<Document> documents);

        void replaceCollectionPrefix(String prefix, List<MongoCollectionImport> collections);
    }

    @FunctionalInterface
    interface RedisRestoreTarget {

        void replacePrefix(String prefix, List<RedisImportEntry> entries);
    }

    private record LiveMongoRestoreTarget(MongoDatabase database) implements MongoRestoreTarget {

        @Override
        public void replaceCollection(String collection, List<Document> documents) {
            var target = database.getCollection(collection);
            target.drop();
            if (!documents.isEmpty()) {
                target.insertMany(documents);
            }
        }

        @Override
        public void replaceCollectionPrefix(String prefix, List<MongoCollectionImport> collections) {
            for (String collectionName : database.listCollectionNames()) {
                if (collectionName.startsWith(prefix)) {
                    database.getCollection(collectionName).drop();
                }
            }
            for (MongoCollectionImport collection : collections) {
                if (!collection.documents().isEmpty()) {
                    database.getCollection(collection.collection()).insertMany(collection.documents());
                }
            }
        }
    }

    private record LiveRedisRestoreTarget(RedisCommands<String, String> commands) implements RedisRestoreTarget {

        @Override
        public void replacePrefix(String prefix, List<RedisImportEntry> entries) {
            deletePrefix(prefix);
            for (RedisImportEntry entry : entries) {
                if (entry.ttlSeconds() != null) {
                    commands.setex(entry.key(), entry.ttlSeconds(), entry.value());
                } else {
                    commands.set(entry.key(), entry.value());
                }
            }
        }

        private void deletePrefix(String prefix) {
            var scanArgs = ScanArgs.Builder.matches(prefix + "*").limit(500);
            var cursor = commands.scan(scanArgs);
            while (true) {
                if (!cursor.getKeys().isEmpty()) {
                    commands.del(cursor.getKeys().toArray(String[]::new));
                }
                if (cursor.isFinished()) {
                    break;
                }
                cursor = commands.scan(cursor, scanArgs);
            }
        }
    }

    public static final class RestoreRejectedException extends RuntimeException {

        private final RestoreValidator.RestoreValidationResult validation;

        RestoreRejectedException(RestoreValidator.RestoreValidationResult validation) {
            super("Backup failed restore validation");
            this.validation = validation;
        }

        public RestoreValidator.RestoreValidationResult validation() {
            return validation;
        }
    }
}
