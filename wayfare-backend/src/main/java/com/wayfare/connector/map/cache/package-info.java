/**
 * 地图缓存降级实现（P1-C / P1-D 落地）。
 *
 * <p>熔断打开或调用失败时，查本地 poi_cache 表命中历史数据，
 * 命中标记 {@code CACHED}，未命中标记 {@code ESTIMATED}，
 * 让行程在断网或配额耗尽时依然能生成。
 */
package com.wayfare.connector.map.cache;
