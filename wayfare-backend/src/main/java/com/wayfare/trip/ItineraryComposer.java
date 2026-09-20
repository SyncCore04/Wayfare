package com.wayfare.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.security.UserContext;
import com.wayfare.service.AiLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 管线 Step 4：行程编排（P3-D）—— <b>大模型在这一步才真正上场</b>。
 *
 * <p><b>它的职责边界划得很死</b>：分天、排时段、配餐饮、写理由。
 * 也就是说，模型负责的全是「语义决策」，而所有「事实」都已经在它上场之前定好了：
 * <ul>
 *   <li>有哪些点 → P3-B 的候选池（模型<b>只能从这里选</b>）；</li>
 *   <li>点之间的先后 → P3-C 的算法排序（模型可以微调，但不能乱跳）；</li>
 *   <li>距离与时长 → P3-F 的地图补全（<b>这一步一律留 null</b>）。</li>
 * </ul>
 * 这样划分的好处是：模型即使「想编」，也没有地方可编 ——
 * 它唯一能自由发挥的是文字（reason / summary / title），而那些不影响行程能不能走。
 *
 * <p><b>失败不抛异常</b>（手册明确要求）：解析重试 1 次仍失败时返回
 * {@link ComposeResult#fail}，调用方手里<b>仍然有候选池</b>，前端可以让用户手选点位。
 * 抛异常会把这份「还能用」的中间产物一起丢掉 —— 那是把能力降级做成功能降级。
 */
@Component
public class ItineraryComposer {

    private static final Logger log = LoggerFactory.getLogger(ItineraryComposer.class);

    /** 最多尝试 2 次（首次 + 重试 1 次），与 P3-A 同一策略 */
    private static final int MAX_ATTEMPTS = 2;

    /** HH:mm */
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** 每天最少几个点（手册硬约束 2） */
    private static final int MIN_ITEMS_PER_DAY = 2;

    private final LlmCapabilityResolver llmResolver;
    private final ProfileRenderer profileRenderer;
    private final CandidateSerializer candidateSerializer;
    private final AiLogService aiLogService;
    private final ObjectMapper objectMapper;

    public ItineraryComposer(LlmCapabilityResolver llmResolver,
                             ProfileRenderer profileRenderer,
                             CandidateSerializer candidateSerializer,
                             AiLogService aiLogService,
                             ObjectMapper objectMapper) {
        this.llmResolver = llmResolver;
        this.profileRenderer = profileRenderer;
        this.candidateSerializer = candidateSerializer;
        this.aiLogService = aiLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 编排行程。
     *
     * @param intent       P3-A 的意图（提供天数、节奏、预算、交通、同行人）
     * @param pool         P3-B 的候选池。<b>模型只能从这里选点</b>
     * @param preOrder     P3-C 的空间预排结果，可为 null（则按候选池原序）
     * @param profile      用户画像，可为 null
     * @param overrides    本次临时条件，可为 null
     * @param capability   地图能力，用于决定是否加「禁止精确距离」那一段约束
     * @return 编排结果；失败时 {@code success=false} 且 draft 为 null，但<b>不抛异常</b>
     */
    public ComposeResult compose(IntentDTO intent, CandidatePool pool, PreOrderResult preOrder,
                                 UserTravelProfile profile, ProfileOverrides overrides,
                                 ResolvedMap capability) {
        return compose(intent, pool, preOrder, profile, overrides, capability, null);
    }

    /**
     * 编排行程（带重排反馈）。
     *
     * @param replanFeedback P3-E 的约束校验失败后，把违规项拼成的一段反馈文本；
     *                       非空时会附加在 user prompt 里，让模型「按这些问题改一版」。
     *                       为 null 表示这是首次编排。
     *                       与 {@code violations}（Schema 校验失败的回喂）是两回事：
     *                       那个是「格式不对」，这个是「格式对但内容违反了约束」。
     */
    public ComposeResult compose(IntentDTO intent, CandidatePool pool, PreOrderResult preOrder,
                                 UserTravelProfile profile, ProfileOverrides overrides,
                                 ResolvedMap capability, String replanFeedback) {
        if (intent == null || intent.getDays() == null) {
            // 天数没解析出来就没法分天 —— 这是 P3-A 的 needConfirm 该拦住的情况，
            // 走到这里说明上游漏判了，如实返回失败而不是硬编一个天数
            return ComposeResult.fail(AiErrorCode.SCHEMA_INVALID,
                    "行程天数还没确定（需要用户先确认天数）");
        }
        if (pool == null || pool.isEmpty()) {
            return ComposeResult.fail(AiErrorCode.CANDIDATE_SHORTAGE,
                    "候选池为空，没有可编排的点位");
        }

        // 按 P3-C 的顺序序列化候选池 —— 编号本身就体现了空间顺序，不必再单独给一份排序说明
        List<CandidateDTO> ordered = preOrder != null && preOrder.orderedList() != null
                && !preOrder.orderedList().isEmpty()
                ? preOrder.orderedList() : pool.getItems();

        boolean mapClosed = capability == null || capability.mode() == MapMode.ESTIMATED;
        String systemPrompt = buildSystemPrompt(intent, profile, overrides, mapClosed);
        String baseUserPrompt = buildUserPrompt(intent, ordered, mapClosed);
        if (StringUtils.hasText(replanFeedback)) {
            // 重排：把上一版的违规项附在 user prompt 里。放在 system 之后、正文之前，
            // 是因为它属于「这一次的具体要求」，不是「一直生效的规则」
            baseUserPrompt = baseUserPrompt + "\n\n" + replanFeedback;
        }

        long startMs = System.currentTimeMillis();
        List<LlmUsage> usages = new ArrayList<>();
        List<String> violations = null;
        TripDraftDTO draft = null;
        LlmCapabilityResolver.LlmCallResult<String> lastCall = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && draft == null; attempt++) {
            String userPrompt = attempt == 1
                    ? baseUserPrompt
                    : baseUserPrompt + buildCorrectionBlock(violations);

            AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
            try {
                lastCall = llmResolver.execute(provider -> provider.chatJson(
                        systemPrompt, userPrompt, buildJsonSchemaHint(intent), LlmCallContext.of(usageRef::set)));
            } catch (LlmException e) {
                recordStage(lastCall, usages, System.currentTimeMillis() - startMs,
                        false, toAiErrorCode(e.getResultCode()), e.getMessage());
                return ComposeResult.fail(toAiErrorCode(e.getResultCode()), e.getMessage());
            }
            if (usageRef.get() != null) {
                usages.add(usageRef.get());
            }

            ParseOutcome outcome = parseAndValidate(lastCall.value(), intent, ordered);
            if (outcome.draft() != null) {
                draft = outcome.draft();
                if (attempt > 1) {
                    log.info("行程编排第 {} 次尝试通过（首次失败原因：{}）", attempt, violations);
                }
            } else {
                violations = outcome.violations();
                log.warn("行程编排第 {} 次尝试校验未通过：{}", attempt, violations);
            }
        }

        if (draft == null) {
            String detail = String.join("；", violations == null ? List.of() : violations);
            recordStage(lastCall, usages, System.currentTimeMillis() - startMs,
                    false, AiErrorCode.LLM_PARSE_FAIL,
                    "重试 " + (MAX_ATTEMPTS - 1) + " 次后仍未产出合法行程：" + detail);
            // 关键：这里**不抛异常**，候选池原样留在调用方手里供前端手选
            return ComposeResult.fail(AiErrorCode.LLM_PARSE_FAIL,
                    "行程编排没能生成合法结果（" + detail + "），你可以从候选点位里手动挑选");
        }

        recordStage(lastCall, usages, System.currentTimeMillis() - startMs, true, null, null);
        return ComposeResult.ok(draft);
    }

    // ==================== Prompt ====================

    /**
     * system prompt：把手册的 8 条硬约束逐条写进去。
     *
     * <p><b>每一条都不能省</b>：这些约束是「模型不许碰事实」这条设计在提示词层面的落地，
     * 漏掉任何一条，对应那类幻觉就会重新出现（例如漏了第 3 条，模型就会自己编景点）。
     */
    private String buildSystemPrompt(IntentDTO intent, UserTravelProfile profile,
                                     ProfileOverrides overrides, boolean mapClosed) {
        int days = intent.getDays();
        StringBuilder sb = new StringBuilder();

        sb.append("你是行程编排助手。用户已经确定了目的地与候选点位，")
          .append("你的任务是**把它们分天、排时段、配餐饮、写理由**。\n\n");

        sb.append("【绝对不可违反的约束】\n");
        sb.append("1. 总天数必须**恰好是 ").append(days).append(" 天**，不能多也不能少。\n");
        sb.append("2. 每天至少 ").append(MIN_ITEMS_PER_DAY)
          .append(" 个点；活动安排在 09:00–18:00 之间；午餐排在 11:30–13:00；")
          .append("晚餐排在 18:00 之后（可以超出 18:00）。\n");
        sb.append("3. **只能从下面给出的候选池里选点**，绝对不允许新增任何候选池以外的地名。")
          .append("每个 item 的 poiRef 必须**照抄候选池里的名称**（一字不改地抄），")
          .append("**不要写编号** —— 编号只是给你看空间顺序用的，写成编号系统就认不出是哪个点了。\n");
        sb.append("4. 每个 item 必须写 reason，说明为什么安排在这一天、这个时段。要具体")
          .append("（例如「上午光线适合拍照，且离前一站步行几分钟」），")
          .append("禁止「因为很值得去」这类空话。\n");
        sb.append("5. 【节奏约束】").append(paceRule(intent.getPace())).append('\n');
        sb.append("6. 每天至少安排 1 个餐饮条目（itemType=FOOD），从候选池的餐饮候选里选。\n");
        sb.append("7. 画像里的**忌口过敏是硬约束**，任何餐饮推荐都不得包含这些食材。")
          .append("你必须在每天的 summary 里体现出你记住了用户的偏好")
          .append("（例如「避开香菜，选了几家本地面食馆」）。")
          .append("预算倾向为经济时，餐饮与门票要优先选低价的。\n");

        if (mapClosed) {
            // 地图关闭时的额外约束 —— 这一段是「不让模型编距离」的最后一道防线
            sb.append("8. 你**没有坐标数据**，请按地理常识给出同一区域内相对合理的先后顺序。")
              .append("**禁止输出精确的距离与时长**，如需说明请用「相距不远」「步行约十几分钟」")
              .append("这类模糊表述写在 reason 里。")
              .append("distanceMeters / durationSeconds 字段一律留空（null）。\n");
        } else {
            sb.append("8. 距离与时长由系统另行补全，**你不要输出这两个字段**。\n");
        }

        // 画像块：隐私开关关掉时只注入本次临时条件（铁律 3）
        UserTravelProfile injectable = profileRenderer.shouldInject(profile) ? profile : null;
        String profileBlock = profileRenderer.render(injectable, overrides);
        if (StringUtils.hasText(profileBlock)) {
            sb.append("\n【用户偏好】\n").append(profileBlock).append('\n');
        }

        return sb.toString();
    }

    /** 节奏 → 每天几个点。pace 为 null 时按「适中」处理，并说明这是默认值 */
    private String paceRule(Integer pace) {
        if (pace == null) {
            return "用户没有说明节奏，按「适中」处理：每天 3–4 个点。";
        }
        return switch (pace) {
            case IntentDTO.PACE_SLOW -> "用户要**慢**节奏：每天 2–3 个点，不要排满。";
            case IntentDTO.PACE_NORMAL -> "用户要**适中**节奏：每天 3–4 个点。";
            case IntentDTO.PACE_PACKED -> "用户要**紧凑**节奏：每天 4–5 个点。";
            default -> "用户没有说明节奏，按「适中」处理：每天 3–4 个点。";
        };
    }

    /** user prompt：把「这一次具体要排什么」说清楚 */
    private String buildUserPrompt(IntentDTO intent, List<CandidateDTO> ordered, boolean mapClosed) {
        StringBuilder sb = new StringBuilder();
        sb.append("目的地：").append(intent.getDestination()).append('\n');
        sb.append("行程天数：").append(intent.getDays()).append('\n');
        if (intent.getStartDate() != null) {
            sb.append("出发日期：").append(intent.getStartDate()).append('\n');
        }
        if (intent.getBudgetTotal() != null) {
            sb.append("预算：").append(intent.getBudgetTotal()).append(" 元（")
              .append(IntentDTO.BUDGET_MODE_PER_PERSON.equals(intent.getBudgetMode())
                      ? "人均" : "总计").append("）\n");
        }
        if (StringUtils.hasText(intent.getTransport())) {
            sb.append("交通方式：").append(intent.getTransport()).append('\n');
        }
        if (StringUtils.hasText(intent.getCompanion())) {
            sb.append("同行人：").append(intent.getCompanion()).append('\n');
        }

        sb.append("\n【候选池】（只能从这里选点；**已经按建议的空间顺序排好**，")
          .append("编号即顺序，尽量避免来回折返，必要时可小幅微调。")
          .append("**写 poiRef 时请照抄名称，不要写编号**）\n");
        sb.append(candidateSerializer.serialize(ordered)).append('\n');

        if (mapClosed) {
            sb.append("\n再提醒一次：**不要输出任何精确的距离或分钟数**，")
              .append("需要说明时用「相距不远」这类模糊表述。\n");
        }
        return sb.toString();
    }

    private String buildCorrectionBlock(List<String> violations) {
        StringBuilder sb = new StringBuilder("\n\n【上一次的输出没有通过校验，请修正后重新输出】\n");
        if (violations != null) {
            for (String v : violations) {
                sb.append("- ").append(v).append('\n');
            }
        }
        sb.append("请只输出修正后的 json 对象，不要解释。\n");
        return sb.toString();
    }

    private String buildJsonSchemaHint(IntentDTO intent) {
        return """
                {
                  "title": "建议标题，如 寿阳古建两日慢行",
                  "days": [
                    { "dayIndex": 1, "title": "当天主题", "summary": "当天小结（要体现用户偏好）",
                      "items": [
                        { "poiRef": "候选池里的名称（照抄，不要写编号）", "itemType": "SCENIC",
                          "startTime": "09:00", "endTime": "10:30", "stayMinutes": 90,
                          "costEstimate": 30, "reason": "具体理由" }
                      ] }
                  ]
                }
                """ + "days 数组长度必须恰好 " + intent.getDays() + "；"
                + "itemType 只能是 " + TripItem.TYPE_SCENIC + " 或 " + TripItem.TYPE_FOOD + "；"
                + "startTime / endTime 必须是 HH:mm 格式；"
                + "**poiRef 必须照抄候选池里的名称，不能为空、不能写编号**；"
                + "不要输出 distanceMeters / durationSeconds。";
    }

    // ==================== 解析与校验 ====================

    private record ParseOutcome(TripDraftDTO draft, List<String> violations) {
        static ParseOutcome ok(TripDraftDTO d) { return new ParseOutcome(d, List.of()); }
        static ParseOutcome fail(List<String> v) { return new ParseOutcome(null, v); }
    }

    /**
     * 解析并校验模型输出。
     *
     * <p><b>这里刻意不校验「poiRef 是否真的在候选池里」</b> —— 手册明确要求闭包校验属于 P3-E。
     * 本步只管结构与枚举是否合法（天数、条数、类型、时间格式）。
     * 分工的理由：如果在这里就把池外点打回去，P3-E 的 CLOSURE 规则就成了摆设，
     * 而那条规则恰恰是「防幻觉」最核心的闸门，必须能被独立验证与观测。
     *
     * <p><b>距离与时长一律不读</b>：模型给了也丢弃。它们是事实，只能来自地图。
     */
    private ParseOutcome parseAndValidate(String raw, IntentDTO intent, List<CandidateDTO> pool) {
        List<String> violations = new ArrayList<>();
        String json = extractJsonObject(raw);
        if (json == null) {
            violations.add("输出里找不到 json 对象");
            return ParseOutcome.fail(violations);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            violations.add("json 语法错误：" + e.getMessage());
            return ParseOutcome.fail(violations);
        }
        if (root == null || !root.isObject()) {
            violations.add("json 顶层必须是对象");
            return ParseOutcome.fail(violations);
        }

        TripDraftDTO draft = new TripDraftDTO();
        draft.setTitle(blankToNull(root.path("title").asText(null)));

        JsonNode daysNode = root.path("days");
        if (!daysNode.isArray() || daysNode.isEmpty()) {
            violations.add("days 必须是非空数组");
            return ParseOutcome.fail(violations);
        }

        // 硬约束 1：天数必须恰好等于 intent.days
        if (daysNode.size() != intent.getDays()) {
            violations.add("天数不对：要求恰好 " + intent.getDays() + " 天，你给了 " + daysNode.size() + " 天");
        }

        boolean poolHasFood = pool.stream().anyMatch(CandidateDTO::isFood);
        List<TripDraftDTO.DayDraft> days = new ArrayList<>();
        int daySeq = 0;

        for (JsonNode dayNode : daysNode) {
            daySeq++;
            TripDraftDTO.DayDraft day = new TripDraftDTO.DayDraft();
            day.setDayIndex(dayNode.path("dayIndex").isNumber()
                    ? dayNode.path("dayIndex").asInt() : daySeq);
            day.setTitle(blankToNull(dayNode.path("title").asText(null)));
            day.setSummary(blankToNull(dayNode.path("summary").asText(null)));

            JsonNode itemsNode = dayNode.path("items");
            if (!itemsNode.isArray() || itemsNode.isEmpty()) {
                violations.add("第 " + daySeq + " 天没有任何 item");
                continue;
            }
            if (itemsNode.size() < MIN_ITEMS_PER_DAY) {
                violations.add("第 " + daySeq + " 天只有 " + itemsNode.size()
                        + " 个点，每天至少要有 " + MIN_ITEMS_PER_DAY + " 个");
            }

            List<TripDraftDTO.ItemDraft> items = new ArrayList<>();
            boolean hasFood = false;
            for (JsonNode itemNode : itemsNode) {
                TripDraftDTO.ItemDraft item = parseItem(itemNode, daySeq, violations);
                if (item != null) {
                    items.add(item);
                    if (item.isFood()) {
                        hasFood = true;
                    }
                }
            }
            // 硬约束 6：每天至少 1 个 FOOD。
            // 只在候选池里真有餐饮候选时才校验 —— 池子里没有却要求模型变一个出来，
            // 只会逼它编造，那是本末倒置
            if (poolHasFood && !hasFood) {
                violations.add("第 " + daySeq + " 天没有餐饮条目（每天至少要有 1 个 FOOD）");
            }
            day.setItems(items);
            days.add(day);
        }

        if (!violations.isEmpty()) {
            return ParseOutcome.fail(violations);
        }
        draft.setDays(days);
        return ParseOutcome.ok(draft);
    }

    /** 解析单个 item；结构性错误记进 violations 并返回 null */
    private TripDraftDTO.ItemDraft parseItem(JsonNode node, int daySeq, List<String> violations) {
        String poiRef = node.path("poiRef").asText("").trim();
        if (poiRef.isEmpty()) {
            violations.add("第 " + daySeq + " 天有 item 的 poiRef 为空");
            return null;
        }
        String itemType = node.path("itemType").asText("").trim().toUpperCase();
        if (!TripItem.TYPE_SCENIC.equals(itemType) && !TripItem.TYPE_FOOD.equals(itemType)) {
            violations.add("第 " + daySeq + " 天的「" + poiRef + "」itemType 非法：\""
                    + node.path("itemType").asText("") + "\"，只能是 SCENIC 或 FOOD");
            return null;
        }
        String startTime = node.path("startTime").asText("").trim();
        String endTime = node.path("endTime").asText("").trim();
        if (!TIME_PATTERN.matcher(startTime).matches()) {
            violations.add("第 " + daySeq + " 天的「" + poiRef + "」startTime 格式不对：\"" + startTime
                    + "\"，必须是 HH:mm");
            return null;
        }
        if (!TIME_PATTERN.matcher(endTime).matches()) {
            violations.add("第 " + daySeq + " 天的「" + poiRef + "」endTime 格式不对：\"" + endTime
                    + "\"，必须是 HH:mm");
            return null;
        }
        String reason = node.path("reason").asText("").trim();
        if (reason.isEmpty()) {
            violations.add("第 " + daySeq + " 天的「" + poiRef + "」没有写 reason");
            return null;
        }

        TripDraftDTO.ItemDraft item = new TripDraftDTO.ItemDraft();
        item.setPoiRef(poiRef);
        item.setItemType(itemType);
        item.setStartTime(startTime);
        item.setEndTime(endTime);
        item.setStayMinutes(node.path("stayMinutes").isNumber() ? node.path("stayMinutes").asInt() : null);
        item.setCostEstimate(node.path("costEstimate").isNumber()
                ? node.path("costEstimate").decimalValue() : null);
        item.setReason(reason);
        // ⚠️ distanceMeters / durationSeconds **故意不设**：它们是事实，只能由地图给。
        // 模型输出了也一律丢弃（不是「不采信」而是「不写进去」），由 P3-F 回填。
        return item;
    }

    // ==================== 日志 ====================

    private void recordStage(LlmCapabilityResolver.LlmCallResult<String> call, List<LlmUsage> usages,
                             long durationMs, boolean success, AiErrorCode errorCode, String errorMsg) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        for (LlmUsage u : usages) {
            if (u.promptTokens() != null) {
                promptTokens = (promptTokens == null ? 0 : promptTokens) + u.promptTokens();
            }
            if (u.completionTokens() != null) {
                completionTokens = (completionTokens == null ? 0 : completionTokens) + u.completionTokens();
            }
        }
        aiLogService.recordStage(new AiStageRecord(
                UserContext.getUserId(),
                null,
                AiStageRecord.STAGE_COMPOSE,
                call == null ? null : call.used().providerName(),
                call == null ? null : call.used().model(),
                promptTokens,
                completionTokens,
                (int) durationMs,
                success,
                errorCode,
                errorMsg));
    }

    private AiErrorCode toAiErrorCode(com.wayfare.common.result.ResultCode rc) {
        if (rc == null) {
            return AiErrorCode.LLM_SERVER_ERROR;
        }
        return switch (rc) {
            case LLM_TIMEOUT -> AiErrorCode.LLM_TIMEOUT;
            case LLM_RATE_LIMIT -> AiErrorCode.LLM_RATE_LIMIT;
            case LLM_AUTH_FAIL -> AiErrorCode.LLM_AUTH_FAIL;
            case LLM_PARSE_ERROR, LLM_BAD_REQUEST -> AiErrorCode.LLM_PARSE_FAIL;
            default -> AiErrorCode.LLM_SERVER_ERROR;
        };
    }

    // ==================== 工具 ====================

    /** 与 P3-A 同一套：先剥 markdown 围栏，再取第一个 { 到最后一个 }。容忍包装、不容忍内容 */
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

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
