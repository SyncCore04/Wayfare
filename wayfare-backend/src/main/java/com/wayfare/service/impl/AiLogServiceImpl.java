package com.wayfare.service.impl;

import com.wayfare.common.util.MaskUtil;
import com.wayfare.connector.governance.ExternalCallLogService;
import com.wayfare.connector.governance.ExternalCallRecord;
import com.wayfare.entity.AiGenerationLog;
import com.wayfare.mapper.AiGenerationLogMapper;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import com.wayfare.trip.AiErrorCode;
import com.wayfare.trip.AiStageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 生成日志服务实现（P2-C）。
 *
 * <p><b>成本计算的口径（写清楚，免得被追问时说不出来）</b>：
 * <pre>
 *   成本 = 单价（元/百万token） × token 数 ÷ 1_000_000
 * </pre>
 * 单价来自 {@code sys_config} 的 {@code llm.price.glm} / {@code llm.price.deepseek}。
 * <b>单价未配置（0 或缺失）时返回 null 而不是 0</b> —— 这条是硬约束：
 * 项目初始化时两个单价都留 0，就是不想让任何人看到一个编造的成本数字。
 * 交接文档第 9 节写得很清楚：简历上的数字必须来自 P7-B 的实测。
 *
 * <p><b>另一个硬约束：token 为 null 时成本也是 null。</b>
 * 把「拿不到 usage」当成 0 token 会让当日成本偏低，属于静默错误。
 */
@Service
public class AiLogServiceImpl implements AiLogService {

    private static final Logger log = LoggerFactory.getLogger(AiLogServiceImpl.class);

    /** 单价配置键前缀，与 db/schema-trip.sql 的初始数据一致 */
    private static final String PRICE_KEY_PREFIX = "llm.price.";

    /** 百万：单价的口径单位 */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** 成本保留 6 位小数，够表达 0.000001 元级的小额调用 */
    private static final int COST_SCALE = 6;

    /** 失败率保留 4 位小数 */
    private static final int RATE_SCALE = 4;

    private final AiGenerationLogMapper aiGenerationLogMapper;
    private final ExternalCallLogService externalCallLogService;
    private final SysConfigService sysConfigService;

    public AiLogServiceImpl(AiGenerationLogMapper aiGenerationLogMapper,
                            ExternalCallLogService externalCallLogService,
                            SysConfigService sysConfigService) {
        this.aiGenerationLogMapper = aiGenerationLogMapper;
        this.externalCallLogService = externalCallLogService;
        this.sysConfigService = sysConfigService;
    }

    // ==================== 写入 ====================

    @Override
    public void recordStage(AiStageRecord record) {
        if (record == null) {
            return;
        }
        try {
            AiGenerationLog entity = new AiGenerationLog();
            entity.setUserId(record.userId());
            entity.setTripId(record.tripId());
            entity.setStage(record.stage());
            entity.setProvider(record.provider());
            entity.setModel(record.model());
            entity.setPromptTokens(record.promptTokens());
            entity.setCompletionTokens(record.completionTokens());
            entity.setTotalTokens(record.totalTokens());
            entity.setDurationMs(record.durationMs());
            entity.setSuccess(record.success() ? 1 : 0);
            entity.setErrorCode(record.errorCode() == null ? null : record.errorCode().name());
            // 失败详情里可能夹着 URL 或 Key（比如百度把 AK 回显在错误信息里），
            // 写入前统一过 sanitize —— 「库里不允许出现完整密钥」是铁律，
            // 日志表也不能例外。sanitize 内部是「先脱敏再截断」，顺序不能反。
            entity.setErrorMsg(MaskUtil.sanitize(record.errorMsg()));
            aiGenerationLogMapper.insert(entity);
        } catch (Exception e) {
            // 记日志失败绝不能影响主流程：一张日志表的问题不该让用户的行程规划失败
            log.warn("写 AI 生成日志失败（不影响主流程）: stage={}, provider={}, {}",
                    record.stage(), record.provider(), e.getMessage());
        }
    }

    @Override
    public void recordCall(ExternalCallRecord record) {
        // 委托给既有实现，不重复造一份落库逻辑
        externalCallLogService.record(record);
    }

    // ==================== 聚合 ====================

