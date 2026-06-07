package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.OptionalInt;
import java.util.Set;

/**
 * Allocates the lowest available port within a given range.
 */
public final class PortAllocator {

    private PortAllocator() {}

    /**
     * Find the lowest port in {@code [rangeStart, rangeEnd]} not in
     * {@code usedPorts}.
     */
    public static OptionalInt allocate(int rangeStart, int rangeEnd, Set<Integer> usedPorts) {
        for (int port = rangeStart; port <= rangeEnd; port++) {
            if (!usedPorts.contains(port)) {
                return OptionalInt.of(port);
            }
        }
        return OptionalInt.empty();
    }
}
