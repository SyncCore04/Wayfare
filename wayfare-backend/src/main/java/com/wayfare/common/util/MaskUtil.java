package com.wayfare.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密钥脱敏工具。<b>凡是可能含密钥的字符串，在写日志/入库/返回给前端之前都必须过这里。</b>
 *
 * <p>为什么单独抽一个类而不是各写各的：脱敏规则一旦分散，就一定会有地方漏掉 ——
 * 而漏一次的代价是密钥进日志、进工单、进截图。集中在一处，测试也只测一处。
 *
 * <p>本类无状态、纯函数，方便单测。
 */
public final class MaskUtil {

    /** 掩码后缀 */
    private static final String MASK = "****";

    /** 保留的前缀长度：前 4 位足以让管理员辨认「填的是不是那一把 Key」，又不构成泄露 */
    private static final int KEEP_PREFIX = 4;

    /** 默认截断长度（手册要求 request_summary 截到 500 字符） */
    public static final int DEFAULT_MAX_LENGTH = 500;

    /** URL query 里的敏感参数名 */
    private static final Pattern URL_SECRET =
            Pattern.compile("(?i)\\b(ak|key|api_?key|access_?token|secret|token|password)=([^&\\s]+)");

    /** JSON 里的敏感字段名（含 Authorization 头被序列化的情况） */
    private static final Pattern JSON_SECRET =
            Pattern.compile("(?i)\"(ak|api_?key|access_?token|secret|token|password|authorization)\"\\s*:\\s*\"([^\"]*)\"");

    /** 请求头形式的 Bearer / Basic 凭证 */
    private static final Pattern BEARER =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+([A-Za-z0-9\\-._~+/=]+)");

    /** 配置键命中这些片段的视为敏感（用于 sys_config 的回显掩码） */
    private static final String[] SECRET_KEY_HINTS = {
            ".ak", "api-key", "apikey", "secret", "token", "password"
    };

    /** 配置键是否敏感（如 map.baidu.ak） */
    public static boolean isSecretKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (String hint : SECRET_KEY_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /**
     * 按配置键掩码配置值：敏感键只留前 4 位，非敏感键原样返回。
     * 这是 sys_config / 配置接口回显的唯一入口（手册 12.3：后台回显一律掩码）。
     */
    public static String maskConfigValue(String key, String value) {
        if (!isSecretKey(key)) return value;
        return maskSecret(value);
    }

    private MaskUtil() {
    }

    /**
     * 把一段密钥变成「前 4 位 + ****」。
     *
     * <p>约定：
     * <ul>
     *   <li>null / 空串 → 返回空串（不返回 null，避免调用方再判空）</li>
     *   <li>长度 ≤ 4 → 只返回 {@code ****}（前 4 位就可能是全部内容，不能再露）</li>
     *   <li>其他 → 前 4 位 + {@code ****}</li>
     * </ul>
     *
     * <p>例：{@code maskSecret("sk-abcdefghijklmn")} → {@code "sk-a****"}
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= KEEP_PREFIX) {
            return MASK;
        }
        return secret.substring(0, KEEP_PREFIX) + MASK;
    }

    /**
     * 脱掉 URL 里的密钥参数值（{@code ak=xxx} / {@code api_key=xxx} / {@code access_token=xxx} 等）。
     * 百度地图的 AK 就在 query 里，所以外呼日志记 URL 之前必须先过这一步。
     */
    public static String maskUrlSecrets(String url) {
        if (url == null || url.isEmpty()) return "";
        Matcher m = URL_SECRET.matcher(url);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) + "=" + maskSecret(m.group(2));
            // Matcher.quoteReplacement：掩码里不含 $ \ 但保持习惯，避免将来改动踩坑
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 脱掉 JSON 文本里的密钥字段值。
     * 大模型的请求体里有 {@code api_key} 这类字段时，必须先把值换掉再记录。
     */
    public static String maskJsonSecrets(String json) {
        if (json == null || json.isEmpty()) return "";
        Matcher m = JSON_SECRET.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb,
                    Matcher.quoteReplacement("\"" + m.group(1) + "\":\"" + maskSecret(m.group(2)) + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 脱掉 Bearer/Basic 后面的凭证 */
    public static String maskBearer(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = BEARER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " " + maskSecret(m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 外呼日志的 request_summary 专用：依次应用上面三种脱敏，再截断。
     * 顺序很重要 —— 先脱敏再截断，否则截断可能把密钥切成前半段留在库里。
     */
    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_MAX_LENGTH);
    }

    public static String sanitize(String raw, int maxLength) {
        if (raw == null || raw.isEmpty()) return "";
        String masked = maskBearer(maskJsonSecrets(maskUrlSecrets(raw)));
        if (maxLength > 0 && masked.length() > maxLength) {
            return masked.substring(0, maxLength) + "...(truncated)";
        }
        return masked;
    }
}
