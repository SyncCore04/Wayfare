# 端到端降级演练报告（P7-C）

> 本文件由 `scripts/drill-fallback.ps1` 自动生成，请勿手改。
> 生成时间：2026-09-24 09:35:05
> 固定输入：`周末想去大同玩两天，喜欢古建筑，预算 500`

## 一、核心对比表（可直接复制进 PPT）

| 维度 | 地图关闭（估算模式） | 地图开启（实测模式） |
|---|---|---|
| 数据来源标记 | ESTIMATED × 10 | VERIFIED × 11（其余为 CACHED/ESTIMATED） |
| 距离字段 | 全部为空 | 9 / 11 条有值 |
| 文案距离表述 | 模糊（「相距不远」「步行即可到达」） | 由实测距离驱动 |
| 生成耗时 | 15.9 s | 15.5 s |
| 是否可用 | ✅ 完整可用 | ✅ 完整可用 |
| 行程条目数 | 10 | 11 |

**一句话结论**：关掉地图能力后系统仍然产出**结构完整、可执行的行程**（10 个条目），
只是所有事实性数据降级为「估算」标记、距离字段留空、文案不再给精确数字 ——
这就是铁律二「连接器可插拔、任一时刻系统完整可用」的可验证证据。

## 二、故障注入（模拟「百度地图服务挂了」）

做法：把 `map.baidu.ak`（L2 配置，**改完立即生效、不用重启**）写成一个非法值，
让真实请求返回 `status != 0`，从而累计熔断失败次数（阈值 `map.breaker.fail-threshold=5`）。

| 观察项 | 结果 |
|---|---|
| 注入前清掉的 POI 缓存键 | 5 个（保证请求真的打到百度，而不是命中缓存） |
| 注入用检索 | 朔州 / 古建筑 |
| 连续失败次数 | 5 |
| 熔断后 mode | CACHED |
| degraded 标记 | True |
| 降级原因 | 百度地图连续失败已触发熔断，暂不发起请求 |
| 生成是否中断 | ✅ 未中断，仍返回 tripId 422（9 个条目，耗时 12.6 s） |

**结论**：地图上游整体挂掉时，熔断器自动打开，决策器改走缓存/估算，
**生成流程不中断、用户侧无感知故障**。演练结束后已恢复 AK 并复位熔断。

## 三、断言明细

| # | 断言 | 结果 | 实际值 |
|---|---|---|---|
| 1 | 后端可达（/health 返回 status=UP） | ✅ 通过 | {"code":200,"message":"操作成功","data":{"status":"UP","app":"wayfare","version":"1.0.0","time":"2026-09-24T09:34:12.534600300"},"timestamp":1790213652534} |
| 2 | 管理员登录成功（拿到 JWT） | ✅ 通过 | token 长度=232 |
| 3 | A1 诊断接口 mode = ESTIMATED | ✅ 通过 | mode=ESTIMATED |
| 4 | A2 生成完整可用（拿到 tripId、无 composeError） | ✅ 通过 | tripId=420 composeError= |
| 5 | A3 全部条目 verifyStatus = ESTIMATED（共 10 条） | ✅ 通过 | 非 ESTIMATED 的条目数=0 取值= |
| 6 | A4 全部条目 distanceMeters = null | ✅ 通过 | 有距离的条目数=0 |
| 7 | A5 meta.mapMode = ESTIMATED | ✅ 通过 | meta.mapMode=ESTIMATED |
| 8 | A6 文案中没有「N 公里」这类精确距离表述 | ✅ 通过 | 命中 0 处： |
| 9 | A7 文案中没有「N 分钟」这类精确时长表述 | ✅ 通过 | 命中 0 处：（估算模式应写「约十几分钟」这种不带数字的模糊表述） |
| 10 | B1 诊断接口 mode = VERIFIED（地图可用） | ✅ 通过 | mode=VERIFIED（若为 CACHED/ESTIMATED 说明 AK 不可用或熔断未复位） |
| 11 | B2 生成完整可用（拿到 tripId、无 composeError） | ✅ 通过 | tripId=421 composeError= |
| 12 | B3 存在 verifyStatus = VERIFIED 的条目 | ✅ 通过 | VERIFIED 条目数=11 / 共 11 条 |
| 13 | B4 存在 distanceMeters 有值的条目 | ✅ 通过 | 有距离的条目数=9 |
| 14 | B5 meta.mapMode = VERIFIED | ✅ 通过 | meta.mapMode=VERIFIED |
| 15 | C1 连续调用已产生失败（fail-threshold=5，实际 5 次） | ✅ 通过 | 观察到失败 5 次（city=朔州 keyword=古建筑） |
| 16 | C2 熔断后降级生效：mode 变为 CACHED 或 ESTIMATED | ✅ 通过 | mode=CACHED degraded=True reason=百度地图连续失败已触发熔断，暂不发起请求 |
| 17 | C3 地图全挂时生成流程不中断（仍返回 tripId） | ✅ 通过 | tripId=422 composeError= |
| 18 | C4 恢复 AK + 复位熔断后 mode 回到 VERIFIED | ✅ 通过 | mode=VERIFIED |
| 19 | R1 map.enabled 已恢复到初始值 True | ✅ 通过 | mode=VERIFIED（期望 VERIFIED） |
| 20 | R2 map.baidu.ak 已清回空串（回落 .env.properties） | ✅ 通过 | ak= |

**合计 20 / 20 通过。**

## 四、复现方式

```powershell
cd D:\Code\Vibe coding test\Wayfare
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1
```

> 会临时改动 `map.enabled` 与 `map.baidu.ak` 两个 L2 配置，**脚本结束自动恢复原值**；
> 阶段 B 会真实调用百度地图（消耗日配额）。加 `-SkipFaultInjection` 可跳过阶段 C。
