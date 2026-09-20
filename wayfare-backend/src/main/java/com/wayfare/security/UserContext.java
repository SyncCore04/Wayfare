package com.wayfare.security;

/**
 * 当前登录用户上下文
 * 基于ThreadLocal存储，在拦截器中设置，在Controller/Service中获取
 * 请求结束后必须清除，防止内存泄漏
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> CONTEXT = new ThreadLocal<>();

    /**
     * 设置当前登录用户
     */
    public static void set(LoginUser loginUser) {
        CONTEXT.set(loginUser);
    }

    /**
     * 获取当前登录用户
     */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前登录用户ID
     */
    public static Long getUserId() {
        LoginUser user = CONTEXT.get();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 判断当前用户是否为管理员
     */
    public static boolean isAdmin() {
        LoginUser user = CONTEXT.get();
        return user != null && user.isAdmin();
    }

    /**
     * 判断是否已登录
     */
    public static boolean isLogin() {
        return CONTEXT.get() != null;
    }

    /**
     * 清除当前用户上下文（请求结束时调用）
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
