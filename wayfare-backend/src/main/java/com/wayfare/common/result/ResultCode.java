package com.wayfare.common.result;

/**
 * 统一返回状态码枚举
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    ERROR(500, "操作失败"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有权限访问"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    USERNAME_EXIST(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "密码错误"),
    ACCOUNT_DISABLED(1004, "账号已被禁用"),
    OLD_PASSWORD_ERROR(1005, "原密码错误"),
    WORK_NOT_FOUND(1101, "作品不存在"),
    WORK_NO_PERMISSION(1102, "无权操作该作品"),
    TAG_NOT_FOUND(1201, "标签不存在"),
    TAG_NAME_EXIST(1202, "标签名称已存在"),
    CATEGORY_NOT_FOUND(1251, "分类不存在"),
    CATEGORY_NAME_EXIST(1252, "同级下已存在同名分类"),
    CATEGORY_HAS_WORK(1253, "该分类下还有作品，无法删除"),
    CATEGORY_HAS_CHILDREN(1254, "该分类下还有子分类，无法删除"),
    // ---- 连接器 / 大模型：厂商返回的各类失败统一映射到这几个码 ----
    // 这样上层不用去判断「是 401 还是 429」，只看业务码就能决定是否降级、
    // 以及给用户什么提示（认证失败要管理员查 Key，限流只需要稍后重试）。
    LLM_NOT_AVAILABLE(2101, "没有可用的大模型厂商"),
    LLM_AUTH_FAIL(2102, "大模型认证失败，请检查 API Key"),
    LLM_RATE_LIMIT(2103, "大模型调用频率超限，请稍后重试"),
    LLM_TIMEOUT(2104, "大模型调用超时"),
    LLM_SERVER_ERROR(2105, "大模型服务端错误"),
    LLM_BAD_REQUEST(2106, "大模型请求参数被拒绝"),
    LLM_PARSE_ERROR(2107, "大模型响应解析失败"),
    LLM_DISABLED(2108, "大模型能力已被关闭"),
    // ---- 管线（P3）----
    // 与 AiErrorCode.SCHEMA_INVALID 是「一个场景两个视角」的关系：
    // 本码给用户看（提示换个说法重试），AiErrorCode 写进 ai_generation_log 给运维归因。
    SCHEMA_INVALID(2109, "没能理解你的行程需求，请换个说法再试一次"),
    // ---- 地图连接器 ----
    // 百度的失败原因（AK 错 / 配额耗尽 / 参数非法）语义差别很大，必须分开：
    // AK 错要管理员动手、配额耗尽要等或换 Key、参数非法是我们自己的 bug。
    MAP_NOT_AVAILABLE(2201, "地图服务不可用"),
    MAP_DISABLED(2202, "地图连接器已被关闭"),
    MAP_AUTH_FAIL(2203, "地图服务认证失败，请检查 AK"),
    MAP_QUOTA_EXCEEDED(2204, "地图服务配额已用尽"),
    MAP_REQUEST_FAILED(2205, "地图服务请求失败"),
    COMMENT_NOT_FOUND(1301, "评论不存在"),
    COMMENT_NO_PERMISSION(1302, "无权操作该评论"),
    LIKE_ALREADY(1401, "已经点过赞了"),
    LIKE_NOT_FOUND(1402, "点赞记录不存在"),
    FAVORITE_ALREADY(1501, "已经收藏过了"),
    FAVORITE_NOT_FOUND(1502, "收藏记录不存在"),
    FOLLOW_SELF(1601, "不能关注自己"),
    FOLLOW_ALREADY(1602, "已经关注了"),
    FOLLOW_NOT_FOUND(1603, "关注关系不存在"),
    MESSAGE_EMPTY(1701, "消息内容不能为空"),
    MESSAGE_RECEIVER_NOT_FOUND(1702, "接收者不存在"),
    FILE_TYPE_NOT_ALLOWED(1801, "不支持的文件类型"),
    FILE_SIZE_EXCEEDED(1802, "文件大小超出限制"),
    FILE_UPLOAD_FAILED(1803, "文件上传失败"),
    CONTENT_VIOLATION(1901, "内容包含违规关键词，请修改后重试"),
    SYSTEM_ERROR(5000, "系统内部错误"),
    DATABASE_ERROR(5001, "数据库操作异常"),
    REDIS_ERROR(5002, "Redis操作异常"),
    UPLOAD_ERROR(5003, "文件上传失败");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() { return code; }
    public String getMessage() { return message; }
}
