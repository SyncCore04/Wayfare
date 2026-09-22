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

- **重试**：仅 GET 重试（指数退避），POST 一律不重试——重复调用可能产生费用；4xx 除 429 外不重试——客户端错误重试无意义。
- **日志**：每次尝试落一条 `external_call_log`（连接器、接口、耗时、成败、脱敏后的请求摘要），是成本统计与监控看板的数据源。
- **脱敏**：AK / API Key / Bearer 凭证在写日志前统一过 `MaskUtil`，库里不允许出现完整密钥。
- **缓存**：POI 检索 24h、详情 7d、路线 12h（Redis），**大模型调用一律不缓存**——同一个输入需要可复现的新结果。

### 4. 鉴权与安全

- 自建 `JwtInterceptor`（不引 Spring Security），无 token / token 无效 / 已登出（Redis 黑名单）统一 401。
- 管理接口与公开接口的边界由白名单显式定义，公开路径用正则约束（`/works/{id:[0-9]+}`）避免把 `/works/my` 一并放行。
- 密钥全部走环境变量或 gitignore 的本地配置文件，仓库内不含任何真实 Key；所有回显一律掩码（前 4 位 + `****`）。

### 5. 流式输出：把「等五分钟」拆成「先看到行程」

同步接口实测一次生成要 **194~507 秒**（骨架本身要 3 次大模型调用，主力模型屡次在 90 秒超时后才降级到备用厂商），
浏览器与 axios 的默认超时都撑不到——用户只会看到一个转圈然后失败。所以生成走 SSE，事件分五类：

```
stage（六阶段进度）→ itinerary（行程骨架）→ delta（攻略文案增量）→ done / error
```

关键在于 **`itinerary` 与 `delta` 的分工就是降级出口**：行程骨架（Step 6 事实补全后立即推送）一旦送达，
攻略文案哪怕整段生成失败，用户手里也已经有一份带可信度角标的完整行程——**文案是锦上添花，不是必需项**。

客户端断开时，增量回调里抛异常即可掐断上游的读取循环（连接器层不需要新增取消 API）；
已生成的部分照常计费，**未生成的部分是真省下来的**。中断会落一条 `success=0 + CLIENT_DISCONNECTED` 的日志，
并用「同类请求历史 `completion_tokens` 均值」估算本次省下多少——
**拿不到 usage 时如实写「未知」，绝不按字符数换算一个 token 数出来。**

### 6. 后台可观测性：开关能当场拨、降级能当场演示

后台两个页面（`/admin/connectors` 连接器管理、`/admin/generation` AI 生成监控）建立在同一套
「只读诊断 + 热改配置」接口上，三件事值得说：

- **改配置不重启**：`PUT /admin/configs/{key}` 写库后立即清 Redis 缓存。验证口径也是可执行的 ——
  「先读一次（把值灌进缓存）→ 写新值 → 立刻再读」，能读回新值才说明缓存真被清了
  （读一个直查数据库的接口是证明不了这件事的）。
- **一键降级演练**：一个按钮关掉地图能力，之后的生成 `verifyStatus` 全为 `ESTIMATED`、
  距离与时长留空并标注「估算」、`mapMode=ESTIMATED`，**系统功能不受影响**；
  且关闭后零外部调用，不消耗任何地图额度。这正是「连接器可插拔」的现场证明。
- **诊断信息必须与事实一致**：`GET /diagnostics/llm/providers` 逐厂商返回模型名、Base URL、
  **掩码后的 Key**（前 4 位 + `****`）、可用性与熔断快照。这里修过一个真 bug —— 熔断快照原先写死读
  `map.breaker.*`，于是大模型被显示成「阈值 5」，而它实际第 3 次失败就跳闸：**诊断信息说谎比没有信息更坏**。
  现在快照按配置前缀读各自阈值（大模型 3 / 地图 5，因为地图一次失败只损失几百毫秒，
  大模型一次失败要白等一整个 90 秒超时）。
- **安全验收点就是页面本身**：外呼日志页签直接展示 `request_summary`，而它是**写入时**脱敏的
  （先脱敏再截断，顺序不能反），所以页面上看到的必然是 `ak=abcd****` 这种形态。

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
# 1. 环境要求：JDK 17、Maven 3.6+、Node 16+、MySQL 8.0、Redis 6+

# 2. 建库（两份脚本：内容域 + 行程域，均幂等）
mysql -u root -p < db/schema.sql
mysql -u root -p < db/schema-trip.sql

# 3. 配置密钥与数据库密码（本文件已被 gitignore，仓库内只有 .example 模板）
cd wayfare-backend
copy .env.properties.example .env.properties
#   编辑 .env.properties，填入 MYSQL_ROOT_PASSWORD 与 QWEN_API_KEY（或 GLM_API_KEY）

# 4. 启动后端（8080，context-path /api）
mvn spring-boot:run
#    启动日志会自检：LLM 厂商配置状态 / 地图开关状态，一眼可见

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

- **测试**：214 个单元 / 集成测试全绿（另有 6 个真实调用大模型与地图的验收测试默认跳过，
  用 `-Dwayfare.live=true` 显式开启），覆盖脱敏规则（6）、出站重试与降级策略（13）、
  用户画像渲染规则（11）、画像读写与 upsert 的 null 语义（7）、行程表族排序与数据诚信字段（5）、
  AI 日志聚合与成本计算（20，含输入输出分开计价与统计聚合）、实体与表结构映射（4）、
  **意图解析的 Schema 校验与失败重试（24）**、
  **候选检索的去重 / 忌口过滤 / 坐标诚信 / 候选不足降级 / 配额保护（24）**、
  **空间预排的贪心 + 2-opt、无坐标降级、距离公式（14）**、
  **行程编排的硬约束校验 / 失败降级 / 距离不采信（19）**、
  **约束校验的七条本地规则（27）**、**约束违规后的回喂重排（7）**、
  **SSE 取消闸门与中断说明（8）**、**攻略文案 prompt 的六条规则与「距离不进 prompt」（12）**、
  **熔断阈值与诊断快照（7，含大模型 / 地图阈值分离与 Redis 键命名空间）**。
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
| P7 | 测试、指标埋点与降级演练 | ⏳ 进行中 |
| P8 | 文档同步与部署 | ⏳ 规划中 |

---

*行走集（Wayfare）——把每一次出发，都变成可以分享的路线。*
