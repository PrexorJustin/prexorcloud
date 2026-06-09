package me.prexorjustin.prexorcloud.controller.recovery;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Self-describing on-disk manifest written alongside every controller backup
 * bundle. The disk manifest is the source of truth — list/verify/restore
 * operate from this record. Stored at {bundleRoot}/manifest.json.
 */
public record BackupManifest(
        @JsonProperty("id") String id,
        @JsonProperty("createdAtMs") long createdAtMs,
        @JsonProperty("controllerId") String controllerId,
        @JsonProperty("controllerVersion") String controllerVersion,
        @JsonProperty("mongoDatabase") String mongoDatabase,
        @JsonProperty("mongoCollections") List<String> mongoCollections,
        @JsonProperty("mongoCollectionPrefixes") List<String> mongoCollectionPrefixes,
        @JsonProperty("redisKeyPrefixes") List<String> redisKeyPrefixes,
        @JsonProperty("files") List<String> files,
        @JsonProperty("directories") List<String> directories,
        @JsonProperty("sizeBytes") long sizeBytes,
        @JsonProperty("mongoDocumentCount") long mongoDocumentCount,
        @JsonProperty("redisKeyCount") long redisKeyCount,
        @JsonProperty("fileCount") long fileCount) {

    public BackupManifest {
        mongoCollections = List.copyOf(mongoCollections == null ? List.of() : mongoCollections);
        mongoCollectionPrefixes = List.copyOf(mongoCollectionPrefixes == null ? List.of() : mongoCollectionPrefixes);
        redisKeyPrefixes = List.copyOf(redisKeyPrefixes == null ? List.of() : redisKeyPrefixes);
        files = List.copyOf(files == null ? List.of() : files);
        directories = List.copyOf(directories == null ? List.of() : directories);
    }
}
