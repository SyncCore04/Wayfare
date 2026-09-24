# Wayfare · 行走集

> AI 旅游攻略平台 —— 输入目的地与天数，AI 帮你排出可执行的行程，再把它变成一篇能分享的图文攻略。

`Spring Boot 3.3.5` `JDK 17` `MyBatis-Plus 3.5.7` `MySQL 8.0` `Redis 7` `Vue 3.4` `Vite 5` `Element Plus 2.6`

---

## 这是什么

大多数"AI 旅游规划"演示的流程是：把问题扔给大模型，把返回的文本渲染成卡片。看起来很好，但它有两个致命伤——**行程里的距离和时长是模型编的**，以及**服务一挂整个功能就消失**。

Wayfare 把这两个问题当成架构问题来解决：

- **大模型只做语义决策**（分天、选点、写讲解），**坐标、距离、时长只来自地图 API**，拿不到就明确标记 `ESTIMATED`——绝不让模型编一个"距离 3.2 公里"。
- **连接器可插拔，任一时刻系统完整可用**：地图被管理员整体关闭后，全流程自动切换到估算模式继续跑，界面上只是角标从绿（实测）变成橙（估算）——这是**能力降级，不是功能降级**。
- **大模型可热切换**：Qwen（阿里百炼）/ 智谱 GLM / DeepSeek 三家走同一套 OpenAI 兼容协议，改一条数据库配置即完成切换，无需重启；主力过载时按序自动降级，全部不可用时回落离线 Mock，链路照样演示。

一句话概括设计哲学：**模型只是强大的引擎，变速箱和离合器得自己调——这个项目调的就是那套变速箱。**

## 核心设计

### 1. 七步行程编排管线

AI 生成不是一次 prompt，而是一条有质检的流水线：

```
意图解析 → 候选检索 → 空间预排 → 行程编排 → 约束校验 → 事实补全 → 结果组装
  (LLM)     (地图/缓存)   (本地算法)     (LLM 主战场)   (本地规则)    (地图回填)     (指标输出)
```

- **空间预排**用本地地理聚类算法先排一遍顺序，避免"上午在城东、下午跑城西"这种外行行程——LLM 拿到的是已经按地理位置排好的候选序列，只做语义层面的取舍。
- **约束校验**是本地的七条规则（点位重复、时序重叠、折返、日通勤总量、预算超支、忌口命中、单日点位密度等），对 LLM 输出做质检，不合格打回重排——**最多两轮，绝不死循环**。
- **事实补全**用地图 API 回填真实距离与时长，并打上可信度标记：`VERIFIED`（实测）/ `CACHED`（缓存）/ `ESTIMATED`（估算）。

### 2. 三层配置 + 三级降级

```
L1 启动期配置   application.yml        连接地址、模型名、超时（重启生效）
L2 运行期开关   sys_config + Redis     切厂商 / 开关地图 / 熔断阈值（30 秒内生效，无需重启）
L3 降级         熔断器 + 分级 Provider  主力挂了切备用，全挂了回落 Mock
```

地图连接器的三级降级：

```
实时调用(BaiduMapProvider) → 熔断/断网时查本地缓存(LocalCacheMapProvider) → 整体关闭(DisabledMapProvider)
     VERIFIED                      CACHED                              ESTIMATED
```

熔断器基于 Redis 计数实现（连续失败 ≥ 阈值即打开，到期半开放行一次试探），状态可通过诊断接口观测。

### 3. 统一出站治理

所有外部 HTTP 调用（Qwen / GLM / DeepSeek / 百度地图）走同一个治理封装：

- **重试**：GET 失败重试 2 次（退避 200 ms / 600 ms），POST 默认不重试——重复调用可能产生费用。
  **唯一例外是 HTTP 429**（退避 1 s / 3 s）：限流等一会儿确实会好，而 4xx 客户端错误重试无意义。
- **日志**：每次尝试落一条 `external_call_log`（连接器、接口、耗时、成败、脱敏后的请求摘要），是成本统计与监控看板的数据源。
- **脱敏**：AK / API Key / Bearer 凭证在写日志前统一过 `MaskUtil`，库里不允许出现完整密钥。
- **缓存**：POI 检索 24h、详情 7d、路线 12h（Redis），**大模型调用一律不缓存**——同一个输入需要可复现的新结果。

### 4. 鉴权与安全

