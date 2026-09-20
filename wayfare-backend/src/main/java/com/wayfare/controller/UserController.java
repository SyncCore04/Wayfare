package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.Result;
import com.wayfare.common.result.ResultCode;
import com.wayfare.dto.UserUpdateDTO;
import com.wayfare.entity.User;
import com.wayfare.security.UserContext;
import com.wayfare.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public Result<User> getCurrentUser() {
        Long userId = UserContext.getUserId();
        return Result.success(userService.getById(userId));
    }

    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @GetMapping("/page")
    public Result<IPage<User>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        checkAdmin();
        return Result.success(userService.page(pageNum, pageSize, keyword, status));
    }

    @PutMapping("/me")
    public Result<Void> updateCurrentUser(@Valid @RequestBody UserUpdateDTO userUpdateDTO) {
        Long userId = UserContext.getUserId();
        userService.update(userId, userUpdateDTO);
        return Result.success();
    }

    @PutMapping("/me/password")
    public Result<Void> changePassword(@RequestBody Map<String, String> params) {
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        if (oldPassword == null || newPassword == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "原密码和新密码不能为空");
        }
        Long userId = UserContext.getUserId();
        userService.changePassword(userId, oldPassword, newPassword);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        checkAdmin();
        userService.updateStatus(id, status);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        checkAdmin();
        userService.delete(id);
        return Result.success();
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
