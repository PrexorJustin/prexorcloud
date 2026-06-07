package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MongoCommandTracer")
class MongoCommandTracerTest {

    private static final ConnectionDescription CONN =
            new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress()));

    private static CommandStartedEvent started(int requestId, String command, BsonDocument doc) {
        return new CommandStartedEvent(null, 0L, requestId, CONN, "prexorcloud", command, doc);
    }

    @Test
    @DisplayName("records a CLIENT span with db attributes on success")
    void recordsSpanOnSuccess() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        MongoCommandTracer tracer = new MongoCommandTracer();
        tracer.attachTracer(telemetry.tracer());

        tracer.commandStarted(started(1, "find", new BsonDocument("find", new BsonString("templates"))));
        tracer.commandSucceeded(
                new CommandSucceededEvent(null, 0L, 1, CONN, "prexorcloud", "find", new BsonDocument(), 0L));

        telemetry.flush();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("mongodb find templates", span.getName());
        assertEquals("mongodb", span.getAttributes().get(AttributeKey.stringKey("db.system")));
        assertEquals("find", span.getAttributes().get(AttributeKey.stringKey("db.operation")));
        assertEquals("prexorcloud", span.getAttributes().get(AttributeKey.stringKey("db.namespace")));
        assertEquals("templates", span.getAttributes().get(AttributeKey.stringKey("db.collection.name")));
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("marks the span ERROR and records the exception on failure")
    void recordsErrorOnFailure() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        MongoCommandTracer tracer = new MongoCommandTracer();
        tracer.attachTracer(telemetry.tracer());

        tracer.commandStarted(started(2, "insert", new BsonDocument("insert", new BsonString("audit"))));
        tracer.commandFailed(
                new CommandFailedEvent(null, 0L, 2, CONN, "prexorcloud", "insert", 0L, new RuntimeException("boom")));

        telemetry.flush();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertTrue(span.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())));
    }

    @Test
    @DisplayName("a db-level command (getMore) yields a span with no collection attribute")
    void dbLevelCommandHasNoCollection() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        MongoCommandTracer tracer = new MongoCommandTracer();
        tracer.attachTracer(telemetry.tracer());

        tracer.commandStarted(started(3, "getMore", new BsonDocument("getMore", new BsonInt64(42L))));
        tracer.commandSucceeded(
                new CommandSucceededEvent(null, 0L, 3, CONN, "prexorcloud", "getMore", new BsonDocument(), 0L));

        telemetry.flush();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("mongodb getMore", spans.get(0).getName());
        assertEquals(null, spans.get(0).getAttributes().get(AttributeKey.stringKey("db.collection.name")));
    }

    @Test
    @DisplayName("monitoring chatter (hello/ping) is not traced")
    void ignoresMonitoringCommands() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        MongoCommandTracer tracer = new MongoCommandTracer();
        tracer.attachTracer(telemetry.tracer());

        tracer.commandStarted(started(4, "hello", new BsonDocument("hello", new BsonInt64(1L))));
        tracer.commandSucceeded(
                new CommandSucceededEvent(null, 0L, 4, CONN, "prexorcloud", "hello", new BsonDocument(), 0L));

        telemetry.flush();
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    @DisplayName("with no tracer attached nothing is recorded")
    void noTracerNoSpans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        MongoCommandTracer tracer = new MongoCommandTracer(); // attachTracer never called

        tracer.commandStarted(started(5, "find", new BsonDocument("find", new BsonString("templates"))));
        tracer.commandSucceeded(
                new CommandSucceededEvent(null, 0L, 5, CONN, "prexorcloud", "find", new BsonDocument(), 0L));

        telemetry.flush();
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
}
