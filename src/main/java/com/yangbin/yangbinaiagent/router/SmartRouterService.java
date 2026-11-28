package com.yangbin.yangbinaiagent.router;

import com.yangbin.yangbinaiagent.models.ModelProfileService;
import com.yangbin.yangbinaiagent.models.PrivateChatModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** A best-effort routing hint. Failures never prevent execution of the default model. */
@Service
public class SmartRouterService {
    private static final Logger log = LoggerFactory.getLogger(SmartRouterService.class);
    private final RouterProperties properties;
    private final SmartRouterClient client;
    private final PrivateChatModels privateModels;
    private final Clock clock;
    private final Object state = new Object();
    private int consecutiveFailures;
    private long openUntil;
    private long circuitGeneration;
    private boolean probeInFlight;
    private long totalDecisions, appliedCount, fallbackCount;
    private double totalLatency;
    private final Map<String,Long> tierCounts = new TreeMap<>(), intentCounts = new TreeMap<>(), fallbackReasons = new TreeMap<>();
    @Autowired
    public SmartRouterService(RouterProperties properties, SmartRouterClient client, PrivateChatModels privateModels) {
        this(properties, client, privateModels, Clock.systemUTC());
    }
    public SmartRouterService(RouterProperties properties, SmartRouterClient client, PrivateChatModels privateModels, Clock clock) {
        this.properties = properties; this.client = client; this.privateModels = privateModels; this.clock = clock;
    }
    public record RouteOutcome(ChatModel model, RouterDecision decision, ModelProfileService.Snapshot snapshot) {
        public RouteOutcome(ChatModel model, RouterDecision decision) { this(model, decision, null); }
    }
    public boolean isEnabled() { return properties.isEnabled(); }
    public boolean isShadowOnly() { return properties.isShadowOnly(); }
    public String defaultBudgetMode() { return properties.getDefaultBudgetMode(); }
    public RouteOutcome route(String promptText, String mode, String requestedBudgetMode, ChatModel defaultModel) {
        if (!isEnabled()) return new RouteOutcome(defaultModel, null);
        long started = System.nanoTime();
        String budget = requestedBudgetMode == null || requestedBudgetMode.isBlank()
                ? defaultBudgetMode() : RouterProperties.normalizeBudgetMode(requestedBudgetMode);
        long admission = admit();
        if (admission < 0) return finish(defaultModel, failed("circuit_open", budget, 0), null);
        SmartRouterClient.Response response;
        try {
            response = client.route(promptText, budget);
            success(admission);
        } catch (SmartRouterClient.Failure ex) {
            failure(admission); return finish(defaultModel, failed(safeFailure(ex.reason()), budget, elapsed(started)), null);
        } catch (Exception ex) {
            failure(admission); return finish(defaultModel, failed("network_error", budget, elapsed(started)), null);
        }
        try {
            if ("abstain".equals(response.chosenModel()))
                return finish(defaultModel, decision(response, false, "router_abstain"), null);
            if (isShadowOnly()) return finish(defaultModel, decision(response, false, response.fallbackReason()), null);
            RouterProperties.Tier target = properties.getTiers().get(response.tier());
            if (target == null || !target.configured())
                return finish(defaultModel, decision(response, false, "tier_unconfigured"), null);
            String provider = target.getModel().toLowerCase(Locale.ROOT).startsWith("deepseek") ? "deepseek" : "openai-compatible";
            var configured = new ModelProfileService.Snapshot("router-" + response.tier(), "Smart router / " + response.tier(),
                    provider, target.getBaseUrl(), target.getModel(), "openai-completions");
            // The injected factory applies ModelGovernance, redacts secrets, disables retries and redirects,
            // and permits administrator configured local OpenAI-compatible endpoints.
            ChatModel selected = privateModels.create(new ModelProfileService.Resolved(configured,
                    target.getApiKey().isBlank() ? "router-local-no-key" : target.getApiKey(), true));
            if (selected == null) throw new IllegalStateException();
            var display = new ModelProfileService.Snapshot(configured.profileId(), configured.name(), configured.provider(),
                    "", configured.model(), configured.api(), configured.reasoningEffort());
            return finish(selected, decision(response, true, response.fallbackReason()), display);
        } catch (Exception ex) {
            return finish(defaultModel, decision(response, false, "tier_unconfigured"), null);
        }
    }
    private long admit() {
        synchronized (state) {
            if (openUntil == 0) return circuitGeneration;
            if (clock.millis() < openUntil || probeInFlight) return -1;
            probeInFlight = true;
            return circuitGeneration;
        }
    }
    private void success(long admission) {
        synchronized (state) {
            if (admission != circuitGeneration) return;
            consecutiveFailures = 0; openUntil = 0; probeInFlight = false;
        }
    }
    private void failure(long admission) {
        synchronized (state) {
            if (admission != circuitGeneration) return;
            consecutiveFailures++;
            if (probeInFlight || consecutiveFailures >= properties.getCircuitFailureThreshold()) {
                openUntil = clock.millis() + properties.getCircuitCooldownMs();
                circuitGeneration++;
            }
            probeInFlight = false;
        }
    }
    private RouterDecision failed(String reason, String budget, double latency) {
        return new RouterDecision(true, false, isShadowOnly(), null, null, null, null,
                0, 0, 0, latency, budget, 0, reason, false, Map.of());
    }
    private RouterDecision decision(SmartRouterClient.Response response, boolean applied, String reason) {
        return new RouterDecision(true, applied, isShadowOnly(), response.intent(), response.rawModel(), response.chosenModel(),
                response.tier(), response.routeConfidence(), response.routeConfidence(), response.jointConfidence(),
                response.routerLatencyMs(), response.budgetMode(), response.estimatedSavingPct(), reason,
                response.promoted(), response.candidateProbs());
    }
    private static String safeFailure(String reason) {
        if (Set.of("timeout", "network_error", "invalid_response", "invalid_schema").contains(Objects.toString(reason, ""))) return reason;
        return reason != null && reason.matches("http_status_[1-5][0-9]{2}") ? reason : "network_error";
    }
    private RouteOutcome finish(ChatModel model, RouterDecision decision, ModelProfileService.Snapshot snapshot) {
        synchronized (state) {
            totalDecisions++; totalLatency += decision.routerLatencyMs();
            if (decision.applied()) appliedCount++;
            if (decision.fallbackReason() != null && !"autoregressive_fallback".equals(decision.fallbackReason())) {
                fallbackCount++; fallbackReasons.merge(decision.fallbackReason(), 1L, Long::sum);
            }
            if (decision.tier() != null) tierCounts.merge(decision.tier(), 1L, Long::sum);
            if (decision.intent() != null) intentCounts.merge(decision.intent(), 1L, Long::sum);
        }
        log.info("event=router_decision intent={} chosen_model={} tier={} confidence={} router_latency_ms={} budget_mode={} applied={} shadow_only={} fallback_reason={}",
                decision.intent(), decision.chosenModel(), decision.tier(), decision.confidence(), decision.routerLatencyMs(),
                decision.budgetMode(), decision.applied(), decision.shadowOnly(), decision.fallbackReason());
        return new RouteOutcome(model, decision, snapshot);
    }
    private static double elapsed(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
    public Map<String,Object> status() {
        synchronized (state) {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("enabled", isEnabled()); result.put("shadowOnly", isShadowOnly());
            result.put("defaultBudgetMode", defaultBudgetMode()); result.put("budgetModes", RouterProperties.BUDGET_MODES);
            result.put("circuitOpen", openUntil != 0 && (clock.millis() < openUntil || probeInFlight));
            result.put("totalDecisions", totalDecisions); result.put("appliedCount", appliedCount); result.put("fallbackCount", fallbackCount);
            result.put("avgLatencyMs", totalDecisions == 0 ? 0.0 : totalLatency / totalDecisions);
            result.put("tierCounts", Map.copyOf(tierCounts)); result.put("intentCounts", Map.copyOf(intentCounts));
            result.put("fallbackReasons", Map.copyOf(fallbackReasons));
            return Collections.unmodifiableMap(result);
        }
    }
}
