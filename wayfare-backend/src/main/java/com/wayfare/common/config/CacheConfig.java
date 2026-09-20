package com.wayfare.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * 缓存配置（Spring Cache + Redis）。
 *
 * <p><b>为什么没引 spring-boot-starter-cache</b>：本类自己定义了 {@code RedisCacheManager}，
 * 而 {@code @EnableCaching} 在 spring-context 里、{@code RedisCacheManager} 在 spring-data-redis 里，
 * 两者都已在依赖中。加那个 starter 只是多一个依赖，不会带来任何这里需要的能力。
 *
 * <h3>key 设计与 TTL（来自手册的缓存表）</h3>
 * <table>
 *   <tr><th>用途</th><th>key</th><th>TTL</th></tr>
 *   <tr><td>POI 检索</td><td>{@code map:poi:baidu:{city}:{keyword}:{pageNum}:{pageSize}}</td><td>24h</td></tr>
 *   <tr><td>POI 详情</td><td>{@code map:detail:{poiUid}}</td><td>7d</td></tr>
 * </table>
 * 路线缓存不在这里 —— 它由 {@code LocalCacheMapProvider} 直接读写 Redis，
 * 因为熔断降级时也要能读到同一份数据（见那个类的注释）。
 *
 * <p><b>大模型调用一律不缓存</b>：同一个输入需要能拿到可复现的新结果，
 * 缓存会让「换个说法再问一次」看起来没反应。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** POI 检索缓存名 */
    public static final String CACHE_MAP_POI = "map:poi";

    /** POI 详情缓存名 */
    public static final String CACHE_MAP_DETAIL = "map:detail";

    private static final Duration POI_TTL = Duration.ofHours(24);
    private static final Duration DETAIL_TTL = Duration.ofDays(7);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                // 默认前缀是 "cacheName::"，这里改成 "cacheName:"，
                // 让键正好等于手册里写的 map:poi:xxx / map:detail:xxx，便于线上直接 KEYS 查
                .computePrefixWith(cacheName -> cacheName + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(buildCacheObjectMapper())))
                // 不缓存 null：地图「这次没查到」往往只是暂时状态，
                // 若把它缓存 7 天，用户会连续一周都查不到这个点，比不缓存更糟
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CACHE_MAP_POI, base.entryTtl(POI_TTL),
                CACHE_MAP_DETAIL, base.entryTtl(DETAIL_TTL)
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /**
     * 缓存专用 ObjectMapper。
     *
     * <p>必须开启默认类型信息（{@code @class} 字段），否则反序列化回来是 LinkedHashMap
     * 而不是 {@code PoiDTO} —— 调用方拿到一个「长得像」但类型不对的对象，
     * 会在下一层用 getter 时才炸，且报错莫名其妙。
     */
    private ObjectMapper buildCacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
