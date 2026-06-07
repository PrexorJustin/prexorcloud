package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.regex.Pattern;

/**
 * Explicit runtime target for a workload extension, for example
 * {@code server/paper} or {@code proxy/velocity}.
 */
public record RuntimeTarget(String workloadType, String runtimeFamily) {

    private static final Pattern PART_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    public RuntimeTarget {
        if (!isValidPart(workloadType)) {
            throw new IllegalArgumentException("invalid workload type: " + workloadType);
        }
        if (!isValidPart(runtimeFamily)) {
            throw new IllegalArgumentException("invalid runtime family: " + runtimeFamily);
        }
    }

    public static RuntimeTarget parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime target must not be blank");
        }
        String[] parts = value.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("runtime target must be '<type>/<runtime>': " + value);
        }
        return new RuntimeTarget(parts[0], parts[1]);
    }

    public String wireValue() {
        return workloadType + "/" + runtimeFamily;
    }

    @Override
    public String toString() {
        return wireValue();
    }

    private static boolean isValidPart(String value) {
        return value != null && PART_PATTERN.matcher(value).matches();
    }
}