- 自建 `JwtInterceptor`（不引 Spring Security），无 token / token 无效 / 已登出（Redis 黑名单）统一 401。
- 管理接口与公开接口的边界由白名单显式定义，公开路径用正则约束（`/works/{id:[0-9]+}`）避免把 `/works/my` 一并放行。
- 密钥全部走环境变量或 gitignore 的本地配置文件，仓库内不含任何真实 Key；所有回显一律掩码（前 4 位 + `****`）。

### 5. 流式输出：把「等一分钟」拆成「先看到行程」

一次完整生成要跑 **3 次非流式大模型调用**（意图解析 / 候选检索 / 行程编排），**骨架阶段根本没法边想边吐**；
P7 实测（`docs/metrics.md` 第四项，5 次）：

| 模型 | 骨架（`itinerary` 事件） | 全文（`done`） | 骨架占比 |
|---|---|---|---|
| `glm-5.3-flash` | **71.0 s** | **87.7 s** | 81% |
| `deepseek-flash` | — | **9.2 s**（整轮） | — |

浏览器与 axios 的默认超时都撑不到，用户只会看到一个转圈然后失败。所以生成走 SSE，事件分五类：

```
stage（六阶段进度）→ itinerary（行程骨架）→ delta（攻略文案增量）→ done / error
```

关键在于 **`itinerary` 与 `delta` 的分工就是降级出口**：行程骨架（Step 6 事实补全后立即推送）一旦送达，
攻略文案哪怕整段生成失败，用户手里也已经有一份带可信度角标的完整行程——**文案是锦上添花，不是必需项**。

> ⚠️ 手册原定的目标「骨架 ~15 秒」**架构上做不到**，如实写进 `docs/metrics.md` 第四项。

客户端断开时，增量回调里抛异常即可掐断上游的读取循环（连接器层不需要新增取消 API），
中断会落一条 `success=0 + CLIENT_DISCONNECTED` 的日志。但**这条链路的实际效果比设计预期弱得多**，
P7 用 9 次实测把它量了出来（`docs/metrics.md` 第五项）：

- **「客户端断开即中断 LLM 流」只有 3/9 成立** —— 另 6 次模型把整篇生成完并照常落库。
  原因：检测点是「下一次 `send()` 抛异常」，而 delta 写入平均只有 **1.4 字符/块**，
  被 socket 缓冲吸收后 `send()` 会一直成功，服务端察觉不到。
- **日志里那句「节省约 N tokens」高估约 14 倍** —— 它取的是「同类请求整篇输出的均值」，
  **没有减去已产出的部分**。实测自报 429 / 已产出 399 → 真实净节省只有约 **30 tokens**。

所以本项目对这条能力的口径是：**价值在于「不落库半成品数据」，不在于省成本**；
拿不到 usage 时如实写「未知」，**绝不按字符数换算一个 token 数出来**。

### 6. 后台可观测性：开关能当场拨、降级能当场演示

后台两个页面（`/admin/connectors` 连接器管理、`/admin/generation` AI 生成监控）建立在同一套
「只读诊断 + 热改配置」接口上，三件事值得说：

- **改配置不重启**：`PUT /admin/configs/{key}` 写库后立即清 Redis 缓存。验证口径也是可执行的 ——
  「先读一次（把值灌进缓存）→ 写新值 → 立刻再读」，能读回新值才说明缓存真被清了
  （读一个直查数据库的接口是证明不了这件事的）。
- **一键降级演练**：后台有个按钮关掉地图能力，之后的生成 `verifyStatus` 全为 `ESTIMATED`、
  距离与时长留空并标注「估算」、`mapMode=ESTIMATED`，**系统功能不受影响**；
  且关闭后零外部调用，不消耗任何地图额度。这正是「连接器可插拔」的现场证明。
  按钮只是「拨开关」，**要出证据请跑脚本**（见下）。

#### 端到端降级演练脚本（一条命令出报告）

```powershell
# 仓库根目录执行（后端需已启动）
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1
# 只想看降级对比、不做故障注入：
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1 -SkipFaultInjection
```

跑完「降级 → 验证 → 恢复 → 验证 → 出报告」全流程，做 **20 条断言**并把结果写进
[`docs/drill-report.md`](docs/drill-report.md)（含一张可直接复制进 PPT 的对比表）：

