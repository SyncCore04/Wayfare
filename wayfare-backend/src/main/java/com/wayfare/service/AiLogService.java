package com.wayfare.service;

import com.wayfare.connector.governance.ExternalCallRecord;
import com.wayfare.trip.AiStageRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 生成日志服务（P2-C）—— 写入与聚合。
 *
 * <p>本服务是「成本与质量证据链」的唯一出入口：P4 的成本控制、P6 的监控看板、
 * P7 的量化指标都从这里取数。<b>所以写入必须可靠，聚合口径必须稳定。</b>
 *
 * <p><b>写入一律吞异常</b>（与 {@code ExternalCallLogService} 同一取舍）：
 * 记日志失败绝不能让用户的行程规划失败 —— 一张日志表的问题不该毁掉主流程。
 */
public interface AiLogService {

    /**
     * 记一条管线阶段日志。
     *
     * <p>失败只记 WARN，不抛异常。
     */
    void recordStage(AiStageRecord record);

    /**
     * 记一条外部调用日志。
     *
     * <p><b>注意：这不是外部调用日志的主写入路径。</b>
     * 主路径是出站治理层的 {@code GovernedExternalHttpClient} ——
     * 所有走 {@code ExternalHttpClient} 的调用都会自动落日志，不需要调用方操心。
     * 本方法只是给「不走治理层但需要留痕」的场景留一个统一入口，
     * 内部直接委托给 {@code ExternalCallLogService}，不重复实现。
     */
    void recordCall(ExternalCallRecord record);

    /**
     * 某用户某天的 token 合计与预估成本。
     *
     * @param userId 用户ID
     * @param date   日期（按服务器时区，与 {@code created_at} 的存储口径一致）
     * @return 形如：
     *         <pre>
     * {
     *   "date": "2026-09-20",
     *   "promptTokens": 1234, "completionTokens": 567, "totalTokens": 1801,
     *   "callCount": 7,
     *   "estCost": 0.000360,
     *   "byProvider": [ {provider, promptTokens, completionTokens, totalTokens, callCount, estCost} ]
     * }
     * </pre>
     *         <b>token 字段可能为 null</b>（当天没有日志，或日志里没记 token）；
     *         <b>estCost 可能为 null</b>（单价未配置）—— 都不编造成 0。
     */
    Map<String, Object> sumTokensByUserAndDate(Long userId, LocalDate date);

    /**
     * 各阶段成功率（含失败率）。
     *
     * @return 每行 stage / total / successCount / failRate（0~1，4 位小数）
     */
    List<Map<String, Object>> successRateByStage(LocalDate from, LocalDate to);

    /**
     * 失败最多的错误码 Top N。
     *
     * @return 每行 errorCode / cnt
     */
    List<Map<String, Object>> topErrors(LocalDate from, LocalDate to, int limit);

    /**
     * 预估成本 = 单价 × token 数 ÷ 100 万。
     *
     * <p><b>单价未配置时返回 null，绝不返回 0</b>：成本是简历/答辩上会被追问的数字，
     * 显示「未配置」是诚实的，显示「0 元」是编造。
     * 单价来自 {@code sys_config} 的 {@code llm.price.glm} / {@code llm.price.deepseek}
     * （单位：元/百万 token）。
     *
     * @param provider 厂商名，与 sys_config 键的后缀一致（glm / deepseek）
     * @param tokens   token 数；为 null 时返回 null
     * @return 成本（保留 6 位小数，够表达 0.000001 元级的小额）；
     *         单价未配置或 tokens 为 null 时返回 null
     */
    BigDecimal estCost(String provider, Integer tokens);
}
