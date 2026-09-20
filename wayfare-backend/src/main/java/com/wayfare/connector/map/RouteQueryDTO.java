package com.wayfare.connector.map;

/**
 * 路线查询条件。
 *
 * <p><b>坐标顺序是本项目最容易出错的地方，这里一次说清</b>：
 * <ul>
 *   <li>本 DTO 用 <b>lng, lat</b> 顺序（与 GeoJSON、以及绝大多数现代 API 一致）；</li>
 *   <li>但<b>百度请求参数的 origin/destination 用的是 "lat,lng"</b>（纬度在前）；
 *       而百度<b>响应里</b>又变成 {@code {lng:..., lat:...}} 的对象。</li>
 * </ul>
 * 也就是说同一家厂商「请求用纬度在前、响应用经度在前」，凭直觉写必然错一半。
 * 转换只在 {@code BaiduMapProvider} 一处做，并写明注释，绝不让顺序问题散落到业务代码里。
 */
public class RouteQueryDTO {

    /** 起点经度 */
    private Double fromLng;
    /** 起点纬度 */
    private Double fromLat;
    /** 终点经度 */
    private Double toLng;
    /** 终点纬度 */
    private Double toLat;

    /** 出行方式：driving / walking / riding / transit */
    private String mode = "driving";

    public RouteQueryDTO() {}

    public RouteQueryDTO(Double fromLng, Double fromLat, Double toLng, Double toLat, String mode) {
        this.fromLng = fromLng;
        this.fromLat = fromLat;
        this.toLng = toLng;
        this.toLat = toLat;
        if (mode != null) this.mode = mode;
    }

    public Double getFromLng() { return fromLng; }
    public void setFromLng(Double fromLng) { this.fromLng = fromLng; }
    public Double getFromLat() { return fromLat; }
    public void setFromLat(Double fromLat) { this.fromLat = fromLat; }
    public Double getToLng() { return toLng; }
    public void setToLng(Double toLng) { this.toLng = toLng; }
    public Double getToLat() { return toLat; }
    public void setToLat(Double toLat) { this.toLat = toLat; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
