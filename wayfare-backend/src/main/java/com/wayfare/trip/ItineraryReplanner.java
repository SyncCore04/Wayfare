package com.wayfare.trip;

import com.wayfare.connector.map.ResolvedMap;
import com.wayfare.dto.CandidateDTO;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.security.UserContext;
import com.wayfare.service.AiLogService;
import com.wayfare.service.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 约束校验 + 回喂重排的循环（P3-E 的编排部分）。
 *
 * <p>{@link ItineraryValidator} 只负责「判」；本类负责「判完之后怎么办」：
 * 把违规项拼成纠正 prompt 回喂给 {@link ItineraryComposer} 重排，直到通过或撞上轮次上限。
 *
 * <h3>三条不容妥协的纪律</h3>
 * <ol>
 *   <li><b>必须有硬性轮次上限，绝不允许死循环</b>（手册明确要求，P7 会用超时断言验证）。
 *       上限来自 {@code trip.max-replan-rounds}（默认 2）。</li>
 *   <li><b>撞上限仍不通过时不抛异常</b>，返回<b>当前最优版本</b> + 违规清单。
 *       一份「有一个点偏远」的行程对用户仍然有用，抛异常会把它整份丢掉。</li>
 *   <li><b>保留的是「最优」而不是「最后一版」</b>：模型重排有可能越改越差
 *       （比如把 CLOSURE 修好了却引入了折返），所以每轮都跟历史最优比，
 *       比较口径是「先比 HIGH 数、再比 MEDIUM 数、最后比总数」（见 {@link ValidationReport#severitySignature}）。</li>
 * </ol>
 *
 * <p><b>关于日志</b>：每轮重排都会调一次 {@code ItineraryComposer}，而它内部已经写了一条
 * COMPOSE 阶段日志，所以「每轮重排都留痕」是自动满足的，本类不重复记。
 * 本类额外写的是 <b>VALIDATE 阶段</b>日志（本地校验不是 LLM 调用，没有 token，但有轮次与结论）。
 */
@Component
public class ItineraryReplanner {

    private static final Logger log = LoggerFactory.getLogger(ItineraryReplanner.class);

    /** sys_config 键：最多重排几轮 */
    private static final String KEY_MAX_REPLAN_ROUNDS = "trip.max-replan-rounds";
    private static final int DEFAULT_MAX_REPLAN_ROUNDS = 2;

    /** 纠正 prompt 的分隔符 —— 手册要求用明确的分隔避免与正文格式错乱 */
    private static final String DELIMITER = "===== 需要修正的问题（begin）=====";
    private static final String DELIMITER_END = "===== 需要修正的问题（end）=====";

    private final ItineraryValidator validator;
    private final ItineraryComposer composer;
    private final SysConfigService sysConfigService;
    private final AiLogService aiLogService;

    public ItineraryReplanner(ItineraryValidator validator,
                              ItineraryComposer composer,
                              SysConfigService sysConfigService,
                              AiLogService aiLogService) {
        this.validator = validator;
        this.composer = composer;
        this.sysConfigService = sysConfigService;
        this.aiLogService = aiLogService;
    }

    /**
     * 编排 → 校验 → （不通过则）回喂重排 → 再校验，直到通过或撞上轮次上限。
     *
     * @return 最终结果；<b>永不抛异常</b>。编排阶段就失败时 {@code draft} 为 null，
     *         调用方应把候选池交给前端手选
     */
    public ReplanResult enforce(IntentDTO intent, CandidatePool pool, PreOrderResult preOrder,
                                UserTravelProfile profile, ProfileOverrides overrides,
                                ResolvedMap capability) {
        ComposeResult first = composer.compose(intent, pool, preOrder, profile, overrides, capability);
        if (!first.success()) {
            return ReplanResult.composeFailed(first.errorMessage());
        }

        TripDraftDTO best = first.draft();
        ValidationReport bestReport = validate(best, intent, pool, profile, overrides, 0);

        int maxRounds = sysConfigService.getInt(KEY_MAX_REPLAN_ROUNDS, DEFAULT_MAX_REPLAN_ROUNDS);
        int rounds = 0;

        while (!bestReport.passed() && shouldReplan(bestReport, rounds) && rounds < maxRounds) {
            rounds++;
            String feedback = buildFeedback(bestReport, pool, rounds, maxRounds);
            log.info("第 {} / {} 轮重排：{}", rounds, maxRounds, bestReport.toUserHint());

            ComposeResult again = composer.compose(intent, pool, preOrder, profile, overrides,
                    capability, feedback);
            if (!again.success()) {
                log.warn("第 {} 轮重排的编排本身失败了（{}），保留当前最优版本", rounds, again.errorMessage());
                break;
            }

            TripDraftDTO candidate = again.draft();
            ValidationReport candidateReport = validate(candidate, intent, pool, profile, overrides, rounds);

            if (isBetter(candidateReport, bestReport)) {
                best = candidate;
                bestReport = candidateReport;
            } else {
                // 没变好也继续下一轮：模型换个说法可能就修好了，而硬上限保证不会死循环。
                // 但**保留最优**——不能因为「最后一版」而丢掉前面更好的那一版
                log.info("第 {} 轮重排没有变得更好，继续尝试（仍保留历史最优）", rounds);
            }
        }

        if (!bestReport.passed()) {
            log.warn("重排 {} 轮后仍有 {}，返回当前最优版本 + 风险提示（不死循环、不抛异常）",
                    rounds, bestReport.toUserHint());
        }
        return ReplanResult.of(best, bestReport, rounds);
    }

    /**
     * 这一轮要不要重排。
     *
     * <ul>
     *   <li>有 HIGH → 必须重排（编造景点、违反忌口这类错误不能带着走）；</li>
     *   <li>只有 MEDIUM → <b>默认重排一次</b>（手册的默认行为），第二轮起不再为它们消耗调用；</li>
     *   <li>只有 LOW → 不重排（轻微超支不值得再花一次大模型调用）。</li>
     * </ul>
     */
    private boolean shouldReplan(ValidationReport report, int rounds) {
        if (report.hasHigh()) {
            return true;
        }
        if (rounds > 0) {
            return false;
        }
        return report.countOf(Violation.SEVERITY_MEDIUM) > 0;
    }

    /**
     * 构造纠正 prompt 正文。
     *
     * <p>除了逐条列出违规，<b>遇到 CLOSURE 违规时还会把候选池名单再贴一遍</b> ——
     * 那是整条管线里最要命的一类错误（模型编了不存在的点），
     * 而模型犯这个错往往是因为它「忘了池子里有什么」，把名单摆到眼前最有效。
     */
    private String buildFeedback(ValidationReport report, CandidatePool pool, int round, int maxRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append(DELIMITER).append('\n');
        sb.append("上一版行程存在以下问题（第 ").append(round).append(" / ").append(maxRounds)
          .append(" 轮修正），请修正后**重新输出完整 JSON**：\n");
        sb.append(report.toFeedbackText()).append('\n');

        boolean hasClosure = report.violations().stream()
                .anyMatch(v -> ItineraryValidator.RULE_CLOSURE.equals(v.code()));
        if (hasClosure && pool != null && !pool.isEmpty()) {
            sb.append("\n特别注意：上面标了 CLOSURE 的点位**不在候选池里**，请从候选池里换一个。")
              .append("候选池是：")
              .append(pool.getItems().stream()
                      .map(CandidateDTO::getName)
                      .filter(n -> n != null && !n.isBlank())
                      .collect(Collectors.joining("、")))
              .append('\n');
        }
        sb.append(DELIMITER_END);
        return sb.toString();
    }

    /**
     * 新版是否比当前最优更好。
     *
     * <p>比较口径：<b>HIGH 数 → MEDIUM 数 → 总数</b>，逐级比较、先小者胜。
     * 不能只比总数：一版 1 个 HIGH 另一版 3 个 LOW，总数上前者少，
     * 但前者是「编造了景点」这种不能接受的错误，后者只是「多花了几十块」。
     */
    private boolean isBetter(ValidationReport candidate, ValidationReport current) {
        List<Integer> a = candidate.severitySignature();
        List<Integer> b = current.severitySignature();
        for (int i = 0; i < a.size(); i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return false;   // 完全一样时算「没有变好」，保留原版
    }

    /** 跑一次校验并留一条 VALIDATE 阶段日志（本地校验没有 token，只记轮次与结论） */
    private ValidationReport validate(TripDraftDTO draft, IntentDTO intent, CandidatePool pool,
                                      UserTravelProfile profile, ProfileOverrides overrides, int round) {
        ValidationReport report = validator.validate(draft, intent, pool, profile, overrides);
        try {
            aiLogService.recordStage(new AiStageRecord(
                    UserContext.getUserId(),
                    null,
                    AiStageRecord.STAGE_VALIDATE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    report.passed(),
                    report.passed() ? null : AiErrorCode.VALIDATION_FAILED,
                    report.passed() ? null : ("第 " + round + " 轮校验：" + report.toUserHint()
                            + "；" + report.toFeedbackText().replace('\n', ' '))));
        } catch (Exception e) {
            // 记日志失败不该影响主流程（与 AiLogService 的取舍一致）
            log.warn("写 VALIDATE 阶段日志失败（不影响主流程）: {}", e.getMessage());
        }
        return report;
    }
}
