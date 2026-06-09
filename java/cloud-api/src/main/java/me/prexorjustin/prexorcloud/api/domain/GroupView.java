package me.prexorjustin.prexorcloud.api.domain;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of a server group configuration. Unified type used by both
 * plugin developers and module developers.
 *
 * <p>
 * Fields derived purely from configuration ({@code memoryMb},
 * {@code cpuReservation}, {@code diskReservationMb}, {@code jvmArgs},
 * {@code env}, {@code nodeAffinity}) are available to module developers.
 * Plugin developers typically only use identity and scaling fields.
 * </p>
 *
 * @param name
 *            unique group name
 * @param parent
 *            parent group name for template inheritance, or {@code null}
 * @param platform
 *            server platform (e.g. {@code "paper"}, {@code "velocity"})
 * @param platformVersion
 *            Minecraft or proxy version string
 * @param scalingMode
 *            scaling policy identifier
 * @param minInstances
 *            minimum running instances
 * @param maxInstances
 *            maximum running instances
 * @param maxPlayers
 *            player capacity per instance
 * @param onlineCount
 *            total players across all running instances (runtime stat)
 * @param isStatic
 *            whether this group manages static (non-ephemeral) instances
 * @param maintenance
 *            whether the group is in maintenance mode
 * @param maintenanceMessage
 *            message shown to players blocked by maintenance
 * @param maintenanceBypassUuids
 *            player UUIDs allowed to join during maintenance
 * @param isDefaultGroup
 *            whether new players land here by default
 * @param nodeAffinity
 *            preferred node labels for scheduling
 * @param priority
 *            scheduling priority (higher = preferred)
 * @param memoryMb
 *            JVM heap size in megabytes
 * @param cpuReservation
 *            requested CPU reservation as a fraction of node capacity
 * @param diskReservationMb
 *            requested disk reservation in megabytes
 * @param jvmArgs
 *            additional JVM arguments
 * @param env
 *            environment variables injected into instances
 */
public record GroupView(
        String name,
        String parent,
        String platform,
        String platformVersion,
        String scalingMode,
        int minInstances,
        int maxInstances,
        int maxPlayers,
        int onlineCount,
        boolean isStatic,
        boolean maintenance,
        String maintenanceMessage,
        List<String> maintenanceBypassUuids,
        boolean isDefaultGroup,
        List<String> nodeAffinity,
        int priority,
        int memoryMb,
        double cpuReservation,
        long diskReservationMb,
        List<String> jvmArgs,
        Map<String, String> env) {}
