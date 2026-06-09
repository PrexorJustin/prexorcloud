package me.prexorjustin.prexorcloud.daemon.config;

import java.util.Map;

import me.prexorjustin.prexorcloud.common.config.LoggingConfig;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DaemonConfig(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("advertiseAddress") String advertiseAddress,
        @JsonProperty("controller") ControllerConnectionConfig controller,
        @JsonProperty("health") HealthConfig health,
        @JsonProperty("security") SecurityDaemonConfig security,
        @JsonProperty("instances") InstancesConfig instances,
        @JsonProperty("resources") ResourcesConfig resources,
        @JsonProperty("logging") LoggingConfig logging,
        @JsonProperty("reconnect") ReconnectConfig reconnect,
        @JsonProperty("modules") ModulesDaemonConfig modules,
        @JsonProperty("telemetry") TelemetryDaemonConfig telemetry,
        @JsonProperty("labels") Map<String, String> labels) {

    public DaemonConfig {
        if (nodeId == null) nodeId = "node-1";
        if (advertiseAddress == null) advertiseAddress = "";
        if (controller == null) controller = new ControllerConnectionConfig();
        if (health == null) health = new HealthConfig();
        if (security == null) security = new SecurityDaemonConfig();
        if (instances == null) instances = new InstancesConfig();
        if (resources == null) resources = new ResourcesConfig();
        if (logging == null) logging = new LoggingConfig();
        if (reconnect == null) reconnect = new ReconnectConfig();
        if (modules == null) modules = new ModulesDaemonConfig();
        if (telemetry == null) telemetry = new TelemetryDaemonConfig();
        if (labels == null) labels = Map.of();
    }

    /** Container for daemon-host module settings (signing today; more in v2). */
    public record ModulesDaemonConfig(
            @JsonProperty("signing") ModuleSigningDaemonConfig signing) {
        public ModulesDaemonConfig {
            if (signing == null) signing = new ModuleSigningDaemonConfig();
        }

        public ModulesDaemonConfig() {
            this(new ModuleSigningDaemonConfig());
        }
    }
}
