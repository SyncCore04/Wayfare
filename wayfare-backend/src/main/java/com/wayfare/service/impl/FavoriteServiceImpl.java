package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.entity.Favorite;
import com.wayfare.entity.User;
import com.wayfare.entity.Work;
import com.wayfare.mapper.FavoriteMapper;
import com.wayfare.mapper.UserMapper;
import com.wayfare.mapper.WorkMapper;
import com.wayfare.service.FavoriteService;
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
 * 收藏服务实现
 * 使用Redis做计数缓存
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteServiceImpl.class);

    private static final String CACHE_PREFIX = "favorite:count:";
    private static final long CACHE_TTL_HOURS = 24;

    private final FavoriteMapper favoriteMapper;
    private final WorkMapper workMapper;
    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public FavoriteServiceImpl(FavoriteMapper favoriteMapper, WorkMapper workMapper, UserMapper userMapper, RedisTemplate<String, Object> redisTemplate) {
        this.favoriteMapper = favoriteMapper;
        this.workMapper = workMapper;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(Long userId, Long workId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId).eq(Favorite::getWorkId, workId);
        Favorite exist = favoriteMapper.selectOne(wrapper);

        if (exist != null) {
            favoriteMapper.deleteById(exist.getId());
            decrementCache(workId);
            log.info("用户{}取消收藏作品{}", userId, workId);
            return false;
        } else {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setWorkId(workId);
            favoriteMapper.insert(favorite);
            incrementCache(workId);
            log.info("用户{}收藏作品{}", userId, workId);
            return true;
        }
    }

    @Override
    public boolean isFavorited(Long userId, Long workId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId).eq(Favorite::getWorkId, workId);
        return favoriteMapper.selectCount(wrapper) > 0;
    }

    @Override
    public Long getFavoriteCount(Long workId) {
        String key = CACHE_PREFIX + workId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached.toString());
            }
        } catch (Exception e) {
            log.warn("Redis读取收藏数失败，降级到数据库: {}", e.getMessage());
        }

        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getWorkId, workId);
        Long count = favoriteMapper.selectCount(wrapper);

        try {
            redisTemplate.opsForValue().set(key, count, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis回写收藏数失败: {}", e.getMessage());
        }
        return count;
    }

    @Override
    public IPage<Favorite> getUserFavorites(Long userId, Integer pageNum, Integer pageSize) {
        Page<Favorite> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId).orderByDesc(Favorite::getCreatedAt);
        return favoriteMapper.selectPage(page, wrapper);
    }

    @Override
    public IPage<Work> getUserFavoriteWorks(Long userId, Integer pageNum, Integer pageSize) {
        // 1. 分页查询收藏关系
        Page<Favorite> favPage = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId).orderByDesc(Favorite::getCreatedAt);
        IPage<Favorite> favResult = favoriteMapper.selectPage(favPage, wrapper);

        // 2. 提取作品ID，批量查询作品信息
        List<Long> workIds = favResult.getRecords().stream()
                .map(Favorite::getWorkId)
                .collect(Collectors.toList());

        Page<Work> workPage = new Page<>(pageNum, pageSize);
        workPage.setTotal(favResult.getTotal());
        workPage.setPages(favResult.getPages());
        workPage.setCurrent(favResult.getCurrent());
        workPage.setSize(favResult.getSize());

        if (workIds.isEmpty()) {
            return workPage;
        }

        List<Work> works = workMapper.selectBatchIds(workIds);
        // 按照收藏关系的顺序排序
        Map<Long, Work> workMap = works.stream().collect(Collectors.toMap(Work::getId, w -> w));
        List<Work> sortedWorks = workIds.stream()
                .map(workMap::get)
                .filter(w -> w != null)
                .collect(Collectors.toList());

        // 3. 填充作者信息
        if (!sortedWorks.isEmpty()) {
            List<Long> authorIds = sortedWorks.stream()
                    .map(Work::getUserId)
                    .filter(id -> id != null)
                    .distinct()
                    .collect(Collectors.toList());
            if (!authorIds.isEmpty()) {
                List<User> authors = userMapper.selectBatchIds(authorIds);
                authors.forEach(a -> a.setPassword(null));
                Map<Long, User> authorMap = authors.stream().collect(Collectors.toMap(User::getId, u -> u));
                sortedWorks.forEach(w -> w.setAuthor(authorMap.get(w.getUserId())));
            }
            // 填充图片URL列表（目前只有封面图）
            sortedWorks.forEach(w -> {
                if (w.getCoverUrl() != null) {
                    w.setImageUrls(java.util.Collections.singletonList(w.getCoverUrl()));
                }
            });
        }

        workPage.setRecords(sortedWorks);
        return workPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByWork(Long workId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getWorkId, workId);
        favoriteMapper.delete(wrapper);
        try {
            redisTemplate.delete(CACHE_PREFIX + workId);
        } catch (Exception e) {
            log.warn("Redis删除收藏缓存失败: {}", e.getMessage());
        }
    }

    private void incrementCache(Long workId) {
        String key = CACHE_PREFIX + workId;
        try {
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis递增收藏数失败: {}", e.getMessage());
        }
    }

    private void decrementCache(Long workId) {
        String key = CACHE_PREFIX + workId;
        try {
            redisTemplate.opsForValue().decrement(key);
        } catch (Exception e) {
            log.warn("Redis递减收藏数失败: {}", e.getMessage());
        }
    }
}
