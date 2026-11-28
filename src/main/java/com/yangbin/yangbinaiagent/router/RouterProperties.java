package com.yangbin.yangbinaiagent.router;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

/** Administrator supplied settings. Never stringify tier credentials. */
@Component
@ConfigurationProperties(prefix = "app.router")
public class RouterProperties {
    public static final List<String> BUDGET_MODES = List.of("economy", "balanced", "quality");
    private boolean enabled, shadowOnly;
    private String baseUrl = "http://127.0.0.1:18129", defaultBudgetMode = "balanced";
    private int timeoutMs = 250, circuitFailureThreshold = 3;
    private long circuitCooldownMs = 30000;
    private Map<String,Tier> tiers = new LinkedHashMap<>();
    public RouterProperties() { for (String tier : List.of("local", "flash", "pro")) tiers.put(tier, new Tier()); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isShadowOnly() { return shadowOnly; }
    public void setShadowOnly(boolean value) { shadowOnly = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = normalizeUrl(value); }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int value) { timeoutMs = Math.max(50, Math.min(5000, value)); }
    public String getDefaultBudgetMode() { return defaultBudgetMode; }
    public void setDefaultBudgetMode(String value) { defaultBudgetMode = normalizeBudgetMode(value); }
    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int value) { circuitFailureThreshold = Math.max(1, Math.min(1000, value)); }
    public long getCircuitCooldownMs() { return circuitCooldownMs; }
    public void setCircuitCooldownMs(long value) { circuitCooldownMs = Math.max(1, Math.min(3600000, value)); }
    public Map<String,Tier> getTiers() { return tiers; }
    public void setTiers(Map<String,Tier> value) { tiers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public static String normalizeBudgetMode(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return BUDGET_MODES.contains(normalized) ? normalized : "balanced";
    }
    public static String normalizeUrl(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(value.strip());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String path = Objects.toString(uri.getRawPath(), "").toLowerCase(Locale.ROOT);
            if (path.contains("..") || path.contains("%2e") || path.contains("%2f") || path.contains("\\")) throw new IllegalArgumentException();
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid router HTTP(S) endpoint"); }
    }
    public static class Tier {
        private String model = "", baseUrl = "", apiKey = "";
        public String getModel() { return model; }
        public void setModel(String value) { model = value == null ? "" : value.strip(); }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value == null || value.isBlank() ? "" : normalizeUrl(value); }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value == null ? "" : value; }
        public boolean configured() { return !model.isBlank() && !baseUrl.isBlank(); }
        @Override public String toString() { return "RouterTier[credentials=REDACTED]"; }
    }
}
