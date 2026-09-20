package com.wayfare.controller;

import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmProviderFactory;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.llm.ResolvedLlm;
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
 * 大模型连接器诊断接口。
 *
 * <p>定位：P1-B 的验收要求「llm.ping 能成功返回」，所以这里提供最小的连通性验证入口。
 * <b>P1-E 的连接器诊断接口会在此基础上扩展</b>（加熔断状态、失败率、地图连接器），
 * 现在不做那些，避免越界。
 *
 * <p>权限：整条路径不在公开白名单里 → 必须登录，方法内再要求管理员。
 * 原因很直接：ping 会真实调用外部 API、产生费用，不能匿名触发。
 */
@RestController
@RequestMapping("/connector/llm")
public class LlmDiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(LlmDiagnosticController.class);

    private final LlmCapabilityResolver resolver;
    private final LlmProviderFactory factory;

    public LlmDiagnosticController(LlmCapabilityResolver resolver, LlmProviderFactory factory) {
        this.resolver = resolver;
        this.factory = factory;
    }

    /**
     * 各 Provider 的状态快照 + 本次会选中哪一家。<b>不发起任何外部调用</b>，可以随便刷。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        checkAdmin();

        List<Map<String, Object>> providers = new ArrayList<>();
        for (String name : factory.names()) {
            LlmProvider provider = factory.get(name);
            if (provider == null) continue;
            LlmInfo info = provider.info();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", info.provider());
            row.put("model", info.model());
            row.put("available", info.available());
            row.put("reason", info.reason());
            providers.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registeredProviders", factory.names());
        data.put("providers", providers);
        try {
            ResolvedLlm resolved = resolver.resolve();
            data.put("resolved", decision(resolved));
        } catch (RuntimeException e) {
            // 能力被关闭时这里会抛 LLM_DISABLED；诊断接口不该因此 500，如实回传即可
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            data.put("resolved", err);
        }
        return Result.success(data);
    }

    /**
     * 连通性测试：真实调用一次大模型。
     *
     * @param prompt 可自定义，默认用一句极短的话把成本压到最低
     */
    @GetMapping("/ping")
    public Result<Map<String, Object>> ping(@RequestParam(required = false) String prompt) {
        checkAdmin();

        String userPrompt = (prompt != null && !prompt.isBlank())
                ? prompt : "只回复两个字：可以";

        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
        long start = System.currentTimeMillis();
        // 用 execute 而不是 resolve().provider().chat()：
        // 「Key 配错」在 resolve 阶段看不出来（非空就算已配置），只有真发请求拿到 401 才知道，
        // 这时必须能自动换下一家 —— 这正是手册验收第 4 条要的行为。
        LlmCapabilityResolver.LlmCallResult<String> result = resolver.execute(
                provider -> provider.chat("你是一个简洁的助手。", userPrompt, LlmCallContext.of(usageRef::set)));
        long elapsed = System.currentTimeMillis() - start;

        ResolvedLlm resolved = result.used();
        Map<String, Object> data = decision(resolved);
        data.put("failedProviders", result.failures());
        data.put("reply", result.value());
        data.put("usage", usageRef.get() == null ? null : Map.of(
                "promptTokens", usageRef.get().promptTokens() == null ? 0 : usageRef.get().promptTokens(),
                "completionTokens", usageRef.get().completionTokens() == null ? 0 : usageRef.get().completionTokens(),
                "totalTokens", usageRef.get().totalTokens() == null ? 0 : usageRef.get().totalTokens()));
        data.put("elapsedMs", elapsed);
        log.info("llm ping 成功: provider={}, model={}, isFallback={}, 耗时={}ms",
                resolved.providerName(), resolved.model(), resolved.isFallback(), elapsed);
        return Result.success(data);
    }

    /**
     * 流式连通性测试：把 chatStream 的每一段 delta 收集起来回传。
     *
     * <p>为什么要专门做一个：流式的问题（最后一个 chunk 只有 usage、
     * reasoner 把思考过程混进 content）在非流式调用里根本暴露不出来，
     * 必须真跑一次流式才能确认。
     */
    @GetMapping("/stream-ping")
    public Result<Map<String, Object>> streamPing(@RequestParam(required = false) String prompt) {
        checkAdmin();

        String userPrompt = (prompt != null && !prompt.isBlank())
                ? prompt : "用一句话介绍泉州。";
        ResolvedLlm resolved = resolver.resolve();

        StringBuilder full = new StringBuilder();
        List<String> firstDeltas = new ArrayList<>();
        // 计数与取样分开：deltaCount 要报真实段数，firstDeltas 只留前几段供肉眼比对
        java.util.concurrent.atomic.AtomicInteger deltaCount = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        long start = System.currentTimeMillis();

        resolved.provider().chatStream("你是一个简洁的助手。", userPrompt, LlmCallContext.empty(),
                delta -> {
                    full.append(delta);
                    deltaCount.incrementAndGet();
                    if (firstDeltas.size() < 8) firstDeltas.add(delta);
                },
                () -> { /* 结束由下面的 elapsed 统计体现，无需额外动作 */ },
                errorRef::set);

        long elapsed = System.currentTimeMillis() - start;
        if (errorRef.get() != null) {
            Throwable t = errorRef.get();
            log.warn("llm stream ping 失败: provider={}, {}", resolved.providerName(), t.getMessage());
            throw new BusinessException(ResultCode.LLM_SERVER_ERROR.getCode(),
                    "流式调用失败：" + t.getMessage());
        }

        Map<String, Object> data = decision(resolved);
        data.put("deltaCount", deltaCount.get());
        data.put("firstDeltas", firstDeltas);
        data.put("fullText", full.toString());
        data.put("textLength", full.length());
        data.put("elapsedMs", elapsed);
        return Result.success(data);
    }

    private Map<String, Object> decision(ResolvedLlm resolved) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", resolved.providerName());
        map.put("model", resolved.model());
        map.put("isFallback", resolved.isFallback());
        map.put("reason", resolved.reason());
        return map;
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
