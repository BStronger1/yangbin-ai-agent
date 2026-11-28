package com.yangbin.yangbinaiagent.router;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterDecision(boolean enabled, boolean applied, boolean shadowOnly, String intent,
        String rawModel, String chosenModel, String tier, double confidence, double routeConfidence,
        double jointConfidence, double routerLatencyMs, String budgetMode, double estimatedSavingPct,
        String fallbackReason, boolean promoted, Map<String,Double> candidateProbs) {
    public RouterDecision { candidateProbs = candidateProbs == null ? Map.of() : Map.copyOf(candidateProbs); }
}
