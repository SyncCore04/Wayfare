package com.wayfare.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 用户旅行偏好画像保存请求DTO（P2-A）。
 *
 * <p>刻意不复用实体 {@code UserTravelProfile} 作为入参：实体带着 id / userId /
 * createdAt / updatedAt，让它们出现在请求体里既没意义（userId 一律取自登录态，
 * 不接受客户端指定），又给「越权改别人画像」留了口子。用 DTO 只暴露用户真正该填的字段。
 *
 * <p>{@code @Size} 的上限与 {@code user_travel_profile} 的列宽严格一致 ——
 * 校验放在入口，用户拿到的是「菜系偏好长度不能超过255位」这种可读提示，
 * 而不是数据库抛出来的截断错误。
 */
public class TravelProfileDTO {

    @Size(max = 255, message = "菜系偏好长度不能超过255位")
    private String cuisines;

    @Size(max = 255, message = "口味偏好长度不能超过255位")
    private String flavors;

    @Size(max = 500, message = "忌口与过敏长度不能超过500位")
    private String taboos;

    @Size(max = 255, message = "旅行风格长度不能超过255位")
    private String travelStyles;

    /** 节奏 1慢 2适中 3紧凑；null = 未填写 */
    @Min(value = 1, message = "节奏取值只能是 1慢 / 2适中 / 3紧凑")
    @Max(value = 3, message = "节奏取值只能是 1慢 / 2适中 / 3紧凑")
    private Integer pace;

    /** 预算倾向 1经济 2舒适 3品质；null = 未填写 */
    @Min(value = 1, message = "预算倾向取值只能是 1经济 / 2舒适 / 3品质")
    @Max(value = 3, message = "预算倾向取值只能是 1经济 / 2舒适 / 3品质")
    private Integer budgetLevel;

    @Size(max = 50, message = "常同行人长度不能超过50位")
    private String companions;

    /** 单日步行上限（公里）；null = 未填写 */
    @Min(value = 0, message = "单日步行上限不能为负数")
    @Max(value = 100, message = "单日步行上限请填 0-100 之间的公里数")
    private Integer walkLimitKm;

    @Size(max = 255, message = "住宿偏好长度不能超过255位")
    private String hotelPref;

    @Size(max = 500, message = "补充说明长度不能超过500位")
    private String notes;

    /** 隐私开关 0否 1是；不传按 1（允许）处理 */
    @Min(value = 0, message = "隐私开关只能是 0 或 1")
    @Max(value = 1, message = "隐私开关只能是 0 或 1")
    private Integer allowAiUse;

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
}