| 阶段 | 做什么 | 关键断言 |
|---|---|---|
| A 关地图 | 生成一次行程 | 全部条目 `verifyStatus=ESTIMATED`、`distanceMeters` 全空、`meta.mapMode=ESTIMATED`、**文案里不出现「N 公里」「N 分钟」这类精确数字** |
| B 开地图 | 同输入再生成一次 | 诊断接口 `mode=VERIFIED`、存在 `VERIFIED` 条目、存在有距离的条目 |
| C 故障注入 | 把 `map.baidu.ak` 临时写成非法值，连续触发 5 次失败 | 熔断打开 → `mode` 降为 `CACHED`、`degraded=true`，**生成流程不中断**（仍返回 tripId） |
| 收尾 | 恢复 `map.enabled` 与 AK、复位熔断 | 状态回到演练前 |

注意事项（脚本里都写明了）：

- 会临时改动 `map.enabled` 与 `map.baidu.ak` 两个 **L2 配置**（改完立即生效、不用重启），
  **脚本结束自动恢复原值**；
- **阶段 B 会真实调用百度地图，消耗日配额**（约 6 次地点检索）；
- 故障注入前会**先清 Redis 里的 POI 缓存**，否则请求命中缓存就测不到熔断（这是本脚本踩过的坑）；
- 断言失败时**明确报出是哪一条、实际值是什么**，并以退出码 1 结束 —— 不吞失败。
  首轮演练就靠它抓到过一个真缺陷：估算模式的兜底文案 `约 15 分钟左右可达` **带精确数字**，
  与它自己声明的「禁止给精确数字」矛盾（已修）。
- **诊断信息必须与事实一致**：`GET /diagnostics/llm/providers` 逐厂商返回模型名、Base URL、
  **掩码后的 Key**（前 4 位 + `****`）、可用性与熔断快照。这里修过一个真 bug —— 熔断快照原先写死读
  `map.breaker.*`，于是大模型被显示成「阈值 5」，而它实际第 3 次失败就跳闸：**诊断信息说谎比没有信息更坏**。
  现在快照按配置前缀读各自阈值（大模型 3 / 地图 5，因为地图一次失败只损失几百毫秒，
  大模型一次失败要白等一整个 90 秒超时）。
- **安全验收点就是页面本身**：外呼日志页签直接展示 `request_summary`，而它是**写入时**脱敏的
  （先脱敏再截断，顺序不能反），所以页面上看到的必然是 `ak=abcd****` 这种形态。

## 实测指标（数字全部来自本机真实运行，不抄外部数据）

> 完整测量方法、原始数据、失败样本与诚实提醒见 **[`docs/metrics.md`](docs/metrics.md)**；
> 降级演练见 **[`docs/drill-report.md`](docs/drill-report.md)**；
> 2-opt 量化见 **[`docs/metrics-preorder.md`](docs/metrics-preorder.md)**。

| 指标 | 实测结论 | 口径与诚实提醒 |
|---|---|---|
| **2-opt 空间预排** | 当候选顺序存在明显交叉时，总里程再降 **9%~25%**；顺直点集改进为 **0** | 不能写「平均降 9.3%」（那会把两组 0% 平摊掉） |
| **POI 缓存冷热** | 冷 **329 ms / P95 620 ms** → 热 **8.4 ms / P95 9 ms**（**快 39×**），20 次热调对外 HTTP 请求 **0** 次 | 这是**单次检索**的收益；一轮规划约 6 次检索 ≈ 省 1.9 s，占整条管线 82 s 的 **2%** —— 价值在**省配额与抗限流**，不在缩短整轮耗时 |
| **校验收敛率** | 30/30 次生成，**两轮内收敛 90.0%** | 不能报「100%」——那是把「只有 MEDIUM 时只排一次」误当收敛 |
| **流式输出时间** | 骨架 **71.0 s** / 全文 **87.7 s**（glm），骨架占 **81%**；deepseek 整轮 **9.2 s** | 手册目标「骨架 ~15 s」**架构上做不到**（要等 3 次非流式调用） |
| **客户端断开中断** | **9 次只有 3 次真掐断**；真实净节省仅约 **30 tokens** | 「断开即中断」只有 1/3 成立，价值在**不落半成品数据** |
| **单次生成成本** | `deepseek-flash` **4,888 tok / ¥0.0107 / 9.2 s**；`glm-5.3-flash` 5,436 tok / **¥0.0084** / 62.6 s | 性价比要看**单次总成本**而非单价：deepseek 单价更高但输出更省，总价只贵 27%、快 6.8 倍；成本要带时段（DeepSeek 峰谷价，高峰上限约 ¥0.021） |

