package com.wayfare.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.governance.CircuitBreaker;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.security.JwtUtil;
import com.wayfare.security.LoginUser;
import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理接口的集成测试（P7-A）—— 权限边界、密钥不外泄、配置热生效、熔断状态可视、参数校验一致性。
 *
 * <p><b>为什么这些要放在集成测试而不是单测</b>：它们验的是「整条 HTTP 链路 + 拦截器 + 掩码工具 + 配置缓存」
 * 合起来的效果。单测只能证明各自正确，证明不了「接起来之后没有把 Key 漏出去」——
 * 而漏 Key 这件事，错一次就够了。
 *
 * <p><b>为什么真连 Redis / MySQL</b>：权限要过 {@code JwtInterceptor}（Token 黑名单在 Redis）、
 * 熔断状态存在 Redis、配置读的是 {@code sys_config} 表。用 mock 搭出来的「绿」没有意义。
 *
 * <p>{@code @Transactional}：用例里会改 sys_config。DB 写随事务回滚，
 * 但<b>它清掉的 Redis 缓存与熔断键不会跟着回滚</b>，所以 {@link #cleanUp()} 里还要显式还原一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminApiSecurityIntegrationTest {

    /**
     * 「完整密钥」的形态：DashScope 风格的 sk- 长串、32 位十六进制、百度 AK 的长串。
     * 只按<b>形态</b>扫，不按具体值扫 —— 否则测试文件里就得写真实 Key，那本身就是泄露。
     */
    private static final Pattern COMPLETE_KEY_IN_RESPONSE =
            Pattern.compile("sk-[A-Za-z0-9]{16,}|(?<![A-Za-z0-9])[0-9a-f]{32}(?![A-Za-z0-9])|ak=[A-Za-z0-9]{20,}");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CircuitBreaker circuitBreaker;
    @Autowired private LlmCapabilityResolver llmResolver;
    @Autowired private SysConfigService sysConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String adminToken() {
        return jwtUtil.generateToken(new LoginUser(1L, "admin", "平台管理员", "admin"));
    }

    private String normalUserToken() {
        return jwtUtil.generateToken(new LoginUser(2L, "normal", "普通用户", "user"));
    }

    @AfterEach
    void cleanUp() {
        circuitBreaker.reset("baidu");
        sysConfigService.clearCache();
    }

    // ==================== 权限边界 ====================

    @Test
    @DisplayName("诊断接口无 token → 401（诊断会真实消耗配额与 token，不能匿名触发）")
    void diagnosticsRequiresLogin() throws Exception {
        mockMvc.perform(get("/diagnostics/connectors")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/diagnostics/llm/providers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("诊断与管理接口：普通用户 → body.code=403（登录了也不等于能看运维信息）")
    void adminApisRejectNormalUser() throws Exception {
        assertBusinessCode("/diagnostics/connectors", normalUserToken(), 403);
        assertBusinessCode("/admin/configs", normalUserToken(), 403);
        assertBusinessCode("/admin/generation/logs", normalUserToken(), 403);
    }

    @Test
    @DisplayName("接口契约：业务异常走「HTTP 200 + body.code」，而拦截器的 401 才是真的 HTTP 状态码")
    void businessErrorsUseBodyCodeNotHttpStatus() throws Exception {
        // 这条不是在挑刺，而是把项目<b>实际</b>的契约写下来：
        // 前端 axios 拦截器判的是 res.code（不是 res.status），
        // 所以 Controller 抛的 BusinessException 一律 HTTP 200 + code。
        // 不知道这条的人写测试会先撞一次「expected 403 but was 200」（我自己就撞了）。
        MvcResult forbidden = mockMvc.perform(get("/admin/configs")
                        .header("Authorization", "Bearer " + normalUserToken()))
                .andReturn();
        assertEquals(200, forbidden.getResponse().getStatus(), "业务异常不应改 HTTP 状态码");
        assertEquals(403, objectMapper.readTree(forbidden.getResponse().getContentAsString())
                .path("code").asInt(), "权限失败的信息在 body.code 里");

        // 对照：鉴权失败由拦截器直接返回 401，不带统一响应体
        mockMvc.perform(get("/admin/configs")).andExpect(status().isUnauthorized());
    }

    /** 断言「统一的业务错误」：HTTP 200 + body.code 等于期望值 */
    private void assertBusinessCode(String path, String token, int expectedCode) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(expectedCode, body.path("code").asInt(),
                path + " 期望 body.code=" + expectedCode + "，实际响应 = " + body);
    }

    @Test
    @DisplayName("伪造 token → 401（即使路径公开也 401，让前端清 token 自愈）")
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/diagnostics/connectors").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 密钥安全（P7-A 第三部分） ====================

    @Test
    @DisplayName("验收4：诊断与配置接口的响应里不含任何完整密钥")
    void noCompleteKeyInAnyAdminResponse() throws Exception {
        // 这几个接口是「最可能把 Key 吐出来」的地方：诊断要报配置、配置接口本来就在读 Key
        String[] paths = {
                "/diagnostics/connectors",
                "/diagnostics/llm/providers",
                "/admin/configs",
                "/admin/generation/external-calls",
                "/admin/generation/stats",
                "/admin/generation/logs"
        };

        for (String path : paths) {
            MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();

            Matcher matcher = COMPLETE_KEY_IN_RESPONSE.matcher(body);
            String leaked = matcher.find() ? matcher.group() : null;
            assertFalse(leaked != null,
                    path + " 的响应里出现了疑似完整密钥「" + leaked + "」—— 回显一律只允许掩码");
        }
    }

    @Test
    @DisplayName("验收4：百度 AK 的返回值必然是掩码形态（空串也允许，那是「未配置」）")
    void mapAkIsAlwaysMasked() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/configs").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode akRow = null;
        for (JsonNode group : root.path("data")) {
            for (JsonNode row : group) {
                if ("map.baidu.ak".equals(row.path("configKey").asText())) {
                    akRow = row;
                }
            }
        }
        assertNotNull(akRow, "配置里应当存在 map.baidu.ak 这一项");

        String value = akRow.path("configValue").asText();
        assertTrue(akRow.path("masked").asBoolean(), "map.baidu.ak 必须被标记为敏感项");
        assertTrue(value.isEmpty() || (value.length() <= 8 && value.endsWith("****")),
                "AK 的回显只能是空串或「前 4 位 + ****」，实际 = " + value);
    }

    @Test
    @DisplayName("外呼日志表里的历史数据同样不含完整密钥（脱敏是在写入时做的，不是展示时）")
    void externalCallLogRowsContainNoCompleteKey() {
        // 直查表而不是看接口：接口可能「顺手过滤」，而证据链的价值在于库里存的就是脱敏后的内容。
        // 表为空时这条断言平凡成立 —— 所以它只做「有则必不泄露」的方向性保证，不假装在验别的东西。
        List<String> rows = jdbcTemplate.queryForList("SELECT request_summary FROM external_call_log", String.class);
        for (String summary : rows) {
            Matcher matcher = COMPLETE_KEY_IN_RESPONSE.matcher(summary == null ? "" : summary);
            assertFalse(matcher.find(), "external_call_log.request_summary 里出现了疑似完整密钥：" + summary);
        }
    }

    // ==================== 配置热生效（手册场景 8 的基础） ====================

    @Test
    @DisplayName("场景8：改 active-provider 后立即生效，不需要重启（下一次调用就用新厂商）")
    void activeProviderTakesEffectImmediately() {
        String original = llmResolver.resolve().providerName();
        // 用 mock 做断言值：它永远可用，所以断言不会因为「这台机器没配 Key」而误报
        sysConfigService.set("llm.active-provider", "mock", 1L);
        assertEquals("mock", llmResolver.resolve().providerName(),
                "改完 sys_config 后决策器应当立刻给出新厂商（还是旧值 = Redis 缓存没清）");

        sysConfigService.set("llm.active-provider", original, 1L);
        assertEquals(original, llmResolver.resolve().providerName(), "改回去同样要立即生效");
    }

    // ==================== 熔断状态可视（手册场景 3 的接口侧） ====================

    @Test
    @DisplayName("场景3：地图连续失败达阈值后，诊断接口的 map.breakerOpen 变为 true")
    void breakerStateIsVisibleThroughDiagnostics() throws Exception {
        assertFalse(fetchConnectors().path("data").path("map").path("breakerOpen").asBoolean(),
                "初始不应是打开状态");

        // 地图阈值默认 5（map.breaker.fail-threshold）
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure("baidu", "map");
        }

        assertTrue(fetchConnectors().path("data").path("map").path("breakerOpen").asBoolean(),
                "连续失败达阈值后诊断接口应报 breakerOpen=true —— 这就是「熔断能被观测到」");
    }

    private JsonNode fetchConnectors() throws Exception {
        MvcResult result = mockMvc.perform(get("/diagnostics/connectors")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ==================== 参数校验一致性（P6-B 的两个接口） ====================

    @Test
    @DisplayName("生成明细：from 晚于 to → body.code=400（与 /stats 口径一致，不返回「看着正常的空列表」）")
    void logsRejectsReversedRange() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/generation/logs")
                        .param("from", "2026-09-22").param("to", "2026-09-01")
                        .header("Authorization", "Bearer " + adminToken()))
                .andReturn();
        assertEquals(400, objectMapper.readTree(result.getResponse().getContentAsString())
                .path("code").asInt(), "开始日期晚于结束日期应当被拒绝，而不是返回空列表");
    }

    @Test
    @DisplayName("生成明细：size 超上限被收敛到 100，不把库拖垮")
    void logsClampsPageSize() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/generation/logs")
                        .param("size", "9999")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertEquals(100, data.path("size").asInt());
    }

    @Test
    @DisplayName("趋势：days 超上限收敛到 90，且每一天都有行（缺日期会把两天的点连成直线）")
    void trendClampsDaysAndFillsEveryDay() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/generation/trend").param("days", "500")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode days = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("days");
        assertEquals(90, days.size(), "窗口应被收敛到 90 天");
        days.forEach(day -> assertTrue(day.has("statDate"), "每一行都必须带 statDate"));
    }
}
