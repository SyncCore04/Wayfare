package com.wayfare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wayfare.entity.AiGenerationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 生成日志Mapper接口（P2-C）
 *
 * <p>写入由 {@code AiLogService} 负责，这里的统计查询供 P4 成本控制、
 * P6 监控看板、P7 量化指标使用。
 *
 * <p><b>为什么所有 SUM 都套了 {@code CAST(... AS SIGNED)}</b>：
 * MySQL 的 {@code SUM()} 对 INT 列返回的是 <b>DECIMAL</b> 而不是 BIGINT，
 * MyBatis 会把它映射成 {@link java.math.BigDecimal}。调用方按 Integer 取值就会
 * {@code ClassCastException} —— 这是个很容易踩的坑。显式 CAST 成 SIGNED
 * 让返回类型变成 BIGINT/Long，行为可预期。
 *
 * <p>另外：{@code SUM()} 在「所有行该列都是 NULL」时返回 <b>NULL 而不是 0</b>，
 * 这里<b>有意不套 COALESCE</b> —— 0 表示「确实是 0 token」，NULL 表示「没有数据」，
 * 混同会让成本统计把「未计费」算成「没花钱」。
 */
@Mapper
public interface AiGenerationLogMapper extends BaseMapper<AiGenerationLog> {

    /**
     * 某用户某时间窗内的 token 合计，按厂商分组。
     *
     * <p>按厂商分组是必须的：单价是「每家一个」（llm.price.glm / llm.price.deepseek），
     * 把两家的 token 加在一起就没法算钱了。
     */
    @Select("""
            SELECT provider                                                   AS provider,
                   CAST(SUM(prompt_tokens)     AS SIGNED)                     AS promptTokens,
                   CAST(SUM(completion_tokens) AS SIGNED)                     AS completionTokens,
                   CAST(SUM(total_tokens)      AS SIGNED)                     AS totalTokens,
                   COUNT(*)                                                   AS callCount
            FROM ai_generation_log
            WHERE user_id = #{userId}
              AND created_at >= #{from}
              AND created_at < #{to}
            GROUP BY provider
            ORDER BY totalTokens DESC
            """)
    List<Map<String, Object>> sumTokensGroupByProvider(@Param("userId") Long userId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /**
     * 各阶段成功率（时间窗内）。
     *
     * @return 每行 stage / total / successCount
     */
    @Select("""
            SELECT stage                                                      AS stage,
                   COUNT(*)                                                   AS total,
                   CAST(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS SIGNED) AS successCount
            FROM ai_generation_log
            WHERE created_at >= #{from}
              AND created_at < #{to}
            GROUP BY stage
            ORDER BY stage
            """)
    List<Map<String, Object>> successRateByStage(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 某条行程的 token / 耗时按阶段分组（P4-C · 成本拆解表）。
     *
     * <p>用 {@code FIELD()} 把阶段固定成管线顺序 —— 否则返回顺序由 MySQL 决定，
     * 看板上「PARSE 排在 COPY 后面」会让人以为管线反了。
     */
    @Select("""
            SELECT stage                                                  AS stage,
                   CAST(SUM(prompt_tokens)     AS SIGNED)                  AS promptTokens,
                   CAST(SUM(completion_tokens) AS SIGNED)                  AS completionTokens,
                   CAST(SUM(total_tokens)      AS SIGNED)                  AS totalTokens,
                   CAST(SUM(duration_ms)       AS SIGNED)                  AS durationMs,
                   COUNT(*)                                                AS callCount
            FROM ai_generation_log
            WHERE trip_id = #{tripId}
            GROUP BY stage
            ORDER BY FIELD(stage, 'PARSE','CANDIDATE','PREORDER','COMPOSE','VALIDATE','ROUTE','COPY')
            """)
    List<Map<String, Object>> sumTokensGroupByStage(@Param("tripId") Long tripId);

    /**
     * 某条行程的 token 按厂商分组（P4-C · 算成本用）。
     *
     * <p>为什么不能拿「按阶段分组」的结果直接算钱：一次生成里不同阶段可能落到不同厂商
     * （例如 PARSE 用 qwen、COPY 降级到 glm），而单价是每家一套。
     */
    @Select("""
            SELECT provider                                               AS provider,
                   CAST(SUM(prompt_tokens)     AS SIGNED)                  AS promptTokens,
                   CAST(SUM(completion_tokens) AS SIGNED)                  AS completionTokens,
                   CAST(SUM(total_tokens)      AS SIGNED)                  AS totalTokens,
                   COUNT(*)                                                AS callCount
            FROM ai_generation_log
            WHERE trip_id = #{tripId}
            GROUP BY provider
            ORDER BY totalTokens DESC
            """)
    List<Map<String, Object>> sumTokensGroupByProviderForTrip(@Param("tripId") Long tripId);

    /**
     * 某阶段某厂商的历史 completion_tokens 均值（P4-C · 中断节省估算的分母）。
     *
     * <p>只取<b>成功</b>记录：失败记录常常 token 为 null，混进来会把均值拉低。
     * 没有样本时返回 NULL，调用方据此放弃估算（宁可不估，也不编）。
     */
    @Select("""
            SELECT AVG(completion_tokens) AS avgCompletionTokens
            FROM ai_generation_log
            WHERE stage = #{stage}
              AND success = 1
              AND completion_tokens IS NOT NULL
              AND (#{provider} IS NULL OR provider = #{provider})
            """)
    Double avgCompletionTokens(@Param("stage") String stage, @Param("provider") String provider);

    /**
     * 时间窗内的 token 合计，按厂商分组（P4-C · stats 接口的全站口径，不限用户）。
     *
     * <p>与 {@link #sumTokensGroupByProvider} 的区别只是不按 userId 过滤 ——
     * 后台看板看的是全站，不是某个人。
     */
    @Select("""
            SELECT provider                                               AS provider,
                   CAST(SUM(prompt_tokens)     AS SIGNED)                  AS promptTokens,
                   CAST(SUM(completion_tokens) AS SIGNED)                  AS completionTokens,
                   CAST(SUM(total_tokens)      AS SIGNED)                  AS totalTokens,
                   COUNT(*)                                                AS callCount
            FROM ai_generation_log
            WHERE created_at >= #{from}
              AND created_at < #{to}
            GROUP BY provider
            ORDER BY totalTokens DESC
            """)
    List<Map<String, Object>> sumTokensGroupByProviderInRange(@Param("from") LocalDateTime from,
                                                              @Param("to") LocalDateTime to);

    /**
     * 时间窗内的总览（P4-C · stats 接口）。
     *
     * <p>{@code tripCount} 用 {@code COUNT(DISTINCT trip_id)}：一次生成会写多条阶段日志，
     * 拿 {@code COUNT(*)} 当「生成次数」会把它放大 5~7 倍。
     * 注意 trip_id 为 NULL 的行（PARSE 阶段有时还没有 tripId）不计入 tripCount，
     * 这是有意的 —— 它们无法归属到某一次生成。
     */
    @Select("""
            SELECT COUNT(*)                                                     AS totalCount,
                   CAST(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS SIGNED)  AS successCount,
                   COUNT(DISTINCT trip_id)                                      AS tripCount,
                   CAST(SUM(total_tokens) AS SIGNED)                            AS totalTokens,
                   CAST(AVG(duration_ms) AS SIGNED)                             AS avgDurationMs
            FROM ai_generation_log
            WHERE created_at >= #{from}
              AND created_at < #{to}
            """)
    Map<String, Object> statsOverview(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /**
     * 时间窗内耗时的 P95（毫秒）。
     *
     * <p>用窗口函数而不是「排序取第 95% 条」：后者要先把整表捞到应用层，
     * 数据一多就成了性能地雷。MySQL 8 的 {@code ROW_NUMBER()} 让它在库里一次算完。
     *
     * <p>{@code duration_ms IS NULL} 的行被排除：那是「没测到耗时」，不是「耗时 0」。
     */
    @Select("""
            SELECT CAST(MIN(duration_ms) AS SIGNED) AS p95DurationMs
            FROM (
                SELECT duration_ms,
                       ROW_NUMBER() OVER (ORDER BY duration_ms) AS rn,
                       COUNT(*)     OVER ()                     AS cnt
                FROM ai_generation_log
                WHERE created_at >= #{from}
                  AND created_at < #{to}
                  AND duration_ms IS NOT NULL
            ) t
            WHERE t.rn >= CEIL(t.cnt * 0.95)
            """)
    Long p95DurationMs(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 各阶段的平均 token / 平均耗时 / 成功率（P4-C · stats 接口）。
     */
    @Select("""
            SELECT stage                                                        AS stage,
                   COUNT(*)                                                     AS total,
                   CAST(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS SIGNED)  AS successCount,
                   CAST(AVG(total_tokens) AS SIGNED)                             AS avgTokens,
                   CAST(AVG(duration_ms)  AS SIGNED)                             AS avgDurationMs
            FROM ai_generation_log
            WHERE created_at >= #{from}
              AND created_at < #{to}
            GROUP BY stage
            ORDER BY FIELD(stage, 'PARSE','CANDIDATE','PREORDER','COMPOSE','VALIDATE','ROUTE','COPY')
            """)
    List<Map<String, Object>> statsByStage(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /**
     * 按地图模式分组的耗时（P4-C · stats 接口）。
     *
     * <p>必须 join {@code trip} 表：{@code ai_generation_log} 没有 map_mode 列，
     * 而「地图开着 vs 关着，耗时差多少」正是要看的指标。
     *
     * <p>不筛 {@code trip.deleted}：已逻辑删除的行程也确实发生过生成与花费，
     * 从成本统计里剔掉会让总花费对不上账。
     */
    @Select("""
            SELECT COALESCE(t.map_mode, 'UNKNOWN')            AS mapMode,
                   COUNT(*)                                   AS cnt,
                   CAST(AVG(l.duration_ms) AS SIGNED)         AS avgDurationMs
            FROM ai_generation_log l
            JOIN trip t ON t.id = l.trip_id
            WHERE l.created_at >= #{from}
              AND l.created_at < #{to}
            GROUP BY t.map_mode
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> statsByMapMode(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * 失败最多的错误码 Top N（时间窗内）。
     *
     * <p>只统计 {@code error_code IS NOT NULL} 的行：成功记录与「失败了但没归因」的记录
     * 都不该出现在错误榜里，否则榜单第一永远是空字符串。
     *
     * @return 每行 errorCode / cnt
     */
    @Select("""
            SELECT error_code            AS errorCode,
                   COUNT(*)              AS cnt
            FROM ai_generation_log
            WHERE success = 0
              AND error_code IS NOT NULL
              AND created_at >= #{from}
              AND created_at < #{to}
            GROUP BY error_code
            ORDER BY cnt DESC, error_code ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topErrors(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("limit") int limit);
}
