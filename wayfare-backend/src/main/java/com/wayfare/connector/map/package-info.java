/**
 * 地图连接器抽象（P1-C 落地）。
 *
 * <p>对外只暴露一个统一接口，内部按可用性分派到三种实现：
 * {@code baidu}（实时调用）、{@code cache}（熔断后查本地 poi_cache）、
 * {@code disabled}（管理员整体关闭地图）。
 *
 * <p>每条事实数据都必须带可信度标记：VERIFIED / CACHED / ESTIMATED / USER。
 */
package com.wayfare.connector.map;
