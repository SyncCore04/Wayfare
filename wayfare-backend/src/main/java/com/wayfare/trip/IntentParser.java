package com.wayfare.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.common.exception.BusinessException;
import com.wayfare.common.result.ResultCode;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.map.MapCapabilityResolver;
import com.wayfare.connector.map.MapMode;
import com.wayfare.connector.map.PoiDTO;
import com.wayfare.connector.map.PoiQueryDTO;
import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.IntentDTO;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.security.UserContext;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 管线 Step 1：意图解析（P3-A）。
 *
 * <p>把「周末想去寿阳玩两天，喜欢古建筑，预算 500」解析成 {@link IntentDTO}。
 * 这一步是整条管线唯一「用户的话 → 结构化需求」的转换点，后面六步都吃它的输出，
 * 所以<b>宁可失败也不要输出一个错误的意图</b> —— 错误的意图会让后面每一步都正确地跑在错误的前提上。
 *
 * <p><b>三道防线，按顺序生效</b>：
 * <ol>
 *   <li><b>提示词约束</b>：要求模型把自己靠猜填的字段名放进 {@code needConfirm}；</li>
 *   <li><b>服务端 Schema 校验</b>：类型 / 枚举 / 必填 / 天数上限，<b>不信任模型的输出</b>；</li>
 *   <li><b>校验失败重试 1 次</b>：把<b>具体的错误信息</b>回喂给模型让它改；
 *       仍失败抛 {@link ResultCode#SCHEMA_INVALID}，<b>绝不静默兜底编一个 IntentDTO</b>。</li>
 * </ol>
 *
 * <p><b>为什么第 3 条必须是「抛异常」而不是「给个默认值」</b>：
 * 一个兜底的默认意图（比如 days=2、destination=原文）看起来让流程"跑通了"，
 * 但它会一路带着错误的需求走到最后，用户拿到一份「按错误前提排出来的行程」，
 * 而且没有任何地方报错。这比直接告诉他「我没听懂，换个说法」糟糕得多。
 *
 * <p>解析阶段<b>不做</b>：不检索候选点（P3-B）、不调用行程编排（P3-D）。
 * 唯一的例外是目的地消歧时的一次地图检索 —— 那是为了把「寿阳」这个字符串变成坐标，
 * 属于解析的一部分（见 {@link #applyDestinationLocation}）。
 */
@Component
public class IntentParser {

    private static final Logger log = LoggerFactory.getLogger(IntentParser.class);

    /** sys_config 键：单次行程最大天数 */
    private static final String KEY_MAX_DAYS = "trip.max-days";
    private static final int DEFAULT_MAX_DAYS = 5;

    /** 最多尝试次数 = 首次 + 重试 1 次（手册规定，不许更多） */
    private static final int MAX_ATTEMPTS = 2;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 纯整数文本，用于容忍模型把数字写成 "2"（但绝不接受 "两天"） */
    private static final Pattern PURE_INT = Pattern.compile("^-?\\d+$");

    private static final String[] WEEKDAY_CN = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private final LlmCapabilityResolver llmResolver;
    private final MapCapabilityResolver mapResolver;
    private final ProfileRenderer profileRenderer;
    private final AiLogService aiLogService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    public IntentParser(LlmCapabilityResolver llmResolver,
                        MapCapabilityResolver mapResolver,
                        ProfileRenderer profileRenderer,
                        AiLogService aiLogService,
                        SysConfigService sysConfigService,
                        ObjectMapper objectMapper) {
        this.llmResolver = llmResolver;
        this.mapResolver = mapResolver;
        this.profileRenderer = profileRenderer;
        this.aiLogService = aiLogService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析用户输入。
     *
     * @param rawInput   用户原话，如「周末想去寿阳玩两天，喜欢古建筑，预算 500」
     * @param profile    用户长期画像，可为 null（未填写）
     * @param overrides  本次临时条件，可为 null
     * @param capability 地图能力决策结果。只有 {@code mode == VERIFIED} 时才会去补目的地坐标；
     *                   为 null 或非 VERIFIED 时坐标保持 null（铁律一：坐标只能来自地图）
     * @return 结构化意图；<b>永不返回 null</b>
     * @throws BusinessException 大模型不可用、或两次都无法产出合法结构时抛出
     */
    public IntentDTO parseIntent(String rawInput,
                                 UserTravelProfile profile,
                                 ProfileOverrides overrides,
                                 ResolvedMap capability) {
        if (!StringUtils.hasText(rawInput)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "行程需求不能为空");
        }

        int maxDays = sysConfigService.getInt(KEY_MAX_DAYS, DEFAULT_MAX_DAYS);
        String systemPrompt = buildSystemPrompt(profile, overrides, maxDays);
        String baseUserPrompt = buildUserPrompt(rawInput);

        long startMs = System.currentTimeMillis();
        List<String> violations = null;
        List<LlmUsage> usages = new ArrayList<>();
        IntentDTO intent = null;
        LlmCapabilityResolver.LlmCallResult<String> lastCall = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && intent == null; attempt++) {
            String userPrompt = attempt == 1
                    ? baseUserPrompt
                    : baseUserPrompt + buildCorrectionBlock(violations);

            AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
            LlmCallContext ctx = LlmCallContext.of(usageRef::set);

            try {
                lastCall = llmResolver.execute(provider ->
                        provider.chatJson(systemPrompt, userPrompt, buildJsonSchemaHint(maxDays), ctx));
            } catch (LlmException e) {
                // 大模型侧的问题（超时/认证/限流）：不是「结构不合法」，如实上报，不要伪装成 SCHEMA_INVALID
                recordStage(lastCall, usages, System.currentTimeMillis() - startMs,
                        false, toAiErrorCode(e.getResultCode()), e.getMessage());
                throw new BusinessException(e.getResultCode(), e.getMessage());
            }

            LlmUsage usage = usageRef.get();
            if (usage != null) {
                usages.add(usage);
            }

            ParseOutcome outcome = parseAndValidate(lastCall.value(), maxDays);
            if (outcome.intent() != null) {
                intent = outcome.intent();
                if (attempt > 1) {
                    log.info("意图解析第 {} 次尝试通过（首次失败原因：{}）", attempt, violations);
                }
            } else {
                violations = outcome.violations();
                log.warn("意图解析第 {} 次尝试校验未通过：{}", attempt, violations);
            }
        }

        if (intent == null) {
            String detail = String.join("；", violations == null ? List.of() : violations);
            recordStage(lastCall, usages, System.currentTimeMillis() - startMs,
                    false, AiErrorCode.LLM_PARSE_FAIL,
                    "重试 " + (MAX_ATTEMPTS - 1) + " 次后仍未产出合法结构：" + detail);
            throw new BusinessException(ResultCode.SCHEMA_INVALID,
                    "没能理解你的行程需求（" + detail + "），请换个说法再试一次");
        }

        // 目的地消歧：把「寿阳」变成坐标。地图不可用时不做，坐标保持 null
        applyDestinationLocation(intent, capability);
        // 预算口径的服务端兜底（见方法注释）
        enforceBudgetModeConfirm(intent);

        recordStage(lastCall, usages, System.currentTimeMillis() - startMs,
                true, null, null);
        return intent;
    }

    // ==================== Prompt 组装 ====================

    /**
     * system prompt：角色 + 硬性要求 + 今天日期 + 画像块 + 本次覆盖。
     *
     * <p>字段说明不放在这里，而是走 {@code jsonSchemaHint} —— 基类的
     * {@code buildJsonSystemPrompt} 会把它拼成「期望的 json 结构如下」，分工更清楚，
     * 也避免同一段说明在两处各写一遍后跑偏。
     */
    private String buildSystemPrompt(UserTravelProfile profile, ProfileOverrides overrides, int maxDays) {
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();

        sb.append("你是旅行需求解析器。把用户的一句话行程需求解析成 json。\n\n");

        sb.append("【今天的日期】\n")
          .append(today.format(DATE_FMT)).append("（").append(weekdayCn(today)).append("）。\n")
          .append("用户说「周末」「明天」「下个月」这类相对时间时，以今天为基准推算成 yyyy-MM-dd。\n")
          .append("「周末」指最近的周六；若今天已经是周六或周日，就指今天。\n\n");

        sb.append("【硬性要求】\n")
          .append("1. 天数必须在 1 ~ ").append(maxDays).append(" 天之间。用户说的天数超了也不要改，如实填，由系统提示他拆分。\n")
          .append("2. 只填用户真的说了的字段。**没有依据就不要填**，宁缺勿猜 —— ")
          .append("你猜出来的一个具体值，比一个空字段危险得多，因为它会被下游当成事实使用。\n")
          .append("   用户没提目的地、没提天数时，**把该字段整个省略**（不要写空字符串 \"\"，不要写 null，更不要编一个地名填上），\n")
          .append("   系统会把它列进 needConfirm 让用户补填。编一个地名是最严重的错误。\n")
          .append("3. 凡是你没有十足依据、只能靠推测填的字段，**必须把它的字段名放进 needConfirm 数组**。\n")
          .append("   典型情况：\n")
          .append("   - 用户说「预算 500」却没说人均还是总计 → needConfirm 里要有 \"budgetMode\"\n")
          .append("   - 用户没提同行人，你却填了 companion → needConfirm 里要有 \"companion\"\n")
          .append("   - 用户没提交通方式，你却填了 transport → needConfirm 里要有 \"transport\"\n")
          .append("   - 目的地名你拿不准是哪个（同名地名）→ needConfirm 里要有 \"destination\"\n")
          .append("   填了值又不标进 needConfirm，等于告诉系统「这是用户说的」，而其实是你猜的。\n")
          .append("4. 用户明确表达过的（如「不吃辣」「带爸妈」「走不动」），要解析成对应字段，不要漏。\n")
          .append("   「走不动」「想轻松点」这类表述 → pace=1（慢）。\n")
          .append("5. dietaryOverrides 只放用户**不吃 / 忌口**的东西（如「不吃辣」「海鲜过敏」）。\n")
          .append("   用户**想吃**的（如「想吃面食」）是偏好，要放进 preferenceTags ——\n")
          .append("   dietaryOverrides 会被下游当作硬约束，把「想吃的东西」放进去反而会把它排除掉。\n")
          .append("6. 用户没提预算时**整个省略 budgetTotal**，绝对不要填 0（0 会被当成「预算为零」）。\n")
          .append("7. 用户**完全没提到日期/时间**时，**省略 startDate** —— 不要自己替他定一个出发日期。\n")
          .append("   （实测过：模型会自作主张填一个日期，而用户从没说过什么时候去。）\n");

        // 画像块：隐私开关关掉时只注入本次临时条件，不注入长期画像（铁律 3）
        UserTravelProfile injectable = profileRenderer.shouldInject(profile) ? profile : null;
        String profileBlock = profileRenderer.render(injectable, overrides);
        if (StringUtils.hasText(profileBlock)) {
            sb.append("\n【用户长期偏好与本次条件】\n").append(profileBlock).append("\n")
              .append("这些是用户的既有偏好，用来补全他没在本次输入里重复说明的部分；\n")
              .append("**但若用户本次的输入与画像冲突，以本次输入为准**。\n")
              .append("画像里的忌口是硬约束，要体现在 dietaryOverrides 里。\n");
        }

        return sb.toString();
    }

    /** user prompt：用户原话。刻意保持极简 —— 约束都在 system 里，这里只放「这一次说了什么」 */
    private String buildUserPrompt(String rawInput) {
        return "用户的需求：" + rawInput.trim();
    }

    /**
     * 重试时的纠错块：把**具体**的校验错误逐条回喂。
     *
     * <p>为什么不能只说「上次输出不合法，请重新输出」：那样模型只能靠猜自己错在哪，
     * 大概率原样再错一遍。必须指出「哪个字段、错成什么、应该是什么」。
     */
    private String buildCorrectionBlock(List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【上一次你的输出没有通过校验，请修正后重新输出】\n");
        if (violations != null) {
            for (String v : violations) {
                sb.append("- ").append(v).append("\n");
            }
        }
        sb.append("请只输出修正后的 json 对象，不要解释。\n");
        return sb.toString();
    }

    /** jsonSchemaHint：字段清单与取值说明，由基类拼进「期望的 json 结构如下」 */
    private String buildJsonSchemaHint(int maxDays) {
        return "{\n"
             + "  \"destination\": \"目的地名称（字符串；用户没说就省略此字段）\",\n"
             + "  \"days\": " + maxDays + ",\n"
             + "  \"startDate\": \"yyyy-MM-dd（用户没说日期就省略此字段）\",\n"
             + "  \"budgetTotal\": 500,\n"
             + "  \"budgetMode\": \"PER_PERSON 或 TOTAL（人均 / 总计）\",\n"
             + "  \"transport\": \"DRIVE 或 PUBLIC 或 WALK 或 MIX\",\n"
             + "  \"companion\": \"同行人，如 爸妈 / 一个人 / 情侣\",\n"
             + "  \"preferenceTags\": [\"古建筑\", \"博物馆\"],\n"
             + "  \"pace\": \"1=慢 2=适中 3=紧凑（数字）\",\n"
             + "  \"dietaryOverrides\": [\"不吃辣\"],\n"
             + "  \"needConfirm\": [\"budgetMode\"],\n"
             + "  \"confidence\": 0.8\n"
             + "}\n"
             + "days 不超过 " + maxDays + "；"
             + "budgetMode 只能是 PER_PERSON 或 TOTAL；transport 只能是 DRIVE/PUBLIC/WALK/MIX；"
             + "pace 只能是 1/2/3。没有依据的字段直接省略，不要填 null，也不要填空字符串。";
    }

    // ==================== 解析与校验 ====================

    /** 解析结果：成功给 intent，失败给 violations，两者必有其一 */
    private record ParseOutcome(IntentDTO intent, List<String> violations) {
        static ParseOutcome ok(IntentDTO dto) { return new ParseOutcome(dto, List.of()); }
        static ParseOutcome fail(List<String> violations) { return new ParseOutcome(null, violations); }
    }

    /**
     * 把模型返回的文本解析成 IntentDTO 并做 Schema 校验。
     *
     * <p><b>刻意不直接把 JSON 反序列化成 DTO</b>：Jackson 的宽松绑定会做各种隐式转换
     * （字符串转数字、大小写、未知字段），而这里恰恰需要「精确知道模型错在哪」——
     * 因为错误信息要回喂给模型。逐字段读 + 逐字段判，才能给出「days 必须是 1~5 的数字，
     * 你给的是 9」这种能真正帮模型改正的提示。
     */
    private ParseOutcome parseAndValidate(String raw, int maxDays) {
        List<String> violations = new ArrayList<>();

        String json = extractJsonObject(raw);
        if (json == null) {
            violations.add("输出里找不到 json 对象（应当直接输出 {...}，不要用 markdown 代码块包裹，也不要加解释文字）");
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
            violations.add("json 的顶层必须是一个对象（{...}），实际是 " + (root == null ? "空" : root.getNodeType()));
            return ParseOutcome.fail(violations);
        }

        IntentDTO dto = new IntentDTO();

        // ---- destination：缺失不判失败（见下方「为什么目的地不是硬性必填」）----
        String destination = readText(root, IntentDTO.FIELD_DESTINATION, violations, false);
        dto.setDestination(destination);

        // ---- days：缺失同样不判失败，但要进 needConfirm ----
        boolean daysRejected = false;
        Integer days = readInt(root, IntentDTO.FIELD_DAYS, violations, false);
        if (days != null) {
            if (days < 1 || days > maxDays) {
                violations.add("days 超出允许范围：你给的是 " + days + "，允许范围是 1 ~ " + maxDays);
                daysRejected = true;
            } else {
                dto.setDays(days);
            }
        }

        // ---- startDate：可选，格式必须对 ----
        String startDateText = readText(root, IntentDTO.FIELD_START_DATE, violations, false);
        if (StringUtils.hasText(startDateText)) {
            try {
                dto.setStartDate(LocalDate.parse(startDateText.trim(), DATE_FMT));
            } catch (DateTimeParseException e) {
                violations.add("startDate 格式不对：你给的是 \"" + startDateText + "\"，必须是 yyyy-MM-dd");
            }
        }

        // ---- budgetTotal：可选，不能为负；0 视作「未提供」 ----
        BigDecimal budget = readDecimal(root, IntentDTO.FIELD_BUDGET_TOTAL, violations);
        if (budget != null) {
            if (budget.signum() < 0) {
                violations.add("budgetTotal 不能是负数：你给的是 " + budget);
            } else if (budget.signum() == 0) {
                // 实测发现：用户没提预算时，模型会填 budgetTotal=0 当「不知道」的占位符。
                // 若把 0 当成真预算，P3-E 的 BUDGET_EXCEED 会认为「任何花费都超支」，
                // 用户会看到莫名其妙的超支提示。所以 0 一律按「未提供」处理。
                log.warn("模型返回 budgetTotal=0，视作未提供（0 元预算的行程需求不存在）");
            } else {
                dto.setBudgetTotal(budget);
            }
        }

        // ---- budgetMode：可选，枚举 ----
        String budgetMode = readText(root, IntentDTO.FIELD_BUDGET_MODE, violations, false);
        if (StringUtils.hasText(budgetMode)) {
            String normalized = budgetMode.trim().toUpperCase();
            if (!IntentDTO.BUDGET_MODES.contains(normalized)) {
                violations.add("budgetMode 取值非法：你给的是 \"" + budgetMode + "\"，只能是 "
                        + IntentDTO.BUDGET_MODES + "（人均 / 总计）");
            } else {
                dto.setBudgetMode(normalized);
            }
        }

        // ---- transport：可选，枚举 ----
        String transport = readText(root, IntentDTO.FIELD_TRANSPORT, violations, false);
        if (StringUtils.hasText(transport)) {
            String normalized = transport.trim().toUpperCase();
            if (!IntentDTO.TRANSPORTS.contains(normalized)) {
                violations.add("transport 取值非法：你给的是 \"" + transport + "\"，只能是 " + IntentDTO.TRANSPORTS);
            } else {
                dto.setTransport(normalized);
            }
        }

        // ---- companion：可选 ----
        dto.setCompanion(readText(root, IntentDTO.FIELD_COMPANION, violations, false));

        // ---- preferenceTags：可选数组 ----
        dto.setPreferenceTags(readStringList(root, "preferenceTags", violations));

        // ---- pace：可选，1/2/3 ----
        Integer pace = readInt(root, IntentDTO.FIELD_PACE, violations, false);
        if (pace != null) {
            if (!IntentDTO.PACES.contains(pace)) {
                violations.add("pace 取值非法：你给的是 " + pace + "，只能是 1（慢）/ 2（适中）/ 3（紧凑）");
            } else {
                dto.setPace(pace);
            }
        }

        // ---- dietaryOverrides：可选数组 ----
        dto.setDietaryOverrides(readStringList(root, "dietaryOverrides", violations));

        // ---- needConfirm：可选数组 ----
        dto.setNeedConfirm(readStringList(root, "needConfirm", violations));

        // ---- 目的地 / 天数缺失：进 needConfirm，而不是判失败 ----
        // 为什么目的地不是硬性必填（与任务块的「destination 必填」有意不同，理由在此）：
        //   用户完全可以只说「两天，带爸妈，走不动，想轻松点，想吃面食」而不说去哪 ——
        //   任务块给的三条验收输入里，第三条就是这样。
        //   若把它判成校验失败，重试时模型为了让校验通过，**只能凭空编一个目的地**，
        //   这正好违反铁律一（事实数据永不来自大模型）和提示词里「没有依据就不要填」的要求。
        //   两害相权：让解析成功、把 destination 标进 needConfirm 交给确认表单，是唯一不逼模型撒谎的做法。
        //   （任务块验收 1 要求三条输入都能跑通、验收 4 要求输入3 能拿到 pace 与 companion，
        //    也只有在允许目的地缺失的前提下才成立。）
        if (dto.getDestination() == null) {
            addNeedConfirm(dto, IntentDTO.FIELD_DESTINATION);
        }
        if (dto.getDays() == null && !daysRejected) {
            addNeedConfirm(dto, IntentDTO.FIELD_DAYS);
        }
        // 但两个核心字段同时缺失，说明这次解析基本没产出东西，不能伪装成「成功但全空」
        if (dto.getDestination() == null && dto.getDays() == null) {
            violations.add("destination 与 days 都没有解析出来，无法据此规划行程");
        }

        // ---- confidence：可选，0~1 ----
        Double confidence = readDouble(root, "confidence", violations);
        if (confidence != null) {
            if (confidence < 0 || confidence > 1) {
                violations.add("confidence 必须在 0 ~ 1 之间：你给的是 " + confidence);
            } else {
                dto.setConfidence(confidence);
            }
        }

        // ---- shortageHint：解析阶段不该出现（P3-B 才写），给了就忽略，不算错误 ----
        if (!violations.isEmpty()) {
            return ParseOutcome.fail(violations);
        }
        return ParseOutcome.ok(dto);
    }

    // ==================== 目的地消歧 ====================

    /**
     * 用地图检索目的地，验证存在性并补上中心坐标。
     *
     * <p><b>只在 {@code mode == VERIFIED} 时做</b>：地图关闭或走缓存时坐标保持 null，
     * 交给下游标记 ESTIMATED —— 这是铁律一在解析阶段的落点。
     * 绝不允许在这里用模型给的坐标顶替（模型给的坐标一律不采信，DTO 里也没有它的位置）。
     *
     * <p>检索不到时把 {@code destination} 记进 needConfirm，让前端把目的地输入框标出来请用户确认。
     * 这里放的是<b>字段名</b>而不是一句中文说明：needConfirm 的契约是「字段名列表」，
     * 前端靠它定位到具体表单项；混进自由文本会让前端无法处理。
     */
    private void applyDestinationLocation(IntentDTO intent, ResolvedMap capability) {
        if (capability == null || capability.mode() != MapMode.VERIFIED) {
            log.debug("地图非 VERIFIED（{}），跳过目的地坐标补全，destLng/destLat 保持 null",
                    capability == null ? "capability 为 null" : capability.mode());
            return;
        }

        PoiQueryDTO query = new PoiQueryDTO();
        query.setCity(intent.getDestination());
        query.setKeyword(intent.getDestination());
        query.setPageSize(1);

        MapCapabilityResolver.MapCallResult<List<PoiDTO>> result =
                mapResolver.call(p -> p.searchPoi(query), list -> list != null && !list.isEmpty());

        PoiDTO hit = null;
        if (result.mode() == MapMode.VERIFIED && result.value() != null) {
            hit = result.value().stream().filter(PoiDTO::hasLocation).findFirst().orElse(null);
        }

        if (hit == null) {
            log.warn("目的地「{}」在地图上检索不到（mode={}，reason={}），记入 needConfirm",
                    intent.getDestination(), result.mode(), result.reason());
            addNeedConfirm(intent, IntentDTO.FIELD_DESTINATION);
            return;
        }

        intent.setDestLng(hit.lng());
        intent.setDestLat(hit.lat());
        log.debug("目的地「{}」命中地图：{} ({}, {})",
                intent.getDestination(), hit.name(), hit.lng(), hit.lat());
    }

    /**
     * 预算口径的服务端兜底。
     *
     * <p><b>为什么要在服务端再补一刀</b>：模型被要求「猜的字段要标进 needConfirm」，
     * 但它完全可能填了 {@code budgetMode} 却没标 —— 这时我们<b>无法核验</b>用户到底说没说口径。
     * 而口径不是小事：500 元「人均」和 500 元「总计」在 P3-E 的预算校验里差好几倍，
     * 会得出完全相反的超支结论。所以只要填了预算，就一律让用户确认口径。
     *
     * <p>代价是「用户明确说了人均」时也会多出一行确认 —— 但那是<b>可撤销的打扰</b>，
     * 而错误的预算口径是<b>看不出来的错误</b>。两害相权取其轻。
     *
     * <p>注意这里只补 {@code needConfirm}，<b>不改动任何业务字段</b>，更不会失败重试 ——
     * 它是个提示增强，不是校验规则。
     */
    private void enforceBudgetModeConfirm(IntentDTO intent) {
        if (intent.getBudgetTotal() == null) {
            return;
        }
        if (intent.getNeedConfirm() != null && intent.getNeedConfirm().contains(IntentDTO.FIELD_BUDGET_MODE)) {
            return;
        }
        log.debug("填了预算但未确认口径，补一条 needConfirm");
        addNeedConfirm(intent, IntentDTO.FIELD_BUDGET_MODE);
    }

    /** 往 needConfirm 里加一个字段名（去重、保持顺序、容忍 null） */
    private void addNeedConfirm(IntentDTO intent, String field) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (intent.getNeedConfirm() != null) {
            set.addAll(intent.getNeedConfirm());
        }
        set.add(field);
        intent.setNeedConfirm(new ArrayList<>(set));
    }

    // ==================== 日志 ====================

    /**
     * 写 PARSE 阶段日志。
     *
     * <p><b>token 取的是所有尝试的累加</b>：一次解析可能调了两遍模型（首次 + 重试），
     * 两遍都真花了钱，只记最后一遍会低估成本。耗时同理，记整段。
     * 这与「{@code ai_generation_log} 一个阶段一条」的约定一致 ——
     * 一次阶段调用对应多条 {@code external_call_log}，两者不是一一对应。
     */
    private void recordStage(LlmCapabilityResolver.LlmCallResult<String> call,
                             List<LlmUsage> usages,
                             long durationMs,
                             boolean success,
                             AiErrorCode errorCode,
                             String errorMsg) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        // 一次 usage 都没拿到 → 两个都保持 null，不要写 0（0 会被成本统计算成「没花钱」）
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
                // 原先这里写死 null，注释还说「解析阶段还没有 tripId」—— 那句是错的：
                // 草稿在 Step1 之前就已落库（P3-F 的设计）。写死 null 会让 PARSE 的成本归不到行程上
                TripRunContext.getTripId(),
                AiStageRecord.STAGE_PARSE,
                call == null ? null : call.used().providerName(),
                call == null ? null : call.used().model(),
                promptTokens,
                completionTokens,
                (int) durationMs,
                success,
                errorCode,
                errorMsg));
    }

    /**
     * ResultCode → AiErrorCode 的映射。
     *
     * <p>两者视角不同（前者给用户、后者给运维），大部分能一一对上；
     * 对不上的（如「没有可用厂商」）落到 LLM_SERVER_ERROR，
     * 真实原因由 {@code errorMsg} 带出来 —— 宁可归因粗一点，也不新造枚举值
     * （AiErrorCode 的 12 个码是与《开发文档》§14.4 对齐的，不能随意增删）。
     */
    private AiErrorCode toAiErrorCode(ResultCode rc) {
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

    // ==================== 取值工具 ====================

    /**
     * 从模型输出里抠出 JSON 对象。
     *
     * <p>模型经常会「多说一句」或用 markdown 代码块包起来 —— 尽管提示词明确禁止了。
     * 与其为此重试一次（白花钱），不如在这里容忍掉：先剥代码块围栏，
     * 再取第一个 <code>{</code> 到最后一个 <code>}</code> 之间的内容。
     * <b>容忍的是包装，不是内容</b> —— 抠出来的东西仍然要过完整校验。
     */
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

    /** 读文本字段。必填但缺失/为空 → 记一条违规 */
    private String readText(JsonNode root, String field, List<String> violations, boolean required) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                violations.add("缺少必填字段 " + field);
            }
            return null;
        }
        if (!node.isTextual()) {
            violations.add(field + " 必须是字符串，实际是 " + describeType(node));
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            if (required) {
                violations.add("必填字段 " + field + " 是空字符串");
            }
            return null;
        }
        return value;
    }

    /**
     * 读整数字段。
     *
     * <p>容忍 {@code "2"} 这种被引号包起来的数字（模型很常见），
     * 但<b>不容忍</b> {@code "两天"} —— 那说明模型没有真的给出数量，
     * 放过去会让下游拿到一个瞎猜的天数。
     */
    private Integer readInt(JsonNode root, String field, List<String> violations, boolean required) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                violations.add("缺少必填字段 " + field);
            }
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual() && PURE_INT.matcher(node.asText().trim()).matches()) {
            return Integer.valueOf(node.asText().trim());
        }
        violations.add(field + " 必须是数字，实际是 " + describeType(node)
                + (node.isTextual() ? "（\"" + node.asText() + "\"）" : ""));
        return null;
    }

    /** 读浮点字段；容忍 "0.8" 这种字符串数字 */
    private Double readDouble(JsonNode root, String field, List<String> violations) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.valueOf(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 落到下面报违规
            }
        }
        violations.add(field + " 必须是数字，实际是 " + describeType(node));
        return null;
    }

    /** 读金额字段。用 BigDecimal 而不是 double —— 金额不该有二进制浮点误差 */
    private BigDecimal readDecimal(JsonNode root, String field, List<String> violations) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException ignored) {
                // 落到下面报违规
            }
        }
        violations.add(field + " 必须是数字，实际是 " + describeType(node));
        return null;
    }

    /**
     * 读字符串数组。
     *
     * <p>容忍「只给了一个字符串」这种退化输出（单元素数组语义），
     * 但数组里的每一项都必须是字符串 —— 出现对象/嵌套数组说明模型理解错了结构。
     */
    private List<String> readStringList(JsonNode root, String field, List<String> violations) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String only = node.asText().trim();
            return only.isEmpty() ? null : new ArrayList<>(List.of(only));
        }
        if (!node.isArray()) {
            violations.add(field + " 必须是字符串数组，实际是 " + describeType(node));
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                violations.add(field + " 数组里必须是字符串，出现了 " + describeType(item));
                return null;
            }
            String value = item.asText().trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : values;
    }

    /** 给违规信息用的类型描述 —— 要能让人一眼看懂模型给的是什么 */
    private String describeType(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isTextual()) return "字符串";
        if (node.isIntegralNumber()) return "整数";
        if (node.isNumber()) return "小数";
        if (node.isArray()) return "数组";
        if (node.isObject()) return "对象";
        if (node.isBoolean()) return "布尔值";
        return node.getNodeType().toString();
    }

    private String weekdayCn(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return WEEKDAY_CN[dow.getValue() - 1];
    }
}
