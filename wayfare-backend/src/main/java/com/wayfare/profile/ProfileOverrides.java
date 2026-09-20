package com.wayfare.profile;

import java.util.List;

/**
 * 本次规划的临时条件（P2-A）。
 *
 * <p>画像里的偏好是<b>长期</b>的（「我平时喜欢慢节奏」），而一次具体的行程往往有
 * <b>临时</b>的额外条件（「这次带了我妈，得走慢点」「这次同行的朋友花生过敏」）。
 * 两者不能混为一谈 —— 把临时条件写回画像会污染长期偏好，
 * 所以用这个类单独承载，只在渲染那一刻与画像合并。
 *
 * <p><b>合并语义（P3 依赖，不要随意改）</b>：
 * <ul>
 *   <li>{@code taboos} 非空时与画像的忌口 <b>合并</b>（并集去重），不是覆盖 ——
 *       忌口是硬约束，多一条只会更安全，不存在「这次可以不忌口」这种诉求；</li>
 *   <li>{@code pace} 非空时 <b>覆盖</b> 画像的节奏 —— 节奏是取舍，
 *       这次的「带长辈要慢」就该盖过平时的「紧凑」。</li>
 * </ul>
 *
 * <p>只放这两个字段是刻意的：手册只定义了这两条的合并语义。
 * 要扩展（比如临时预算、临时同行人）必须先想清楚是合并还是覆盖，
 * 想不清楚就不要加 —— 语义含糊的 overrides 比没有 overrides 更危险。
 */
public class ProfileOverrides {

    /** 本次额外忌口（与画像忌口取并集） */
    private List<String> taboos;

    /** 本次节奏 1慢 2适中 3紧凑（覆盖画像节奏） */
    private Integer pace;

    public ProfileOverrides() {
    }

    public ProfileOverrides(List<String> taboos, Integer pace) {
        this.taboos = taboos;
        this.pace = pace;
    }

    public List<String> getTaboos() { return taboos; }
    public void setTaboos(List<String> taboos) { this.taboos = taboos; }
    public Integer getPace() { return pace; }
    public void setPace(Integer pace) { this.pace = pace; }
}
