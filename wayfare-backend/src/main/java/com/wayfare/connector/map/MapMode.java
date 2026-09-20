package com.wayfare.connector.map;

/**
 * 数据可信度标记。前端会按它显示不同颜色的角标，用户据此判断能不能照着走。
 *
 * <p>这是项目「事实数据永不来自大模型」那条铁律的可视化出口：
 * 凡是带 {@link #ESTIMATED} 的坐标/距离/时长，都是估算值，界面上必须让用户看得出来。
 */
public enum MapMode {

    /** 绿：来自地图 API 的实测数据 */
    VERIFIED,

    /** 灰：来自本地缓存的历史数据（当前熔断或断网） */
    CACHED,

    /** 橙：估算值，未经地图校验 */
    ESTIMATED
}
