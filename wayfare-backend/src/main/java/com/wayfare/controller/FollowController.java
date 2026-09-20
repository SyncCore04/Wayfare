package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.entity.Follow;
import com.wayfare.entity.User;
import com.wayfare.security.UserContext;
import com.wayfare.service.FollowService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 关注控制器
 */
@RestController
@RequestMapping("/follows")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 关注/取消关注
     */
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggleFollow(@RequestParam Long followingId) {
        Long userId = UserContext.getUserId();
        boolean following = followService.toggleFollow(userId, followingId);
        Map<String, Object> result = new HashMap<>();
        result.put("following", following);
        result.put("followerCount", followService.getFollowerCount(followingId));
        result.put("followingCount", followService.getFollowingCount(userId));
        return Result.success(result);
    }

    /**
     * 检查是否已关注
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkFollow(@RequestParam Long followingId) {
        Long userId = UserContext.getUserId();
        boolean following = followService.isFollowing(userId, followingId);
        Map<String, Object> result = new HashMap<>();
        result.put("following", following);
        return Result.success(result);
    }

    /**
     * 获取用户粉丝数
     */
    @GetMapping("/{userId}/followers/count")
    public Result<Long> getFollowerCount(@PathVariable Long userId) {
        return Result.success(followService.getFollowerCount(userId));
    }

    /**
     * 获取用户关注数
     */
    @GetMapping("/{userId}/following/count")
    public Result<Long> getFollowingCount(@PathVariable Long userId) {
        return Result.success(followService.getFollowingCount(userId));
    }

    /**
     * 获取用户粉丝列表
     */
    @GetMapping("/{userId}/followers")
    public Result<IPage<Follow>> getFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(followService.getFollowers(userId, pageNum, pageSize));
    }

    /**
     * 获取用户关注列表
     */
    @GetMapping("/{userId}/following")
    public Result<IPage<Follow>> getFollowings(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(followService.getFollowings(userId, pageNum, pageSize));
    }

    /**
     * 获取我的关注列表（含用户详细信息）
     */
    @GetMapping("/following")
    public Result<IPage<User>> myFollowing(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(followService.getFollowingUsers(userId, pageNum, pageSize));
    }

    /**
     * 获取我的粉丝列表（含用户详细信息及是否已关注）
     */
    @GetMapping("/followers")
    public Result<IPage<User>> myFollowers(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(followService.getFollowerUsers(userId, pageNum, pageSize));
    }

    /**
     * 获取我的关注统计
     */
    @GetMapping("/my/stats")
    public Result<Map<String, Object>> myStats() {
        Long userId = UserContext.getUserId();
        Map<String, Object> result = new HashMap<>();
        result.put("followerCount", followService.getFollowerCount(userId));
        result.put("followingCount", followService.getFollowingCount(userId));
        return Result.success(result);
    }
}
