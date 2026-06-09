package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable view of an instance about to be launched on a daemon.
 *
 * <p>Daemon modules receive this in {@code onInstanceStarting} and may mutate
 * {@link #jvmArgs()} or {@link #env()} to inject flags or environment variables before the
 * process spawns. Mutations on the returned lists/maps are observed by the daemon when it
 * builds the final start command. Other fields are read-only — re-assigning them through
 * setters is not supported in v1; this is a one-shot pre-launch hook, not a planning DSL.
 */
public final class InstanceSpec {

    private final String instanceId;
    private final String group;
    private final int port;
    private final int memoryMb;
    private final List<String> jvmArgs;
    private final Map<String, String> env;
    private final String platform;
    private final String platformVersion;
    private final String jarFile;
    private final String planHash;

    public InstanceSpec(
            String instanceId,
            String group,
            int port,
            int memoryMb,
            List<String> jvmArgs,
            Map<String, String> env,
            String platform,
            String platformVersion,
            String jarFile,
            String planHash) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.group = Objects.requireNonNull(group, "group");
        this.port = port;
        this.memoryMb = memoryMb;
        this.jvmArgs = new ArrayList<>(jvmArgs == null ? List.of() : jvmArgs);
        this.env = new LinkedHashMap<>(env == null ? Map.of() : env);
        this.platform = Objects.requireNonNull(platform, "platform");
        this.platformVersion = Objects.requireNonNull(platformVersion, "platformVersion");
        this.jarFile = jarFile;
        this.planHash = planHash;
    }

    public String instanceId() {
        return instanceId;
    }

    public String group() {
        return group;
    }

    public int port() {
        return port;
    }

    public int memoryMb() {
        return memoryMb;
    }

    /** Mutable list — daemon modules may add or remove entries before launch. */
    public List<String> jvmArgs() {
        return jvmArgs;
    }

    /** Mutable map — daemon modules may add or replace entries before launch. */
    public Map<String, String> env() {
        return env;
    }

    public String platform() {
        return platform;
    }

    public String platformVersion() {
        return platformVersion;
    }

    public String jarFile() {
        return jarFile;
    }

    public String planHash() {
        return planHash;
    }
}
