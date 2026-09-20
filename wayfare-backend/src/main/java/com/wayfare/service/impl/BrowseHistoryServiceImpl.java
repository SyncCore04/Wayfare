package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wayfare.entity.WorkTag;
import com.wayfare.mapper.WorkTagMapper;
import com.wayfare.service.BrowseHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 浏览历史服务实现
 * 使用Redis ZSet存储浏览历史和标签偏好
 * Redis不可用时自动降级到内存存储，不影响推荐功能
 *
 * Redis数据结构：
 * - browse:history:{userId} : ZSet，score=浏览时间戳，value=workId，保留最近100条
 * - browse:tags:{userId}    : ZSet，score=标签浏览频次，value=tagId
 */
@Service
public class BrowseHistoryServiceImpl implements BrowseHistoryService {

    private static final Logger log = LoggerFactory.getLogger(BrowseHistoryServiceImpl.class);

    private static final String HISTORY_PREFIX = "browse:history:";
    private static final String TAGS_PREFIX = "browse:tags:";
    private static final int MAX_HISTORY_SIZE = 100;
    private static final long TTL_DAYS = 7;

    private final RedisTemplate<String, Object> redisTemplate;
    private final WorkTagMapper workTagMapper;

    // ========== 内存降级存储（Redis不可用时使用） ==========
    // userId -> (workId -> timestamp)，按插入顺序保留
    private final Map<Long, LinkedHashMap<Long, Long>> memoryHistory = new ConcurrentHashMap<>();
    // userId -> (tagId -> 频次)
    private final Map<Long, Map<Long, Integer>> memoryTags = new ConcurrentHashMap<>();
    // Redis可用性标志，首次失败后标记为不可用
    private volatile boolean redisAvailable = true;

    public BrowseHistoryServiceImpl(RedisTemplate<String, Object> redisTemplate,
                                     WorkTagMapper workTagMapper) {
        this.redisTemplate = redisTemplate;
        this.workTagMapper = workTagMapper;
    }

    @Override
    public void recordBrowse(Long userId, Long workId) {
        if (userId == null || workId == null) return;

        double now = System.currentTimeMillis();

        if (redisAvailable) {
            try {
                recordBrowseRedis(userId, workId, now);
                return;
            } catch (Exception e) {
                redisAvailable = false;
                log.warn("Redis不可用，浏览历史降级到内存存储: {}", e.getMessage());
            }
        }
        recordBrowseMemory(userId, workId, now);
    }

    private void recordBrowseRedis(Long userId, Long workId, double now) {
        String historyKey = HISTORY_PREFIX + userId;
        String tagsKey = TAGS_PREFIX + userId;

        // 1. 记录浏览历史到ZSet（score=时间戳）
        redisTemplate.opsForZSet().add(historyKey, String.valueOf(workId), now);

        // 2. 只保留最近100条
        Long size = redisTemplate.opsForZSet().size(historyKey);
        if (size != null && size > MAX_HISTORY_SIZE) {
            long removeCount = size - MAX_HISTORY_SIZE;
            redisTemplate.opsForZSet().removeRange(historyKey, 0, removeCount - 1);
        }

        // 3. 设置过期时间
        redisTemplate.expire(historyKey, TTL_DAYS, TimeUnit.DAYS);

        // 4. 查询该作品的标签，更新用户标签偏好
        LambdaQueryWrapper<WorkTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkTag::getWorkId, workId);
        List<WorkTag> workTags = workTagMapper.selectList(wrapper);

        for (WorkTag wt : workTags) {
            redisTemplate.opsForZSet().incrementScore(tagsKey, String.valueOf(wt.getTagId()), 1);
        }
        redisTemplate.expire(tagsKey, TTL_DAYS, TimeUnit.DAYS);

