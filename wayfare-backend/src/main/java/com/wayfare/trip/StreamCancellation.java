package com.wayfare.trip;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 生成任务的「取消闸门」（P4-A）—— 客户端断开后靠它把大模型流掐断，省钱的关键件。
 *
 * <p><b>为什么必须有它</b>：一次文案生成要跑 10~60 秒。用户关了页面/按了 Ctrl+C，
 * 如果后端继续把整段文案生成完，那些 token 是<b>白花的</b>。所以断开后要能立即停。
 *
 * <p><b>中断怎么生效（不是靠标志位自己消失）</b>：
 * {@code AbstractOpenAiCompatibleProvider.chatStream} 的读取是一个阻塞的
 * {@code lines.forEach(onLine)}，外面套着 try-with-resources。因此
 * <b>只要让 {@link #checkCancelled()} 在增量回调里抛异常</b>，异常就会顺着读取循环炸出去，
 * try-with-resources 随即关闭响应流 —— 这就是手册要求的「关闭 InputStream」，
 * 而且不需要给连接器层加新的取消 API。
 *
 * <p><b>不编 token 数字</b>：流式调用的 usage 通常在<b>最后一个 chunk</b> 才返回，
 * 中断时根本拿不到。所以这里只如实记「已产出多少字符」，token 数留给 P4-C
 * 用「同类请求的平均 completion_tokens」去估算，绝不在这里编一个换算比例。
 */
public class StreamCancellation {

    private final AtomicInteger producedChars = new AtomicInteger();
    private volatile boolean cancelled;
    private volatile String reason;

    /**
     * 标记取消。<b>只认第一次</b> —— 断开可能同时被 onError / send 失败 / onCompletion
     * 三条路径探测到，第一条原因才最准确（后面的往往是它的连锁反应）。
     */
    public void markCancelled(String reason) {
        if (!cancelled) {
            cancelled = true;
            this.reason = reason;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }

    /**
     * 每收到一段增量文本累加一次。
     *
     * <p><b>口径是「模型已产出的字符数」，不是「已推送给前端的字符数」</b>：
     * 调用方必须在 {@link #checkCancelled()} <b>之前</b>调用本方法，
     * 因此触发中断的那一段（收到但没推给前端）也会被计入 —— 它确实被模型生成、
     * 也确实计了费，算进「已产出」比算进「已送达」更贴近成本真相。
     */
    public void countProduced(String piece) {
        if (piece != null && !piece.isEmpty()) {
            producedChars.addAndGet(piece.length());
        }
    }

    public int producedChars() {
        return producedChars.get();
    }

    /** 已取消就抛 —— 抛在增量回调里即可中断上游读取循环（见类注释） */
    public void checkCancelled() {
        if (cancelled) {
            throw new Aborted(reason);
        }
    }

    /**
     * 写进 {@code ai_generation_log.error_msg} 的说明。
     *
     * <p>刻意不含 token 数字：中断时取不到 usage，写成「节省约 N tokens」就是编数字。
     * P4-C 会用同类请求均值补上这个估算，那时格式再升级。
     */
    public String interruptionSummary() {
        return "客户端断开已中断，已产出 " + producedChars.get() + " 字符"
                + "（流式 usage 随最后一个 chunk 返回，中断时取不到 token 数，故不编数字）；"
                + "已停止后续生成，未生成部分即为本次节省。取消原因：" + reason;
    }

    /** 中断信号：用异常把上游读取循环炸开，是这里唯一可靠的「关闭连接」手段 */
    public static class Aborted extends RuntimeException {
        public Aborted(String message) {
            super(message);
        }
    }
}