**一个被实测淘汰的选项**：`qwen3.8-flash`（免费档）实测 **476 s/次、失败率 55%、输出 token 是另两家的 13~16 倍**
（推测思维链计入 `completion_tokens`）。**「免费」在这里是陷阱 —— 一次 qwen 的 token 量够 deepseek 跑 6.5 次。**

**降级演练（一条命令，20 条断言全绿）**：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1
```

关掉地图后仍产出**结构完整可执行的行程**（10 个条目），只是 `verifyStatus` 全为 `ESTIMATED`、距离字段留空、
文案不再给精确数字；故障注入（把百度 AK 写坏触发熔断）后 **`mode` 自动降为 `CACHED`、生成流程不中断**。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 3.3.5 / JDK 17 / MyBatis-Plus 3.5.7 | 纯 Spring MVC，不引 WebFlux |
| 鉴权 | jjwt 0.12.6 + 自建拦截器 | 刻意不引 Spring Security 全家桶 |
| 数据 | MySQL 8.0 / Redis 7 | 22 张表（内容域 14 + 行程治理域 8） |
| 大模型 | 阿里百炼 Qwen / 智谱 GLM / DeepSeek / Mock | OpenAI 兼容协议，公共逻辑收敛在抽象基类 |
| 地图 | 百度地图 Web 服务 | 可整体关闭，关闭后全流程估算 |
| 前端 | Vue 3.4 / Vite 5 / Element Plus 2.6 / Pinia | 纯 JavaScript，无 TypeScript |
| 文档 | springdoc-openapi (Swagger UI) | 启动即可访问 |

## 快速开始

```bash
# 1. 环境要求：JDK 17、Maven 3.9+、Node 18+（Vite 5 要求）、MySQL 8.0、Redis 7

# 2. 建库（两份脚本：内容域 + 行程域，均幂等）
mysql -u root -p < db/schema.sql
mysql -u root -p < db/schema-trip.sql

# 3. 配置密钥与数据库密码（本文件已被 gitignore，仓库内只有 .example 模板）
cd wayfare-backend
copy .env.properties.example .env.properties
#   编辑 .env.properties：MYSQL_ROOT_PASSWORD 必填；
#   三家大模型 Key（QWEN / GLM / DEEPSEEK）与 BAIDU_MAP_AK 都可不填 ——
#   不填大模型 Key 自动回落离线 Mock，不填地图 AK 自动走估算模式，全链路照常演示

# 4. 启动后端（8080，context-path /api）
mvn spring-boot:run
#    启动日志末尾会打印「Wayfare 后端启动成功」与接口文档 / 健康检查地址

# 5. 启动前端（5173，已代理 /api 与 /uploads）
cd ../wayfare-frontend
npm install
npm run dev
```

> 没有任何大模型 Key？不需要申请也能跑：三家都未配置时自动回落离线 Mock，
> 全链路（含前端流式渲染）照常可演示，只是内容由 Mock 生成。

- 健康检查：`GET http://localhost:8080/api/health`
- 接口文档：`http://localhost:8080/api/swagger-ui/index.html`
- 连接器诊断：`GET /api/diagnostics/connectors`（管理员）
- 行程规划（同步）：`POST /api/trip/plan/sync`
- 行程规划（流式）：`POST /api/trip/plan/stream`（SSE，事件协议见「流式输出」一节）
- 只重生成文案：`POST /api/trip/{id}/regenerate-copy?provider=glm`（不重跑管线，可指定厂商做效果对比）
- 生成统计：`GET /api/admin/generation/stats?from=&to=`（管理员，token / 成本 / 成功率 / P95）
- 生成趋势：`GET /api/admin/generation/trend?days=7`（管理员，每日次数与成本，供看板折线图）
- 生成明细：`GET /api/admin/generation/logs?from=&to=&success=&stage=&model=&destination=&mapMode=&page=&size=`（管理员）
- 外呼日志：`GET /api/admin/generation/external-calls?connector=&page=&size=`（管理员，`request_summary` 已脱敏）
- 厂商清单与状态：`GET /api/diagnostics/llm/providers`（管理员，模型名 / Base URL / 掩码 Key / 熔断快照）

## 接口清单

> 本节由 [`scripts/gen-api-list.py`](scripts/gen-api-list.py) **从真实注解自动生成**，请勿手改 ——
> 手写清单一定会和代码漂移。完整清单（含每个端点的说明与权限）见 [`docs/接口清单.md`](docs/接口清单.md)。
>
> 重新生成：
> ```bash
> "C:/Users/Apollo/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" scripts/gen-api-list.py
> ```

<!-- BEGIN:API-LIST -->

