package me.prexorjustin.prexorcloud.controller.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class RingBufferLogAppenderTest {

    private LoggerContext context;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        var existing = root.getAppender("PREXORCLOUD_RING");
        if (existing != null) {
            root.detachAppender(existing);
            existing.stop();
        }
        MDC.clear();
    }

    @Test
    void capturesRealLogbackEventsWithFormattedMessageAndLevel() {
        var buffer = new ControllerLogBuffer(50);
        RingBufferLogAppender.attachToRoot(buffer);

        var logger = LoggerFactory.getLogger("test.alpha");
        logger.info("hello {}", "world");
        logger.warn("boom");

        List<LogRecord> recent = buffer.recent(ControllerLogBuffer.LogFilter.accept(), 10);
        assertTrue(recent.size() >= 2, "expected at least two captured records");

        LogRecord helloRecord = recent.stream()
                .filter(r -> "hello world".equals(r.message()))
                .findFirst()
                .orElseThrow();
        assertEquals("INFO", helloRecord.level());
        assertEquals("test.alpha", helloRecord.logger());

        LogRecord boomRecord = recent.stream()
                .filter(r -> "boom".equals(r.message()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARN", boomRecord.level());
    }

    @Test
    void capturesThrowableStackTrace() {
        var buffer = new ControllerLogBuffer(10);
        RingBufferLogAppender.attachToRoot(buffer);

        var logger = LoggerFactory.getLogger("test.beta");
        logger.error("explosion", new IllegalStateException("kaboom"));

        var record = buffer.recent(ControllerLogBuffer.LogFilter.accept(), 10).stream()
                .filter(r -> "explosion".equals(r.message()))
                .findFirst()
                .orElseThrow();
        assertNotNull(record.throwable());
        assertTrue(record.throwable().contains("IllegalStateException"));
        assertTrue(record.throwable().contains("kaboom"));
    }

    @Test
    void capturesMdcSnapshot() {
        var buffer = new ControllerLogBuffer(10);
        RingBufferLogAppender.attachToRoot(buffer);

        MDC.put("requestId", "req-42");
        try {
            LoggerFactory.getLogger("test.gamma").info("with-mdc");
        } finally {
            MDC.remove("requestId");
        }

        var record = buffer.recent(ControllerLogBuffer.LogFilter.accept(), 10).stream()
                .filter(r -> "with-mdc".equals(r.message()))
                .findFirst()
                .orElseThrow();
        assertEquals("req-42", record.mdc().get("requestId"));
    }

    @Test
    void attachToRootIsIdempotent() {
        var buffer = new ControllerLogBuffer(10);
        RingBufferLogAppender.attachToRoot(buffer);
        RingBufferLogAppender.attachToRoot(buffer);

        LoggerFactory.getLogger("test.delta").info("once");

        long matches = buffer.recent(ControllerLogBuffer.LogFilter.accept(), 50).stream()
                .filter(r -> "once".equals(r.message()))
                .count();
        assertEquals(1L, matches, "double-attach must not double-publish");
    }
}
