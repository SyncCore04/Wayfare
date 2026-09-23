import io, json, time, urllib.request

ENVF = r"D:\Code\Vibe coding test\Wayfare\wayfare-backend\.env.properties"
AK = None
for line in io.open(ENVF, encoding="utf-8"):
    if line.startswith("DEEPSEEK_API_KEY="):
        AK = line.split("=", 1)[1].strip()

S = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_system_prompt.txt", encoding="utf-8").read()
H = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_hint.txt", encoding="utf-8").read().split("2026-09-23T")[0].rstrip()
SYS = S + "\n严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + H
USER = "用户的需求：周末想去寿阳玩两天，喜欢古建筑，预算 500"   # 干净输入，脚本本身是 UTF-8 无 BOM 的 .py

print("%-8s %-8s %-7s %-9s %-10s %-10s %s" % ("effort", "http", "sec", "in_tok", "out_tok", "reasoning", "destination"))
for eff in ("none", "minimal", "low", "high"):
    body = json.dumps({"model": "deepseek-flash",
                       "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": USER}],
                       "temperature": 0.3, "max_tokens": 32768, "stream": False,
                       "reasoning_effort": eff}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("https://api.deepseek.com/v1/chat/completions", data=body,
                                 headers={"Authorization": "Bearer " + AK, "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=400) as r:
            d = json.load(r)
        sec = time.time() - t0
        u = d["usage"]
        m = d["choices"][0]["message"]
        c = m.get("content") or ""
        rc = m.get("reasoning_content") or ""
        try:
            dest = json.loads(c).get("destination", "(无)")
        except Exception:
            dest = "(非JSON)"
        print("%-8s %-8s %-7.1f %-9d %-10d %-10d %s" % (eff, "200", sec, u["prompt_tokens"],
                                                        u["completion_tokens"], len(rc), dest))
    except Exception as e:
        print("%-8s ERROR %s" % (eff, e))
