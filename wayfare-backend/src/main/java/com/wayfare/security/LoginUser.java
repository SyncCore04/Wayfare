package com.wayfare.security;

import java.io.Serializable;

/**
 * 登录用户信息
 * 存储在JWT中，解析后放入UserContext
 */
public class LoginUser implements Serializable {

    private Long userId;
    private String username;
    private String nickname;
    private String role;

    public LoginUser() {}

    public LoginUser(Long userId, String username, String nickname, String role) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.role = role;
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
