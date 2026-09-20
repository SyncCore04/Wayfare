package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.entity.Follow;
import com.wayfare.entity.User;
import com.wayfare.mapper.FollowMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.service.FollowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 关注服务实现
 * 使用Redis做粉丝数/关注数缓存
 */
@Service
public class FollowServiceImpl implements FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowServiceImpl.class);

    private static final String FOLLOWER_CACHE_PREFIX = "follow:follower:";
    private static final String FOLLOWING_CACHE_PREFIX = "follow:following:";
    private static final long CACHE_TTL_HOURS = 24;

    private final FollowMapper followMapper;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public FollowServiceImpl(FollowMapper followMapper, UserMapper userMapper, RedisTemplate<String, Object> redisTemplate) {
        this.followMapper = followMapper;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFollow(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new BusinessException(ResultCode.FOLLOW_SELF);
        }

        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, followerId).eq(Follow::getFollowingId, followingId);
        Follow exist = followMapper.selectOne(wrapper);

        if (exist != null) {
            followMapper.deleteById(exist.getId());
            decrementFollowerCache(followingId);
            decrementFollowingCache(followerId);
            log.info("用户{}取消关注用户{}", followerId, followingId);
            return false;
        } else {
            Follow follow = new Follow();
            follow.setFollowerId(followerId);
            follow.setFollowingId(followingId);
            followMapper.insert(follow);
            incrementFollowerCache(followingId);
            incrementFollowingCache(followerId);
            log.info("用户{}关注用户{}", followerId, followingId);
            return true;
        }
    }

    @Override
    public boolean isFollowing(Long followerId, Long followingId) {
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, followerId).eq(Follow::getFollowingId, followingId);
        return followMapper.selectCount(wrapper) > 0;
    }

    @Override
    public Long getFollowerCount(Long userId) {
        String key = FOLLOWER_CACHE_PREFIX + userId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return Long.parseLong(cached.toString());
        } catch (Exception e) {
            log.warn("Redis读取粉丝数失败: {}", e.getMessage());
        }

        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowingId, userId);
        Long count = followMapper.selectCount(wrapper);

        try {
            redisTemplate.opsForValue().set(key, count, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis回写粉丝数失败: {}", e.getMessage());
        }
        return count;
    }

    @Override
    public Long getFollowingCount(Long userId) {
        String key = FOLLOWING_CACHE_PREFIX + userId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return Long.parseLong(cached.toString());
        } catch (Exception e) {
            log.warn("Redis读取关注数失败: {}", e.getMessage());
        }

        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, userId);
        Long count = followMapper.selectCount(wrapper);

        try {
            redisTemplate.opsForValue().set(key, count, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis回写关注数失败: {}", e.getMessage());
        }
        return count;
    }

    @Override
    public IPage<Follow> getFollowers(Long userId, Integer pageNum, Integer pageSize) {
        Page<Follow> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowingId, userId).orderByDesc(Follow::getCreatedAt);
        return followMapper.selectPage(page, wrapper);
    }

    @Override
    public IPage<Follow> getFollowings(Long userId, Integer pageNum, Integer pageSize) {
        Page<Follow> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, userId).orderByDesc(Follow::getCreatedAt);
        return followMapper.selectPage(page, wrapper);
    }

    @Override
    public IPage<User> getFollowingUsers(Long userId, Integer pageNum, Integer pageSize) {
        // 1. 分页查询关注关系
        Page<Follow> followPage = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, userId).orderByDesc(Follow::getCreatedAt);
        IPage<Follow> followResult = followMapper.selectPage(followPage, wrapper);

        // 2. 提取被关注用户ID，批量查询用户信息
        List<Long> followingIds = followResult.getRecords().stream()
                .map(Follow::getFollowingId)
                .collect(Collectors.toList());

        Page<User> userPage = new Page<>(pageNum, pageSize);
        userPage.setTotal(followResult.getTotal());
        userPage.setPages(followResult.getPages());
        userPage.setCurrent(followResult.getCurrent());
        userPage.setSize(followResult.getSize());

        if (followingIds.isEmpty()) {
            return userPage;
        }

        List<User> users = userMapper.selectBatchIds(followingIds);
        // 隐藏密码
        users.forEach(u -> u.setPassword(null));
        // 按照关注关系的顺序排序
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));
        List<User> sortedUsers = followingIds.stream()
                .map(userMap::get)
                .filter(u -> u != null)
                .collect(Collectors.toList());
        userPage.setRecords(sortedUsers);
        return userPage;
    }

    @Override
    public IPage<User> getFollowerUsers(Long userId, Integer pageNum, Integer pageSize) {
        // 1. 分页查询粉丝关系
        Page<Follow> followPage = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowingId, userId).orderByDesc(Follow::getCreatedAt);
        IPage<Follow> followResult = followMapper.selectPage(followPage, wrapper);

        // 2. 提取粉丝用户ID，批量查询用户信息
        List<Long> followerIds = followResult.getRecords().stream()
                .map(Follow::getFollowerId)
                .collect(Collectors.toList());

        Page<User> userPage = new Page<>(pageNum, pageSize);
        userPage.setTotal(followResult.getTotal());
        userPage.setPages(followResult.getPages());
        userPage.setCurrent(followResult.getCurrent());
        userPage.setSize(followResult.getSize());

        if (followerIds.isEmpty()) {
            return userPage;
        }

        List<User> users = userMapper.selectBatchIds(followerIds);
        users.forEach(u -> u.setPassword(null));

        // 按照粉丝关系的顺序排序，并标记当前用户是否已关注该粉丝
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));
        List<User> sortedUsers = followerIds.stream()
                .map(userMap::get)
                .filter(u -> u != null)
                .collect(Collectors.toList());

        // 批量查询当前用户是否已关注这些粉丝
        if (!sortedUsers.isEmpty() && userId != null) {
            LambdaQueryWrapper<Follow> checkWrapper = new LambdaQueryWrapper<>();
            checkWrapper.eq(Follow::getFollowerId, userId)
                    .in(Follow::getFollowingId, followerIds);
            List<Follow> existingFollows = followMapper.selectList(checkWrapper);
            List<Long> followedIds = existingFollows.stream()
                    .map(Follow::getFollowingId)
                    .collect(Collectors.toList());
            sortedUsers.forEach(u -> u.setIsFollowing(followedIds.contains(u.getId())));
        }

        userPage.setRecords(sortedUsers);
        return userPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByUser(Long userId) {
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Follow::getFollowerId, userId).or().eq(Follow::getFollowingId, userId);
        followMapper.delete(wrapper);
        try {
            redisTemplate.delete(FOLLOWER_CACHE_PREFIX + userId);
            redisTemplate.delete(FOLLOWING_CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Redis删除关注缓存失败: {}", e.getMessage());
        }
    }

    private void incrementFollowerCache(Long userId) {
        String key = FOLLOWER_CACHE_PREFIX + userId;
        try {
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis递增粉丝数失败: {}", e.getMessage());
        }
    }

    private void decrementFollowerCache(Long userId) {
        try {
            redisTemplate.opsForValue().decrement(FOLLOWER_CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Redis递减粉丝数失败: {}", e.getMessage());
        }
    }

    private void incrementFollowingCache(Long userId) {
        String key = FOLLOWING_CACHE_PREFIX + userId;
        try {
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis递增关注数失败: {}", e.getMessage());
        }
    }

    private void decrementFollowingCache(Long userId) {
        try {
            redisTemplate.opsForValue().decrement(FOLLOWING_CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Redis递减关注数失败: {}", e.getMessage());
        }
    }
}
