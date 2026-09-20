package com.wayfare.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 意图解析结果（P3-A · 管线 Step 1）。
 *
 * <p>把「周末想去寿阳玩两天，喜欢古建筑，预算 500」这样一句话，变成后续六步都能吃的结构化数据。
 * 它是整条管线的输入契约：<b>P3-B 的候选检索靠它定目的地与偏好标签，P3-D 的分天靠它定天数与节奏，
 * P3-E 的预算校验靠它定口径</b>。字段一旦改名，下游全要跟着动。
 *
 * <p><b>关于 {@code needConfirm}（本类最重要的设计）</b>：
 * 大模型天然倾向于「把不确定的东西填得像确定的」。用户说「预算 500」没说是人均还是总计，
 * 模型完全可以随手填个 {@code TOTAL} 就交差 —— 而下游会照此校验预算，
 * 用户最后看到的是一个「按错误口径算出来的超支提示」。
 * 所以这里强制模型<b>把自己猜的字段名列进 {@code needConfirm}</b>，
 * 前端据此高亮让用户确认（见《开发文档》§3.1 的「用户确认表单」）。
 * 这不是可选的美化项 —— 没有它，铁律一（事实数据永不来自大模型）在解析这一步就已经漏了。
 *
 * <p><b>关于坐标</b>：{@code destLng} / {@code destLat} 只能由地图连接器填。
 * 地图关闭或检索不到时<b>保持 null</b>，绝不填模型猜的坐标 ——
 * 一个编出来的经纬度会让后面整条空间预排（P3-C）建立在错误的基础上，
 * 而那种错误在界面上看不出来（行程看着很合理，只是地点全错）。
 *
 * <p>实现为普通类而非 record：它要被 Jackson 序列化进 {@code trip.intent_json}（P3-F 落库），
 * 也要被解析代码逐字段赋值，getter/setter 形式与项目里其它 DTO 保持一致。
 */
public class IntentDTO implements Serializable {

    // ==================== 枚举取值（校验与提示都引用这里，不要手写字符串）====================

    /** 预算口径：人均 */
    public static final String BUDGET_MODE_PER_PERSON = "PER_PERSON";
    /** 预算口径：总计 */
    public static final String BUDGET_MODE_TOTAL = "TOTAL";
    /** 预算口径的全部合法值 */
    public static final List<String> BUDGET_MODES = List.of(BUDGET_MODE_PER_PERSON, BUDGET_MODE_TOTAL);

    /** 交通方式：自驾 */
    public static final String TRANSPORT_DRIVE = "DRIVE";
    /** 交通方式：公共交通 */
    public static final String TRANSPORT_PUBLIC = "PUBLIC";
    /** 交通方式：步行 */
    public static final String TRANSPORT_WALK = "WALK";
    /** 交通方式：混合 */
    public static final String TRANSPORT_MIX = "MIX";
    /** 交通方式的全部合法值 */
    public static final List<String> TRANSPORTS =
            List.of(TRANSPORT_DRIVE, TRANSPORT_PUBLIC, TRANSPORT_WALK, TRANSPORT_MIX);

    /** 节奏：慢 */
    public static final int PACE_SLOW = 1;
    /** 节奏：适中 */
    public static final int PACE_NORMAL = 2;
    /** 节奏：紧凑 */
    public static final int PACE_PACKED = 3;
    /** 节奏的全部合法值（与 {@code user_travel_profile.pace}、{@code ProfileRenderer} 的口径一致） */
    public static final List<Integer> PACES = List.of(PACE_SLOW, PACE_NORMAL, PACE_PACKED);

    // ==================== needConfirm 里用的字段名常量 ====================
    // 前端要按这些名字去表单里定位高亮字段，写成字面量迟早会有一处拼错（且不报错）

