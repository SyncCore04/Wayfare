package com.wayfare.service;

import com.wayfare.entity.AiGenerationLog;
import com.wayfare.mapper.AiGenerationLogMapper;
import com.wayfare.trip.AiErrorCode;
import com.wayfare.trip.AiStageRecord;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 生成日志服务集成测试（P2-C 验收 2）。
 *
 * <p>验收要求「手工插入几条日志，调用聚合方法能算出正确的 token 合计与成本」，
 * 所以这里真连库：插入带<b>已知 token 数</b>的几条日志，再断言聚合结果，
 * 数字能对上才算数。
 *
 * <p><b>单价由测试自己设定</b>（默认配置里两个单价都是 0）：
 * 这样验的是<b>公式</b>而不是某个厂商的报价 —— 报价是会变的，
 * 而且项目纪律是「不编造成本数字」，真实单价要用户按官网填。
 */
@SpringBootTest
@Transactional
class AiLogServiceTest {

    private static final Long TEST_USER_ID = 999_990L;

    @Autowired private AiLogService aiLogService;
    @Autowired private AiGenerationLogMapper aiGenerationLogMapper;
    @Autowired private com.wayfare.mapper.PoiCacheMapper poiCacheMapper;
    @Autowired private SysConfigService sysConfigService;

    /**
     * {@code set()} 的数据库写会被事务回滚，但它清掉的 Redis 缓存不会跟着回滚 ——
     * 所以每个用例结束后主动清一次，避免缓存里残留「只在事务内存在过」的单价。
     * （缓存 TTL 只有 30 秒，但测试不该依赖等待。）
     */
    @AfterEach
    void clearConfigCache() {
        sysConfigService.clearCache();
    }

    private void setGlmPrice(String value) {
        sysConfigService.set("llm.price.glm", value, 0L);
    }

    private void record(Long userId, String stage, Integer prompt, Integer completion, boolean success,
                        AiErrorCode code, String msg) {
        aiLogService.recordStage(new AiStageRecord(userId, null, stage, "glm", "glm-4-flash",
                prompt, completion, 100, success, code, msg));
    }

    // ==================== 验收 2 ====================

