package com.wayfare.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 行程草稿（P3-D · 管线 Step 4 的产物）。
 *
 * <p>大模型在这一步只做四件事：<b>分天、排时段、配餐饮、写理由</b>。
 * 它不产生任何「事实」—— 点位来自 P3-B 的候选池、空间顺序来自 P3-C 的算法、
 * 距离与时长来自 P3-F 的地图补全。所以本类里的每个字段都要问一句
 * 「这是模型该决定的，还是它不该碰的？」
 *
 * <p><b>{@link ItemDraft#poiRef} 是整条管线防幻觉的落点</b>：
 * 它必须能在候选池里找到（P3-E 的 CLOSURE 规则会逐条回查）。
 * 模型只有两种合法的填法：候选池里的**名称**或**序号**。填了池外的东西 = 编造，
 * 会被 CLOSURE 判 HIGH 违规并触发重排。
 *
 * <p><b>{@link ItemDraft#distanceMeters} 与 {@link ItemDraft#durationSeconds} 在本阶段恒为 null</b>：
 * 它们是「事实」，只能由地图给。模型即便输出了，解析时也直接丢弃
 * （不是「不采信」而是「不写进去」），由 P3-F 的事实补全回填 ——
 * 这是铁律一在这一步的落点。
 */
public class TripDraftDTO implements Serializable {

    /** 建议标题，如「寿阳古建两日慢行」 */
    private String title;

    /** 分天结果。长度必须恰好等于 {@code intent.days} */
    private List<DayDraft> days;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<DayDraft> getDays() { return days; }
    public void setDays(List<DayDraft> days) { this.days = days; }

    /**
     * 一天。
     *
     * <p>{@code dayIndex} 从 1 起（界面上要显示「第 1 天」），与 {@code TripDay} 表一致。
     */
    public static class DayDraft implements Serializable {

        /** 第几天，从 1 起 */
        private Integer dayIndex;

        /** 当天主题，如「古城寻塔」 */
        private String title;

        /** 当天小结。<b>画像偏好必须体现在这里</b>（prompt 的硬约束之一） */
        private String summary;

        private List<ItemDraft> items;

        public Integer getDayIndex() { return dayIndex; }
        public void setDayIndex(Integer dayIndex) { this.dayIndex = dayIndex; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<ItemDraft> getItems() { return items; }
        public void setItems(List<ItemDraft> items) { this.items = items; }
    }

    /** 一个行程条目 */
    public static class ItemDraft implements Serializable {

        /** 候选池里的名称或序号。<b>必须是池内点</b>，P3-E 的 CLOSURE 会逐条回查 */
        private String poiRef;

        /** 类型，取值见 {@code TripItem.TYPE_*} */
        private String itemType;

        /** 开始时间，格式 HH:mm */
        private String startTime;

        /** 结束时间，格式 HH:mm */
        private String endTime;

        /** 停留分钟数 */
        private Integer stayMinutes;

        /** 费用估算（元）。可为 null（未知）—— <b>不要用 0 冒充「免费」</b> */
        private BigDecimal costEstimate;

        /** 安排理由。<b>必填且要具体</b>，禁止「因为很值得去」这类空话 */
        private String reason;

        /**
         * 与下一站的距离（米）。<b>本阶段恒为 null</b> —— 它是事实，只能由地图给。
         * 模型输出了也丢弃，由 P3-F 回填。
         */
        private Integer distanceMeters;

        /**
         * 与下一站的耗时（秒）。<b>本阶段恒为 null</b>，理由同上。
         * 地图关闭时尤其重要：绝不能让模型编一个「步行 15 分钟」进数值字段。
         */
        private Integer durationSeconds;

        public String getPoiRef() { return poiRef; }
        public void setPoiRef(String poiRef) { this.poiRef = poiRef; }
        public String getItemType() { return itemType; }
        public void setItemType(String itemType) { this.itemType = itemType; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public Integer getStayMinutes() { return stayMinutes; }
        public void setStayMinutes(Integer stayMinutes) { this.stayMinutes = stayMinutes; }
        public BigDecimal getCostEstimate() { return costEstimate; }
        public void setCostEstimate(BigDecimal costEstimate) { this.costEstimate = costEstimate; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public Integer getDistanceMeters() { return distanceMeters; }
        public void setDistanceMeters(Integer distanceMeters) { this.distanceMeters = distanceMeters; }
        public Integer getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

        // ==================== P3-F · 事实补全后回填 ====================
        // 下面这些字段在 P3-D 编排阶段（大模型输出）不受信任、解析时不写，
        // 由 P3-F 的 Step6 enrichRoutes 从候选池 + 地图补全后回填，
        // 使返回的 TripDraftDTO 每个条目都能自证「坐标/距离从哪来、可信到几分」，
        // 满足「每一项都带 verifyStatus 与 dataSource」的交付要求。

        /** 地图 POI 唯一标识；地图关闭时为空（P3-F 从候选池回填） */
        private String poiUid;

        /** 点位名称（P3-F 从候选池回填，供前端直接展示） */
        private String poiName;

        /** 地址（P3-F 从候选池回填，可能为空） */
        private String address;

        /** 经度(BD-09)；地图关闭时为空 */
        private Double lng;

        /** 纬度(BD-09)；地图关闭时为空 */
        private Double lat;

        /** 到下一站交通方式 DRIVE/PUBLIC/WALK/MIX（P3-F 回填，末站为空） */
        private String transportModeToNext;

        /** 可信度 VERIFIED/CACHED/ESTIMATED（数据诚信机制） */
        private String verifyStatus;

        /** 数据来源 BAIDU/LLM（数据诚信机制） */
        private String dataSource;

        /** 备注；估算模式下放「步行约十几分钟」这类模糊表述 */
        private String note;

        public String getPoiUid() { return poiUid; }
        public void setPoiUid(String poiUid) { this.poiUid = poiUid; }
        public String getPoiName() { return poiName; }
        public void setPoiName(String poiName) { this.poiName = poiName; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public String getTransportModeToNext() { return transportModeToNext; }
        public void setTransportModeToNext(String transportModeToNext) { this.transportModeToNext = transportModeToNext; }
        public String getVerifyStatus() { return verifyStatus; }
        public void setVerifyStatus(String verifyStatus) { this.verifyStatus = verifyStatus; }
        public String getDataSource() { return dataSource; }
        public void setDataSource(String dataSource) { this.dataSource = dataSource; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }

        /**
         * 是否餐饮。P3-E 的「每天至少 1 个 FOOD」靠它判。
         *
         * <p>{@code @JsonIgnore} 是必须的：否则 Jackson 会把这个 getter 当成一个名为
         * {@code food} 的字段序列化出去，接口响应里就会多出一个内部判断用的布尔值
         * （实测确实漏出去了）。
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isFood() {
            return com.wayfare.entity.TripItem.TYPE_FOOD.equals(itemType);
        }
    }
}