| Controller | 端点数 | 公开 | 管理员 | 类级路径 |
|---|---|---|---|---|
| `AdminConfigController` | 6 | 0 | 6 | `/admin` |
| `AdminGenerationController` | 5 | 0 | 5 | `/admin/generation` |
| `AuthController` | 3 | 2 | 0 | `/auth` |
| `CategoryController` | 6 | 1 | 4 | `/categories` |
| `CommentController` | 6 | 1 | 0 | `/comments` |
| `DiagnosticsController` | 4 | 0 | 4 | `/diagnostics` |
| `FavoriteController` | 4 | 0 | 0 | `/favorites` |
| `FileController` | 3 | 0 | 0 | `/files` |
| `FollowController` | 9 | 0 | 0 | `/follows` |
| `HealthController` | 1 | 1 | 0 | `` |
| `LikeController` | 4 | 0 | 0 | `/likes` |
| `LlmDiagnosticController` | 3 | 0 | 3 | `/connector/llm` |
| `MapDiagnosticController` | 4 | 0 | 4 | `/connector/map` |
| `MessageController` | 6 | 0 | 0 | `/messages` |
| `RecommendController` | 7 | 1 | 0 | `/recommend` |
| `TagController` | 7 | 1 | 4 | `/tags` |
| `TravelProfileController` | 2 | 0 | 0 | `/profile` |
| `TripController` | 10 | 0 | 0 | `/trip` |
| `UserController` | 7 | 0 | 3 | `/users` |
| `WorkController` | 8 | 5 | 0 | `/works` |
| **合计** | **105** | **12** | **33** | — |

> 完整清单（含每个端点的说明与权限）见 [`docs/接口清单.md`](docs/接口清单.md)，由 `scripts/gen-api-list.py` 从真实注解生成。

<!-- END:API-LIST -->

## 数据表清单（22 张）

| 脚本 | 域 | 张数 | 表 |
|---|---|---|---|
| `db/schema.sql` | 内容域（沿用旧项目表名） | 14 | `sys_user` `user_third_account` `category` `tag` `work` `work_image` `work_tag` `comment` `like_record` `favorite` `follow` `private_message` `report` `admin_operation_log` |
| `db/schema-trip.sql` | 行程域 + 治理域 | 8 | `sys_config` `poi_cache` `external_call_log` `user_travel_profile` `trip` `trip_day` `trip_item` `ai_generation_log` |

> ⚠️ `trip` 表**没有任何外键约束** —— 删行程必须手工按序清理 `trip_day` / `trip_item` /
> `ai_generation_log` / `external_call_log`，否则会留下孤儿行（已实测踩过）。

## Redis key 设计

| 用途 | key 格式 | TTL | 写入位置 |
|---|---|---|---|
| 系统配置缓存 | `sys:config`（Hash） | 30 s | `SysConfigServiceImpl` —— 「L2 配置 30 秒内生效」就来自这里 |
| POI 检索 | `map:poi:baidu:{city}:{keyword}:{pageNum}:{pageSize}` | 24 h | Spring Cache（`CacheConfig`） |
| POI 详情 | `map:detail:{poiUid}` | 7 d | Spring Cache |
| 路线 | `map:route:{mode}:{fromLng},{fromLat}:{toLng},{toLat}` | 12 h | `LocalCacheMapProvider` **直连 Redis**（熔断降级时也要能读到同一份） |
| 熔断失败计数 | `cb:fail:{provider}` | — | `CircuitBreaker` |
| 熔断打开标记 | `cb:open:{provider}` | `*.breaker.open-seconds`（地图 300 s） | `CircuitBreaker` |
| JWT 黑名单 | `auth:blacklist:{token 的 SHA-256}` | 取 token 剩余有效期 | `TokenBlacklist`（登出时写入） |
| 浏览历史 / 社交计数 | `browse:history:*`、`browse:tags:*`、`like:count:*`、`favorite:count:*`、`follow:follower:*`、`follow:following:*` | — | 内容域 |

> **大模型调用一律不缓存** —— 同一个输入需要能拿到可复现的新结果，缓存会让「换个说法再问一次」看起来没反应。
>
> ⚠️ 已知缺陷（未修）：POI 检索的**空结果也会被缓存 24 h** —— `disableCachingNullValues()` 只挡 `null`、
> 不挡空 `List`，而查不到时返回的正是空 List。后果是**用户搜一个百度查不到的词，24 小时内再搜都直接返回空**。
> 修法只有一行（`@Cacheable` 加 `unless = "#result == null || #result.isEmpty()"`），见交接文档 §7.1。

