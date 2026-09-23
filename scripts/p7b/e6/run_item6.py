#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P7-B 第六项采集驱动：3 个模型各跑 5 次完整生成。

设计要点（都是本项目踩过的坑）：
- 请求体从 UTF-8 的 .json 文件读，**脚本里不写中文字面量**（.ps1 无 BOM 会被 GBK 拆碎）
- 每轮前探活（沙箱会定期回收后端进程），失败即停并写标记 → 可续跑
- 结果增量写 TSV，已完成的 (provider,index) 自动跳过
"""
import io, json, os, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8080/api"
TMP = os.path.join(os.environ.get("TEMP", "/tmp"), "wf_p7b", "e6")
os.makedirs(TMP, exist_ok=True)
ROWS = os.path.join(TMP, "runs.tsv")
BODY = json.load(io.open(os.path.join(os.environ.get("TEMP", "/tmp"), "wf_p7b", "body_parse.json"),
                         encoding="utf-8"))
PLAN = sys.argv[1] if len(sys.argv) > 1 else "deepseek:1,deepseek:2,deepseek:3,deepseek:4,deepseek:5," \
                                               "glm:1,glm:2,glm:3,glm:4,glm:5," \
                                               "qwen:1,qwen:2,qwen:3,qwen:4,qwen:5"


def call(method, path, payload=None, token=None, timeout=900):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8")
        return True, time.time() - t0, body
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8")
        except Exception:
            body = str(e)
        return False, time.time() - t0, "HTTP %s %s" % (e.code, body[:300])
    except Exception as e:
        return False, time.time() - t0, "ERR %s" % e


def healthy():
    try:
        with urllib.request.urlopen(BASE + "/health", timeout=8) as r:
            return '"status":"UP"' in r.read().decode("utf-8")
    except Exception:
        return False


def done_keys():
    if not os.path.exists(ROWS):
        return set()
    keys = set()
    for line in io.open(ROWS, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) > 1 and p[0] != "provider":
            keys.add(p[0] + ":" + p[1])
    return keys


if not os.path.exists(ROWS):
    io.open(ROWS, "w", encoding="utf-8").write(
        "provider\tidx\tok\thttpSec\ttripId\tdurationMs\tmetaTokens\tmetaEstCost\trounds\tmapMode\tcomposeError\ttripTitle\n")

ok, sec, body = call("POST", "/auth/login", {"username": "admin", "password": "Admin123456"})
if not ok:
    print("LOGIN FAIL", body)
    sys.exit(2)
TOK = json.loads(body)["data"]["token"]
print("login ok")

for item in [x.strip() for x in PLAN.split(",") if x.strip()]:
    provider, idx = item.split(":")
    if provider + ":" + idx in done_keys():
        print("skip (done):", item)
        continue
    if not healthy():
        print("BACKEND_DOWN before", item, "-> stop, resume later")
        sys.exit(3)
    # 隔离：active + fallback 都只留当前厂商（链尾只剩 mock），避免被别家悄悄救回来
    call("POST", "/admin/config/llm", {"activeProvider": provider, "fallbackOrder": provider}, TOK, 30)
    ok, sec, body = call("POST", "/trip/plan/sync", BODY, TOK, 900)
    trip = dur = tok = cost = rounds = mode = cerr = title = ""
    if ok:
        try:
            d = json.loads(body)["data"]
            trip = d.get("tripId")
            m = d.get("meta") or {}
            dur = m.get("durationMs")
            tok = m.get("tokens")
            cost = m.get("estCost")
            rounds = m.get("rounds")
            mode = m.get("mapMode")
            cerr = d.get("composeError")
            title = (d.get("trip") or {}).get("title")
        except Exception as e:
            cerr = "PARSE_ERR " + str(e)
    else:
        cerr = body[:200].replace("\t", " ")
    io.open(ROWS, "a", encoding="utf-8").write(
        "%s\t%s\t%s\t%.1f\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" % (
            provider, idx, ok, sec, trip, dur, tok, cost, rounds, mode, cerr, title))
    print("%-9s #%s ok=%s %.1fs trip=%s tokens=%s estCost=%s rounds=%s title=%s" % (
        provider, idx, ok, sec, trip, tok, cost, rounds, title))
    sys.stdout.flush()

print("ALL DONE")
