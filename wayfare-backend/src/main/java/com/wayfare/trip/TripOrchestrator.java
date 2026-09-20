package com.wayfare.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.connector.map.RouteDTO;
import com.wayfare.connector.map.RouteQueryDTO;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import com.wayfare.service.TravelProfileService;
import com.wayfare.service.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * 七步行程编排管线 / 外观（P3-F）。
 *
 * <p>把 P3-A~P3-E 各自独立的步骤串成一条可调用的链，并在 Step 6/7 补上
 * <b>事实补全（enrichRoutes）</b>与<b>结果组装 + 落库</b>。Step 1~5 的组件各自
 * 负责写自己的 {@code ai_generation_log}，本类补记 ROUTE 阶段并汇总 meta：
 * <pre>
 *   Step1 意图解析   IntentParser.parseIntent()
 *   Step2 候选检索   CandidateSearcher.searchCandidates()
 *   Step3 空间预排   PreOrderService.preOrder()（纯本地，0 token）
 *   Step4 行程编排   ItineraryComposer.compose()
 *   Step5 约束校验   ItineraryValidator.validate() + ItineraryReplanner.enforce()（回喂重排）
 *   Step6 事实补全   enrichRoutes()  ← P3-F 的核心
 *   Step7 结果组装   组装 Trip/TripDay/TripItem → TripService.saveFullTrip() → meta
 * </pre>
 *
 * <p><b>Step6 的三路分支（铁律一的落点）</b>：
 * <ul>
 *   <li>{@code mapMode=VERIFIED} —— 逐段调百度路线规划（并发上限 4），
 *       回填 distanceMeters / durationSeconds / transportModeToNext，标记 VERIFIED/BAIDU；
 *       transit 无数据时降级为 driving；</li>
 *   <li>{@code mapMode=CACHED} —— 命本地线路缓存的段 → 标 VERIFIED/BAIDU（fromCache 标 CACHED），
 *       未命中段 → 估算；</li>
 *   <li>{@code mapMode=ESTIMATED} —— 完全不调地图，距离/时长一律 null，
 *       verifyStatus=ESTIMATED、dataSource=LLM，模糊表述写进 note。</li>
 * </ul>
 * 补全后重跑 BACKTRACK / BUDGET_EXCEED 相关的校验，仍有 HIGH 违规且未达
 * {@code trip.max-replan-rounds} 上限时回到编排重排。
 */
