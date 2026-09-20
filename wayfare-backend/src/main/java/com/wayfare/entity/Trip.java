package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 行程主表实体（P2-B）
 * 对应数据库表: trip
 *
 * <p>一条记录 = 用户的一次行程规划，是行程域的根。P3 的七步管线最终产物就是
 * 「一条 trip + N 条 trip_day + M 条 trip_item」。
 *
 * <p><b>坐标字段为什么允许为 NULL</b>：{@code destLng / destLat} 在地图连接器整体关闭时
 * 就是空的，而这是<b>正常状态</b>而非数据缺失（铁律 2：地图关闭只是能力降级）。
 * 用 (0,0) 冒充「没有坐标」会把行程指到几内亚湾，比 NULL 危险得多 ——
 * 所以本类里所有坐标与金额字段一律用包装类型 / BigDecimal，不用基本类型。
 *
 * <p><b>{@code mapMode} 与 {@code profileUsed} 是「本次生成的自述」</b>：
 * 前者记录这次的数据可信度（与 trip_item.verifyStatus 同源），
 * 后者记录这次有没有注入用户画像（铁律 3：画像注入是增强，用户关掉开关时这里就是 0）。
 * 两者都是 P3 结果组装阶段回写的，用于事后复盘与前端角标。
 */
@TableName("trip")
public class Trip implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 行程标题，用户可改；初值由 AI 生成 */
    private String title;

    /** 用户原始输入（自然语言原文，保留便于复盘） */
    private String rawInput;

    /** 意图解析结果 JSON（P3 Step1 写入），存字符串不引 JSON 类型处理器 */
    private String intentJson;

    private String destination;

    /** 目的地中心经度(BD-09)；地图关闭时为 null */
    private BigDecimal destLng;

    /** 目的地中心纬度(BD-09)；地图关闭时为 null */
    private BigDecimal destLat;

    /** 行程天数 */
    private Integer days;

    private LocalDate startDate;

    private BigDecimal budgetTotal;

    /** 预算口径 1人均 2总计 */
    private Integer budgetMode;

    /** 交通方式 DRIVE/PUBLIC/WALK/MIX */
    private String transport;

    private String companion;

    /** 本次生成的数据可信度 VERIFIED/CACHED/ESTIMATED */
    private String mapMode;

    /** 本次生成是否使用了用户画像 0否 1是 */
    private Integer profileUsed;

    /** 攻略文案（P4-B 生成后写回，重复访问不重新生成） */
    private String guideText;

    /** 业务状态 0草稿 1已生成 2已编辑 3已发布 */
    private Integer status;

    /** 发布为攻略后关联 work.id，未发布为 null */
    private Long workId;

    /** 本次使用的模型，如 glm-4-flash / deepseek-chat */
    private String modelName;

    /** 约束校验打回重排的实际轮次（观测迭代优化效果） */
    private Integer generationRounds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除 0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /**
     * 非数据库字段：详情聚合用的「按天安排」。
     *
     * <p><b>为什么叫 {@code dayPlans} 而不是 {@code days}</b>：本表已有一个
     * {@code days} 列表示「天数」（Integer），两者同名会让 MyBatis-Plus 的
     * 字段映射彻底混乱 —— 一个是要落库的整数，一个是纯内存的集合。
     * 所以聚合字段改名，并在类注释里点明这个撞车点，避免后来人「顺手统一」。
     */
    @TableField(exist = false)
    private List<TripDay> dayPlans;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRawInput() { return rawInput; }
    public void setRawInput(String rawInput) { this.rawInput = rawInput; }
    public String getIntentJson() { return intentJson; }
    public void setIntentJson(String intentJson) { this.intentJson = intentJson; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public BigDecimal getDestLng() { return destLng; }
    public void setDestLng(BigDecimal destLng) { this.destLng = destLng; }
    public BigDecimal getDestLat() { return destLat; }
    public void setDestLat(BigDecimal destLat) { this.destLat = destLat; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public BigDecimal getBudgetTotal() { return budgetTotal; }
    public void setBudgetTotal(BigDecimal budgetTotal) { this.budgetTotal = budgetTotal; }
    public Integer getBudgetMode() { return budgetMode; }
    public void setBudgetMode(Integer budgetMode) { this.budgetMode = budgetMode; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getCompanion() { return companion; }
    public void setCompanion(String companion) { this.companion = companion; }
    public String getMapMode() { return mapMode; }
    public void setMapMode(String mapMode) { this.mapMode = mapMode; }
    public Integer getProfileUsed() { return profileUsed; }
    public void setProfileUsed(Integer profileUsed) { this.profileUsed = profileUsed; }
    public String getGuideText() { return guideText; }
    public void setGuideText(String guideText) { this.guideText = guideText; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getGenerationRounds() { return generationRounds; }
    public void setGenerationRounds(Integer generationRounds) { this.generationRounds = generationRounds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public List<TripDay> getDayPlans() { return dayPlans; }
    public void setDayPlans(List<TripDay> dayPlans) { this.dayPlans = dayPlans; }
}