        log.debug("用户{}浏览作品{}，更新标签偏好{}个(Redis)", userId, workId, workTags.size());
    }

    private void recordBrowseMemory(Long userId, Long workId, double now) {
        // 1. 记录浏览历史
        LinkedHashMap<Long, Long> userHistory = memoryHistory.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        userHistory.remove(workId); // 移除旧记录以便更新到最新位置
        userHistory.put(workId, (long) now);
        // 只保留最近100条
        while (userHistory.size() > MAX_HISTORY_SIZE) {
            Iterator<Map.Entry<Long, Long>> it = userHistory.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }

        // 2. 更新标签偏好
        LambdaQueryWrapper<WorkTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkTag::getWorkId, workId);
        List<WorkTag> workTags = workTagMapper.selectList(wrapper);

        Map<Long, Integer> userTags = memoryTags.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        for (WorkTag wt : workTags) {
            userTags.merge(wt.getTagId(), 1, Integer::sum);
        }

        log.debug("用户{}浏览作品{}，更新标签偏好{}个(内存降级)", userId, workId, workTags.size());
    }

    @Override
    public List<Long> getBrowseHistory(Long userId, int limit) {
        if (userId == null) return Collections.emptyList();

        if (redisAvailable) {
            try {
                return getBrowseHistoryRedis(userId, limit);
            } catch (Exception e) {
                redisAvailable = false;
                log.warn("获取浏览历史失败，降级到内存: {}", e.getMessage());
            }
        }
        return getBrowseHistoryMemory(userId, limit);
    }

    private List<Long> getBrowseHistoryRedis(Long userId, int limit) {
        String historyKey = HISTORY_PREFIX + userId;
        Set<Object> history = redisTemplate.opsForZSet().reverseRange(historyKey, 0, limit - 1);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        return history.stream()
                .map(Object::toString)
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    private List<Long> getBrowseHistoryMemory(Long userId, int limit) {
        LinkedHashMap<Long, Long> userHistory = memoryHistory.get(userId);
        if (userHistory == null || userHistory.isEmpty()) {
            return Collections.emptyList();
        }
        // LinkedHashMap按插入顺序，最新的在最后，需要倒序取
        List<Long> all = new ArrayList<>(userHistory.keySet());
        Collections.reverse(all);
        return all.stream().limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<Long> getUserPreferredTags(Long userId, int topN) {
        if (userId == null) return Collections.emptyList();

        if (redisAvailable) {
            try {
                return getUserPreferredTagsRedis(userId, topN);
            } catch (Exception e) {
                redisAvailable = false;
                log.warn("获取用户偏好标签失败，降级到内存: {}", e.getMessage());
            }
        }
        return getUserPreferredTagsMemory(userId, topN);
    }

    private List<Long> getUserPreferredTagsRedis(Long userId, int topN) {
        String tagsKey = TAGS_PREFIX + userId;
        Set<Object> tags = redisTemplate.opsForZSet().reverseRange(tagsKey, 0, topN - 1);
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        return tags.stream()
                .map(Object::toString)
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    private List<Long> getUserPreferredTagsMemory(Long userId, int topN) {
        Map<Long, Integer> userTags = memoryTags.get(userId);
        if (userTags == null || userTags.isEmpty()) {
            return Collections.emptyList();
        }
        return userTags.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public Map<Long, Integer> getUserPreferredTagsWithScore(Long userId, int topN) {
        if (userId == null) return Collections.emptyMap();

        if (redisAvailable) {
            try {
                return getUserPreferredTagsWithScoreRedis(userId, topN);
            } catch (Exception e) {
                redisAvailable = false;
                log.warn("获取用户偏好标签(含分值)失败，降级到内存: {}", e.getMessage());
            }
        }
        return getUserPreferredTagsWithScoreMemory(userId, topN);
    }

    private Map<Long, Integer> getUserPreferredTagsWithScoreRedis(Long userId, int topN) {
        String tagsKey = TAGS_PREFIX + userId;
        Set<ZSetOperations.TypedTuple<Object>> tags = redisTemplate.opsForZSet()
                .reverseRangeWithScores(tagsKey, 0, topN - 1);
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (ZSetOperations.TypedTuple<Object> tuple : tags) {
            Long tagId = Long.parseLong(tuple.getValue().toString());
            int score = tuple.getScore() != null ? tuple.getScore().intValue() : 0;
            result.put(tagId, score);
        }
        return result;
    }

    private Map<Long, Integer> getUserPreferredTagsWithScoreMemory(Long userId, int topN) {
        Map<Long, Integer> userTags = memoryTags.get(userId);
        if (userTags == null || userTags.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        userTags.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(topN)
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    @Override
    public boolean hasBrowsed(Long userId, Long workId) {
        if (userId == null || workId == null) return false;

        if (redisAvailable) {
            try {
                Double score = redisTemplate.opsForZSet().score(HISTORY_PREFIX + userId, String.valueOf(workId));
                return score != null;
            } catch (Exception e) {
                redisAvailable = false;
            }
        }
        LinkedHashMap<Long, Long> userHistory = memoryHistory.get(userId);
        return userHistory != null && userHistory.containsKey(workId);
    }

    @Override
    public void clearHistory(Long userId) {
        if (userId == null) return;
        if (redisAvailable) {
            try {
                redisTemplate.delete(HISTORY_PREFIX + userId);
                redisTemplate.delete(TAGS_PREFIX + userId);
            } catch (Exception e) {
                redisAvailable = false;
            }
        }
        memoryHistory.remove(userId);
        memoryTags.remove(userId);
        log.info("用户{}浏览历史已清除", userId);
    }

    @Override
    public long getBrowseCount(Long userId) {
        if (userId == null) return 0;

        if (redisAvailable) {
            try {
                Long count = redisTemplate.opsForZSet().size(HISTORY_PREFIX + userId);
                return count != null ? count : 0;
            } catch (Exception e) {
                redisAvailable = false;
            }
        }
        LinkedHashMap<Long, Long> userHistory = memoryHistory.get(userId);
        return userHistory != null ? userHistory.size() : 0;
    }
}
