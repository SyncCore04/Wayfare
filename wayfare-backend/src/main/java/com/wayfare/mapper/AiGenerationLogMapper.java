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
