#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第六项分析：从阶段日志按 trip_id 聚合，算每个模型的 tokens 与成本（P4-C 公式）。

用法：python analyze_item6.py <runs.tsv> <log_dump.tsv>
成本 = 输入×in价/1e6 + 输出×out价/1e6；单价（元/百万 token）：
  deepseek-flash 空闲时段 in=1 / out=4（官方峰谷价，高峰翻倍）
  glm-5.3-flash      in=0.8 / out=2.8（官方报价）
  qwen3.8-flash      免费档 → 0
"""
import io, sys
from collections import defaultdict

PRICE = {
    "deepseek": (1.0, 4.0),
    "glm": (0.8, 2.8),
    "qwen": (0.0, 0.0),
}

runs_path, log_path = sys.argv[1], sys.argv[2]

# ---- 读采集结果 ----
runs = []
for line in io.open(runs_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) < 12 or p[0] == "provider":
        continue
    runs.append({"provider": p[0], "idx": p[1], "ok": p[2] == "True", "tripId": p[4],
                 "durationMs": p[5], "metaTokens": p[6], "metaEstCost": p[7], "rounds": p[8],
                 "composeError": p[10] if len(p) > 10 else "", "title": p[11] if len(p) > 11 else ""})

# ---- 读阶段日志 ----
log = defaultdict(lambda: {"in": 0, "out": 0, "stages": defaultdict(int), "providers": set(),
                           "dur": 0, "fails": 0})
for line in io.open(log_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) < 11 or p[0] == "id":
        continue
    # id trip_id stage provider model prompt completion total duration success
    trip, stage, prov = p[1], p[2], p[3]
    if trip in ("NULL", ""):
        continue
    try:
        pin = int(p[5]) if p[5] not in ("NULL", "") else 0
        pout = int(p[6]) if p[6] not in ("NULL", "") else 0
        dur = int(p[8]) if p[8] not in ("NULL", "") else 0
    except ValueError:
        continue
    rec = log[trip]
    rec["in"] += pin
    rec["out"] += pout
    rec["dur"] = max(rec["dur"], dur)
    rec["stages"][stage] += 1
    if prov not in ("NULL", ""):
        rec["providers"].add(prov)
    if p[9] == "0":
        rec["fails"] += 1

print("=" * 100)
print("%-9s %-4s %-9s %-8s %-8s %-8s %-8s %-10s %-9s %s" % (
    "provider", "idx", "trip", "sec", "in_tok", "out_tok", "tot_tok", "cost(元)", "stages", "providers"))
print("=" * 100)
per = defaultdict(lambda: {"in": [], "out": [], "cost": [], "sec": [], "trips": 0, "mock": 0,
                           "fail": 0, "att": 0})
for r in runs:
    t = r["tripId"]
    succ = r["composeError"] in ("", "None") and bool(t)
    per[r["provider"]]["att"] += 1
    if not succ:
        per[r["provider"]]["fail"] += 1
        print("%-9s %-4s %-9s %-8.1f  ❌ 失败样本：%s" % (
            r["provider"], r["idx"], t or "-", int(r["durationMs"] or 0) / 1000,
            r["composeError"][:70]))
        continue
    if t not in log:
        per[r["provider"]]["fail"] += 1
        print("%-9s %-4s %-9s %-8.1f  ❌ 无阶段日志（未落库）" % (
            r["provider"], r["idx"], t, int(r["durationMs"] or 0) / 1000))
        continue
    rec = log[t]
    pin_, pout_ = PRICE[r["provider"]]
    cost = rec["in"] * pin_ / 1e6 + rec["out"] * pout_ / 1e6
    stg = "+".join("%s×%d" % (k, v) for k, v in sorted(rec["stages"].items()))
    provs = ",".join(sorted(rec["providers"]))
    is_mock = "mock" in rec["providers"]
    if is_mock:
        per[r["provider"]]["mock"] += 1
    print("%-9s %-4s %-9s %-8.1f %-8d %-8d %-8d %-10.5f %-9s %s" % (
        r["provider"], r["idx"], t, int(r["durationMs"] or 0) / 1000, rec["in"], rec["out"],
        rec["in"] + rec["out"], cost, stg, provs))
    per[r["provider"]]["in"].append(rec["in"])
    per[r["provider"]]["out"].append(rec["out"])
    per[r["provider"]]["cost"].append(cost)
    per[r["provider"]]["sec"].append(int(r["durationMs"] or 0) / 1000)
    per[r["provider"]]["trips"] += 1

print()
print("=" * 104)
print("%-9s %-13s %-11s %-11s %-12s %-13s %-11s %s" % (
    "provider", "成功/尝试", "avg_in", "avg_out", "avg_total", "avg_cost(元)", "avg_sec", "单价(元/百万)"))
print("=" * 104)
for prov in ("deepseek", "glm", "qwen"):
    d = per[prov]
    if not d["in"]:
        print("%-9s %-13s (无有效样本)" % (prov, "%d/%d" % (0, d["att"])))
        continue
    n = len(d["in"])
    print("%-9s %-13s %-11.0f %-11.0f %-12.0f %-13.5f %-11.1f in=%s/out=%s%s" % (
        prov, "%d/%d" % (n, d["att"]), sum(d["in"]) / n, sum(d["out"]) / n,
        (sum(d["in"]) + sum(d["out"])) / n,
        sum(d["cost"]) / n, sum(d["sec"]) / n, PRICE[prov][0], PRICE[prov][1],
        "  ⚠️有 %d 次回落到 mock" % d["mock"] if d["mock"] else ""))
print()
print("失败样本数：", {p: per[p]["fail"] for p in ("deepseek", "glm", "qwen")})