## 环境变量

`wayfare-backend/.env.properties`（**已 gitignore，仓库内只有 `.env.properties.example` 模板**）：

| 变量 | 必填 | 说明 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | ✅ | 数据库密码 |
| `QWEN_API_KEY` | ⬜ | 阿里云百炼（免费档）；不填则该厂商不可用 |
| `GLM_API_KEY` | ⬜ | 智谱开放平台 |
| `DEEPSEEK_API_KEY` | ⬜ | DeepSeek 开放平台 |
| `BAIDU_MAP_AK` | ⬜ | 百度地图 Web 服务 AK；不填则地图整体不可用（自动走估算模式） |

> **三个大模型 Key 全都不填也能跑**：自动回落内置离线 Mock，全链路（含前端流式渲染）照常演示。
> 数据库 / Redis 连接地址、模型名、超时属 **L1 启动期配置**，在 `application.yml` 里，**改完必须重启**；
> 切厂商 / 开关地图 / 熔断阈值属 **L2 运行期配置**，在 `sys_config` 里，**改完 30 秒内生效、不用重启**。

## 测试账号

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `Admin123456` | 管理员（可进 `/admin/connectors` 与 `/admin/generation`） |

> 这是**演示凭据**，写在 README 里是有意的（答辩/面试要能直接登录看后台），不是泄漏。
> 新增真实账号请走 `POST /api/auth/register`，**不要把任何真实密码写进仓库**。

## 目录结构

```
Wayfare/
├── db/
│   ├── schema.sql          内容域 14 张表（用户/作品/评论/社交…）
│   └── schema-trip.sql     行程域与治理域 8 张表（运行时开关/POI 缓存/调用日志/
│                           偏好画像/行程三表/AI 生成日志）
├── wayfare-backend/
│   └── src/main/
│       ├── java/com/wayfare/
│       │   ├── common/         统一返回、全局异常、配置、脱敏工具
│       │   ├── connector/
│       │   │   ├── llm/        大模型连接器（qwen / glm / deepseek / mock + 决策器）
│       │   │   ├── map/        地图连接器（baidu / cache / disabled + 三级降级）
│       │   │   └── governance/ 出站治理（重试 / 熔断 / 日志 / 脱敏）
│       │   ├── controller/     REST 接口（内容域 + 社交域 + 偏好 + 行程规划 + 诊断与配置）
│       │   ├── dto/            请求与管线数据结构（IntentDTO / CandidateDTO …）
│       │   ├── entity|mapper/  22 张表的实体与 Mapper
│       │   ├── profile/        用户画像渲染器（渲染成注入 prompt 的中文文本块）
│       │   ├── trip/           编排管线：意图解析 / 候选检索 / 空间预排 / 行程编排 / 约束校验 / 事实补全，
│       │   │                   以及 SSE 取消闸门、攻略文案 prompt、阶段记录与错误码
│       │   └── security/       JWT 拦截器、Token 黑名单、用户上下文
│       └── resources/
│           └── map-preference-tag.json   偏好 → 地图检索词字典（可维护，改词不用改代码）
└── wayfare-frontend/
    └── src/{api,stores,router,layouts,components,views}/
```

## 工程实践

- **测试**：238 个单元 / 集成测试全绿（另有 6 个真实调用大模型与地图的验收测试默认跳过，
  用 `-Dwayfare.live=true` 显式开启），覆盖脱敏规则（6）、出站重试与降级策略（13）、
  用户画像渲染规则（11）、画像读写与 upsert 的 null 语义（7）、行程表族排序与数据诚信字段（5）、
  AI 日志聚合与成本计算（20，含输入输出分开计价与统计聚合）、实体与表结构映射（4）、
  **意图解析的 Schema 校验与失败重试（24）**、
  **候选检索的去重 / 忌口过滤 / 坐标诚信 / 候选不足降级 / 配额保护（24）**、
  **空间预排的贪心 + 2-opt、无坐标降级、距离公式（14）**、
  **行程编排的硬约束校验 / 失败降级 / 距离不采信（19）**、
  **约束校验的七条本地规则与脏输入守卫（35）**、**约束违规后的回喂重排（7）**、
  **SSE 取消闸门与中断说明（8）**、**攻略文案 prompt 的六条规则与「距离不进 prompt」（12）**、
  **熔断阈值与诊断快照（7，含大模型 / 地图阈值分离与 Redis 键命名空间）**、
  **管理接口的权限边界与密钥不外泄（12，含热生效与熔断可观测）**、
  **离线全链路的降级诚实性（2：地图关闭时全 ESTIMATED、距离时长必须为空）**。
