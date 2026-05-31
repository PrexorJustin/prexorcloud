package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import me.prexorjustin.prexorcloud.api.domain.EventChoreography;
import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.common.config.LoggingConfig;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ControllerConfig(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("http") HttpConfig http,
        @JsonProperty("grpc") GrpcConfig grpc,
        @JsonProperty("network") NetworkConfig network,
        @JsonProperty("database") DatabaseConfig database,
        @JsonProperty("logging") LoggingConfig logging,
        @JsonProperty("scheduler") SchedulerConfig scheduler,
        @JsonProperty("heartbeat") HeartbeatConfig heartbeat,
        @JsonProperty("runtime") RuntimeConfig runtime,
        @JsonProperty("security") SecurityControllerConfig security,
        @JsonProperty("crashes") CrashConfig crashes,
        @JsonProperty("metrics") MetricsConfig metrics,
        @JsonProperty("modules") ModulesConfig modules,
        @JsonProperty("maintenance") MaintenanceConfig maintenance,
        @JsonProperty("dashboard") DashboardConfig dashboard,
        @JsonProperty("backup") BackupConfig backup,
        @JsonProperty("share") ShareConfig share,
        @JsonProperty("networks") List<NetworkComposition> networks,
        @JsonProperty("events") List<EventChoreography> events,
        @JsonProperty("redis") RedisConfig redis,
        @JsonProperty("cluster") ClusterConfig cluster,
        @JsonProperty("raft") RaftConfig raft) {

    public ControllerConfig {
        if (uuid == null) uuid = java.util.UUID.randomUUID().toString();
        if (cluster == null) cluster = new ClusterConfig();
        if (raft == null) raft = new RaftConfig();
        if (http == null) http = new HttpConfig();
        if (grpc == null) grpc = new GrpcConfig();
        if (network == null) network = new NetworkConfig();
        if (database == null) database = new DatabaseConfig();
        if (logging == null) logging = new LoggingConfig();
        if (scheduler == null) scheduler = new SchedulerConfig();
        if (heartbeat == null) heartbeat = new HeartbeatConfig();
        if (runtime == null) runtime = new RuntimeConfig();
        if (security == null) security = new SecurityControllerConfig();
        if (crashes == null) crashes = new CrashConfig();
        if (metrics == null) metrics = new MetricsConfig();
        if (modules == null) modules = new ModulesConfig();
        if (maintenance == null) maintenance = new MaintenanceConfig(false, null);
        if (dashboard == null) dashboard = new DashboardConfig();
        if (backup == null) backup = new BackupConfig();
        if (share == null) share = new ShareConfig();
        if (networks == null) networks = List.of();
        if (events == null) events = List.of();
        // redis is intentionally nullable — null means Redis is disabled
    }

    /** Convenience constructor that defaults {@code networks} and {@code events} to empty lists. */
    public ControllerConfig(
            String uuid,
            HttpConfig http,
            GrpcConfig grpc,
            NetworkConfig network,
            DatabaseConfig database,
            LoggingConfig logging,
            SchedulerConfig scheduler,
            HeartbeatConfig heartbeat,
            RuntimeConfig runtime,
            SecurityControllerConfig security,
            CrashConfig crashes,
            MetricsConfig metrics,
            ModulesConfig modules,
            MaintenanceConfig maintenance,
            DashboardConfig dashboard,
            BackupConfig backup,
            ShareConfig share,
            RedisConfig redis) {
        this(
                uuid,
                http,
                grpc,
                network,
                database,
                logging,
                scheduler,
                heartbeat,
                runtime,
                security,
                crashes,
                metrics,
                modules,
                maintenance,
                dashboard,
                backup,
                share,
                List.of(),
                List.of(),
                redis,
                null,
                null);
    }
}
