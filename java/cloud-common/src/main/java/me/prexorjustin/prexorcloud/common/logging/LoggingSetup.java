package me.prexorjustin.prexorcloud.common.logging;

import java.util.List;

import me.prexorjustin.prexorcloud.common.config.LoggingConfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import org.slf4j.LoggerFactory;

/**
 * Programmatic logging bootstrap driven by {@link LoggingConfig}.
 *
 * <p>
 * Must be called early in startup, before any log output. Performs a full
 * context reset — any messages logged before {@link #configure(LoggingConfig)}
 * will be discarded.
 * </p>
 */
public final class LoggingSetup {

    private static final List<String> QUIET_LOGGERS = List.of("io.grpc", "io.netty", "io.javalin", "org.eclipse.jetty");

    private LoggingSetup() {}

    public static void configure(LoggingConfig config) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        Encoder<ILoggingEvent> encoder =
                switch (config.format()) {
                    case HUMAN -> createHumanEncoder(context);
                    case JSON -> createJsonEncoder(context);
                };

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("CONSOLE");
        appender.setEncoder(encoder);
        appender.start();

        ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(config.level(), Level.INFO));
        root.addAppender(appender);

        QUIET_LOGGERS.forEach(name -> context.getLogger(name).setLevel(Level.WARN));
    }

    private static Encoder<ILoggingEvent> createHumanEncoder(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{0}) - %msg%n");
        encoder.start();
        return encoder;
    }

    private static Encoder<ILoggingEvent> createJsonEncoder(LoggerContext context) {
        JsonLogEncoder encoder = new JsonLogEncoder();
        encoder.setContext(context);
        encoder.start();
        return encoder;
    }
}
