package com.yangbin.yangbinaiagent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class PlanRouteService {
    public static final String VERSION = "walking-routes-v1";
    public record Leg(int fromStop, int toStop, String fromName, String toName, String status,
                      Long distanceMeters, Long durationSeconds, Long startGapSeconds,
                      Long remainingAfterWalkingSeconds, String timing, String message) {}
    public record Report(String version, String planId, int planVersion, String checkedAt,
                         String evidenceFetchedAt, String mode, String requestedTransport,
                         String status, int upstreamRequests, List<Leg> legs, List<String> notices) {}
    private final boolean enabled;
    private final AmapWalking walking;

    public PlanRouteService(ObjectMapper json,
        @Value("${app.planning.live-enabled:false}") boolean enabled,
        @Value("${app.planning.amap-key:${AMAP_API_KEY:}}") String key,
        @Value("${app.planning.amap-url:https://restapi.amap.com}") String base) {
        this.enabled = enabled; walking = new AmapWalking(json, key, base);
    }
    /** Caller must resolve ownership with PlannerService.get before any provider call. No disk writes. */
    public Report inspect(PlannerService.Plan plan) {
        String checkedAt = Instant.now().toString();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        var stops = plan.itinerary().stops();
        if (stops == null || stops.size() > 12) throw new IllegalArgumentException("方案站点数量无效");
        var evidence = plan.evidence();
        var places = new HashMap<String, PlanningEvidence.Place>();
        var duplicates = new HashSet<String>();
        if (evidence != null && evidence.places() != null) for (var place : evidence.places()) {
            if (place != null && place.id() != null && places.putIfAbsent(place.id(), place) != null)
                duplicates.add(place.id());
        }
        duplicates.forEach(places::remove);
        String transport = plan.requirements().transport();
        boolean timingApplies = "步行".equals(transport == null ? "" : transport.strip());
        var legs = new ArrayList<Leg>();
        int calls = 0;
        for (int n = 1; n < stops.size(); n++) {
            var a = stops.get(n - 1); var b = stops.get(n);
            var from = places.get(a.placeId()); var to = places.get(b.placeId());
            String fromName = from == null ? "第 " + n + " 站（无唯一地点）" : from.name();
            String toName = to == null ? "第 " + (n + 1) + " 站（无唯一地点）" : to.name();
            Long gap = gap(a.time(), b.time());
            String status = null, message = null;
            if (!enabled) { status = "DISABLED"; message = "实时查询尚未启用。"; }
            else if (!walking.configured()) { status = "NOT_CONFIGURED"; message = "服务端尚未配置高德 Web 服务。"; }
            else if (!valid(from) || !valid(to)) {
                status = "MISSING_COORDINATES"; message = "需要两个已保存的高德地点及有效 GCJ-02 坐标；不猜测或转换旧坐标。";
            } else if (from.id().equals(to.id())) {
                status = "SAME_PLACE"; message = "两站引用同一地点，无需查询站间步行；场馆内部移动仍需自行确认。";
            }
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
            if (status == null && (remainingMillis < 100 || Thread.currentThread().isInterrupted())) {
                status = "TIME_BUDGET_EXHAUSTED"; message = "本次查询时间已用尽，该段未查询，可稍后重试。";
            }
            if (status != null) {
                legs.add(new Leg(n - 1, n, fromName, toName, status, null, null, gap, null, "NOT_ASSESSABLE", message));
                continue;
            }
            try {
                calls++;
                var route = walking.fetch(from, to, Math.min(4000, remainingMillis));
                Long allowance = timingApplies && gap != null ? gap - route.durationSeconds() : null;
                String timing = allowance == null ? "REFERENCE_ONLY"
                    : allowance < 0 ? "INSUFFICIENT_TIME" : "NEEDS_STAY_TIME";
                message = switch (timing) {
                    case "INSUFFICIENT_TIME" -> "仅预计步行就超过两站开始时间的间隔，需调整时间或地点。";
                    case "NEEDS_STAY_TIME" -> "剩余时间还需容纳上一站停留、进出场及缓冲；不能据此保证赶得上。";
                    default -> "仅作步行参考；原交通方式不是步行或时间间隔无效，未判断行程可行性。";
                };
                legs.add(new Leg(n - 1, n, fromName, toName, "AVAILABLE", route.distanceMeters(),
                    route.durationSeconds(), gap, allowance, timing, message));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                legs.add(new Leg(n - 1, n, fromName, toName, "UNAVAILABLE", null, null, gap, null,
                    "NOT_ASSESSABLE", "高德路线服务未返回可用数据；可能是权限、额度、无路线或连接异常，可稍后重试。"));
            }
        }
        long available = legs.stream().filter(l -> l.status().equals("AVAILABLE")).count();
        String status = legs.isEmpty() ? "NO_LEGS" : available == legs.size() ? "AVAILABLE"
            : available > 0 ? "PARTIAL" : "UNAVAILABLE";
        return new Report(VERSION, plan.id(), plan.version(), checkedAt,
            evidence == null ? null : evidence.fetchedAt(), "WALKING", transport, status, calls, List.copyOf(legs),
            List.of("路线来自高德地图查询时的步行预计，不是出行当天保证；距离为道路路线距离。",
                "地点坐标沿用此版本保存的依据；营业、预约、费用及停留时长仍需确认。",
                "查询结果仅供当前页面参考，不修改原方案、来源报告、PDF或一次纠错次数。"));
    }
    private static boolean valid(PlanningEvidence.Place p) {
        return p != null && p.id() != null && p.id().matches("amap/[A-Za-z0-9]{1,40}")
            && "GCJ-02".equals(p.coordinateSystem())
            && Double.isFinite(p.longitude()) && Double.isFinite(p.latitude())
            && p.longitude() >= 73 && p.longitude() <= 136 && p.latitude() >= 3 && p.latitude() <= 54;
    }
    private static Long gap(String from, String to) {
        try { long seconds = Duration.between(LocalTime.parse(from), LocalTime.parse(to)).getSeconds();
            return seconds > 0 ? seconds : null;
        } catch (Exception e) { return null; }
    }
}
