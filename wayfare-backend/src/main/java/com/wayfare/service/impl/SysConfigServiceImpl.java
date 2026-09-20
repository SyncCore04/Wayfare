package com.wayfare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wayfare.entity.SysConfig;
import com.wayfare.mapper.SysConfigMapper;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 运行时配置服务实现（L2 开关层）。
 *
 * <p><b>缓存设计</b>：整表 12 条左右的配置，全部塞进 Redis 的一个 Hash
 * （key = {@code sys:config}，field = config_key，value = config_value），TTL 30 秒。
 * 用 Hash 而不是「一个 key 一条配置」的好处：
 * <ul>
 *   <li>缓存未命中时<b>一次查询就能把全部配置捞回来</b>，而不是查一次缓存、再查一次库；</li>
 *   <li>读任意配置都只发一次 Redis 命令（HGETALL），配置项变多也不增加往返次数。</li>
 * </ul>
 *
 * <p><b>为什么 TTL 是 30 秒</b>：这样「直接改数据库」也能在 30 秒内自然生效，
 * 不需要重启或手动清缓存；而通过 {@link #set} 改的会立即删缓存，是即时的。
 * 两者配合保证了「后台改完不重启即生效」。
 *
 * <p><b>Redis 不可用时怎么处理</b>：回落到直连数据库（只查需要的那一条），并打 WARN。
 * 这和 {@code TokenBlacklist} 的选择一致 —— 项目铁律是「任一时刻系统都完整可用」，
 * 配置读不到就整个系统不可用，代价太大。
 *
 * <p><b>一处有意的不缓存</b>：{@link #listByGroup} 查的是带 description / valueType 的完整行，
 * 与本缓存只存 key-value 的结构不匹配，且它是后台配置页的低频调用，所以直接查库。
 */
@Service
public class SysConfigServiceImpl implements SysConfigService {

    private static final Logger log = LoggerFactory.getLogger(SysConfigServiceImpl.class);

    /** 缓存 Hash 的 key */
    private static final String CACHE_KEY = "sys:config";

    /** 缓存有效期（秒）。改库不改代码时，最多 30 秒生效 */
    private static final long CACHE_TTL_SECONDS = 30;

    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public SysConfigServiceImpl(SysConfigMapper sysConfigMapper, StringRedisTemplate stringRedisTemplate) {
        this.sysConfigMapper = sysConfigMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ==================== 读 ====================

    @Override
    public String get(String key) {
        if (!StringUtils.hasText(key)) return null;

        Map<String, String> cached = readCache();
        if (cached != null) {
            return cached.get(key);
        }
        // 走到这里说明 Redis 不可用，退化为单条查库（比全量查更省）
        SysConfig config = selectByKey(key);
        return config == null ? null : config.getConfigValue();
    }

    @Override
    public String get(String key, String fallback) {
        String value = get(key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    @Override
    public Integer getInt(String key) {
        String raw = get(key);
        if (!StringUtils.hasText(raw)) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // 配置写错不该让整个请求失败：记下来，返回 null 让调用方用兜底值
            log.warn("sys_config 的 {} 值 '{}' 不是合法整数，本次按未配置处理", key, raw);
            return null;
        }
    }

    @Override
    public int getInt(String key, int fallback) {
        Integer value = getInt(key);
        return value != null ? value : fallback;
    }

    @Override
    public Boolean getBool(String key) {
        String raw = get(key);
        if (!StringUtils.hasText(raw)) return null;
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        log.warn("sys_config 的 {} 值 '{}' 不是合法布尔值，本次按未配置处理", key, raw);
        return null;
    }

    @Override
    public boolean getBool(String key, boolean fallback) {
        Boolean value = getBool(key);
        return value != null ? value : fallback;
    }

    @Override
    public List<SysConfig> listByGroup(String group) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(group)) {
            wrapper.eq(SysConfig::getGroupName, group.trim());
        }
        wrapper.orderByAsc(SysConfig::getGroupName).orderByAsc(SysConfig::getConfigKey);
        return sysConfigMapper.selectList(wrapper);
    }

    // ==================== 写 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void set(String key, String value, Long operatorId) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        String trimmedKey = key.trim();
        SysConfig existing = selectByKey(trimmedKey);

        if (existing == null) {
            // 新增：valueType 默认 STRING，分组取键的第一段（llm.xxx → llm）。
            // 需要精确的 valueType 时应在 sys_config 里预先建好这一行。
            SysConfig config = new SysConfig();
            config.setConfigKey(trimmedKey);
            config.setConfigValue(value != null ? value : "");
            config.setValueType("STRING");
            config.setGroupName(trimmedKey.contains(".") ? trimmedKey.substring(0, trimmedKey.indexOf('.')) : "");
            config.setDescription("");
            config.setUpdatedBy(operatorId != null ? operatorId : 0L);
            sysConfigMapper.insert(config);
        } else {
            SysConfig update = new SysConfig();
            update.setId(existing.getId());
            update.setConfigValue(value != null ? value : "");
            update.setUpdatedBy(operatorId != null ? operatorId : 0L);
            sysConfigMapper.updateById(update);
        }

        // 立即删缓存 → 「后台改完不重启即生效」靠的就是这一句
        clearCache();
        log.info("运行时配置已更新: {}={}, 操作人={}", trimmedKey, value, operatorId);
    }

    @Override
    public void clearCache() {
        try {
            stringRedisTemplate.delete(CACHE_KEY);
        } catch (Exception e) {
            log.warn("清除 sys_config 缓存失败（Redis 不可用？）: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * 读缓存；未命中则回源全量加载并写入缓存。
     *
     * @return 配置 map；<b>Redis 不可用时返回 null</b>（调用方据此退化为直连数据库）
     */
    private Map<String, String> readCache() {
        try {
            Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(CACHE_KEY);
            if (cached != null && !cached.isEmpty()) {
                Map<String, String> result = new LinkedHashMap<>(cached.size());
                cached.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                return result;
            }

            // 未命中：回源
            Map<String, String> all = loadAllFromDb();
            if (!all.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(CACHE_KEY, all);
                stringRedisTemplate.expire(CACHE_KEY, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            }
            // 库里确实一条都没有时也返回空 map，避免每次都回源
            return all;
        } catch (Exception e) {
            log.warn("sys_config 缓存不可用，回落到直连数据库: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> loadAllFromDb() {
        Map<String, String> map = new LinkedHashMap<>();
        List<SysConfig> list = sysConfigMapper.selectList(null);
        for (SysConfig config : list) {
            map.put(config.getConfigKey(), config.getConfigValue());
        }
        return map;
    }

    private SysConfig selectByKey(String key) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysConfig::getConfigKey, key);
        return sysConfigMapper.selectOne(wrapper);
    }
}
