package com.wayfare.controller;

import com.wayfare.common.result.Result;
import com.wayfare.dto.LoginDTO;
import com.wayfare.dto.LoginVO;
import com.wayfare.dto.RegisterDTO;
import com.wayfare.security.TokenBlacklist;
import com.wayfare.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 鉴权接口：注册 / 登录 / 登出。
 *
 * <p>{@code /auth/login} 与 {@code /auth/register} 在 WebMvcConfig 白名单里（可匿名调）；
 * {@code /auth/logout} <b>不在</b>白名单里，所以它必须先通过拦截器校验，
 * 这样「登出」这件事才有确定的身份上下文，也避免匿名请求把别人的 token 拉黑。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final TokenBlacklist tokenBlacklist;

    @Value("${jwt.header}")
    private String authHeader;

    public AuthController(UserService userService, TokenBlacklist tokenBlacklist) {
        this.userService = userService;
        this.tokenBlacklist = tokenBlacklist;
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO registerDTO) {
        return Result.success(userService.register(registerDTO));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO, HttpServletRequest request) {
        String ip = getClientIp(request);
        return Result.success(userService.login(loginDTO, ip));
    }

    /**
     * 登出：把当前 token 加入 Redis 黑名单。
     *
     * <p>仅前端删掉本地 token 是不够的 —— JWT 无状态，在有效期内仍然有效，
     * 必须服务端登记失效才能真登出。
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = tokenBlacklist.resolveToken(request.getHeader(authHeader));
        tokenBlacklist.blacklist(token);
        return Result.success();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
