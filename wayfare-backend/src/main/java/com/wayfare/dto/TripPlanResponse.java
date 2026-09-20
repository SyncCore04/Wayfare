package com.wayfare.dto;

import com.wayfare.trip.Violation;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 同步规划接口 {@code POST /api/trip/plan/sync} 的响应（P3-F Step7 的结果组装）。
 *
 * <p>字段刻意对齐联调手册的返回契约：
 * <pre>
 * { tripId, intent, shortage, shortageHint, validation, trip: TripDraftDTO,
 *   meta: { rounds, mapMode, modelName, profileUsed, durationMs, tokens, estCost } }
 * </pre>
 *
 * <p>两处「软失败」的表达：
 * <ul>
 *   <li>{@code trip} 为 null —— 编排阶段就失败（候选池空 / 天数未确认 / LLM 不可用），
 *       {@code composeError} 说明原因，前端可把候选点交给用户手选（能力降级不是功能降级）；</li>
 *   <li>{@code validation} 非空 —— 到达重排轮次上限仍未完全通过约束校验，
 *       {@link Violation} 列表是「以下问题未能自动解决」的清单，前端展示风险提示。</li>
 * </ul>
 */
public class TripPlanResponse implements Serializable {

    /** 落库后的行程主键；编排失败时仍非 null（草稿已落库，用户可续作） */
    private Long tripId;

    /** 意图解析结果（含 needConfirm，前端据此高亮待确认表单项） */
    private IntentDTO intent;

    /** 候选点是否不足 */
    private Boolean shortage;

    /** 候选不足时给用户看的说明 */
    private String shortageHint;

    /** 未解决的约束违规；完全通过时为 null */
    private List<Violation> validation;

    /** 编排完成的行程草稿（每项都带 verifyStatus / dataSource）；失败时为 null */
    private TripDraftDTO trip;

    /** 编排阶段失败的原因（如「#候选池为空#」）；成功时为 null */
    private String composeError;

    /** 本次生成的元信息：rounds / mapMode / modelName / profileUsed / durationMs / tokens / estCost */
    private Map<String, Object> meta;

    public TripPlanResponse() {
    }

    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public IntentDTO getIntent() { return intent; }
    public void setIntent(IntentDTO intent) { this.intent = intent; }
    public Boolean getShortage() { return shortage; }
    public void setShortage(Boolean shortage) { this.shortage = shortage; }
    public String getShortageHint() { return shortageHint; }
    public void setShortageHint(String shortageHint) { this.shortageHint = shortageHint; }
    public List<Violation> getValidation() { return validation; }
    public void setValidation(List<Violation> validation) { this.validation = validation; }
    public TripDraftDTO getTrip() { return trip; }
    public void setTrip(TripDraftDTO trip) { this.trip = trip; }
    public String getComposeError() { return composeError; }
    public void setComposeError(String composeError) { this.composeError = composeError; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }
}