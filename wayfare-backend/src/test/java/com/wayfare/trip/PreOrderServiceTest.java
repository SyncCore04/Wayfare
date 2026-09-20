package com.wayfare.trip;

import com.wayfare.dto.CandidateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间预排单元测试（P3-C 验收 1~4）。
 *
 * <p>本类<b>不需要 Spring 容器</b>：{@link PreOrderService} 没有任何依赖注入，
 * 直接 {@code new} 出来就能测 —— 这正是手册要求「纯函数式」的目的。
 */
class PreOrderServiceTest {

    private PreOrderService service;

    @BeforeEach
    void setUp() {
        service = new PreOrderService();
    }

    // ==================== 验收 1：正方形 + 中心点 ====================

    @Test
    @DisplayName("验收1：8 个点（正方形 + 边中点）→ 无遗漏无重复，且 2-opt 后里程 ≤ 贪心")
    void ordersSquareGridWithoutLosingPoints() {
        // 以寿阳附近为中心构造一个正方形 + 四条边中点，共 8 个点
        List<CandidateDTO> input = List.of(
                cand("西北角", 113.12, 37.94),
                cand("东北角", 113.22, 37.94),
                cand("东南角", 113.22, 37.84),
                cand("西南角", 113.12, 37.84),
                cand("北中点", 113.17, 37.94),
                cand("东中点", 113.22, 37.89),
                cand("南中点", 113.17, 37.84),
                cand("西中点", 113.12, 37.89));

        PreOrderResult result = service.preOrder(input, 113.17, 37.89);

        // 无遗漏、无重复
        assertEquals(8, result.orderedList().size(), "输出点数与输入不一致");
        Set<String> inputNames = input.stream().map(CandidateDTO::getName).collect(Collectors.toSet());
        Set<String> outputNames = result.orderedList().stream()
                .map(CandidateDTO::getName).collect(Collectors.toSet());
        assertEquals(inputNames, outputNames, "输出与输入的点集不一致（有遗漏或多了点）");
        assertEquals(8, outputNames.size(), "输出里有重复点");

        // 核心正确性断言：2-opt 不可能让里程变长
        assertTrue(result.totalDistanceMeters() <= result.greedyDistanceMeters() + 1e-6,
                "2-opt 后里程反而更长：" + result.totalDistanceMeters() + " > " + result.greedyDistanceMeters());

        assertFalse(result.skippedNoCoord(), "有坐标却标记了跳过算法");
        assertEquals(0, result.noCoordCount());
        assertTrue(result.savedPercent() >= 0, "节省百分比不该是负数");

        // 验收 4：贴出真实的「优化前 → 优化后」对比
        System.out.printf("验收1/4：优化前 %.0f m → 优化后 %.0f m，节省 %.2f%%（共 %d 个点）%n",
                result.greedyDistanceMeters(), result.totalDistanceMeters(),
                result.savedPercent(), result.orderedList().size());
        System.out.println("排序结果：" + result.orderedList().stream()
                .map(CandidateDTO::getName).collect(Collectors.joining(" → ")));
    }

    @Test
    @DisplayName("验收1/4：8 个真实寿阳 POI 坐标 → 2-opt 相对贪心的里程对比（真实数据比对称网格更能暴露差异）")
    void ordersRealPoiCoordinates() {
        // 坐标取自 P3-B 真实检索结果（百度返回的 8 个寿阳寺庙/景点），不是编的
        List<CandidateDTO> input = List.of(
                cand("冷泉寺", 113.073119, 37.755288),
                cand("福田寺", 112.870834, 37.946223),
                cand("普光寺", 112.962146, 37.780220),
                cand("方山寺", 113.273225, 38.023587),
                cand("玄天庙", 113.169787, 38.005311),
                cand("石佛寺", 113.109209, 37.585669),
                cand("北下庄关帝庙", 113.268407, 37.893600),
                cand("文昌庙", 113.143771, 37.990718));

        PreOrderResult result = service.preOrder(input, 113.17, 37.89);

        assertEquals(8, result.orderedList().size());
        assertTrue(result.totalDistanceMeters() <= result.greedyDistanceMeters() + 1e-6,
                "2-opt 后里程反而更长");

        System.out.printf("验收4（真实坐标）：优化前 %.0f m → 优化后 %.0f m，节省 %.2f%%%n",
                result.greedyDistanceMeters(), result.totalDistanceMeters(), result.savedPercent());
        System.out.println("  排序：" + result.orderedList().stream()
                .map(CandidateDTO::getName).collect(Collectors.joining(" → ")));
    }

