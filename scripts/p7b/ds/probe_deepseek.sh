#!/usr/bin/env bash
# 探测 deepseek-flash 在不同 reasoning_effort 档位下的表现（项目真实场景：结构化 JSON 输出）
set -u
ENVF="D:/Code/Vibe coding test/Wayfare/wayfare-backend/.env.properties"
AK=$(grep '^DEEPSEEK_API_KEY=' "$ENVF" | cut -d= -f2- | tr -d '\r')
OUT="$TEMP/wf_p7b/ds"
mkdir -p "$OUT"

# 与项目 PARSE 阶段同类的任务：自然语言 -> 结构化 JSON
SYS='你是行程意图解析器。只输出一个 JSON 对象，不要任何解释或 markdown 代码块。'
USR='周末想去寿阳玩两天，喜欢古建筑，预算 500。输出 JSON，字段：destination(字符串)、days(整数)、preferenceTags(字符串数组)、budgetTotal(整数)。'

for EFF in none minimal low medium high; do
  BODY=$(cat <<JSON
{"model":"deepseek-flash","messages":[{"role":"system","content":"$SYS"},{"role":"user","content":"$USR"}],"max_tokens":3000,"temperature":0.3,"reasoning_effort":"$EFF","response_format":{"type":"json_object"}}
JSON
)
  echo "$BODY" > "$OUT/req_$EFF.json"
  T=$(curl -s -m 300 -o "$OUT/resp_$EFF.json" -w "%{http_code}|%{time_total}" \
      https://api.deepseek.com/chat/completions \
      -H "Authorization: Bearer $AK" -H "Content-Type: application/json" \
      --data-binary @"$OUT/req_$EFF.json")
  echo "effort=$EFF http|time=$T"
done
