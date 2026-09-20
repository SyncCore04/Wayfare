package com.wayfare.connector.map;

/**
 * 地图能力决策结果。
 *
 * @param mode     数据可信度（VERIFIED / CACHED / ESTIMATED）
 * @param provider 实际要用的实现
 * @param reason   进入降级的原因；正常（VERIFIED）时为 null。
 *                 这段文字会被写进行程返回的 meta、也会进诊断接口，所以要写清「为什么降级」。
 */
public record ResolvedMap(MapMode mode, MapProvider provider, String reason) {

    public static ResolvedMap verified(MapProvider provider) {
        return new ResolvedMap(MapMode.VERIFIED, provider, null);
    }

    public boolean degraded() {
        return mode != MapMode.VERIFIED;
    }
}
