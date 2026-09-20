package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.dto.LoginDTO;
import com.wayfare.dto.LoginVO;
import com.wayfare.dto.RegisterDTO;
import com.wayfare.dto.UserUpdateDTO;
import com.wayfare.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 用户注册
     */
    LoginVO register(RegisterDTO registerDTO);

    /**
     * 用户登录
     */
    LoginVO login(LoginDTO loginDTO, String ip);

    /**
     * 根据ID查询用户
     */
    User getById(Long id);

    /**
     * 分页查询用户列表（管理员）
     */
    IPage<User> page(Integer pageNum, Integer pageSize, String keyword, Integer status);

    /**
     * 更新用户信息
     */
    void update(Long userId, UserUpdateDTO userUpdateDTO);

    /**
     * 删除用户（管理员，逻辑删除）
     */
    void delete(Long id);

    /**
     * 禁用/启用用户（管理员）
     */
    void updateStatus(Long id, Integer status);

    /**
     * 修改密码
     */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
