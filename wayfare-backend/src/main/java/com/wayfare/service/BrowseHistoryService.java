package com.wayfare.service;

import java.util.List;
import java.util.Map;

/**
 * 浏览历史服务接口
 * 使用Redis存储用户浏览历史和标签偏好
 */
public interface BrowseHistoryService {

    /**
     * 记录用户浏览历史
     * 同时更新用户标签偏好（该作品的标签频次+1）
     *
     * @param userId 用户ID
     * @param workId 作品ID
     */
    void recordBrowse(Long userId, Long workId);

    /**
     * 获取用户浏览历史（最近N条）
     *
     * @param userId 用户ID
     * @param limit  返回数量
     * @return 作品ID列表（按时间倒序）
     */
    List<Long> getBrowseHistory(Long userId, int limit);

    /**
     * 获取用户偏好标签（按频次排序的Top N）
     *
     * @param userId 用户ID
     * @param topN   返回前N个标签
     * @return 标签ID列表（按频次倒序）
     */
    List<Long> getUserPreferredTags(Long userId, int topN);

    /**
     * 获取用户偏好标签及频次
     *
     * @param userId 用户ID
     * @param topN   返回前N个
     * @return Map: tagId -> 频次
     */
    Map<Long, Integer> getUserPreferredTagsWithScore(Long userId, int topN);

    /**
     * 检查用户是否已浏览某作品
     */
    boolean hasBrowsed(Long userId, Long workId);

    /**
     * 清除用户浏览历史
     */
    void clearHistory(Long userId);

    /**
     * 获取用户浏览总数
     */
    long getBrowseCount(Long userId);
}