- **覆盖率与缺陷清单**：`mvn test` 会自动生成 JaCoCo 报告（`target/site/jacoco/index.html`）。
  核心管线（`trip` 包）行覆盖 **85.4%**、`PreOrderService` 99.1%、`ItineraryValidator` 93.5%
  （2026-09-24 复核实测；P7-A 结项时为 86.2% —— 主代码在其后有 3 次提交，差在测量时点）。
  测量口径、未覆盖项与原因见 [`docs/测试用例表.md`](docs/测试用例表.md)；
  推进过程中由测试与真实联调抓出的 9 个真实缺陷及修复见 [`docs/已知缺陷与修复.md`](docs/已知缺陷与修复.md)。
- **连接器是"实测驱动"的，不是照文档抄的**：地图与大模型的每个参数都拿真实 Key 逐组打过，
  踩到的坑全部写进代码注释与配置说明。两个例子：
  百度 `place/v2/search` 的 `tag` 参数**填错不报错、只会静默返回垃圾** —— 实测 `tag=风景名胜` 时
  结果从真实 POI（方山国家森林公园、冷泉寺）变成「广州市/邵阳市」这类城市级噪声，
  **这比报错危险得多，因为它不引起任何告警**，所以本项目只用 `query`；
  免费额度下「地点检索」只有 100 次/天、并发 3 QPS，因此加了**请求间隔 + 早停 + 检索词收敛**，
  一轮完整验收从约 40 次调用降到 **9 次**，且候选质量反而更好（村名噪声消失）。
  再一个例子：设计文档给 2-opt 写死了「迭代上限 200 次 / 连续 20 次无改进」，
  但 20 个点跑一轮完整扫描就要 190 次 —— 这个数字连一轮都跑不完，把 2-opt 的收益掐掉一个数量级
  （实测 0.28% vs 3.80%）。所以改成**按点数推导上限**、手册原值退化为下限，耗时仍是毫秒级。
- **AI 辅助开发的工程化**：本项目使用"任务块"方式驱动 AI 编码——每个任务块有独立的目标、交付物与验收标准，AI 读完复述确认后才动手，跑通一块再投喂下一块。全套实施手册与设计文档暂未开源，需要的可以通过 issue 联系我。
- **命名纪律**：仓库内不允许出现旧项目残留（`photoshare` / `photo-share`），验收时以 grep 结果为零为准。

## 文档

| 文档 | 给谁看 |
|---|---|
| [`docs/部署说明.md`](docs/部署说明.md) | 要把它跑起来的人：**WSL + Docker 初学者全程教程**（装 Docker → 写 5 个文件 → 一键启动 → 首次部署检查清单）/ 本机直跑路线 / 常见故障排查表 |
| [`docs/Wayfare-开发文档.md`](docs/Wayfare-开发文档.md) | 答辩 / 写论文 / 架构答疑：架构图、七步管线、三层开关、表结构、接口清单、SSE 协议、降级矩阵、可观测性、已知局限 |
| [`docs/交付说明.md`](docs/交付说明.md) | 验收：功能 → 实现位置 → 验证方式对照表 + 诚实局限清单 |
| [`docs/接口清单.md`](docs/接口清单.md) | 105 个端点全量清单（**脚本自动生成，勿手改**） |
| [`docs/metrics.md`](docs/metrics.md) / [`docs/metrics-preorder.md`](docs/metrics-preorder.md) | 六项量化指标的原始数据与口径 |
| [`docs/drill-report.md`](docs/drill-report.md) | 降级演练报告（含可直接进 PPT 的对比表） |
| [`docs/测试用例表.md`](docs/测试用例表.md) / [`docs/已知缺陷与修复.md`](docs/已知缺陷与修复.md) | 测试覆盖口径 / 推进过程中抓出并修掉的缺陷 |

## 已知局限（如实列出）

