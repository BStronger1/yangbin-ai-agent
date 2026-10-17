package com.yangbin.yangbinaiagent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Only server-configured endpoints; credentials and upstream errors never enter reports. */
final class AmapWalking {
    record Route(long distanceMeters, long durationSeconds) {}
    private final ObjectMapper json;
    private final String key, base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    AmapWalking(ObjectMapper json, String key, String base) {
        this.json = json; this.key = key == null ? "" : key.strip(); this.base = base.replaceAll("/+$", "");
    }
    boolean configured() { return !key.isEmpty(); }
    static String coordinates(PlanningEvidence.Place p) {
        return String.format(Locale.ROOT, "%.6f,%.6f", p.longitude(), p.latitude());
    }
    Route fetch(PlanningEvidence.Place from, PlanningEvidence.Place to, long timeoutMillis) throws Exception {
        String query = "?origin=" + coordinates(from) + "&destination=" + coordinates(to)
            + "&output=JSON&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(base + "/v3/direction/walking" + query))
            .timeout(Duration.ofMillis(timeoutMillis)).GET().build();
        var pending = http.sendAsync(request, ignored -> new BoundedBody());
        try {
            var response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException();
            JsonNode data = json.readTree(response.body());
            if (data == null || !"1".equals(data.path("status").asText())) throw new IllegalStateException();
            var paths = data.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) throw new IllegalStateException();
            var path = paths.get(0);
            long distance = positive(path.path("distance")), duration = positive(path.path("duration"));
            if (distance > 100_000 || duration > 7 * 24 * 3600) throw new IllegalStateException();
            return new Route(distance, duration);
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    private static long positive(JsonNode value) {
        String text = value.asText();
        if (!text.matches("[0-9]{1,8}")) throw new IllegalStateException();
        long result = Long.parseLong(text);
        if (result <= 0) throw new IllegalStateException();
        return result;
    }
    /** Bound both response memory and completion time, including a slowly streaming body. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 512_000) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException()); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new IllegalStateException()); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
