package com.wayfare.connector.map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 地图连接器启动期配置（L1）。
 * 对应 application.yml 的 {@code map:} 段。
 *
 * <p><b>关于 {@code enabled} 的语义，这一条最容易被误解，写清楚</b>：
 * 它就是「地图整体开关」，默认 <b>false</b>。关闭之后系统<b>不是「少了个功能」</b>，
 * 而是把坐标/距离/时长的来源整体切成估算，并在结果上标记 {@code ESTIMATED}。
 * 也就是项目铁律里的「连接器可插拔 —— 关闭地图是<b>能力降级</b>，不是功能降级」。
 * 所以本地没有百度地图 AK 也能把 P1 之后的全部链路跑通。
 *
 * <p>与 L2 的关系同 {@code LlmProperties}：{@code map.enabled} 与 {@code map.baidu.ak}
 * 在 sys_config 表里也有对应项，运行时以 L2 为准，本类是 L1 兜底值。
 */
@Component
@ConfigurationProperties(prefix = "map")
public class MapProperties {

    private static final Logger log = LoggerFactory.getLogger(MapProperties.class);

    /** 是否启用地图能力（L1 兜底值，运行时以 sys_config 的 map.enabled 为准） */
    private boolean enabled = false;

    private Baidu baidu = new Baidu();

    /**
     * 百度地图服务端配置。
     *
     * <p>{@code ak} 来自 {@code ${BAIDU_MAP_AK:}}，<b>禁止写真实值进配置文件</b>。
     * 这个 Key 只允许待在服务端：任何接口响应、日志、前端代码里出现明文都算事故，
     * 后台回显一律掩码（P6-A 实现）。
     */
    public static class Baidu {

        private String ak;
        private String baseUrl = "https://api.map.baidu.com";
        private Integer connectTimeoutMs = 3000;
        private Integer readTimeoutMs = 8000;

        /** 是否已配置 AK。仅判断，不做网络调用 */
        public boolean isConfigured() {
            return ak != null && !ak.isBlank();
        }

        public String getAk() { return ak; }
        public void setAk(String ak) { this.ak = ak; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public Integer getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(Integer readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Baidu getBaidu() { return baidu; }
    public void setBaidu(Baidu baidu) { this.baidu = baidu; }

    /**
     * 启动时把地图配置情况打到日志里，与 {@code LlmProperties} 同款。
     *
     * <p>要特别说清一件容易误判的事：<b>{@code enabled=false} 不是错误状态</b>，
     * 它是本项目的默认值，意味着地图能力整体关闭、距离时长走估算（ESTIMATED），
     * 系统仍然全功能可用。所以这里用 INFO 而不是 WARN，避免把正常默认态报成异常。
     *
     * <p>AK 只打前 4 位与长度，不打明文（AK 泄露等于别人可以拿你的额度刷接口）。
     */
    @PostConstruct
    public void logMapStatus() {
        boolean akConfigured = baidu != null && baidu.isConfigured();
        if (enabled) {
            log.info("地图能力[启用], baseUrl={}, 超时={}ms/{}ms, AK={}（长度 {}）",
                    baidu.getBaseUrl(), baidu.getConnectTimeoutMs(), baidu.getReadTimeoutMs(),
                    maskAk(baidu.getAk()), baidu.getAk() == null ? 0 : baidu.getAk().length());
            if (!akConfigured) {
                log.warn("地图已启用但百度地图 AK 为空，任何地图调用都会失败 —— 请填 BAIDU_MAP_AK 或把 map.enabled 关掉");
            }
        } else {
            log.info("地图能力[关闭]（默认值，属正常状态）: 距离/时长将标记为 ESTIMATED，系统全功能可用；AK={}",
                    akConfigured ? maskAk(baidu.getAk()) + "（已配置但当前未启用）" : "(空)");
        }
    }

    private String maskAk(String ak) {
        if (ak == null || ak.isEmpty()) return "(空)";
        if (ak.length() <= 4) return "****";
        return ak.substring(0, 4) + "****";
    }
}
