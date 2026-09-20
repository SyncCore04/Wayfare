package com.wayfare.service;

import com.wayfare.entity.SysConfig;

import java.util.List;

/**
 * 运行时配置服务（L2 开关层）。
 *
 * <p>所有读取都走 Redis 缓存（30 秒），因此高频调用也不会有数据库压力；
 * {@link #set} 会立即删掉缓存，保证「后台改完不重启即生效」。
 *
 * <p>带 fallback 参数的重载是 L2 → L1 的桥梁：
 * 先问 sys_config（L2），查不到就用调用方给的 application.yml 值（L1）。
 * 这样「L2 覆盖 L1、L2 未配置回落 L1」这条规则只需要在调用处写一行。
 */
public interface SysConfigService {

    /**
     * 读配置值。不存在返回 null。
     */
    String get(String key);

    /**
     * 读配置值，不存在或为空时返回 fallback（L2 → L1 回落）。
     */
    String get(String key, String fallback);

    /**
     * 读整型配置。值不是合法数字时返回 null（并打 WARN），不抛异常 ——
     * 一个配置项写错不该让整个请求失败。
     */
    Integer getInt(String key);

    /**
     * 读整型配置，缺失或非法时用 fallback。
     */
    int getInt(String key, int fallback);

    /**
     * 读布尔配置。只认 true/false（忽略大小写），其他值返回 null。
     */
    Boolean getBool(String key);

    /**
     * 读布尔配置，缺失或非法时用 fallback。
     */
    boolean getBool(String key, boolean fallback);

    /**
     * 写配置并立即失效缓存。
     *
     * @param operatorId 操作人ID，写入 updated_by；后台改的传管理员ID，系统初始值传 0
     */
    void set(String key, String value, Long operatorId);

    /**
     * 按分组读取（后台配置页用）。
     */
    List<SysConfig> listByGroup(String group);

    /**
     * 主动清空本地缓存。正常不需要调用（set 会自动清），
     * 仅用于运维排查或测试里强制回源。
     */
    void clearCache();
}
