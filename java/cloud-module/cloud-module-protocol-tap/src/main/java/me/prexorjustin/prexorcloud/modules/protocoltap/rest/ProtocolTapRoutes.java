package me.prexorjustin.prexorcloud.modules.protocoltap.rest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.protocoltap.data.PacketCount;
import me.prexorjustin.prexorcloud.modules.protocoltap.data.PacketCountRepository;
import me.prexorjustin.prexorcloud.modules.protocoltap.rest.dto.ObservationRequest;

/**
 * REST routes for protocol-tap.
 *
 * <ul>
 *   <li>{@code POST /observe} — plugin posts a packet-count delta</li>
 *   <li>{@code GET  /recent?group=&limit=} — list recent observations</li>
 *   <li>{@code GET  /metrics} — Prometheus exposition (per-group totals)</li>
 * </ul>
 */
public final class ProtocolTapRoutes {

    private final PacketCountRepository repo;

    public ProtocolTapRoutes(PacketCountRepository repo) {
        this.repo = repo;
    }

    public void register(RouteRegistrar routes) {
        routes.post("/observe", ObservationRequest.class, (req, body, res) -> {
            if (body.group() == null || body.group().isBlank()) {
                res.status(400).json(Map.of("error", "group required"));
                return;
            }
            if (body.packetType() == null || body.packetType().isBlank()) {
                res.status(400).json(Map.of("error", "packetType required"));
                return;
            }
            repo.record(new PacketCount(
                    body.group(), body.packetType(), body.count(), Instant.now().toEpochMilli()));
            res.status(202);
        });

        routes.get("/recent", (req, res) -> {
            String group = req.queryParam("group").orElse("");
            int limit = req.queryParam("limit")
                    .map(s -> {
                        try {
                            return Integer.parseInt(s);
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    })
                    .orElse(50);
            if (limit <= 0 || limit > 500) limit = 50;
            res.json(Map.of("observations", repo.recent(group, limit)));
        });

        routes.get("/metrics", (req, res) -> {
            // Aggregate across the most recent 1000 observations into a small
            // per-group, per-packet table. Real protocol-tap modules would
            // pre-aggregate in Mongo; this is the reference module so we keep
            // the read simple.
            Map<String, Long> totals = new HashMap<>();
            for (PacketCount c : repo.recent("", 1000)) {
                String key = c.group() + ":" + c.packetType();
                totals.merge(key, c.count(), Long::sum);
            }
            StringBuilder out = new StringBuilder();
            out.append("# HELP protocol_tap_packets_total observed packets per group/type\n");
            out.append("# TYPE protocol_tap_packets_total counter\n");
            for (var entry : totals.entrySet()) {
                String[] parts = entry.getKey().split(":", 2);
                String group = parts.length > 0 ? parts[0] : "unknown";
                String packetType = parts.length > 1 ? parts[1] : "unknown";
                out.append("protocol_tap_packets_total{group=\"")
                        .append(group)
                        .append("\",packet_type=\"")
                        .append(packetType)
                        .append("\"} ")
                        .append(Optional.ofNullable(entry.getValue()).orElse(0L))
                        .append("\n");
            }
            res.header("Content-Type", "text/plain; version=0.0.4").text(out.toString());
        });
    }
}
