package com.wayfare.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户旅行偏好画像实体类
 * 对应数据库表: user_travel_profile
 *
 * <p><b>这张表是给大模型「加料」用的，不是给用户看的</b> ——
 * 它的消费方是 P3 的 {@code ProfileRenderer}：把结构化字段渲染成一段中文文本块，
 * 拼进行程编排阶段的 system prompt。所以字段设计的第一原则是
 * <b>「渲染时能不能写出人话」</b>，而不是「查询时方不方便索引」。
 *
 * <p><b>画像注入是增强，不是必需（铁律 3）</b>：{@link #allowAiUse} 置 0 后
 * 规划照常跑通，只是不再个性化。因此本类的任何字段都可能是 null / 空串，
 * 上层不得假设「画像一定存在」或「某字段一定有值」。
 *
 * <p><b>三处刻意用包装类型而不是基本类型</b>（{@code pace} / {@code budgetLevel} /
 * {@code walkLimitKm}）：它们是 TINYINT / INT 可空列，「没填」必须能与「填了 0」区分开。
 * 用 {@code int} 的话没填会默认成 0，渲染时就会输出「节奏：慢（每天 2-3 个点）」这种
 * 用户从没选过的结论 —— 与 {@code poi_cache.rating} 保留 null 语义是同一条原则。
 */
@TableName("user_travel_profile")
public class UserTravelProfile implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID，一人一行（库里有 uk_user_id 唯一索引兜底） */
    private Long userId;

    // ==================== 口味 ====================

    /** 喜欢菜系，逗号分隔，如 "晋菜,面食,家常菜" */
    private String cuisines;

    /** 口味偏好，如 "偏咸,微辣" */
    private String flavors;

    /**
     * 忌口与过敏，逗号分隔，如 "香菜,花生,海鲜"。
     * <p><b>这是硬约束</b>：渲染时会专门标注「任何推荐都不得包含」，
     * 与 {@code flavors}（软偏好）语义完全不同，不要合并。
     */
    private String taboos;

    // ==================== 风格 ====================

    /** 旅行风格，多选逗号分隔：古建探访/自然风光/博物馆/市井烟火/摄影旅拍/亲子出行/城市漫步/美食之旅 */
    private String travelStyles;

    /** 节奏 1慢(每天2-3点) 2适中(3-4) 3紧凑(4-5)，null = 未填写 */
    private Integer pace;

    /** 预算倾向 1经济 2舒适 3品质，null = 未填写 */
    private Integer budgetLevel;

    /** 常同行人 独自/情侣/朋友/家庭带娃/带长辈 */
    private String companions;

    /** 单日步行上限（公里），用于约束点位密度，null = 未填写 */
    private Integer walkLimitKm;

    // ==================== 其他 ====================

    /** 住宿偏好 */
    private String hotelPref;

    /** 自由备注（渲染时会以「补充说明」原样带进 prompt） */
    private String notes;

    /**
     * 隐私开关：是否允许 AI 使用本画像，0否 1是，默认 1。
     * <p>关掉后 P3 应跳过注入并在响应里标记 {@code profileUsed=false}。
     */
    private Integer allowAiUse;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCuisines() { return cuisines; }
    public void setCuisines(String cuisines) { this.cuisines = cuisines; }
    public String getFlavors() { return flavors; }
    public void setFlavors(String flavors) { this.flavors = flavors; }
    public String getTaboos() { return taboos; }
    public void setTaboos(String taboos) { this.taboos = taboos; }
    public String getTravelStyles() { return travelStyles; }
    public void setTravelStyles(String travelStyles) { this.travelStyles = travelStyles; }
    public Integer getPace() { return pace; }
    public void setPace(Integer pace) { this.pace = pace; }
    public Integer getBudgetLevel() { return budgetLevel; }
    public void setBudgetLevel(Integer budgetLevel) { this.budgetLevel = budgetLevel; }
    public String getCompanions() { return companions; }
    public void setCompanions(String companions) { this.companions = companions; }
    public Integer getWalkLimitKm() { return walkLimitKm; }
    public void setWalkLimitKm(Integer walkLimitKm) { this.walkLimitKm = walkLimitKm; }
    public String getHotelPref() { return hotelPref; }
    public void setHotelPref(String hotelPref) { this.hotelPref = hotelPref; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Integer getAllowAiUse() { return allowAiUse; }
    public void setAllowAiUse(Integer allowAiUse) { this.allowAiUse = allowAiUse; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