@Component
public class TripOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TripOrchestrator.class);

    private static final String KEY_MAX_REPLAN = "trip.max-replan-rounds";
    private static final int DEFAULT_MAX_REPLAN = 2;
    private static final Pattern PURE_INT = Pattern.compile("^\\d+$");

    // ---- 供本类写 TripItem 常量，避免依赖具体实现类名 ----
    private static final String V_VERIFIED = "VERIFIED";
    private static final String V_CACHED = "CACHED";
    private static final String V_ESTIMATED = "ESTIMATED";
    private static final String S_BAIDU = "BAIDU";
    private static final String S_LLM = "LLM";

    private final TripService tripService;
    private final IntentParser intentParser;
    private final CandidateSearcher candidateSearcher;
    private final PreOrderService preOrderService;
    private final ItineraryComposer composer;
    private final ItineraryValidator validator;
    private final ItineraryReplanner replanner;
    private final MapCapabilityResolver mapResolver;
    private final LlmCapabilityResolver llmResolver;
    private final ProfileRenderer profileRenderer;
    private final TravelProfileService travelProfileService;
    private final AiLogService aiLogService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    public TripOrchestrator(TripService tripService,
                            IntentParser intentParser,
                            CandidateSearcher candidateSearcher,
                            PreOrderService preOrderService,
                            ItineraryComposer composer,
                            ItineraryValidator validator,
                            ItineraryReplanner replanner,
                            MapCapabilityResolver mapResolver,
                            LlmCapabilityResolver llmResolver,
                            ProfileRenderer profileRenderer,
                            TravelProfileService travelProfileService,
                            AiLogService aiLogService,
                            SysConfigService sysConfigService,
                            ObjectMapper objectMapper) {
        this.tripService = tripService;
        this.intentParser = intentParser;
        this.candidateSearcher = candidateSearcher;
        this.preOrderService = preOrderService;
        this.composer = composer;
        this.validator = validator;
        this.replanner = replanner;
        this.mapResolver = mapResolver;
        this.llmResolver = llmResolver;
        this.profileRenderer = profileRenderer;
        this.travelProfileService = travelProfileService;
        this.aiLogService = aiLogService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
    }

    /**
     * 完整规划（Step1~7）：从用户原话一路走到落库。
     *
     * @return 编排结果；{@link OrchestrationOutcome#tripId()} 永远非 null（草稿已落库）
     */
    public OrchestrationOutcome orchestrate(Long userId, String rawInput,
                                            boolean useProfile, ProfileOverrides overrides) {
        long startMs = System.currentTimeMillis();
        LocalDateTime runStart = LocalDateTime.now();

        // Step 0/1：先落一条草稿（status=0），后续任何一步失败用户都能续作
        Trip draftTrip = tripService.createDraft(userId, rawInput);

        // 地图能力与画像：这两件是贯穿全管线的上下文
        ResolvedMap capability = mapResolver.resolve();
        UserTravelProfile profile = loadProfile(userId, useProfile);
        boolean profileUsed = profile != null;

        try {
            IntentDTO intent = intentParser.parseIntent(rawInput, profile, overrides, capability);
            PipelineOutput out = runPipeline(intent, profile, overrides, capability, null);

            if (out.draft() != null) {
                assembleAndSave(draftTrip, intent, out);
            }
            recordRouteStage(userId, draftTrip.getId(), out.draft() != null, startMs);

            return new OrchestrationOutcome(draftTrip.getId(), out.intent(), out.draft(),
                    out.report(), out.pool(), out.composeError(), buildMeta(userId, runStart, startMs, out));
        } catch (RuntimeException e) {
            log.warn("行程编排执行到中途失败，草稿 {} 保持 status=0 供续作：{}", draftTrip.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * 基于已落库行程的「重排」（P3-F 的 {@code POST /api/trip/{id}/replan}）。
     *
     * <p>不重跑意图解析：从 {@code trip.intent_json} 恢复 {@link IntentDTO}，
     * 用带 dayIndex 的 {@code feedback} 触发一次以纠错为主的重新编排。
     *
     * @return 重新编排后的行程草稿；{@code composeError} 非空表示编排失败
     */
    public OrchestrationOutcome replan(Long userId, Long tripId, String feedback) {
        long startMs = System.currentTimeMillis();
        LocalDateTime runStart = LocalDateTime.now();

        Trip existing = tripService.getDetail(tripId, userId);
        if (existing == null) {
            throw new com.wayfare.common.exception.BusinessException(
                    com.wayfare.common.result.ResultCode.NOT_FOUND, "行程不存在");
        }
        IntentDTO intent = readIntent(existing.getIntentJson());
        if (intent == null) {
            throw new com.wayfare.common.exception.BusinessException(
                    com.wayfare.common.result.ResultCode.PARAM_ERROR, "这条行程缺少可用的意图信息，无法重排");
        }

        ResolvedMap capability = mapResolver.resolve();
        UserTravelProfile profile = loadProfile(userId, true);
        PipelineOutput out = runPipeline(intent, profile, new ProfileOverrides(), capability, feedback);

        if (out.draft() != null) {
            assembleInto(existing, intent, out);
        }
        recordRouteStage(userId, tripId, out.draft() != null, startMs);

        return new OrchestrationOutcome(tripId, intent, out.draft(), out.report(),
                out.pool(), out.composeError(), buildMeta(userId, runStart, startMs, out));
    }

    // ==================== Step 2~7 的串行执行体 ====================

    private PipelineOutput runPipeline(IntentDTO intent, UserTravelProfile profile,
                                       ProfileOverrides overrides, ResolvedMap capability,
                                       String feedback) {
        // Step2：候选检索
        CandidatePool pool = candidateSearcher.searchCandidates(intent, profile, capability);
        // Step3：空间预排（起点 = 目的地中心，见 PreOrderResult 的说明）
        PreOrderResult preOrder = preOrderService.preOrder(
                pool.getItems(), intent.getDestLng(), intent.getDestLat());

        int maxRounds = sysConfigService.getInt(KEY_MAX_REPLAN, DEFAULT_MAX_REPLAN);
        ReplanResult replan;
        if (StringUtils.hasText(feedback)) {
            // 重排入口：直接带反馈编排一版（不再走 replanner 的内部自洽重排，反馈已指定了方向）
            ComposeResult c = composer.compose(intent, pool, preOrder, profile, overrides, capability, feedback);
            replan = c.success()
                    ? ReplanResult.of(c.draft(), validator.validate(c.draft(), intent, pool, profile, overrides), 0)
                    : ReplanResult.composeFailed(c.errorMessage());
        } else {
            // Step4+5：编排 → 校验 → 回喂重排（replanner 内部自带轮次上限）
            replan = replanner.enforce(intent, pool, preOrder, profile, overrides, capability);
        }

        TripDraftDTO draft = replan.draft();
        ValidationReport report = replan.report();
        int rounds = replan.replanRounds();
        String composeError = replan.composeError();

        // Step6：事实补全（enrichRoutes）
        if (draft != null) {
            enrichRoutes(draft, intent, pool, capability);
            // 补全后重跑校验：路线事实可能改变 BACKTRACK / BUDGET_EXCEED 的结论
            ValidationReport afterRoute = validator.validate(draft, intent, pool, profile, overrides);
            while (afterRoute.hasHigh() && rounds < maxRounds && composeError == null) {
                rounds++;
                ComposeResult again = composer.compose(intent, pool, preOrder, profile, overrides,
                        capability, afterRoute.toFeedbackText());
                if (!again.success()) {
                    composeError = again.errorMessage();
                    break;
                }
                draft = again.draft();
                enrichRoutes(draft, intent, pool, capability);
                afterRoute = validator.validate(draft, intent, pool, profile, overrides);
            }
            report = afterRoute;
        }

        return new PipelineOutput(intent, pool, draft, report, composeError, rounds,
                capability == null ? MapMode.ESTIMATED : capability.mode(),
                resolveModelName(), profile != null);
    }

    // ==================== Step6 · enrichRoutes ====================

    /**
     * 逐段补全路线事实。见类注释的三路分支说明。
     */
    private void enrichRoutes(TripDraftDTO draft, IntentDTO intent, CandidatePool pool, ResolvedMap capability) {
        if (draft == null || draft.getDays() == null) {
            return;
        }
        List<CandidateDTO> poolItems = pool == null ? List.of() : pool.getItems();
        boolean mapLive = capability != null && capability.mode() != MapMode.ESTIMATED;

        List<Runnable> tasks = new ArrayList<>();
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            List<TripDraftDTO.ItemDraft> items = day.getItems();
            for (int i = 0; i < items.size(); i++) {
                TripDraftDTO.ItemDraft item = items.get(i);
                resolveFacts(item, poolItems);
                TripDraftDTO.ItemDraft next = (i < items.size() - 1) ? items.get(i + 1) : null;
                if (mapLive && next != null && hasCoords(item) && hasCoords(next)) {
                    tasks.add(() -> fillRoute(item, next, intent));
                } else if (mapLive) {
                    // 末站 / 无坐标段：保留已从候选池回填的可信度标记，不补距离
                    setNoRoute(item);
                } else {
                    tasks.add(() -> setEstimated(item, intent));
                }
            }
        }
        runConcurrent(tasks, 4);
    }

    /** 把ItemDraft 的 poiRef 解析到候选池里的候选点，回填 POI 事实字段 */
    private void resolveFacts(TripDraftDTO.ItemDraft item, List<CandidateDTO> poolItems) {
        if (item == null) {
            return;
        }
        CandidateDTO c = resolveCandidate(item, poolItems);
        if (c == null) {
            return;
        }
        item.setPoiUid(c.getPoiUid());
        if (StringUtils.hasText(c.getName())) {
            item.setPoiName(c.getName());
        }
        item.setAddress(c.getAddress());
        item.setLng(c.getLng());
        item.setLat(c.getLat());
        // 候选点自带的可信度/来源（map 检索到的点带 VERIFIED/BAIDU，估算补的点带 ESTIMATED/LLM）
        if (StringUtils.hasText(c.getVerifyStatus())) {
            item.setVerifyStatus(c.getVerifyStatus());
        }
        if (StringUtils.hasText(c.getDataSource())) {
            item.setDataSource(c.getDataSource());
        }
    }

    private CandidateDTO resolveCandidate(TripDraftDTO.ItemDraft item, List<CandidateDTO> poolItems) {
        if (item == null || item.getPoiRef() == null || poolItems == null || poolItems.isEmpty()) {
            return null;
        }
        String ref = item.getPoiRef().trim();
        if (PURE_INT.matcher(ref).matches()) {
            int idx = Integer.parseInt(ref);
            if (idx >= 1 && idx <= poolItems.size()) {
                return poolItems.get(idx - 1);
            }
            return null;
        }
        for (CandidateDTO c : poolItems) {
            if (ref.equals(c.getName())
                    || (item.getPoiName() != null && ref.equals(item.getPoiName()))) {
                return c;
            }
        }
        return null;
    }

    /** 真实调地图补一路：VERIFIED 命中回填距离/时长；失败或缓存未命中 → 估算 */
    private void fillRoute(TripDraftDTO.ItemDraft item, TripDraftDTO.ItemDraft next, IntentDTO intent) {
        try {
            String mode = toRouteMode(intent == null ? null : intent.getTransport());
            Double flng = item.getLng(), flat = item.getLat();
            Double tlng = next.getLng(), tlat = next.getLat();
            final String requestMode = mode;

            MapCapabilityResolver.MapCallResult<RouteDTO> result = mapResolver.call(p -> {
                RouteDTO r = p.route(new RouteQueryDTO(flng, flat, tlng, tlat, requestMode));
                if (r == null && "transit".equals(requestMode)) {
                    r = p.route(new RouteQueryDTO(flng, flat, tlng, tlat, "driving"));
                }
                return r;
            }, r -> r != null && r.distanceMeters() != null);

            RouteDTO route = result.value();
            if ((result.mode() == MapMode.VERIFIED || result.mode() == MapMode.CACHED)
                    && route != null && route.distanceMeters() != null) {
                item.setDistanceMeters(route.distanceMeters());
                item.setDurationSeconds(route.durationSeconds());
                String actual = route.mode() != null ? route.mode() : requestMode;
                item.setTransportModeToNext(toFormalTransport(actual));
                item.setVerifyStatus(route.fromCache() ? V_CACHED : V_VERIFIED);
                item.setDataSource(S_BAIDU);
                return;
            }
            setEstimated(item, intent);
        } catch (Exception e) {
            log.warn("路线规划失败，该段降级为估算：{}", e.getMessage());
            setEstimated(item, intent);
        }
    }

    /** 估算模式：距离/时长一律 null，标 ESTIMATED/LLM，模糊表述写 note */
    private void setEstimated(TripDraftDTO.ItemDraft item, IntentDTO intent) {
        item.setDistanceMeters(null);
        item.setDurationSeconds(null);
        item.setVerifyStatus(V_ESTIMATED);
        item.setDataSource(S_LLM);
        if (!StringUtils.hasText(item.getNote())) {
            item.setNote(defaultEstimNote(intent));
        }
    }

    /** 地图可用但该段不补路线（末站/无坐标）：清掉距离表现，保留候选可信度标记 */
    private void setNoRoute(TripDraftDTO.ItemDraft item) {
        item.setDistanceMeters(null);
        item.setDurationSeconds(null);
        if (!StringUtils.hasText(item.getVerifyStatus())) {
            item.setVerifyStatus(item.getLng() == null ? V_ESTIMATED : V_VERIFIED);
            item.setDataSource(item.getLng() == null ? S_LLM : S_BAIDU);
        }
    }

    private boolean hasCoords(TripDraftDTO.ItemDraft item) {
        return item != null && item.getLng() != null && item.getLat() != null;
    }

    /** intent.transport（DRIVE/PUBLIC/WALK/MIX）→ 百度出行方式；缺省 driving */
    private String toRouteMode(String transport) {
        if (transport == null) {
            return "driving";
        }
        return switch (transport) {
            case IntentDTO.TRANSPORT_WALK -> "walking";
            case IntentDTO.TRANSPORT_PUBLIC -> "transit";
            case IntentDTO.TRANSPORT_DRIVE -> "driving";
            case IntentDTO.TRANSPORT_MIX -> "driving";
            default -> "driving";
        };
    }

    /** 百度出行方式 → trip_item.transport_mode_to_next 的正式取值（DRIVE/PUBLIC/WALK/MIX） */
    private String toFormalTransport(String routeMode) {
        if (routeMode == null) {
            return "MIX";
        }
        return switch (routeMode) {
            case "walking" -> IntentDTO.TRANSPORT_WALK;
            case "transit", "riding" -> IntentDTO.TRANSPORT_PUBLIC;
            case "driving" -> IntentDTO.TRANSPORT_DRIVE;
            default -> IntentDTO.TRANSPORT_MIX;
        };
    }

    /** 估算模式下的默认模糊表述 —— 禁止给精确数字 */
    private String defaultEstimNote(IntentDTO intent) {
        String t = intent == null ? null : intent.getTransport();
        if (IntentDTO.TRANSPORT_WALK.equals(t)) {
            return "步行约十几分钟";
        }
        if (IntentDTO.TRANSPORT_PUBLIC.equals(t)) {
            return "公共交通约二十分钟上下";
        }
        if (IntentDTO.TRANSPORT_DRIVE.equals(t)) {
            return "驾车约十几分钟";
        }
        return "约 15 分钟左右可达";
    }

    private void runConcurrent(List<Runnable> tasks, int maxParallel) {
        if (tasks.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(maxParallel, tasks.size()));
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Runnable task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // 任务内部已各自兜底，这里只保证不因单个失败中断整体
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    // ==================== Step7 · 结果组装与落库 ====================

    private void assembleAndSave(Trip draftTrip, IntentDTO intent, PipelineOutput out) {
        assembleInto(draftTrip, intent, out);
    }

    private void assembleInto(Trip trip, IntentDTO intent, PipelineOutput out) {
        TripDraftDTO draft = out.draft();
        trip.setTitle(draft.getTitle());
        trip.setIntentJson(writeIntent(intent));
        trip.setDestination(intent.getDestination());
        trip.setDestLng(intent.getDestLng() == null ? null : BigDecimal.valueOf(intent.getDestLng()));
        trip.setDestLat(intent.getDestLat() == null ? null : BigDecimal.valueOf(intent.getDestLat()));
        trip.setDays(intent.getDays());
        trip.setStartDate(intent.getStartDate());
        trip.setBudgetTotal(intent.getBudgetTotal());
        trip.setBudgetMode(toBudgetModeInt(intent.getBudgetMode()));
        trip.setTransport(intent.getTransport());
        trip.setCompanion(intent.getCompanion());
        trip.setMapMode(out.mapMode() == null ? null : out.mapMode().name());
        trip.setProfileUsed(Boolean.TRUE.equals(out.profileUsed()) ? 1 : 0);
        trip.setModelName(out.modelName());
        trip.setGenerationRounds(out.replanRounds());
        trip.setStatus(1); // 0草稿 → 1已生成（整份行程已落库）
        trip.setDayPlans(buildDayPlans(draft));
        tripService.saveFullTrip(trip, trip.getDayPlans());
    }

    private List<TripDay> buildDayPlans(TripDraftDTO draft) {
        List<TripDay> days = new ArrayList<>();
        if (draft.getDays() == null) {
            return days;
        }
        for (TripDraftDTO.DayDraft dd : draft.getDays()) {
            TripDay day = new TripDay();
            day.setDayIndex(dd.getDayIndex());
            day.setTitle(dd.getTitle());
            day.setSummary(dd.getSummary());
            List<TripItem> items = new ArrayList<>();
            if (dd.getItems() != null) {
                int seq = 0;
                for (TripDraftDTO.ItemDraft id : dd.getItems()) {
                    items.add(toTripItem(dd.getDayIndex(), seq++, id));
                }
            }
            day.setItems(items);
            days.add(day);
        }
        return days;
    }

    private TripItem toTripItem(Integer dayIndex, int seq, TripDraftDTO.ItemDraft id) {
        TripItem item = new TripItem();
        item.setDayIndex(dayIndex);
        item.setSeq(seq);
        item.setItemType(id.getItemType());
        item.setPoiUid(id.getPoiUid());
        item.setPoiName(id.getPoiName());
        item.setAddress(id.getAddress());
        item.setLng(id.getLng() == null ? null : BigDecimal.valueOf(id.getLng()));
        item.setLat(id.getLat() == null ? null : BigDecimal.valueOf(id.getLat()));
        item.setArriveTime(parseTime(id.getStartTime()));
        item.setLeaveTime(parseTime(id.getEndTime()));
        item.setStayMinutes(id.getStayMinutes());
        item.setCostEstimate(id.getCostEstimate());
        item.setTransportModeToNext(id.getTransportModeToNext());
        item.setDistanceMeters(id.getDistanceMeters());
        item.setDurationSeconds(id.getDurationSeconds());
        item.setVerifyStatus(id.getVerifyStatus());
        item.setDataSource(id.getDataSource());
        item.setReason(id.getReason());
        item.setNote(id.getNote());
        return item;
    }

    private LocalTime parseTime(String hhmm) {
        if (!StringUtils.hasText(hhmm)) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** IntentDTO.budgetMode（PER_PERSON/TOTAL）→ trip.budget_mode（1人均/2总计）；未知回 2 */
    private Integer toBudgetModeInt(String mode) {
        if (IntentDTO.BUDGET_MODE_PER_PERSON.equals(mode)) {
            return 1;
        }
        return 2;
    }

    private String writeIntent(IntentDTO intent) {
        try {
            return objectMapper.writeValueAsString(intent);
        } catch (Exception e) {
            log.warn("意图 JSON 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private IntentDTO readIntent(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IntentDTO.class);
        } catch (Exception e) {
            log.warn("意图 JSON 反序列化失败：{}", e.getMessage());
            return null;
        }
    }

    // ==================== 上下文 / meta / 日志 ====================

    /** 按 useProfile 与隐私开关加载画像；不满足时不注入（铁律 3）且 profileUsed=false */
    private UserTravelProfile loadProfile(Long userId, boolean useProfile) {
        if (!useProfile) {
            return null;
        }
        UserTravelProfile loaded = travelProfileService.getByUserId(userId);
        return profileRenderer.shouldInject(loaded) ? loaded : null;
    }

    private String resolveModelName() {
        try {
            return llmResolver.resolve().model();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildMeta(Long userId, LocalDateTime runStart,
                                          long startMs, PipelineOutput out) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rounds", out.replanRounds());
        meta.put("mapMode", out.mapMode() == null ? null : out.mapMode().name());
        meta.put("modelName", out.modelName());
        meta.put("profileUsed", Boolean.TRUE.equals(out.profileUsed()) ? 1 : 0);
        meta.put("durationMs", (int) (System.currentTimeMillis() - startMs));
        Map<String, Object> cost = aiLogService.sumByUserSince(userId, runStart, LocalDateTime.now());
        meta.put("tokens", cost.get("totalTokens"));
        meta.put("estCost", cost.get("estCost"));
        return meta;
    }

    private void recordRouteStage(Long userId, Long tripId, boolean success, long startMs) {
        try {
            aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_ROUTE,
                    "map", null, null, null,
                    (int) (System.currentTimeMillis() - startMs), success, null, null));
        } catch (Exception ignored) {
            // 记日志失败绝不阻断主流程
        }
    }

    // ==================== 结果类型 ====================

    /** Step2~7 的中间产物，两块入口路径（完整规划 / 重排）共用 */
    public record PipelineOutput(IntentDTO intent, CandidatePool pool, TripDraftDTO draft,
                                 ValidationReport report, String composeError, int replanRounds,
                                 MapMode mapMode, String modelName, boolean profileUsed) {
    }

    /** 一次编排的对外结果。{@code tripId} 永远非 null */
    public record OrchestrationOutcome(Long tripId, IntentDTO intent, TripDraftDTO draft,
                                       ValidationReport report, CandidatePool pool,
                                       String composeError, Map<String, Object> meta) {
    }
}