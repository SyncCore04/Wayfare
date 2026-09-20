package com.wayfare.controller;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.governance.CircuitBreaker;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmProviderFactory;
import com.wayfare.connector.llm.ResolvedLlm;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapProviderException;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.governance.ExternalCallLogService;
import com.wayfare.connector.governance.ExternalCallRecord;
import com.wayfare.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 连接器诊断接口（P1-E 定稿）。
 *
 * <p><b>把 P1-B / P1-C 临时加的诊断收拢到这里</b>：
 * LLM 侧的 ping（P1-B 的 /connector/llm/ping）与地图侧的检索诊断
 * （P1-C 的 /connector/map/search）功能等价，但本类额外补齐了
 * 手册要求的「失败原因四分类」与聚合统计。
 *
 * <p><b>失败原因四分类是这里的重点</b>：「没配 Key」「网络不通」「认证失败」「限流」
 * 的处置方式完全不同 —— 前一个要管理员填 Key，中间两个等一会儿，最后一个换厂商。
 * 笼统报「调用失败」会让排查方向全错。
 *
 * <p>权限：全部登录 + 管理员。ping 会真实消耗配额，不能匿名触发。
 */
@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final LlmCapabilityResolver llmResolver;
    private final LlmProviderFactory llmFactory;
    private final MapCapabilityResolver mapResolver;
    private final ExternalCallLogService callLogService;

    public DiagnosticsController(LlmCapabilityResolver llmResolver,
                                 LlmProviderFactory llmFactory,
                                 MapCapabilityResolver mapResolver,
                                 ExternalCallLogService callLogService) {
        this.llmResolver = llmResolver;
        this.llmFactory = llmFactory;
        this.mapResolver = mapResolver;
        this.callLogService = callLogService;
    }

    // ==================== 聚合诊断 ====================

    /**
     * 两个连接器的一次性总览：开关、当前决策、熔断状态、调用统计。
     */
    @GetMapping("/connectors")
    public Result<Map<String, Object>> connectors() {
        checkAdmin();

        Map<String, Object> llm = new LinkedHashMap<>();
        boolean llmEnabled = llmResolver.isEnabled();
        llm.put("enabled", llmEnabled);
        if (llmEnabled) {
            ResolvedLlm resolved = llmResolver.resolve();
            llm.put("activeProvider", resolved.providerName());
            llm.put("model", resolved.model());
            llm.put("available", true);
            llm.put("isFallback", resolved.isFallback());
            llm.put("reason", resolved.reason());
        } else {
            llm.put("available", false);
            llm.put("reason", "大模型能力已被关闭（llm.enabled=false）");
        }
        llm.putAll(callLogService.stats(ExternalCallRecord.CONNECTOR_LLM));
        // token 统计要等 P4-C 把用量落库后才有数据来源，这里如实返回 null
        llm.put("todayTokens", null);
        llm.put("estCost", null);

        Map<String, Object> map = new LinkedHashMap<>();
        var mapResolved = mapResolver.resolve();
        boolean mapEnabled = mapResolver.isMapEnabled();
        map.put("enabled", mapEnabled);
        map.put("mode", mapResolved.mode());
        map.put("provider", mapResolved.provider().name());
        map.put("available", mapResolved.provider().isAvailable());
        map.put("reason", mapResolved.reason());
        CircuitBreaker circuitBreaker = mapResolver.breaker();
        Map<String, Object> breakerState = circuitBreaker.state("baidu");
        map.put("breakerOpen", Boolean.TRUE.equals(breakerState.get("breakerOpen")));
        map.putAll(callLogService.stats(ExternalCallRecord.CONNECTOR_BAIDU_MAP));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llm", llm);
        data.put("map", map);
        return Result.success(data);
    }

    // ==================== LLM ping ====================

    /**
     * 大模型连通性测试。
     *
     * @param provider 可选。指定则只测这一家（不降级）—— 用于「分别 ping GLM 与 DeepSeek」；
     *                 不指定则用当前决策（含降级链）。
     */
    @PostMapping("/llm/ping")
    public Result<Map<String, Object>> llmPing(@RequestBody(required = false) Map<String, String> body) {
        checkAdmin();
        String provider = body == null ? null : body.get("provider");
        String prompt = (body != null && body.get("prompt") != null && !body.get("prompt").isBlank())
                ? body.get("prompt") : "请只回复：OK";

        Map<String, Object> data = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        try {
            if (provider != null && !provider.isBlank()) {
                // 指定厂商：不降级，失败原样返回（否则你测 DeepSeek 时它可能偷偷用 GLM 回答你）
                LlmProvider target = llmFactory.get(provider);
                if (target == null) {
                    data.put("provider", provider);
                    data.put("available", false);
                    data.put("failureType", "厂商未注册");
                    data.put("reason", "没有名为 " + provider + " 的 Provider，已注册：" + llmFactory.names());
                    return Result.success(data);
                }
                return Result.success(pingOne(target, prompt, start));
            }
            // 未指定：走决策链（含降级），失败时 execute 会把可降级的异常换下一家
            var result = llmResolver.execute(p -> p.chat("你是一个简洁的助手。", prompt,
                    LlmCallContext.of(u -> { /* 诊断接口不关心用量 */ })));
            long elapsed = System.currentTimeMillis() - start;
            data.put("provider", result.used().providerName());
            data.put("model", result.used().model());
            data.put("isFallback", result.used().isFallback());
            data.put("reason", result.used().reason());
            data.put("success", true);
            data.put("durationMs", elapsed);
            data.put("reply", result.value());
            data.put("failedProviders", result.failures());
            return Result.success(data);
        } catch (LlmException e) {
            long elapsed = System.currentTimeMillis() - start;
            data.put("provider", e.getProvider());
            data.put("success", false);
            data.put("failureType", failureType(e.getResultCode()));
            data.put("reason", e.getMessage());
            data.put("durationMs", elapsed);
            log.warn("llm ping 失败: {}", e.getMessage());
            return Result.success(data);
        }
    }

    // ==================== 地图 ping ====================

    /**
     * 地图连通性测试：真实发起一次 POI 检索（走三级降级）。
     */
    @PostMapping("/map/ping")
    public Result<Map<String, Object>> mapPing(@RequestBody(required = false) Map<String, String> body) {
        checkAdmin();
        String city = body == null ? null : body.get("city");
        String keyword = body == null ? null : body.get("keyword");
        if (city == null || city.isBlank()) city = "泉州";
        if (keyword == null || keyword.isBlank()) keyword = "开元寺";

        PoiQueryDTO query = new PoiQueryDTO();
        query.setCity(city);
        query.setKeyword(keyword);
        query.setPageNum(1);
        query.setPageSize(3);

        Map<String, Object> data = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            var result = mapResolver.call(p -> p.searchPoi(query), list -> list != null && !list.isEmpty());
            long elapsed = System.currentTimeMillis() - start;
            List<PoiDTO> pois = result.value();
            List<String> samples = new ArrayList<>();
            if (pois != null) {
                pois.stream().limit(3).forEach(p -> samples.add(p.name()));
            }
            data.put("mode", result.mode());
            data.put("provider", result.providerName());
            data.put("success", result.mode() != com.wayfare.connector.map.MapMode.ESTIMATED);
            data.put("failureType", result.mode() == com.wayfare.connector.map.MapMode.ESTIMATED
                    ? "已降级（" + result.reason() + "）" : null);
            data.put("durationMs", elapsed);
            data.put("hitCount", pois == null ? 0 : pois.size());
            data.put("samples", samples);
            // rawSummary 给一条原始摘要，便于确认「字段为什么缺」
            data.put("rawSummary", (pois != null && !pois.isEmpty() && pois.get(0).rawJson() != null)
                    ? pois.get(0).rawJson().substring(0, Math.min(200, pois.get(0).rawJson().length()))
                    : null);
            return Result.success(data);
        } catch (MapProviderException e) {
            long elapsed = System.currentTimeMillis() - start;
            data.put("provider", e.getProvider());
            data.put("success", false);
            data.put("failureType", mapFailureType(e.getResultCode()));
            data.put("reason", e.getMessage());
            data.put("durationMs", elapsed);
            log.warn("map ping 失败: {}", e.getMessage());
            return Result.success(data);
        }
    }

    // ==================== 工具 ====================

    private Map<String, Object> pingOne(LlmProvider target, String prompt, long start) {
        LlmInfo info = target.info();
        if (!info.available()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", target.name());
            data.put("model", info.model());
            data.put("available", false);
            data.put("failureType", "未配置 API Key");
            data.put("reason", info.reason());
            return data;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", target.name());
        data.put("model", info.model());
        data.put("available", true);
        try {
            String reply = target.chat("你是一个简洁的助手。", prompt, LlmCallContext.of(u -> { }));
            data.put("success", true);
            data.put("durationMs", System.currentTimeMillis() - start);
            data.put("reply", reply);
        } catch (LlmException e) {
            data.put("success", false);
            data.put("failureType", failureType(e.getResultCode()));
            data.put("reason", e.getMessage());
        }
        return data;
    }

    /** 把 LLM 失败原因归到手册要求的四类，给人看得懂的中文 */
    private String failureType(ResultCode code) {
        return switch (code) {
            case LLM_NOT_AVAILABLE -> "未配置 API Key";
            case LLM_TIMEOUT -> "网络不通或超时";
            case LLM_AUTH_FAIL -> "认证失败";
            case LLM_RATE_LIMIT -> "限流";
            default -> "调用失败";
        };
    }

    private String mapFailureType(ResultCode code) {
        return switch (code) {
            case MAP_AUTH_FAIL -> "认证失败（AK 无效或未开启该服务）";
            case MAP_DISABLED -> "地图连接器已被关闭";
            case MAP_QUOTA_EXCEEDED -> "配额已用尽";
            case MAP_NOT_AVAILABLE -> "地图服务不可用";
            default -> "调用失败";
        };
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
