package com.wayfare.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmCapabilityResolver;
import com.wayfare.connector.llm.LlmException;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmUsage;
import com.wayfare.connector.llm.ResolvedLlm;
import com.wayfare.dto.IntentDTO;
import com.wayfare.dto.TripDraftDTO;
import com.wayfare.entity.Trip;
import com.wayfare.entity.TripDay;
import com.wayfare.entity.TripItem;
import com.wayfare.entity.UserTravelProfile;
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.profile.ProfileRenderer;
import com.wayfare.security.LoginUser;
import com.wayfare.security.UserContext;
import com.wayfare.trip.AiErrorCode;
import com.wayfare.trip.AiStageRecord;
import com.wayfare.trip.StreamCancellation;
import com.wayfare.trip.TripCopyPrompt;
import com.wayfare.trip.TripOrchestrator;
import com.wayfare.trip.TripProgressListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行程生成的 SSE 通道（P4-A）。
 *
 * <p><b>为什么必须流式</b>：P3-F 联调实测同步接口 {@code /plan/sync} 一次要 194~507 秒
 * （主力模型屡次 90 秒超时后降级，每次白等 90 秒）。浏览器与 axios 的默认超时都撑不到，
 * 用户只会看到一个转圈然后失败。流式把「等 5 分钟」拆成「15 秒先看到行程骨架」，
 * 剩下的时间用文案增量填满。
 *
 * <p><b>两类事件的分工（降级出口）</b>：
 * <ul>
 *   <li>{@code itinerary} —— Step6 事实补全完成即推送，行程骨架（含可信度角标）已经可用；</li>
 *   <li>{@code delta} —— 之后的攻略文案增量。<b>文案失败不影响已推送的行程</b>，
 *       这就是手册说的「降级出口」。</li>
 * </ul>
 *
 * <p><b>不占 Tomcat 请求线程</b>：整条管线 + 文案流都在本类的独立线程池里跑，
 * 请求线程只负责返回 {@link SseEmitter}。
 *
 * <p><b>客户端断开必须能掐断大模型</b>：见 {@link StreamCancellation} —— 断开后
 * 增量回调会抛异常，把上游的 SSE 读取循环炸开并关闭连接，不再白花钱生成。
 *
 * <p><b>P4-B 已补齐文案环节</b>：prompt 换成完整实现（见 {@link TripCopyPrompt}，含画像融入与
 * 「地图关闭时不许写距离时间数字」的强约束），生成成功后写回 {@code trip.guide_text}，
 * 并新增 {@code POST /trip/{id}/regenerate-copy} —— 只重生成文案、不重跑管线。
 * {@code done} 事件里带 {@code copyProvider / copyModel / copyChars}，供双厂商文案效果对比。
 */
@Service
public class TripStreamService {

    private static final Logger log = LoggerFactory.getLogger(TripStreamService.class);

    // ---- 事件名：与《开发文档》§7.1 逐字一致，前端按这个解析 ----
    private static final String EVENT_STAGE = "stage";
    private static final String EVENT_ITINERARY = "itinerary";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /**
     * emitter 超时默认值 = 10 分钟。
     *
     * <p>⚠️ <b>这是对手册数字的一处实测性偏离</b>（手册写「emitter timeout 设 5 分钟」）：
     * P4-A 联调实测一次生成要 <b>194~507 秒</b>（骨架本身就要 3 次大模型调用，
     * 主力模型屡次在 90 秒超时后才降级到备用厂商），5 分钟会在文案流中途把连接掐断，
     * <b>{@code done} 事件永远发不出来</b>。所以默认放宽到 10 分钟，并做成 L2 可调
     * （{@code sys_config} 的 {@code trip.sse-timeout-ms}，改完不重启即生效）。
     */
    private static final int DEFAULT_SSE_TIMEOUT_MS = 10 * 60 * 1000;

    /** L2 配置键：SSE 通道超时（毫秒） */
    private static final String KEY_SSE_TIMEOUT_MS = "trip.sse-timeout-ms";

