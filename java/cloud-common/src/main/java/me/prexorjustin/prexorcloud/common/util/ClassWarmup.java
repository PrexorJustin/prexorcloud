package me.prexorjustin.prexorcloud.common.util;

/**
 * Eagerly loads classes that are normally only loaded on error/shutdown code
 * paths.
 *
 * <p>
 * On Windows, first-time class loading from a large shadow JAR can
 * intermittently fail when the JVM's ZIP reader contends with real-time
 * antivirus scanning. Classes used only in error handlers (gRPC stream
 * cancellation, logback exception rendering) are never loaded during normal
 * operation, making them vulnerable to this race.
 *
 * <p>
 * Calling {@link #loadErrorPathClasses()} at startup forces the JVM to parse
 * and cache these classes while the application is still initialising, so they
 * are already resident in memory when an error condition triggers them later.
 */
public final class ClassWarmup {

    private static final System.Logger LOGGER = System.getLogger(ClassWarmup.class.getName());

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 50L;

    private ClassWarmup() {}

    /**
     * Loads classes that live on error/shutdown code paths. Safe to call multiple
     * times; failures are silently ignored.
     */
    public static void loadErrorPathClasses() {
        // gRPC internal classes used during stream cancellation and channel panic
        loadQuietly("io.grpc.internal.NoopClientStream");
        loadQuietly("io.grpc.internal.RetriableStream");
        loadQuietly("io.grpc.internal.ManagedChannelImpl");
        loadQuietly("io.grpc.internal.ManagedChannelImpl$LbHelperImpl");
        loadQuietly("io.grpc.internal.ManagedChannelImpl$SubchannelImpl");
        loadQuietly("io.grpc.internal.DelayedClientCall");
        loadQuietly("io.grpc.internal.PickFirstLoadBalancer");
        loadQuietly("io.grpc.internal.AutoConfiguredLoadBalancerFactory$AutoConfiguredLoadBalancer");

        // Logback exception rendering (ThrowableProxy, StackTraceElementProxy, etc.)
        // Must use explicit loading — logger.trace() is a no-op when TRACE is disabled,
        // so ThrowableProxy never gets loaded via that path.
        loadQuietly("ch.qos.logback.classic.spi.ThrowableProxy");
        loadQuietly("ch.qos.logback.classic.spi.ThrowableProxyUtil");
        loadQuietly("ch.qos.logback.classic.spi.StackTraceElementProxy");
        loadQuietly("ch.qos.logback.classic.spi.LoggingEvent");
        loadQuietly("ch.qos.logback.classic.spi.LoggingEventVO");

        LOGGER.log(System.Logger.Level.DEBUG, "Error-path classes pre-loaded");
    }

    private static void loadQuietly(String className) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Class.forName(className, true, ClassWarmup.class.getClassLoader());
                return;
            } catch (ClassNotFoundException ignored) {
                // Class genuinely not on the classpath (e.g. controller doesn't use gRPC client
                // classes).
                return;
            } catch (Throwable e) {
                if (!retryOrWarn(className, attempt, e)) return;
            }
        }
    }

    /**
     * Sleeps before the next attempt, or logs a warning on final failure.
     *
     * @return {@code true} if a retry should be attempted, {@code false} if the
     *         caller should return
     */
    private static boolean retryOrWarn(String className, int attempt, Throwable e) {
        if (attempt < MAX_ATTEMPTS) {
            try {
                // On Windows, AV scanning can briefly lock the shadow JAR —
                // back off linearly to give the scanner time to release the file.
                Thread.sleep(java.time.Duration.ofMillis(RETRY_BASE_MS * attempt));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Failed to pre-load error-path class {0} after {1} attempts: {2}",
                className,
                MAX_ATTEMPTS,
                e);
        return false;
    }
}
