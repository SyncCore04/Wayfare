package com.wayfare.trip;

/**
 * 管线统一错误码（P2-C 定死，后续所有阶段复用）。
 *
 * <p>这些码写进 {@code ai_generation_log.error_code}（VARCHAR(30)），
 * <b>刻意用可读字符串而不是数字</b>：它们要出现在日志、监控看板、接口响应里，
 * 排查问题时「MAP_BREAKER_OPEN」比「2204」有用得多，省那几个字节没有意义。
 *
 * <p><b>为什么不复用 {@code ResultCode}</b>：两者用途不同，硬合并会互相污染。
 * {@code ResultCode} 是<b>给用户看的</b>接口返回码（带中文提示文案，
 * 用户会看到「大模型认证失败，请检查 API Key」）；本枚举是<b>给运维/开发看的</b>
 * 内部归因码（记录「这一步为什么失败」，用于聚合统计与告警）。
 * 一个失败场景常常是「一个 error_code + 一个 ResultCode」的关系，不是二选一。
 *
 * <p>每个常量都带一句「触发场景」，因为验收要求输出枚举表 ——
 * 而且写代码的人（包括未来的我）看到码名未必能立刻想到它在什么条件下产生。
 */
public enum AiErrorCode {

    // ---------- 大模型相关 ----------
    LLM_TIMEOUT("大模型在 llm.timeout-ms 内未返回（长输出阶段如 COMPOSE 最容易触发）"),
    LLM_RATE_LIMIT("大模型返回 429 限流；换厂商或稍后重试，不要原地死等"),
    LLM_AUTH_FAIL("大模型返回 401/403，API Key 无效或过期；需管理员介入，不该静默降级"),
    LLM_PARSE_FAIL("大模型返回的内容不是合法 JSON，或字段类型/枚举越界（Schema 校验失败）"),
    LLM_SERVER_ERROR("大模型返回 5xx；属对方故障，可降级到备用厂商"),

    // ---------- 地图相关 ----------
    MAP_UNAVAILABLE("地图连接器整体关闭，或没有可用 AK；数据降级为 ESTIMATED（属能力降级，不是故障）"),
    MAP_AUTH_FAIL("地图返回认证失败（百度「AK 有误」时 status=200，靠 message 文案兜底识别）"),
    MAP_BREAKER_OPEN("地图熔断器处于打开状态，本次请求未真正发出；等待半开试探或转用缓存"),

    // ---------- 管线自身 ----------
    CANDIDATE_SHORTAGE("候选点位不足，撑不起用户要求的天数与节奏（如「3 天要 15 个点」但只搜到 6 个）"),
    VALIDATION_FAILED("本地六条约束校验不通过，且重排达到 trip.max-replan-rounds 上限（绝不死循环）"),
    SCHEMA_INVALID("上游输入本身不合法（如 days 超出 trip.max-days），重试也救不回来"),
    CLIENT_DISCONNECTED("SSE 客户端提前断开，生成中止；已生成的内容不丢弃，但要记一笔以免误判为系统故障");

    private final String scene;

    AiErrorCode(String scene) {
        this.scene = scene;
    }

    /** 触发场景说明（验收要求输出这张表） */
    public String getScene() {
        return scene;
    }

    /**
     * 按名字解析，未知返回 null 而不抛异常 ——
     * 日志里出现一个陌生的码不该让整个查询失败。
     */
    public static AiErrorCode fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AiErrorCode code : values()) {
            if (code.name().equals(name.trim())) {
                return code;
            }
        }
        return null;
    }
}
