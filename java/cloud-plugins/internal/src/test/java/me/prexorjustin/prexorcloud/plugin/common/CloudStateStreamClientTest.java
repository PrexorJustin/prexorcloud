package me.prexorjustin.prexorcloud.plugin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;

import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.plugin.common.dto.GroupDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.InstanceDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.PlayerDto;

import org.junit.jupiter.api.Test;

final class CloudStateStreamClientTest {

    private static final Instant STARTED_AT = Instant.parse("2026-04-22T00:00:00Z");

    @Test
    void parsesSseFramesWithIdsAndMultiLineData() throws Exception {
        var frames = new ArrayList<CloudStateStreamClient.SseFrame>();

        CloudStateStreamClient.readFrames(new StringReader("""
                        : keepalive
                        id: 41
                        event: message
                        data: {"type":"A",
                        data: "sequence":41}

                        id: 42
                        data: {"type":"B","sequence":42}

                        """), frames::add);

        assertEquals(2, frames.size());
        assertEquals("41", frames.get(0).id());
        assertEquals("{\"type\":\"A\",\n\"sequence\":41}", frames.get(0).data());
        assertEquals("message", frames.get(1).event());
    }

    @Test
    void appliesInstanceStateDeltaAndNotifiesRunningTransition() {
        var client = new FakeControllerClient();
        var cache = new CloudStateCache(client, 0);
        var stream = new CloudStateStreamClient(client, cache);
        var becameRunning = new AtomicReference<Set<String>>(Set.of());
        cache.addListener((current, added, removed, running) -> becameRunning.set(running));
        cache.applyInstanceSnapshot(List.of(instance("lobby-1", "lobby", "STARTING", 0)));

        stream.handleFrame(new CloudStateStreamClient.SseFrame("message", "7", """
                {"type":"INSTANCE_STATE_CHANGED","instanceId":"lobby-1","newState":"RUNNING","sequence":7}
                """));

        assertEquals(
                InstanceState.RUNNING,
                cache.getInstance("lobby-1").orElseThrow().state());
        assertTrue(becameRunning.get().contains("lobby-1"));
        assertEquals(7, stream.lastSequence());
        cache.stop();
    }

    @Test
    void resyncRequiredRefreshesSnapshotsAndAdvancesSequence() {
        var client = new FakeControllerClient();
        client.instances = List.of(instance("fresh-1", "fresh", "RUNNING", 3));
        client.groups = List.of(group("fresh", 3));
        var cache = new CloudStateCache(client, 0);
        var stream = new CloudStateStreamClient(client, cache);

        stream.handleFrame(new CloudStateStreamClient.SseFrame("message", "12", """
                {"type":"RESYNC_REQUIRED","lastSequence":1,"earliestSequence":5,"latestSequence":12}
                """));

        assertTrue(cache.getInstance("fresh-1").isPresent());
        assertTrue(cache.getGroup("fresh").isPresent());
        assertEquals(12, stream.lastSequence());
        assertEquals(1, client.fetchInstancesCalls);
        assertEquals(1, client.fetchGroupsCalls);
        cache.stop();
    }

    @Test
    void appliesPlayerAndGroupCountDeltas() {
        var client = new FakeControllerClient();
        var cache = new CloudStateCache(client, 0);
        var stream = new CloudStateStreamClient(client, cache);
        cache.applyInstanceSnapshot(List.of(instance("lobby-1", "lobby", "RUNNING", 1)));
        cache.applyGroupSnapshot(List.of(group("lobby", 1)));

        stream.handleFrame(new CloudStateStreamClient.SseFrame("message", "8", """
                {"type":"PLAYER_CONNECTED","instanceId":"lobby-1","group":"lobby","sequence":8}
                """));

        assertEquals(2, cache.getInstance("lobby-1").orElseThrow().playerCount());
        assertEquals(2, cache.getGroup("lobby").orElseThrow().onlineCount());
        cache.stop();
    }

    @Test
    void reconnectUsesLastProcessedSequence() throws Exception {
        var client = new FakeControllerClient();
        client.streams.add("""
                id: 1
                data: {"type":"INSTANCE_METRICS","instanceId":"lobby-1","playerCount":1,"sequence":1}

                """);
        client.streams.add("""
                id: 2
                data: {"type":"INSTANCE_METRICS","instanceId":"lobby-1","playerCount":2,"sequence":2}

                """);
        var cache = new CloudStateCache(client, 0);
        cache.applyInstanceSnapshot(List.of(instance("lobby-1", "lobby", "RUNNING", 0)));
        var stream = new CloudStateStreamClient(client, cache);

        stream.start();
        waitUntil(() -> stream.lastSequence() == 2 && client.requestedSequences.size() >= 2);
        stream.stop();

        assertEquals(List.of(0L, 1L), client.requestedSequences.subList(0, 2));
        assertEquals(2, cache.getInstance("lobby-1").orElseThrow().playerCount());
        cache.stop();
    }

    private static InstanceDto instance(String id, String group, String state, int playerCount) {
        return new InstanceDto(id, group, "node-1", "127.0.0.1", state, 25565, playerCount, 0, STARTED_AT);
    }

    private static GroupDto group(String name, int onlineCount) {
        return new GroupDto(
                name,
                "paper",
                1,
                10,
                100,
                onlineCount,
                false,
                "",
                List.of(),
                false,
                "lobby".equals(name),
                1024,
                0.25,
                4096,
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                "STATIC",
                30);
    }

    private static void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.ready()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Check {

        boolean ready();
    }

    private static final class FakeControllerClient extends BaseControllerClient {

        private List<InstanceDto> instances = List.of();
        private List<GroupDto> groups = List.of();
        private final Queue<String> streams = new ArrayDeque<>();
        private final List<Long> requestedSequences = new CopyOnWriteArrayList<>();
        private int fetchInstancesCalls;
        private int fetchGroupsCalls;

        private FakeControllerClient() {
            super("http://127.0.0.1:0", "token");
        }

        @Override
        protected String apiPrefix() {
            return "/api/plugin";
        }

        @Override
        public List<InstanceDto> fetchInstances() {
            fetchInstancesCalls++;
            return instances;
        }

        @Override
        public List<GroupDto> fetchGroups() {
            fetchGroupsCalls++;
            return groups;
        }

        @Override
        public List<PlayerDto> fetchPlayers() {
            return List.of();
        }

        @Override
        HttpResponse<java.io.InputStream> openEventStream(long lastSequence) {
            requestedSequences.add(lastSequence);
            String body = streams.poll();
            if (body == null) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("no stream available");
            }
            return new FakeStreamResponse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private record FakeStreamResponse(java.io.InputStream body) implements HttpResponse<java.io.InputStream> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1/events")).build();
        }

        @Override
        public Optional<HttpResponse<java.io.InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (ignoredName, ignoredValue) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://127.0.0.1/events");
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
