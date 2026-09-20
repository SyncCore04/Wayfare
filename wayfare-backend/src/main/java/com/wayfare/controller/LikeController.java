package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.entity.LikeRecord;
import com.wayfare.security.UserContext;
import com.wayfare.service.LikeService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 点赞控制器
 * target_type: 1作品 2评论
 */
@RestController
@RequestMapping("/likes")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    /**
     * 点赞/取消点赞
     */
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggleLike(
            @RequestParam Integer targetType,
            @RequestParam Long targetId) {
        Long userId = UserContext.getUserId();
        boolean liked = likeService.toggleLike(userId, targetType, targetId);
        Long count = likeService.getLikeCount(targetType, targetId);
        Map<String, Object> result = new HashMap<>();
        result.put("liked", liked);
        result.put("count", count);
        return Result.success(result);
    }

    /**
     * 检查是否已点赞
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkLike(
            @RequestParam Integer targetType,
            @RequestParam Long targetId) {
        Long userId = UserContext.getUserId();
        boolean liked = likeService.isLiked(userId, targetType, targetId);
        Long count = likeService.getLikeCount(targetType, targetId);
        Map<String, Object> result = new HashMap<>();
        result.put("liked", liked);
        result.put("count", count);
        return Result.success(result);
    }

    /**
     * 获取点赞数
     */
    @GetMapping("/count")
    public Result<Long> getLikeCount(
            @RequestParam Integer targetType,
            @RequestParam Long targetId) {
        return Result.success(likeService.getLikeCount(targetType, targetId));
    }

    /**
     * 获取我的点赞列表
     */
    @GetMapping("/my")
    public Result<IPage<LikeRecord>> myLikes(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(likeService.getUserLikes(userId, pageNum, pageSize));
    }
}
