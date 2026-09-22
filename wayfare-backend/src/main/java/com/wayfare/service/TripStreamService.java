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
import com.wayfare.profile.ProfileOverrides;
import com.wayfare.security.LoginUser;
import com.wayfare.security.UserContext;
import com.wayfare.trip.AiErrorCode;
import com.wayfare.trip.AiStageRecord;
import com.wayfare.trip.StreamCancellation;
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
import java.util.LinkedHashMap;
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
 * <p>⚠️ <b>本块的文案 prompt 是占位实现</b>：按天组织 / 画像融入 / 长度约束 /
 * {@code guide_text} 落库 / {@code regenerate-copy} 重试接口都属 P4-B，
 * 这里只保证「有真实的大模型流可以推 delta」，好让事件协议能被独立验证。
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

    /** 占位文案 prompt（P4-B 会用完整规则替换：按天 150~250 字、画像融入、800 字上限） */
    private static final String COPY_SYSTEM_PROMPT =
            "你是一个给朋友写旅行攻略的人。把给定的行程写成一段人读得懂的攻略文案："
                    + "按天分段，每天开头一句话点出当天主题，再逐个点位说清「为什么去、看什么、大概待多久」。"
                    + "语气像朋友给的建议，不要写成官方宣传稿。总长控制在 800 字以内。"
                    + "注意：不要写出任何具体的距离或时长数字 —— 那些属于系统提供的事实数据，你不需要也不许估。";

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final TripOrchestrator orchestrator;
    private final LlmCapabilityResolver llmResolver;
    private final AiLogService aiLogService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

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
                             ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.llmResolver = llmResolver;
        this.aiLogService = aiLogService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
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
        // 超时走 L2 配置：运维改 trip.sse-timeout-ms 后新开的长连接立即用新值，无需重启
        Integer configured = sysConfigService.getInt(KEY_SSE_TIMEOUT_MS, DEFAULT_SSE_TIMEOUT_MS);
        int timeoutMs = configured == null || configured <= 0 ? DEFAULT_SSE_TIMEOUT_MS : configured;

        SseEmitter emitter = new SseEmitter((long) timeoutMs);
        StreamCancellation cancellation = new StreamCancellation();

        // 三条断开探测路径都登记上：只靠 send 失败往往要等到下一次发送才发现
        emitter.onCompletion(() -> cancellation.markCancelled("客户端断开（连接已结束）"));
        emitter.onError(e -> cancellation.markCancelled("客户端断开（" + e.getClass().getSimpleName() + "）"));
        emitter.onTimeout(() -> cancellation.markCancelled("SSE 连接超时（" + (timeoutMs / 60000) + " 分钟）"));

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
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("stage", stage);
                data.put("status", status);
                data.put("message", message);
                send(emitter, cancellation, EVENT_STAGE, data);
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
                sendDone(emitter, cancellation, out);
                completeQuietly(emitter);
                return;
            }

            streamCopy(emitter, cancellation, userId, out.tripId(), out.draft(), out.intent());

            if (cancellation.isCancelled()) {
                completeQuietly(emitter);
                return;
            }
            sendDone(emitter, cancellation, out);
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
     * 攻略文案流式生成（占位：P4-B 会用完整 prompt + guide_text 落库替换）。
     *
     * <p>刻意<b>不走 {@code llmResolver.execute()}</b>：那是给非流式调用做逐家降级的。
     * 流式一旦已经把部分文案推给前端，换一家重来会让用户看到重复文案 ——
     * 决策器自己的注释也是这么说的。所以这里只用候选链的第一家，
     * 失败就发 error 事件（行程已在 itinerary 事件里推过了，不受影响）。
     */
    private void streamCopy(SseEmitter emitter, StreamCancellation cancellation, Long userId,
                            Long tripId, TripDraftDTO draft, IntentDTO intent) {
        ResolvedLlm resolved;
        try {
            resolved = llmResolver.resolve();
        } catch (LlmException e) {
            send(emitter, cancellation, EVENT_ERROR,
                    errorData(e.getResultCode().name(), e.getMessage()));
            return;
        }
        LlmProvider provider = resolved.provider();

        LlmUsage[] usageRef = new LlmUsage[1];
        Throwable[] errorRef = new Throwable[1];
        long startMs = System.currentTimeMillis();

        try {
            provider.chatStream(COPY_SYSTEM_PROMPT, renderDraftBrief(draft, intent),
                    LlmCallContext.of(u -> usageRef[0] = u),
                    piece -> {
                        // 先记账再判取消：已产出的字符数要如实统计（P4-C 会用它估算节省量）
                        cancellation.countProduced(piece);
                        cancellation.checkCancelled();
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
            // ★ 成本控制落点：写一条 success=0 + CLIENT_DISCONNECTED，说明「已产出多少、为什么没有 token 数」
            recordDisconnected(userId, tripId, durationMs, cancellation);
            return;
        }
        if (errorRef[0] != null) {
            AiErrorCode code = toAiErrorCode(errorRef[0]);
            aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_COPY,
                    provider.name(), safeModel(provider), null, null, durationMs, false, code,
                    errorRef[0].getMessage()));
            send(emitter, cancellation, EVENT_ERROR,
                    errorData(code.name(), "文案生成失败（行程已保留，可稍后重试）"));
            return;
        }
        aiLogService.recordStage(new AiStageRecord(userId, tripId, AiStageRecord.STAGE_COPY,
                provider.name(), safeModel(provider),
                usageRef[0] == null ? null : usageRef[0].promptTokens(),
                usageRef[0] == null ? null : usageRef[0].completionTokens(),
                durationMs, true, null, null));
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
                          TripOrchestrator.OrchestrationOutcome out) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", out.tripId());
        if (out.meta() != null) {
            data.putAll(out.meta());
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

    /** 把结构化行程压缩成给模型看的简报（不进 prompt 的距离/时长字段，避免它顺手复述） */
    private String renderDraftBrief(TripDraftDTO draft, IntentDTO intent) {
        StringBuilder sb = new StringBuilder();
        if (intent != null && StringUtils.hasText(intent.getDestination())) {
            sb.append("目的地：").append(intent.getDestination()).append('\n');
        }
        sb.append("标题：").append(StringUtils.hasText(draft.getTitle()) ? draft.getTitle() : "（待定）").append('\n');
        if (draft.getDays() != null) {
            for (TripDraftDTO.DayDraft day : draft.getDays()) {
                sb.append("\n第 ").append(day.getDayIndex()).append(" 天");
                if (StringUtils.hasText(day.getTitle())) {
                    sb.append('：').append(day.getTitle());
                }
                sb.append('\n');
                if (day.getItems() == null) {
                    continue;
                }
                for (TripDraftDTO.ItemDraft item : day.getItems()) {
                    sb.append("  - ")
                            .append(item.getStartTime()).append('-').append(item.getEndTime()).append(' ');
                    sb.append(StringUtils.hasText(item.getPoiName()) ? item.getPoiName() : item.getPoiRef());
                    if (item.getStayMinutes() != null) {
                        sb.append("（停留 ").append(item.getStayMinutes()).append(" 分钟）");
                    }
                    if (item.getCostEstimate() != null) {
                        sb.append(" 预算 ").append(item.getCostEstimate()).append(" 元");
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}