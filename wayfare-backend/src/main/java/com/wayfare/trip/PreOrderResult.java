package com.wayfare.trip;

import com.wayfare.dto.CandidateDTO;

import java.util.List;

/**
 * 空间预排的结果（P3-C）。
 *
 * <p>把「排好序的候选点」和「这次排得怎么样」一起交出去 ——
 * 后面几步和 P7 的指标都要读它：P3-D 按这个顺序分天，P7 要拿 {@link #savedPercent()}
 * 证明「2-opt 真的比贪心好」，而不是嘴上说说。
 *
 * @param orderedList         排好序的候选点。<b>包含全部输入点</b>（有坐标的排在前面并按空间顺序优化，
 *                            无坐标的按原相对顺序追加在尾部），不遗漏、不重复
 * @param totalDistanceMeters 最终总里程（米）。<b>只算「相邻点之间」的里程之和，不含起点到第一点的第一段</b> ——
 *                            起点是目的地中心这种「虚拟锚点」，不是行程里的一个点；
 *                            这样单点用例的总里程自然为 0（手册验收 3 就是这么要求的）
 * @param greedyDistanceMeters 贪心最近邻的里程（米），用于和 2-opt 结果对比
 * @param savedPercent        2-opt 相对贪心节省的百分比（保留 2 位小数）。贪心为 0 时为 0
 * @param skippedNoCoord      <b>算法是否被整体跳过</b> —— 一个坐标都没有时为 true（手册的用法）
 * @param noCoordCount        无坐标点的个数。与 {@link #skippedNoCoord()} 不同：
 *                            部分点无坐标时它是 true 之外的情况，靠这个字段看细节
 */
public record PreOrderResult(
        List<CandidateDTO> orderedList,
        double totalDistanceMeters,
        double greedyDistanceMeters,
        double savedPercent,
        boolean skippedNoCoord,
        int noCoordCount) {

    /** 是否真的做了空间优化（有坐标的点 ≥ 2 个才谈得上「排序」） */
    public boolean optimized() {
        return !skippedNoCoord;
    }

    /** 里程的公里表示，日志与展示用 */
    public double totalDistanceKm() {
        return totalDistanceMeters / 1000.0;
    }
}
