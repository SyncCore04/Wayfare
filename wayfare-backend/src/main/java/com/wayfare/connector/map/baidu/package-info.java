/**
 * 百度地图连接器实现（P1-C 落地）。
 *
 * <p>负责服务端 POI 检索与路线规划。AK 从 {@code BAIDU_MAP_AK} 环境变量注入，
 * <b>绝不允许出现在日志、接口响应或前端代码中</b>（后台回显一律掩码）。
 */
package com.wayfare.connector.map.baidu;
