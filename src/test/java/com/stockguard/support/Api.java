package com.stockguard.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP client: requests travel through real sockets, Tomcat threads and the security filter chain. */
public final class Api {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    public Api(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Response get(String path, String token) {
        return send(request(path, token, Map.of()).GET());
    }

    public Response post(String path, String token, Object body) {
        return post(path, token, body, Map.of());
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        return send(request(path, token, headers).POST(publisher(body)));
    }

    public Response put(String path, String token, Object body) {
        return send(request(path, token, Map.of()).PUT(publisher(body)));
    }

    /** Sends the body exactly as given, for testing malformed input. */
    public Response postRaw(String path, String token, String rawBody) {
        return send(request(path, token, Map.of()).POST(HttpRequest.BodyPublishers.ofString(rawBody)));
    }

    private HttpRequest.Builder request(String path, String token, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        return builder;
    }

    private static HttpRequest.BodyPublisher publisher(Object body) {
        return body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    }

    private static Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public record Response(int status, String rawBody, HttpHeaders headers) {

        public JsonNode json() {
            return JSON.readTree(rawBody);
        }

        public String text(String field) {
            JsonNode node = json().get(field);
            return node == null || node.isNull() ? null : node.asString();
        }

        public int number(String field) {
            return json().get(field).asInt();
        }

        public String code() {
            return rawBody == null || rawBody.isEmpty() ? null : text("code");
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return status + " " + rawBody;
        }
    }
}
