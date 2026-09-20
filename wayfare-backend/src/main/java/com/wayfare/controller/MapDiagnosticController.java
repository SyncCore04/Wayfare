package com.wayfare.controller;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.RouteDTO;
import com.wayfare.connector.map.RouteQueryDTO;
import com.wayfare.security.UserContext;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 地图连接器诊断接口。
 *
 * <p>P1-C 的验收要求「调诊断接口显示 mode 与 reason」「熔断状态要能被读到」，
 * 这里是那些要求的出口。P1-E 的连接器诊断接口会在此基础上统一整理。
 *
 * <p>权限：需要登录且管理员 —— 诊断接口会真实消耗地图配额，也暴露 AK 配置状态（不暴露 AK 本身）。
 */
@RestController
@RequestMapping("/connector/map")
public class MapDiagnosticController {

    private final MapCapabilityResolver resolver;

    public MapDiagnosticController(MapCapabilityResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 当前的能力决策与熔断状态。<b>不发起任何外部请求</b>，可以随便刷。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        checkAdmin();

        Map<String, Object> data = new LinkedHashMap<>();
        var resolved = resolver.resolve();
        data.put("mode", resolved.mode());
        data.put("provider", resolved.provider().name());
        data.put("reason", resolved.reason());
        data.put("degraded", resolved.degraded());

        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("baidu", availability(resolver.baiduProvider().isAvailable(),
                resolver.baiduProvider().unavailableReason()));
        providers.put("cache", availability(resolver.cacheProvider().isAvailable(),
                resolver.cacheProvider().unavailableReason()));
        providers.put("disabled", availability(resolver.disabledProvider().isAvailable(),
                resolver.disabledProvider().unavailableReason()));
        data.put("providers", providers);

        data.put("breaker", resolver.breaker().state("baidu"));
        return Result.success(data);
    }

    /**
     * 走完整降级链地做一次 POI 检索。
     * 返回值里的 mode 是<b>最终敲定</b>的可信度（缓存未命中会被降为 ESTIMATED）。
     */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam String city,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "10") Integer pageSize) {
        checkAdmin();

        PoiQueryDTO query = new PoiQueryDTO();
        query.setCity(city);
        query.setKeyword(keyword);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);

        MapCapabilityResolver.MapCallResult<List<PoiDTO>> result =
                resolver.call(p -> p.searchPoi(query), list -> list != null && !list.isEmpty());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", result.mode());
        data.put("provider", result.providerName());
        data.put("reason", result.reason());
        List<PoiDTO> pois = result.value();
        data.put("count", pois == null ? 0 : pois.size());
        data.put("results", pois);
        // 顺带把「有没有拿到评分 / 票价」统计出来：这两个字段百度经常不给，
        // 显式统计能让「为什么是 null」一眼看清，而不必逐条翻 JSON
        if (pois != null && !pois.isEmpty()) {
            data.put("ratingMissingCount", pois.stream().filter(p -> p.rating() == null).count());
            data.put("ticketPriceMissingCount", pois.stream().filter(p -> p.ticketPrice() == null).count());
        }
        return Result.success(data);
    }

    /**
     * 走完整降级链地做一次路线规划。VERIFIED 命中时会把结果写进 Redis 缓存，
     * 供熔断期间降级使用。
     */
    @GetMapping("/route")
    public Result<Map<String, Object>> route(@RequestParam Double fromLng,
                                             @RequestParam Double fromLat,
                                             @RequestParam Double toLng,
                                             @RequestParam Double toLat,
                                             @RequestParam(defaultValue = "driving") String mode) {
        checkAdmin();

        RouteQueryDTO query = new RouteQueryDTO(fromLng, fromLat, toLng, toLat, mode);
        MapCapabilityResolver.MapCallResult<RouteDTO> result =
                resolver.call(p -> p.route(query), Objects::nonNull);

        // 只有实时算出来的才写缓存（估算值不该污染缓存，否则以后会把它当真实数据用）
        if (result.mode() == MapMode.VERIFIED && result.value() != null) {
            resolver.cacheProvider().cacheRoute(query, result.value());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", result.mode());
        data.put("provider", result.providerName());
        data.put("reason", result.reason());
        RouteDTO route = result.value();
        if (route != null) {
            data.put("distanceMeters", route.distanceMeters());
            data.put("durationSeconds", route.durationSeconds());
            data.put("mode_", route.mode());
            data.put("polylinePoints", route.polyline() == null ? 0 : route.polyline().split(";").length);
            data.put("fromCache", route.fromCache());
        }
        return Result.success(data);
    }

    /** 手动重置熔断（调好 AK 之后不必干等 5 分钟） */
    @PostMapping("/breaker/reset")
    public Result<Void> resetBreaker() {
        checkAdmin();
        resolver.breaker().reset("baidu");
        return Result.success();
    }

    private Map<String, Object> availability(boolean available, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", available);
        map.put("reason", reason);
        return map;
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
