package com.wayfare.mapper.provider;

import java.util.Map;

/**
 * 「生成明细」分页查询的动态 SQL（P6-B）。
 *
 * <p><b>为什么单独抽一个 Provider 类，而不是在 Mapper 上写两个 {@code @Select}</b>：
 * 分页必然要两条 SQL —— 一条取当页数据、一条取总数 —— 而两条的 {@code WHERE} 必须<b>完全一致</b>。
 * 各写一份的话，将来加一个筛选条件很容易只改一处，症状是「翻到第 2 页数字突然对不上」
 * 或「筛了目的地但总数没变」，而且不会报错。这里把 {@code WHERE} 收成一个方法，两处共用。
 *
 * <p><b>为什么用 Java 拼串而不是 MyBatis 的 {@code <if>} 标签</b>：
 * Provider 返回的 SQL 只有在用 {@code <script>} 包起来时才会走动态标签解析，
 * 而 {@code <script>} 里 {@code created_at < #{to}} 这种裸的 {@code <} 是非法 XML（得写成 {@code &lt;}）。
 * 拼串版本没有这个问题，可读性也更好。拼的只有<b>固定条件片段</b>，
 * 所有取值仍然走 {@code #{}} 占位符，不存在注入面。
 *
 * <p>筛选一律「为 null 就不过滤」：管理员不填就是不筛，
 * 而不是筛 {@code = null}（那会一条都查不出来）。
 */
public class GenerationLogSqlProvider {

    /** 明细行要的字段：日志本身 + trip 的目的地/天数/地图模式 + 用户昵称（看板要显示「是谁跑的」） */
    private static final String SELECT_COLUMNS = """
            SELECT l.id                AS id,
                   l.created_at        AS createdAt,
                   l.user_id           AS userId,
                   u.nickname          AS userName,
                   l.trip_id           AS tripId,
                   t.destination       AS destination,
                   t.days              AS days,
                   t.map_mode          AS mapMode,
                   l.stage             AS stage,
                   l.provider          AS provider,
                   l.model             AS model,
                   l.prompt_tokens     AS promptTokens,
                   l.completion_tokens AS completionTokens,
                   l.total_tokens      AS totalTokens,
                   l.duration_ms       AS durationMs,
                   l.success           AS success,
                   l.error_code        AS errorCode,
                   l.error_msg         AS errorMsg
            """;

    /**
     * 两条 SQL 共用的来源子句。
     *
     * <p>用 {@code LEFT JOIN} 而不是 {@code JOIN}：PARSE 阶段可能还没有 tripId
     * （trip 是 Step1 之后才建的），内连接会把这些行整条丢掉 ——
     * 而它们恰恰是「解析失败」最需要被看到的那一批。
     */
    private static final String FROM_CLAUSE = """
            FROM ai_generation_log l
            LEFT JOIN trip t     ON t.id = l.trip_id
            LEFT JOIN sys_user u ON u.id = l.user_id
            """;

    /** 当页数据 */
    public String pageSql(Map<String, Object> q) {
        return SELECT_COLUMNS + FROM_CLAUSE + whereClause(q)
                + " ORDER BY l.created_at DESC, l.id DESC"
                + " LIMIT #{offset}, #{size}";
    }

    /** 总数（与 {@link #pageSql} 共用同一个 WHERE） */
    public String countSql(Map<String, Object> q) {
        return "SELECT COUNT(*) " + FROM_CLAUSE + whereClause(q);
    }

    /** 唯一的一份筛选条件，两个查询都从这里取 */
    private String whereClause(Map<String, Object> q) {
        StringBuilder sql = new StringBuilder("""
                WHERE l.created_at >= #{from}
                  AND l.created_at <  #{to}
                """);
        if (q.get("success") != null) {
            sql.append("  AND l.success = #{success}\n");
        }
        if (q.get("stage") != null) {
            sql.append("  AND l.stage = #{stage}\n");
        }
        if (q.get("model") != null) {
            sql.append("  AND l.model = #{model}\n");
        }
        if (q.get("mapMode") != null) {
            sql.append("  AND t.map_mode = #{mapMode}\n");
        }
        if (q.get("destination") != null) {
            // 模糊匹配：管理员通常只记得地名的一部分
            sql.append("  AND t.destination LIKE CONCAT('%', #{destination}, '%')\n");
        }
        return sql.toString();
    }
}