    @Test
    @DisplayName("验收2：token 合计与成本计算正确（单价 2 元/百万token）")
    void sumTokensAndCostAreCorrect() {
        setGlmPrice("2");

        // 三条成功（有 usage）+ 一条失败（拿不到 usage，token 为 null）
        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, 100, 50, true, null, null);
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, 1000, 2000, true, null, null);
        record(TEST_USER_ID, AiStageRecord.STAGE_COPY, 300, 700, true, null, null);
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, null, null, false, AiErrorCode.LLM_TIMEOUT, "调用超时");

        Map<String, Object> stat = aiLogService.sumTokensByUserAndDate(TEST_USER_ID, LocalDate.now());

        // 100+1000+300 = 1400
        assertEquals(1400L, stat.get("promptTokens"), "输入 token 合计");
        // 50+2000+700 = 2750
        assertEquals(2750L, stat.get("completionTokens"), "输出 token 合计");
        // 150+3000+1000 = 4150（失败那条为 null，不参与求和）
        assertEquals(4150L, stat.get("totalTokens"), "总 token 合计");
        assertEquals(4L, stat.get("callCount"), "调用条数");
        // 4150 × 2 ÷ 1_000_000 = 0.0083
        assertEquals(0, new BigDecimal("0.008300").compareTo((BigDecimal) stat.get("estCost")),
                "预估成本应为 0.0083 元，实际 " + stat.get("estCost"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byProvider = (List<Map<String, Object>>) stat.get("byProvider");
        assertEquals(1, byProvider.size(), "只有 glm 一家");
        assertEquals("glm", byProvider.get(0).get("provider"));
        assertEquals(4150L, byProvider.get(0).get("totalTokens"));
    }

    @Test
    @DisplayName("验收2补充：单价未配置时 estCost 返回 null，不编造成本")
    void unconfiguredPriceYieldsNullCost() {
        setGlmPrice("0");

        assertNull(aiLogService.estCost("glm", 1000), "单价为 0 应视为未配置，返回 null");
        assertNull(aiLogService.estCost("glm", null), "token 为 null 应返回 null");
        assertNull(aiLogService.estCost(null, 1000), "厂商为 null 应返回 null");
        assertNull(aiLogService.estCost("不存在的厂商", 1000), "未知厂商应返回 null");

        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, 1000, 1000, true, null, null);
        Map<String, Object> stat = aiLogService.sumTokensByUserAndDate(TEST_USER_ID, LocalDate.now());
        assertEquals(2000L, stat.get("totalTokens"), "token 仍要如实统计");
        assertNull(stat.get("estCost"), "单价未配置时成本必须是 null，不能是 0");
    }

    @Test
    @DisplayName("成本公式：小额也要算得准（0.002 元级）")
    void costFormulaForSmallAmounts() {
        setGlmPrice("1"); // 1 元/百万 token

        // 2000 token × 1 元/百万 = 0.002 元
        assertEquals(0, new BigDecimal("0.002000").compareTo(aiLogService.estCost("glm", 2000)));

        // 500 token × 1 元/百万 = 0.0005 元
        assertEquals(0, new BigDecimal("0.000500").compareTo(aiLogService.estCost("glm", 500)));
    }

    @Test
    @DisplayName("各阶段成功率与失败率")
    void successRateByStage() {
        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, 10, 10, true, null, null);
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, 10, 10, true, null, null);
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, 10, 10, false, AiErrorCode.LLM_PARSE_FAIL, "JSON 解析失败");
        record(TEST_USER_ID, AiStageRecord.STAGE_VALIDATE, 10, 10, false, AiErrorCode.VALIDATION_FAILED, "重排超限");

        List<Map<String, Object>> rows = aiLogService.successRateByStage(LocalDate.now(), LocalDate.now());

        Map<String, Object> parse = findStage(rows, AiStageRecord.STAGE_PARSE);
        assertEquals(1L, parse.get("total"));
        assertEquals(0, new BigDecimal("0.0000").compareTo((BigDecimal) parse.get("failRate")));

        Map<String, Object> compose = findStage(rows, AiStageRecord.STAGE_COMPOSE);
        assertEquals(2L, compose.get("total"));
        assertEquals(1L, compose.get("successCount"));
        assertEquals(1L, compose.get("failCount"));
        assertEquals(0, new BigDecimal("0.5000").compareTo((BigDecimal) compose.get("failRate")));

        Map<String, Object> validate = findStage(rows, AiStageRecord.STAGE_VALIDATE);
        assertEquals(0, new BigDecimal("1.0000").compareTo((BigDecimal) validate.get("failRate")));
    }

    @Test
    @DisplayName("错误码 Top N 按次数倒序，并带出触发场景")
    void topErrorsRanking() {
        for (int i = 0; i < 3; i++) {
            record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, null, null, false, AiErrorCode.LLM_TIMEOUT, "超时");
        }
        for (int i = 0; i < 2; i++) {
            record(TEST_USER_ID, AiStageRecord.STAGE_CANDIDATE, null, null, false, AiErrorCode.MAP_BREAKER_OPEN, "熔断");
        }
        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, null, null, false, AiErrorCode.LLM_AUTH_FAIL, "401");
        // 成功记录不该出现在错误榜里
        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, 10, 10, true, null, null);

        List<Map<String, Object>> top = aiLogService.topErrors(LocalDate.now(), LocalDate.now(), 10);

        assertEquals(3, top.size(), "只有三种错误码");
        assertEquals(AiErrorCode.LLM_TIMEOUT.name(), top.get(0).get("errorCode"));
        assertEquals(3L, top.get(0).get("cnt"));
        assertEquals(AiErrorCode.MAP_BREAKER_OPEN.name(), top.get(1).get("errorCode"));
        assertEquals(2L, top.get(1).get("cnt"));
        assertEquals(AiErrorCode.LLM_AUTH_FAIL.name(), top.get(2).get("errorCode"));
        assertEquals(1L, top.get(2).get("cnt"));

        // 触发场景应随行返回，看板不必再查枚举表。
        // 精确比对枚举里的文案（别用 contains 猜关键词 —— 我第一版猜「超时」就猜错了，
        // 文案里写的是「llm.timeout-ms 内未返回」）
        assertEquals(AiErrorCode.LLM_TIMEOUT.getScene(), top.get(0).get("scene"));
    }

    @Test
    @DisplayName("limit 参数生效")
    void topErrorsHonoursLimit() {
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, null, null, false, AiErrorCode.LLM_TIMEOUT, "x");
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, null, null, false, AiErrorCode.LLM_AUTH_FAIL, "x");

        assertEquals(1, aiLogService.topErrors(LocalDate.now(), LocalDate.now(), 1).size());
    }

    // ==================== 写入正确性 ====================

    @Test
    @DisplayName("recordStage 落库正确：totalTokens 由输入+输出算出，成功时 errorCode 为 null")
    void recordStagePersistsCorrectly() {
        record(TEST_USER_ID, AiStageRecord.STAGE_COMPOSE, 1000, 2000, true, null, null);

        AiGenerationLog row = aiGenerationLogMapper.selectOne(new LambdaQueryWrapper<AiGenerationLog>()
                .eq(AiGenerationLog::getUserId, TEST_USER_ID)
                .eq(AiGenerationLog::getStage, AiStageRecord.STAGE_COMPOSE));
        assertNotNull(row, "日志未落库");
        assertEquals(1000, row.getPromptTokens());
        assertEquals(2000, row.getCompletionTokens());
        assertEquals(3000, row.getTotalTokens(), "totalTokens 应等于输入+输出");
        assertEquals(1, row.getSuccess());
        assertNull(row.getErrorCode(), "成功时不该有错误码");
        assertNotNull(row.getCreatedAt(), "created_at 自动填充未生效");
    }

    @Test
    @DisplayName("recordStage 的失败详情写入前已脱敏（AK 不能进库）")
    void recordStageSanitizesErrorMessage() {
        record(TEST_USER_ID, AiStageRecord.STAGE_ROUTE, null, null, false, AiErrorCode.MAP_AUTH_FAIL,
                "https://api.map.baidu.com/place/v2/search?ak=SECRETAK1234567890&query=泉州 认证失败");

        AiGenerationLog row = aiGenerationLogMapper.selectOne(new LambdaQueryWrapper<AiGenerationLog>()
                .eq(AiGenerationLog::getUserId, TEST_USER_ID)
                .eq(AiGenerationLog::getStage, AiStageRecord.STAGE_ROUTE));
        assertNotNull(row);
        assertEquals(AiErrorCode.MAP_AUTH_FAIL.name(), row.getErrorCode());
        String msg = row.getErrorMsg();
        assertNotNull(msg);
        assertTrue(!msg.contains("SECRETAK1234567890"),
                "完整 AK 不得进日志，实际写入 = " + msg);
    }

    @Test
    @DisplayName("当天没有日志时返回空统计，不报错")
    void emptyDayReturnsEmptyStat() {
        Map<String, Object> stat = aiLogService.sumTokensByUserAndDate(999_991L, LocalDate.now());

        assertEquals(0L, stat.get("callCount"));
        assertNull(stat.get("totalTokens"), "没有数据时 token 合计应为 null，不是 0");
        assertNull(stat.get("estCost"));
        assertTrue(((List<?>) stat.get("byProvider")).isEmpty());
    }

    @Test
    @DisplayName("按用户隔离：别人的日志不会算进来")
    void statsAreScopedToUser() {
        record(TEST_USER_ID, AiStageRecord.STAGE_PARSE, 100, 100, true, null, null);
        record(999_992L, AiStageRecord.STAGE_PARSE, 9999, 9999, true, null, null);

        Map<String, Object> stat = aiLogService.sumTokensByUserAndDate(TEST_USER_ID, LocalDate.now());
        assertEquals(200L, stat.get("totalTokens"), "只应统计本用户的日志");
    }

    @Test
    @DisplayName("P2-C 触碰的两张表：实体列映射可用（含 poi_cache 的 4 个新列）")
    void p2cTablesMapCleanly() {
        // selectList 会按实体生成完整列名清单的 SELECT，
        // 「实体有、库里没有」的新列（provider/keyword/fetched_at/expires_at）会立刻抛异常
        assertNotNull(aiGenerationLogMapper.selectList(null));
        assertNotNull(poiCacheMapper.selectList(null));

        // 新列能真实读写（库里 0 行，用事务内插入验证后由回滚清理）
        com.wayfare.entity.PoiCache poi = new com.wayfare.entity.PoiCache();
        poi.setPoiUid("p2c-smoke-uid");
        poi.setName("P2-C 映射验证点");
        poi.setCity("泉州");
        poi.setKeyword("古建筑");
        poi.setProvider("baidu");
        poi.setFetchedAt(java.time.LocalDateTime.now());
        poi.setExpiresAt(java.time.LocalDateTime.now().plusHours(24));
        assertEquals(1, poiCacheMapper.insert(poi));

        com.wayfare.entity.PoiCache loaded = poiCacheMapper.selectById(poi.getId());
        assertNotNull(loaded);
        assertEquals("baidu", loaded.getProvider());
        assertEquals("古建筑", loaded.getKeyword());
        assertNotNull(loaded.getFetchedAt());
        assertNotNull(loaded.getExpiresAt());
        assertNull(loaded.getRating(), "未提供的评分仍应为 null，不被写成 0");
    }

    private Map<String, Object> findStage(List<Map<String, Object>> rows, String stage) {
        return rows.stream()
                .filter(r -> stage.equals(r.get("stage")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到阶段 " + stage + "，实际 = " + rows));
    }
}