    public static final String FIELD_DESTINATION = "destination";
    public static final String FIELD_DAYS = "days";
    public static final String FIELD_START_DATE = "startDate";
    public static final String FIELD_BUDGET_TOTAL = "budgetTotal";
    public static final String FIELD_BUDGET_MODE = "budgetMode";
    public static final String FIELD_TRANSPORT = "transport";
    public static final String FIELD_COMPANION = "companion";
    public static final String FIELD_PACE = "pace";

    // ==================== 字段 ====================

    /** 目的地，如「寿阳」。必填 */
    private String destination;

    /** 目的地中心经度（BD-09）。地图不可用时为 null —— 见类注释 */
    private Double destLng;

    /** 目的地中心纬度（BD-09）。地图不可用时为 null */
    private Double destLat;

    /** 行程天数，必填，取值范围 1 ~ {@code trip.max-days}（默认 5） */
    private Integer days;

    /** 起始日期。用户说「周末」这类相对时间时由模型按「今天」推算 */
    private LocalDate startDate;

    /** 预算金额。口径由 {@link #budgetMode} 决定，两者必须一起理解 */
    private BigDecimal budgetTotal;

    /** 预算口径：{@link #BUDGET_MODE_PER_PERSON} 或 {@link #BUDGET_MODE_TOTAL} */
    private String budgetMode;

    /** 交通方式：{@link #TRANSPORT_DRIVE} / {@link #TRANSPORT_PUBLIC} / {@link #TRANSPORT_WALK} / {@link #TRANSPORT_MIX} */
    private String transport;

    /** 同行人，如「爸妈」「一个人」 */
    private String companion;

    /** 偏好标签，如 古建筑 / 自然风光 / 博物馆 / 美食。P3-B 据此映射成百度检索标签 */
    private List<String> preferenceTags;

    /** 节奏：{@link #PACE_SLOW} 慢 / {@link #PACE_NORMAL} 适中 / {@link #PACE_PACKED} 紧凑 */
    private Integer pace;

    /** 本次临时忌口，会与长期画像的忌口合并（见 {@code ProfileOverrides}） */
    private List<String> dietaryOverrides;

    /** 模型主动标注「这个字段我是靠猜的」的字段名列表 */
    private List<String> needConfirm;

    /** 候选不足时的提示（P3-B 写，解析阶段通常为 null） */
    private String shortageHint;

    /** 解析置信度 0~1。低置信度时前端应更积极地请用户确认 */
    private Double confidence;

    // ==================== getter / setter ====================

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public Double getDestLng() { return destLng; }
    public void setDestLng(Double destLng) { this.destLng = destLng; }

    public Double getDestLat() { return destLat; }
    public void setDestLat(Double destLat) { this.destLat = destLat; }

    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public BigDecimal getBudgetTotal() { return budgetTotal; }
    public void setBudgetTotal(BigDecimal budgetTotal) { this.budgetTotal = budgetTotal; }

    public String getBudgetMode() { return budgetMode; }
    public void setBudgetMode(String budgetMode) { this.budgetMode = budgetMode; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getCompanion() { return companion; }
    public void setCompanion(String companion) { this.companion = companion; }

    public List<String> getPreferenceTags() { return preferenceTags; }
    public void setPreferenceTags(List<String> preferenceTags) { this.preferenceTags = preferenceTags; }

    public Integer getPace() { return pace; }
    public void setPace(Integer pace) { this.pace = pace; }

    public List<String> getDietaryOverrides() { return dietaryOverrides; }
    public void setDietaryOverrides(List<String> dietaryOverrides) { this.dietaryOverrides = dietaryOverrides; }

    public List<String> getNeedConfirm() { return needConfirm; }
    public void setNeedConfirm(List<String> needConfirm) { this.needConfirm = needConfirm; }

    public String getShortageHint() { return shortageHint; }
    public void setShortageHint(String shortageHint) { this.shortageHint = shortageHint; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    /** 是否拿到了目的地坐标。P3-C 的空间预排据此决定能不能排序 */
    public boolean hasDestinationLocation() {
        return destLng != null && destLat != null;
    }
}
