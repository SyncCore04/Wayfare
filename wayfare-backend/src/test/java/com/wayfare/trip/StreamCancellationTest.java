package com.wayfare.trip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StreamCancellation} 的单测（P4-A）。
 *
 * <p>这里测的是手册验收 3/4 背后的机制：客户端断开后，
 * <b>增量回调必须抛出异常</b>，才能把上游 {@code lines.forEach(...)} 读取循环炸开、
 * 让 try-with-resources 关掉连接（= 手册说的「关闭 InputStream」）。
 * 单测无法真的断开 TCP，但可以把这条因果链钉死。
 */
class StreamCancellationTest {

    @Test
    @DisplayName("默认未取消：isCancelled=false 且 checkCancelled 是空操作")
    void notCancelledByDefault() {
        StreamCancellation c = new StreamCancellation();

        assertFalse(c.isCancelled());
        assertEquals(0, c.producedChars());
        // 不抛异常才算「空操作」
        c.checkCancelled();
    }

    @Test
    @DisplayName("标记取消后：状态可见、原因保留、checkCancelled 抛出 Aborted")
    void markCancelledThenCheck() {
        StreamCancellation c = new StreamCancellation();

        c.markCancelled("客户端断开（连接已结束）");

        assertTrue(c.isCancelled());
        assertEquals("客户端断开（连接已结束）", c.reason());
        StreamCancellation.Aborted ex = assertThrows(StreamCancellation.Aborted.class, c::checkCancelled);
        assertEquals("客户端断开（连接已结束）", ex.getMessage());
    }

    @Test
    @DisplayName("只认第一次取消原因 —— 断开会被三条路径同时探测到，首因最准确")
    void firstReasonWins() {
        StreamCancellation c = new StreamCancellation();

        c.markCancelled("第一条原因");
        c.markCancelled("第二条原因（连锁反应）");

        assertEquals("第一条原因", c.reason());
    }

    @Test
    @DisplayName("已产出字符数：逐段累加，null 与空串不计")
    void countProduced() {
        StreamCancellation c = new StreamCancellation();

        c.countProduced("第一天");
        c.countProduced("");
        c.countProduced(null);
        c.countProduced("从古城墙开始");

        // 「第一天」=3 字 + 「从古城墙开始」=6 字
        assertEquals(3 + 6, c.producedChars());
    }

    @Test
    @DisplayName("断开后增量回调必须抛异常 —— 这就是掐断大模型流的唯一手段")
    void deltaConsumerAbortsAfterCancel() {
        StreamCancellation c = new StreamCancellation();
        List<String> pushed = new ArrayList<>();
        AtomicInteger commits = new AtomicInteger();

        // 复刻 TripStreamService 里的回调顺序：先记账 → 再判取消 → 再推送
        java.util.function.Consumer<String> onDelta = piece -> {
            c.countProduced(piece);
            c.checkCancelled();
            pushed.add(piece);
            commits.incrementAndGet();
        };

        onDelta.accept("第一段");
        onDelta.accept("第二段");
        assertEquals(2, commits.get());

        c.markCancelled("客户端断开（发送 delta 事件失败：IOException）");

        assertThrows(StreamCancellation.Aborted.class, () -> onDelta.accept("第三段"));
        // 抛出去之后不该再有内容被推给前端
        assertEquals(2, commits.get());
        assertEquals(2, pushed.size());
        // 但字符数按「模型已产出」口径统计：第三段收到了、也计费了，
        // 只是没推给前端 —— 所以是 3×3=9 而不是 6（详见 countProduced 的注释）
        assertEquals(9, c.producedChars());
    }

    @Test
    @DisplayName("中断说明：如实报字符数，且不编造 token 数字")
    void interruptionSummary() {
        StreamCancellation c = new StreamCancellation();
        c.countProduced("一二三四五");
        c.markCancelled("客户端断开（连接已结束）");

        String msg = c.interruptionSummary();

        assertNotNull(msg);
        assertTrue(msg.contains("已产出 5 字符"), msg);
        // 中断时拿不到 usage，就不能出现「节省约 N tokens」这种编出来的数字
        assertFalse(msg.contains("tokens 约"), msg);
        assertTrue(msg.contains("已停止后续生成"), msg);
        assertTrue(msg.contains("客户端断开（连接已结束）"), msg);
    }
}