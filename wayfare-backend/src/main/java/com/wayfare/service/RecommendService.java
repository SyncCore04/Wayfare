package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Work;

import java.util.List;
import java.util.Map;

/**
 * 推荐服务接口
 * 基于用户浏览历史和作品标签的简易协同过滤推荐
 */
public interface RecommendService {

    /**
     * 个性化推荐
     * 根据用户偏好标签推荐带有同类标签的摄影作品
     *
     * @param userId   用户ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 推荐作品分页（按标签匹配度排序）
     */
    IPage<Work> getPersonalRecommend(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 热门作品排行榜
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @param sortBy   排序方式: like(按点赞) / view(按浏览量) / hot(综合热度)
     * @return 热门作品分页
     */
    IPage<Work> getHotWorks(Integer pageNum, Integer pageSize, String sortBy);

    /**
     * 首页瀑布流推荐
     * 混合个性化推荐(70%)和热门作品(30%)，去重后返回
     *
     * @param userId   用户ID（未登录时返回热门作品）
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 瀑布流作品分页
     */
    IPage<Work> getFeed(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取用户偏好标签及匹配作品数（调试用）
     *
     * @param userId 用户ID
     * @return Map: tagId -> 匹配作品数量
     */
    Map<Long, Integer> getUserPreferenceAnalysis(Long userId);

    /**
     * 获取相似作品（基于相同标签）
     *
     * @param workId   作品ID
     * @param limit    返回数量
     * @return 相似作品列表
     */
    List<Work> getSimilarWorks(Long workId, int limit);
}
