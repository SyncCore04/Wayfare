import io, json, sys, urllib.request

ENVF = r"D:\Code\Vibe coding test\Wayfare\wayfare-backend\.env.properties"
AK = None
for line in io.open(ENVF, encoding="utf-8"):
    if line.startswith("DEEPSEEK_API_KEY="):
        AK = line.split("=", 1)[1].strip()

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
    "days 不超过 5；budgetMode 只能是 PER_PERSON 或 TOTAL；transport 只能是 DRIVE/PUBLIC/WALK/MIX；"
    "pace 只能是 1/2/3。没有依据的字段直接省略，不要填 null，也不要填空字符串。"
)
TAIL = "\n严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + HINT

HEAD = ("你是旅行需求解析器。把用户的一句话行程需求解析成 json。\n\n"
        "【今天的日期】\n2026-09-23（周三）。\n"
        "用户说「周末」「明天」「下个月」这类相对时间时，以今天为基准推算成 yyyy-MM-dd。\n"
        "「周末」指最近的周六；若今天已经是周六或周日，就指今天。\n\n")

R1 = ("1. 天数必须在 1 ~ 5 天之间。用户说的天数超了也不要改，如实填，由系统提示他拆分。\n")
R2 = ("2. 只填用户真的说了的字段。**没有依据就不要填**，宁缺勿猜 —— 你猜出来的一个具体值，比一个空字段危险得多，因为它会被下游当成事实使用。\n"
      "   用户没提目的地、没提天数时，**把该字段整个省略**（不要写空字符串 \"\"，不要写 null，更不要编一个地名填上），\n"
      "   系统会把它列进 needConfirm 让用户补填。编一个地名是最严重的错误。\n")
R3A = ("3. 凡是你没有十足依据、只能靠推测填的字段，**必须把它的字段名放进 needConfirm 数组**。\n   典型情况：\n"
       "   - 用户说「预算 500」却没说人均还是总计 → needConfirm 里要有 \"budgetMode\"\n"
       "   - 用户没提同行人，你却填了 companion → needConfirm 里要有 \"companion\"\n"
       "   - 用户没提交通方式，你却填了 transport → needConfirm 里要有 \"transport\"\n")
R3B = "   - 目的地名你拿不准是哪个（同名地名）→ needConfirm 里要有 \"destination\"\n"
R3C = "   填了值又不标进 needConfirm，等于告诉系统「这是用户说的」，而其实是你猜的。\n"
R4 = ("4. 用户明确表达过的（如「不吃辣」「带爸妈」「走不动」），要解析成对应字段，不要漏。\n"
      "   「走不动」「想轻松点」这类表述 → pace=1（慢）。\n")
R5 = ("5. dietaryOverrides 只放用户**不吃 / 忌口**的东西（如「不吃辣」「海鲜过敏」）。\n"
      "   用户**想吃**的（如「想吃面食」）是偏好，要放进 preferenceTags ——\n"
      "   dietaryOverrides 会被下游当作硬约束，把「想吃的东西」放进去反而会把它排除掉。\n")
R6 = "6. 用户没提预算时**整个省略 budgetTotal**，绝对不要填 0（0 会被当成「预算为零」）。\n"
R7 = ("7. 用户**完全没提到日期/时间**时，**省略 startDate** —— 不要自己替他定一个出发日期。\n"
      "   （实测过：模型会自作主张填一个日期，而用户从没说过什么时候去。）\n")

RULES_ALL = "【硬性要求】\n" + R1 + R2 + R3A + R3B + R3C + R4 + R5 + R6 + R7

CASES = {
    "A_full": HEAD + RULES_ALL + TAIL,
    "B_no_R3B(同名地名行)": HEAD + "【硬性要求】\n" + R1 + R2 + R3A + R3C + R4 + R5 + R6 + R7 + TAIL,
    "C_only_R1R2": HEAD + "【硬性要求】\n" + R1 + R2 + TAIL,
    "D_only_R2R3B": HEAD + "【硬性要求】\n" + R2 + R3A + R3B + R3C + TAIL,
}

USER = "用户的需求：周末想去寿阳玩两天，喜欢古建筑，预算 500"

for name, sysmsg in CASES.items():
    body = json.dumps({"model": "deepseek-flash",
                       "messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": USER}],
                       "max_tokens": 800, "temperature": 0.3, "reasoning_effort": "none"},
                      ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("https://api.deepseek.com/chat/completions", data=body,
                                headers={"Authorization": "Bearer " + AK, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            d = json.load(r)
        c = d["choices"][0]["message"].get("content") or ""
        dest = ""
        try:
            dest = json.loads(c).get("destination", "")
        except Exception:
            dest = "(非JSON)"
        print("%-24s destination=[%s]  head=%s" % (name, dest, c.replace("\n", " ")[:90]))
    except Exception as e:
        print("%-24s ERROR %s" % (name, e))
