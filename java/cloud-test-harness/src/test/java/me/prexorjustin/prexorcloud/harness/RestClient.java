package me.prexorjustin.prexorcloud.harness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Thin REST client for testing the controller's HTTP API.
 * Auto-attaches JWT Bearer token to all requests.
 */
public final class RestClient {

    private final String baseUrl;
    private String token;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public RestClient(String baseUrl, String jwtToken) {
        this.baseUrl = baseUrl;
        this.token = jwtToken;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    // --- Raw HTTP methods ---

    public Response get(String path) throws Exception {
        var request = buildRequest(path).GET().build();
        return execute(request);
    }

    public Response post(String path, Object body) throws Exception {
        String json = body instanceof String s ? s : mapper.writeValueAsString(body);
        var request = buildRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    public Response postEmpty(String path) throws Exception {
        var request =
                buildRequest(path).POST(HttpRequest.BodyPublishers.noBody()).build();
        return execute(request);
    }

    public Response postMultipartFile(String path, String fieldName, Path file) throws Exception {
        return postMultipart(
                path,
                List.of(new FilePart(
                        fieldName,
                        file.getFileName().toString(),
                        "application/java-archive",
                        Files.readAllBytes(file))));
    }

    public Response postMultipart(String path, List<FilePart> parts) throws Exception {
        String boundary = "----PrexorBoundary" + UUID.randomUUID();
        var body = new ArrayList<byte[]>();
        for (FilePart part : parts) {
            body.add(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.add(("Content-Disposition: form-data; name=\"" + part.fieldName() + "\"; filename=\"" + part.fileName()
                            + "\"\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.add(("Content-Type: " + part.contentType() + "\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.add(part.content());
            body.add("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        body.add(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var request = buildRequest(path)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(body))
                .build();
        return execute(request);
    }

    public record FilePart(String fieldName, String fileName, String contentType, byte[] content) {}

    public Response patch(String path, Object body) throws Exception {
        String json = body instanceof String s ? s : mapper.writeValueAsString(body);
        var request = buildRequest(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    public Response put(String path, Object body) throws Exception {
        String json = body instanceof String s ? s : mapper.writeValueAsString(body);
        var request = buildRequest(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    public Response putRaw(String path, String contentType, String body) throws Exception {
        var request = buildRequest(path)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request);
    }

    public Response delete(String path) throws Exception {
        var request = buildRequest(path).DELETE().build();
        return execute(request);
    }

    /**
     * POST without JWT auth (for unauthenticated endpoint tests).
     */
    public Response postNoAuth(String path, Object body) throws Exception {
        String json = body instanceof String s ? s : mapper.writeValueAsString(body);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    public Response getNoAuth(String path) throws Exception {
        var request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return execute(request);
    }

    /**
     * POST with a custom Authorization header (e.g., plugin token).
     */
    public Response postWithToken(String path, String bearerToken, Object body) throws Exception {
        return postWithToken(path, bearerToken, body, Map.of());
    }

    public Response postWithToken(String path, String bearerToken, Object body, Map<String, String> headers)
            throws Exception {
        String json = body instanceof String s ? s : mapper.writeValueAsString(body);
        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(requestBuilder::header);
        return execute(requestBuilder.build());
    }

    public Response getWithToken(String path, String bearerToken) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return execute(request);
    }

    // --- Builder ---

    private HttpRequest.Builder buildRequest(String path) {
        var builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Response execute(HttpRequest request) throws Exception {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), mapper);
    }

    // --- Response wrapper ---

    public record Response(int status, String body, ObjectMapper mapper) {

        public JsonNode json() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse JSON: " + body, e);
            }
        }

        public <T> T as(Class<T> type) {
            try {
                return mapper.readValue(body, type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize: " + body, e);
            }
        }

        public <T> T as(TypeReference<T> type) {
            try {
                return mapper.readValue(body, type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize: " + body, e);
            }
        }

        public List<Map<String, Object>> asList() {
            return as(new TypeReference<>() {});
        }

        public Map<String, Object> asMap() {
            return as(new TypeReference<>() {});
        }

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        public Response assertStatus(int expected) {
            if (status != expected) {
                throw new AssertionError("Expected status " + expected + " but got " + status + ": " + body);
            }
            return this;
        }

        public Response assertOk() {
            if (!isOk()) {
                throw new AssertionError("Expected 2xx but got " + status + ": " + body);
            }
            return this;
        }
    }
}
