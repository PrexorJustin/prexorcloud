package me.prexorjustin.prexorcloud.common.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates instance IDs in the format {@code {group}-{N}} where N is a
 * positive integer. Dynamic instances use gap-filling (lowest available N),
 * static instances use deterministic numbering.
 */
public final class InstanceIdGenerator {

    private InstanceIdGenerator() {}

    /**
     * Generate an incremental instance ID for a dynamic group. Finds the lowest
     * positive integer N where {@code {group}-{N}} is not in use.
     *
     * @param group
     *            the group name (e.g. "lobby")
     * @param existingIds
     *            set of all currently existing instance IDs
     * @return e.g. "lobby-1", "lobby-2" (gap-filling)
     */
    public static String generateDynamic(String group, Set<String> existingIds) {
        String prefix = group + "-";
        Set<Integer> usedNumbers = existingIds.stream()
                .filter(id -> id.startsWith(prefix))
                .map(id -> id.substring(prefix.length()))
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .collect(Collectors.toSet());

        for (int n = 1; ; n++) {
            if (!usedNumbers.contains(n)) {
                return group + "-" + n;
            }
        }
    }

    /**
     * Return the deterministic list of expected instance IDs for a static group. If
     * {@code staticInstanceNames} is non-empty, those are used as full IDs
     * verbatim. Otherwise generates {@code {group}-1} through
     * {@code {group}-{minInstances}}.
     *
     * @param group
     *            the group name
     * @param minInstances
     *            number of static instances
     * @param staticInstanceNames
     *            explicit names (may be empty)
     * @return ordered list of expected instance IDs
     */
    public static List<String> staticInstanceIds(String group, int minInstances, List<String> staticInstanceNames) {
        if (!staticInstanceNames.isEmpty()) {
            return List.copyOf(staticInstanceNames);
        }
        var ids = new ArrayList<String>(minInstances);
        for (int i = 1; i <= minInstances; i++) {
            ids.add(group + "-" + i);
        }
        return List.copyOf(ids);
    }
}
