package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 行程条目实体（P2-B，本项目的核心表）
 * 对应数据库表: trip_item
 *
 * <p>一条 = 行程里的一个安排（景点 / 餐饮 / 住宿 / 交通 / 休息）。
 *
 * <h3>数据诚信机制（本类最重要的约定）</h3>
 * 铁律 1「事实数据永不来自大模型」在代码上的落点就是 {@code verifyStatus} 与
 * {@code dataSource} 这一对字段。<b>任何进了本表的事实字段都必须带这两个标记，
 * 没有标记的事实字段视为设计缺陷。</b>
 *
 * <ul>
 *   <li>{@code verifyStatus} —— 这个事实可信到什么程度：
 *     <ul>
 *       <li>{@link #VERIFY_VERIFIED} 绿「实测」：来自地图 API 的真实返回</li>
 *       <li>{@link #VERIFY_CACHED} 灰「缓存」：来自本地 poi_cache / 路线缓存，不是刚查的</li>
 *       <li>{@link #VERIFY_ESTIMATED} 橙「估算」：地图关闭或没查到，由本地规则估算</li>
 *       <li>{@link #VERIFY_USER} 蓝「手动」：用户自己改过的值</li>
 *     </ul>
 *   </li>
 *   <li>{@code dataSource} —— 这个事实是谁给的：
 *     {@link #SOURCE_BAIDU} 地图 API / {@link #SOURCE_LLM} 大模型（只能给语义类字段，
 *     如 {@code reason}、{@code note}）/ {@link #SOURCE_USER} 用户。</li>
 * </ul>
 *
 * <h3>估算模式下怎么写（别让模型编数字）</h3>
 * {@code distanceMeters} 与 {@code durationSeconds} 留 <b>null</b>，
 * 把模糊表述写进 {@code note}（如「步行约十几分钟」）。
 * <b>绝不在数值列里填一个编出来的具体数字</b> —— 这是最后一道防线：
 * 列里没有数字，前端就渲染不出假精度，用户也就不会被「3.2 公里」骗到。
 *
 * <p>另一个容易混的点：{@code ticketPrice} 的 <b>null 表示「未知」，0 表示「免费」</b>，
 * 两者不是一回事（免费景点是真实的 0 元）。这与 {@code poi_cache.rating} 的处理一致。
 */
@TableName("trip_item")
public class TripItem implements Serializable {

    // ---- itemType 取值 ----
    public static final String TYPE_SCENIC = "SCENIC";
    public static final String TYPE_FOOD = "FOOD";
    public static final String TYPE_HOTEL = "HOTEL";
    public static final String TYPE_TRANSPORT = "TRANSPORT";
    public static final String TYPE_REST = "REST";

    // ---- verifyStatus 取值（可信度，前端角标：绿/灰/橙/蓝）----
    public static final String VERIFY_VERIFIED = "VERIFIED";
    public static final String VERIFY_CACHED = "CACHED";
    public static final String VERIFY_ESTIMATED = "ESTIMATED";
    public static final String VERIFY_USER = "USER";

    // ---- dataSource 取值（谁给的）----
    public static final String SOURCE_BAIDU = "BAIDU";
    public static final String SOURCE_LLM = "LLM";
    public static final String SOURCE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripId;

    /** 第几天，从 1 起（与 trip_day.dayIndex 对齐） */
    private Integer dayIndex;

    /** 当天顺序，从 0 起（纯代码排序用，与 dayIndex 的起点不同是有意的） */
    private Integer seq;

    /** 类型 SCENIC/FOOD/HOTEL/TRANSPORT/REST */
    private String itemType;

    /** 地图 POI 唯一标识；地图关闭时为空 */
    private String poiUid;

    private String poiName;

    private String address;

    /** 经度(BD-09)；地图关闭时可空 */
    private BigDecimal lng;

    /** 纬度(BD-09)；地图关闭时可空 */
    private BigDecimal lat;

    private LocalTime arriveTime;

    private LocalTime leaveTime;

    private Integer stayMinutes;

    /** 门票价；null=未知，0=免费（两者不可混同） */
    private BigDecimal ticketPrice;

    private BigDecimal costEstimate;

    /** 到下一站的交通方式 DRIVE/PUBLIC/WALK/MIX */
    private String transportModeToNext;

    /** 到下一站距离（米）；估算模式下为 null */
    private Integer distanceMeters;

    /** 到下一站耗时（秒）；估算模式下为 null */
    private Integer durationSeconds;

    /** 可信度 VERIFIED/CACHED/ESTIMATED/USER（数据诚信机制） */
    private String verifyStatus;

    /** 数据来源 BAIDU/LLM/USER（数据诚信机制） */
    private String dataSource;

    /** AI 给出的安排理由 —— 属语义字段，来自 LLM 是合规的 */
    private String reason;

    /** 备注；估算模式下放模糊表述，如「步行约十几分钟」 */
    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public Integer getDayIndex() { return dayIndex; }
    public void setDayIndex(Integer dayIndex) { this.dayIndex = dayIndex; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getPoiUid() { return poiUid; }
    public void setPoiUid(String poiUid) { this.poiUid = poiUid; }
    public String getPoiName() { return poiName; }
    public void setPoiName(String poiName) { this.poiName = poiName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public LocalTime getArriveTime() { return arriveTime; }
    public void setArriveTime(LocalTime arriveTime) { this.arriveTime = arriveTime; }
    public LocalTime getLeaveTime() { return leaveTime; }
    public void setLeaveTime(LocalTime leaveTime) { this.leaveTime = leaveTime; }
    public Integer getStayMinutes() { return stayMinutes; }
    public void setStayMinutes(Integer stayMinutes) { this.stayMinutes = stayMinutes; }
    public BigDecimal getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(BigDecimal ticketPrice) { this.ticketPrice = ticketPrice; }
    public BigDecimal getCostEstimate() { return costEstimate; }
    public void setCostEstimate(BigDecimal costEstimate) { this.costEstimate = costEstimate; }
    public String getTransportModeToNext() { return transportModeToNext; }
    public void setTransportModeToNext(String transportModeToNext) { this.transportModeToNext = transportModeToNext; }
    public Integer getDistanceMeters() { return distanceMeters; }
    public void setDistanceMeters(Integer distanceMeters) { this.distanceMeters = distanceMeters; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getVerifyStatus() { return verifyStatus; }
    public void setVerifyStatus(String verifyStatus) { this.verifyStatus = verifyStatus; }
    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