    /** {@code trip_item} 的 arrive_time / leave_time 是 LocalTime，渲染成 "HH:mm" 给文案 prompt */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // 文案 prompt 已移到 TripCopyPrompt（P4-B）：那边是零依赖纯函数，可直接单测

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final TripOrchestrator orchestrator;
    private final LlmCapabilityResolver llmResolver;
    private final AiLogService aiLogService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;
    private final TripService tripService;
    private final TravelProfileService travelProfileService;
    private final ProfileRenderer profileRenderer;

    /**
     * 独立线程池（手册参数：core 4 / max 8 / queue 50 / 前缀 wayfare-trip-sse-）。
     *
     * <p>两处取舍写在这里免得以后被「顺手优化」掉：
     * <ol>
     *   <li><b>队列 50 会让 max 8 基本用不上</b>：线程池先填队列再扩线程，
     *       所以并发真正能到 8 得等队列坐满。这是手册给的参数，照用，但不假装它是 8 路并发。</li>
     *   <li><b>拒绝策略用 AbortPolicy 而不是 CallerRunsPolicy</b>：后者会把任务丢回
     *       调用线程执行 —— 在 Web 场景下就是拿 Tomcat 请求线程去跑几分钟的生成，直接把容器拖死。
     *       宁可拒绝并对前端如实回一条 error。</li>
     * </ol>
     */
    private final ThreadPoolExecutor executor;

