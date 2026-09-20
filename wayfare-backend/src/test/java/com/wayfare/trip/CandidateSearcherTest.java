package com.wayfare.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.llm.ResolvedLlm;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.MapProvider;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 候选检索单元测试（P3-B）。
 *
 * <p>用假的地图 Provider 与假的大模型 Provider，验的是<b>服务端自己的逻辑</b>：
 * 去重、忌口过滤、限量、坐标诚信、候选不足的降级。这些用真接口验不稳定
 * （百度不会每次给你一个重名的点），所以必须用可编排的替身钉住行为。
 */
class CandidateSearcherTest {

    private MapCapabilityResolver mapResolver;
    private LlmCapabilityResolver llmResolver;
    private AiLogService aiLogService;
    private SysConfigService sysConfigService;
    private CandidateSearcher searcher;

    @BeforeEach
    void setUp() {
        mapResolver = mock(MapCapabilityResolver.class);
        llmResolver = mock(LlmCapabilityResolver.class);
        aiLogService = mock(AiLogService.class);
        sysConfigService = mock(SysConfigService.class);
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(20);
        // 限速间隔在测试里置 0 —— 否则每个用例都要真睡 400ms，白白拖慢测试
        when(sysConfigService.getInt(eq("map.min-interval-ms"), anyInt())).thenReturn(0);

        // 用真 ObjectMapper：字典加载与 JSON 解析都是被测行为的一部分
        searcher = new CandidateSearcher(mapResolver, llmResolver, aiLogService,
                sysConfigService, new ObjectMapper());
    }

    // ==================== 地图路径 ====================

    @Test
    @DisplayName("地图可用：走百度检索，候选带真实坐标、poiUid，标 BAIDU/VERIFIED")
    void collectsFromMapWhenAvailable() {
        FakeMapProvider map = stubMap(List.of(
                poi("寿阳文庙", "uid-1", 113.17, 37.89),
                poi("龙栖湖", "uid-2", 113.05, 37.95)));

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, verifiedMap());