这份清单是刻意写出来的 —— 被问出来不如自己先说，**「发现了但权衡后没改」本身就是工程判断力**。
完整版（含每条的影响与未修原因）见 [`docs/Wayfare-开发文档.md` §19](docs/Wayfare-开发文档.md#十九已知局限与扩展方向)。

- **前端没有地图可视化**：未接入百度地图 JS API。无坐标（估算模式）时给明确占位文案而不是空白地图；
  有坐标时也只会提示点位数量。这是**未实现项**，不是降级效果。
- **客户端断开只有约 1/3 能真正掐断上游生成**；日志里那句「节省 N tokens」高估约 14 倍。
- **`meta.tokens` / `meta.estCost` 会被相邻行程污染**（未按 `trip_id` 查），取数请用
  `ai_generation_log` 按 `trip_id` 聚合或 `/api/admin/generation/trips/{tripId}/breakdown`。
- **空检索结果会被缓存 24 小时**（`disableCachingNullValues()` 只挡 `null`、不挡空 List）。
- **没有接口级限流**；**管理员操作日志表只有实体与 Mapper，没有写入代码**。
- **`qwen3.8-flash` 已不可用**（476 s/次、失败率 55%）；**`glm-5.3-flash` 必须显式下发 `reasoning-effort`**，
  **`deepseek-flash` 必须显式下发 `reasoning_effort`** —— 两个系列都是推理模型，不传强度会按默认高档跑，
  表现为「请求超时、什么都不返回」。
- **容器化部署（Docker）尚未落地**：仓库内还没有 `Dockerfile` / `docker-compose.yml`
  （P8-B 进行中，由作者自己动手写；循序渐进的操作教程见 [`docs/部署说明.md`](docs/部署说明.md)）。

## 开发进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 仓库骨架 / 内容域 / 鉴权 / 前台页面 | ✅ 已完成 |
| P1 | 大模型连接器（GLM + DeepSeek，后扩展 Qwen）/ 地图连接器 / 出站治理 / 诊断接口 | ✅ 已完成 |
| P2 | 偏好画像 / 行程表族 / 日志缓存 | ✅ 已完成 |
| P3 | 七步 AI 行程编排管线（核心） | ✅ 已完成（Step 6 事实补全与 Step 7 结果组装已于 P3-F 交付：新增 `TripOrchestrator` 串起整条管线，`/api/trip/plan/sync` 同步接口可落库并返回带 `verifyStatus`/`dataSource` 的行程） |
| P4 | SSE 流式输出 / 成本控制 | ✅ 已完成（P4-A 五类事件的 SSE 通道与断线掐流；P4-B 攻略文案流式生成、画像融入与 `guide_text` 落库、`regenerate-copy` 只重生成文案；P4-C 输入输出分开计价、按行程分阶段拆解 token 与成本、中断节省量化，以及 `GET /api/admin/generation/stats` 聚合接口） |
| P5 | 前端偏好中心 / AI 规划交互 / 首页与详情页 / 体验收尾 | ✅ 已完成（P5-A 旅行偏好 11 字段 + 隐私开关；P5-B 四步规划流程与原生 fetch 流式渲染；P5-C 首页筛选与攻略详情页行程区块、AI 生成角标、403 语义；P5-D 全局加载条、生成中离开确认、错误码翻译、我的行程页、移动端适配；另补「发布为攻略」链路 `PUT /trip/{id}/publish`） |
| P6 | 后台管理：连接器开关与 AI 监控 | ✅ 已完成（P6-A `/admin/connectors`：厂商切换 / 降级顺序 / 地图总开关与 AK / 一键降级演练 / 行程参数与单价热改；P6-B `/admin/generation`：统计卡片 + 四张图表 + 生成明细分页 + 外呼日志页签） |
| P7 | 测试、指标埋点与降级演练 | ✅ 已完成（**P7-A** 管理接口安全集成测试 + 离线全链路集成测试，`trip` 包行覆盖 86.2%，产出测试用例表与缺陷清单；**P7-B** 六项量化指标全部实测并汇总到 `docs/metrics.md`，含 2-opt、缓存冷热、校验收敛率、流式时间、中断节省、单次成本；**P7-C** `scripts/drill-fallback.ps1` 端到端降级演练，20 条断言全绿并自动产出 `docs/drill-report.md`） |
| P8 | 文档同步与部署 | 🔨 进行中（**P8-A** 已完成：README 重写 + 自动生成的接口清单、`Wayfare-开发文档.md` 全量更新、`部署说明.md` 与 `交付说明.md` 新建、六项一致性校验逐条给证据。**P8-B** Docker 容器化：**进行中**，由作者自己写 `Dockerfile` / `nginx.conf` / `docker-compose.yml`，`部署说明.md` 已改成面向 WSL + Docker 初学者的全程教程） |

---

*行走集（Wayfare）——把每一次出发，都变成可以分享的路线。*
