package com.wayfare.trip;

import com.wayfare.connector.map.MapMode;
import com.wayfare.dto.CandidateDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选池（P3-B 的输出契约）。
 *
 * <p>把「一堆候选点」和「这批候选是怎么来的、可不可信、够不够用」打包在一起。
 * 后面三步都要读它：P3-C 拿它排序，P3-D 从它里面选点（只能选里面的），
 * P3-E 拿它做 CLOSURE 闭包校验。所以它必须自带可信度信息，而不是让下游去猜。
 *
 * <p><b>{@link #shortage} 不是错误，是如实上报</b>：一个冷门小镇搜不到 6 个景点是正常事实，
 * 系统的正确反应是「告诉用户点位少，并把范围放宽」，而不是「编几个景点凑数」。
 * 这与 P3-A 里「目的地缺失就进 needConfirm、而不是逼模型编一个地名」是同一条原则。
 */
public class CandidatePool {

    /** 候选点，已去重、已限量。可能是空的 —— 空池要由 {@link #shortageHint} 解释原因 */
    private List<CandidateDTO> items = new ArrayList<>();

    /** 这批候选是在什么地图能力下拿到的（VERIFIED / CACHED / ESTIMATED） */
    private MapMode mapMode;

    /** 景点候选是否不足（< trip 规定的最小值）。true 表示"能用但偏少"，不是失败 */
    private boolean shortage;

    /** 不足时的说明文案，直接给用户看，如「寿阳县可检索到的古建点位较少，已放宽到周边区县」 */
    private String shortageHint;

    /** 为了凑够候选做过哪些放宽动作（按序记录），是「系统如何降级」的可观测证据 */
    private List<String> degradations = new ArrayList<>();

    public CandidatePool() {
    }

    public CandidatePool(List<CandidateDTO> items, MapMode mapMode) {
        this.items = items == null ? new ArrayList<>() : items;
        this.mapMode = mapMode;
    }

    public List<CandidateDTO> getItems() { return items; }
    public void setItems(List<CandidateDTO> items) { this.items = items; }

    public MapMode getMapMode() { return mapMode; }
    public void setMapMode(MapMode mapMode) { this.mapMode = mapMode; }

    public boolean isShortage() { return shortage; }
    public void setShortage(boolean shortage) { this.shortage = shortage; }

    public String getShortageHint() { return shortageHint; }
    public void setShortageHint(String shortageHint) { this.shortageHint = shortageHint; }

    public List<String> getDegradations() { return degradations; }
    public void setDegradations(List<String> degradations) { this.degradations = degradations; }

    /** 记一次放宽动作，供上游展示与排查 */
    public void addDegradation(String step) {
        if (degradations == null) {
            degradations = new ArrayList<>();
        }
        degradations.add(step);
    }

    /** 景点类候选数量（不含餐饮）。「候选是否不足」按它判断 */
    public long scenicCount() {
        return items.stream().filter(c -> !c.isFood()).count();
    }

    /** 餐饮类候选数量 */
    public long foodCount() {
        return items.stream().filter(CandidateDTO::isFood).count();
    }

    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
}
