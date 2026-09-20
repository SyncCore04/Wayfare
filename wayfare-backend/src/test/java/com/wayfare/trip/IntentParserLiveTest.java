package com.wayfare.trip;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.AiGenerationLog;
import com.wayfare.mapper.AiGenerationLogMapper;
import com.wayfare.security.LoginUser;
import com.wayfare.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图解析的真实模型验收（P3-A 验收 1/2/4/5）。
 *
 * <p><b>为什么默认跳过</b>：它会真的调用大模型（花钱、依赖网络、输出不完全可预测），
 * 不适合放进每次 {@code mvn test}。显式开启：
 * <pre>
 * mvn test -Dtest=IntentParserLiveTest -Dwayfare.live=true
 * </pre>
 *
 * <p>它验的是 {@link IntentParserTest} 验不了的东西：<b>提示词是否真的能让模型
 * 把「预算没说口径」「走不动」这类模糊表述解析成正确的结构化字段</b>。
 * 这一层只能靠真实模型，mock 是验不出来的。
 *
 * <p>本测试会往 {@code ai_generation_log} 写 PARSE 记录（这是被测代码的正常行为，
 * 也是验收 5 要看的证据）。跑完可以按 id 清理，别让验收数据污染 P4/P6 的成本统计。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "wayfare.live", matches = "true")
class IntentParserLiveTest {

    /** 手册给定的三条验收输入 */
    private static final String[] INPUTS = {
            "周末想去寿阳玩两天，喜欢古建筑，预算 500",
            "三天，想去大同看古建和博物馆，不吃辣，一个人，预算 1500",
            "两天，带爸妈，走不动，想轻松点，想吃面食"
    };

    @Autowired
    private IntentParser parser;

    @Autowired
    private MapCapabilityResolver mapResolver;

    @Autowired
    private AiGenerationLogMapper aiGenerationLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("验收 1/2/4/5：三条输入的真实解析结果")
    void threeAcceptanceInputs() throws Exception {
        UserContext.set(new LoginUser(1L, "admin", "管理员", "admin"));
        try {
            ResolvedMap capability = mapResolver.resolve();
            System.out.println("=== 地图能力：mode=" + capability.mode()
                    + "，reason=" + capability.reason() + " ===");

            IntentDTO[] results = new IntentDTO[INPUTS.length];
            for (int i = 0; i < INPUTS.length; i++) {
                System.out.println("\n========== 输入" + (i + 1) + "：" + INPUTS[i] + " ==========");
                long t0 = System.currentTimeMillis();
                results[i] = parser.parseIntent(INPUTS[i], null, null, capability);
                long cost = System.currentTimeMillis() - t0;
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results[i]));
                System.out.println("--- 本阶段耗时 " + cost + " ms ---");
            }

            // 验收 1：三条都必须跑通，天数都要解析出来
            for (int i = 0; i < results.length; i++) {
                assertNotNull(results[i].getDays(), "输入" + (i + 1) + " 没解析出天数");
            }
            // 输入1、输入2 用户明确说了目的地 → 必须解析出来
            assertNotNull(results[0].getDestination(), "输入1 说了「寿阳」，却没解析出目的地");
            assertNotNull(results[1].getDestination(), "输入2 说了「大同」，却没解析出目的地");
            // 输入3 用户压根没说去哪 → 允许为空，但必须让用户补填（不能凭空编一个地名）
            if (results[2].getDestination() == null) {
                assertNotNull(results[2].getNeedConfirm(),
                        "输入3 没目的地又没进 needConfirm，用户无从知道要补填");
                assertTrue(results[2].getNeedConfirm().contains(IntentDTO.FIELD_DESTINATION),
                        "输入3 的 needConfirm 应包含 destination，实际为 " + results[2].getNeedConfirm());
            }

            // 验收 2：输入1 说了预算但没说口径 → needConfirm 必须包含 budgetMode
            List<String> needConfirm1 = results[0].getNeedConfirm();
            assertNotNull(needConfirm1, "输入1 的 needConfirm 为空 —— 预算口径不明却没让用户确认");
            assertTrue(needConfirm1.contains(IntentDTO.FIELD_BUDGET_MODE),
                    "输入1 的 needConfirm 缺少 budgetMode，实际为 " + needConfirm1);

            // 验收 4：输入3「走不动」→ pace=1；「带爸妈」→ companion 含长辈
            assertNotNull(results[2].getPace(), "输入3 没解析出节奏");
            assertTrue(results[2].getPace() == IntentDTO.PACE_SLOW,
                    "输入3「走不动」应解析为 pace=1（慢），实际为 " + results[2].getPace());
            String companion3 = results[2].getCompanion();
            assertNotNull(companion3, "输入3 没解析出同行人");
            assertTrue(companion3.contains("爸") || companion3.contains("妈")
                            || companion3.contains("父母") || companion3.contains("长辈"),
                    "输入3 的同行人应体现长辈，实际为 " + companion3);

            // 验收 5：ai_generation_log 里有 PARSE 阶段的真实 token 与耗时
            System.out.println("\n========== ai_generation_log 里的 PARSE 记录 ==========");
            List<AiGenerationLog> logs = aiGenerationLogMapper.selectList(
                    new QueryWrapper<AiGenerationLog>()
                            .eq("stage", AiStageRecord.STAGE_PARSE)
                            .orderByDesc("id")
                            .last("limit 6"));
            for (AiGenerationLog log : logs) {
                System.out.printf("id=%d stage=%s provider=%s model=%s in=%s out=%s total=%s duration=%sms success=%s errorCode=%s%n",
                        log.getId(), log.getStage(), log.getProvider(), log.getModel(),
                        log.getPromptTokens(), log.getCompletionTokens(), log.getTotalTokens(),
                        log.getDurationMs(), log.getSuccess(), log.getErrorCode());
            }
            assertTrue(logs.stream().anyMatch(l -> l.getSuccess() != null && l.getSuccess() == 1),
                    "ai_generation_log 里没有成功的 PARSE 记录");
            assertTrue(logs.stream().anyMatch(l -> l.getDurationMs() != null && l.getDurationMs() > 0),
                    "PARSE 记录里没有耗时");
            assertTrue(logs.stream().anyMatch(l -> l.getTotalTokens() != null && l.getTotalTokens() > 0),
                    "PARSE 记录里没有 token 数 —— 成本统计会缺数据");
        } finally {
            UserContext.clear();
        }
    }
}
