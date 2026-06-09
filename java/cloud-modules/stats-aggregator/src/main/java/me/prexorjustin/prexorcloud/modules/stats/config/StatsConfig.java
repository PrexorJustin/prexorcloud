package me.prexorjustin.prexorcloud.modules.stats.config;

public record StatsConfig(int leaderboardSize, int retentionDays, boolean prometheusEnabled) {

    public static StatsConfig defaults() {
        return new StatsConfig(25, 90, true);
    }

    public StatsConfig {
        if (leaderboardSize <= 0) {
            throw new IllegalArgumentException("leaderboardSize must be > 0");
        }
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be > 0");
        }
    }
}
