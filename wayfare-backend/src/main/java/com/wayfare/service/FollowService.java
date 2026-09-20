package com.wayfare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wayfare.entity.Follow;
import com.wayfare.entity.User;

/**
 * 关注服务接口
 * 使用Redis做计数缓存（粉丝数、关注数）
 */
public interface FollowService {

    /**
     * 关注/取消关注（切换状态）
     *
     * @param followerId 关注者ID
     * @param followingId 被关注者ID
     * @return true=已关注, false=已取消
     */
    boolean toggleFollow(Long followerId, Long followingId);

    /**
     * 检查是否已关注
     */
    boolean isFollowing(Long followerId, Long followingId);

    /**
     * 获取用户粉丝数（优先Redis缓存）
     */
    Long getFollowerCount(Long userId);

    /**
     * 获取用户关注数（优先Redis缓存）
     */
    Long getFollowingCount(Long userId);

    /**
     * 获取粉丝列表（Follow记录）
     */
    IPage<Follow> getFollowers(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取关注列表（Follow记录）
     */
    IPage<Follow> getFollowings(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取关注的用户列表（含用户详细信息）
     */
    IPage<User> getFollowingUsers(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 获取粉丝用户列表（含用户详细信息，及当前用户是否已关注该粉丝）
     */
    IPage<User> getFollowerUsers(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 删除用户的所有关注关系（用户被删除时调用）
     */
    void deleteByUser(Long userId);
}
