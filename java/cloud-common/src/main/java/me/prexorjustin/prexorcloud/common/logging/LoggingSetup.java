package me.prexorjustin.prexorcloud.common.logging;

import java.util.List;

import me.prexorjustin.prexorcloud.common.config.LoggingConfig;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
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

    // Third-party libraries that log verbosely at INFO. Silenced to WARN so the console
    // shows our own lifecycle logs, not library chatter:
    //   - org.apache.ratis     : dumps full Raft config + every election/role transition
    //   - org.mongodb.driver   : huge MongoClientSettings dump + cluster monitor reconnect spam
    //   - io.lettuce           : Redis client connection chatter
    // We surface the events that matter (Raft leader, datastore connect, bootstrap) ourselves.
    private static final List<String> QUIET_LOGGERS = List.of(
            "io.grpc",
            "io.netty",
            "io.javalin",
            "org.eclipse.jetty",
            "org.apache.ratis",
            "org.mongodb.driver",
            "io.lettuce");

    // Default directory for the rolling log file, relative to the process working dir.
    // Overridable with -Dprexorcloud.log.dir=/var/log/prexorcloud.
    private static final String LOG_DIR_PROPERTY = "prexorcloud.log.dir";
    private static final String DEFAULT_LOG_DIR = "logs";

    private LoggingSetup() {}

    /**
     * Configure console-only logging. Prefer {@link #configure(LoggingConfig, String)} so a rolling
     * file appender is installed too — see the note there about the context reset.
     */
    public static void configure(LoggingConfig config) {
        configure(config, null);
    }

    /**
     * Configure logging with a console appender and, when {@code componentName} is non-null, a
     * rolling file appender writing to {@code <log-dir>/<componentName>.log}.
     *
     * <p>{@code context.reset()} wipes every appender declared in {@code logback.xml} (including the
     * {@code FILE} one), so the file appender MUST be rebuilt here programmatically — otherwise the
     * configured log file is created but never written to.</p>
     */
    public static void configure(LoggingConfig config, String componentName) {
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

        if (componentName != null && !componentName.isBlank()) {
            root.addAppender(createFileAppender(context, config, componentName));
        }

        QUIET_LOGGERS.forEach(name -> context.getLogger(name).setLevel(Level.WARN));
    }

    private static RollingFileAppender<ILoggingEvent> createFileAppender(
            LoggerContext context, LoggingConfig config, String componentName) {
        String dir = System.getProperty(LOG_DIR_PROPERTY, DEFAULT_LOG_DIR);

        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setContext(context);
        fileAppender.setName("FILE");
        fileAppender.setFile(dir + "/" + componentName + ".log");

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(fileAppender);
        policy.setFileNamePattern(dir + "/" + componentName + ".%d{yyyy-MM-dd}.%i.log.gz");
        policy.setMaxFileSize(FileSize.valueOf("50MB"));
        policy.setMaxHistory(30);
        policy.setTotalSizeCap(FileSize.valueOf("500MB"));
        policy.start();
        fileAppender.setRollingPolicy(policy);

        // Files never get ANSI colour, so even HUMAN format uses a plain pattern (JSON stays JSON).
        Encoder<ILoggingEvent> encoder =
                switch (config.format()) {
                    case HUMAN -> createFileEncoder(context);
                    case JSON -> createJsonEncoder(context);
                };
        fileAppender.setEncoder(encoder);
        fileAppender.start();
        return fileAppender;
    }

    private static Encoder<ILoggingEvent> createFileEncoder(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();
        return encoder;
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
