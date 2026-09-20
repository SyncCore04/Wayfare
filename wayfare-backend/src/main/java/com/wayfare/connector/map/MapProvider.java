package com.wayfare.connector.map;

import java.util.List;

/**
 * 地图厂商的统一接口。
 *
 * <p>三个实现对应三种能力状态，它们<b>不是三个可选项，而是一条降级链</b>：
 * <ol>
 *   <li>{@code BaiduMapProvider} —— 实时调用（mode=VERIFIED）</li>
 *   <li>{@code LocalCacheMapProvider} —— 熔断/断网时查本地缓存（mode=CACHED）</li>
 *   <li>{@code DisabledMapProvider} —— 管理员整体关闭地图（mode=ESTIMATED）</li>
 * </ol>
 *
 * <p><b>「未命中返回空集合而不是抛异常」是刻意的</b>：地图能力是「增强」不是「必需」，
 * 拿不到数据时上层应该继续用估算把行程排出来，而不是整个请求失败。
 * 只有真正的参数错误才抛异常。
 */
public interface MapProvider {

    /** 当前能否真的发出请求 */
    boolean isAvailable();

    /** 实现名：baidu / cache / disabled */
    String name();

    /** 不可用时的中文原因，会原样进入诊断接口与降级说明 */
    String unavailableReason();

    /**
     * 地点检索。查不到返回空列表，不抛异常。
     */
    List<PoiDTO> searchPoi(PoiQueryDTO query);

    /**
     * 地点详情（评分、营业时间等只有详情接口才可能给全）。查不到返回 null。
     */
    PoiDTO detail(String poiUid);

    /**
     * 路线规划。算不出来返回 null（上层据此标记 ESTIMATED）。
     */
    RouteDTO route(RouteQueryDTO query);
}