        assertEquals(2, pool.getItems().size());
        assertTrue(map.queries.size() > 0, "应当真的发出了地图检索");
        for (CandidateDTO c : pool.getItems()) {
            assertNotNull(c.getLng(), "地图可用时坐标不该为空");
            assertNotNull(c.getPoiUid());
            assertEquals(TripItem.SOURCE_BAIDU, c.getDataSource());
            assertEquals(TripItem.VERIFY_VERIFIED, c.getVerifyStatus());
        }
        assertEquals(MapMode.VERIFIED, pool.getMapMode());
    }

    @Test
    @DisplayName("字典命中：偏好「古建筑」翻译成字典里的检索词，而不是只把口语原词丢出去")
    void usesDictionaryForKnownPreference() {
        FakeMapProvider map = stubMap(List.of(poi("寿阳文庙", "uid-1", 113.17, 37.89)));

        searcher.searchCandidates(intent("寿阳", List.of("古建筑")), null, verifiedMap());

        List<String> keywords = map.queries.stream().map(PoiQueryDTO::getKeyword).toList();
        assertTrue(keywords.contains("寺庙"),
                "字典没被用上（「古建筑」应翻译成「寺庙」而不是只用原词），实际：" + keywords);
    }

    @Test
    @DisplayName("护栏：每个偏好的检索词不超过 3 个 —— 地点检索只有 100 次/天，加词就是烧额度")
    void dictionaryKeepsKeywordCountBounded() throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(
                new org.springframework.core.io.ClassPathResource("map-preference-tag.json").getInputStream());

        root.path("preferences").fields().forEachRemaining(entry -> {
            int count = entry.getValue().path("keywords").size();
            assertTrue(count >= 1, "偏好「" + entry.getKey() + "」一个检索词都没有");
            assertTrue(count <= 3,
                    "偏好「" + entry.getKey() + "」有 " + count + " 个检索词；超过 3 个会明显烧额度，"
                            + "而且早停会让排在后面的词根本用不上");
        });
    }

    @Test
    @DisplayName("永不发送 tag 参数 —— 百度 tag 填错不报错、只会静默返回垃圾结果（实测踩过）")
    void neverSendsTagParameter() {
        FakeMapProvider map = stubMap(List.of(poi("寿阳文庙", "uid-1", 113.17, 37.89)));

        searcher.searchCandidates(intent("寿阳", List.of("古建筑", "自然风光", "博物馆")),
                null, verifiedMap());

        assertTrue(map.queries.stream().allMatch(q -> q.getTag() == null),
                "有查询带了 tag（填错会让结果静默变成垃圾城市）：" + describe(map.queries));
        assertTrue(map.queries.stream().allMatch(q -> q.getKeyword() != null),
                "百度要求 query 必填，有查询没带 keyword（会 status=2 Parameter Invalid）："
                        + describe(map.queries));
    }

    @Test
    @DisplayName("字典未命中：退化为直接用偏好词当关键词，不丢这一路候选")
    void fallsBackToRawPreferenceWhenNotInDictionary() {
        FakeMapProvider map = stubMap(List.of(poi("某某点", "uid-1", 113.1, 37.8)));

        searcher.searchCandidates(intent("寿阳", List.of("小众秘境")), null, verifiedMap());

        boolean usedRaw = map.queries.stream().anyMatch(q -> "小众秘境".equals(q.getKeyword()));
        assertTrue(usedRaw, "字典没收录时应退化为直接用偏好词检索，实际：" + describe(map.queries));
    }

    @Test
    @DisplayName("按 poiUid 去重")
    void deduplicatesByPoiUid() {
        stubMap(List.of(
                poi("寿阳文庙", "uid-1", 113.17, 37.89),
                poi("寿阳文庙（正门）", "uid-1", 113.17, 37.89)));

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, verifiedMap());

        assertEquals(1, pool.getItems().size(), "同一个 uid 出现了两次，应当只留一条");
    }

    @Test
    @DisplayName("按名称相似度二次去重：『寿阳文庙』与『寿阳文庙(正门)』视为同一个点")
    void deduplicatesBySimilarName() {
        stubMap(List.of(
                poi("寿阳文庙", "uid-1", 113.17, 37.89),
                poi("寿阳文庙(正门)", "uid-2", 113.18, 37.90)));

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, verifiedMap());

        assertEquals(1, pool.getItems().size(),
                "只共前缀的不同点不该被误杀，但包含关系的重复点必须去掉");
    }

    @Test
    @DisplayName("限量 trip.max-candidate")
    void respectsMaxCandidateLimit() {
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(3);
        List<PoiDTO> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(poi("景点" + i, "uid-" + i, 113.0 + i * 0.01, 37.8));
        }
        stubMap(many);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, verifiedMap());

        assertTrue(pool.getItems().size() <= 3, "上限 3 却返回了 " + pool.getItems().size() + " 条");
    }

    @Test
    @DisplayName("够用就停：收集到上限的 2 倍就不再继续调百度（免费 AK 配额很紧，实测会被 401 挡住）")
    void stopsEarlyToSaveQuota() {
        // maxCandidate=6 → maxCollect=12；假地图每次返回 7 条不同的点 → 两次查询就有 14 条，应当停手。
        // 上限取 6 是为了让清洗后 scenicCount=6 达到阈值，不触发「候选不足」的放宽检索
        // ——否则多出来的查询是放宽造成的，测不出早停。
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(6);
        List<PoiDTO> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(poi("寿阳古寺" + i, "uid-" + i, 113.0 + i * 0.01, 37.8));
        }
        FakeMapProvider map = stubMap(seven);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑", "自然风光", "博物馆")),
                null, verifiedMap());

        assertFalse(pool.isShortage(), "这个用例的前提是候选充足、不该触发放宽检索");
        assertTrue(map.queries.size() <= 3,
                "收集够了还在继续查百度，实际查询次数 " + map.queries.size());
    }

    // ==================== 忌口过滤（验收 4）====================

    @Test
    @DisplayName("验收4：忌口「海鲜」→ 候选里没有海鲜类餐饮点")
    void filtersFoodHittingTaboo() {
        stubMap(List.of(
                poi("老张海鲜大排档", "uid-1", 113.1, 37.8),
                poi("寿阳面馆", "uid-2", 113.11, 37.81)));

        UserTravelProfile profile = new UserTravelProfile();
        profile.setCuisines("海鲜");
        profile.setTaboos("海鲜");

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("美食")),
                profile, verifiedMap());

        assertTrue(pool.getItems().stream().noneMatch(c -> c.getName().contains("海鲜")),
                "含忌口的餐饮点没被剔除：" + names(pool));
    }

    @Test
    @DisplayName("忌口口语前缀要归一化：「不吃辣」应剔掉含「辣」的店，而不是去匹配字面「不吃辣」")
    void normalizesTabooPhrase() {
        stubMap(List.of(
                poi("重庆麻辣火锅", "uid-1", 113.1, 37.8),
                poi("清汤面馆", "uid-2", 113.11, 37.81)));

        IntentDTO intent = intent("寿阳", List.of("美食"));
        intent.setDietaryOverrides(List.of("不吃辣"));

        CandidatePool pool = searcher.searchCandidates(intent, null, verifiedMap());

        assertTrue(pool.getItems().stream().noneMatch(c -> c.getName().contains("辣")),
                "「不吃辣」没被归一化成「辣」，漏过了麻辣火锅：" + names(pool));
    }

    // ==================== 地图关闭路径（验收 1）====================

    @Test
    @DisplayName("验收1：地图关闭 → 走 LLM 生成，坐标全 null，标 LLM/ESTIMATED")
    void generatesByLlmWhenMapDisabled() {
        stubLlm("""
                {"candidates":[
                  {"name":"寿阳文庙","area":"寿阳县城内","stayMinutes":90,"highlight":"金代木构"},
                  {"name":"龙栖湖","area":"寿阳县西部","stayMinutes":120,"highlight":"湖光山色"},
                  {"name":"寿阳老街","area":"老城区","stayMinutes":60,"highlight":"市井烟火"}
                ]}
                """);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        assertEquals(3, pool.getItems().size());
        for (CandidateDTO c : pool.getItems()) {
            assertNull(c.getLng(), "地图关闭时坐标必须为 null —— 这是铁律一的落点");
            assertNull(c.getLat());
            assertNull(c.getAddress(), "模型不许编门牌号，address 必须为空");
            assertEquals(TripItem.SOURCE_LLM, c.getDataSource());
            assertEquals(TripItem.VERIFY_ESTIMATED, c.getVerifyStatus());
            assertNotNull(c.getStayMinutes());
        }
        assertEquals(MapMode.ESTIMATED, pool.getMapMode());
    }

    @Test
    @DisplayName("地图关闭时模型擅自给了坐标 → 直接丢弃，不进候选池")
    void discardsLlmProvidedCoordinates() {
        stubLlm("""
                {"candidates":[{"name":"寿阳文庙","area":"城内","stayMinutes":90,
                 "highlight":"x","lat":37.89,"lng":113.17,"address":"某某街1号"}]}
                """);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        CandidateDTO c = pool.getItems().get(0);
        assertNull(c.getLat(), "模型给的坐标绝不能被采信");
        assertNull(c.getLng());
        assertNull(c.getAddress());
    }

    @Test
    @DisplayName("地图关闭 + 大模型也不可用 → 返回空池并如实报 shortage，不抛异常")
    void degradesGracefullyWhenBothUnavailable() {
        when(llmResolver.execute(any())).thenThrow(
                new com.wayfare.connector.llm.LlmException(
                        com.wayfare.common.result.ResultCode.LLM_NOT_AVAILABLE, "system", "全都不可用"));

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        assertTrue(pool.isEmpty());
        assertTrue(pool.isShortage());
        assertNotNull(pool.getShortageHint(), "空池必须说明原因，不能只给个空列表");
    }

    @Test
    @DisplayName("地图关闭：模型给的 type=FOOD 要真被标成餐饮，否则 P3-D 排不出「每天至少 1 个 FOOD」")
    void honorsLlmProvidedType() {
        stubLlm("""
                {"candidates":[
                  {"name":"寿阳文庙","type":"SCENIC","area":"城内","stayMinutes":90,"highlight":"x"},
                  {"name":"寿阳老面馆","type":"FOOD","area":"老城区","stayMinutes":60,"highlight":"y"}
                ]}
                """);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        assertEquals(2, pool.getItems().size());
        assertEquals(1, pool.foodCount(), "餐饮候选没被识别出来，foodCount 会是 0");
        assertEquals(1, pool.scenicCount());
    }

    @Test
    @DisplayName("地图关闭：占位式命名直接丢弃 —— 阿拉伯数字与中文数字两种写法都要挡")
    void dropsPlaceholderNames() {
        stubLlm("""
                {"candidates":[
                  {"name":"寿阳文庙","type":"SCENIC","area":"城内","stayMinutes":90,"highlight":"x"},
                  {"name":"寿阳特色餐馆1","type":"FOOD","area":"城内","stayMinutes":60,"highlight":"y"},
                  {"name":"某某景点","type":"SCENIC","area":"城内","stayMinutes":60,"highlight":"z"},
                  {"name":"寿阳特色餐馆一","type":"FOOD","area":"城内","stayMinutes":60,"highlight":"w"},
                  {"name":"寿阳特色餐馆二","type":"FOOD","area":"城内","stayMinutes":60,"highlight":"v"}
                ]}
                """);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        assertEquals(1, pool.getItems().size(),
                "占位式命名没被挡干净（模型会用中文数字绕过阿拉伯数字规则）：" + names(pool));
        assertEquals("寿阳文庙", pool.getItems().get(0).getName());
    }

    @Test
    @DisplayName("真实地名不会被占位符规则误杀（「8号院」「三巷」要留下）")
    void keepsRealNamesEndingWithDigits() {
        stubLlm("""
                {"candidates":[
                  {"name":"王家8号院","type":"SCENIC","area":"城内","stayMinutes":60,"highlight":"x"},
                  {"name":"三巷老街","type":"SCENIC","area":"城内","stayMinutes":60,"highlight":"y"}
                ]}
                """);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, estimatedMap());

        assertEquals(2, pool.getItems().size(), "真实地名被误杀了：" + names(pool));
    }

    @Test
    @DisplayName("只要餐饮偏好时不触发放宽检索 —— 景点数天然为 0，放宽纯属白烧额度")
    void skipsWideningWhenOnlyFoodPreferred() {
        FakeMapProvider map = stubMap(List.of(poi("寿阳面馆", "uid-1", 113.1, 37.8)));
        UserTravelProfile profile = new UserTravelProfile();
        profile.setCuisines("面食");

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("美食")),
                profile, verifiedMap());

        assertTrue(pool.getDegradations().isEmpty(),
                "只要餐饮偏好却做了放宽检索（实测一次白烧 5 次百度调用）：" + pool.getDegradations());
        assertTrue(map.queries.size() <= 2,
                "调用次数过多，白烧额度。实际 " + map.queries.size());
    }

    // ==================== 候选不足降级（验收 3）====================

    @Test
    @DisplayName("验收3：景点候选 < 6 → 放宽检索 → 仍不足则 shortage=true 且提示合理，绝不编造景点")
    void marksShortageWhenScenicCandidatesInsufficient() {
        stubMap(List.of(poi("唯一的小庙", "uid-1", 113.1, 37.8)));

        CandidatePool pool = searcher.searchCandidates(intent("某个小镇", List.of("古建筑")),
                null, verifiedMap());

        assertTrue(pool.isShortage(), "只有 1 个景点却没报 shortage");
        assertNotNull(pool.getShortageHint());
        assertTrue(pool.getShortageHint().contains("景点较少"), "提示文案不合理：" + pool.getShortageHint());
        assertFalse(pool.getDegradations().isEmpty(), "应当记录做过哪些放宽动作");
        // 关键：放宽之后池子里仍然只有真实检索到的点，没有任何凭空多出来的景点
        assertTrue(pool.getItems().size() <= 3,
                "放宽不该凭空变出景点，实际 " + pool.getItems().size() + " 条：" + names(pool));
    }

    @Test
    @DisplayName("候选充足时不报 shortage")
    void noShortageWhenEnoughCandidates() {
        List<PoiDTO> enough = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            enough.add(poi("景点" + i, "uid-" + i, 113.0 + i * 0.01, 37.8));
        }
        stubMap(enough);

        CandidatePool pool = searcher.searchCandidates(intent("寿阳", List.of("古建筑")),
                null, verifiedMap());

        assertFalse(pool.isShortage());
        assertNull(pool.getShortageHint());
    }

    @Test
    @DisplayName("目的地为空 → 返回空池并说明原因，不抛异常、不硬编一个目的地")
    void returnsEmptyPoolWhenDestinationMissing() {
        CandidatePool pool = searcher.searchCandidates(intent(null, List.of("古建筑")),
                null, estimatedMap());

        assertTrue(pool.isEmpty());
        assertTrue(pool.isShortage());
        assertNotNull(pool.getShortageHint());
    }

    // ==================== 序列化 =====================

    @Test
    @DisplayName("序列化：带编号、坐标未知时明确写「坐标未知」而不是省略")
    void serializesWithExplicitUnknownLocation() {
        CandidateSerializer serializer = new CandidateSerializer();
        CandidateDTO withCoord = new CandidateDTO();
        withCoord.setName("寿阳文庙");
        withCoord.setItemType(TripItem.TYPE_SCENIC);
        withCoord.setArea("寿阳县城内");
        withCoord.setLng(113.17);
        withCoord.setLat(37.89);
        withCoord.setStayMinutes(90);
        withCoord.setHighlight("金代木构");

        CandidateDTO noCoord = new CandidateDTO();
        noCoord.setName("龙栖湖");
        noCoord.setItemType(TripItem.TYPE_SCENIC);
        noCoord.setStayMinutes(120);

        String text = serializer.serialize(List.of(withCoord, noCoord));

        assertTrue(text.startsWith("[1] 寿阳文庙"), "实际：" + text);
        assertTrue(text.contains("113.17,37.89"));
        assertTrue(text.contains("[2] 龙栖湖"));
        assertTrue(text.contains("坐标未知"), "没有坐标时必须明说未知，不能让模型以为它离得近");
        assertTrue(text.contains("区域未知"));
    }

    // ==================== 日志 =====================

    @Test
    @DisplayName("写 CANDIDATE 阶段日志，provider 与地图模式对得上")
    void recordsCandidateStage() {
        stubMap(List.of(poi("寿阳文庙", "uid-1", 113.17, 37.89)));

        searcher.searchCandidates(intent("寿阳", List.of("古建筑")), null, verifiedMap());

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        org.mockito.Mockito.verify(aiLogService).recordStage(captor.capture());
        AiStageRecord record = captor.getValue();

        assertEquals(AiStageRecord.STAGE_CANDIDATE, record.stage());
        assertEquals("baidu", record.provider());
        assertTrue(record.success());
        assertNull(record.errorCode());
    }

    @Test
    @DisplayName("空池时日志记 CANDIDATE_SHORTAGE，而不是伪装成成功")
    void recordsShortageAsFailure() {
        stubMap(List.of());

        searcher.searchCandidates(intent("某个小镇", List.of("古建筑")), null, verifiedMap());

        ArgumentCaptor<AiStageRecord> captor = ArgumentCaptor.forClass(AiStageRecord.class);
        org.mockito.Mockito.verify(aiLogService).recordStage(captor.capture());
        AiStageRecord record = captor.getValue();

        assertFalse(record.success());
        assertEquals(AiErrorCode.CANDIDATE_SHORTAGE, record.errorCode());
    }

    // ==================== 替身与工具 =====================

    private IntentDTO intent(String destination, List<String> preferences) {
        IntentDTO intent = new IntentDTO();
        intent.setDestination(destination);
        intent.setDays(2);
        intent.setPreferenceTags(preferences);
        return intent;
    }

    private PoiDTO poi(String name, String uid, Double lng, Double lat) {
        return new PoiDTO(uid, name, "山西省晋中市" + name + "附近", lng, lat,
                "风景名胜", null, null, null, null);
    }

    private ResolvedMap verifiedMap() {
        return new ResolvedMap(MapMode.VERIFIED, null, null);
    }

    private ResolvedMap estimatedMap() {
        return new ResolvedMap(MapMode.ESTIMATED, null, "地图连接器已关闭");
    }

    private String names(CandidatePool pool) {
        return pool.getItems().stream().map(CandidateDTO::getName).toList().toString();
    }

    private String describe(List<PoiQueryDTO> queries) {
        return queries.stream()
                .map(q -> "tag=" + q.getTag() + ",keyword=" + q.getKeyword())
                .toList().toString();
    }

    /** 假的地图 Provider：记录收到的查询，返回预设结果 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private FakeMapProvider stubMap(List<PoiDTO> result) {
        FakeMapProvider provider = new FakeMapProvider(result);
        when(mapResolver.call(any(), any())).thenAnswer(invocation -> {
            Function<MapProvider, Object> action = invocation.getArgument(0);
            Object value = action.apply(provider);
            return new MapCapabilityResolver.MapCallResult(value, MapMode.VERIFIED, "baidu", null);
        });
        return provider;
    }

    private void stubLlm(String reply) {
        FakeLlmProvider provider = new FakeLlmProvider(reply);
        when(llmResolver.execute(any())).thenAnswer(invocation -> {
            Function<LlmProvider, String> action = invocation.getArgument(0);
            String value = action.apply(provider);
            return new LlmCapabilityResolver.LlmCallResult<>(value, ResolvedLlm.primary(provider), List.of());
        });
    }

    private static final class FakeMapProvider implements MapProvider {
        private final List<PoiDTO> result;
        final List<PoiQueryDTO> queries = new ArrayList<>();

        FakeMapProvider(List<PoiDTO> result) {
            this.result = result;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String name() {
            return "fake-map";
        }

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public List<PoiDTO> searchPoi(PoiQueryDTO query) {
            queries.add(query);
            return result;
        }

        @Override
        public PoiDTO detail(String poiUid) {
            return null;
        }

        @Override
        public com.wayfare.connector.map.RouteDTO route(com.wayfare.connector.map.RouteQueryDTO query) {
            return null;
        }
    }

    private static final class FakeLlmProvider implements LlmProvider {
        private final String reply;

        FakeLlmProvider(String reply) {
            this.reply = reply;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public LlmInfo info() {
            return LlmInfo.available("fake", "fake-model");
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, LlmCallContext context) {
            if (context != null) context.reportUsage(new LlmUsage(100, 20, 120));
            return reply;
        }

        @Override
        public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
            if (context != null) context.reportUsage(new LlmUsage(100, 20, 120));
            return reply;
        }

        @Override
        public void chatStream(String systemPrompt, String userPrompt,
                               Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
            throw new UnsupportedOperationException("本测试不需要流式");
        }
    }
}
