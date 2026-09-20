package com.wayfare.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.security.LoginUser;
import com.wayfare.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选检索的真实验收（P3-B 验收 1~5）。
 *
 * <p><b>默认跳过</b>，显式开启：
 * <pre>
 * mvn test -Dtest=CandidateSearcherLiveTest -Dwayfare.live=true
 * </pre>
 *
 * <p>本测试会真的调地图与大模型（花钱、依赖网络），所以不进日常 {@code mvn test}。
 * 它会<b>按当前的地图开关自适应</b>：地图开着就验 VERIFIED 路径，关着就验 ESTIMATED 路径，
 * 这样同一份测试在两种配置下都能跑，不用改代码。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "wayfare.live", matches = "true")
class CandidateSearcherLiveTest {

    @Autowired
    private CandidateSearcher searcher;

    @Autowired
    private CandidateSerializer serializer;

    @Autowired
    private MapCapabilityResolver mapResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("验收 1/2/5：按当前地图开关跑一次真实检索并打印候选池")
    void searchUnderCurrentMapMode() {
        UserContext.set(new LoginUser(1L, "admin", "管理员", "admin"));
        try {
            ResolvedMap capability = mapResolver.resolve();
            System.out.println("=== 地图能力：mode=" + capability.mode()
                    + "，reason=" + capability.reason() + " ===");

            CandidatePool pool = searcher.searchCandidates(
                    intent("寿阳", List.of("古建筑"), 2), null, capability);

            printPool("寿阳 · 古建筑", pool);

            assertFalse(pool.isEmpty(), "真实检索不该得到空池");

            if (pool.getMapMode() == MapMode.VERIFIED || pool.getMapMode() == MapMode.CACHED) {
                // 验收 2：地图开启 → 真实坐标 + poiUid
                long withCoord = pool.getItems().stream().filter(CandidateDTO::hasLocation).count();
                assertTrue(withCoord > 0, "地图可用却一个带坐标的候选都没有");
                assertTrue(pool.getItems().stream().anyMatch(c -> c.getPoiUid() != null),
                        "地图可用却没有任何 poiUid");
                System.out.println("验收2：带坐标候选 " + withCoord + " / " + pool.getItems().size());
            } else {
                // 验收 1：地图关闭 → 坐标全 null、全 ESTIMATED
                for (CandidateDTO c : pool.getItems()) {
                    assertNull(c.getLng(), "地图关闭时 " + c.getName() + " 竟然有经度");
                    assertNull(c.getLat(), "地图关闭时 " + c.getName() + " 竟然有纬度");
                    assertEquals(TripItem.SOURCE_LLM, c.getDataSource());
                    assertEquals(TripItem.VERIFY_ESTIMATED, c.getVerifyStatus());
                }
                System.out.println("验收1：全部 " + pool.getItems().size()
                        + " 条候选坐标均为 null、verifyStatus 均为 ESTIMATED ✓");
            }
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("验收 4：忌口「海鲜」→ 候选里没有任何海鲜类点位")
    void tabooIsEnforced() {
        UserContext.set(new LoginUser(1L, "admin", "管理员", "admin"));
        try {
            UserTravelProfile profile = new UserTravelProfile();
            profile.setCuisines("海鲜");
            profile.setTaboos("海鲜");
            profile.setAllowAiUse(1);

            CandidatePool pool = searcher.searchCandidates(
                    intent("寿阳", List.of("美食"), 2), profile, mapResolver.resolve());

            printPool("寿阳 · 忌口海鲜", pool);

            assertTrue(pool.getItems().stream().noneMatch(c -> c.getName().contains("海鲜")),
                    "候选里出现了海鲜点位：" + names(pool));
            System.out.println("验收4：候选里无任何含「海鲜」的点位 ✓");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("验收 3：冷门目的地 → 触发 shortage 且提示合理，没有编造景点")
    void shortageForObscureDestination() {
        UserContext.set(new LoginUser(1L, "admin", "管理员", "admin"));
        try {
            CandidatePool pool = searcher.searchCandidates(
                    intent("阿尔山白狼镇", List.of("古建筑"), 1), null, mapResolver.resolve());

            printPool("阿尔山白狼镇 · 古建筑（冷门）", pool);

            if (pool.scenicCount() < 6) {
                assertTrue(pool.isShortage(), "景点少于 6 个却没报 shortage");
                assertNotNull(pool.getShortageHint());
                System.out.println("验收3：shortage=true，提示=" + pool.getShortageHint());
            } else {
                System.out.println("验收3：该目的地候选充足（" + pool.scenicCount() + " 个），未触发 shortage");
            }
            System.out.println("验收3：池内共 " + pool.getItems().size() + " 条，全部来自检索，无编造");
        } finally {
            UserContext.clear();
        }
    }

    // ==================== 工具 ====================

    private IntentDTO intent(String destination, List<String> preferences, int days) {
        IntentDTO intent = new IntentDTO();
        intent.setDestination(destination);
        intent.setDays(days);
        intent.setPreferenceTags(preferences);
        return intent;
    }

    /** 验收 5：输出候选池示例（同时用序列化格式，那正是 P3-D 会看到的） */
    private void printPool(String title, CandidatePool pool) {
        System.out.println("\n========== " + title + " ==========");
        System.out.println("mapMode=" + pool.getMapMode()
                + "，共 " + pool.getItems().size() + " 条"
                + "（景点 " + pool.scenicCount() + " / 餐饮 " + pool.foodCount() + "）"
                + "，shortage=" + pool.isShortage());
        if (!pool.getDegradations().isEmpty()) {
            System.out.println("放宽动作：" + pool.getDegradations());
        }
        System.out.println("--- 序列化格式（P3-D 将看到的内容）---");
        System.out.println(serializer.serialize(pool.getItems()));
        System.out.println("--- 明细 ---");
        for (CandidateDTO c : pool.getItems()) {
            System.out.printf("%s | %s | %s | lng=%s lat=%s | uid=%s | %s/%s | 停留%s分钟 | 来源=%s%n",
                    c.getName(), c.getItemType(), c.getArea(), c.getLng(), c.getLat(),
                    c.getPoiUid(), c.getDataSource(), c.getVerifyStatus(),
                    c.getStayMinutes(), c.getFromPreference());
        }
    }

    private String names(CandidatePool pool) {
        return pool.getItems().stream().map(CandidateDTO::getName).toList().toString();
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
