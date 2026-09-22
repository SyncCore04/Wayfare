package com.wayfare.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.security.UserContext;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 管线 Step 2：候选检索（P3-B）。
 *
 * <p><b>这是防幻觉的第一道闸门。</b>整条管线的数据诚信靠两道闸：
 * 本步产出<b>封闭的候选池</b>，P3-D 的编排只准从池里选点，P3-E 再用 CLOSURE 规则回查一遍。
 * 所以「模型编了一个景点」这件事在结构上就不可能通过 —— 它编的名字不在池子里，
 * CLOSURE 直接判 HIGH 违规。这不是靠提示词求模型别编，而是让它编的东西没有落脚点。
 *
 * <p><b>两条完全不同的取数路径，由地图能力决定</b>：
 * <ul>
 *   <li><b>地图可用（VERIFIED/CACHED）</b>：查百度，拿到真实 poiUid 与坐标，
 *       标 {@code dataSource=BAIDU} + {@code verifyStatus=VERIFIED/CACHED}；</li>
 *   <li><b>地图关闭（ESTIMATED）</b>：改由大模型生成「名称 + 区域 + 建议停留 + 亮点」，
 *       <b>坐标强制留 null</b>，标 {@code dataSource=LLM} + {@code verifyStatus=ESTIMATED}。
 *       这是一次<b>能力降级</b>：点位还能推荐，但系统明确告诉你「这些位置我没核实过」。</li>
 * </ul>
 * 注意地图关闭时<b>绝不能抛异常</b> —— 那会把「能力降级」变成「功能降级」，违反铁律二。
 */
@Component
public class CandidateSearcher {

    private static final Logger log = LoggerFactory.getLogger(CandidateSearcher.class);

    /** 字典文件路径（classpath） */
    private static final String DICT_PATH = "map-preference-tag.json";

    private static final String KEY_MAX_CANDIDATE = "trip.max-candidate";
    private static final int DEFAULT_MAX_CANDIDATE = 20;

    /** sys_config 键：两次地图调用之间的最小间隔（毫秒），用于避开百度的 QPS 并发限制 */
    private static final String KEY_MAP_MIN_INTERVAL = "map.min-interval-ms";
    private static final int DEFAULT_MAP_MIN_INTERVAL_MS = 400;

    /** 地图关闭时让模型生成的候选数量区间 —— 太少撑不起行程，太多必然长尾幻觉 */
    private static final int LLM_MIN_CANDIDATES = 10;
    private static final int LLM_MAX_CANDIDATES = 15;

    /** 地图关闭时最多重试 1 次（与 P3-A 同一策略：错误回喂，不无限重试） */
    private static final int MAX_ATTEMPTS = 2;

    private final MapCapabilityResolver mapResolver;
    private final LlmCapabilityResolver llmResolver;
    private final AiLogService aiLogService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    /** 偏好 → 检索关键词映射，启动时从 classpath 读一次 */
    private final Map<String, PreferenceMapping> preferenceDict = new LinkedHashMap<>();
    private final Map<String, List<String>> foodDict = new LinkedHashMap<>();
    private final Map<String, Integer> defaultStayMinutes = new LinkedHashMap<>();

