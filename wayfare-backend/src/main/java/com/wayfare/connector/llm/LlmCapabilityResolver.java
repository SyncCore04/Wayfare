package com.wayfare.connector.llm;

import com.wayfare.common.result.ResultCode;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 能力决策器：决定「这一次调用到底用哪家 Provider」。
 *
 * <p>决策顺序：
 * <ol>
 *   <li>读 {@code llm.enabled}（L2 → L1）。<b>为 false 直接抛 {@link ResultCode#LLM_DISABLED}</b>，
 *       而不是回落 Mock —— 能力被主动关掉时不该拿假数据糊弄用户（前端应据此隐藏 AI 入口）。</li>
 *   <li>取主力 Provider = {@code llm.active-provider}（L2 → L1）。可用则直接用。</li>
 *   <li>主力不可用 → 按 {@code llm.fallback-order} 依次尝试，跳过主力自身。</li>
 *   <li>全都不行 → 回落 {@link com.wayfare.connector.llm.mock.MockLlmProvider}，
 *       并带上完整原因。这一步保证「双 Key 都不配时全链路仍可跑通」。</li>
 * </ol>
 *
 * <p><b>L1/L2 分层体现在这里</b>：所有开关都先问 {@code SysConfigService}（L2），
 * 读不到才用 {@code LlmProperties}（L1）。所以后台改一下 {@code llm.active-provider}，
 * 下一个请求立刻换厂商，<b>不需要重启</b>。
 *
 * <p>P1-D 会在 {@link #isAvailable} 里补上「熔断是否打开」这一条，
 * 其余决策逻辑不用动。
 */
@Component
public class LlmCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmCapabilityResolver.class);

    private final SysConfigService sysConfigService;
    private final LlmProperties llmProperties;
    private final LlmProviderFactory factory;

    public LlmCapabilityResolver(SysConfigService sysConfigService,
                                 LlmProperties llmProperties,
                                 LlmProviderFactory factory) {
        this.sysConfigService = sysConfigService;
        this.llmProperties = llmProperties;
        this.factory = factory;
    }

    /** 解析出本次要用的 Provider（只回答「先用哪一家」） */
    public ResolvedLlm resolve() {
        // 直接复用候选链的第一项，避免两套判定逻辑以后跑偏
        return resolveChain().get(0);
    }

    /**
     * 指定厂商解析（P4-B 的双模型文案对比用）。
     *
     * <p><b>刻意不降级</b>：调用方点名要某一家，就是为了看这一家的真实产出。
     * 如果这家不可用就悄悄换成别家，两份「对比文案」可能出自同一个模型 ——
     * 对比失去意义，而且结果看起来完全正常，没人会发现。
     * 所以这里不可用就如实抛错，让调用方收到明确的 error 事件。
     *
     * <p>与 {@link #resolve()} 的分工：{@code resolve()} 回答「这次该用谁」（含降级链），
     * 本方法回答「就用这一家，能不能用」。
     */
    public ResolvedLlm resolveFor(String providerName) {
        if (!isEnabled()) {
            throw new LlmException(ResultCode.LLM_DISABLED, providerName, "llm.enabled=false");
        }
        LlmProvider provider = factory.get(providerName);
        if (provider == null) {
            throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, providerName,
                    "厂商 '" + providerName + "' 未注册");
        }
        if (!isAvailable(provider)) {
            throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, providerName,
                    "厂商 '" + providerName + "' 当前不可用：" + provider.info().reason());
        }
        return ResolvedLlm.primary(provider);
    }

    /** llm.enabled 当前值（L2 → L1 分层读取），诊断接口用它回答「能力开了没有」 */
    public boolean isEnabled() {
        return sysConfigService.getBool(KEY_ENABLED, llmProperties.isEnabled());
    }

    /**
     * 某家 Provider 现在能不能用。
     *
     * <p>P1-D 会在这里加「熔断器是否打开」。当前只判断「配没配 Key」——
     * 这是最常见的不可用原因，也是唯一一个不需要请求就能判断的原因。
     */
    protected boolean isAvailable(LlmProvider provider) {
        return provider.info().available();
    }

    /**
     * 带降级的调用：按「主力 → 降级顺序 → Mock」依次真的去调，某家失败就换下一家。
     *
     * <p><b>为什么光有 {@link #resolve()} 不够</b>：{@code resolve()} 只能判断
     * 「配没配 Key」，判断不了「Key 是不是错的」—— 错的 Key 看起来也是「已配置」。
     * 只有真正发出请求、拿回 401，才知道这家不行。所以必须有这一层：
     * 调用失败后按链往下换，这正是手册验收第 4 条要的行为
     * （「故意把 GLM Key 改错 → 收到 LLM_AUTH_FAIL 且自动降级」）。
     *
     * <p>注意：这里只处理<b>非流式</b>调用的降级。流式调用一旦已经推了部分内容出去，
     * 就没法干净地换个厂商重来 —— 那属于 P4 的 SSE 事件协议要处理的事。
     *
     * @param action 真正发起调用的动作，参数是选中的 Provider
     * @return 调用结果 + 实际用的 Provider + 沿途失败过哪些家（便于写进 meta 与监控）
     */
    public <T> LlmCallResult<T> execute(Function<LlmProvider, T> action) {
        List<ResolvedLlm> chain = resolveChain();
        List<String> failures = new ArrayList<>();
        LlmException lastError = null;

        for (int i = 0; i < chain.size(); i++) {
            ResolvedLlm candidate = chain.get(i);
            try {
                T value = action.apply(candidate.provider());
                if (i > 0 && lastError != null) {
                    // 走到这里说明是「前面失败后才换到这家」的，把降级事实记清楚
                    candidate = ResolvedLlm.fallback(candidate.provider(),
                            lastError.getResultCode().getMessage() + "（" + lastError.getProvider() + "）");
                }
                return new LlmCallResult<>(value, candidate, failures);
            } catch (LlmException e) {
                lastError = e;
                failures.add(e.getProvider() + ":" + e.getResultCode().name());
                if (!e.isFallbackWorthy()) {
                    throw e;
                }
                log.warn("Provider[{}] 调用失败（{}），尝试降级到下一家", e.getProvider(), e.getResultCode());
            }
        }
        // 全部失败：把最后一次的原因抛出去（它最有信息量），并说明整条链都试过了
        if (lastError != null) {
            throw new LlmException(lastError.getResultCode(), lastError.getProvider(),
                    "已尝试全部候选厂商 " + failures + "，均失败。最后一次原因：" + lastError.getMessage(),
                    lastError);
        }
        throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, "system", "没有任何可用的候选厂商");
    }

    /**
     * 展开成有序候选链：主力 → 降级顺序 → Mock（兜底）。
     * 只保留「当前判断为可用」的；Mock 永远可用，所以链不会为空。
     */
    public List<ResolvedLlm> resolveChain() {
        boolean enabled = sysConfigService.getBool(KEY_ENABLED, llmProperties.isEnabled());
        if (!enabled) {
            throw new LlmException(ResultCode.LLM_DISABLED, "system", "llm.enabled=false");
        }

        String activeName = sysConfigService.get(KEY_ACTIVE_PROVIDER, llmProperties.getActiveProvider());
        List<ResolvedLlm> chain = new ArrayList<>();
        List<String> tried = new ArrayList<>();

        addIfAvailable(chain, tried, activeName, activeName, null);

        String primaryReason = chain.isEmpty()
                ? describe(activeName) : null;
        for (String name : fallbackCandidates(activeName)) {
            addIfAvailable(chain, tried, name, activeName, primaryReason);
        }

        LlmProvider mock = factory.mock();
        if (mock != null && chain.stream().noneMatch(r -> mock.name().equals(r.providerName()))) {
            // ┌─────────────────────────────────────────────────────────────────────┐
            // │ 注意：只有「一家可用厂商都没有」时才回落 Mock，而不是无条件兜底。      │
            // │ 这是有意区分两种情况：                                              │
            // │  (a) 压根没配 Key        → 回落 Mock，让系统保持可演示（验收 3）；    │
            // │  (b) 配了但调用失败(401等) → 不回落 Mock，让错误如实暴露（验收 4）。  │
            // │ 理由：Key 过期/配错是运维事故，此时悄悄返回「假行程」比直接报错更危险 ——│
            // │ 用户会以为 AI 生成的攻略是真的。宁可让他看到"认证失败请检查 Key"。    │
            // └─────────────────────────────────────────────────────────────────────┘
            if (chain.isEmpty()) {
                // 把「哪家、为什么」都写进 reason —— 这段文字会被 P3 写进返回的 meta、
                // 也会被 P6 的监控看板统计，含糊的 reason 等于没写
                String reason = "所有候选厂商均不可用（" + activeName + "：" + primaryReason
                        + "；降级顺序 " + fallbackCandidates(activeName) + " 也都不行），已回落 Mock";
                log.warn("{}", reason);
                chain.add(ResolvedLlm.fallback(mock, reason));
            }
        }

        if (chain.isEmpty()) {
            throw new LlmException(ResultCode.LLM_NOT_AVAILABLE, activeName,
                    "没有可用厂商且未注册 Mock；已尝试 " + tried);
        }
        return chain;
    }

    private void addIfAvailable(List<ResolvedLlm> chain, List<String> tried,
                                String name, String activeName, String primaryReason) {
        if (name == null || tried.contains(name)) return;
        tried.add(name);
        LlmProvider provider = factory.get(name);
        if (provider == null) {
            log.debug("厂商 '{}' 未注册，跳过", name);
            return;
        }
        if (!isAvailable(provider)) {
            log.debug("厂商 '{}' 当前不可用：{}", name, provider.info().reason());
            return;
        }
        if (name.equals(activeName)) {
            chain.add(ResolvedLlm.primary(provider));
        } else {
            chain.add(ResolvedLlm.fallback(provider,
                    primaryReason != null ? primaryReason : "主力之前调用失败，按降级顺序切换"));
        }
    }

    private String describe(String activeName) {
        LlmProvider primary = factory.get(activeName);
        if (primary == null) return "配置的厂商 '" + activeName + "' 未注册";
        return primary.info().reason();
    }

    /** 一次带降级调用的结果 */
    public record LlmCallResult<T>(T value, ResolvedLlm used, List<String> failures) {
        public boolean degraded() { return !failures.isEmpty(); }
    }

    /** 降级候选：来自 L2 的 llm.fallback-order，剔除主力自己与不存在的名字 */
    private List<String> fallbackCandidates(String activeName) {
        String order = sysConfigService.get(KEY_FALLBACK_ORDER, llmProperties.getFallbackOrder());
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(order)) return result;
        for (String raw : order.split(",")) {
            String name = raw.trim();
            if (name.isEmpty() || name.equals(activeName)) continue;
            if (!result.contains(name)) result.add(name);
        }
        return result;
    }

    // ---- sys_config 里的键名（含点号，与 schema-trip.sql 的初始数据一致）----
    private static final String KEY_ENABLED = "llm.enabled";
    private static final String KEY_ACTIVE_PROVIDER = "llm.active-provider";
    private static final String KEY_FALLBACK_ORDER = "llm.fallback-order";
}
