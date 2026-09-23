import io, json, os, sys, urllib.request

ENVF = r"D:\Code\Vibe coding test\Wayfare\wayfare-backend\.env.properties"
AK = None
for line in io.open(ENVF, encoding="utf-8"):
    if line.startswith("DEEPSEEK_API_KEY="):
        AK = line.split("=", 1)[1].strip()
if not AK:
    print("no key"); sys.exit(1)

# 与 IntentParser.buildJsonSchemaHint(5) 完全一致的文本（从源码逐字抄来）
HINT = (
    "{\n"
    '  "destination": "目的地名称（字符串；用户没说就省略此字段）",\n'
    '  "days": 5,\n'
    '  "startDate": "yyyy-MM-dd（用户没说日期就省略此字段）",\n'
    '  "budgetTotal": 500,\n'
    '  "budgetMode": "PER_PERSON 或 TOTAL（人均 / 总计）",\n'
    '  "transport": "DRIVE 或 PUBLIC 或 WALK 或 MIX",\n'
    '  "companion": "同行人，如 爸妈 / 一个人 / 情侣",\n'
    '  "preferenceTags": ["古建筑", "博物馆"],\n'
    '  "pace": "1=慢 2=适中 3=紧凑（数字）",\n'
    '  "dietaryOverrides": ["不吃辣"],\n'
    '  "needConfirm": ["budgetMode"],\n'
    '  "confidence": 0.8\n'
    "}\n"
    "days 不超过 5；"
    "budgetMode 只能是 PER_PERSON 或 TOTAL；transport 只能是 DRIVE/PUBLIC/WALK/MIX；"
    "pace 只能是 1/2/3。没有依据的字段直接省略，不要填 null，也不要填空字符串。"
)

USER = "用户的需求：周末想去寿阳玩两天，喜欢古建筑，预算 500"

CASES = {
    "A_hint_only": "严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + HINT,
    "B_hint_plus_rule2": (
        "你是旅行需求解析器。把用户的一句话行程需求解析成 json。\n\n"
        "【硬性要求】\n"
        "2. 只填用户真的说了的字段。**没有依据就不要填**，宁缺勿猜 —— "
        "用户没提目的地、没提天数时，**把该字段整个省略**，不要编一个地名填上。编一个地名是最严重的错误。\n\n"
        "严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + HINT
    ),
    "C_no_hint": "你是旅行需求解析器。把用户的一句话行程需求解析成 json。只填用户真的说了的字段，没有依据就省略。严格只输出一个合法的 json 对象。",
}

for name, sysmsg in CASES.items():
    body = json.dumps({
        "model": "deepseek-flash",
        "messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": USER}],
        "max_tokens": 800, "temperature": 0.3, "reasoning_effort": "none",
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("https://api.deepseek.com/chat/completions", data=body,
                                headers={"Authorization": "Bearer " + AK, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            d = json.load(r)
        m = d["choices"][0]["message"]
        print("=== %s ===" % name)
        print("content:", (m.get("content") or "").replace("\n", " ")[:300])
        print("out_tokens:", d["usage"]["completion_tokens"])
    except Exception as e:
        print("=== %s === ERROR %s" % (name, e))
