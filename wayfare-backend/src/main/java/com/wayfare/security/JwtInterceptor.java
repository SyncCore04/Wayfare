package com.wayfare.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证拦截器。
 *
 * <p><b>本类同时是「哪些接口允许匿名」的唯一权威</b>（见 {@link #PUBLIC_PATHS}），
 * {@code WebMvcConfig} 只负责把拦截器注册到 {@code /**}，不再使用 excludePathPatterns。
 *
 * <p><b>为什么必须这样改（P0-C 修正）</b>：最初的做法是把公开路径写进
 * {@code excludePathPatterns}，但那样有个致命副作用 —— <b>被排除的路径根本不进拦截器，
 * 于是 UserContext 永远不会被填充</b>。结果是「已登录用户访问公开路径」时后端认不出他是谁：
 * 例如 {@code PUT /works/{id}} 为了让未登录用户能看详情而被放行（白名单按路径匹配、不区分方法），
 * 登录用户去改自己的作品反而拿到「未登录」。
 *
 * <p>现在的逻辑分两步，互不干扰：
 * <ol>
 *   <li><b>先认身份</b>：只要请求带了有效 token，就解析并塞进 UserContext ——
 *       无论该路径是否公开；</li>
 *   <li><b>再判放行</b>：只有「没带 token 且路径不公开」才返回 401。</li>
 * </ol>
 *
 * <p>另一个有意选择：<b>带了 token 但 token 无效/已登出时，一律 401（即使路径公开）</b>。
 * 这样会话过期会立刻暴露，前端 axios 收到 401 会清掉本地 token 并跳登录，
 * 下一个请求就变成干净的匿名请求、公开页面照常能看 —— 一次往返即可自愈；
 * 反过来若在这种情况静默降级为匿名，只会把问题藏起来。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtInterceptor.class);

    /**
     * 允许匿名访问的路径白名单。
     *
     * <p>约定：<b>不在白名单里的路径一律需要登录</b>。管理类接口（如
     * {@code PUT /works/{id}/status}、{@code /categories} 的写操作）刻意不在此列。
     *
     * <p>{@code /works/{id:[0-9]+}} 用正则约束只放行纯数字 id —— 若写成 {@code /works/{id}}，
     * {@code /works/my}（我的作品）会被同一模式命中而变成匿名可访问。
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            // 鉴权自身
            "/auth/login",
            "/auth/register",
            // 基础设施
            "/health",
            "/error",
            "/uploads/**",
            // 接口文档
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            // 公开浏览
            "/works/page",
            "/works/{id:[0-9]+}",
            "/categories/tree",
            "/tags/hot",
            "/comments/work/**",
            "/recommend/hot"
    );

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final ObjectMapper objectMapper;
    private final List<PathPattern> publicPatterns;

    @Value("${jwt.header}")
    private String header;

    public JwtInterceptor(JwtUtil jwtUtil, TokenBlacklist tokenBlacklist, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklist = tokenBlacklist;
        this.objectMapper = objectMapper;
        PathPatternParser parser = new PathPatternParser();
        this.publicPatterns = PUBLIC_PATHS.stream().map(parser::parse).collect(Collectors.toList());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跨域预检请求不带 Authorization，必须放行，否则浏览器拿不到 CORS 响应头
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = tokenBlacklist.resolveToken(request.getHeader(header));

        // 第一步：认身份。带了 token 就尝试解析，成功即写入上下文（公开路径也不例外）
        if (StringUtils.hasText(token)) {
            if (tokenBlacklist.isBlacklisted(token)) {
                log.debug("请求携带的 token 已被登出拉黑, uri={}", request.getRequestURI());
                writeUnauthorized(response, "登录已失效，请重新登录");
                return false;
            }
            LoginUser loginUser = jwtUtil.parseToken(token);
            if (loginUser == null) {
                writeUnauthorized(response, "登录已过期，请重新登录");
                return false;
            }
            UserContext.set(loginUser);
            log.debug("登录校验通过: userId={}, username={}, role={}, uri={}",
                    loginUser.getUserId(), loginUser.getUsername(), loginUser.getRole(), request.getRequestURI());
            return true;
        }

        // 第二步：没带 token。公开路径放行（匿名浏览），其余 401
        if (isPublicPath(resolvePath(request))) {
            return true;
        }
        writeUnauthorized(response, "未登录或登录已过期，请先登录");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // ThreadLocal 必须清理，否则线程复用时会串号（下一个请求读到上一个用户）
        UserContext.clear();
    }

    /** 取应用内路径（去掉 context-path，本项目是 /api），用于和白名单比对 */
    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private boolean isPublicPath(String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern pattern : publicPatterns) {
            if (pattern.matches(container)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直接写回 401 JSON。
     * 注意这里是「真 HTTP 401」，不是包装成 200 + 业务码 —— 请求在进入 Controller 前就被中断，
     * 前端 axios 会走 error 分支，而 error 分支里正好有清 token 跳登录的处理逻辑。
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<Void> result = Result.error(ResultCode.UNAUTHORIZED.getCode(), message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
