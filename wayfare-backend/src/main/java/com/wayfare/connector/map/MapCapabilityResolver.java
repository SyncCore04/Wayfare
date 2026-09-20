package com.wayfare.connector.map;

import com.wayfare.connector.governance.CircuitBreaker;
import com.wayfare.connector.map.baidu.BaiduMapProvider;
import com.wayfare.connector.map.cache.LocalCacheMapProvider;
import com.wayfare.connector.map.disabled.DisabledMapProvider;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 地图能力决策器 —— 三级降级的大脑。
 *
 * <p><b>严格按手册规定的顺序决策</b>：
 * <pre>
 * 1) map.enabled == false（L2 → L1 分层读取）
 *      → mode=ESTIMATED, provider=DisabledMapProvider
 *      → reason="管理员已关闭地图连接器"
 *
 * 2) 百度不可用（熔断打开，或压根没配 AK）
 *      → 先试 LocalCacheMapProvider：拿到数据 mode=CACHED；没数据 mode=ESTIMATED
 *
 * 3) 否则 → mode=VERIFIED, provider=BaiduMapProvider, reason=null
 * </pre>
 *
 * <p><b>对第 2 步做了一处扩展，说明理由</b>：手册只写了「熔断器打开」这一种情况，
 * 但「地图开着、AK 却没配」同样会让每次请求必然失败。如果不把它也算作不可用，
 * 系统会傻傻地连发 5 次必然失败的请求才触发熔断 —— 徒增延迟与日志噪音。
 * 所以这里统一按「百度不可用」处理，reason 里如实写明是哪一种。
 *
 * <p><b>mode 到底是 CACHED 还是 ESTIMATED，要等真拿到数据才能定</b> ——
 * 缓存里有没有这条数据只有查了才知道。所以真正对外用的是 {@link #call}，
 * 它在调用结束后按「有没有数据」把 mode 敲定；{@link #resolve} 只回答「该找谁要数据」。
 */
@Component
public class MapCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(MapCapabilityResolver.class);

    /** sys_config 里的键名，与 db/schema-trip.sql 的初始数据一致 */
    private static final String KEY_MAP_ENABLED = "map.enabled";

    private final SysConfigService sysConfigService;
    private final MapProperties mapProperties;
    private final BaiduMapProvider baiduMapProvider;
    private final LocalCacheMapProvider localCacheMapProvider;
    private final DisabledMapProvider disabledMapProvider;
    private final CircuitBreaker circuitBreaker;

    public MapCapabilityResolver(SysConfigService sysConfigService,
                                 MapProperties mapProperties,
                                 BaiduMapProvider baiduMapProvider,
                                 LocalCacheMapProvider localCacheMapProvider,
                                 DisabledMapProvider disabledMapProvider,
                                 CircuitBreaker circuitBreaker) {
        this.sysConfigService = sysConfigService;
        this.mapProperties = mapProperties;
        this.baiduMapProvider = baiduMapProvider;
        this.localCacheMapProvider = localCacheMapProvider;
        this.disabledMapProvider = disabledMapProvider;
        this.circuitBreaker = circuitBreaker;
    }

    /** map.enabled 当前值（L2 → L1 分层读取），诊断接口用它回答「地图开了没有」 */
    public boolean isMapEnabled() {
        return sysConfigService.getBool(KEY_MAP_ENABLED, mapProperties.isEnabled());
    }

    /** 决定「该找谁要数据」，以及这一步的可信度预期 */
    public ResolvedMap resolve() {        // 1) 总开关：先问 sys_config（L2），读不到才用 application.yml（L1）
        boolean enabled = sysConfigService.getBool(KEY_MAP_ENABLED, mapProperties.isEnabled());
        if (!enabled) {
            return new ResolvedMap(MapMode.ESTIMATED, disabledMapProvider, "管理员已关闭地图连接器");
        }

        // 2) 百度是否可用（熔断打开 / 没配 AK 都算不可用，理由如上）
        if (!baiduMapProvider.isAvailable()) {
            String reason = baiduMapProvider.unavailableReason();
            log.debug("地图实时能力不可用（{}），先尝试本地缓存", reason);
            // 注意：这里先给 CACHED，实际是 CACHED 还是 ESTIMATED 由 call() 按数据命中情况敲定
            return new ResolvedMap(MapMode.CACHED, localCacheMapProvider, reason);
        }

        // 3) 正常
        return ResolvedMap.verified(baiduMapProvider);
    }

    /**
     * 带降级的调用入口：这是业务代码<b>唯一该用</b>的地图调用方式。
     *
     * @param action  真正的调用动作，参数是选中的 Provider
     * @param hasData 判断返回值「是否拿到了有效数据」，用于把 CACHED 降为 ESTIMATED
     */
    public <T> MapCallResult<T> call(Function<MapProvider, T> action, Predicate<T> hasData) {
        ResolvedMap resolved = resolve();

        if (resolved.mode() == MapMode.ESTIMATED && resolved.provider() == disabledMapProvider) {
            // 地图整体关闭：连缓存都不查，直接给空值让上层走估算
            T empty = action.apply(disabledMapProvider);
            return new MapCallResult<>(empty, MapMode.ESTIMATED,
                    disabledMapProvider.name(), resolved.reason());
        }

        T value = action.apply(resolved.provider());

        if (resolved.mode() == MapMode.CACHED) {
            if (hasData != null && hasData.test(value)) {
                return new MapCallResult<>(value, MapMode.CACHED,
                        resolved.provider().name(), resolved.reason());
            }
            // 缓存也没命中 → 只能估算
            return new MapCallResult<>(value, MapMode.ESTIMATED, resolved.provider().name(),
                    resolved.reason() + "；且本地缓存未命中该数据");
        }

        return new MapCallResult<>(value, MapMode.VERIFIED, resolved.provider().name(), null);
    }

    /** 熔断器状态快照，供诊断接口读取 */
    public CircuitBreaker breaker() {
        return circuitBreaker;
    }

    public BaiduMapProvider baiduProvider() {
        return baiduMapProvider;
    }

    public LocalCacheMapProvider cacheProvider() {
        return localCacheMapProvider;
    }

    public DisabledMapProvider disabledProvider() {
        return disabledMapProvider;
    }

    /**
     * 一次地图调用的结果：值 + 最终敲定的可信度 + 用了谁 + 为什么降级。
     * 上层要把 mode 与 reason 透传到接口响应里（前端据此显示角标与提示）。
     */
    public record MapCallResult<T>(T value, MapMode mode, String providerName, String reason) {
        public boolean degraded() {
            return mode != MapMode.VERIFIED;
        }
    }
}
