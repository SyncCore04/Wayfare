package com.wayfare.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 同步规划接口 {@code POST /api/trip/plan/sync} 的入参（P3-F）。
 *
 * <p>三块各自独立：
 * <ul>
 *   <li>{@code rawInput} —— 必须，用户这句话是整条管线的起点（P3-A 意图解析）；</li>
 *   <li>{@code useProfile} —— 是否注入长期画像。关闭后走铁律 3 的反例：
 *       规划照常跑通但不个性化（联调手册要求：关闭后点位数变多、{@code profileUsed=false}）；</li>
 *   <li>{@code overrides} —— 本次临时条件（复用 {@link com.wayfare.profile.ProfileOverrides} 的语义：
 *       taboos 与画像忌口取<b>并集</b>、pace <b>覆盖</b>画像节奏）。</li>
 * </ul>
 */
public class TripPlanRequest implements Serializable {

    /** 用户原始需求，如「周末想去寿阳玩两天，喜欢古建筑，预算 500」 */
    private String rawInput;

    /** 是否使用用户长期画像，默认 true */
    private Boolean useProfile = true;

    /** 本次临时条件，可为 null */
    private Overrides overrides;

    public TripPlanRequest() {
    }

    public String getRawInput() { return rawInput; }
    public void setRawInput(String rawInput) { this.rawInput = rawInput; }
    public Boolean getUseProfile() { return useProfile; }
    public void setUseProfile(Boolean useProfile) { this.useProfile = useProfile; }
    public Overrides getOverrides() { return overrides; }
    public void setOverrides(Overrides overrides) { this.overrides = overrides; }

    /** 本次临时条件。见 {@link com.wayfare.profile.ProfileOverrides} 的合并语义 */
    public static class Overrides implements Serializable {

        /** 本次额外忌口（与画像忌口取并集） */
        private List<String> taboos;

        /** 本次节奏 1慢 2适中 3紧凑（覆盖画像节奏） */
        private Integer pace;

        public Overrides() {
        }

        public List<String> getTaboos() { return taboos; }
        public void setTaboos(List<String> taboos) { this.taboos = taboos; }
        public Integer getPace() { return pace; }
        public void setPace(Integer pace) { this.pace = pace; }
    }
}