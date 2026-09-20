package com.wayfare.trip;

import com.wayfare.dto.TripDraftDTO;

/**
 * 行程编排的结果（P3-D）。
 *
 * <p><b>为什么失败时不抛异常、而是返回一个「失败的结果对象」</b>：
 * 手册明确要求「仍失败返回 LLM_PARSE_FAIL，但**保留候选池**供前端手选（不要整体失败）」。
 * 编排失败不等于整次规划失败 —— 候选池已经检索到了、空间顺序也排好了，
 * 用户完全可以自己从池子里挑点。抛异常会把这份「还能用」的中间产物一起丢掉，
 * 那就把<b>能力降级</b>做成了<b>功能降级</b>（违反铁律二）。
 *
 * <p>所以失败时 {@link #draft()} 为 null，但调用方（P3-F）手里仍然有候选池。
 *
 * @param draft        编排结果；失败时为 null
 * @param success      是否成功
 * @param errorCode    失败归因码（成功时为 null），写进 {@code ai_generation_log}
 * @param errorMessage 失败详情（已脱敏），给用户看的提示由此派生
 */
public record ComposeResult(TripDraftDTO draft, boolean success,
                            AiErrorCode errorCode, String errorMessage) {

    public static ComposeResult ok(TripDraftDTO draft) {
        return new ComposeResult(draft, true, null, null);
    }

    public static ComposeResult fail(AiErrorCode errorCode, String errorMessage) {
        return new ComposeResult(null, false, errorCode, errorMessage);
    }

    /** 有没有拿到可用的行程草稿 */
    public boolean hasDraft() {
        return draft != null;
    }
}