    @Test
    @DisplayName("20 个不规则点 → 2-opt 相对贪心的实际优化幅度（点数上去、布局不规则时贪心才不再最优）")
    void improvesOnIrregularLayout() {
        // 用固定种子的线性同余生成器造 20 个不规则点：确定性、可复现，不需要引随机库
        List<CandidateDTO> input = new ArrayList<>();
        long seed = 20260920L;
        for (int i = 0; i < 20; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double lng = 113.00 + ((seed >>> 20) % 5000) / 10000.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double lat = 37.70 + ((seed >>> 20) % 5000) / 10000.0;
            input.add(cand("P" + i, lng, lat));
        }

        PreOrderResult result = service.preOrder(input, 113.20, 37.90);

        assertEquals(20, result.orderedList().size());
        assertTrue(result.totalDistanceMeters() <= result.greedyDistanceMeters() + 1e-6,
                "2-opt 后里程反而更长");
        System.out.printf("20 点不规则布局：优化前 %.0f m → 优化后 %.0f m，节省 %.2f%%%n",
                result.greedyDistanceMeters(), result.totalDistanceMeters(), result.savedPercent());
    }

    // ==================== 验收 2：全部无坐标 ====================

    @Test
    @DisplayName("验收2：全部无坐标 → 保持输入顺序，skippedNoCoord=true，里程 0")
    void keepsInputOrderWhenNoCoordinates() {
        List<CandidateDTO> input = List.of(
                noCoord("寿阳文庙"), noCoord("龙栖湖"), noCoord("寿阳老街"));

        PreOrderResult result = service.preOrder(input, 113.17, 37.89);

        assertEquals(3, result.orderedList().size());
        assertArrayEquals(new String[]{"寿阳文庙", "龙栖湖", "寿阳老街"},
                result.orderedList().stream().map(CandidateDTO::getName).toArray(String[]::new),
                "无坐标时应当原样保持输入顺序（顺序交由 P3-D 让大模型按常识排）");
        assertTrue(result.skippedNoCoord(), "全无坐标却没标记 skippedNoCoord");
        assertEquals(3, result.noCoordCount());
        assertEquals(0, result.totalDistanceMeters(), "无坐标时里程应当为 0");
        assertEquals(0, result.greedyDistanceMeters());
        assertEquals(0, result.savedPercent());
    }

    @Test
    @DisplayName("部分无坐标 → 有坐标的排在前并优化，无坐标的按原相对顺序追加在尾部")
    void appendsNoCoordCandidatesAtTail() {
        List<CandidateDTO> input = List.of(
                cand("有坐标A", 113.12, 37.84),
                noCoord("无坐标1"),
                cand("有坐标B", 113.22, 37.94),
                noCoord("无坐标2"));

        PreOrderResult result = service.preOrder(input, 113.17, 37.89);

        assertEquals(4, result.orderedList().size());
        List<String> names = result.orderedList().stream().map(CandidateDTO::getName).toList();
        assertEquals(Set.of("有坐标A", "有坐标B"), Set.copyOf(names.subList(0, 2)),
                "有坐标的点应当排在前两个");
        assertEquals(List.of("无坐标1", "无坐标2"), names.subList(2, 4),
                "无坐标的点应当保持原相对顺序排在尾部");
        assertFalse(result.skippedNoCoord(), "有坐标时不该标记「算法被跳过」");
        assertEquals(2, result.noCoordCount(), "noCoordCount 要如实反映有多少个点没坐标");
    }

    // ==================== 验收 3：单点 ====================

    @Test
    @DisplayName("验收3：单点 → 不报错，总里程 0")
    void handlesSinglePoint() {
        PreOrderResult result = service.preOrder(List.of(cand("独苗", 113.17, 37.89)), 113.17, 37.89);

        assertEquals(1, result.orderedList().size());
        assertEquals(0, result.totalDistanceMeters(), "单点总里程应当是 0");
        assertEquals(0, result.greedyDistanceMeters());
        assertFalse(result.skippedNoCoord());
    }

    @Test
    @DisplayName("空输入 → 返回空结果，不抛异常")
    void handlesEmptyInput() {
        PreOrderResult result = service.preOrder(List.of(), null, null);

        assertNotNull(result);
        assertTrue(result.orderedList().isEmpty());
        assertEquals(0, result.totalDistanceMeters());
    }

    @Test
    @DisplayName("输入为 null → 不抛异常")
    void handlesNullInput() {
        PreOrderResult result = service.preOrder(null, null, null);
        assertNotNull(result);
        assertTrue(result.orderedList().isEmpty());
    }

    // ==================== 2-opt 本身 ====================

