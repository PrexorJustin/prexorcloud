package me.prexorjustin.prexorcloud.controller.observability;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

/**
 * Logback appender that mirrors every {@link ILoggingEvent} into a
 * {@link ControllerLogBuffer}. Operator-facing surfaces read from the buffer
 * instead of tailing on-disk log files, which keeps `prexorctl logs` working
 * regardless of how the operator routes stdout (console, file, syslog, k8s).
 */
public final class RingBufferLogAppender extends AppenderBase<ILoggingEvent> {

    private final ControllerLogBuffer buffer;

    public RingBufferLogAppender(ControllerLogBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Attach a fresh appender to the current Logback root logger. Idempotent: if
     * one is already attached it is replaced so live-reload of configuration does
     * not double-publish events.
     */
    public static RingBufferLogAppender attachToRoot(ControllerLogBuffer buffer) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // Replace any previously attached instance to keep the appender chain stable
        // across reconfigure() / reset() calls.
        var existing = root.getAppender("PREXORCLOUD_RING");
        if (existing != null) {
            root.detachAppender(existing);
            existing.stop();
        }

        RingBufferLogAppender appender = new RingBufferLogAppender(buffer);
        appender.setContext(context);
        appender.setName("PREXORCLOUD_RING");
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        Map<String, String> mdcCopy = (mdc == null || mdc.isEmpty()) ? Map.of() : new HashMap<>(mdc);
        String throwable = formatThrowable(event.getThrowableProxy());
        buffer.append(
                event.getTimeStamp(),
                event.getLevel() == null ? "INFO" : event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                throwable,
                mdcCopy);
    }

    private static String formatThrowable(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) return null;
        StringWriter writer = new StringWriter();
        try (PrintWriter out = new PrintWriter(writer)) {
            out.println(ThrowableProxyUtil.asString(throwableProxy));
        }
        String formatted = writer.toString();
        return formatted.isBlank() ? null : formatted;
    }
}