    @Override
    public Map<String, Object> sumByUserSince(Long userId, LocalDateTime since, LocalDateTime to) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userId == null || since == null || to == null) {
            return result;
        }
        List<Map<String, Object>> rows = aiGenerationLogMapper.sumTokensGroupByProvider(userId, since, to);
        return aggregateTokens(rows);
    }

    @Override
    public Map<String, Object> sumTokensByUserAndDate(Long userId, LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userId == null || date == null) {
            return result;
        }
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();

        List<Map<String, Object>> rows = aiGenerationLogMapper.sumTokensGroupByProvider(userId, from, to);
        Map<String, Object> agg = aggregateTokens(rows);

        result.put("date", date.toString());
        result.putAll(agg);
        result.put("byProvider", agg.get("byProvider"));
        return result;
    }

    /**
     * 把「按厂商分组的聚合行」加工成统一的 token/次数/成本 Map。
     *
     * <p>这是 {@link #sumTokensByUserAndDate} 与 {@link #sumByUserSince} 共用的核心，
     * 抽出来避免两处口径漂移。
     */
    private Map<String, Object> aggregateTokens(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> byProvider = new ArrayList<>();
        Long promptSum = null;
        Long completionSum = null;
        Long totalSum = null;
        long callCountSum = 0;
        BigDecimal costSum = null;

        for (Map<String, Object> row : rows) {
            String provider = row.get("provider") == null ? null : String.valueOf(row.get("provider"));
            Long promptTokens = toLong(row.get("promptTokens"));
            Long completionTokens = toLong(row.get("completionTokens"));
            Long totalTokens = toLong(row.get("totalTokens"));
            long callCount = toLongOrZero(row.get("callCount"));

            BigDecimal providerCost = estCost(provider, totalTokens == null ? null : totalTokens.intValue());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", provider);
            item.put("promptTokens", promptTokens);
            item.put("completionTokens", completionTokens);
            item.put("totalTokens", totalTokens);
            item.put("callCount", callCount);
            item.put("estCost", providerCost);
            byProvider.add(item);

            promptSum = addNullable(promptSum, promptTokens);
            completionSum = addNullable(completionSum, completionTokens);
            totalSum = addNullable(totalSum, totalTokens);
            callCountSum += callCount;
            if (providerCost != null) {
                costSum = costSum == null ? providerCost : costSum.add(providerCost);
            }
        }

        result.put("promptTokens", promptSum);
        result.put("completionTokens", completionSum);
        result.put("totalTokens", totalSum);
        result.put("callCount", callCountSum);
        // 只要有一家单价没配置，总额就不完整 —— 返回 null 而非「部分之和」
        boolean allPricesConfigured = byProvider.stream()
                .allMatch(item -> item.get("estCost") != null);
        result.put("estCost", byProvider.isEmpty() || !allPricesConfigured ? null : costSum);
        result.put("byProvider", byProvider);
        return result;
    }

    @Override
    public List<Map<String, Object>> successRateByStage(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = aiGenerationLogMapper.successRateByStage(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long total = toLongOrZero(row.get("total"));
            long successCount = toLongOrZero(row.get("successCount"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", row.get("stage"));
            item.put("total", total);
            item.put("successCount", successCount);
            item.put("failCount", total - successCount);
            item.put("failRate", total == 0
                    ? null
                    : BigDecimal.valueOf(total - successCount)
                            .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP));
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> topErrors(LocalDate from, LocalDate to, int limit) {
        List<Map<String, Object>> rows = aiGenerationLogMapper.topErrors(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), limit);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String code = row.get("errorCode") == null ? null : String.valueOf(row.get("errorCode"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("errorCode", code);
            item.put("cnt", toLongOrZero(row.get("cnt")));
            // 顺带把「触发场景」带出来，看板上不用再查一次枚举表；
            // 遇到枚举里没有的码（历史数据/手工插入）就返回 null，不抛异常
            AiErrorCode parsed = AiErrorCode.fromName(code);
            item.put("scene", parsed == null ? null : parsed.getScene());
            result.add(item);
        }
        return result;
    }

    @Override
    public BigDecimal estCost(String provider, Integer tokens) {
        if (provider == null || tokens == null || tokens <= 0) {
            return null;
        }
        Integer price = sysConfigService.getInt(PRICE_KEY_PREFIX + provider, 0);
        // 0（或负数）表示「未配置单价」—— 返回 null 而不是 0 元
        if (price == null || price <= 0) {
            return null;
        }
        return BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }

    // ==================== 内部 ====================

    /**
     * MyBatis 返回的聚合列类型不固定（COUNT 是 Long、CAST(... AS SIGNED) 是 Long，
     * 但某些驱动会给 BigDecimal），统一走 Number 取值，避免 ClassCastException。
     */
    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long toLongOrZero(Object value) {
        Long v = toLong(value);
        return v == null ? 0L : v;
    }

    /** 可空相加：两边都为 null 才是 null，否则把 null 当 0 加（聚合场景下这是正确的） */
    private static Long addNullable(Long sum, Long value) {
        if (value == null) {
            return sum;
        }
        return sum == null ? value : sum + value;
    }
}
