package com.wayfare.service;

import com.wayfare.connector.governance.ExternalCallRecord;
import com.wayfare.trip.AiStageRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * 某用户某时间窗内的 token 合计与预估成本（P3-F 的 meta 用）。
     *
     * <p>P3 编排一次请求串了好几个 LLM 阶段，每个阶段各记一条 ai_generation_log。
     * 本次运行的 token 合计就按「用户 + 时间窗（runStart·runEnd）」归集，
     * 这样即便 PARSE/CANDIDATE 阶段还没拿到 tripId，也能被这次运行的 meta 算进来。
     *
     * @return 形如：
     *         <pre>
     * { "totalTokens": 1801, "callCount": 7, "estCost": 0.000360 }
     * </pre>
     *         estCost 仅在「每家单价都已配置」时才非 null（口径与 {@link #sumTokensByUserAndDate} 一致）。
     */
    Map<String, Object> sumByUserSince(Long userId, LocalDateTime since, LocalDateTime to);

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

    /**
     * 预估成本（P4-C · 输入输出分开计价）。
     *
     * <p>公式：
     * <pre>
     *   estCost = promptTokens × priceInput ÷ 1e6 + completionTokens × priceOutput ÷ 1e6
     * </pre>
     *
     * <p>单价从 {@code sys_config} 读，两级回落：
     * <ol>
     *   <li>优先 {@code llm.price.{provider}-input} / {@code llm.price.{provider}-output}（小数字符串）；</li>
     *   <li>两个都没配时回落到 {@code llm.price.{provider}}（整数，视作输入输出同价）——
     *       这是 P2-C 的既有配置，留着它，旧部署升级后不会突然算不出成本。</li>
     * </ol>
     * <b>任一侧单价缺失就返回 null</b>，不做「缺失侧按 0 算」——
     * 那会把成本算低，而成本是要写进简历与答辩的数字。
     *
     * @return 成本（保留 4 位小数）；单价未配置、或两侧 token 都为空时返回 null
     */
    BigDecimal estCost(String provider, Integer promptTokens, Integer completionTokens);

    /**
     * 一次生成的 token 与成本拆解（P4-C · 按 trip 归集、分阶段列出）。
     *
     * @return 形如：
     *         <pre>
     * {
     *   "tripId": 94,
     *   "stages": [ {stage, promptTokens, completionTokens, tokens, durationMs, callCount} ],
     *   "totalTokens": 8433,
     *   "estCost": 0.0012
     * }
     * </pre>
     *         {@code stages} 按管线顺序（PARSE→CANDIDATE→PREORDER→COMPOSE→VALIDATE→ROUTE→COPY）排列；
     *         token / estCost 拿不到时是 null，不编 0。
     */
    Map<String, Object> breakdownByTrip(Long tripId);

    /**
     * 某阶段某厂商的历史 completion_tokens 均值（P4-C · 中断节省估算的分母）。
     *
     * <p>中断时流式 usage 拿不到（它随最后一个 chunk 返回），只能按
     * 「同类请求本来会产出多少」来估。同类 = 同 stage + 同 provider 的<b>成功</b>记录。
     *
     * @return 均值（四舍五入到整数）；没有历史样本时返回 null（宁可不估，也不编）
     */
    Integer avgCompletionTokens(String stage, String provider);

    /**
     * 生成统计总览（P4-C · 给后台用，P6 对接）。
     *
     * <p>对应 {@code GET /api/admin/generation/stats?from=&to=}。
     *
     * @return 形如：
     *         <pre>
     * {
     *   totalCount, successCount, successRate, tripCount,
     *   avgDurationMs, p95DurationMs,
     *   totalTokens, avgTokensPerTrip, totalEstCost, avgCostPerTrip,
     *   stageBreakdown:   [{stage, total, avgTokens, avgDurationMs, successRate}],
     *   mapModeBreakdown: [{mapMode, count, avgDurationMs}],
     *   topErrors:        [{errorCode, cnt, scene}]
     * }
     * </pre>
     *         <b>分母为 0 的比率一律返回 null，不返回 0</b>：窗口内没有数据时，
     *         「成功率 0%」是错的（不是失败了，是压根没跑过）。
     */
    Map<String, Object> generationStats(LocalDate from, LocalDate to);
}