    public TripStreamService(TripOrchestrator orchestrator,
                             LlmCapabilityResolver llmResolver,
                             AiLogService aiLogService,
                             SysConfigService sysConfigService,
                             ObjectMapper objectMapper,
                             TripService tripService,
                             TravelProfileService travelProfileService,
                             ProfileRenderer profileRenderer) {
        this.orchestrator = orchestrator;
        this.llmResolver = llmResolver;
        this.aiLogService = aiLogService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
        this.tripService = tripService;
        this.travelProfileService = travelProfileService;
        this.profileRenderer = profileRenderer;
        this.executor = new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "wayfare-trip-sse-" + THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 开一条 SSE 通道：立刻返回 emitter，生成过程在线程池里跑。
     *
     * @param userId 必须由调用方在<b>请求线程</b>里取好（UserContext 是 ThreadLocal，
     *               线程池里读不到），这也是本方法把 userId 当参数而不是自己取的原因
     */
    public SseEmitter start(Long userId, String rawInput, boolean useProfile, ProfileOverrides overrides) {
        int timeoutMs = resolveTimeoutMs();
        SseEmitter emitter = new SseEmitter((long) timeoutMs);
        StreamCancellation cancellation = new StreamCancellation();
        registerDisconnect(emitter, cancellation, timeoutMs);

        // 请求线程里把身份取下来：生成跑在线程池，那里读不到 UserContext 的 ThreadLocal。
        // 少带这一步的后果实测过：PARSE/CANDIDATE/COMPOSE 的 ai_generation_log.user_id 全成 NULL
        //（那些组件从 UserContext 取用户），连带 meta.tokens 也为 null。
        LoginUser loginUser = UserContext.get();

        try {
            executor.execute(() -> generate(emitter, cancellation, loginUser, userId, rawInput, useProfile, overrides));
        } catch (RejectedExecutionException e) {
            log.warn("SSE 线程池已满，拒绝本次生成任务");
            send(emitter, cancellation, EVENT_ERROR,
                    errorData("SERVER_BUSY", "当前排队太多，请稍后再试"));
            completeQuietly(emitter);
        }
        return emitter;
    }

    /**
     * 重新生成攻略文案（P4-B · {@code POST /trip/{id}/regenerate-copy}）。
     *
     * <p><b>刻意不重跑管线</b>：行程已经在库里了，用户只是对文案不满意（或上次文案失败）。
     * 重跑一遍要 3 次大模型调用、几分钟、全量 token；只重生成文案是 1 次 ——
     * 这就是这个接口存在的全部价值：文案失败不该逼用户重花一遍全量 token。
     *
     * <p>同样返回 {@link SseEmitter}，与 {@code /plan/stream} 共用一套事件协议
     * （{@code stage} → {@code delta} → {@code done} / {@code error}），前端只需一套解析逻辑。
     * 这里<b>不会</b>再推 {@code itinerary} —— 行程没变，没有新骨架可推。
     *
     * @param providerName 指定厂商（{@code qwen} / {@code glm} / {@code deepseek}），
     *                     用于双模型文案效果对比；为空时走常规降级链。
     *                     指定时<b>不降级</b>：不可用就如实报错，否则两份「对比文案」
     *                     可能出自同一家，对比形同虚设（见 {@link LlmCapabilityResolver#resolveFor}）。
     */
    public SseEmitter regenerateCopy(Long userId, Long tripId, String providerName) {
        int timeoutMs = resolveTimeoutMs();
        SseEmitter emitter = new SseEmitter((long) timeoutMs);
        StreamCancellation cancellation = new StreamCancellation();
        registerDisconnect(emitter, cancellation, timeoutMs);

        // 与 start 同理：身份必须在请求线程里取好，池线程里读不到 ThreadLocal
        LoginUser loginUser = UserContext.get();
        try {
            executor.execute(() -> regenerate(emitter, cancellation, loginUser, userId, tripId, providerName));
        } catch (RejectedExecutionException e) {
            log.warn("SSE 线程池已满，拒绝本次文案重试任务");
            send(emitter, cancellation, EVENT_ERROR,
                    errorData("SERVER_BUSY", "当前排队太多，请稍后再试"));
            completeQuietly(emitter);
        }
        return emitter;
    }

    /**
     * 超时走 L2 配置：运维改 {@code trip.sse-timeout-ms} 后新开的长连接立即用新值，无需重启。
     */
    private int resolveTimeoutMs() {
        Integer configured = sysConfigService.getInt(KEY_SSE_TIMEOUT_MS, DEFAULT_SSE_TIMEOUT_MS);
        return configured == null || configured <= 0 ? DEFAULT_SSE_TIMEOUT_MS : configured;
    }

    /**
     * 登记三条断开探测路径：只靠 {@code send} 失败往往要等到下一次发送才发现
     * （P4-A 实测：断开落在一个 119 秒的 LLM 调用里时，延迟 104 秒才检测到）。
     */
    private void registerDisconnect(SseEmitter emitter, StreamCancellation cancellation, int timeoutMs) {
        emitter.onCompletion(() -> cancellation.markCancelled("客户端断开（连接已结束）"));
        emitter.onError(e -> cancellation.markCancelled("客户端断开（" + e.getClass().getSimpleName() + "）"));
        emitter.onTimeout(() -> cancellation.markCancelled("SSE 连接超时（" + (timeoutMs / 60000) + " 分钟）"));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== 生成主体（线程池里执行）====================

    private void generate(SseEmitter emitter, StreamCancellation cancellation, LoginUser loginUser,
                          Long userId, String rawInput, boolean useProfile, ProfileOverrides overrides) {
        // 在池线程里重建身份，让下游组件（它们都从 UserContext 取用户）与同步路径行为一致。
        // ⚠️ 必须在 finally 里 clear：线程会被复用，残留身份会让下一个任务认错人。
        UserContext.set(loginUser);
        try {
            doGenerate(emitter, cancellation, userId, rawInput, useProfile, overrides);
        } finally {
            UserContext.clear();
        }
    }

    private void doGenerate(SseEmitter emitter, StreamCancellation cancellation, Long userId,
                            String rawInput, boolean useProfile, ProfileOverrides overrides) {
        TripProgressListener listener = new TripProgressListener() {
            @Override
            public void onStage(String stage, String status, String message) {
                send(emitter, cancellation, EVENT_STAGE, stageData(stage, status, message));
            }

            @Override
            public void onItinerary(TripDraftDTO draft) {
                send(emitter, cancellation, EVENT_ITINERARY, draft);
            }
        };

        try {
            TripOrchestrator.OrchestrationOutcome out =
                    orchestrator.orchestrate(userId, rawInput, useProfile, overrides, listener);

            if (cancellation.isCancelled()) {
                // 骨架都没人要了，就别再花钱生成文案（这是省钱的关键分支）
                recordDisconnected(userId, out.tripId(), 0, cancellation);
                completeQuietly(emitter);
                return;
            }

            if (out.draft() == null) {
                // 编排没产出可用行程：如实报错，但仍发 done —— 前端要拿到 tripId 与 meta，
                // 草稿已落库（status=0），用户可以续作
                send(emitter, cancellation, EVENT_ERROR, errorData(
                        AiErrorCode.VALIDATION_FAILED.name(),
                        StringUtils.hasText(out.composeError())
                                ? out.composeError() : "没能生成可用行程，请换个说法再试"));
                sendDone(emitter, cancellation, out, null);
                completeQuietly(emitter);
                return;
            }

            // P4-B：画像文本块只在这里渲染一次 —— 铁律三的两道闸（有内容 + 隐私开关）
            // 都在 ProfileRenderer.shouldInject 里，关掉画像时这里返回空串，prompt 里不会出现画像段
            String profileBlock = renderProfileBlock(userId, useProfile, overrides);
            CopyOutcome copy = streamCopy(emitter, cancellation, userId, out.tripId(),
                    out.draft(), out.intent(), profileBlock, null);

            if (cancellation.isCancelled()) {
                completeQuietly(emitter);
                return;
            }
            sendDone(emitter, cancellation, out, copy);
            completeQuietly(emitter);
        } catch (StreamCancellation.Aborted e) {
            // 预期路径：客户端断开后由增量回调抛出，用来掐断数据库/网络读取
            log.info("生成已被中断：{}", e.getMessage());
            completeQuietly(emitter);
        } catch (Exception e) {
            log.warn("SSE 生成任务异常终止：{}", e.getMessage());
            send(emitter, cancellation, EVENT_ERROR,
                    errorData("PIPELINE_FAILED", "生成过程中断了，已收到的行程不受影响"));
            completeQuietly(emitter);
        }
    }

    /**
     * 重新生成文案的主体（线程池里执行）：从库读行程 → 复用 {@link #streamCopy}。
     *
     * <p>它<b>不碰编排管线</b>，所以既不会推 {@code itinerary}，也不会产生
     * PARSE / CANDIDATE / COMPOSE 的阶段日志 —— 手册验收第 4 条要的正是
     * 「{@code ai_generation_log} 里只有 COPY 一条、token 明显低于全量生成」。
     */
    private void regenerate(SseEmitter emitter, StreamCancellation cancellation, LoginUser loginUser,
                            Long userId, Long tripId, String providerName) {
        UserContext.set(loginUser);
        try {
            Trip trip = tripService.getDetail(tripId, userId);
            if (trip == null) {
                send(emitter, cancellation, EVENT_ERROR, errorData("NOT_FOUND", "行程不存在"));
                completeQuietly(emitter);
                return;
            }

            ResolvedLlm forced = null;
            if (StringUtils.hasText(providerName)) {
                try {
                    forced = llmResolver.resolveFor(providerName.trim());
                } catch (LlmException e) {
                    // 指定厂商不可用：如实报错，不退化成「随便找一家」——
                    // 那样两份「对比文案」可能同源，而且完全看不出问题
                    send(emitter, cancellation, EVENT_ERROR,
                            errorData(e.getResultCode().name(), e.getMessage()));
                    completeQuietly(emitter);
                    return;
                }
            }

            TripDraftDTO draft = toDraft(trip);
            IntentDTO intent = readIntent(trip.getIntentJson());
            // 沿用当初的画像开关：那条行程生成时没用画像，重试时也不该突然用上
            boolean useProfile = trip.getProfileUsed() != null && trip.getProfileUsed() == 1;
            String profileBlock = renderProfileBlock(userId, useProfile, new ProfileOverrides());

            send(emitter, cancellation, EVENT_STAGE, stageData(AiStageRecord.STAGE_COPY,
                    TripProgressListener.STATUS_RUNNING, "正在重写攻略文案"));

            CopyOutcome copy = streamCopy(emitter, cancellation, userId, tripId,
                    draft, intent, profileBlock, forced);

            if (cancellation.isCancelled()) {
                completeQuietly(emitter);
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tripId", tripId);
            if (copy != null) {
                data.put("copyProvider", copy.providerName());
                data.put("copyModel", copy.modelName());
                data.put("copyChars", copy.chars());
            }
            send(emitter, cancellation, EVENT_DONE, data);
            completeQuietly(emitter);
        } catch (StreamCancellation.Aborted e) {
            log.info("文案重试已被中断：{}", e.getMessage());
            completeQuietly(emitter);
        } catch (Exception e) {
            log.warn("文案重试异常终止：{}", e.getMessage());
            send(emitter, cancellation, EVENT_ERROR,
                    errorData("COPY_FAILED", "文案重新生成失败，原文案不受影响"));
            completeQuietly(emitter);
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 攻略文案流式生成（P4-B：完整 prompt + 画像注入 + {@code guide_text} 落库）。
     *
     * <p>刻意<b>不走 {@code llmResolver.execute()}</b>：那是给非流式调用做逐家降级的。
     * 流式一旦已经把部分文案推给前端，换一家重来会让用户看到重复文案 ——
     * 决策器自己的注释也是这么说的。所以这里只用候选链的第一家，
     * 失败就发 error 事件（行程已在 itinerary 事件里推过了，不受影响）。
     *
     * <p>{@code forced} 非空（{@code regenerate-copy} 点名了厂商）时直接用它、不做降级，
     * 理由见 {@link LlmCapabilityResolver#resolveFor}。
     *
     * @return 成功时返回文案结果；失败 / 被中断 / 无可用厂商时返回 null（错误事件已发过）
     */
    private CopyOutcome streamCopy(SseEmitter emitter, StreamCancellation cancellation, Long userId,
                                   Long tripId, TripDraftDTO draft, IntentDTO intent,
                                   String profileBlock, ResolvedLlm forced) {
        ResolvedLlm resolved = forced;
        if (resolved == null) {
            try {
                resolved = llmResolver.resolve();
            } catch (LlmException e) {
                send(emitter, cancellation, EVENT_ERROR,
                        errorData(e.getResultCode().name(), e.getMessage()));
                return null;
            }
        }
        LlmProvider provider = resolved.provider();

        // 有没有可靠的距离事实，决定 system prompt 里那句「绝对不许写数字」要不要上强度
        boolean estimated = TripCopyPrompt.estimatedMode(draft);
        String systemPrompt = TripCopyPrompt.systemPrompt(estimated);
        String userPrompt = TripCopyPrompt.userPrompt(draft, intent, profileBlock);

        LlmUsage[] usageRef = new LlmUsage[1];
        Throwable[] errorRef = new Throwable[1];
        // 落库要完整文案，所以自己攒一份（cancellation 那边只数字符，不存内容）
        StringBuilder text = new StringBuilder();
        long startMs = System.currentTimeMillis();

        try {
            provider.chatStream(systemPrompt, userPrompt,
                    LlmCallContext.of(u -> usageRef[0] = u),
                    piece -> {
                        // 先记账再判取消：已产出的字符数要如实统计（P4-C 会用它估算节省量）
                        cancellation.countProduced(piece);
                        cancellation.checkCancelled();
                        text.append(piece);
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("text", piece);
                        send(emitter, cancellation, EVENT_DELTA, data);
                    },
                    () -> { },
                    t -> errorRef[0] = t);
        } catch (Exception e) {
            errorRef[0] = e;
        }

        int durationMs = (int) (System.currentTimeMillis() - startMs);

        if (cancellation.isCancelled()) {
            // ★ 成本控制落点：写一条 success=0 + CLIENT_DISCONNECTED，说明「已产出多少、为什么没有 token 数」。
            // 中断时**不落库**：半截文案比没有文案更糟 —— 用户会以为这就是完整攻略
            recordDisconnected(userId, tripId, durationMs, cancellation);
            return null;
        }
        if (errorRef[0] != null) {
            AiErrorCode code = toAiErrorCode(errorRef[0]);
            aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_COPY,
                    provider.name(), safeModel(provider), null, null, durationMs, false, code,
                    errorRef[0].getMessage()));
            send(emitter, cancellation, EVENT_ERROR,
                    errorData(code.name(), "文案生成失败（行程已保留，可稍后重试）"));
            return null;
        }
        aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_COPY,
                provider.name(), safeModel(provider),
                usageRef[0] == null ? null : usageRef[0].promptTokens(),
                usageRef[0] == null ? null : usageRef[0].completionTokens(),
                durationMs, true, null, null));

        // 写回 trip.guide_text：重进详情页不必重新生成（手册 P4-B 第 4 条）。
        // 落库失败只记日志 —— 文案已经推给前端了，不能因为写库失败把它变成一次「失败」
        String copy = text.toString().trim();
        try {
            tripService.updateGuideText(tripId, userId, copy);
        } catch (Exception e) {
            log.warn("攻略文案写回 trip.guide_text 失败（文案已推送）：tripId={} {}", tripId, e.getMessage());
        }
        return new CopyOutcome(provider.name(), safeModel(provider), copy.length());
    }

    // ==================== 发送与收尾 ====================

    /**
     * 发一条事件。<b>发送失败即判定客户端已断开</b>并标记取消 ——
     * 这是「数秒内停止生成」的触发点。
     */
    private void send(SseEmitter emitter, StreamCancellation cancellation, String event, Object data) {
        if (cancellation.isCancelled()) {
            return; // 已知断开，不必再往 socket 里塞
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            cancellation.markCancelled("客户端断开（发送 " + event + " 事件失败：" + e.getClass().getSimpleName() + "）");
            // 这里立刻打一条：断开必须「数秒内可见」，不能等管线跑完才记录
            log.info("检测到客户端断开：发送 {} 事件失败（{}）", event, e.getMessage());
        } catch (Exception e) {
            log.warn("发送 {} 事件异常：{}", event, e.getMessage());
        }
    }

    /** done 的 data = tripId + meta（rounds / mapMode / durationMs / tokens / estCost 都在 meta 里） */
    private void sendDone(SseEmitter emitter, StreamCancellation cancellation,
                          TripOrchestrator.OrchestrationOutcome out, CopyOutcome copy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", out.tripId());
        if (out.meta() != null) {
            data.putAll(out.meta());
        }
        // P4-B：文案用哪家模型、写了多少字 —— 手册要求 meta 记录本次文案模型，
        // 也是双厂商文案效果对比的观测点
        if (copy != null) {
            data.put("copyProvider", copy.providerName());
            data.put("copyModel", copy.modelName());
            data.put("copyChars", copy.chars());
        }
        send(emitter, cancellation, EVENT_DONE, data);
    }

    private Map<String, Object> errorData(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        return data;
    }

    private void recordDisconnected(Long userId, Long tripId, int durationMs, StreamCancellation cancellation) {
        aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_COPY,
                null, null, null, null, durationMs, false,
                AiErrorCode.CLIENT_DISCONNECTED, cancellation.interruptionSummary()));
        // 这行日志就是手册验收 3 要 grep 的那句
        log.info("客户端断开，已中断生成：{}", cancellation.interruptionSummary());
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("关闭 SSE 通道时异常（多为对端已断开）：{}", e.getMessage());
        }
    }

    private String safeModel(LlmProvider provider) {
        try {
            return provider.info() == null ? null : provider.info().model();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把大模型异常映射成日志用的归因码。
     *
     * <p>两个枚举<b>不同源</b>：{@code ResultCode} 是给用户看的接口码（含 LLM_DISABLED /
     * LLM_NOT_AVAILABLE 这类），{@code AiErrorCode} 是给运维归因的（只有 12 个）。
     * 名字对不上时不能直接 {@code valueOf}（会抛 IllegalArgumentException 把日志写崩），
     * 统一归到 {@code LLM_SERVER_ERROR}。
     */
    private AiErrorCode toAiErrorCode(Throwable t) {
        if (t instanceof LlmException le) {
            try {
                return AiErrorCode.valueOf(le.getResultCode().name());
            } catch (IllegalArgumentException ignore) {
                // 落不到就是两个枚举没对齐，按服务端错误记，别把日志写崩
            }
        }
        return AiErrorCode.LLM_SERVER_ERROR;
    }

    // ==================== P4-B 辅助 ====================

    /** stage 事件的 data 结构（三处在用，抽出来免得结构漂移） */
    private Map<String, Object> stageData(String stage, String status, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("status", status);
        data.put("message", message);
        return data;
    }

    /**
     * 渲染画像文本块（铁律三的落点）。
     *
     * <p>两道闸都在 {@link ProfileRenderer#shouldInject} 里：画像确实有内容、且用户没关隐私开关
     * （{@code allowAiUse != 0}）。任一不满足就返回空串 —— prompt 里连「【用户画像】」这个标题
     * 都不会出现，模型没有机会把不存在的信息写进文案。
     *
     * <p>读画像失败也返回空串：画像只是增强，不能因为它把文案生成拖失败。
     */
    private String renderProfileBlock(Long userId, boolean useProfile, ProfileOverrides overrides) {
        if (!useProfile) {
            return "";
        }
        try {
            UserTravelProfile profile = travelProfileService.getByUserId(userId);
            return profileRenderer.shouldInject(profile) ? profileRenderer.render(profile, overrides) : "";
        } catch (Exception e) {
            log.warn("画像加载失败，本次文案不注入画像：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 把已落库的行程聚合还原成 {@link TripDraftDTO}，供文案重试复用。
     *
     * <p>{@code poiRef} 用 {@code poiName} 回填：重试路径不经过 P3-E 的 CLOSURE 校验，
     * 这个字段在这里只是给文案 prompt 当点位名兜底（{@code TripCopyPrompt} 优先取 {@code poiName}）。
     */
    private TripDraftDTO toDraft(Trip trip) {
        TripDraftDTO draft = new TripDraftDTO();
        draft.setTitle(trip.getTitle());
        List<TripDraftDTO.DayDraft> days = new ArrayList<>();
        if (trip.getDayPlans() != null) {
            for (TripDay day : trip.getDayPlans()) {
                TripDraftDTO.DayDraft dayDraft = new TripDraftDTO.DayDraft();
                dayDraft.setDayIndex(day.getDayIndex());
                dayDraft.setTitle(day.getTitle());
                dayDraft.setSummary(day.getSummary());
                List<TripDraftDTO.ItemDraft> items = new ArrayList<>();
                if (day.getItems() != null) {
                    for (TripItem item : day.getItems()) {
                        items.add(toItemDraft(item));
                    }
                }
                dayDraft.setItems(items);
                days.add(dayDraft);
            }
        }
        draft.setDays(days);
        return draft;
    }

    private TripDraftDTO.ItemDraft toItemDraft(TripItem item) {
        TripDraftDTO.ItemDraft d = new TripDraftDTO.ItemDraft();
        d.setPoiRef(item.getPoiName());
        d.setPoiName(item.getPoiName());
        d.setItemType(item.getItemType());
        d.setStartTime(formatTime(item.getArriveTime()));
        d.setEndTime(formatTime(item.getLeaveTime()));
        d.setStayMinutes(item.getStayMinutes());
        d.setCostEstimate(item.getCostEstimate());
        d.setReason(item.getReason());
        // 距离/时长必须带上：TripCopyPrompt.estimatedMode 靠它判断「手里有没有可靠的事实」
        d.setDistanceMeters(item.getDistanceMeters());
        d.setDurationSeconds(item.getDurationSeconds());
        d.setVerifyStatus(item.getVerifyStatus());
        d.setDataSource(item.getDataSource());
        d.setNote(item.getNote());
        return d;
    }

    /** LocalTime → "HH:mm"；为空返回 null（文案 prompt 会跳过时段） */
    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /** 从 {@code trip.intent_json} 恢复意图；失败返回 null（文案少一句「目的地」，但不该因此失败） */
    private IntentDTO readIntent(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IntentDTO.class);
        } catch (Exception e) {
            log.warn("行程意图反序列化失败，文案将不含目的地：{}", e.getMessage());
            return null;
        }
    }

    /** 一次文案生成的结果：哪家厂商、哪个模型、写了多少字（进 done 事件与阶段日志） */
    private record CopyOutcome(String providerName, String modelName, int chars) {
    }
}