package me.prexorjustin.prexorcloud.daemon.observability;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import me.prexorjustin.prexorcloud.protocol.DaemonLogRecord;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

/**
 * Logback appender that mirrors each daemon log event up to the controller via
 * {@link DaemonLogPublisher}. Wired into {@code logback.xml} alongside the
 * existing CONSOLE and FILE appenders so disk history is preserved when the
 * controller stream is unavailable.
 *
 * <p>
 * Field lengths are clamped here (not on the controller) so that a chatty
 * daemon cannot inflate its outbound gRPC frames; the limits match the
 * validation thresholds enforced controller-side. Records emitted before the
 * gRPC client is bound or while the stream is disconnected are dropped by the
 * publisher.
 * </p>
 */
public final class DaemonGrpcLogAppender extends AppenderBase<ILoggingEvent> {

    static final int MAX_LOGGER = 256;
    static final int MAX_THREAD = 128;
    static final int MAX_MESSAGE = 8192;
    static final int MAX_THROWABLE = 32_768;

    private static final String APPENDER_NAME = "PREXORCLOUD_DAEMON_GRPC";

    /**
     * Attach a fresh appender to the current Logback root logger. Idempotent: if
     * one is already attached it is replaced. Must be called after
     * {@code LoggingSetup.configure(...)} since that resets the context and
     * detaches all appenders.
     */
    public static DaemonGrpcLogAppender attachToRoot() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        var existing = root.getAppender(APPENDER_NAME);
        if (existing != null) {
            root.detachAppender(existing);
            existing.stop();
        }

        DaemonGrpcLogAppender appender = new DaemonGrpcLogAppender();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent event) {
        DaemonLogPublisher publisher = DaemonLogPublisher.get();
        if (publisher == null) return;

        String level = event.getLevel() == null ? "INFO" : event.getLevel().toString();
        String logger = clamp(event.getLoggerName(), MAX_LOGGER);
        String thread = clamp(event.getThreadName(), MAX_THREAD);
        String message = clamp(event.getFormattedMessage(), MAX_MESSAGE);
        String throwable = clamp(formatThrowable(event.getThrowableProxy()), MAX_THROWABLE);

        DaemonLogRecord.Builder builder = DaemonLogRecord.newBuilder()
                .setTimestampMs(event.getTimeStamp())
                .setLevel(level)
                .setLogger(logger == null ? "" : logger)
                .setThread(thread == null ? "" : thread)
                .setMessage(message == null ? "" : message);
        if (throwable != null) builder.setThrowable(throwable);
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) builder.putAllMdc(mdc);

        publisher.publish(builder.build());
    }

    private static String clamp(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
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
