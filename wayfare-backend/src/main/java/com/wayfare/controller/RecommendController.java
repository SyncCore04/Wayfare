package com.wayfare.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.common.result.Result;
import com.wayfare.entity.Work;
import com.wayfare.security.UserContext;
import com.wayfare.service.BrowseHistoryService;
import com.wayfare.service.RecommendService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐控制器
 * 提供浏览历史记录、个性化推荐、热门排行榜、首页瀑布流等接口
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    private final RecommendService recommendService;
    private final BrowseHistoryService browseHistoryService;

    public RecommendController(RecommendService recommendService,
                               BrowseHistoryService browseHistoryService) {
        this.recommendService = recommendService;
        this.browseHistoryService = browseHistoryService;
    }

    /**
     * 记录用户浏览历史
     * 前端在用户查看作品详情时调用，同时更新用户标签偏好
     */
    @PostMapping("/browse/{workId}")
    public Result<Map<String, Object>> recordBrowse(@PathVariable Long workId) {
        Long userId = UserContext.getUserId();
        browseHistoryService.recordBrowse(userId, workId);

        Map<String, Object> result = new HashMap<>();
        result.put("workId", workId);
        result.put("browsed", true);
        result.put("totalBrowsed", browseHistoryService.getBrowseCount(userId));
        return Result.success(result);
    }

    /**
     * 首页瀑布流推荐
     * 已登录用户：70%个性化推荐 + 30%热门补充
     * 未登录用户：返回热门作品
     */
    @GetMapping("/feed")
    public Result<IPage<Work>> getFeed(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = null;
        try {
            userId = UserContext.getUserId();
        } catch (Exception ignored) {
            // 未登录时userId为null，返回热门
        }
        return Result.success(recommendService.getFeed(userId, pageNum, pageSize));
    }

    /**
     * 个性化推荐
     * 根据用户偏好标签推荐同类标签作品
     */
    @GetMapping("/personal")
    public Result<IPage<Work>> getPersonalRecommend(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = UserContext.getUserId();
        return Result.success(recommendService.getPersonalRecommend(userId, pageNum, pageSize));
    }

    /**
     * 热门作品排行榜
     * sortBy: like(按点赞) / view(按浏览量) / hot(综合热度，默认)
     */
    @GetMapping("/hot")
    public Result<IPage<Work>> getHotWorks(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "hot") String sortBy) {
        return Result.success(recommendService.getHotWorks(pageNum, pageSize, sortBy));
    }

    /**
     * 相似作品推荐
     * 根据相同标签推荐相似作品
     */
    @GetMapping("/similar/{workId}")
    public Result<List<Work>> getSimilarWorks(
            @PathVariable Long workId,
            @RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(recommendService.getSimilarWorks(workId, limit));
    }

    /**
     * 获取用户偏好标签（调试用）
     * 返回用户浏览历史中频次最高的标签
     */
    @GetMapping("/user-tags")
    public Result<Map<String, Object>> getUserPreferredTags() {
        Long userId = UserContext.getUserId();
        Map<Long, Integer> tagScores = browseHistoryService.getUserPreferredTagsWithScore(userId, 10);
        Map<Long, Integer> tagWorkCounts = recommendService.getUserPreferenceAnalysis(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("preferredTags", tagScores);
        result.put("tagWorkCounts", tagWorkCounts);
        result.put("browseCount", browseHistoryService.getBrowseCount(userId));
        return Result.success(result);
    }

    /**
     * 清除用户浏览历史
     */
    @DeleteMapping("/history")
    public Result<Void> clearHistory() {
        Long userId = UserContext.getUserId();
        browseHistoryService.clearHistory(userId);
        return Result.success();
    }
}
