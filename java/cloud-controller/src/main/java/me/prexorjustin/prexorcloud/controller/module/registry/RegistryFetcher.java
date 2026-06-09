package me.prexorjustin.prexorcloud.controller.module.registry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP GET seam for registry index + artifact fetches. Abstracted to an
 * interface so {@link ModuleRegistryClient} can be unit-tested with an in-memory
 * fetcher and so the only place that touches the network is small and auditable.
 */
@FunctionalInterface
public interface RegistryFetcher {

    /** GET the bytes at {@code uri}. Implementations must reject non-http(s) schemes. */
    byte[] get(URI uri) throws IOException;

    /** Production fetcher: java.net.http with sane timeouts and an http(s)-only guard. */
    static RegistryFetcher httpDefault() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new IOException("refusing to fetch non-http(s) URL: " + uri);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json, application/octet-stream, */*")
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted fetching " + uri, e);
            }
        };
    }
}
