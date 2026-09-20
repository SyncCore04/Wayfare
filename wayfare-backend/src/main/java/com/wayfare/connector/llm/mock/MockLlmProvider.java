package com.wayfare.connector.llm.mock;

import com.wayfare.connector.llm.LlmCallContext;
import com.wayfare.connector.llm.LlmInfo;
import com.wayfare.connector.llm.LlmProvider;
import com.wayfare.connector.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 离线兜底 Provider：不需要网络、不需要 Key。
 *
 * <p><b>为什么它不是「测试用的假东西」</b>：双厂商都不配 Key、或都挂掉时，
 * 整条链路要靠它继续跑通，用户仍然能看到完整的流程与结果（只是内容不是真的）。
 * 这是项目铁律「连接器可插拔，任一时刻系统完整可用」的最后一道兜底 ——
 * 没有它，一台干净的机器 clone 下代码连演示都做不了。
 *
 * <p><b>返回什么由 prompt 里的关键词决定</b>，故意做得「看起来像真的」，
 * 让上游的解析逻辑（P3 的 JSON 解析、P4 的流式渲染）能被真实地跑一遍。
 *
 * <p>⚠️ <b>关于 JSON 形状</b>：下面 Intent / TripDraft 的字段是按手册描述拟的，
 * 但 P3-A / P3-D 才会定义真正的 DTO。<b>P3 落地时如果字段名有出入，需要回来对齐这里</b> ——
 * 不能让 Mock 的 JSON 与真实 DTO 不一致，否则「Mock 能跑通、真实厂商跑不通」这种
 * 最难查的问题就会出现。
 */
@Component
public class MockLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockLlmProvider.class);

    public static final String PROVIDER_NAME = "mock";

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    /** Mock 永远可用 —— 它是兜底，不是候选 */
    @Override
    public LlmInfo info() {
        return LlmInfo.available(PROVIDER_NAME, "mock");
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, LlmCallContext context) {
        String text = respond(userPrompt);
        // 给一个假的用量，让 P4 的成本统计链路在无 Key 时也能被完整走通
        if (context != null) {
            context.reportUsage(LlmUsage.of(text.length() / 2, text.length() / 2));
        }
        return text;
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint, LlmCallContext context) {
        // JSON 模式一律按意图解析的输出来答（调用方要 JSON 时基本都是结构化抽取场景）
        String json = intentJson();
        if (context != null) {
            context.reportUsage(LlmUsage.of(json.length() / 2, json.length() / 2));
        }
        return json;
    }

    /**
     * 模拟流式：把整段文本按较小片段依次吐出。
     * 这样前端的 SSE 渲染、P4 的流式事件协议在无 Key 环境下也能被验证。
     */
    @Override
    public void chatStream(String systemPrompt, String userPrompt,
                           Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        // 接口里的 5 参数版是必须实现的；带 context 的版本见下面，两者共用同一段逻辑
        chatStream(systemPrompt, userPrompt, LlmCallContext.empty(), onDelta, onDone, onError);
    }

    @Override
    public void chatStream(String systemPrompt, String userPrompt, LlmCallContext context,
                           Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        try {
            String text = respond(userPrompt);
            int chunkSize = 12;
            for (int i = 0; i < text.length(); i += chunkSize) {
                String piece = text.substring(i, Math.min(text.length(), i + chunkSize));
                onDelta.accept(piece);
            }
            if (context != null) {
                context.reportUsage(LlmUsage.of(text.length() / 2, text.length() / 2));
            }
            onDone.run();
        } catch (Throwable t) {
            log.error("Mock 流式输出异常", t);
            onError.accept(t);
        }
    }

    /** 按 prompt 关键词决定返回哪种假数据 */
    private String respond(String userPrompt) {
        String p = userPrompt == null ? "" : userPrompt.toLowerCase();
        if (p.contains("意图") || p.contains("intent") || p.contains("解析")) {
            return intentJson();
        }
        if (p.contains("行程") || p.contains("编排") || p.contains("itinerary") || p.contains("规划")) {
            return tripDraftJson();
        }
        return guideText();
    }

    private String intentJson() {
        return """
                {
                  "destination": "泉州",
                  "days": 2,
                  "travelers": 1,
                  "budgetLevel": "medium",
                  "themes": ["古建探访", "市井烟火"],
                  "constraints": ["不要太赶"],
                  "taboos": [],
                  "rawQuery": "想去泉州玩两天，看看古建，吃点本地小吃"
                }""";
    }

    private String tripDraftJson() {
        return """
                {
                  "days": [
                    {
                      "dayIndex": 1,
                      "title": "古城与开元寺",
                      "items": [
                        {"name": "开元寺", "startTime": "09:00", "durationMinutes": 90, "category": "古建探访", "note": "东西塔必看"},
                        {"name": "西街", "startTime": "11:00", "durationMinutes": 60, "category": "市井烟火", "note": "顺路吃面线糊"},
                        {"name": "钟楼", "startTime": "14:00", "durationMinutes": 40, "category": "城市漫步", "note": "拍照点"}
                      ]
                    },
                    {
                      "dayIndex": 2,
                      "title": "洛阳桥与蟳埔村",
                      "items": [
                        {"name": "洛阳桥", "startTime": "09:30", "durationMinutes": 80, "category": "古建探访", "note": "退潮时更出片"},
                        {"name": "蟳埔村", "startTime": "13:30", "durationMinutes": 120, "category": "摄影旅拍", "note": "簪花围"},
                        {"name": "中山路", "startTime": "16:00", "durationMinutes": 90, "category": "美食之旅", "note": "晚餐"}
                      ]
                    }
                  ]
                }""";
    }

    private String guideText() {
        return "泉州两日游的节奏可以放得很慢。第一天从开元寺开始，东西塔的木构细节值得花上一个小时；"
                + "出来就是西街，面线糊配油条是本地人最普通的早餐。下午沿中山路往北走到钟楼，"
                + "老骑楼的墙面被晒得发白。第二天去洛阳桥，退潮时能看到桥基上的牡蛎附着，"
                + "下午转到蟳埔村，簪花围配上闽南红的砖墙，是很出片的地方。"
                + "（以上内容由离线 Mock 生成，仅用于在未配置任何大模型 Key 时跑通全链路）";
    }
}
