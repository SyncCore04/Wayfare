import io, json, sys, urllib.request

ENVF = r"D:\Code\Vibe coding test\Wayfare\wayfare-backend\.env.properties"
AK = None
for line in io.open(ENVF, encoding="utf-8"):
    if line.startswith("DEEPSEEK_API_KEY="):
        AK = line.split("=", 1)[1].strip()

REAL_SYS = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_system_prompt.txt", encoding="utf-8").read()
REAL_HINT = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_hint.txt", encoding="utf-8").read()
# 去掉日志尾部粘进来的下一行时间戳
REAL_HINT = REAL_HINT.split("2026-09-23T")[0].rstrip()
USER = "用户的需求：周末想去寿阳玩两天，喜欢古建筑，预算 500"

# 真实 prompt = 真实 system + 基类拼的 JSON 指令 + 真实 hint
BASE_TAIL = "\n严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + REAL_HINT

# 把 system 里的画像块切出来（便于二分）
PROFILE_MARK = "【用户长期偏好与本次条件】"
idx = REAL_SYS.find(PROFILE_MARK)
SYS_NO_PROFILE = REAL_SYS[:idx].rstrip() if idx > 0 else REAL_SYS

CASES = {
    "1_REAL_full": REAL_SYS + BASE_TAIL,
    "2_REAL_noProfile": SYS_NO_PROFILE + BASE_TAIL,
    "3_REAL_noProfile_maxTokens32768": SYS_NO_PROFILE + BASE_TAIL,
}

for name, sysmsg in CASES.items():
    mt = 32768 if "32768" in name else 800
    body = json.dumps({"model": "deepseek-flash",
                       "messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": USER}],
                       "max_tokens": mt, "temperature": 0.3, "stream": False,
                       "reasoning_effort": "none"}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("https://api.deepseek.com/chat/completions", data=body,
                                headers={"Authorization": "Bearer " + AK, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            d = json.load(r)
        c = d["choices"][0]["message"].get("content") or ""
        try:
            dest = json.loads(c).get("destination", "(无该字段)")
        except Exception:
            dest = "(非JSON)"
        print("%-34s destination=[%s] tokens=%d" % (name, dest, d["usage"]["completion_tokens"]))
    except Exception as e:
        print("%-34s ERROR %s" % (name, e))
