package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Favorite;
import com.wayfare.entity.Work;

/**
 * 收藏服务接口
 * 使用Redis做计数缓存
 */
public interface FavoriteService {

    /**
     * 收藏/取消收藏（切换状态）
     *
     * @param userId 用户ID
     * @param workId 作品ID
     * @return true=已收藏, false=已取消
     */
    boolean toggleFavorite(Long userId, Long workId);

    /**
     * 检查用户是否已收藏
     */
    boolean isFavorited(Long userId, Long workId);

    /**
     * 获取作品收藏数（优先Redis缓存）
     */
    Long getFavoriteCount(Long workId);

    /**
     * 获取用户收藏列表（Favorite记录）
     */
    IPage<Favorite> getUserFavorites(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取用户收藏的作品列表（含作品详细信息）
     */
    IPage<Work> getUserFavoriteWorks(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 删除作品的所有收藏记录（作品被删除时调用）
     */
    void deleteByWork(Long workId);
}
