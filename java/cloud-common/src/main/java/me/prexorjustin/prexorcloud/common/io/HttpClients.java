package me.prexorjustin.prexorcloud.common.io;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standard {@link HttpClient} configurations for PrexorCloud.
 *
 * <p>
 * All outbound HTTP from the cloud (controller, daemon, modules, plugins)
 * should obtain a client from here so connect timeout, redirect policy, and
 * the executor pool are uniform.
 * </p>
 */
public final class HttpClients {

    private HttpClients() {}

    private static final Executor SHARED_EXECUTOR = Executors.newCachedThreadPool(daemonThreadFactory("prexor-http-"));

    private static final HttpClient DEFAULT = defaults().build();

    /**
     * Pre-configured builder. Callers that need additional customization
     * (authenticator, SSL context, proxy) start here.
     */
    public static HttpClient.Builder defaults() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(SHARED_EXECUTOR);
    }

    /**
     * Process-wide singleton built from {@link #defaults()}. Use for callers
     * with no custom needs to avoid creating per-component pools.
     */
    public static HttpClient defaultClient() {
        return DEFAULT;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicLong seq = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
