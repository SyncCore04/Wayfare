package com.wayfare.connector.map;

/**
 * 路线规划结果。
 *
 * <p><b>distanceMeters / durationSeconds 只允许来自地图 API</b>，
 * 或者由上层显式标记为估算（ESTIMATED）。大模型永远不允许直接给出公里数与分钟数 ——
 * 这是项目第一条铁律，写在这里是因为这个 DTO 正是它的落点。
 *
 * @param distanceMeters  距离（米），来自百度 routes[0].distance
 * @param durationSeconds 预计耗时（秒），来自百度 routes[0].duration
 * @param mode            出行方式 driving / walking / riding / transit
 * @param polyline        路线折线，分号分隔的 "lng,lat" 串（百度 steps[].path 拼接）
 * @param fromCache       是否来自本地缓存（熔断/关闭地图时的降级路径要用）
 */
public record RouteDTO(
        Integer distanceMeters,
        Integer durationSeconds,
        String mode,
        String polyline,
        boolean fromCache
) {
    /** 带缓存标记的副本，用于把「实时算出来的」原样存进缓存后再取出来 */
    public RouteDTO asCached() {
        return new RouteDTO(distanceMeters, durationSeconds, mode, polyline, true);
    }
}
