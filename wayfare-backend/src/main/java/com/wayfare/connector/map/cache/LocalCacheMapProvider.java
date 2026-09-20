package com.wayfare.connector.map.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.map.MapProvider;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.RouteDTO;
import com.wayfare.connector.map.RouteQueryDTO;
import com.wayfare.entity.PoiCache;
import com.wayfare.mapper.PoiCacheMapper;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地缓存地图 Provider（三级降级里的第二级：mode=CACHED）。
 *
 * <p><b>零网络请求</b>：只读 {@code poi_cache} 表与 Redis 路线缓存。
 * 地图熔断或断网时，靠它把「历史上查过的点位」继续用起来，
 * 让行程还能排出来，而不是直接瘫掉。
 *
 * <p><b>未命中返回空集合、不抛异常</b>：这是刻意的 ——
 * 「缓存里没有这条」是很正常的业务状态，不是错误。
 * 上层拿到空数据后会把可信度标成 ESTIMATED 继续往下走。
 */
@Component
public class LocalCacheMapProvider implements MapProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheMapProvider.class);

    public static final String PROVIDER_NAME = "cache";

    private static final String ROUTE_KEY_PREFIX = "map:route:";

    /** 路线缓存 TTL（小时），来自手册的缓存表 */
    private static final long ROUTE_TTL_HOURS = 12L;

    private final PoiCacheMapper poiCacheMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SysConfigService sysConfigService;

    public LocalCacheMapProvider(PoiCacheMapper poiCacheMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 SysConfigService sysConfigService) {
        this.poiCacheMapper = poiCacheMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.sysConfigService = sysConfigService;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    /** 缓存不依赖任何外部服务，永远「可用」——它本身就是降级目的地 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "本地缓存只提供历史数据，不提供实时能力";
    }

    @Override
    public List<PoiDTO> searchPoi(PoiQueryDTO query) {
        LambdaQueryWrapper<PoiCache> wrapper = new LambdaQueryWrapper<>();
        if (query != null && StringUtils.hasText(query.getCity())) {
            wrapper.eq(PoiCache::getCity, query.getCity().trim());
        }
        if (query != null && StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            // 名称或标签命中即可 —— 缓存量小，不必追求精确匹配
            wrapper.and(w -> w.like(PoiCache::getName, kw).or().like(PoiCache::getTag, kw));
        }
        int limit = (query != null && query.getPageSize() != null) ? query.getPageSize() : 10;
        wrapper.last("LIMIT " + Math.min(limit, 50));

        List<PoiCache> rows = poiCacheMapper.selectList(wrapper);
        List<PoiDTO> result = new ArrayList<>();
        for (PoiCache row : rows) {
            result.add(toPoi(row));
        }
        log.debug("本地缓存检索命中 {} 条: city={}, keyword={}",
                result.size(), query == null ? null : query.getCity(),
                query == null ? null : query.getKeyword());
        return result;
    }

    @Override
    public PoiDTO detail(String poiUid) {
        if (!StringUtils.hasText(poiUid)) return null;
        LambdaQueryWrapper<PoiCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PoiCache::getPoiUid, poiUid);
        PoiCache row = poiCacheMapper.selectOne(wrapper);
        return row == null ? null : toPoi(row);
    }

    @Override
    public RouteDTO route(RouteQueryDTO query) {
        if (query == null) return null;
        try {
            String json = stringRedisTemplate.opsForValue().get(routeKey(query));
            if (!StringUtils.hasText(json)) return null;
            RouteDTO cached = objectMapper.readValue(json, RouteDTO.class);
            return cached == null ? null : cached.asCached();
        } catch (Exception e) {
            log.warn("读取路线缓存失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把一条实时算出的路线写进 Redis 缓存。
     * 由上层在 VERIFIED 路径成功后调用（这样缓存里存的都是真实数据，不带估算）。
     *
     * <p>TTL 取 12 小时（手册缓存表规定的值）。路线缓存不放到 {@code CacheConfig} 的
     * Spring Cache 里，而是由本类直接读写 —— 因为<b>熔断降级时也要能读到同一份数据</b>，
     * 而 Spring Cache 只在调用 BaiduMapProvider 时才生效，降级路径根本不会经过它。
     */
    public void cacheRoute(RouteQueryDTO query, RouteDTO route) {
        if (query == null || route == null) return;
        try {
            stringRedisTemplate.opsForValue().set(routeKey(query),
                    objectMapper.writeValueAsString(route), ROUTE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写路线缓存失败（不影响本次结果）: {}", e.getMessage());
        }
    }

    /**
     * 缓存键：{@code map:route:{mode}:{fromLng},{fromLat}:{toLng},{toLat}}（手册缓存表的规定格式）。
     *
     * <p>键里必须包含出行方式与起终点：**同一对点不同出行方式的路线完全不同**，
     * 混在一起会给出错误的距离。
     * 坐标保留 5 位小数（约 1 米精度），避免浮点尾差导致永远命中不了缓存。
     */
    private String routeKey(RouteQueryDTO q) {
        return ROUTE_KEY_PREFIX + q.getMode() + ":"
                + coord(q.getFromLng()) + "," + coord(q.getFromLat()) + ":"
                + coord(q.getToLng()) + "," + coord(q.getToLat());
    }

    private String coord(Double v) {
        return v == null ? "null" : String.format("%.5f", v);
    }

    private PoiDTO toPoi(PoiCache row) {
        return new PoiDTO(row.getPoiUid(), row.getName(), row.getAddress(),
                row.getLng(), row.getLat(), row.getTag(), row.getShopHours(),
                row.getRating(), row.getTicketPrice(), row.getRawJson());
    }
}
