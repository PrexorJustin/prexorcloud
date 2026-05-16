package me.prexorjustin.prexorcloud.controller.recovery;

import java.nio.file.Path;

import com.mongodb.client.MongoDatabase;

/**
 * Plumbing handle for the backup REST routes. Holds the controller's mongo
 * handle, working directory, and on-disk catalog so the routes can build a
 * {@link BackupCreator} without depending on the bootstrap module.
 */
public record BackupServices(MongoDatabase mongoDatabase, Path workingDirectory, BackupCatalog catalog) {

    public BackupServices {
        if (mongoDatabase == null) throw new IllegalArgumentException("mongoDatabase required");
        if (workingDirectory == null) throw new IllegalArgumentException("workingDirectory required");
        if (catalog == null) throw new IllegalArgumentException("catalog required");
    }
}
