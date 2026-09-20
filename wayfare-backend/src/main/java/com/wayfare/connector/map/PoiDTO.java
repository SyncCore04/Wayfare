package com.wayfare.connector.map;

/**
 * 地图上的一个兴趣点。
 *
 * <p><b>关于坐标系（务必注意）</b>：百度地图返回的经纬度是 <b>BD-09</b>（百度自有坐标系），
 * 与 GPS 的 WGS-84、以及高德/腾讯的 GCJ-02 都不通用。
 * 本项目目前只接百度一家、也只在自己库里存这一份坐标，所以<b>不转换</b>；
 * 但如果将来换地图商或把坐标打到别家地图上，必须做 BD-09 ↔ GCJ-02 转换，
 * 否则点会偏移几百米 —— 这属于「看起来对、实际全错」的典型故障。
 *
 * <p><b>关于字段可能为 null</b>：百度的检索接口<b>经常不返回评分，也基本不返回票价</b>。
 * 这种情况下 {@code rating} / {@code ticketPrice} 保持 <b>null 表示「未知」</b>。
 * <b>绝对不允许用 0 或任何编造的数字冒充</b> —— 前端会把 null 显示成「未知」，
 * 这是这个项目的数据诚信底线（0 分和未知完全是两回事）。
 *
 * @param poiUid      百度 POI 唯一 ID，可用于详情查询
 * @param rawJson     原始响应片段，保留下来便于排查「字段为什么是空的」
 */
public record PoiDTO(
        String poiUid,
        String name,
        String address,
        Double lng,
        Double lat,
        String tag,
        String shopHours,
        Double rating,
        Double ticketPrice,
        String rawJson
) {
    /** 是否包含坐标（没有坐标的点位在行程里没法排序，需要上层处理） */
    public boolean hasLocation() {
        return lng != null && lat != null;
    }
}