    /**
     * 两次地图调用之间强制间隔。
     *
     * <p><b>为什么需要它</b>：免费 AK 的并发上限是 <b>3 QPS</b>，而本类的检索流程会对
     * 「每个偏好 × 每个检索词」连续发请求（「古建筑」一个偏好就有好几个词），
     * 瞬时很容易打到 5 QPS，百度直接返回
     * {@code status=401 当前并发量已经超过约定并发配额，限制访问} —— 整个阶段失败。
     * 2026-09-20 就是这么被拦住的（用户从控制台截图确认：峰值 5 / 上限 3QPS）。
     *
     * <p><b>为什么用 sleep 而不是令牌桶</b>：这里的调用量是「几十次/天」量级，串行等待完全够用；
     * 引入令牌桶只是徒增复杂度，而它带来的并发收益在本场景毫无意义
     * （本来就该慢慢发，因为额度只有 100 次/天）。
     *
     * <p>间隔做成 <b>L2 可配</b>（{@code map.min-interval-ms}）：百度偶尔调整限额，
     * 而且用户买了流量包之后上限会提高，那时把间隔调小即可，不用改代码重启。
     * 配置键不存在时用默认值 —— 所以不配置也能正常工作。
     */
    private void throttleMapCall() {
        int interval = sysConfigService.getInt(KEY_MAP_MIN_INTERVAL, DEFAULT_MAP_MIN_INTERVAL_MS);
        if (interval <= 0) {
            return;
        }
        long last = lastMapCallAt.get();
        if (last > 0) {
            long wait = interval - (System.currentTimeMillis() - last);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    // 恢复中断标记：被中断时不该继续，把状态还回去
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("地图调用限速等待被中断", e);
                }
            }
        }
        lastMapCallAt.set(System.currentTimeMillis());
    }

    /** 候选不足时用的宽泛检索词，来自字典的 broadenKeywords（可维护，不用改代码） */
    private List<String> broadenKeywords = List.of("景点", "公园", "古迹");

    /** 上一次地图调用的时刻，用于限速（本实例内有效；本项目是单实例部署，够用） */
    private final java.util.concurrent.atomic.AtomicLong lastMapCallAt =
            new java.util.concurrent.atomic.AtomicLong(0);

    private int minScenicCandidates = 6;

    public CandidateSearcher(MapCapabilityResolver mapResolver,
                             LlmCapabilityResolver llmResolver,
                             AiLogService aiLogService,
                             SysConfigService sysConfigService,
                             ObjectMapper objectMapper) {
        this.mapResolver = mapResolver;
        this.llmResolver = llmResolver;
        this.aiLogService = aiLogService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
        loadDictionary();
    }

    // ==================== 主流程 ====================

    /**
     * 检索候选池。
     *
     * @param intent      P3-A 的意图解析结果（提供目的地、偏好标签、本次忌口）
     * @param profile     用户长期画像，可为 null（提供菜系偏好与长期忌口）
     * @param capability  地图能力决策结果，可为 null（视作不可用 → 走 LLM 路径）
     * @return 候选池；<b>永不返回 null</b>，可能为空池但一定带 shortage 说明
     */
    public CandidatePool searchCandidates(IntentDTO intent,
                                          UserTravelProfile profile,
                                          ResolvedMap capability) {
        if (intent == null || !StringUtils.hasText(intent.getDestination())) {
            // 目的地都没有就没法检索 —— 这是 P3-A 的 needConfirm 该拦住的情况，
            // 走到这里说明上游漏判了，如实返回空池而不是硬编一个目的地
            CandidatePool empty = new CandidatePool();
            empty.setMapMode(capability == null ? MapMode.ESTIMATED : capability.mode());
            empty.setShortage(true);
            empty.setShortageHint("还没有确定目的地，无法检索点位");
            return empty;
        }

        long startMs = System.currentTimeMillis();
        boolean mapLive = capability != null
                && (capability.mode() == MapMode.VERIFIED || capability.mode() == MapMode.CACHED);

        List<CandidateDTO> raw;
        List<String> degradations = new ArrayList<>();
        LlmCallContextHolder llmHolder = new LlmCallContextHolder();

        int maxCandidate = sysConfigService.getInt(KEY_MAX_CANDIDATE, DEFAULT_MAX_CANDIDATE);

        if (mapLive) {
            // 留一倍余量就够（去重会砍掉一部分），不必把每个检索词都打完 —— 免费 AK 配额很紧
            raw = collectFromMap(intent, profile, degradations, maxCandidate * 2);
        } else {
            raw = generateByLlm(intent, profile, llmHolder);
        }

        List<CandidateDTO> cleaned = clean(raw, profile, intent, maxCandidate);

        CandidatePool pool = new CandidatePool(cleaned, capability == null ? MapMode.ESTIMATED : capability.mode());
        pool.setDegradations(degradations);

        // 景点候选不足 → 按序放宽。两个前提，缺一不可：
        //   ① 走的是地图路径（LLM 路径已在 prompt 里要求给够数量）；
        //   ② 用户**确实想要景点**。若他只点了「美食」这类餐饮偏好，景点数天然为 0，
        //      此时触发放宽纯属浪费 —— 实测一次白烧 5 次百度调用（额度只有 100 次/天）。
        List<String> preferences = safeList(intent.getPreferenceTags());
        boolean expectsScenic = preferences.isEmpty()
                || preferences.stream().anyMatch(p -> !TripItem.TYPE_FOOD.equals(resolveMapping(p).itemType));

        if (mapLive && expectsScenic && pool.scenicCount() < minScenicCandidates) {
            widen(intent, profile, pool, maxCandidate, degradations);
        } else if (mapLive && !expectsScenic) {
            log.debug("用户只要餐饮偏好，跳过景点放宽检索（省额度）");
        }

        evaluateShortage(pool);

        recordStage(llmHolder, System.currentTimeMillis() - startMs, pool, mapLive);
        return pool;
    }

    // ==================== 地图路径 ====================

    /**
     * 景点路 + 餐饮路，两路都走百度。
     *
     * <p><b>够用就停</b>：每个检索词都是一次真实的百度调用，而免费 AK 的并发/日配额都很紧
     * （实测把三条验收跑一遍就会撞上 {@code status=401 并发量超过约定配额}）。
     * 所以收集到 {@code maxCandidate * 2} 条就先停 —— 后面还有去重会砍掉一部分，
     * 留一倍余量足够，不必把每个偏好 × 每个检索词都打完。
     */
    private List<CandidateDTO> collectFromMap(IntentDTO intent, UserTravelProfile profile,
                                              List<String> degradations, int maxCollect) {
        List<CandidateDTO> collected = new ArrayList<>();
        String city = intent.getDestination();

        // ---- 景点路：按偏好查字典，用检索词（query）查百度 ----
        for (String preference : safeList(intent.getPreferenceTags())) {
            PreferenceMapping mapping = resolveMapping(preference);
            if (TripItem.TYPE_FOOD.equals(mapping.itemType)) {
                continue;   // 餐饮偏好交给餐饮路，避免同一个点被两路重复搜出来
            }
            for (String keyword : mapping.keywords) {
                collected.addAll(queryPoi(city, keyword, preference, mapping));
                if (collected.size() >= maxCollect) {
                    log.debug("景点候选已达 {} 条，停止继续检索（省配额）", collected.size());
                    return collected;
                }
            }
        }

        // ---- 餐饮路：菜系偏好 + 本次忌口里提到的菜系词 ----
        for (String cuisine : foodKeywordsFrom(profile, intent)) {
            List<String> words = foodDict.getOrDefault(cuisine, List.of(cuisine));
            for (String word : words) {
                collected.addAll(queryPoi(city, word, cuisine, foodMapping()));
                if (collected.size() >= maxCollect) {
                    log.debug("候选已达 {} 条，停止继续检索（省配额）", collected.size());
                    return collected;
                }
            }
        }

        log.debug("地图路径原始候选 {} 条（景点路 + 餐饮路）", collected.size());
        return collected;
    }

    /**
     * 跑一次百度检索。
     *
     * <p><b>只发 query，不发 tag</b> —— 这条是实测逼出来的：
     * ① 百度要求 query 必填，单发 tag 直接 {@code status=2 Parameter Invalid}（会抛异常、整个阶段失败）；
     * ② 更阴的是 tag 填错时<b>不报错</b>：{@code tag=风景名胜} 会让结果静默变成
     *    「广州市/邵阳市/福州市」这类城市级垃圾（实测 42 条全是噪声），
     *    而正确的 POI（方山国家森林公园、冷泉寺）一个都不剩；
     * ③ 填了合法的 tag 也没有增益，结果与不带 tag 完全一致。
     * 一个「填错不报错、只会静默变垃圾」的参数，在没有可靠取值清单之前不该用。
     */
    private List<CandidateDTO> queryPoi(String city, String keyword,
                                        String fromPreference, PreferenceMapping mapping) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        throttleMapCall();
        PoiQueryDTO query = new PoiQueryDTO();
        query.setCity(city);
        query.setKeyword(keyword);
        query.setPageSize(10);

        MapCapabilityResolver.MapCallResult<List<PoiDTO>> result =
                mapResolver.call(p -> p.searchPoi(query), list -> list != null && !list.isEmpty());

        if (result.value() == null || result.value().isEmpty()) {
            return List.of();
        }

        String verifyStatus = result.mode() == MapMode.VERIFIED
                ? TripItem.VERIFY_VERIFIED : TripItem.VERIFY_CACHED;

        List<CandidateDTO> list = new ArrayList<>();
        for (PoiDTO poi : result.value()) {
            CandidateDTO candidate = new CandidateDTO();
            candidate.setPoiUid(poi.poiUid());
            candidate.setName(poi.name());
            candidate.setItemType(mapping.itemType);
            candidate.setAddress(poi.address());
            candidate.setArea(deriveArea(poi));
            candidate.setLng(poi.lng());
            candidate.setLat(poi.lat());
            candidate.setStayMinutes(mapping.stayMinutes);
            candidate.setTag(poi.tag());
            candidate.setRating(poi.rating());
            candidate.setTicketPrice(poi.ticketPrice());
            candidate.setDataSource(TripItem.SOURCE_BAIDU);
            candidate.setVerifyStatus(verifyStatus);
            candidate.setFromPreference(fromPreference);
            list.add(candidate);
        }
        return list;
    }

    /**
     * 从地址里抠出「区域」。
     *
     * <p>百度的检索结果<b>没有单独的行政区字段</b>，只有一整串地址（如「山西省晋中市寿阳县朝阳街…」）。
     * 这里取「省市」之后、「街/路/号」之前的一段当区域 —— 只是给模型一个「在城东还是城西」的粗略感知，
     * 不追求精确。抠不出来就留 null，<b>不编</b>。
     */
    private String deriveArea(PoiDTO poi) {
        String address = poi.address();
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String trimmed = address.trim();
        for (String marker : List.of("街", "路", "巷", "号", "镇", "乡")) {
            int idx = trimmed.indexOf(marker);
            if (idx > 0) {
                String head = trimmed.substring(0, idx);
                return head.length() > 12 ? head.substring(head.length() - 12) : head;
            }
        }
        return trimmed.length() > 12 ? trimmed.substring(0, 12) : trimmed;
    }

    /** 餐饮路的映射：类型 FOOD，停留 60 分钟 */
    private PreferenceMapping foodMapping() {
        PreferenceMapping mapping = new PreferenceMapping();
        mapping.itemType = TripItem.TYPE_FOOD;
        mapping.stayMinutes = defaultStayMinutes.getOrDefault(TripItem.TYPE_FOOD, 60);
        mapping.keywords = List.of();
        return mapping;
    }

    /** 菜系词的来源：长期画像的 cuisines + 本次忌口里能识别出的菜系词 */
    private List<String> foodKeywordsFrom(UserTravelProfile profile, IntentDTO intent) {
        LinkedHashSet<String> cuisines = new LinkedHashSet<>();
        if (profile != null && StringUtils.hasText(profile.getCuisines())) {
            for (String part : profile.getCuisines().split("[,，]")) {
                if (StringUtils.hasText(part)) {
                    cuisines.add(part.trim());
                }
            }
        }
        // 「想吃面食」这类本次表达也会落到 dietaryOverrides 之外 —— P3-A 已把「想吃的」放进 preferenceTags，
        // 所以这里只额外收一次 preferenceTags 里的菜系词，保证「只想吃面食」也能搜到餐饮
        for (String tag : safeList(intent.getPreferenceTags())) {
            if (foodDict.containsKey(tag)) {
                cuisines.add(tag);
            }
        }
        return new ArrayList<>(cuisines);
    }

    // ==================== 放宽（候选不足）====================

    /**
     * 候选不足时的按序放宽。
     *
     * <p><b>关于手册说的「扩大检索半径（region 从区县放宽到地级市）」</b>：
     * 实测发现<b>不需要自己拼地级市名</b> —— {@code region=寿阳}（县级名）时百度本来就会
     * 放宽到周边地市返回结果（实测搜「文庙」返回了平遥文庙，平遥属晋中），
     * 也就是说「半径放宽」是百度自己的行为。而 {@code PoiQueryDTO} 也没有 bounds/radius 字段。
     * 所以这里用两个能真正执行的放宽动作，并在 degradations 里如实记录：
     * ① 换宽泛检索词（「景点」「公园」「古迹」）；
     * ② 翻页（同一 query 多取几页）。
     * <b>没有做的事绝不写进 degradations</b> —— 那会让「降级证据」变成假的。
     */
    private void widen(IntentDTO intent, UserTravelProfile profile, CandidatePool pool,
                       int maxCandidate, List<String> degradations) {
        String city = intent.getDestination();
        List<CandidateDTO> extra = new ArrayList<>();
        List<String> broaden = broadenKeywords.isEmpty() ? List.of("景点") : broadenKeywords;

        // ① 换宽泛检索词
        for (String broad : broaden) {
            extra.addAll(queryPoi(city, broad, "放宽关键词", scenicMapping()));
        }
        degradations.add("改用宽泛检索词（" + String.join("/", broaden) + "）");

        // ② 翻页
        for (int page = 2; page <= 3; page++) {
            PoiQueryDTO query = new PoiQueryDTO();
            query.setCity(city);
            query.setKeyword(broaden.get(0));
            query.setPageNum(page);
            query.setPageSize(10);
            throttleMapCall();
            MapCapabilityResolver.MapCallResult<List<PoiDTO>> result =
                    mapResolver.call(p -> p.searchPoi(query), list -> list != null && !list.isEmpty());
            if (result.value() != null) {
                for (PoiDTO poi : result.value()) {
                    extra.add(toCandidate(poi, scenicMapping(), "翻页补充", TripItem.VERIFY_VERIFIED));
                }
            }
        }
        degradations.add("翻页检索（第 2~3 页）");

        // 合并后再清洗一次（放宽带进来的新点同样要过去重与忌口）
        List<CandidateDTO> merged = new ArrayList<>(pool.getItems());
        merged.addAll(extra);
        pool.setItems(clean(merged, profile, intent, maxCandidate));
        log.info("候选不足，已放宽检索；放宽后景点 {} 个", pool.scenicCount());
    }

    private PreferenceMapping scenicMapping() {
        PreferenceMapping mapping = new PreferenceMapping();
        mapping.itemType = TripItem.TYPE_SCENIC;
        mapping.stayMinutes = defaultStayMinutes.getOrDefault(TripItem.TYPE_SCENIC, 90);
        mapping.keywords = List.of();
        return mapping;
    }

    private CandidateDTO toCandidate(PoiDTO poi, PreferenceMapping mapping,
                                     String fromPreference, String verifyStatus) {
        CandidateDTO candidate = new CandidateDTO();
        candidate.setPoiUid(poi.poiUid());
        candidate.setName(poi.name());
        candidate.setItemType(mapping.itemType);
        candidate.setAddress(poi.address());
        candidate.setArea(deriveArea(poi));
        candidate.setLng(poi.lng());
        candidate.setLat(poi.lat());
        candidate.setStayMinutes(mapping.stayMinutes);
        candidate.setTag(poi.tag());
        candidate.setRating(poi.rating());
        candidate.setTicketPrice(poi.ticketPrice());
        candidate.setDataSource(TripItem.SOURCE_BAIDU);
        candidate.setVerifyStatus(verifyStatus);
        candidate.setFromPreference(fromPreference);
        return candidate;
    }

    // ==================== LLM 路径（地图关闭）====================

    /**
     * 地图关闭时改由大模型生成候选清单。
     *
     * <p><b>prompt 里的三句硬约束是这个方法的全部价值所在</b>：
     * ① 你不掌握实时数据；② 不要输出经纬度；③ 不要编造具体门牌号。
     * 模型一旦给出坐标，下游就会拿它去做空间预排（P3-C），排出一份「看起来合理但地点全错」的行程 ——
     * 那种错误在界面上看不出来，是本项目最怕的一类故障。
     * 所以这里对模型返回的坐标<b>不是「不采信」而是「直接丢弃」</b>，从数据上杜绝。
     */
    private List<CandidateDTO> generateByLlm(IntentDTO intent, UserTravelProfile profile,
                                             LlmCallContextHolder holder) {
        String systemPrompt = buildLlmCandidatePrompt();
        String userPrompt = buildLlmCandidateUserPrompt(intent, profile);

        List<String> violations = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String prompt = attempt == 1 ? userPrompt : userPrompt + buildCorrection(violations);
            AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
            LlmCapabilityResolver.LlmCallResult<String> call;
            try {
                call = llmResolver.execute(provider -> provider.chatJson(
                        systemPrompt, prompt, LLM_CANDIDATE_SCHEMA_HINT, LlmCallContext.of(usageRef::set)));
            } catch (LlmException e) {
                log.warn("地图关闭时让大模型生成候选失败（{}），返回空池并如实上报 shortage", e.getMessage());
                holder.call = null;
                holder.usage = usageRef.get();
                // 记下「最后尝试的厂商」：失败也要能归因到厂商，否则指标里这次失败无处可归
                holder.failedProvider = e.getProvider();
                return List.of();
            }
            holder.call = call;
            holder.usage = usageRef.get();

            ParseResult parsed = parseLlmCandidates(call.value());
            if (parsed.candidates.isEmpty()) {
                violations = parsed.violations;
                log.warn("大模型候选第 {} 次解析未通过：{}", attempt, violations);
                continue;
            }
            return parsed.candidates;
        }
        return List.of();
    }

    private String buildLlmCandidatePrompt() {
        return """
                你是旅行点位推荐助手。用户的目的地地图服务当前不可用，所以你只能凭你已有的知识推荐点位。

                【必须遵守的五条】
                1. **你不掌握实时数据**，你给的点位可能已经改名、搬迁或关闭。
                2. **不要输出经纬度**，一个都不要给 —— 系统拿不到可信坐标时会自行按估算处理。
                3. **不要编造具体门牌号或精确地址**，只说到「大致区域」这一级（如「寿阳县城东」「老城区」）。
                4. **只写你真的说得出名字的地方**。
                   - 严禁占位式命名：「特色餐馆1」「某某景点」「XX公园」这种一律不要出现；
                   - 严禁拿电影院、图书馆、政府广场、小区这类**不是旅游目的地**的场所充数；
                   - 说不出具体名字就**少给几条**，宁可只有 5 条，也不要凑数 ——
                     用户会照着这份清单真的出门，编出来的点会让他白跑一趟。
                5. **必须至少给 3 个餐饮点位（type=FOOD）**，而且要是能说出名字的当地餐馆/面馆/小吃店。
                   —— 行程里没有吃饭的地方是没法用的。这一条和第 4 条不冲突：
                   第 4 条要求的是「别编」，不是「别给餐饮」；说不出店名就给当地**有名字的**特色餐馆。

                【只输出这些字段】
                name          点位名称（必须是你确实知道的具体地名）
                type          只能是 SCENIC（景点）或 FOOD（餐饮）
                area          所属大致区域（如「寿阳县城东」）
                stayMinutes   建议停留时长（分钟，整数）
                highlight     一句话亮点（不超过 30 字）

                严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。
                """;
    }

    private String buildLlmCandidateUserPrompt(IntentDTO intent, UserTravelProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("目的地：").append(intent.getDestination()).append('\n');
        sb.append("行程天数：").append(intent.getDays() == null ? "未定" : intent.getDays()).append('\n');
        if (!safeList(intent.getPreferenceTags()).isEmpty()) {
            sb.append("偏好：").append(String.join("、", intent.getPreferenceTags())).append('\n');
        }
        if (profile != null && StringUtils.hasText(profile.getCuisines())) {
            sb.append("菜系偏好：").append(profile.getCuisines()).append('\n');
        }
        sb.append("\n请给出 ").append(LLM_MIN_CANDIDATES).append('~').append(LLM_MAX_CANDIDATES)
          .append(" 个点位，其中**至少 3 个是当地餐饮**（type=FOOD，要能说出店名，用于安排午餐/晚餐），")
          .append("其余为景点。没有餐饮点位的行程是没法用的。");
        sb.append("\n注意：如果你对「").append(intent.getDestination())
          .append("」这个地方并不了解，请<b>少给几个</b>也不要编 —— 少给只是候选不足，编了会让用户按错误信息出行。");
        return sb.toString();
    }

    private static final String LLM_CANDIDATE_SCHEMA_HINT = """
            期望的 json 结构如下：
            {"candidates":[{"name":"点位名称","type":"SCENIC","area":"大致区域","stayMinutes":90,"highlight":"一句话亮点"}]}
            type 只能是 SCENIC 或 FOOD；stayMinutes 是整数分钟；
            area 只写大致区域，不要写门牌号；**不要出现 lat/lng/address 这些字段**。
            """;

    private String buildCorrection(List<String> violations) {
        StringBuilder sb = new StringBuilder("\n\n【上一次的输出没有通过校验，请修正后重新输出】\n");
        if (violations != null) {
            for (String v : violations) {
                sb.append("- ").append(v).append('\n');
            }
        }
        sb.append("请只输出修正后的 json 对象。\n");
        return sb.toString();
    }

    private record ParseResult(List<CandidateDTO> candidates, List<String> violations) {
    }

    /**
     * 解析模型给出的候选清单。
     *
     * <p><b>坐标一律不读</b>：即便模型给了 lat/lng，这里也直接忽略 ——
     * 不是「不信」而是「不采信」，让它没有机会污染 P3-C 的空间预排。
     */
    private ParseResult parseLlmCandidates(String raw) {
        List<String> violations = new ArrayList<>();
        String json = extractJsonObject(raw);
        if (json == null) {
            violations.add("输出里找不到 json 对象");
            return new ParseResult(List.of(), violations);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            violations.add("json 语法错误：" + e.getMessage());
            return new ParseResult(List.of(), violations);
        }
        JsonNode array = root.path("candidates");
        if (!array.isArray() || array.isEmpty()) {
            violations.add("candidates 必须是非空数组");
            return new ParseResult(List.of(), violations);
        }

        List<CandidateDTO> list = new ArrayList<>();
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;   // 没名字的候选没法用，直接丢，不算违规
            }
            // 占位式命名直接丢：模型凑数时会写「寿阳特色餐馆1」「某某景点」，
            // 提示词已明令禁止，这里是第二道闸 —— 这类名字进了候选池，
            // P3-D 会一本正经地把它排进行程，用户照着走会发现根本没这个地方
            if (looksLikePlaceholder(name)) {
                log.warn("丢弃占位式命名的候选：{}", name);
                continue;
            }
            CandidateDTO candidate = new CandidateDTO();
            candidate.setName(name);
            candidate.setArea(blankToNull(node.path("area").asText(null)));
            candidate.setHighlight(blankToNull(node.path("highlight").asText(null)));
            // 类型必须由模型给出：餐饮与景点不分开，P3-D 的「每天至少 1 个 FOOD」就永远排不出来
            // （实测过：不分类型时所有候选都被当成景点，foodCount 恒为 0）。
            // 给了非法值就当景点 —— 保守方向：宁可少一个餐饮点，也不要凭空造出一个。
            String type = node.path("type").asText("").trim().toUpperCase();
            candidate.setItemType(TripItem.TYPE_FOOD.equals(type)
                    ? TripItem.TYPE_FOOD : TripItem.TYPE_SCENIC);
            candidate.setStayMinutes(node.path("stayMinutes").isNumber()
                    ? node.path("stayMinutes").asInt()
                    : defaultStayMinutes.getOrDefault(candidate.getItemType(), 90));
            // 坐标与详细地址：坚决不设。dataSource/verifyStatus 如实标 LLM/ESTIMATED
            candidate.setDataSource(TripItem.SOURCE_LLM);
            candidate.setVerifyStatus(TripItem.VERIFY_ESTIMATED);
            candidate.setFromPreference("LLM生成");
            list.add(candidate);
        }
        return new ParseResult(list, violations);
    }

    /**
     * 识别占位式命名。
     *
     * <p><b>为什么规则里要同时管阿拉伯数字和中文数字</b>：第一版只挡了阿拉伯数字后缀，
     * 真实运行里模型立刻改用中文数字绕过去 —— 日志里抓到它把「寿阳特色餐馆1」改写成
     * 「寿阳特色餐馆**一**」「寿阳特色餐馆**二**」继续凑数。
     * 这不是过度设计，是实测出来的对抗。
     *
     * <p>仍然只挡<b>明确的</b>凑数写法，不做宽泛判断（宽泛判断会误杀真实地名）：
     * ① 含「某某 / XX」这类显式占位符；
     * ② 以数字（阿拉伯或中文）结尾、且整体是泛称（「特色餐馆一」「景点2」）。
     * 「王家8号院」「三巷老街」这类真实地名不会被误伤 —— 它们结尾是「院」「街」不是数字。
     */
    private boolean looksLikePlaceholder(String name) {
        if (name.contains("某某") || name.contains("XX") || name.contains("xx")) {
            return true;
        }
        boolean endsWithNumber = name.matches(".*[0-9０-９]+$")
                || name.matches(".*[一二三四五六七八九十]$");
        boolean genericNoun = name.contains("餐馆") || name.contains("餐厅")
                || name.contains("饭店") || name.contains("景点")
                || name.contains("小吃") || name.contains("美食")
                || name.contains("酒店") || name.contains("客栈");
        return endsWithNumber && genericNoun;
    }

    // ==================== 清洗 ====================

    /**
     * 清洗：丢无坐标点 → 按 uid 去重 → 按名称相似度去重 → 剔忌口餐饮 → 限量。
     *
     * <p><b>「丢无坐标点」只在有坐标可言的路径上做</b>：地图关闭时全部候选都没坐标，
     * 若也照丢就成了空池 —— 那是把能力降级做成了功能降级。
     */
    private List<CandidateDTO> clean(List<CandidateDTO> raw, UserTravelProfile profile,
                                     IntentDTO intent, int maxCandidate) {
        List<CandidateDTO> result = new ArrayList<>();
        LinkedHashSet<String> seenUid = new LinkedHashSet<>();
        // 忌口词与 P3-E 用同一套归一化（TabooMatcher）—— 两边各写一份迟早会跑偏，
        // 而「漏过忌口」是安全问题，不是体验问题
        List<String> tabooTerms = TabooMatcher.collect(profile, intent);

        for (CandidateDTO candidate : raw) {
            if (candidate == null || !StringUtils.hasText(candidate.getName())) {
                continue;
            }
            // 忌口第一道过滤：名字里带忌口词的点直接不要。
            // ⚠️ **刻意不判 isFood()**：放宽检索时用的宽泛关键词（「景点」「公园」）同样会把
            // 餐饮点搜出来，而那时它会被打成 SCENIC —— 只看类型就会漏掉它。
            // 忌口是硬约束（"多剔一个只是少一个点，漏剔一个是安全问题"），所以按名字一律过滤。
            if (TabooMatcher.hits(candidate.getName(), tabooTerms)) {
                log.debug("剔除含忌口的点：{}（命中 {}）", candidate.getName(), tabooTerms);
                continue;
            }
            // uid 去重
            if (StringUtils.hasText(candidate.getPoiUid())) {
                if (!seenUid.add(candidate.getPoiUid())) {
                    continue;
                }
            }
            // 名称相似度去重（防「XX寺」与「XX寺(东门)」这类重复）
            if (isDuplicateName(candidate.getName(), result)) {
                continue;
            }
            result.add(candidate);
            if (result.size() >= maxCandidate) {
                break;
            }
        }
        return result;
    }

    /**
     * 名称去重：归一化后「一个包含另一个」或完全相同，即视为同一个点。
     *
     * <p>手册说「相似度 > 0.85」，但本项目<b>不引字符串相似度库</b>（依赖纪律），
     * 所以用「去括号、去空白与全半角差异后，是否互为子串」这个等价判据 ——
     * 它对付的正是「寿阳文庙」vs「寿阳文庙(正门)」这种真实重复，
     * 而对「寿阳文庙」vs「寿阳古城」这种只共前缀的情况不会误杀。
     */
    private boolean isDuplicateName(String name, List<CandidateDTO> existing) {
        String normalized = normalizeName(name);
        for (CandidateDTO item : existing) {
            String other = normalizeName(item.getName());
            if (normalized.equals(other)
                    || normalized.contains(other)
                    || other.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化：<b>先去掉括号内容</b>，再做通用归一化（去空白、全角转半角、统一小写）。
     *
     * <p>通用部分委托给 {@link TabooMatcher#normalize} —— 与忌口匹配共用同一套规则，
     * 避免「去重时认为两个名字相同、忌口匹配时却认为不同」这种自相矛盾。
     * 去括号是本方法特有的：「寿阳文庙」与「寿阳文庙(正门)」应当算同一个点。
     */
    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return TabooMatcher.normalize(name.replaceAll("[（(].*?[)）]", ""));
    }

    // ==================== shortage 判定 ====================

    private void evaluateShortage(CandidatePool pool) {
        if (pool.isEmpty()) {
            pool.setShortage(true);
            pool.setShortageHint("没有检索到任何可用的点位，建议换个目的地或换个说法再试");
            return;
        }
        if (pool.scenicCount() < minScenicCandidates) {
            pool.setShortage(true);
            String extra = pool.getDegradations() == null || pool.getDegradations().isEmpty()
                    ? ""
                    : "（已尝试放宽标签、改用宽泛关键词与翻页检索）";
            pool.setShortageHint("该目的地可检索到的景点较少，只有 " + pool.scenicCount()
                    + " 个" + extra + "，行程可能会比较宽松");
        }
    }

    // ==================== 字典加载 ====================

    private void loadDictionary() {
        try (InputStream in = new ClassPathResource(DICT_PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);

            JsonNode preferences = root.path("preferences");
            preferences.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                PreferenceMapping mapping = new PreferenceMapping();
                mapping.itemType = node.path("itemType").asText(TripItem.TYPE_SCENIC);
                mapping.stayMinutes = node.path("stayMinutes").isNumber()
                        ? node.path("stayMinutes").asInt() : null;
                mapping.keywords = toStringList(node.path("keywords"));
                preferenceDict.put(entry.getKey(), mapping);
            });

            root.path("foodKeywords").fields().forEachRemaining(entry ->
                    foodDict.put(entry.getKey(), toStringList(entry.getValue())));

            root.path("defaultStayMinutes").fields().forEachRemaining(entry ->
                    defaultStayMinutes.put(entry.getKey(), entry.getValue().asInt()));

            List<String> broaden = toStringList(root.path("broadenKeywords"));
            if (!broaden.isEmpty()) {
                broadenKeywords = broaden;
            }

            if (root.path("minScenicCandidates").isNumber()) {
                minScenicCandidates = root.path("minScenicCandidates").asInt();
            }
            log.info("偏好映射字典已加载：{} 个偏好、{} 个菜系、宽泛检索词 {}、最小景点数 {}",
                    preferenceDict.size(), foodDict.size(), broadenKeywords, minScenicCandidates);
        } catch (Exception e) {
            // 字典缺失不该让应用起不来：查不到映射就退化为「直接用偏好词检索」
            log.warn("加载 {} 失败（{}），偏好将直接作为检索关键词使用", DICT_PATH, e.getMessage());
        }
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    list.add(text);
                }
            }
        }
        return list;
    }

    /** 查字典；查不到时按手册要求退化为「直接用偏好词当关键词」 */
    private PreferenceMapping resolveMapping(String preference) {
        PreferenceMapping mapping = preferenceDict.get(preference);
        if (mapping != null) {
            return mapping;
        }
        PreferenceMapping fallback = new PreferenceMapping();
        fallback.itemType = TripItem.TYPE_SCENIC;
        fallback.stayMinutes = defaultStayMinutes.getOrDefault(TripItem.TYPE_SCENIC, 90);
        fallback.keywords = List.of(preference);
        log.debug("偏好「{}」未收录进字典，退化为直接用偏好词检索", preference);
        return fallback;
    }

    // ==================== 工具 ====================

    private String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int closingFence = text.lastIndexOf("```");
            if (closingFence >= 0) {
                text = text.substring(0, closingFence);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 字典里一个偏好的映射项。
     *
     * <p><b>停留时长挂在偏好上而不是 itemType 上，是刻意的</b>：
     * {@code TripItem} 的类型只有 SCENIC/FOOD/HOTEL/TRANSPORT/REST，<b>没有 MUSEUM</b>，
     * 但手册要求「博物馆 120 分钟」——如果按 itemType 给默认值，博物馆只能拿到景点通用的 90 分钟。
     * 让字典同时持有「检索词」与「停留时长」，这个需求就有了正确的落点，
     * 而且将来调某个偏好的时长不用改代码。
     *
     * <p><b>只有 keywords，没有 tag</b>：百度 {@code tag} 参数填错时<b>不报错、只静默返回垃圾结果</b>
     * （实测 {@code tag=风景名胜} 会把结果变成「广州市/邵阳市」这种城市级噪声），
     * 而且填对了也没有增益。所以整条 tag 路径已弃用，理由详见
     * {@code resources/map-preference-tag.json} 的 {@code _whyNoTags}。
     */
    private static final class PreferenceMapping {
        String itemType = TripItem.TYPE_SCENIC;
        Integer stayMinutes;
        List<String> keywords = List.of();
    }

    /** 一次 CANDIDATE 阶段的 LLM 调用痕迹（地图路径下为空） */
    private static final class LlmCallContextHolder {
        LlmCapabilityResolver.LlmCallResult<String> call;
        LlmUsage usage;
        /**
         * 调用彻底失败时「最后尝试过的厂商」（P7-B 前修）。
         *
         * <p>没有它的话，失败时只能记 null，而阶段日志里「谁失败了」恰恰是最该留痕的信息 ——
         * 一次 glm 超时导致的候选池为空，看起来会像「谁都没参与」。
         */
        String failedProvider;
    }

    /**
     * 写 CANDIDATE 阶段日志。
     *
     * <p>地图路径下 provider/model 记的是地图侧的 provider（百度/缓存），
     * LLM 路径下记的是大模型。两条路径都只写一条 —— 一个阶段一条是 P2-C 定下的口径。
     *
     * <p>🔴 <b>走哪条路径必须由调用方显式告知（{@code mapPath}），不能靠「有没有成功调到大模型」反推</b>
     * （2026-09-23 修）：原来的判据是 {@code holder.call != null}，而**大模型超时失败时它恰好是 null**，
     * 于是 LLM 路径被误判成地图路径，这次失败被记成 {@code provider=cache} ——
     * 一次 glm 超时导致的候选池为空，在日志里看起来像「缓存提供者出的问题」。
     * 这种「失败时归因错人」比没有归因更坏：按厂商统计的指标会稳定地偏。
     *
     * @param mapPath 本阶段是否走地图路径（调用方那个 {@code mapLive} 就是它）
     */
    private void recordStage(LlmCallContextHolder holder, long durationMs, CandidatePool pool, boolean mapPath) {
        String provider;
        String model;
        if (mapPath) {
            provider = pool.getMapMode() == MapMode.VERIFIED ? "baidu" : "cache";
            model = null;
        } else {
            // 成功 → 实际服务的厂商；彻底失败 → 最后尝试的厂商（可能为 null，那就如实为 null）
            provider = holder.call != null ? holder.call.used().providerName() : holder.failedProvider;
            model = holder.call != null ? holder.call.used().model() : null;
        }

        // 候选池为空视为本阶段未达成目标，如实记失败（不抛异常 —— 空池也是合法结果）
        boolean success = !pool.isEmpty();
        AiErrorCode errorCode = success ? null : AiErrorCode.CANDIDATE_SHORTAGE;

        aiLogService.recordStage(new AiStageRecord(
                UserContext.getUserId(),
                // 取运行上下文里的 tripId（原先写死 null，导致 CANDIDATE 的成本归不到行程上）
                TripRunContext.getTripId(),
                AiStageRecord.STAGE_CANDIDATE,
                provider,
                model,
                holder.usage == null ? null : holder.usage.promptTokens(),
                holder.usage == null ? null : holder.usage.completionTokens(),
                (int) durationMs,
                success,
                errorCode,
                success ? null : "候选池为空：" + pool.getShortageHint()));
    }
}
