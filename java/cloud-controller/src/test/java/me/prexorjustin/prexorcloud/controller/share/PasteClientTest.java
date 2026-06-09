package me.prexorjustin.prexorcloud.controller.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.ShareConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class PasteClientTest {

    private static final ShareConfig CONFIG = new ShareConfig(true, "https://pste.dev", null, "1d", true, false);
    private static final PasteOptions OPTS = new PasteOptions("1d", "text", false, "idem-1");

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        when(r.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void parses201Success() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String body = "{\"id\":\"abc\",\"url\":\"https://pste.dev/abc\",\"rawUrl\":\"https://pste.dev/abc/raw\","
                + "\"deleteToken\":\"tok\",\"expiresAt\":\"2026-05-16T10:00:00Z\","
                + "\"language\":\"text\",\"burnAfterRead\":false}";
        HttpResponse<String> r = response(201, body, Map.of());
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(r);

        PasteClient client = new PasteClient(CONFIG, http, new ObjectMapper());
        PasteResult result = client.create("hello", OPTS);
        assertEquals("https://pste.dev/abc", result.url());
        assertEquals("tok", result.deleteToken());
        assertNotNull(result.expiresAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void map413ToPasteException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r = response(413, "too big", Map.of());
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(r);
        PasteClient client = new PasteClient(CONFIG, http, new ObjectMapper());

        PasteException ex = assertThrows(PasteException.class, () -> client.create("payload", OPTS));
        assertEquals(413, ex.upstreamStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void map429SurfacesRetryAfter() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r = response(429, "slow down", Map.of("retry-after", List.of("60")));
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(r);
        PasteClient client = new PasteClient(CONFIG, http, new ObjectMapper());

        PasteException ex = assertThrows(PasteException.class, () -> client.create("payload", OPTS));
        assertEquals("60", ex.retryAfter());
        assertTrue(ex.getMessage().contains("60"), ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutMapsToPasteException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("boom"));
        PasteClient client = new PasteClient(CONFIG, http, new ObjectMapper());

        assertThrows(PasteException.class, () -> client.create("payload", OPTS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void networkIoErrorMapsToPasteException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("connection refused"));
        PasteClient client = new PasteClient(CONFIG, http, new ObjectMapper());

        assertThrows(PasteException.class, () -> client.create("payload", OPTS));
    }
}
