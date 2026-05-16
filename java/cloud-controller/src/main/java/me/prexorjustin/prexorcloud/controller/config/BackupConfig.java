package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BackupConfig(
        @JsonProperty("directory") String directory,
        @JsonProperty("retentionCount") int retentionCount) {

    public BackupConfig {
        if (directory == null || directory.isBlank()) directory = "backups";
        if (retentionCount <= 0) retentionCount = 10;
    }

    public BackupConfig() {
        this("backups", 10);
    }
}
