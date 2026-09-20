package com.wayfare.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 登出黑名单（基于 Redis）。
 *
 * <p>为什么需要它：JWT 是无状态的，签发后在有效期内一直有效。用户登出、或者管理员
 * 想踢掉某个 token 时，必须有一份「已失效 token」名单，否则登出只是前端删掉了本地存储，
 * token 本身仍然能调通接口。这是 P0-C 明确要求的（登出 → token 加入 Redis 黑名单）。
 *
 * <p>三个设计决定，都写清理由：
 * <ol>
 *   <li><b>Redis 里存 token 的 SHA-256 摘要，不存原文</b>。Redis 的 key 会出现在
 *       MONITOR 输出、慢日志、运维快照里，存原文等于把可用凭证到处撒；摘要不可逆，
 *       且比对时同样能精确命中。</li>
 *   <li><b>TTL 取该 token 的剩余有效期</b>。token 自己过期后黑名单条目就没有意义了，
 *       让它自动消失，避免黑名单无限膨胀。</li>
 *   <li><b>Redis 故障时选择「放行」而非「全部拒绝」</b>（fail-open，仅记 WARN）。
 *       项目铁律是「任一时刻系统都完整可用」：Redis 挂掉时若把所有请求都判 401，
 *       整个系统会直接不可用。代价是登出后的 token 在 Redis 恢复前会短暂仍然有效 ——
 *       这是有意识的取舍，不是漏写。</li>
 * </ol>
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    private static final String KEY_PREFIX = "auth:blacklist:";

    /** 拿不到 token 过期时间时的兜底 TTL（与 jwt.expiration 默认值一致：7 天） */
    private static final long DEFAULT_TTL_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;

    @Value("${jwt.prefix}")
    private String tokenPrefix;

    public TokenBlacklist(StringRedisTemplate stringRedisTemplate, JwtUtil jwtUtil) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 把 token 加入黑名单。token 已过期则直接忽略（本来就用不了了）。
     */
    public void blacklist(String token) {
        if (!StringUtils.hasText(token)) return;

        long ttl = DEFAULT_TTL_MILLIS;
        Date expiration = jwtUtil.getExpirationFromToken(token);
        if (expiration != null) {
            ttl = expiration.getTime() - System.currentTimeMillis();
            if (ttl <= 0) {
                log.debug("token 已过期，无需加入黑名单");
                return;
            }
        }
        try {
            stringRedisTemplate.opsForValue().set(keyOf(token), "1", ttl, TimeUnit.MILLISECONDS);
            log.info("token 已加入黑名单, 剩余有效期 {} ms", ttl);
        } catch (Exception e) {
            // 加不进黑名单不算致命：token 仍会在自然过期后失效，但要把问题暴露在日志里
            log.warn("写入 token 黑名单失败（Redis 不可用？）: {}", e.getMessage());
        }
    }

    /**
     * 判断 token 是否已被登出。
     *
     * @return true 表示已失效；Redis 异常时返回 false（fail-open，见类注释）
     */
    public boolean isBlacklisted(String token) {
        if (!StringUtils.hasText(token)) return false;
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(keyOf(token)));
        } catch (Exception e) {
            log.warn("查询 token 黑名单失败（Redis 不可用？），本次按未拉黑处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Authorization 请求头里剥掉前缀，取出裸 token。
     * 前缀来自配置（jwt.prefix），不硬编码。
     *
     * @return 取不到则返回 null
     */
    public String resolveToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) return null;
        String prefix = tokenPrefix != null ? tokenPrefix : "Bearer ";
        String token = authorizationHeader.trim();
        if (token.startsWith(prefix)) {
            token = token.substring(prefix.length());
        }
        token = token.trim();
        return token.isEmpty() ? null : token;
    }

    private String keyOf(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定带 SHA-256，走到这里说明环境异常，退回用原文做 key 也好过功能全废
            log.error("SHA-256 不可用，退回使用 token 原文作为黑名单 key", e);
            return raw;
        }
    }
}
