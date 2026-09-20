package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.LikeRecord;

/**
 * 点赞服务接口
 * target_type: 1作品 2评论
 * 使用Redis做计数缓存
 */
public interface LikeService {

    /**
     * 点赞/取消点赞（切换状态）
     *
     * @param userId 用户ID
     * @param targetType 点赞对象类型 1作品 2评论
     * @param targetId 点赞对象ID
     * @return true=已点赞, false=已取消
     */
    boolean toggleLike(Long userId, Integer targetType, Long targetId);

    /**
     * 检查用户是否已点赞
     */
    boolean isLiked(Long userId, Integer targetType, Long targetId);

    /**
     * 获取点赞数（优先Redis缓存，缓存未命中则查数据库并回写）
     */
    Long getLikeCount(Integer targetType, Long targetId);

    /**
     * 获取用户点赞的作品列表
     */
    IPage<LikeRecord> getUserLikes(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 删除某对象的所有点赞记录（对象被删除时调用）
     */
    void deleteByTarget(Integer targetType, Long targetId);
}