    @Test
    @DisplayName("2-opt 会把明显绕远的顺序改短（直接喂坏顺序，保证覆盖到「改进」这条分支）")
    void twoOptShortensBadOrder() {
        // 四个点排在一条直线上（经度 0/1/2/3），坏顺序 0→2→1→3 是来回跳的
        double[] lngs = {0, 1, 2, 3};
        double[] lats = {0, 0, 0, 0};
        int[] order = {0, 2, 1, 3};

        double before = length(order, lngs, lats);
        double after = service.twoOpt(order, lngs, lats, before);

        assertTrue(after < before, "对明显绕远的顺序竟然没有改进：" + before + " → " + after);
        assertArrayEquals(new int[]{0, 1, 2, 3}, order, "应当被修正成单调顺序");
        System.out.printf("2-opt 专项：绕远顺序 %.1f m → %.1f m，节省 %.2f%%%n",
                before, after, (before - after) / before * 100);
    }

    @Test
    @DisplayName("2-opt 绝不接受让里程变长的改动（已经最优的顺序保持原样）")
    void twoOptNeverWorsens() {
        double[] lngs = {0, 1, 2, 3};
        double[] lats = {0, 0, 0, 0};
        int[] order = {0, 1, 2, 3};

        double before = length(order, lngs, lats);
        double after = service.twoOpt(order, lngs, lats, before);

        assertEquals(before, after, 1e-6, "已经最优的顺序被改差了");
        assertArrayEquals(new int[]{0, 1, 2, 3}, order);
    }

    // ==================== 距离公式 ====================

    @Test
    @DisplayName("Haversine：1 度纬度 ≈ 111.19 km，同点距离为 0")
    void haversineSanityCheck() {
        double oneDegreeLat = PreOrderService.haversine(0, 0, 1, 0);
        assertEquals(111194.9, oneDegreeLat, 200,
                "1 度纬度的球面距离应当在 111.19 km 附近，实际 " + oneDegreeLat);

        assertEquals(0, PreOrderService.haversine(37.89, 113.17, 37.89, 113.17), 1e-6,
                "同一个点的距离必须是 0");

        // 对称性：A→B 与 B→A 必须相等
        double ab = PreOrderService.haversine(37.84, 113.12, 37.94, 113.22);
        double ba = PreOrderService.haversine(37.94, 113.22, 37.84, 113.12);
        assertEquals(ab, ba, 1e-6);
    }

    // ==================== 稳健性 ====================

    @Test
    @DisplayName("起点坐标会真的影响第一个点：离起点最近的那个应当排在最前")
    void startPointDrivesFirstStop() {
        List<CandidateDTO> input = List.of(
                cand("远处", 113.30, 37.95),
                cand("近处", 113.18, 37.90),
                cand("中间", 113.24, 37.92));

        PreOrderResult result = service.preOrder(input, 113.17, 37.89);

        assertEquals("近处", result.orderedList().get(0).getName(),
                "起点最近的「近处」应当排第一，实际：" + result.orderedList().get(0).getName());
    }

    @Test
    @DisplayName("不给起点时用第一个有坐标的点当起点，且该点仍会被包含在结果里")
    void fallsBackToFirstCoordAsStart() {
        List<CandidateDTO> input = List.of(
                cand("甲", 113.12, 37.84),
                cand("乙", 113.22, 37.94),
                cand("丙", 113.17, 37.89));

        PreOrderResult result = service.preOrder(input, null, null);

        assertEquals(3, result.orderedList().size());
        assertEquals("甲", result.orderedList().get(0).getName(),
                "没有起点时应当以第一个有坐标的点为起点（它同时是第一个停靠点）");
    }

    @Test
    @DisplayName("点数较多时不卡死（迭代上限生效）")
    void terminatesOnLargerInput() {
        List<CandidateDTO> input = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            input.add(cand("点" + i, 113.0 + (i % 8) * 0.03, 37.8 + (i / 8) * 0.03));
        }

        long t0 = System.currentTimeMillis();
        PreOrderResult result = service.preOrder(input, 113.17, 37.89);
        long cost = System.currentTimeMillis() - t0;

        assertEquals(40, result.orderedList().size());
        assertTrue(cost < 3000, "40 个点竟然跑了 " + cost + " ms，迭代上限可能没生效");
        assertTrue(result.totalDistanceMeters() <= result.greedyDistanceMeters() + 1e-6);
    }

    // ==================== 工具 ====================

    private CandidateDTO cand(String name, Double lng, Double lat) {
        CandidateDTO dto = new CandidateDTO();
        dto.setName(name);
        dto.setLng(lng);
        dto.setLat(lat);
        return dto;
    }

    private CandidateDTO noCoord(String name) {
        CandidateDTO dto = new CandidateDTO();
        dto.setName(name);
        return dto;
    }

    /** 测试里自己算路径长度，避免依赖被测类的私有实现 */
    private double length(int[] order, double[] lngs, double[] lats) {
        double total = 0;
        for (int i = 1; i < order.length; i++) {
            total += PreOrderService.haversine(lats[order[i - 1]], lngs[order[i - 1]],
                    lats[order[i]], lngs[order[i]]);
        }
        return total;
    }
}
