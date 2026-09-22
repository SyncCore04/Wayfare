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
 * <p><b>返回什么由「调用方给的 json schema 提示」或 prompt 关键词决定</b>，故意做得「看起来像真的」，
 * 让上游的解析逻辑（P3 的 JSON 解析、P4 的流式渲染）能被真实地跑一遍。
 *
 * <p>⚠️ <b>关于 JSON 形状</b>：意图 / 候选池 / 编排草稿三类 JSON 的<b>字段名必须与真实 DTO 一致</b>，
 * 否则会出现「Mock 能跑通、真实厂商跑不通」这种最难查的问题。
 * 本类在 2026-09-22（P7-A）<b>已经踩过一次</b>：字段名是臆想的，且 {@code chatJson} 对三个阶段返回同一份数据，
 * 结果是关掉地图时候选池为空、整条管线出不了行程（而 README 里恰好写着「没有 Key 也能跑通全链路」）。
 * 现在这条链路由 {@code TripOfflinePipelineIntegrationTest} 守着 —— 改任何一类 JSON 都要跑它。
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
        String json = jsonForStage(userPrompt, jsonSchemaHint);
        if (context != null) {
            context.reportUsage(LlmUsage.of(json.length() / 2, json.length() / 2));
        }
        return json;
    }

    /**
     * 按「调用方给的 json schema 提示」决定返回哪一种假数据。
     *
     * <p>🔴 <b>2026-09-22 修（P7-A 的离线集成测试抓出来的真缺陷）</b>：
     * 原来这个方法<b>一律返回意图 JSON</b>，于是关掉地图（候选只能由大模型出）时，
     * 候选检索拿到的是一份意图 JSON → 解析出 0 个候选 → 候选池为空 → 整条管线出不了行程。
     * 也就是说 README 里那句「没有大模型 Key 也能跑通全链路」当时是<b>不成立</b>的。
     *
     * <p>判断依据优先用 {@code jsonSchemaHint} 而不是用户 prompt 的关键词：
     * hint 是各阶段自己拼的结构说明（候选阶段一定含 {@code "candidates"}、
     * 编排阶段一定含 {@code "poiRef"}），比「prompt 里出现了某两个字」可靠得多 ——
     * 事实上候选阶段的 prompt 里恰好写着「行程天数」，用关键词判断就会走错分支。
     */
    private String jsonForStage(String userPrompt, String jsonSchemaHint) {
        String hint = jsonSchemaHint == null ? "" : jsonSchemaHint;
        // hint 为空时才退回 prompt（有的调用方不传 hint）
        String probe = hint.isEmpty() ? (userPrompt == null ? "" : userPrompt) : hint;

        if (probe.contains("candidates")) {
            return candidateJson();
        }
        if (probe.contains("poiRef") || probe.contains("dayIndex")) {
            return tripDraftJson();
        }
        return intentJson();
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

    /** 按 prompt 关键词决定返回哪种假数据（非 JSON 的普通调用与流式走这里） */
    private String respond(String userPrompt) {
        String p = userPrompt == null ? "" : userPrompt.toLowerCase();
        // ⚠️ 顺序不能反：候选阶段的用户 prompt 里也写着「行程天数」，
        // 先判「行程」会让候选阶段拿到编排 JSON（上一版就是同类错误）
        if (p.contains("点位") || p.contains("候选") || p.contains("candidate")) {
            return candidateJson();
        }
        if (p.contains("意图") || p.contains("intent") || p.contains("解析")) {
            return intentJson();
        }
        if (p.contains("编排") || p.contains("itinerary") || p.contains("行程")) {
            return tripDraftJson();
        }
        return guideText();
    }

    /**
     * 意图 JSON —— 字段名必须与 {@code IntentDTO} 严格一致。
     *
     * <p>上一版用的是 {@code themes} / {@code constraints} / {@code travelers} 这些<b>臆想的字段名</b>，
     * 反序列化时被静默忽略，结果 intent 里只剩 destination 与 days。
     * 「能跑通、但字段全丢」这种问题在真实 Key 环境下没人会发现 —— 所以字段名要照着 DTO 抄。
     */
    private String intentJson() {
        return """
                {
                  "destination": "泉州",
                  "days": 2,
                  "budgetTotal": 500,
                  "budgetMode": "TOTAL",
                  "transport": "MIX",
                  "companion": "一个人",
                  "preferenceTags": ["古建筑", "市井烟火"],
                  "pace": 2,
                  "dietaryOverrides": [],
                  "needConfirm": ["budgetMode"],
                  "confidence": 0.9
                }""";
    }

    /**
     * 候选池 JSON（关掉地图时由大模型出候选，{@code {"candidates":[...]}} 是候选检索的契约）。
     *
     * <p>三条硬要求，缺一条整条管线就走偏：
     * <ul>
     *   <li><b>至少 3 个 {@code FOOD}</b>：P3-D 的「每天至少 1 个餐饮」只在池里真有餐饮时才校验，
     *       但一份没有午饭的行程对用户是没用的；</li>
     *   <li><b>数量落在候选检索期望的 10~15 区间</b>；</li>
     *   <li><b>名称里不能有数字</b> —— 候选检索会挡「特色餐馆1」这类占位式命名，
     *       挡掉之后池子就空了。所以这里给的是像真店名的写法。</li>
     * </ul>
     *
     * <p>另外：名称必须与 {@link #tripDraftJson()} 里的 {@code poiRef} 一致 ——
     * P3-E 的 CLOSURE 会逐条回查 poiRef 是否真在候选池里，对不上会被打成 HIGH 违规并触发重排。
     */
    private String candidateJson() {
        return """
                {
                  "candidates": [
                    {"name": "开元寺",         "type": "SCENIC", "area": "鲤城区西街",   "stayMinutes": 90,  "highlight": "东西塔与千年古桑"},
                    {"name": "西街",           "type": "SCENIC", "area": "鲤城区",       "stayMinutes": 60,  "highlight": "骑楼老街与市井小吃"},
                    {"name": "钟楼",           "type": "SCENIC", "area": "鲤城区中山路", "stayMinutes": 40,  "highlight": "十字路口的白色钟楼"},
                    {"name": "清净寺",         "type": "SCENIC", "area": "鲤城区涂门街", "stayMinutes": 50,  "highlight": "现存最早的伊斯兰教寺庙之一"},
                    {"name": "天后宫",         "type": "SCENIC", "area": "鲤城区天后路", "stayMinutes": 50,  "highlight": "闽南妈祖信仰的中心"},
                    {"name": "洛阳桥",         "type": "SCENIC", "area": "洛江区",       "stayMinutes": 90,  "highlight": "宋代跨海石桥，退潮可看桥基"},
                    {"name": "蟳埔村",         "type": "SCENIC", "area": "丰泽区东海",   "stayMinutes": 120, "highlight": "簪花围与蚵壳厝"},
                    {"name": "中山路",         "type": "SCENIC", "area": "鲤城区",       "stayMinutes": 60,  "highlight": "连排骑楼的南洋风格"},
                    {"name": "水门国仔面线糊", "type": "FOOD",   "area": "鲤城区水门巷", "stayMinutes": 45,  "highlight": "本地人从小吃到大的面线糊"},
                    {"name": "斯丹姜母鸭",     "type": "FOOD",   "area": "丰泽区",       "stayMinutes": 60,  "highlight": "砂锅姜母鸭，天冷最合适"},
                    {"name": "亚佛海蛎煎",     "type": "FOOD",   "area": "鲤城区西街",   "stayMinutes": 45,  "highlight": "现摊海蛎煎，配甜辣酱"}
                  ]
                }""";
    }

    /**
     * 编排结果 JSON —— 字段名必须与 {@code TripDraftDTO.ItemDraft} 一致：
     * {@code poiRef} 而不是 {@code name}，且<b>不写距离与时长</b>
     * （那是 Step 6 从地图回填的，模型给的会被丢弃；估算模式下更不能有数字）。
     */
    private String tripDraftJson() {
        return """
                {
                  "title": "泉州古城两日慢行",
                  "days": [
                    {
                      "dayIndex": 1,
                      "title": "古城与开元寺",
                      "summary": "以鲤城区为核心，步行串起古建与市井烟火",
                      "items": [
                        {"poiRef": "开元寺",         "itemType": "SCENIC", "startTime": "09:00", "endTime": "10:30", "stayMinutes": 90, "costEstimate": 0,   "reason": "上午光线好，东西塔的木构细节值得慢慢看"},
                        {"poiRef": "西街",           "itemType": "SCENIC", "startTime": "10:40", "endTime": "11:40", "stayMinutes": 60, "costEstimate": 0,   "reason": "从开元寺出来就是，顺路逛骑楼"},
                        {"poiRef": "水门国仔面线糊", "itemType": "FOOD",   "startTime": "12:00", "endTime": "12:45", "stayMinutes": 45, "costEstimate": 25,  "reason": "本地早点摊，午市人相对少"},
                        {"poiRef": "钟楼",           "itemType": "SCENIC", "startTime": "14:00", "endTime": "14:40", "stayMinutes": 40, "costEstimate": 0,   "reason": "沿中山路往北走就能看到"}
                      ]
                    },
                    {
                      "dayIndex": 2,
                      "title": "跨海石桥与蟳埔村",
                      "summary": "出城看宋代石桥，下午回到海边的簪花围村落",
                      "items": [
                        {"poiRef": "洛阳桥",     "itemType": "SCENIC", "startTime": "09:30", "endTime": "11:00", "stayMinutes": 90,  "costEstimate": 0,  "reason": "退潮时能看到桥基上的牡蛎附着"},
                        {"poiRef": "斯丹姜母鸭", "itemType": "FOOD",   "startTime": "12:00", "endTime": "13:00", "stayMinutes": 60,  "costEstimate": 120,"reason": "砂锅现做，两个人吃刚好"},
                        {"poiRef": "蟳埔村",     "itemType": "SCENIC", "startTime": "13:30", "endTime": "15:30", "stayMinutes": 120, "costEstimate": 40, "reason": "簪花围要走一圈才好看"},
                        {"poiRef": "亚佛海蛎煎", "itemType": "FOOD",   "startTime": "17:00", "endTime": "17:45", "stayMinutes": 45,  "costEstimate": 30, "reason": "回程路上垫一口"}
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
