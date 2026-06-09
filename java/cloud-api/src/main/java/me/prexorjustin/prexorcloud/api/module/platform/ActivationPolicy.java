package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Declares how a workload extension becomes active for a group/runtime.
 */
public enum ActivationPolicy {
    EXPLICIT_GROUP_ATTACH("explicit-group-attach"),
    DEFAULT_ENABLED("default-enabled"),
    ALWAYS("always");

    private final String wireValue;

    ActivationPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ActivationPolicy fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("activation policy must not be blank");
        }
        for (ActivationPolicy policy : values()) {
            if (policy.wireValue.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown activation policy: " + value);
    }
}
