package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.bson.BsonValue;

/**
 * OpenTelemetry {@link CommandListener} for the MongoDB driver (northstar-plan Track D.1 — manual
 * per-library instrumentation, no Javaagent). Emits one CLIENT span per command, parented to the
 * caller's current context so a query nests under the HTTP/domain span that issued it.
 *
 * <p>The listener is registered on the {@code MongoClientSettings} when the client is built, which
 * in bootstrap happens <em>before</em> the telemetry SDK exists. It therefore starts with no tracer
 * and is re-pointed via {@link #attachTracer(Tracer)} once telemetry is up — and only when telemetry
 * is actually enabled. Until then (and whenever tracing is off) {@link #commandStarted} returns on a
 * single volatile read and no spans are created, so the listener is effectively free.
 *
 * <p>For the synchronous driver every {@code commandStarted} is followed by exactly one
 * {@code commandSucceeded} or {@code commandFailed} on the same calling thread, so the in-flight map
 * is correlated by request id and drains deterministically.
 */
public final class MongoCommandTracer implements CommandListener {

    /** Driver monitoring chatter we don't want a span per occurrence of. */
    private static final Set<String> IGNORED_COMMANDS = Set.of(
            "hello",
            "ismaster",
            "ping",
            "buildinfo",
            "getlasterror",
            "endsessions",
            "killcursors",
            "saslstart",
            "saslcontinue",
            "authenticate");

    private final ConcurrentHashMap<Integer, Span> inFlight = new ConcurrentHashMap<>();
    private volatile Tracer tracer;
    private volatile boolean enabled;

    /**
     * Point the listener at a live tracer. Pass {@code null} (or simply never call this) to keep
     * instrumentation off. Called once during bootstrap after the telemetry SDK is built.
     */
    public void attachTracer(Tracer tracer) {
        this.tracer = tracer;
        this.enabled = tracer != null;
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        Tracer t = tracer;
        if (!enabled || t == null) {
            return;
        }
        String command = event.getCommandName();
        if (command == null || IGNORED_COMMANDS.contains(command.toLowerCase(Locale.ROOT))) {
            return;
        }
        String collection = collectionName(event);
        String spanName = collection == null ? "mongodb " + command : "mongodb " + command + " " + collection;
        Span span = t.spanBuilder(spanName)
                .setParent(Context.current())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "mongodb")
                .setAttribute("db.operation", command)
                .setAttribute("db.namespace", event.getDatabaseName())
                .startSpan();
        if (collection != null) {
            span.setAttribute("db.collection.name", collection);
        }
        inFlight.put(event.getRequestId(), span);
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        Span span = inFlight.remove(event.getRequestId());
        if (span != null) {
            span.end();
        }
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        Span span = inFlight.remove(event.getRequestId());
        if (span == null) {
            return;
        }
        Throwable cause = event.getThrowable();
        span.setStatus(StatusCode.ERROR, cause == null ? "" : String.valueOf(cause.getMessage()));
        if (cause != null) {
            span.recordException(cause);
        }
        span.end();
    }

    /**
     * The command document's field named after the command holds the target collection for most
     * CRUD commands ({@code {"find": "templates", ...}}). Returns {@code null} for database-level
     * commands (e.g. {@code getMore}, whose value is a cursor id) so those spans stay un-scoped.
     */
    private static String collectionName(CommandStartedEvent event) {
        try {
            BsonValue value = event.getCommand().get(event.getCommandName());
            return value != null && value.isString() ? value.asString().getValue() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
