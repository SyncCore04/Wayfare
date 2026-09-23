import io, json, os, urllib.request

ENVF = r"D:\Code\Vibe coding test\Wayfare\wayfare-backend\.env.properties"
AK = None
for line in io.open(ENVF, encoding="utf-8"):
    if line.startswith("DEEPSEEK_API_KEY="):
        AK = line.split("=", 1)[1].strip()

REAL_SYS = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_system_prompt.txt", encoding="utf-8").read()
REAL_HINT = io.open(r"C:\Users\Apollo\AppData\Local\Temp\wf_p7b\real_hint.txt", encoding="utf-8").read()
REAL_HINT = REAL_HINT.split("2026-09-23T")[0].rstrip()
SYS = REAL_SYS + "\n严格只输出一个合法的 json 对象，不要输出任何解释文字，也不要用 markdown 代码块包裹。\n期望的 json 结构如下：\n" + REAL_HINT
USER = "用户的需求：周末想去寿阳玩两天，喜欢古建筑，预算 500"

body = json.dumps({"model": "deepseek-flash",
                   "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": USER}],
                   "temperature": 0.3, "max_tokens": 32768, "stream": False,
                   "reasoning_effort": "none"}, ensure_ascii=False).encode("utf-8")

print("env proxies:", {k: v for k, v in os.environ.items() if "proxy" in k.lower()})

def run(label):
    req = urllib.request.Request("https://api.deepseek.com/v1/chat/completions", data=body,
                                 headers={"Authorization": "Bearer " + AK, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            d = json.load(r)
        c = d["choices"][0]["message"].get("content") or ""
        try:
            dest = json.loads(c).get("destination", "(无)")
        except Exception:
            dest = "(非JSON)"
        print("%-28s destination=[%s]" % (label, dest))
    except Exception as e:
        print("%-28s ERROR %s" % (label, e))

# A. 默认（走环境变量里的代理）
run("A_with_env_proxy")

# B. 绕过代理（清空 proxy 环境变量 + 显式 no_proxy）
for k in list(os.environ.keys()):
    if "proxy" in k.lower():
        del os.environ[k]
run("B_no_proxy")

# C. 再走一次默认（对照）
os.environ["HTTPS_PROXY"] = "http://127.0.0.1:12093"
os.environ["HTTP_PROXY"] = "http://127.0.0.1:12093"
run("C_proxy_again")
