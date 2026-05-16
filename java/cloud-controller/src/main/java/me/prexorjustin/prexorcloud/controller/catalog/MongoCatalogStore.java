package me.prexorjustin.prexorcloud.controller.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig.PlatformCatalog;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfig.VersionEntry;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed software catalog storage.
 */
public final class MongoCatalogStore implements CatalogStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoCatalogStore.class);
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> catalog;

    public MongoCatalogStore(MongoDatabase db) {
        this.catalog = db.getCollection("catalog");
    }

    @Override
    public List<CatalogConfigLoader.CatalogEntry> getAll() {
        var entries = new ArrayList<CatalogConfigLoader.CatalogEntry>();
        for (var doc : catalog.find().sort(Sorts.ascending("_id"))) {
            String platform = doc.getString("_id");
            String category = doc.getString("category");
            String configFormat = doc.getString("configFormat");
            for (var v : doc.getList("versions", Document.class, List.of())) {
                entries.add(new CatalogConfigLoader.CatalogEntry(
                        platform,
                        category,
                        configFormat,
                        v.getString("version"),
                        v.getString("downloadUrl"),
                        stringValue(v, "sha256"),
                        v.getBoolean("recommended", false)));
            }
        }
        return entries;
    }

    @Override
    public List<CatalogConfigLoader.CatalogEntry> getByPlatform(String platform) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        if (doc == null) return List.of();

        String category = doc.getString("category");
        String configFormat = doc.getString("configFormat");
        return doc.getList("versions", Document.class, List.of()).stream()
                .map(v -> new CatalogConfigLoader.CatalogEntry(
                        doc.getString("_id"),
                        category,
                        configFormat,
                        v.getString("version"),
                        v.getString("downloadUrl"),
                        stringValue(v, "sha256"),
                        v.getBoolean("recommended", false)))
                .toList();
    }

    @Override
    public Optional<PlatformCatalog> getPlatform(String platform) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        return Optional.ofNullable(doc).map(MongoCatalogStore::toPlatformCatalog);
    }

    @Override
    public boolean addEntry(
            String platform, String category, String configFormat, String version, String downloadUrl, String sha256) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        boolean newPlatform = (doc == null);

        if (doc != null) {
            var versions = new ArrayList<>(doc.getList("versions", Document.class, List.of()));
            versions.removeIf(v -> v.getString("version").equals(version));
            boolean recommended = versions.isEmpty();
            versions.add(new Document("version", version)
                    .append("downloadUrl", downloadUrl)
                    .append("sha256", sha256)
                    .append("recommended", recommended));
            doc.put("versions", versions);
            catalog.replaceOne(Filters.eq("_id", doc.getString("_id")), doc);
        } else {
            var versionDoc = new Document("version", version)
                    .append("downloadUrl", downloadUrl)
                    .append("sha256", sha256)
                    .append("recommended", true);
            var newDoc = new Document("_id", platform)
                    .append("category", category)
                    .append("configFormat", configFormat)
                    .append("versions", List.of(versionDoc));
            catalog.insertOne(newDoc);
        }

        logger.debug("Added catalog entry: {}/{}", platform, version);
        return newPlatform;
    }

    @Override
    public void updateEntry(String platform, String oldVersion, String newVersion, String downloadUrl, String sha256) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        if (doc == null) return;

        var versions = new ArrayList<>(doc.getList("versions", Document.class, List.of()));
        boolean wasRecommended = versions.stream()
                .filter(v -> v.getString("version").equals(oldVersion))
                .findFirst()
                .map(v -> v.getBoolean("recommended", false))
                .orElse(false);

        versions.removeIf(v -> v.getString("version").equals(oldVersion));
        versions.add(new Document("version", newVersion)
                .append("downloadUrl", downloadUrl)
                .append("sha256", sha256)
                .append("recommended", wasRecommended));
        doc.put("versions", versions);
        catalog.replaceOne(Filters.eq("_id", doc.getString("_id")), doc);
        logger.debug("Updated catalog entry: {}/{} -> {}", platform, oldVersion, newVersion);
    }

    @Override
    public void removeEntry(String platform, String version) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        if (doc == null) return;

        var versions = new ArrayList<>(doc.getList("versions", Document.class, List.of()));
        versions.removeIf(v -> v.getString("version").equals(version));

        if (versions.isEmpty()) {
            catalog.deleteOne(Filters.eq("_id", doc.getString("_id")));
        } else {
            doc.put("versions", versions);
            catalog.replaceOne(Filters.eq("_id", doc.getString("_id")), doc);
        }
        logger.debug("Removed catalog entry: {}/{}", platform, version);
    }

    @Override
    public void setRecommended(String platform, String version) {
        var doc = catalog.find(Filters.regex("_id", "^" + platform + "$", "i")).first();
        if (doc == null) return;

        var versions = doc.getList("versions", Document.class, List.of()).stream()
                .map(v -> new Document("version", v.getString("version"))
                        .append("downloadUrl", v.getString("downloadUrl"))
                        .append("sha256", stringValue(v, "sha256"))
                        .append("recommended", v.getString("version").equals(version)))
                .toList();
        doc.put("versions", versions);
        catalog.replaceOne(Filters.eq("_id", doc.getString("_id")), doc);
        logger.debug("Set recommended: {}/{}", platform, version);
    }

    private static PlatformCatalog toPlatformCatalog(Document doc) {
        var versions = doc.getList("versions", Document.class, List.of()).stream()
                .map(v -> new VersionEntry(
                        v.getString("version"),
                        v.getString("downloadUrl"),
                        stringValue(v, "sha256"),
                        v.getBoolean("recommended", false)))
                .toList();
        return new PlatformCatalog(
                doc.getString("_id"), doc.getString("category"), doc.getString("configFormat"), versions);
    }

    private static String stringValue(Document doc, String key) {
        Object value = doc.get(key);
        return value instanceof String str ? str : "";
    }
}
