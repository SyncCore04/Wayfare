package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.entity.Favorite;
import com.wayfare.entity.Work;
import com.wayfare.security.UserContext;
import com.wayfare.service.FavoriteService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 收藏控制器
 */
@RestController
@RequestMapping("/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * 收藏/取消收藏
     */
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggleFavorite(@RequestParam Long workId) {
        Long userId = UserContext.getUserId();
        boolean favorited = favoriteService.toggleFavorite(userId, workId);
        Long count = favoriteService.getFavoriteCount(workId);
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", favorited);
        result.put("count", count);
        return Result.success(result);
    }

    /**
     * 检查是否已收藏
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkFavorite(@RequestParam Long workId) {
        Long userId = UserContext.getUserId();
        boolean favorited = favoriteService.isFavorited(userId, workId);
        Long count = favoriteService.getFavoriteCount(workId);
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", favorited);
        result.put("count", count);
        return Result.success(result);
    }

    /**
     * 获取作品收藏数
     */
    @GetMapping("/count")
    public Result<Long> getFavoriteCount(@RequestParam Long workId) {
        return Result.success(favoriteService.getFavoriteCount(workId));
    }

    /**
     * 获取我的收藏列表（含作品详细信息）
     */
    @GetMapping("/my")
    public Result<IPage<Work>> myFavorites(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(favoriteService.getUserFavoriteWorks(userId, pageNum, pageSize));
    }
}
