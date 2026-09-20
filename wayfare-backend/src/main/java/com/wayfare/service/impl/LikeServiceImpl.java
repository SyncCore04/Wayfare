package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.entity.LikeRecord;
import com.wayfare.mapper.LikeRecordMapper;
import com.wayfare.service.LikeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 点赞服务实现
 * 使用Redis做计数缓存，Redis不可用时降级到数据库
 */
@Service
public class LikeServiceImpl implements LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeServiceImpl.class);

    private static final String CACHE_PREFIX = "like:count:";
    private static final long CACHE_TTL_HOURS = 24;

    private final LikeRecordMapper likeRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public LikeServiceImpl(LikeRecordMapper likeRecordMapper, RedisTemplate<String, Object> redisTemplate) {
        this.likeRecordMapper = likeRecordMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleLike(Long userId, Integer targetType, Long targetId) {
        if (targetType == null || targetType < 1 || targetType > 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "点赞对象类型不合法");
        }

        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        LikeRecord exist = likeRecordMapper.selectOne(wrapper);

        if (exist != null) {
            // 取消点赞
            likeRecordMapper.deleteById(exist.getId());
            decrementCache(targetType, targetId);
            log.info("用户{}取消点赞 type={} target={}", userId, targetType, targetId);
            return false;
        } else {
            // 点赞
            LikeRecord record = new LikeRecord();
            record.setUserId(userId);
            record.setTargetType(targetType);
            record.setTargetId(targetId);
            likeRecordMapper.insert(record);
            incrementCache(targetType, targetId);
            log.info("用户{}点赞 type={} target={}", userId, targetType, targetId);
            return true;
        }
    }

    @Override
    public boolean isLiked(Long userId, Integer targetType, Long targetId) {
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        return likeRecordMapper.selectCount(wrapper) > 0;
    }

    @Override
    public Long getLikeCount(Integer targetType, Long targetId) {
        String key = CACHE_PREFIX + targetType + ":" + targetId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached.toString());
            }
        } catch (Exception e) {
            log.warn("Redis读取点赞数失败，降级到数据库: {}", e.getMessage());
        }

        // 缓存未命中，查数据库
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        Long count = likeRecordMapper.selectCount(wrapper);

        // 回写缓存
        try {
            redisTemplate.opsForValue().set(key, count, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis回写点赞数失败: {}", e.getMessage());
        }
        return count;
    }

    @Override
    public IPage<LikeRecord> getUserLikes(Long userId, Integer pageNum, Integer pageSize) {
        Page<LikeRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getUserId, userId)
                .orderByDesc(LikeRecord::getCreatedAt);
        return likeRecordMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTarget(Integer targetType, Long targetId) {
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        likeRecordMapper.delete(wrapper);
        String key = CACHE_PREFIX + targetType + ":" + targetId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis删除点赞缓存失败: {}", e.getMessage());
        }
    }

    private void incrementCache(Integer targetType, Long targetId) {
        String key = CACHE_PREFIX + targetType + ":" + targetId;
        try {
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis递增点赞数失败: {}", e.getMessage());
        }
    }

    private void decrementCache(Integer targetType, Long targetId) {
        String key = CACHE_PREFIX + targetType + ":" + targetId;
        try {
            redisTemplate.opsForValue().decrement(key);
        } catch (Exception e) {
            log.warn("Redis递减点赞数失败: {}", e.getMessage());
        }
    }
}
