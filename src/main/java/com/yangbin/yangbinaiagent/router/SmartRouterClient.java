package com.yangbin.yangbinaiagent.router;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** No retries, redirects, response-body logs, or unbounded response buffering. */
@Component
public class SmartRouterClient implements AutoCloseable {
    public static final List<String> MODELS = List.of("Qwen/Qwen3-4B", "deepseek-v4.1-flash-1017", "deepseek-v4-pro-0813", "abstain");
    public static final Set<String> INTENTS = Set.of("coding_debug_repair", "general_chat_qa", "knowledge_rag_query",
            "local_file_workspace_op", "memory_manage_recall", "out_of_scope_or_unsafe", "task_plan_clarify",
            "tool_mcp_a2a_orchestration", "writing_blog_edit");
    private static final int MAX_BODY = 64 * 1024;
    private final RouterProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    public SmartRouterClient(RouterProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Response(String intent, String rawModel, String chosenModel, String tier, double routeConfidence,
            double jointConfidence, double routerLatencyMs, String budgetMode, double estimatedSavingPct,
            boolean promoted, Map<String,Double> candidateProbs, String fallbackReason) {}
    public static class Failure extends Exception {
        private final String reason;
        public Failure(String reason) { super(reason, null, false, false); this.reason = reason; }
        public String reason() { return reason; }
    }
    public Response route(String prompt, String budgetMode) throws Failure {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        var receivedStatus = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var payload = Map.of("request", prompt, "tools", List.of(), "budget_mode", budgetMode);
            var request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/v1/route"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs())).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            pending = http.sendAsync(request, info -> {
                receivedStatus.set(info.statusCode());
                return info.statusCode() == 200 ? new LimitedBody() : new StatusOnlyBody();
            });
            // get() bounds the complete response, including a body that stalls after headers.
            var response = pending.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new Failure("http_status_" + response.statusCode());
            JsonNode root;
            try { root = json.readTree(response.body()); }
            catch (Exception ex) { throw new Failure("invalid_response"); }
            return validate(root, budgetMode);
        } catch (Failure ex) { throw ex; }
        catch (TimeoutException ex) { throw new Failure(receivedStatus.get() != 0 && receivedStatus.get() != 200
                ? "http_status_" + receivedStatus.get() : "timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failure("network_error"); }
        catch (ExecutionException ex) {
            if (receivedStatus.get() != 0 && receivedStatus.get() != 200) throw new Failure("http_status_" + receivedStatus.get());
            Throwable cause = ex.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof HttpTimeoutException) throw new Failure("timeout");
            if (cause instanceof BodyTooLarge) throw new Failure("invalid_response");
            throw new Failure("network_error");
        } catch (Exception ex) { throw new Failure("network_error"); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }
    private Response validate(JsonNode root, String requestedBudgetMode) throws Failure {
        if (root == null || !root.isObject()) throw new Failure("invalid_schema");
        String intent = string(root, "intent"), raw = string(root, "raw_model_id"), chosen = string(root, "chosen_model");
        if (!INTENTS.contains(intent) || !MODELS.contains(raw) || !MODELS.contains(chosen)) throw new Failure("invalid_schema");
        String tier = string(root, "tier"), budget = string(root, "budget_mode");
        if (!tierFor(chosen).equals(tier) || !budget.equals(requestedBudgetMode)) throw new Failure("invalid_schema");
        double route = number(root, "route_confidence", 0, 1), joint = number(root, "joint_confidence", 0, 1);
        double latency = number(root, "router_latency_ms", 0, Double.MAX_VALUE), saving = number(root, "estimated_saving_pct", 0, 100);
        if (!root.path("promoted").isBoolean()) throw new Failure("invalid_schema");
        boolean promoted = root.path("promoted").booleanValue();
        double threshold = switch (budget) { case "economy" -> 0; case "quality" -> .85; default -> .5; };
        int rawIndex = MODELS.indexOf(raw);
        String expected = !raw.equals("abstain") && joint < threshold ? MODELS.get(Math.min(2, rawIndex + 1)) : raw;
        if (!chosen.equals(expected) || promoted != !raw.equals(chosen)) throw new Failure("invalid_schema");
        JsonNode probabilities = root.path("candidate_probs");
        if (!probabilities.isObject() || probabilities.size() != MODELS.size()) throw new Failure("invalid_schema");
        Map<String,Double> values = new LinkedHashMap<>();
        double sum = 0;
        for (String candidate : MODELS) { double value = number(probabilities, candidate, 0, 1); values.put(candidate, value); sum += value; }
        if (Math.abs(sum - 1) > .001) throw new Failure("invalid_schema");
        // Remote free text never crosses the content-free logging/history boundary.
        if (!root.path("fallback_reason").isNull() && !root.path("fallback_reason").isMissingNode()
                && !root.path("fallback_reason").isTextual()) throw new Failure("invalid_schema");
        String fallback = root.path("fallback_reason").isNull() || root.path("fallback_reason").isMissingNode()
                || root.path("fallback_reason").asText("").isEmpty() ? null : "autoregressive_fallback";
        return new Response(intent, raw, chosen, tier, route, joint, latency, budget, saving, promoted, Map.copyOf(values), fallback);
    }
    private static String string(JsonNode root, String key) throws Failure {
        JsonNode node = root.path(key);
        if (!node.isTextual() || node.textValue().isBlank()) throw new Failure("invalid_schema");
        return node.textValue();
    }
    private static double number(JsonNode root, String key, double min, double max) throws Failure {
        JsonNode node = root.path(key);
        if (!node.isNumber()) throw new Failure("invalid_schema");
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw new Failure("invalid_schema");
        return value;
    }
    public static String tierFor(String model) {
        return switch (model) { case "Qwen/Qwen3-4B" -> "local"; case "deepseek-v4.1-flash-1017" -> "flash";
            case "deepseek-v4-pro-0813" -> "pro"; default -> "abstain"; };
    }
    private static final class BodyTooLarge extends RuntimeException {}
    @jakarta.annotation.PreDestroy
    @Override public void close() { http.shutdownNow(); }
    /** Status is sufficient for a rejected response; never consume an error page. */
    private static final class StatusOnlyBody implements HttpResponse.BodySubscriber<byte[]> {
        public CompletionStage<byte[]> getBody() { return CompletableFuture.completedFuture(new byte[0]); }
        public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        public void onNext(List<ByteBuffer> items) {}
        public void onError(Throwable error) {}
        public void onComplete() {}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new BodyTooLarge()); return;
                }
                byte[] part = new byte[item.remaining()]; item.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
