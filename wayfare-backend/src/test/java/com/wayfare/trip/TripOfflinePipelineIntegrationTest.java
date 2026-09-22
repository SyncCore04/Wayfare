package com.wayfare.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.security.JwtUtil;
import com.wayfare.security.LoginUser;
import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线全链路集成测试（P7-A 场景 2）—— 地图关闭时，管线必须完整跑通且<strong>不编造事实</strong>。
 *
 * <p><b>怎么做到不联网、不用 Key</b>：把 {@code llm.active-provider} 切成内置的 {@code mock}、
 * {@code map.enabled} 关掉。走的是项目自己设计的离线兜底路径 ——
 * 也就是说这条测试同时验证了 README 里那句「没有任何大模型 Key 也能跑通全链路」。
 *
 * <p><b>它验的不是一个接口能不能返回 200</b>，而是三件具体的事：
 * <ol>
 *   <li>铁律二：地图关闭是<b>能力降级</b>不是功能降级 —— 流程照常出结果；</li>
 *   <li>铁律一：<b>事实数据永不来自大模型</b> —— 估算模式下距离/时长必须是 null，
 *       绝不能在数值列里填一个模型编出来的数字（列里没数字，前端就渲染不出假精度）；</li>
 *   <li>降级要<b>诚实</b>：文案里不能出现带数字的精确里程/时长，只能用模糊表述。</li>
 * </ol>
 *
 * <p>{@code @Transactional}：管线会往 trip 三表 + ai_generation_log 写数据，随事务回滚，
 * 不给库里留垃圾（那些日志是「证据链」，掺进测试数据就失去证据资格）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TripOfflinePipelineIntegrationTest {

    /** 带数字的精确距离/时长 —— 估算模式下出现任何一条都算违反铁律一 */
    private static final Pattern PRECISE_FACT_IN_TEXT =
            Pattern.compile("\\d+(\\.\\d+)?\\s*(公里|千米|km|KM|米|分钟|小时)");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SysConfigService sysConfigService;

    private String userToken() {
        return jwtUtil.generateToken(new LoginUser(1L, "admin", "平台管理员", "admin"));
    }

    @AfterEach
    void restoreConfig() {
        // 事务能回滚 DB 里的 sys_config，但回滚不了它清掉的 Redis 缓存 —— 显式清一次最稳
        sysConfigService.clearCache();
    }

    @Test
    @DisplayName("场景2：地图关闭 + 离线 Mock 模型 → 流程跑通，且全部条目为 ESTIMATED、距离时长为空")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void offlinePipelineDegradesHonestly() throws Exception {
        // 准备：关掉地图、把模型换成内置 mock（它永远可用，不依赖任何 Key）
        sysConfigService.set("map.enabled", "false", 1L);
        sysConfigService.set("llm.active-provider", "mock", 1L);

        MvcResult result = mockMvc.perform(post("/trip/plan/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userToken())
                        .content("""
                                {"rawInput":"周末想去泉州玩两天，喜欢古建筑，预算 500","useProfile":false}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);

        // ① 流程本身不能因为「地图关了」而失败
        assertEquals(200, root.path("code").asInt(), "地图关闭不该让规划失败，实际响应 = " + shorten(body));
        JsonNode data = root.path("data");

        // ② 地图模式必须是 ESTIMATED（这是降级被真实执行了的证据）
        assertEquals("ESTIMATED", data.path("meta").path("mapMode").asText(),
                "地图关闭时 mapMode 应为 ESTIMATED，实际 = " + data.path("meta"));
        assertFalse(data.path("meta").path("profileUsed").asBoolean(),
                "useProfile=false 时 profileUsed 应为 false（铁律三的反例路径）");

        // ③ 逐条检查行程条目：标记成对、数值列必须为空
        List<JsonNode> items = collectItems(data);
        assertFalse(items.isEmpty(), "离线模式下也应当产出行程条目，实际响应 = " + shorten(body));

        List<String> violations = new ArrayList<>();
        for (JsonNode item : items) {
            String name = item.path("poiName").asText(item.path("name").asText("(未命名)"));
            if (!"ESTIMATED".equals(item.path("verifyStatus").asText())) {
                violations.add(name + " 的 verifyStatus 不是 ESTIMATED，而是 " + item.path("verifyStatus").asText());
            }
            // ⚠️ 判据是「这个字段是不是一个数字」，不能写成 !hasNonNull(...) ——
            // 项目开了 jackson non_null，值为 null 的字段会**整个消失**，
            // 于是 !hasNonNull 对「字段不存在」也为真，会把正确行为报成违规（我第一版就写错了）。
            if (item.path("distanceMeters").isNumber()) {
                violations.add(name + " 的距离是数字 " + item.path("distanceMeters").asText()
                        + "（估算模式下绝不能填数字）");
            }
            if (item.path("durationSeconds").isNumber()) {
                violations.add(name + " 的时长是数字 " + item.path("durationSeconds").asText()
                        + "（估算模式下绝不能填数字）");
            }
        }
        assertTrue(violations.isEmpty(), "违反「事实数据永不来自大模型」：" + violations);

        // ④ 文案里不能出现带数字的精确里程/时长（模糊表述才诚实）
        List<String> preciseLeaks = new ArrayList<>();
        for (JsonNode item : items) {
            for (String field : new String[] {"reason", "note"}) {
                String text = item.path(field).asText("");
                var m = PRECISE_FACT_IN_TEXT.matcher(text);
                if (m.find()) {
                    preciseLeaks.add(field + " 里出现了精确数字「" + m.group() + "」： " + text);
                }
            }
        }
        assertTrue(preciseLeaks.isEmpty(), "估算模式的文案不该出现精确数字：" + preciseLeaks);
    }

    /**
     * 递归收集响应里所有「像行程条目」的节点。
     *
     * <p>用递归而不是写死路径：响应结构（trip → dayPlans → items）属于会演进的部分，
     * 写死路径会让测试在结构调整时因为「找不到节点」而失败 —— 那是假警报，
     * 而按特征字段识别，测的才是真正的约束。
     */
    private List<JsonNode> collectItems(JsonNode node) {
        List<JsonNode> found = new ArrayList<>();
        walk(node, found);
        return found;
    }

    private void walk(JsonNode node, List<JsonNode> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            if (node.has("verifyStatus") && (node.has("poiName") || node.has("name"))) {
                found.add(node);
            }
            node.fields().forEachRemaining(entry -> walk(entry.getValue(), found));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, found));
        }
    }

    private String shorten(String text) {
        return text == null || text.length() <= 600 ? text : text.substring(0, 600) + "…(截断)";
    }

    @Test
    @DisplayName("离线模式下的响应必须带 meta（mapMode / profileUsed / tokens），前端靠它渲染可信度")
    void offlineResponseCarriesMeta() throws Exception {
        sysConfigService.set("map.enabled", "false", 1L);
        sysConfigService.set("llm.active-provider", "mock", 1L);

        MvcResult result = mockMvc.perform(post("/trip/plan/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userToken())
                        .content("""
                                {"rawInput":"想去泉州玩一天","useProfile":false}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meta = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("meta");
        assertNotNull(meta, "响应必须带 meta");
        assertTrue(meta.has("mapMode"), "meta 必须含 mapMode —— 没有它前端画不出可信度角标");
        assertTrue(meta.has("profileUsed"), "meta 必须含 profileUsed —— 铁律三的开关要有据可查");
    }
}
