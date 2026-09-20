package com.wayfare.trip;

import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管线 Step 5：约束校验（P3-E）—— <b>纯本地规则，不调大模型、不调地图</b>。
 *
 * <p><b>这是防幻觉的第二道闸门，也是最后一道。</b>第一道是 P3-B 的封闭候选池 +
 * P3-D 的「只能从池里选点」提示词；但那两道都只是<b>请求</b>模型别编 ——
 * 模型完全可以不听。只有这里的 {@link #RULE_CLOSURE} 是<b>保证</b>：
 * 它逐条回查每个 {@code poiRef} 是否真的在候选池里，查不到就是 HIGH 违规、必须重排。
 *
 * <p><b>七条规则各自独立成一个方法</b>（手册要求），这样每条都能单独构造正例/反例来测 ——
 * 而「单独可测」在这里特别重要：这些规则的判定逻辑全是我自己写的阈值与比较，
 * 一旦写错（比如把 {@code >} 写成 {@code <}），整条行程的可信度就没了，而界面上看不出来。
 *
 * <p><b>本类不做的事</b>：不调地图补全距离（那是 P3-F）。所以需要距离的地方
 * （折返、通勤、密度）在<b>无坐标时一律跳过</b>，绝不拿「没有数据」当「数据为 0」用 ——
 * 那会把「未知」误判成「很近」，反而放过了真正的问题。
 */
@Component
public class ItineraryValidator {

    private static final Logger log = LoggerFactory.getLogger(ItineraryValidator.class);

    // ---- 规则名（写进 Violation.code，别手写字符串）----
    public static final String RULE_CLOSURE = "CLOSURE";
    public static final String RULE_TIME_OVERLAP = "TIME_OVERLAP";
    public static final String RULE_BACKTRACK = "BACKTRACK";
    public static final String RULE_DETOUR = "DETOUR";
    public static final String RULE_BUDGET_EXCEED = "BUDGET_EXCEED";
    public static final String RULE_TABOO = "TABOO";
    public static final String RULE_TOO_DENSE = "TOO_DENSE";

    /** 活动窗口 */
    private static final LocalTime ACTIVITY_START = LocalTime.of(9, 0);
    private static final LocalTime ACTIVITY_END = LocalTime.of(18, 0);

    /**
     * 单段折返阈值（米）。<b>MIX 取最宽松的 40km</b>：
     * 混合出行里可能包含机动车段，用步行阈值去卡会误报。
     */
    private static final Map<String, Integer> BACKTRACK_LIMIT_M = Map.of(
            "DRIVE", 40_000,
            "WALK", 5_000,
            "RIDING", 15_000,
            "PUBLIC", 40_000,
            "MIX", 40_000);

    /** 单日通勤时长上限（分钟） */
    private static final int DETOUR_DRIVE_LIMIT_MIN = 120;
    private static final int DETOUR_WALK_LIMIT_MIN = 40;

    /** 超支容差：10% 以内只提示，超过就要求重排 */
    private static final double BUDGET_TOLERANCE = 0.10;

    /**
     * 估算速度（km/h）。<b>P3-E 不调地图，所以通勤时长只能估</b> ——
     * 这几个数字只用于「判断是不是明显不合理」，不对外输出，也不是事实数据。
     * 真正的时长由 P3-F 回填。
     */
    private static final Map<String, Double> SPEED_KMH = Map.of(
            "DRIVE", 40.0, "WALK", 5.0, "PUBLIC", 25.0, "RIDING", 15.0, "MIX", 40.0);

    /**
     * 跑全部七条规则。
     *
     * @param draft      P3-D 产出的行程草稿
     * @param intent     P3-A 的意图（提供预算、口径、交通方式、同行人）
     * @param pool       P3-B 的候选池（CLOSURE 的比对基准）
     * @param profile    用户画像，可为 null
     * @param overrides  本次临时条件，可为 null
     * @return 校验报告；<b>永不返回 null</b>，全通过时 {@code passed=true} 且 violations 为空
     */
    public ValidationReport validate(TripDraftDTO draft, IntentDTO intent, CandidatePool pool,
                                     UserTravelProfile profile, ProfileOverrides overrides) {
        if (draft == null || draft.getDays() == null || draft.getDays().isEmpty()) {
            return new ValidationReport(List.of(
                    Violation.high(RULE_CLOSURE, null, null,
                            "行程草稿是空的，没有任何一天", "请重新生成完整行程")));
        }

        List<Violation> violations = new ArrayList<>();
        violations.addAll(checkClosure(draft, pool));
        violations.addAll(checkTimeOverlap(draft));
        violations.addAll(checkBacktrack(draft, intent, pool));
        violations.addAll(checkDetour(draft, intent, pool));
        violations.addAll(checkBudget(draft, intent));
        violations.addAll(checkTaboo(draft, profile, overrides));
        violations.addAll(checkDensity(draft, profile, pool));

        ValidationReport report = new ValidationReport(violations);
        if (!report.passed()) {
            log.info("约束校验未通过：{}", report.toUserHint());
        }
        return report;
    }

    // ==================== 1) CLOSURE 闭包（★最重要）====================

    /**
     * 每个 item 的 {@code poiRef} 必须在候选池中找到（按名称或 uid，容忍空格与全半角差异）。
     *
     * <p><b>这条规则的唯一使命是识破大模型编造的景点。</b>找不到就是 HIGH ——
     * 不是「可能有问题」，而是「这个点根本不存在于我们检索到的真实数据里」。
     *
     * <p>匹配策略：先做归一化后的<b>精确相等</b>，再退一步做<b>包含</b>匹配。
     * 退这一步是必要的：模型偶尔会把「寿阳文庙」写成「寿阳文庙（正门）」，
     * 那不算编造。但包含匹配要求较短的一方至少 2 个字，避免单字误匹配。
     */
    List<Violation> checkClosure(TripDraftDTO draft, CandidatePool pool) {
        List<Violation> violations = new ArrayList<>();
        if (pool == null || pool.isEmpty()) {
            return violations;   // 池子为空时 P3-D 根本不该产出草稿，这里不重复报
        }
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (TripDraftDTO.ItemDraft item : day.getItems()) {
                if (matchesPool(item.getPoiRef(), pool)) {
                    continue;
                }
                violations.add(Violation.high(RULE_CLOSURE, day.getDayIndex(), item.getPoiRef(),
                        "「" + item.getPoiRef() + "」不在候选池里 —— 这可能是模型编造的点位",
                        "请从候选池里换一个真实的点位，不要新增池外的地名"));
            }
        }
        return violations;
    }

    /** poiRef 是否能在候选池里找到（名称归一化后精确相等，或互为包含） */
    private boolean matchesPool(String poiRef, CandidatePool pool) {
        if (!StringUtils.hasText(poiRef)) {
            return false;
        }
        String ref = TabooMatcher.normalize(poiRef);
        for (CandidateDTO candidate : pool.getItems()) {
            if (candidate.getPoiUid() != null && candidate.getPoiUid().equals(poiRef)) {
                return true;   // 按 uid 命中
            }
            if (!StringUtils.hasText(candidate.getName())) {
                continue;
            }
            String name = TabooMatcher.normalize(candidate.getName());
            if (name.equals(ref)) {
                return true;
            }
            String shorter = name.length() <= ref.length() ? name : ref;
            String longer = name.length() <= ref.length() ? ref : name;
            if (shorter.length() >= 2 && longer.contains(shorter)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 2) TIME_OVERLAP 时序 ====================

    /**
     * 同一天内：{@code startTime} 严格递增、相邻项不重叠、不超出 09:00–18:00 窗口。
     *
     * <p><b>晚餐豁免 18:00 上限</b>：晚餐本来就该排在 18:00 之后，拿窗口去卡它是自相矛盾。
     */
    List<Violation> checkTimeOverlap(TripDraftDTO draft) {
        List<Violation> violations = new ArrayList<>();
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            List<TripDraftDTO.ItemDraft> items = day.getItems();
            if (items == null || items.size() < 1) {
                continue;
            }
            LocalTime prevStart = null;
            LocalTime prevEnd = null;
            String prevRef = null;

            for (TripDraftDTO.ItemDraft item : items) {
                LocalTime start = parseTime(item.getStartTime());
                LocalTime end = parseTime(item.getEndTime());
                if (start == null || end == null) {
                    // 格式问题在 P3-D 已经拦过一次；这里再兜一层，避免 NPE
                    violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                            "时间格式无法解析（" + item.getStartTime() + "–" + item.getEndTime() + "）",
                            "请写成 HH:mm 格式"));
                    continue;
                }
                if (!end.isAfter(start)) {
                    violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                            "结束时间 " + item.getEndTime() + " 不晚于开始时间 " + item.getStartTime(),
                            "请让结束时间晚于开始时间"));
                }
                if (prevEnd != null) {
                    if (start.isBefore(prevEnd)) {
                        violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                                "与上一项「" + prevRef + "」时间重叠（上一项到 " + prevEnd + "，本项 " + start + " 开始）",
                                "请把本项的开始时间排到上一项结束之后"));
                    } else if (!start.isAfter(prevStart)) {
                        violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                                "开始时间没有晚于上一项", "同一天的时间必须严格递增"));
                    }
                }
                // 窗口检查：晚餐（FOOD 且 18:00 之后开始）豁免上限
                boolean dinner = item.isFood() && !start.isBefore(ACTIVITY_END);
                if (start.isBefore(ACTIVITY_START)) {
                    violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                            "开始时间 " + start + " 早于活动窗口 09:00", "请把活动安排在 09:00–18:00 之间"));
                }
                if (!dinner && end.isAfter(ACTIVITY_END)) {
                    violations.add(Violation.medium(RULE_TIME_OVERLAP, day.getDayIndex(), item.getPoiRef(),
                            "结束时间 " + end + " 超出活动窗口 18:00",
                            "请压缩时长或调整开始时间；只有晚餐可以排在 18:00 之后"));
                }
                prevStart = start;
                prevEnd = end;
                prevRef = item.getPoiRef();
            }
        }
        return violations;
    }

    private LocalTime parseTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ==================== 3) BACKTRACK 折返 ====================

    /**
     * 相邻两项的直线距离超过阈值 → HIGH。
     *
     * <p><b>无坐标一律跳过</b>：地图关闭时所有点都没有坐标，
     * 拿「没有数据」当「距离为 0」会把真正的问题掩盖掉。
     */
    List<Violation> checkBacktrack(TripDraftDTO draft, IntentDTO intent, CandidatePool pool) {
        List<Violation> violations = new ArrayList<>();
        String transport = StringUtils.hasText(intent.getTransport()) ? intent.getTransport() : "MIX";
        int limit = BACKTRACK_LIMIT_M.getOrDefault(transport, 40_000);

        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            List<TripDraftDTO.ItemDraft> items = day.getItems();
            if (items == null || items.size() < 2) {
                continue;
            }
            for (int i = 1; i < items.size(); i++) {
                double[] a = locationOf(items.get(i - 1).getPoiRef(), pool);
                double[] b = locationOf(items.get(i).getPoiRef(), pool);
                if (a == null || b == null) {
                    continue;   // 无坐标 → 跳过
                }
                double distance = PreOrderService.haversine(a[1], a[0], b[1], b[0]);
                if (distance > limit) {
                    violations.add(Violation.high(RULE_BACKTRACK, day.getDayIndex(), items.get(i).getPoiRef(),
                            "从「" + items.get(i - 1).getPoiRef() + "」到本项直线距离约 "
                                    + Math.round(distance / 1000) + " 公里，超过 "
                                    + (limit / 1000) + " 公里的折返阈值",
                            "请把这两项的顺序调近，或换一个顺路的点位"));
                }
            }
        }
        return violations;
    }

    // ==================== 4) DETOUR 单日通勤总量 ====================

    /**
     * 单日累计通勤时长超限 → MEDIUM。
     *
     * <p><b>时长是估算的</b>（P3-E 不调地图）：用直线距离 ÷ 固定速度。
     * 这几个速度只用于「判断是否明显不合理」，不对外输出、也不是事实数据 ——
     * 真正的时长由 P3-F 回填。
     */
    List<Violation> checkDetour(TripDraftDTO draft, IntentDTO intent, CandidatePool pool) {
        List<Violation> violations = new ArrayList<>();
        String transport = StringUtils.hasText(intent.getTransport()) ? intent.getTransport() : "MIX";
        boolean drive = "DRIVE".equals(transport) || "MIX".equals(transport) || "PUBLIC".equals(transport);
        int limitMin = drive ? DETOUR_DRIVE_LIMIT_MIN : DETOUR_WALK_LIMIT_MIN;
        double speed = SPEED_KMH.getOrDefault(transport, 40.0);

        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            List<TripDraftDTO.ItemDraft> items = day.getItems();
            if (items == null || items.size() < 2) {
                continue;
            }
            double totalMeters = 0;
            boolean anySegment = false;
            for (int i = 1; i < items.size(); i++) {
                double[] a = locationOf(items.get(i - 1).getPoiRef(), pool);
                double[] b = locationOf(items.get(i).getPoiRef(), pool);
                if (a == null || b == null) {
                    continue;
                }
                totalMeters += PreOrderService.haversine(a[1], a[0], b[1], b[0]);
                anySegment = true;
            }
            if (!anySegment) {
                continue;   // 无坐标 → 跳过
            }
            int minutes = (int) Math.round(totalMeters / 1000.0 / speed * 60);
            if (minutes > limitMin) {
                violations.add(Violation.medium(RULE_DETOUR, day.getDayIndex(), null,
                        "第 " + day.getDayIndex() + " 天在路上要花约 " + minutes
                                + " 分钟（按直线距离估算），超过 " + limitMin + " 分钟",
                        "请减少当天的跨区点位，或把相近的点排在一起"));
            }
        }
        return violations;
    }

    // ==================== 5) BUDGET_EXCEED 预算 ====================

    /**
     * 花费之和 vs 预算：超支 ≤10% → LOW（提示）；>10% → MEDIUM。
     *
     * <p><b>人均口径需要人数才能换算，而同行人是自由文本</b>（「爸妈」「一个人」），
     * 所以这里从 {@link IntentDTO#getCompanion()} 里估一个人数。
     * <b>估不准时按 1 人算</b> —— 保守方向：更容易报超支，宁可多提醒也不要漏报。
     * 没有预算（{@code budgetTotal} 为 null）时整条规则跳过。
     */
    List<Violation> checkBudget(TripDraftDTO draft, IntentDTO intent) {
        List<Violation> violations = new ArrayList<>();
        BigDecimal budget = intent.getBudgetTotal();
        if (budget == null || budget.signum() <= 0) {
            return violations;
        }
        BigDecimal spent = BigDecimal.ZERO;
        boolean anyCost = false;
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (TripDraftDTO.ItemDraft item : day.getItems()) {
                if (item.getCostEstimate() != null) {
                    spent = spent.add(item.getCostEstimate());
                    anyCost = true;
                }
            }
        }
        if (!anyCost) {
            return violations;   // 一个费用都没给，没法比，不报「超支 0 元」这种废话
        }

        boolean perPerson = IntentDTO.BUDGET_MODE_PER_PERSON.equals(intent.getBudgetMode());
        int partySize = perPerson ? estimatePartySize(intent.getCompanion()) : 1;
        BigDecimal effectiveBudget = perPerson
                ? budget.multiply(BigDecimal.valueOf(partySize)) : budget;

        BigDecimal over = spent.subtract(effectiveBudget);
        if (over.signum() <= 0) {
            return violations;
        }
        double ratio = over.doubleValue() / effectiveBudget.doubleValue();
        String scope = perPerson
                ? "（按人均 " + budget + " 元 × 估算 " + partySize + " 人）"
                : "（总计 " + budget + " 元）";

        if (ratio > BUDGET_TOLERANCE) {
            violations.add(Violation.medium(RULE_BUDGET_EXCEED, null, null,
                    "预计花费 " + spent + " 元，超出预算约 " + Math.round(ratio * 100) + "%" + scope,
                    "请优先安排免费或低价的点位，并把高价项目替换掉"));
        } else {
            violations.add(Violation.low(RULE_BUDGET_EXCEED, null, null,
                    "预计花费 " + spent + " 元，略超预算 " + Math.round(ratio * 100) + "%" + scope,
                    "可以保留，若想控制成本可把其中一项换成免费点位"));
        }
        return violations;
    }

    /**
     * 从同行人文本估人数。
     *
     * <p>刻意保守：认不出来就按 1 人。宁可把人均预算当成人均 1 人来比、多报几次超支，
     * 也不要因为估大了人数而漏报超支。
     */
    private int estimatePartySize(String companion) {
        if (!StringUtils.hasText(companion)) {
            return 1;
        }
        String text = companion.trim();
        if (text.contains("一家") || text.contains("全家") || text.contains("家庭")) {
            return 4;
        }
        if (text.contains("爸妈") || text.contains("父母") || text.contains("三人") || text.contains("3人")) {
            return 3;
        }
        if (text.contains("情侣") || text.contains("两人") || text.contains("二人") || text.contains("2人")) {
            return 2;
        }
        return 1;
    }

    // ==================== 6) TABOO 忌口（★硬约束）====================

    /**
     * 任何 item 的名称或理由里出现忌口食材 → HIGH，必须重排。
     *
     * <p><b>为什么连 reason 也要查</b>：模型可能在理由里写「这家的香菜牛肉面很有名」——
     * 名字里没有忌口词，但推荐本身就是在推荐用户不能吃的东西。
     *
     * <p>匹配用的是 {@link TabooMatcher}（与 P3-B 同一套归一化），
     * 所以「不吃辣」能拦住「麻辣火锅」。**字面匹配的局限见该类的说明。**
     */
    List<Violation> checkTaboo(TripDraftDTO draft, UserTravelProfile profile, ProfileOverrides overrides) {
        List<Violation> violations = new ArrayList<>();
        List<String> taboos = TabooMatcher.collect(profile, overrides == null ? null : overrides.getTaboos());
        if (taboos.isEmpty()) {
            return violations;
        }
        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            if (day.getItems() == null) {
                continue;
            }
            for (TripDraftDTO.ItemDraft item : day.getItems()) {
                String hit = TabooMatcher.hitTerm(item.getPoiRef(), taboos);
                if (hit == null) {
                    hit = TabooMatcher.hitTerm(item.getReason(), taboos);
                }
                if (hit != null) {
                    violations.add(Violation.high(RULE_TABOO, day.getDayIndex(), item.getPoiRef(),
                            "「" + item.getPoiRef() + "」命中忌口「" + hit + "」——忌口是硬约束，必须换掉",
                            "请换成不含「" + hit + "」的餐饮点位；候选池里的餐饮候选可能都不合适时，"
                                    + "请优先选口味清淡、可要求不加该食材的店，并在 reason 里说明"));
                }
            }
        }
        return violations;
    }

    // ==================== 7) TOO_DENSE 点位密度 ====================

    /**
     * 当日移动总量 vs 用户能承受的步行上限 → MEDIUM。
     *
     * <p>用「当日各段直线距离之和」当移动量的估算（P3-E 不调地图）。
     * 用户没设 {@code walkLimitKm} 时整条规则跳过 —— 没有上限就没有「超标」可言。
     */
    List<Violation> checkDensity(TripDraftDTO draft, UserTravelProfile profile, CandidatePool pool) {
        List<Violation> violations = new ArrayList<>();
        if (profile == null || profile.getWalkLimitKm() == null || profile.getWalkLimitKm() <= 0) {
            return violations;
        }
        double limitMeters = profile.getWalkLimitKm() * 1000.0;

        for (TripDraftDTO.DayDraft day : draft.getDays()) {
            List<TripDraftDTO.ItemDraft> items = day.getItems();
            if (items == null || items.size() < 2) {
                continue;
            }
            double total = 0;
            boolean any = false;
            for (int i = 1; i < items.size(); i++) {
                double[] a = locationOf(items.get(i - 1).getPoiRef(), pool);
                double[] b = locationOf(items.get(i).getPoiRef(), pool);
                if (a == null || b == null) {
                    continue;
                }
                total += PreOrderService.haversine(a[1], a[0], b[1], b[0]);
                any = true;
            }
            if (!any) {
                continue;
            }
            if (total > limitMeters) {
                violations.add(Violation.medium(RULE_TOO_DENSE, day.getDayIndex(), null,
                        "第 " + day.getDayIndex() + " 天移动约 " + Math.round(total / 1000)
                                + " 公里，超过你设置的单日上限 " + profile.getWalkLimitKm() + " 公里",
                        "请减少当天的点位数量，或把距离远的点换掉"));
            }
        }
        return violations;
    }

    // ==================== 工具 ====================

    /**
     * 从候选池里取某点的坐标（经度、纬度）。
     *
     * <p>返回 {@code null} 表示「拿不到坐标」—— 调用方必须<b>跳过</b>该段计算，
     * 不能当成 0 距离。这是「无坐标一律跳过」这条纪律的唯一落点。
     */
    private double[] locationOf(String poiRef, CandidatePool pool) {
        if (!StringUtils.hasText(poiRef) || pool == null) {
            return null;
        }
        for (CandidateDTO candidate : pool.getItems()) {
            if (!candidate.hasLocation() || !StringUtils.hasText(candidate.getName())) {
                continue;
            }
            String name = TabooMatcher.normalize(candidate.getName());
            String ref = TabooMatcher.normalize(poiRef);
            if (name.equals(ref) || name.contains(ref) || ref.contains(name)) {
                return new double[]{candidate.getLng(), candidate.getLat()};
            }
        }
        return null;
    }

    /** 供 P3-E 的重排循环构造纠正 prompt 用的规则清单（便于文档与调试输出） */
    public static Map<String, String> ruleDescriptions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(RULE_CLOSURE, "点位必须在候选池内（识破编造景点）");
        map.put(RULE_TIME_OVERLAP, "同天时间严格递增、不重叠、不超 09:00–18:00 窗口");
        map.put(RULE_BACKTRACK, "相邻两段距离不超过交通方式对应的阈值");
        map.put(RULE_DETOUR, "单日累计通勤时长不超上限");
        map.put(RULE_BUDGET_EXCEED, "花费不超过预算（容差 10%）");
        map.put(RULE_TABOO, "不得出现忌口食材（硬约束）");
        map.put(RULE_TOO_DENSE, "单日移动量不超过用户设定的上限");
        return map;
    }
}
