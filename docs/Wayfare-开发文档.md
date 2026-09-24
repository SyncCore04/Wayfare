# Wayfare · AI 旅游攻略平台 · 开发文档

> 项目代号：**Wayfare** ｜ 中文名：行走集 ｜ 包名：`com.wayfare`
> 版本：v2.1 ｜ 最后核对代码：**2026-09-24**（对应 `HEAD = 299535c`）
> 配套手册：`Wayfare-重构提示词.md`（32 个任务块，P0–P8）
> 前身参考：光影集摄影作品分享平台（内容域与社交域的设计来源）
>
> **维护纪律（最重要的一条）**：**本文档不允许描述代码里不存在的能力。**
> 每一节的内容都必须在真实代码里找得到落点；代码变更后必须同步更新对应章节。
> 本版修订时删掉了上一版中三处「文档有、代码没有」的内容（接口限流、`admin_operation_log` 写入、
> 前端百度地图 JS API 打点），并在 [十九](#十九已知局限与扩展方向) 里如实登记。

---

## 目录

- [一、项目概述](#一项目概述)
- [二、技术栈](#二技术栈)
- [三、系统总体架构](#三系统总体架构)
- [四、连接器层（本系统地基）](#四连接器层本系统地基)
- [五、AI 行程编排管线（7 步）](#五ai-行程编排管线7-步)
- [六、用户旅行偏好画像](#六用户旅行偏好画像)
- [七、流式输出（SSE）](#七流式输出sse)
- [八、数据库设计](#八数据库设计)
- [九、Redis 设计](#九redis-设计)
- [十、REST 接口清单](#十rest-接口清单)
- [十一、前端设计](#十一前端设计)
- [十二、配置与密钥管理](#十二配置与密钥管理)
- [十三、降级与容错设计](#十三降级与容错设计)
- [十四、可观测性与成本核算](#十四可观测性与成本核算)
- [十五、安全设计](#十五安全设计)
- [十六、量化指标](#十六量化指标)
- [十七、测试策略](#十七测试策略)
- [十八、部署](#十八部署)
- [十九、已知局限与扩展方向](#十九已知局限与扩展方向)
- [附录 A：命名规范](#附录-a命名规范)
- [附录 B：术语表](#附录-b术语表)

---

## 一、项目概述

### 1.1 定位

面向年轻旅行者的 **AI 旅游攻略分享平台**。用户用一句自然语言（"周末想去寿阳玩两天，喜欢古建筑，预算 500"）即可生成一份含真实距离与时长校验的行程攻略，经编辑后发布分享；同时保留完整的图文攻略创作与社交能力。

**Wayfare** 是独立新建的项目，内容域与社交域的设计参考了光影集摄影分享平台，但代码为全新实现，命名空间为 `com.wayfare`。

### 1.2 两种内容形态

| 形态 | 说明 | 数据落点 |
|---|---|---|
| 图文攻略 | 用户原创，多图 + 标签 + 分类 + 社交互动 | `work` 表族 |
| AI 行程攻略 | 一句话生成 → 编辑 → 发布，含结构化行程与攻略文案 | `trip` 表族 + 反向关联 `work` |

### 1.3 三条设计铁律

1. **事实数据永不来自大模型。** 坐标、距离、时长只能来自地图连接器，或显式标记为估算（`ESTIMATED`）。大模型只负责语义决策：分天、分配时长、讲解、组织文案。
2. **连接器可插拔，任一时刻系统完整可用。** 大模型与百度地图都可整体切换或关闭，关闭后系统以降级模式继续全功能运行（能力降级，而非功能降级）。
3. **画像注入是增强，不是必需。** 用户关闭隐私开关后规划照常进行，仅不注入偏好。

---

## 二、技术栈

> 版本号取自 `wayfare-backend/pom.xml` 与 `wayfare-frontend/package.json`，**不是记忆值**。

| 层级 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 语言 / 运行时 | JDK | 17 | `pom.xml` 的 `<java.version>17</java.version>` |
| 后端框架 | Spring Boot | 3.3.5 | 单体 MVC，**不使用 WebFlux** |
| ORM | MyBatis-Plus | 3.5.7 | 逻辑删除、自动填充 |
| 数据库 | MySQL | 8.0 | 22 张业务表 |
| 缓存 | Redis | 7 | 配置缓存、POI/路线缓存、计数、熔断计数 |
| 鉴权 | jjwt | 0.12.6 | 自建 `JwtInterceptor` + `Authorization: Bearer` |
| 密码 | spring-security-crypto | 由 Boot 托管 | **只用 BCrypt**，不引入完整 Security |
| 流式输出 | Spring MVC `SseEmitter` | 由 Boot 托管 | 独立线程池 |
| 图片处理 | Java2D / ImageIO（自建 `WatermarkUtil`） | — | 上传即加文字水印 |
| API 文档 | springdoc-openapi | 2.6.0 | 真实入口 `/api/swagger-ui/index.html` |
| 覆盖率 | JaCoCo | 0.8.12 | 已绑在 `test` 阶段，`mvn test` 自动出报告 |
| 前端框架 | Vue | ^3.4.21 | **JS，非 TS** |
| 构建 | Vite | ^5.2.0 | 含 `/api` 与 `/uploads` 代理 |
| UI | Element Plus | ^2.6.1 | 另有 `@element-plus/icons-vue` ^2.3.1 |
| 状态 / 路由 | Pinia ^2.1.7 / Vue Router ^4.3.0 | — | - |
| HTTP | Axios ^1.6.8 | — | 用 `fetch` 读 SSE |
| 日期库 | dayjs | ^1.11.10 | — |
| 图表 | ECharts | ^5.6.0 | 仅后台监控看板，按需引入（core + Line/Bar/Pie） |
| **大模型 A** | 阿里云百炼 Qwen | OpenAI 兼容 | `qwen3.8-flash`（**模型 id 大小写敏感，必须全小写**） |
| **大模型 B** | 智谱 GLM | OpenAI 兼容 | `glm-5.3-flash`（**强制思考，必须下发 `reasoning-effort`**） |
| **大模型 C** | DeepSeek | OpenAI 兼容 | `deepseek-flash`（= DeepSeek-V4.1-Flash，**必须下发 `reasoning_effort`**） |
| 兜底 | `MockLlmProvider` | — | 三家全不可用时回落，链路照常演示 |
| 地图服务端 | 百度地图 Web 服务 API | v2 | POI 检索 / 详情 / 路线规划 |
| 地图前端 | — | — | **未实现**：前端未接入百度地图 JS API，仅显示占位提示（见 [§11.2](#112-ai-规划四步流程el-steps) 与 [十九](#十九已知局限与扩展方向)） |

**依赖纪律**：不引入 WebFlux、新 ORM、新状态管理库、Element Plus 之外的 UI 库。新依赖必须先说明用途并确认。

---

## 三、系统总体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                              浏览器                                    │
│   Vue3 + Element Plus + Pinia                                         │
│   用户端：首页 / 攻略详情 / AI规划(SSE) / 我的行程 / 个人中心+旅行偏好 / 私信 │
│   管理端：内容·用户·分类·审核 / 连接器管理 / AI 监控                    │
│   （行程地图区块：无坐标或无前端 AK 时显示明确占位文案，不渲染空白地图）    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ HTTP + SSE（fetch 流式，携带 JWT）
┌──────────────────────────────────▼───────────────────────────────────┐
│                       Spring Boot 3.3.5（/api）                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ 接入层   JwtInterceptor · 全局异常 · Result 统一返回               │ │
│  ├────────────────────────────────────────────────────────────────┤ │
│  │ 业务层   内容域(work) · 社交域 · 画像域(profile) · 行程域(trip)     │ │
│  │          TripOrchestrator（7 步管线）· 后台管理与诊断              │ │
│  ├────────────────────────────────────────────────────────────────┤ │
│  │ 连接器层（可插拔 · 三层开关 · 本系统核心）                          │ │
│  │   LlmProvider ─ QwenProvider / GlmProvider / DeepSeekProvider  │ │
│  │                 / MockLlmProvider（公共逻辑在同一抽象基类）      │ │
│  │   MapProvider ─ BaiduMapProvider / LocalCacheMapProvider         │ │
│  │                 / DisabledMapProvider                            │ │
│  │   LlmCapabilityResolver / MapCapabilityResolver（决策 + 降级）    │ │
│  │   CircuitBreaker（Redis 计数熔断，大模型与地图阈值分离）           │ │
│  │   ExternalHttpClient（超时/重试/缓存/日志/脱敏）                   │ │
│  ├────────────────────────────────────────────────────────────────┤ │
│  │ 数据层   MyBatis-Plus → MySQL（22 张表）                          │ │
│  │          Redis（缓存 / 计数 / 配置 / 熔断）                        │ │
│  └────────────────────────────────────────────────────────────────┘ │
└──────┬──────────────┬──────────────┬──────────────┬─────────────────┘
       │ HTTPS        │ HTTPS        │ HTTPS        │ HTTPS
┌──────▼──────┐ ┌─────▼─────┐ ┌──────▼──────┐ ┌───────▼──────────┐
│ 阿里百炼 Qwen│ │ 智谱 GLM  │ │  DeepSeek   │ │  百度地图 Web 服务 │
│ OpenAI 兼容 │ │ OpenAI兼容│ │ OpenAI 兼容 │ │  POI/详情/路线     │
└─────────────┘ └───────────┘ └─────────────┘ └──────────────────┘
   三家大模型互为降级（顺序由 llm.fallback-order 决定，**不是硬编码**）；
   地图可整体关闭，关闭后进入估算模式，零外部调用
```

### 3.1 一次生成请求的完整数据流

```
用户输入 + 画像(可选)
   │
   ▼
①意图解析(LLM) → IntentDTO →【用户确认表单 · needConfirm 高亮】
   ▼
②候选检索(地图/LLM) → 候选池(景点+餐饮) ──不足→ 自动放宽→仍不足则 shortage
   ▼
③空间预排(本地算法：贪心最近邻 + 2-opt)   ← 不交给 LLM
   ▼
④行程编排(LLM：分天/时长/餐饮/理由)
   ▼
⑤约束校验(本地七规则：闭包/时序/折返/通勤/预算/忌口/密度)
   │   不通过 → 回③或④，最多 2 轮 → 仍不通过返回最优方案+风险提示
   ▼
⑥事实补全(地图逐段路线 / 缓存 / 估算) → 回填距离时长
   ▼
⑦结果组装 → trip/trip_day/trip_item 落库
   │
   ├─【推送 itinerary 事件】← 降级出口：此后文案失败不影响行程可用
   ▼
⑧攻略文案(LLM 流式) → SSE delta → done
```

---

## 四、连接器层（本系统地基）

### 4.1 大模型连接器

```java
public interface LlmProvider {
    String name();                                   // qwen / glm / deepseek / mock
    LlmInfo info();                                  // provider / model / available / reason（不发起网络调用）

    // context 用于接出 token 用量（P4-C 起）
    String chat(String systemPrompt, String userPrompt, LlmCallContext context);
    String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint,
                    LlmCallContext context);
    void chatStream(String systemPrompt, String userPrompt,
                    Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError);

    // 不带 context 的重载都是 default 方法，内部传 LlmCallContext.empty()
}
```

#### 4.1.1 三家厂商 + Mock 的接入实现

| 实现 | Base URL | 当前模型（`application.yml`） | 关键差异点（全部是实测踩出来的） |
|---|---|---|---|
| `QwenProvider` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.8-flash`（`max-tokens` 4096、temperature 0.3） | **模型 id 必须全小写**，大小写不符直接 404（文案像没权限，其实是名字不对）。⚠️ 实测已不适合承担长结构化输出（见 [十九](#十九已知局限与扩展方向)） |
| `GlmProvider` | `https://open.bigmodel.cn/api/paas/v4` | **`glm-5.3-flash`**（`max-tokens` 16384、temperature 1） | 🔴 **强制思考、不允许关闭**，**必须显式下发 `reasoning-effort`** —— 合法值只有 `low` / `high` / `max`（传 `none`/`minimal`/`medium` 会被 400 拒绝，code 1210）；不传就按默认高档跑，表现为「超时且无返回」。JSON 模式需同时加 `response_format` **与** prompt 里出现 "json" 字样；流式末块可能只有 usage 无内容，需容忍 `content` 为 null |
| `DeepSeekProvider` | `https://api.deepseek.com/v1` | **`deepseek-flash`**（= DeepSeek-V4.1-Flash，`max-tokens` 32768、temperature 0.3） | 🔴 同为推理模型、官方声明 `effort.default_level = high`，**必须显式下发 `reasoning_effort`**；参数名必须写 `reasoning_effort`（写成 `effort` 会被**静默忽略**）；**合法 8 档** `none`/`minimal`/`low`/`medium`/`high`/`xhigh`/`ultra`/`max`，**支持 `none` 彻底关闭思考**。`reasoning_content` 必须忽略（基类只读 `delta.content`，天然规避）；流式需加 `stream_options.include_usage=true` 才有 token 统计 |
| `MockLlmProvider` | — | — | 离线兜底，按 prompt 特征返回合法假 JSON；三家全不可用时链路仍可演示 |

**公共逻辑全部下沉到 `AbstractOpenAiCompatibleProvider`**：请求构造（`buildRequestBody` + 子类钩子 `customizeRequestBody`）、流式 `data:` 行解析、异常映射（401→`LLM_AUTH_FAIL`、429→`LLM_RATE_LIMIT`、超时→`LLM_TIMEOUT`、5xx→`LLM_SERVER_ERROR`）、token 上报。子类只负责写差异部分。

> 🔴 **两个「推理模型」坑必须记住**（P7 实测，两家病根相同）：
> **思维链会计入 `completion_tokens` 并占用 `max_tokens`**。不显式下发强度时，模型会把额度全花在思考上 ——
> 表现为「请求超时、什么都不返回」，看起来像服务挂了（GLM 实测 60 秒产出 3556 字思考、正文 0 字）。
> 所以 `reasoning-effort` 做成了配置项而非硬编码，`max-tokens` 也必须给足。
>
> 同一真实 prompt 各档位实测（`deepseek-flash`）：`none` **0.8 s / 96 tok** ｜ `low` 5.5 s / 1171 ｜
> `high`（默认）**9.7 s / 2139** —— 不显式下发比 `none` **慢 12 倍、token 多 22 倍**，而解析质量四档全对。
>
> ⚠️ **超时是「真可调」的**：`AbstractOpenAiCompatibleProvider.effectiveTimeoutMs()` 每次调用都读一遍
> L2（`sys_config.llm.timeout-ms`），非流式与流式都走它。此前该键只读 L1，改库改界面全不生效
> （实测把 90000 改成 180000 后连重启都没用）。

#### 4.1.2 厂商决策与降级

`LlmCapabilityResolver.resolve()`：

```
读 sys_config.llm.active-provider（Redis 缓存 30s）
  → 该厂商可用？ → 用它
  → 不可用（未配 Key / 熔断打开）？ → 按 llm.fallback-order 依次尝试
  → 全不可用？ → 回落 MockLlmProvider（reason 明确记录）
返回 ResolvedLlm { provider, model, isFallback, reason }
```

- **厂商热切换**：改动 `sys_config.llm.active-provider` 后**不重启即生效**。
- **降级链是厂商粒度**，不是「同厂商换模型」。
- 熔断中的厂商会被**直接跳过、不再干等超时**（见 [§4.4](#44-熔断器)）。
- 全部候选都在熔断中时抛 `LLM_NOT_AVAILABLE`；「都不在熔断但都不可用」时才回落 Mock。

### 4.2 地图连接器

```java
public interface MapProvider {
    boolean isAvailable();
    String name();                     // baidu / cache / disabled
    String unavailableReason();
    List<PoiDTO> searchPoi(PoiQueryDTO q);
    PoiDTO detail(String poiUid);
    RouteDTO route(RouteQueryDTO q);   // driving / walking / riding / transit
}
```

| 实现 | `name()` | 行为 | 降级顺位 |
|---|---|---|---|
| `BaiduMapProvider` | `baidu` | 真实调用百度 Web 服务 API。AK 解析顺序：`sys_config.map.baidu.ak` → `application.yml` → 空（不可用）。**容忍字段缺失：POI 检索接口通常不返回评分与门票，取不到保持 `null` 标记"未知"，绝不填 0 冒充** | 1 |
| `LocalCacheMapProvider` | `cache` | 只查 `poi_cache` 表与 Redis 路线缓存，**零网络请求** | 2 |
| `DisabledMapProvider` | `disabled` | 短路，`isAvailable()=false`，返回空集合 + 明确中文 reason | 3 |

业务代码一律通过 `MapCapabilityResolver.call(action, hasData)` 取数，**不要直接注入某个 Provider**：

```java
// resolve() 的三条出口：总开关关闭 → ESTIMATED；熔断打开/百度不可用 → CACHED；否则 → VERIFIED
public <T> MapCallResult<T> call(Function<MapProvider, T> action, Predicate<T> hasData)
```

### 4.3 三层开关

| 层级 | 载体 | 生效方式 | 操作者 |
|---|---|---|---|
| **L1 启动层** | `application.yml` + `LlmProperties` / `MapProperties` | 启动时 | 开发者 |
| **L2 运行层** | `sys_config` 表 + Redis 缓存 30 s | **改完不重启即生效** | 管理员（后台面板） |
| **L3 降级层** | 熔断器 + 分级 Provider + 厂商 fallback | 自动 | 系统 |

读取优先级：**L2 覆盖 L1，L2 未配置时回落 L1**。

### 4.4 熔断器

不引 Resilience4j，用 Redis 计数实现（`connector/governance/CircuitBreaker.java`）：

```
连续失败 ≥ {prefix}.breaker.fail-threshold
  → 打开熔断 {prefix}.breaker.open-seconds（默认 300 秒）
  → 期间直接走降级，不发起请求
  → 到期后半开放行一次：成功则清零关闭，失败则重新计时
```

- `{prefix}` 由调用方传入：**大模型传 `llm`、地图传 `map`**，两套阈值**互相独立**。
- Redis 键：`cb:fail:{name}`（失败计数，TTL 1 小时）、`cb:open:{name}`（打开截止时间戳）。
  `{name}` 是 provider 名（`glm` / `baidu` / …），**两个命名空间共用同一组键前缀**。
- **默认阈值不一致，这是有意设计**：

| 侧 | 默认阈值 | 默认熔断时长 | 为什么 |
|---|---|---|---|
| 大模型 | **3** | 300 s | 一次失败要白等一整个 `llm.timeout-ms`（默认 240 s） |
| 地图 | **5** | 300 s | 一次失败只损失几百毫秒 |

> ⚠️ 代码默认值必须与 `db/schema-trip.sql` 的初始值一致；两边不一致会让「库没执行过初始化脚本」的
> 部署静默跑偏，且**不会有任何报错**（写测试时正是踩在这个默认值上）。
>
> ⚠️ **诊断快照也必须按前缀读**（`CircuitBreaker#snapshot(prefix)`）。修复前快照把 `map.breaker.*`
> 写死，于是大模型被显示成「阈值 5」，而它实际第 3 次失败就跳闸 —— **诊断信息说谎比没有信息更坏**。

状态可被 `/api/diagnostics/connectors` 与 `/api/connector/map/status` 读取；熔断打开时可用
`POST /api/connector/map/breaker/reset` 手动复位。

### 4.5 统一出站治理 `ExternalHttpClient`

所有外部 HTTP 必须经此封装（`@Primary` 实现是 `GovernedExternalHttpClient`）：

- **超时**：地图 connect 3 s / read 8 s（`map.baidu.*`）；大模型 connect + read 统一由
  `llm.timeout-ms` 控制，**默认 240000 ms，L2 可调**
- **重试**：**GET** 失败重试 2 次，指数退避 200 ms / 600 ms；**POST 默认不重试**
  （避免重复计费与副作用），**唯一例外是 HTTP 429**（退避 1 s / 3 s——限流等一会儿确实会好）；
  **流式 `postJsonStream` 完全不重试**（内容已经往外推了）
- **缓存**：见[第九章](#九redis-设计)；**大模型调用一律不缓存**
- **日志**：每次尝试写一条 `external_call_log`，`request_summary` 截断 500 字符**且已脱敏**
- **脱敏**：`MaskUtil.maskSecret()` 对 ak / api_key / Authorization 只保留前 4 位 + `****`；
  **顺序不能反**（先脱敏再截断，否则截断后的密钥片段会漏出去）

---

## 五、AI 行程编排管线（7 步）

由 `TripOrchestrator` 串联，每步前后写 `ai_generation_log` 并累计耗时。

| 步骤 | 名称 | 执行者 | 关键规则 | 失败行为 |
|---|---|---|---|---|
| 1 | 意图解析 `parseIntent` | **LLM**（chatJson） | 输出 IntentDTO；服务端 Schema 校验（类型/枚举/必填）；**必须输出 `needConfirm[]` 标注靠猜测填的字段**（如预算口径未说明、同名地名消歧） | 校验失败重试 1 次（错误回喂），仍失败抛 `SCHEMA_INVALID`，**不静默兜底** |
| 2 | 候选检索 `searchCandidates` | 地图连接器 / LLM | 偏好→百度检索词映射字典（`resources/map-preference-tag.json`）；景点 + 餐饮两路；按 uid 与名称相似度双重去重；剔除含忌口的餐饮点；限量 `trip.max-candidate`。**地图关闭时改由 LLM 生成候选：只填名称/区域/建议停留/亮点，`lat/lng` 强制留空**，标记 `dataSource=LLM` | 景点候选 <6 → 扩半径 → 放宽标签 → 仍不足返回 `shortage=true` + `shortageHint`，**绝不编造景点** |
| 3 | 空间预排 `preOrder` | **本地算法，零外部调用** | 起点=目的地中心；贪心最近邻 + 2-opt 局部优化；**迭代上限按点数推导**（手册的 200 次 / 连续 20 次无改进**退化为下限**，见下）；优化后**总里程必须 ≤ 优化前**；无坐标候选保持原序放尾部 | 不可失败，但设迭代上限防卡死 |
| 4 | 行程编排 `composeItinerary` | **LLM**（chatJson） | 分天、时段（活动 09:00–18:00、午餐 11:30–13:00、晚餐 18:00 后）；每 item 必写 `reason`；**只能从候选池选点**；节奏约束（慢 2–3 / 适中 3–4 / 紧凑 4–5 点）；每天至少 1 个 FOOD；画像硬约束注入 | 解析失败重试 1 次；仍失败返回 `LLM_PARSE_FAIL` 但**保留候选池**供用户手选 |
| 5 | 约束校验 `validate` | **本地七规则** | 见 [5.1](#51-七条本地校验规则) | 不通过 → 带 violations 回喂重排，最多 `trip.max-replan-rounds`（默认 2）轮；达上限返回当前最优 + 风险提示，**绝不死循环** |
| 6 | 事实补全 `enrichRoutes` | 地图连接器 / LLM | VERIFIED：逐段调路线规划（**并发上限 4**），县域 transit 无数据自动降级 driving 并标注；回填后**重跑规则 3) 与 5)**，违反则回步骤 3（计一轮）。CACHED：命中记 VERIFIED，未命中记 ESTIMATED。ESTIMATED：**完全不调地图，距离时长保持 `null`**，只把模糊表述写入 `note` | 部分段失败只标该段 ESTIMATED，不整体失败 |
| 7 | 结果组装 | 本地 | 批量落库 `trip` → `trip_day` → `trip_item`；回写 `generationRounds` / `mapMode` / `modelName` / `profileUsed` | — |

### 5.1 七条本地校验规则

规则码是 `ItineraryValidator` 里的常量，**会出现在日志与接口响应里**，不要改字符串。

| 规则码 | 校验内容 | 严重度 |
|---|---|---|
| **`CLOSURE`** | 每个 item 的 `poiRef` 必须在候选池中找到（容忍空格与全半角差异）。**唯一使命：识破大模型编造的景点** | HIGH |
| `TIME_OVERLAP` | 同天内 `startTime` 严格递增、不重叠、不超 09:00–18:00 窗口（晚餐豁免） | MEDIUM |
| `BACKTRACK` | 有坐标时单段距离超阈值：DRIVE 40 km / WALK 5 km / RIDING 15 km / PUBLIC 40 km。**无坐标则跳过** | HIGH |
| `DETOUR` | 单日累计通勤：DRIVE > 120 min 或 WALK > 40 min | MEDIUM |
| `BUDGET_EXCEED` | 费用之和 vs 预算（按口径换算）：超支 ≤10% LOW，>10% MEDIUM | LOW/MEDIUM |
| **`TABOO`** | 任何 item 的 `poiName` 命中忌口食材。**硬约束，命中即必须重排** | HIGH |
| `TOO_DENSE` | 按坐标估算当日移动量 vs `profile.walkLimitKm` | MEDIUM |

**回喂机制**：构造纠正 prompt（逐条列出 `message` + `suggestion`，用分隔符包裹避免格式错乱），最多 2 轮。达上限**返回当前最优版本 + violations 风险提示**，由前端展示"以下问题未能自动解决"。

> 🔴 **`TABOO` 曾经扫 `reason` 字段，这是个真缺陷（已修）**：模型常常正当地写出「已要求不加香菜」，
> 旧口径把它判成 HIGH 违规 → **永远收不敛**（修复前 5/5 全是 `rounds=2`、残留 HIGH 3~4 条）。
> 现在只查点位名，修复后 30 次实测**残留 HIGH 全为 0**。

### 5.2 2-opt 迭代上限：对手册的一处有意偏离

手册写死「迭代上限 200 次 / 连续 20 次无改进」，但一轮完整扫描要试 `n(n-1)/2` 个 (i, j) 组合 ——
**20 个点时一轮就是 190 次**，所以 200 次评估连一轮都跑不完，而「连续 20 次无改进就停」在扫描中途就触发了。
结果 2-opt 只做了极浅的搜索。实测差距（20 点不规则布局）：

| 参数 | 优化幅度 |
|---|---|
| 手册的 200 / 20 | 0.28% |
| 放宽到 20000 / 2000 | **3.80%**（省 5.5 公里） |

所以改成 `iterationBudget(n)` **按点数推导上限**，手册原值作为**下限**保留（点数很少时仍退回 200 / 20）。
`trip.max-candidate` 硬限在 20 个点以内，根本不存在「大数据集」，放大 20 倍既安全又能真正发挥 2-opt 的作用。

### 5.3 事实边界（铁律一的落地）

| 数据 | 允许来源 | 地图关闭时 |
|---|---|---|
| 经纬度、POI uid、地址 | 地图连接器 | **留空**，不填假值 |
| 距离、时长 | 地图连接器 | 标记 `ESTIMATED`，**禁止精确数值**（`distanceMeters` 保持 null） |
| 门票价格、评分 | 地图详情（可能为 `null` → 显示"未知"）或用户手填 | 同左 |
| 景点名称 | 候选池闭包（`CLOSURE` 强校验） | 同左 |
| 分天、时长分配、讲解文案 | 大模型 | 同左 |

> **判定方式**：任何进了 `trip_item` 的事实字段，都必须有 `verify_status` 与 `data_source` 标记。没有标记的字段视为设计缺陷。

---

## 六、用户旅行偏好画像

### 6.1 数据字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `cuisines` | 逗号分隔 | 喜欢的菜系（晋菜/川菜/面食/火锅/日料…） |
| `flavors` | 逗号分隔 | 口味（偏清淡/偏咸/微辣/重辣/偏甜…） |
| `taboos` | 逗号分隔 | **忌口与过敏（硬约束）** |
| `travel_styles` | 逗号分隔 | 旅行风格（古建探访/自然风光/博物馆/市井烟火/摄影旅拍…） |
| `pace` | 枚举 1/2/3 | 慢（每天 2–3 点）/ 适中（3–4）/ 紧凑（4–5） |
| `budget_level` | 枚举 1/2/3 | 经济 / 舒适 / 品质 |
| `companions` | 枚举 | 独自/情侣/朋友/家庭带娃/带长辈 |
| `walk_limit_km` | 整数 | 单日步行上限 |
| `hotel_pref` / `notes` | 文本 | 住宿偏好 / 自由备注 |
| `allow_ai_use` | 布尔 | **隐私开关：是否允许 AI 使用本画像，默认开** |

### 6.2 注入规则

**门槛**：`ProfileRenderer.shouldInject(profile, useProfile)` 要求「请求 `useProfile=true`」**且**
「`profile.allowAiUse != 0`」**且**「画像确实有内容」。任一不满足，`render` 返回空串 ——
**prompt 里连「【用户画像】」这个标题都不会出现**，模型没有机会把不存在的信息写进文案。
读画像失败也返回空串：画像只是增强，不能因为它把生成拖失败。

**渲染**：由 `ProfileRenderer.render(profile, overrides)` 生成结构化中文文本块：

```
【用户画像】
菜系偏好：晋菜、面食
口味偏好：偏咸、微辣
忌口过敏（硬约束，任何推荐都不得包含）：香菜、花生
旅行风格：古建探访、摄影旅拍
节奏：慢（每天 2-3 个点）
预算倾向：经济
常同行人：情侣
单日步行上限：8 公里
住宿偏好：民宿
补充说明：不喜欢人多的景区
```

**渲染规则**：
- 空字段**整行省略**（不输出"无""未填写"）
- **忌口永远占一行**：有值列出来；为空时输出「忌口过敏：无（这点可以更自由地推荐餐饮）」
- `overrides.taboos` 与画像 taboos **合并**（并集去重），不是覆盖
- `overrides.pace` **覆盖**画像 pace（本次的「带长辈要慢」该盖过平时的「紧凑」）
- 全空时 `render` 返回空字符串，`hasAnyContent` 返回 false

**UI 可见性**：规划页显示可取消的偏好 chips，结果页显示「本次已参考你的偏好：古建探访 / 慢节奏 / 忌香菜」；隐私开关关闭时显示「未使用偏好」。

**硬约束落地**：忌口走校验规则 `TABOO`，命中即判 HIGH 并重排 —— **不依赖模型"记得"**。

---

## 七、流式输出（SSE）

### 7.1 事件协议

| 事件 | data | 说明 |
|---|---|---|
| `stage` | `{stage, status, message}` | `stage` ∈ `PARSE` / `CANDIDATE` / `PREORDER` / `COMPOSE` / `VALIDATE` / `ROUTE` / `COPY`；`status` ∈ `RUNNING` / `DONE` / **`FALLBACK`** |
| **`itinerary`** | 完整行程骨架 DTO | **步骤 6 完成后立即推送。此时前端已可渲染时间轴、费用表。这是降级出口：之后文案失败不影响行程可用** |
| `delta` | `{text}` | 攻略文案增量 |
| `done` | `{tripId}` + `meta` + 文案信息 | 结束。`meta = {rounds, mapMode, modelName, profileUsed, durationMs, tokens, estCost}`，另附 `copyProvider` / `copyModel` / `copyChars` |
| `error` | `{code, message}` | 发完 error 仍 complete，已生成内容不丢弃 |

事件格式：`event: xxx\ndata: {...}\n\n`（两换行结尾）。

> ⚠️ `meta.tokens` / `meta.estCost` **当前不可信**（`buildMeta()` 按「用户 + 时间窗」查日志，
> 没有按 `trip_id`，会被前后相邻行程污染）。**要 token/成本数字请用 `ai_generation_log`
> 按 `trip_id` 聚合**。详见 [§14.3](#143-成本计算) 与 [十九](#十九已知局限与扩展方向)。

### 7.2 技术约束

- Spring MVC `SseEmitter`，**禁止引入 WebFlux**
- **必须异步**：独立线程池（core 4 / max 8 / keepAlive 60 s / LinkedBlockingQueue(50) / AbortPolicy，线程名前缀 `wayfare-trip-sse-`），不占 Tomcat 请求线程
- emitter timeout 读 **L2 `trip.sse-timeout-ms`（默认 600000 = 10 分钟）**，新开的长连接立即用新值；
  `onTimeout` / `onCompletion` / `onError` 正确清理
- **客户端断开可感知，但效果有限（如实记录）**：捕获 `IOException` → 在增量回调里抛异常以中断上游读取循环
  → 写一条 `success=0` + `error_code=CLIENT_DISCONNECTED` 的日志。
  P7 用 9 次实测把它量了出来：**只有 3 次真正掐断**，另 6 次模型把整篇生成完并照常落库。
  真因是检测点在「下一次 `send()` 抛异常」，而 delta 写入平均只有 1.4 字符/块，会被 socket 缓冲吸收。
  **这条链路的价值在「不落半成品数据」，不在省成本**（详见 `docs/metrics.md` 第五项）
- **日志里「节省约 N tokens」高估约 14 倍**：它取的是 COPY 阶段整篇 `completion_tokens` 的均值，
  **没有减去已产出**。实测自报 429 / 已产出 399 → 真实净节省仅约 **30 tokens**。引用前必须自己减一次。
  拿不到均值时如实写「无法计算」，**绝不按字符数换算一个 token 数出来**
- 鉴权不变：仍走 `Authorization` 头
- **前端不用 `EventSource`**（不能自定义请求头、无法携带 JWT），用 `fetch` + `response.body.getReader()` 手动按 `\n\n` 切分事件块（`utils/sseParser.js`）

### 7.3 文案生成

- 通过 `chatStream` 流式输出，按天组织
- 字数硬指标在 `TripCopyPrompt` 里：**单日 150–250 字，全文 ≤800 字**
  （天数多时两条规则会冲突，prompt 里明确要求「按全文上限反推每天的字数，宁可短一点，不要凑字数」）
- 必须体现画像（忌口/预算档/节奏至少一处）
- **地图关闭时禁止输出精确距离与时间**，用"顺路""不远"这类表达 —— 这是铁律一在文案环节的落点
- 生成成功后写回 `trip.guide_text`，重复访问不重新生成
- 独立重试接口 `POST /api/trip/{id}/regenerate-copy?provider=glm`：**只重跑文案，不重跑全量管线**，
  事件序列同样是 `stage` → `delta` → `done`（**没有 `itinerary`** —— 行程没变，没有新骨架可推）

---

## 八、数据库设计

共 **22 张表** = 内容域与用户域 14 张 + 行程域与治理域 8 张。
字符集 `utf8mb4`，主键 `BIGINT UNSIGNED AUTO_INCREMENT`，**无物理外键**，逻辑删除字段 `deleted`。

分两个脚本：`db/schema.sql`（内容域）+ `db/schema-trip.sql`（行程与治理域），便于分阶段执行。
两个脚本都带 `CREATE DATABASE IF NOT EXISTS`，可直接执行。

### 8.1 内容域与用户域（14 张，`db/schema.sql`）

| 表 | 说明 | 关键点 |
|---|---|---|
| `sys_user` | 用户 | `role` user/admin，`status` 禁用标记 |
| `category` | 分类（二级树） | `parent_id=0` 为一级 |
| `tag` / `work_tag` | 标签与关联 | `use_count` 冗余计数 |
| `work` | 攻略内容主体 | **新增 `destination`（目的地）与 `trip_days`（天数）** —— 这是"攻略"比"摄影作品"多的核心筛选维度 |
| `work_image` | 图片明细 | `sort=0` 为封面，**真实读写，不做单图退化** |
| `comment` | 评论（二级回复） | `parent_id` + `reply_to_user_id` |
| `like_record` | 点赞（多态） | 唯一索引 `(user_id, target_type, target_id)` |
| `favorite` / `follow` | 收藏 / 关注 | 联合唯一索引 |
| `private_message` | 私信 | `conversation_id` 分组 |
| `user_third_account` | 第三方登录 | **预留，只建表，无 Service / Controller** |
| `report` | 举报 | **预留，只建表，无 Service / Controller** |
| `admin_operation_log` | 操作日志 | ⚠️ **只有实体与 Mapper，没有任何写入代码** —— 上一版文档写「本版真实写入」是错的，已更正（见 [十九](#十九已知局限与扩展方向)） |

初始数据：管理员账号 + **8 个分类**（古建探访 / 自然风光 / 博物馆 / 市井烟火 / 美食之旅 / 亲子出行 / 摄影旅拍 / 城市漫步）。

### 8.2 行程域与治理域（8 张，`db/schema-trip.sql`）

#### `sys_config` 运行时开关

`id / config_key(UNIQUE) / config_value / value_type(STRING|INT|BOOL|JSON) / group_name / description / updated_by / updated_at`

**脚本里的初始值（下表即建库后的默认状态）**：

| key | 初始值 | 类型 | 说明 |
|---|---|---|---|
| `llm.enabled` | `true` | BOOL | 关闭后 AI 规划入口不可用 |
| `llm.active-provider` | `qwen` | STRING | 当前厂商：qwen / glm / deepseek / mock |
| `llm.fallback-order` | `qwen,glm,deepseek` | STRING | 降级顺序，逗号分隔 |
| `llm.timeout-ms` | `240000` | INT | 单次调用超时（毫秒），**每次调用都读，改完不重启即生效** |
| `llm.breaker.fail-threshold` | `3` | INT | 大模型熔断阈值（比地图低：一次失败要白等整个超时） |
| `llm.breaker.open-seconds` | `300` | INT | 大模型熔断保持时长 |
| `llm.price.{qwen,glm,deepseek}-input` | `0` | STRING | 输入单价（元/百万 token，**STRING 存小数** —— 用 INT 会把 0.3 取整成 0，直接变成「未配置」） |
| `llm.price.{qwen,glm,deepseek}-output` | `0` | STRING | 输出单价，同上 |
| `llm.price.glm` / `llm.price.deepseek` | `0` | INT | **兼容保留**的单一价（输入输出同价），仅在上面 6 个键都没配时兜底 |
| `map.enabled` | `false` | BOOL | 地图总开关。**默认关闭：无 AK 也能全功能开发** |
| `map.baidu.ak` | `''` | STRING | 运行时 AK，优先于 yml；后台回显一律掩码 |
| `map.breaker.fail-threshold` | `5` | INT | 地图熔断阈值 |
| `map.breaker.open-seconds` | `300` | INT | 地图熔断保持时长 |
| `trip.max-days` | `5` | INT | 行程天数上限 |
| `trip.max-candidate` | `20` | INT | 候选点上限 |
| `trip.max-replan-rounds` | `2` | INT | 最大重排轮次 |
| `trip.poi-cache-ttl-hours` | `24` | INT | POI 缓存时长 |
| `trip.sse-timeout-ms` | `600000` | INT | SSE 通道超时，**必须大于最坏情况的一次生成耗时** |

> 上表里 `llm.price.{qwen,glm,deepseek}-input` / `-output` 是简写，**展开就是这 6 个键**：
> `llm.price.qwen-input`、`llm.price.qwen-output`、`llm.price.glm-input`、`llm.price.glm-output`、
> `llm.price.deepseek-input`、`llm.price.deepseek-output`（脚本里是 6 条独立 INSERT）。

> ⚠️ **单价留 0 是有意的**：0 = 「未配置」，此时 `AiLogService.estCost` 返回 `null` 而不是 0。
> 成本是简历/答辩上会被追问的数字，**宁可显示「未配置」也不能编一个看起来合理的值**。
>
> ⚠️ **`db/schema-trip.sql` 对本表是 `DROP TABLE` 后重建**。手工改过的配置（厂商、AK、单价）
> **重跑脚本会全部丢失**。生产部署请勿重复执行该脚本 —— 见 [部署说明](部署说明.md)。
>
> ⚠️ **本机现网库与脚本存在漂移**（如实记录）：现网 `sys_config` 里**没有 `llm.breaker.*` 与
> `llm.price.qwen-*` 这些键**（它们是在本机建库之后才补进脚本的）。缺键时走**代码默认值**，
> 所以大模型熔断阈值仍是 3，但**后台配置页改不到它** —— 需要时手工 `INSERT` 或重跑脚本。

#### `user_travel_profile` 用户旅行偏好

`id / user_id(UNIQUE) / cuisines / flavors / taboos / travel_styles / pace / budget_level / companions / walk_limit_km / hotel_pref / notes / allow_ai_use / created_at / updated_at`

> 可空列**一律 `DEFAULT NULL`，不要 `DEFAULT ''`**：MyBatis-Plus 插入时会跳过值为 null 的字段，
> 该列便取 MySQL 列默认值。若写成 `DEFAULT ''`，用户的「清空」会存成空串而非 NULL，
> 「未填写」就同时有 `null` 和 `''` 两种表示，`WHERE x IS NULL` 会静默漏行。

#### `trip` 行程主表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `user_id` | — | — |
| `title` | VARCHAR(100) | 用户可改，AI 生成默认值 |
| `raw_input` | TEXT | 用户原始输入 |
| `intent_json` | JSON | 意图解析结果 |
| `destination` | VARCHAR(50) | 目的地 |
| `dest_lng` / `dest_lat` | DECIMAL(10,7) | 中心坐标，**地图关闭时为 NULL（正常状态，不是数据缺失）** |
| `days` / `start_date` | INT / DATE | — |
| `budget_total` / `budget_mode` | DECIMAL(10,2) / TINYINT | 口径 1 人均 2 总计 |
| `transport` / `companion` | VARCHAR | — |
| `map_mode` | VARCHAR(10) | **VERIFIED / CACHED / ESTIMATED** |
| `profile_used` | TINYINT | 本次是否注入画像 |
| `guide_text` | TEXT | 攻略文案（P4-B 写回） |
| `status` | TINYINT | 0 草稿 / 1 已生成 / 2 已编辑 / 3 已发布 |
| `work_id` | BIGINT | 发布后关联 `work.id` |
| `model_name` | VARCHAR(50) | 本次模型（如 `glm-5.3-flash` / `deepseek-flash`） |
| `generation_rounds` | INT | 实际重排轮次 |

索引：`idx_user_id`、`idx_status_created`。
**没有任何外键约束** —— 删行程必须手工按序清理 `trip_day` / `trip_item` / `ai_generation_log` / `external_call_log`，否则会留下孤儿行（已实测踩过）。

#### `trip_day` 行程日

`id / trip_id / day_index（从 1 起） / title（当天主题） / summary / created_at`
索引：`(trip_id, day_index)`

> `day_index` 从 **1** 起（直接出现在界面上），`trip_item.seq` 从 **0** 起（数组下标习惯）。
> **不要统一成同一个起点** —— 统一了反而要在渲染时到处 +1/-1，是 bug 温床。

#### `trip_item` 行程条目（核心表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `trip_id` / `day_index` / `seq` | — | 当天顺序 |
| `item_type` | VARCHAR(10) | SCENIC / FOOD / HOTEL / TRANSPORT / REST |
| `poi_uid` | VARCHAR(64) | **地图关闭时为空** |
| `poi_name` / `address` | VARCHAR | — |
| `lng` / `lat` | DECIMAL(10,7) | **可空** |
| `arrive_time` / `leave_time` | TIME | — |
| `stay_minutes` | INT | — |
| `ticket_price` | DECIMAL(10,2) | 可为 NULL（地图未提供，表示"未知"）。**免费景点是 0，两者不可混同** |
| `cost_estimate` | DECIMAL(10,2) | 费用估算 |
| `transport_mode_to_next` | VARCHAR(10) | — |
| `distance_meters` / `duration_seconds` | INT | **估算模式下为 NULL** |
| **`verify_status`** | VARCHAR(10) | **VERIFIED / CACHED / ESTIMATED / USER** |
| **`data_source`** | VARCHAR(10) | **BAIDU / LLM / USER** |
| `reason` | VARCHAR(500) | AI 给出的安排理由（属语义字段，来自 LLM 是合规的） |
| `note` | VARCHAR(500) | 备注（估算模式下放模糊表述，如「步行约十几分钟」） |

索引：`(trip_id, day_index, seq)`

#### `poi_cache` POI 本地缓存

`id / provider / city / keyword / poi_uid(UNIQUE) / name / address / lng / lat / tag / shop_hours / rating(可NULL) / ticket_price(可NULL) / raw_json / fetched_at / expires_at / updated_at`
索引：`(city, keyword)`、`(city, name)`、`(poi_uid)`、`(updated_at)`
用途：`BaiduMapProvider` 成功检索后回写；`LocalCacheMapProvider` 只读它。

> `provider` / `keyword` / `fetched_at` / `expires_at` 四列**已建好但暂未写入**；
> `LocalCacheMapProvider` 按 `city` + `name`/`tag` 模糊匹配读取，**不依赖这四列**，所以为 NULL 时缓存降级照常工作。
> `rating` / `ticket_price` **允许 NULL 且刻意不设 DEFAULT 0**：用 0 冒充「未知」是数据造假。

#### `ai_generation_log` AI 生成日志

`id / user_id / trip_id / stage / provider / model / prompt_tokens / completion_tokens / total_tokens / duration_ms / success / error_code / error_msg / created_at`
索引：`(user_id, created_at)`、`(stage, success)`、`(trip_id)`

> `error_code` 是 **VARCHAR(30) 而不是 TINYINT**：这些码要出现在日志、看板、接口响应里，
> 可读性比省几个字节重要（`MAP_BREAKER_OPEN` 比 `2204` 有用）。
> `*_tokens` **允许 NULL**：失败时可能拿不到 usage，**不要写 0** —— 0 会污染 SUM 出来的成本统计。

#### `external_call_log` 外部调用日志

`id / user_id / trip_id / connector(LLM|BAIDU_MAP) / api_name / request_summary(已脱敏) / http_status / duration_ms / success / error_msg / created_at`
索引：`(connector, created_at)`、`(user_id)`、`(success)`、`(api_name)`

**两张日志表的分工，别混**：

| 表 | 视角 | 粒度 |
|---|---|---|
| `external_call_log` | **传输层**：网络调用成没成 | 每一次 HTTP 尝试一条（GET 重试会留两条） |
| `ai_generation_log` | **业务阶段**：这一步的语义任务成没成、花了多少钱 | 一个管线阶段一条 |

一次阶段调用可能对应多条 `external_call_log`（重试），**两者不是一一对应**。

### 8.3 实体关系

```
sys_user ──1:1──▶ user_travel_profile
sys_user ──1:N──▶ trip ──1:N──▶ trip_day
                     │
                     └──1:N──▶ trip_item（item_type 区分景点/餐饮/住宿/交通/休息）
trip ──0:1──▶ work（发布后反向关联）
sys_user ──1:N──▶ work ──1:N──▶ work_image
                    │
                    └──N:N──▶ tag（经 work_tag）
poi_cache / sys_config（独立）
ai_generation_log / external_call_log（追加型，按 user_id / trip_id 关联）
```

---

## 九、Redis 设计

> 下表每个 key 前缀都在代码里核对过，**没有列的 key 就是代码里没有**。

| Key | 类型 | TTL | 写入位置 |
|---|---|---|---|
| `sys:config` | Hash | **30 s** | `SysConfigServiceImpl` —— 「L2 配置 30 秒内生效」就来自这里 |
| `map:poi:baidu:{city}:{keyword}:{pageNum}:{pageSize}` | String(JSON) | **24 h** | Spring Cache（`CacheConfig`，前缀 `map:poi:` + SpEL key） |
| `map:detail:{poiUid}` | String(JSON) | **7 d** | Spring Cache（`unless = "#result == null"`） |
| `map:route:{mode}:{fromLng},{fromLat}:{toLng},{toLat}` | String(JSON) | **12 h** | `LocalCacheMapProvider` **直连 Redis**（熔断降级时也要能读到同一份）；坐标保留 5 位小数（约 1 米精度） |
| `cb:fail:{provider}` | String | 1 h | `CircuitBreaker`（失败计数；大模型与地图共用前缀，靠 `{provider}` 区分） |
| `cb:open:{provider}` | String | `open-seconds + 60` | `CircuitBreaker`（存打开截止时间戳） |
| `auth:blacklist:{token 的 SHA-256}` | String | 取 token 剩余有效期 | `TokenBlacklist`（登出时写入） |
| `like:count:{targetType}:{targetId}` | String | — | `LikeServiceImpl` |
| `favorite:count:{workId}` | String | — | `FavoriteServiceImpl` |
| `follow:follower:{userId}` / `follow:following:{userId}` | String | — | `FollowServiceImpl` |
| `browse:history:{userId}` / `browse:tags:{userId}` | ZSet | — | `BrowseHistoryServiceImpl` |

> **大模型调用一律不缓存** —— 同一个输入需要能拿到可复现的新结果，缓存会让「换个说法再问一次」看起来没反应。
>
> ⚠️ **已知缺陷（未修）：POI 检索的空结果也会被缓存 24 h** —— `disableCachingNullValues()`
> **只挡 `null`、不挡空 `List`**，而 `searchPoi` 查不到时返回的正是空 List。后果是
> **用户搜一个百度查不到的词，24 小时内再搜都直接返回空**。修法只有一行
> （`@Cacheable` 加 `unless = "#result == null || #result.isEmpty()"`），**待拍板**。

---

## 十、REST 接口清单

统一前缀 `/api`（`server.servlet.context-path`）。
**完整清单（105 个端点 / 20 个 Controller）见 [`docs/接口清单.md`](接口清单.md)，该文件由
`scripts/gen-api-list.py` 从真实注解自动生成** —— 手写清单一定会和代码漂移，本项目已有过一次教训。

```bash
# 仓库根目录执行，重新生成 docs/接口清单.md 与 README 的汇总表
"C:/Users/Apollo/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" scripts/gen-api-list.py
```

### 10.1 按 Controller 汇总

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

> 权限列口径：**公开** = 该**路径**命中 `JwtInterceptor.PUBLIC_PATHS` 白名单；
> **管理员** = 方法体里调用了 `checkAdmin()`；**登录** = 其余。
> ⚠️ 白名单**按路径匹配、不区分 HTTP 方法**，所以 `PUT` / `DELETE /works/{id}` 也落在白名单里 ——
> 它们**能不能真的操作**由方法内部的作者校验决定（这正是「先认身份、再判放行」的设计）。
> **运行期**才能判定的权限（作者本人 / 资源归属）无法从注解静态推导，这类接口只显示路径级权限。

### 10.2 行程与 AI（`TripController`，10 个）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/trip/plan/sync` | 同步生成完整行程并落库，body `{rawInput, useProfile, overrides}`；响应含 `meta` |
| POST | `/trip/parse` | **只解析意图**（前端四步流程的「确认参数」步骤，不跑后续管线） |
| POST | `/trip/plan/stream` | 流式生成（SSE），请求体与 `plan/sync` 完全相同 |
| POST | `/trip/{id}/regenerate-copy?provider=` | 只重生成文案，不重跑管线（SSE，可指定厂商做效果对比） |
| POST | `/trip/{id}/replan` | 重排：**只重排「第 N 天」所在范围**，从 `feedback` 里解析 dayIndex |
| PUT | `/trip/{id}/days/{dayIndex}/order` | 重排某一天的条目顺序（前端「上移 / 下移」用它） |
| PUT | `/trip/{id}/publish` | 发布为攻略，写回 `work_id` |
| GET | `/trip/{id}` | 详情（本人可见；未发布的非本人 403） |
| GET | `/trip/my` | 我的行程（分页） |
| DELETE | `/trip/{id}` | 逻辑删除（置 `trip.deleted`） |

### 10.3 偏好画像（`TravelProfileController`）

`GET /profile/travel`（读）、`PUT /profile/travel`（存在则更新，不存在则插入）。

### 10.4 诊断与配置

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/diagnostics/connectors` | 管理员 | 两个连接器的一次性总览：开关、当前决策、熔断状态、调用统计 |
| GET | `/diagnostics/llm/providers` | 管理员 | 逐厂商列出模型名、Base URL、**掩码后的 Key**、可用性、熔断快照 |
| POST | `/diagnostics/llm/ping` / `/diagnostics/map/ping` | 管理员 | 连通性测试（**会真实外呼**） |
| GET | `/connector/llm/status` | 管理员 | 各 Provider 状态快照 + 本次会选中哪一家（**不发起外部调用**） |
| GET | `/connector/llm/ping` / `/connector/llm/stream-ping` | 管理员 | 真实调用 / 流式连通性测试 |
| GET | `/connector/map/status` | 管理员 | 当前能力决策与熔断状态（**不发起外部请求**） |
| GET | `/connector/map/search` / `/connector/map/route` | 管理员 | 走完整降级链的真实检索 / 路线规划 |
| POST | `/connector/map/breaker/reset` | 管理员 | 手动重置熔断（调好 AK 之后不必干等 5 分钟） |
| GET / PUT | `/admin/configs`、`/admin/configs/{key}`、`/admin/configs/group/{group}` | 管理员 | sys_config 读写（**写后立即清 Redis 缓存**） |
| POST | `/admin/config/llm`、`/admin/config/map/enabled` | 管理员 | 一键修改大模型开关 / 一键开关地图，立即生效 |
| GET | `/admin/generation/stats` / `trend` / `logs` / `external-calls` / `trips/{tripId}/breakdown` | 管理员 | 生成统计、趋势、明细、外呼日志、单条行程拆解 |

> **鉴权纪律**：管理接口一律走 `JwtInterceptor` + Controller 内 `checkAdmin()` 双重校验，
> **不允许放进 `PUBLIC_PATHS` 排除名单**。
>
> ⚠️ **路径写法**：`context-path` 已是 `/api`，`@RequestMapping` **只写应用内路径**；
> 写全路径会变成 `/api/api/...` → 500。

### 10.5 内容域与社交域（摘要）

| 模块 | 路径 | 说明 |
|---|---|---|
| 认证 | `/auth/register` `/auth/login`【公】 `/auth/logout` | 登出把 token 加入 Redis 黑名单 |
| 用户 | `/users/me` `/users/{id}` `/users/page`【管】 `/users/{id}/status`【管】 `/users/{id}`【管·删】 | — |
| 分类 | `/categories/tree`【公】 `/categories/page`【管】 `/categories` CRUD【管】 | — |
| 内容 | `/works` CRUD、`/works/page`【公】、`/works/{id}`【公】、`/works/{id}/trip`【公】、`/works/my`、`/works/{id}/status`【管】 | 发布/编辑支持 `imageUrls` 多图数组与 `destination`/`tripDays` |
| 标签 | `/tags`【管】 `/tags/{id}` `/tags/page` `/tags/hot`【公】 `/tags/batch`【管】 | — |
| 评论 | `/comments` `/comments/work/{workId}`【公】 `/comments/{id}/replies` `/comments/my` | — |
| 点赞/收藏/关注 | `/likes/*` `/favorites/*` `/follows/*` | toggle 模式 |
| 私信 | `/messages/*` | 会话列表 / 消息 / 未读统计 / 标记已读 |
| 推荐 | `/recommend/feed` `/personal` `/hot`【公】 `/similar/{workId}` `/browse/{workId}` `/user-tags` `/history` | 标签匹配，未做协同过滤 |
| 文件 | `/files/upload` `/files/batch` `/files/avatar` | 上传即水印（头像不加） |
| 健康 | `/health`【公】 | `{"status":"UP",...}` |

---

## 十一、前端设计

### 11.1 路由与页面

| 路由 | 页面组件 | 权限 | 说明 |
|---|---|---|---|
| `/` | `Home.vue` | 公开 | 攻略瀑布流（目的地/天数/分类筛选）、推荐、热门；AI 生成的攻略带角标 |
| `/plan` | `Plan.vue` | 【登】 | AI 规划四步流程，见 11.2 |
| `/trips` | `MyTrips.vue` | 【登】 | 我的行程 |
| `/work/:id` | `WorkDetail.vue` | 公开 | 含「完整行程」时间轴区块 |
| `/publish` `/edit/:id` | `WorkPublish.vue` | 【登】 | 多图 + 目的地 + 天数 |
| `/profile` | `Profile.vue` | 【登】 | 个人中心，含「旅行偏好」页签 |
| `/user/:id` | `UserHome.vue` | 公开 | 用户主页 |
| `/messages` | `Messages.vue` | 【登】 | 私信 |
| `/login` `/register` | `Login.vue` / `Register.vue` | 公开 | — |
| `/admin` `/admin/works` `/admin/users` `/admin/audit` | `views/admin/*` | 【管】 | 内容·用户·分类·审核 |
| `/admin/connectors` | `views/admin/Connectors.vue` | 【管】 | 连接器管理页（P6-A） |
| `/admin/generation` | `views/admin/Generation.vue` | 【管】 | AI 生成监控看板（P6-B） |
| `/:pathMatch(.*)*` | `NotFound.vue` | 公开 | 404 |

### 11.2 AI 规划四步流程（el-steps）

```
Step 1 输入
  大 textarea + 「本次将参考的偏好」chips（可临时取消）+ 本次临时忌口 + 「开始规划」
  画像为空 / 隐私开关关闭时，显示对应引导文案

Step 2 确认参数
  调用 POST /trip/parse，把意图解析结果渲染为可编辑表单
  （目的地/日期/天数/预算含口径切换/交通/同行人/节奏）
  needConfirm 中的字段标橙色「AI 猜测，请确认」
  用户点「确认」才发起真正的生成请求 ← 避免 AI 猜错导致整条流水线白跑

Step 3 生成中
  六阶段进度条按 stage 事件点亮；FALLBACK 状态显示橙色提示
  收到 itinerary 事件立即切到结果视图渲染骨架（不等文案）
  文案区打字机效果；显示「本次已参考你的偏好：…」

Step 4 结果与编辑
  顶部 mapMode 状态条（绿实测 / 蓝缓存 / 橙估算）
  行程地图区块：**未接入百度地图 JS API** ——
    · 无坐标（估算模式）→ 提示「未启用地图校验，本次行程没有实测坐标 —— 因此也不显示地图，
      而不是给你一张空白地图」
    · 有坐标但未配 VITE_BAIDU_MAP_AK → 提示已有点位坐标数量、未配置前端 AK
  时间轴按天 tabs；条目可上移下移/删除/保存顺序
  费用汇总（估算部分显式标注）
  底部：保存草稿 / 发布为攻略 / 重新生成
```

### 11.3 数据来源角标体系

| 角标 | `verify_status` | 含义 |
|---|---|---|
| 绿「实测」 | VERIFIED | 地图 API 实测 |
| 灰「缓存」 | CACHED | 本地缓存命中 |
| 橙「估算」 | ESTIMATED | 地图关闭或没查到，未经地图校验 |
| 蓝「手动」 | USER | 用户手改 |

### 11.4 组件复用

| 文件 | 作用 |
|---|---|
| `components/TripTimeline.vue` | 时间轴组件，规划页（可编辑）与攻略详情页（只读）共用 |
| `components/TripResult.vue` | 规划结果整体视图（状态条 / 地图区块 / 文案区 / 费用汇总） |
| `components/WorkCard.vue` | 瀑布流卡片 |
| `utils/sseParser.js` | SSE 流解析工具，独立可测 |
| `api/*.js` | 按域拆分的 axios 封装（`request.js` 统一 baseURL `/api` 与 token 注入） |

---

## 十二、配置与密钥管理

### 12.1 环境变量

**后端** `wayfare-backend/.env.properties`（**已 gitignore，仓库内只有 `.env.properties.example` 模板**）：

| 变量 | 必填 | 用途 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | ✅ | 数据库密码 |
| `QWEN_API_KEY` | ⬜ | 阿里云百炼 Qwen；不填则该厂商不可用 |
| `GLM_API_KEY` | ⬜ | 智谱开放平台 |
| `DEEPSEEK_API_KEY` | ⬜ | DeepSeek 开放平台 |
| `BAIDU_MAP_AK` | ⬜ | 百度地图 Web 服务 AK；不填则地图整体不可用（自动走估算模式） |
| `JWT_SECRET` | ⬜ | 不填则用 yml 里的开发默认值（**生产必改**） |

**前端** `wayfare-frontend/.env.local`（**仓库内不存在该文件**，需要时自建，Vite 会 gitignore）：

| 变量 | 用途 |
|---|---|
| `VITE_BAIDU_MAP_AK` | 百度地图 **JS API** AK。⚠️ 目前只被 `TripResult.vue` 读用于判断「能不能渲染地图」，**未接入 JS API 本体**（见 [十九](#十九已知局限与扩展方向)） |

> **三个大模型 Key 全都不填也能跑**：自动回落内置离线 Mock，全链路（含前端流式渲染）照常演示。
> 三个 Key 的申请与配置步骤见 [部署说明](部署说明.md)。

### 12.2 `application.yml` 相关段（**当前真实值**）

```yaml
server:
  port: 8080
  servlet:
    context-path: /api          # 前端 request.js 的 baseURL 是 /api，必须是 /api

llm:
  enabled: true
  active-provider: qwen         # L1 兜底；sys_config 里有值会盖过它（现网为 deepseek）
  fallback-order: qwen,glm,deepseek
  timeout-ms: 240000            # L1 兜底；sys_config 的 llm.timeout-ms（L2）优先，且每次调用都读
  providers:
    qwen:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: "${QWEN_API_KEY:}"
      model: qwen3.8-flash      # 百炼模型名大小写敏感，必须全小写
      temperature: 0.3
      max-tokens: 4096
    glm:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: "${GLM_API_KEY:}"
      model: glm-5.3-flash
      temperature: 1
      max-tokens: 16384
      reasoning-effort: low     # 🔴 该系列强制思考，不传就按默认高档跑（表现为挂死）
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: "${DEEPSEEK_API_KEY:}"
      model: deepseek-flash     # deepseek-chat 已不在官方模型列表
      temperature: 0.3
      max-tokens: 32768
      reasoning-effort: none    # 合法 8 档，none = 彻底关思考

map:
  enabled: false                # L1 兜底：默认关闭，无 AK 也能全功能开发
  baidu:
    ak: "${BAIDU_MAP_AK:}"
    base-url: https://api.map.baidu.com
    connect-timeout-ms: 3000
    read-timeout-ms: 8000
```

> L1 与 L2 的关系：**L2 覆盖 L1，L2 未配置时回落 L1**。
> 数据库 / Redis 连接地址、模型名、Base URL 属 L1（改完必须重启）；
> 切厂商 / 开关地图 / 熔断阈值 / 超时 / SSE 超时 / 单价属 L2（**改完 30 秒内生效、不用重启**）。

### 12.3 密钥安全规范

1. Key 只存在于服务端（环境变量 / `sys_config`），**任何接口、日志、前端代码不得出现明文**
2. 后台回显一律掩码（前 4 位 + `****`），输入框留空表示不修改
3. `external_call_log.request_summary` **写入时**脱敏（先脱敏再截断，顺序不能反）
4. `.env.properties` 不入库，只提交 `.env.properties.example`

---

## 十三、降级与容错设计

### 13.1 失败降级矩阵

| 失败点 | 系统行为 | 用户感知 |
|---|---|---|
| 意图解析失败 | 重试 1 次（错误回喂）后抛 `SCHEMA_INVALID` | 提示换个说法，不消耗下游 token |
| 大模型主力不可用 | 按 `llm.fallback-order` 切下一厂商 | 无感（`meta` 里记录 fallback） |
| 主力的熔断已打开 | **直接跳过它**，不发起请求、不干等超时 | 无感（省下一个完整超时的等待） |
| 三家全不可用 | 回落 `MockLlmProvider` | 链路可演示，`meta` 标记 provider=mock |
| 地图被管理员关闭 | 全流程切 ESTIMATED，`ROUTE` 阶段发 `FALLBACK`（**不是 `error`**） | 行程照常生成，距离标"估算" |
| 地图连续失败（≥5 次） | 熔断打开 → 查 `poi_cache` → 命中 CACHED / 未命中 ESTIMATED | 同上 |
| 地图 AK 无效（百度 `status=200` 但 message 报 AK 有误） | 靠 message 文案兜底识别 → 熔断计数 +1，诊断接口可见原因 | 同"地图连续失败" |
| 候选不足 | 扩半径 → 放宽标签 → 仍不足带 `shortage` + `shortageHint` 返回 | "该目的地可玩点位较少，是否扩大范围？" |
| 编排解析失败 | 重试 1 次；仍失败返回错误但**保留候选池** | 用户可手选点位 |
| 校验 2 轮仍不通过 | 返回当前最优 + violations | 行程带风险标注，**绝不死循环** |
| 路线补全部分失败 | 成功段填真实值，失败段标 ESTIMATED | 局部角标变化 |
| 公交无数据（县域） | 自动降级 driving 并在 note 标注 | 备注可见 |
| 文案流式失败 | **`itinerary` 已推送，行程不丢**；发 `error` | "文案生成失败，可重试" |
| 客户端断开 | 感知 IOException，**在增量回调里抛异常**以中断上游读取 | 不再继续推流。⚠️ 实测**只有约 1/3 能真正掐断**，见 [§7.2](#72-技术约束) |

### 13.2 熔断参数与状态

| 侧 | `fail-threshold` | `open-seconds` | 半开行为 |
|---|---|---|---|
| 大模型（`llm`） | 3 | 300 | 到期放行一次试探，成功清零关闭、失败重新计时 |
| 地图（`map`） | 5 | 300 | 同上 |

状态通过 `/api/diagnostics/connectors`、`/api/connector/llm/status`、`/api/connector/map/status`
读取，后台状态卡片展示；可 `POST /api/connector/map/breaker/reset` 手动复位。

> **为什么大模型阈值更低**：大模型一次失败要白等一整个 `llm.timeout-ms`（默认 240 秒），
> 地图一次失败只损失几百毫秒。用同一套阈值会让两边都不可控。

### 13.3 端到端降级演练（**已完成，不是「建议」**）

```powershell
# 仓库根目录执行（后端需已启动）
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1
# 只想看降级对比、不做故障注入：
powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1 -SkipFaultInjection
```

```
关地图生成 → 断言全 ESTIMATED + distanceMeters 全空 + 文案无「N 公里/N 分钟」
  → 开地图生成 → 断言出现 VERIFIED + 存在有距离的条目
  → 故障注入（把 map.baidu.ak 写坏触发熔断）→ 断言 mode 降为 CACHED 且生成不中断
  → 恢复演练前的 map.enabled 与 AK → 输出报告
```

- 报告落在 **`docs/drill-report.md`**（含**可直接进答辩 PPT** 的对比表）。实测 **20 / 20 通过**。
- 故障注入**不需要重启**：`map.baidu.ak` 是 L2 配置，写坏→熔断→设回即可。
- 脚本会**先清 Redis 里的 POI 缓存**，否则请求命中缓存就测不到熔断（本脚本踩过的坑）。
- 断言失败时**明确报出是哪一条、实际值是什么**，并以退出码 1 结束 —— 不吞失败。

> 首轮演练就抓到一个真缺陷：估算模式的兜底文案 `约 15 分钟左右可达` **带精确数字**，
> 与它自己声明的「禁止给精确数字」矛盾（已修）。

---

## 十四、可观测性与成本核算

### 14.1 日志落点

1. **`ai_generation_log`**：每个 stage 一条（provider / model / tokens / duration / success / error_code）
2. **`external_call_log`**：每次出站 HTTP（connector / api_name / http_status / 耗时 / 脱敏摘要）
3. **诊断接口**：双连接器的实时开关、决策、熔断快照
4. **后台看板 `/admin/generation`**：统计卡片 + **5 张图表**
   （近 7 天生成次数与成本 / 各阶段平均耗时 / 地图模式分布 / 失败原因 Top 5 / 耗时大头在哪）
   + 生成明细分页 + 外呼日志页签

### 14.2 聚合接口

`GET /api/admin/generation/stats?from=&to=` 返回：

```
totalCount / successCount / successRate
avgDurationMs / p95DurationMs
totalTokens / avgTokensPerTrip / totalEstCost / avgCostPerTrip
stageBreakdown[]:   {stage, total, successCount, tokens, durationMs}
mapModeBreakdown[]: {mapMode, count, avgDurationMs}
topErrors[]:        {code, count}          // 取 Top 10
```

另有 `GET /api/admin/generation/trend?days=7`（每日次数与成本，供看板折线图）与
`GET /api/admin/generation/trips/{tripId}/breakdown`（**单条行程**的 token 与成本拆解 —— 这是绕开
`meta.tokens` 污染问题的正确取数方式）。

### 14.3 成本计算

```
estCost = promptTokens × priceInput/1e6 + completionTokens × priceOutput/1e6
```

- 单价从 `sys_config` 读，**输入输出分开计价**：优先 `llm.price.{provider}-input` / `-output`，
  回落旧的单一价 `llm.price.{provider}`（视同输入输出同价），**不硬编码**
- 键用 **STRING** 存小数（`'0.3'`）—— 用 INT 会把 0.3 取整成 0，直接变成「未配置」
- **未配置单价时 `estCost` 返回 `null` 而不是 0** —— 宁可显示「未配置」也不编数字
- 精度保留 **4 位小数**（单次成本是分级数字，不是 6 位）
- 只统计「有单价的厂商」：每次行程都有一条 `provider="map"` 的 `ROUTE` 行，地图无单价，
  所以 `estCost` 天然只覆盖 LLM 部分

> 🔴 **`meta.tokens` / `meta.estCost` 当前不可信（未修）**：`TripOrchestrator.buildMeta()` 按
> 「用户 + 时间窗」查日志，**没有按 `trip_id`** → 8 次补跑里 5 次对不上（既漏自己的行、
> 又算进上一次行程晚写的行，已精确对账）。**取数一律用 `ai_generation_log` 按 `trip_id` 聚合**，
> 或直接调 `/admin/generation/trips/{tripId}/breakdown`。
>
> 🔴 **中断时的「节省」估算高估约 14 倍**：它取的是同类请求整篇 `completion_tokens` 的均值，
> 没有减去已产出。**引用前必须自己减一次**（详见 [§7.2](#72-技术约束)）。

### 14.4 统一 error_code 枚举

定义在 `com.wayfare.trip.AiErrorCode`，写进 `ai_generation_log.error_code`：

| 分组 | 取值 |
|---|---|
| 大模型 | `LLM_TIMEOUT` / `LLM_RATE_LIMIT` / `LLM_AUTH_FAIL` / `LLM_PARSE_FAIL` / `LLM_SERVER_ERROR` |
| 地图 | `MAP_UNAVAILABLE` / `MAP_AUTH_FAIL` / `MAP_BREAKER_OPEN` |
| 管线自身 | `CANDIDATE_SHORTAGE` / `VALIDATION_FAILED` / `SCHEMA_INVALID` / `CLIENT_DISCONNECTED` |

> **为什么不复用 `ResultCode`**：`ResultCode` 是**给用户看的**接口返回码（带中文提示文案，
> 用户会看到「大模型认证失败，请检查 API Key」）；`AiErrorCode` 是**给运维/开发看的**内部归因码。
> 一个失败场景常常是「一个 error_code + 一个 ResultCode」的关系，不是二选一。

---

## 十五、安全设计

| 项 | 措施 |
|---|---|
| 鉴权 | JWT 拦截器（`Authorization: Bearer`），SSE 同样走头校验，**不因流式放开鉴权** |
| 鉴权判定顺序 | **先认身份**（带有效 token 就写 `UserContext`，公开路径也一样）→ **再判放行**（无 token 且不公开 → 401）；带 token 但无效/已登出**即使公开也 401**（让前端清 token 自愈，一次往返即可恢复） |
| 公开白名单 | 唯一权威是 `JwtInterceptor.PUBLIC_PATHS`（**不在 `WebMvcConfig`**）。它是 PathPattern **完整匹配**，且用正则约束 ID 段（`/works/{id:[0-9]+}`）避免把 `/works/my` 一并放行；新增带后缀的公开路径（如 `/works/{id}/trip`）**必须单独加一条** |
| 权限 | 资源归属校验 + 管理接口 `checkAdmin()` 双重校验；未发布的行程非本人 403 |
| 密钥 | 环境变量注入 + 接口/日志全脱敏 + `.env.properties` 不入库 |
| **防幻觉（核心）** | ① 候选集闭包校验 `CLOSURE`，识破编造景点 ② 事实字段强制 `verify_status` / `data_source` 标记 ③ 估算模式禁止精确数值（数值列保持 null）④ 门票评分缺失时保持 null 而非填 0 ⑤ 文案 prompt 在无实测数据时**禁止输出任何距离与时间数字** |
| 输入校验 | 长度/类型/枚举校验（`spring-boot-starter-validation`）；LLM 输出走 JSON Schema 校验 |
| 成本防护 | 客户端断开触发中断；候选数/天数/重排轮次/超时均有上限；熔断避免连续白等 |
| 内容安全 | 敏感词过滤（`ContentAuditService`，词表在 `content.audit.sensitive-words`），用于作品、评论、私信 |
| 图片 | 扩展名 + MIME 双白名单 + 大小限制（10 MB） + 上传即水印 |

> ⚠️ **本系统没有接口级限流**（无 `rate:ip:*` 之类的实现）。上一版文档写过「接口限流」，
> 属「文档有、代码没有」，已删除。当前的成本防护靠「上限 + 熔断 + 客户端断开中断」三件事。

---

## 十六、量化指标

**所有指标必须来自实测，禁止编造。** 测量方法、原始数据、失败样本与诚实提醒见
**[`docs/metrics.md`](metrics.md)**（含测量日期与环境）；2-opt 单项见
[`docs/metrics-preorder.md`](metrics-preorder.md)（测试自动生成，勿手改）。

| 指标 | 实测结论 | 口径与诚实提醒 |
|---|---|---|
| 2-opt 空间预排 | 当候选顺序存在明显交叉时，总里程再降 **9%~25%**；顺直点集改进为 **0** | 不能写「平均降 9.3%」（那会把两组 0% 平摊掉） |
| POI 缓存冷热 | 冷 **329 ms / P95 620 ms** → 热 **8.4 ms / P95 9 ms**（**快 39×**），20 次热调对外请求 **0** 次 | 这是**单次检索**收益；一轮规划约 6 次检索 ≈ 省 1.9 s，占整条管线 82 s 的 **2%** —— 价值在**省配额与抗限流** |
| 校验收敛率 | 30/30 次生成，**两轮内收敛 90.0%**，残留 HIGH **0 次** | 不能报「100%」——那是把「只有 MEDIUM 时只排一次」误当收敛（判据是 `ValidationReport.passed()` = 零违规） |
| 流式输出时间 | 骨架（`itinerary`）**71.0 s** / 全文（`done`）**87.7 s**（glm），骨架占 **81%**；deepseek 整轮 **9.2 s** | 手册目标「骨架 ~15 s」**架构上做不到**（要等 3 次非流式调用） |
| 客户端断开中断 | **9 次只有 3 次真掐断**；真实净节省仅约 **30 tokens** | 「断开即中断」只有 1/3 成立，价值在**不落半成品数据** |
| 单次生成成本 | `deepseek-flash` **4,888 tok / ¥0.0107 / 9.2 s**；`glm-5.3-flash` 5,436 tok / **¥0.0084** / 62.6 s | 性价比要看**单次总成本**而非单价：deepseek 单价更高但输出更省，总价只贵 27%、快 6.8 倍；成本要带时段（DeepSeek 峰谷价，高峰上限约 **¥0.021**） |

**一个被实测淘汰的选项**：`qwen3.8-flash`（免费档）实测 **476 s/次、失败率 55%、输出 token 是另两家的 13~16 倍**
（推测思维链计入 `completion_tokens`）。**「免费」在这里是陷阱 —— 一次 qwen 的 token 量够 deepseek 跑 6.5 次。**

---

## 十七、测试策略

### 17.1 测试总览

```bash
# ⚠️ 跑之前必须先清两张日志表（AiLogServiceTest 断言的是绝对条数）
mysql -uroot -p123456 -D wayfare -e "DELETE FROM ai_generation_log; DELETE FROM external_call_log;"
cd wayfare-backend && mvn test
```

**实测结果（2026-09-24 复核，原始回显）**：

```
[INFO] Tests run: 238, Failures: 0, Errors: 0, Skipped: 0
[INFO] Results:
[WARNING] Tests run: 238, Failures: 0, Errors: 0, Skipped: 6
[INFO] BUILD SUCCESS
[INFO] Total time:  48.881 s
```

JaCoCo 报告在 `target/site/jacoco/index.html`（已绑 `test` 阶段，无需额外命令）：

| 范围 | 行覆盖 | 分支覆盖 |
|---|---|---|
| **`trip` 包（AI 编排管线，本项目核心）** | **85.4%**（1794 / 2102） | 65% |
| `PreOrderService` | 99.1% | — |
| `ItineraryValidator` | 93.5% | — |
| 全库（所有类） | 51.5%（4009 / 7788） | 40% |

> 📌 **口径说明（如实记录）**：`docs/测试用例表.md` 里记的是 P7-A 结项时点的 **86.2% / 51.4%**；
> 主代码在 P7-A 之后还有 3 次提交（P7-C 演练脚本、TABOO 口径修复、CANDIDATE 日志归属修复），
> 所以 2026-09-24 复核实测为 **85.4% / 51.5%**。两个数字都是真实测量，差别在测量时点。
>
> **为什么全库只有 51.5%**：内容域/社交域的 CRUD（`service` 包的大头）是 P0 既有代码，
> **不在 P7-A 的测试清单内**。「只报达标口径」不是诚实的做法，所以两个数一起给出。

### 17.2 单元 / 集成测试（按真实类名，共 232 例）

| 测试类 | 用例 | 覆盖要点 |
|---|---|---|
| `trip.ItineraryValidatorTest` | 36 | **七条规则各一正一反**；CLOSURE 能识破编造景点（含全半角/空格容错）；TABOO 命中 `poiName`；脏输入守卫 |
| `trip.CandidateSearcherTest` | 24 | 去重 / 忌口过滤 / 坐标诚信 / 候选不足降级 / 配额保护 |
| `trip.IntentParserTest` | 24 | **意图 Schema 校验与失败重试**（逐字段读 + 逐字段判：非法 JSON / 缺必填 / 枚举越界 / days 越界） |
| `service.AiLogServiceTest` | 20 | token 聚合与成本计算（输入输出分开计价、统计聚合） |
| `trip.ItineraryComposerTest` | 19 | 行程编排的硬约束校验 / 失败降级 / 距离不采信 |
| `trip.PreOrderServiceTest` | 14 | 不遗漏不重复；2-opt 后里程 ≤ 贪心；无坐标保持原序；单点不报错 |
| `connector.governance.GovernedExternalHttpClientTest` | 13 | 出站重试与降级策略（GET 重试 / POST 不重试 / 429 例外） |
| `controller.AdminApiSecurityIntegrationTest` | 12 | 管理接口权限边界与密钥不外泄；热生效与熔断可观测 |
| `trip.TripCopyPromptTest` | 12 | 攻略文案 prompt 的六条规则与「距离不进 prompt」 |
| `profile.ProfileRendererTest` | 11 | 空字段省略 / overrides 合并与覆盖 / 全空返回空串 |
| `trip.StreamCancellationTest` | 8 | SSE 取消闸门与中断说明 |
| `connector.governance.CircuitBreakerTest` | 7 | 熔断阈值与诊断快照（**大模型 3 / 地图 5 阈值分离**与 Redis 键命名空间） |
| `service.TravelProfileServiceTest` | 7 | 画像读写与 upsert 的 null 语义 |
| `trip.ItineraryReplannerTest` | 7 | 约束违规后的回喂重排 |
| `common.util.MaskUtilTest` | 6 | 密钥脱敏格式正确 |
| `trip.TripSchemaTest` | 5 | 行程表族排序与数据诚信字段（`verifyStatus` / `dataSource` / 估算模式 `note`） |
| `SchemaMappingSmokeTest` | 4 | 实体与表结构映射 |
| `trip.TripOfflinePipelineIntegrationTest` | 2 | **离线全链路的降级诚实性**（地图关闭时全 `ESTIMATED`、距离时长必须为空） |
| `trip.PreOrderMetricsTest` | 1 | 2-opt 量化指标 |

### 17.3 默认跳过的真实验收测试（6 例）

| 测试类 | 用例 | 说明 |
|---|---|---|
| `trip.CandidateSearcherLiveTest` | 3 | 真实调百度地图 |
| `trip.ItineraryComposerLiveTest` | 2 | 真实调大模型 |
| `trip.IntentParserLiveTest` | 1 | 真实调大模型 |

> 用 `mvn test -Dwayfare.live=true` 显式开启。**它们会消耗真实的 API 配额与费用**，不要随手跑。

> ⚠️ `GovernedExternalHttpClientTest` 虽不在跳过名单里，但它**会真实外呼**：用真实域名 + 假 AK 打
> bigmodel / 百度（实测 429 重试占 6.8 s）。假 AK 大概率被拒、不计配额，但**未验证**；
> 后续建议改 MockWebServer。

### 17.4 安全测试

- 断言 `/diagnostics/*` 与 `/admin/*` 响应**不含完整 Key**（正则匹配）
- 断言 `external_call_log.request_summary` 是**写入时**脱敏的（先脱敏再截断，顺序不能反）

### 17.5 端到端降级演练

见 [§13.3](#133-端到端降级演练已完成不是建议)。报告 `docs/drill-report.md`，实测 **20 / 20 通过**。

---

## 十八、部署

> ⚠️ **状态：容器化部署（P8-B）已开工但尚未落地** —— 用户要求**自己动手写 Docker 相关文件**，
> 所以**仓库里目前仍然没有 `Dockerfile` / `.dockerignore` / `nginx.conf` / `docker-compose.yml` / `.env.example`**。
> 本节描述的是**目标方案 + 必须满足的硬约束**；逐步实操教程（面向 WSL + Docker 初学者）见
> [**部署说明.md**](部署说明.md) —— 那份文档是从「装 Docker」一路写到「首次部署检查清单」的。
>
> 现在**立刻可用**的部署方式仍是：建库 → Redis → 后端 `mvn spring-boot:run` → 前端 `npm run dev`，
> 详见 [部署说明 附录 A](部署说明.md)。

### 目标方案（未实施）

```
docker-compose
├── mysql:8.0    初始化执行 schema.sql + schema-trip.sql（挂载到 initdb.d）
├── redis:7-alpine
├── backend      多阶段构建（maven:3.9-eclipse-temurin-17 构建 → eclipse-temurin:17-jre 运行）
│                非 root 用户，HEALTHCHECK 轮询 /api/health
│                三把 Key 通过 .env 注入
└── frontend     node:20-alpine 构建 → nginx:alpine 托管
                 try_files 支持 history 路由；/api 反代 backend:8080
```

- 环境变量通过 `.env` 注入，compose 文件里**不出现明文密钥**
- 提供 `.env.example`（逐项注释）+ `.gitignore`（排除 `.env` / `uploads` / `target` / `node_modules`）
- 首次部署后需在后台完成：配置 Key → 测试连通性 → 按需开启地图
- ⚠️ 容器化时**不要重复执行 `schema-trip.sql`** —— 它对 `sys_config` 是 `DROP TABLE` 后重建，
  会把后台改过的厂商 / AK / 单价全部清掉
- ⚠️ **`db/*.sql` 挂在 `/docker-entrypoint-initdb.d` 只在「数据目录为空」时执行一次**；
  之后再改 schema 必须手工 ALTER（或删卷清库重来）
- 🔴 **nginx 必须关缓冲 + 加长读超时**，否则 SSE 会被攒批 / 60 秒掐断：
  表现是「生成行程一直转圈」。`proxy_read_timeout` 要大于 `trip.sse-timeout-ms`（默认 600000 ms）
- 🔴 **`/uploads` 要反代到后端的 `/api/uploads`**（后端在 context-path 内映射该路径），
  漏掉这条 = 所有图片裂掉

**实施前必须补的三件事**（否则容器起来也是坏的）：
1. 🔴 **`file.upload.upload-dir` 必须先改成可被环境变量覆盖**（目前是写死的 `D:/Code/...`，
   容器里不存在 `D:` 盘）—— 具体那 1 行改法见 [部署说明 §4](部署说明.md)
2. 上传目录需 volume 挂载，否则重建容器会丢图
3. 前端若要做地图可视化，需要在**构建期**注入 `VITE_BAIDU_MAP_AK`
   （⚠️ **不是**手册写的 `BAIDU_MAP_JS_AK`；且**当前前端未接入 JS API**，配了也不会画地图）

> ⚠️ **`LLM_ACTIVE_PROVIDER` / `MAP_ENABLED` 这类环境变量会被 L2（`sys_config`）静默覆盖**
> —— `sys_config` 的值由 MySQL 首次初始化时从 `schema-trip.sql` 写入。
> 想改厂商 / 开地图，**起完去后台 `/admin/connectors` 改**（≤30 秒生效），或改脚本里的 INSERT。

---

## 十九、已知局限与扩展方向

### 19.1 已实现但效果有折扣的（**答辩时主动讲**）

| # | 事项 | 事实 | 影响 |
|---|---|---|---|
| 1 | 客户端断开中断 | **9 次实测只有 3 次真掐断**（检测点在下一次 `send()`，delta 仅 1.4 字符/块，被 socket 缓冲吸收） | 价值在「不落半成品数据」，不在省成本 |
| 2 | 中断日志的「节省 N tokens」 | 取的是整篇均值、**未减已产出**，高估约 14 倍（自报 429 / 实际净省约 30） | 引用前必须自己减一次 |
| 3 | `meta.tokens` / `meta.estCost` | 按「用户 + 时间窗」查日志，未按 `trip_id`，被相邻行程污染 | 取数改用 `ai_generation_log` 按 `trip_id` 聚合 |
| 4 | POI 空结果被缓存 24 h | `disableCachingNullValues()` 只挡 `null`、不挡空 `List` | 用户搜到查不到的词，24 h 内再搜仍返回空。修法一行，**待拍板** |
| 5 | `mode` / `provider` 分不出「实测」与「缓存命中」 | `@Cacheable` 命中时方法体不执行，`mode` 仍是 `VERIFIED` | 判冷热只能靠 `external_call_log` 行数增量（冷 +1 / 热 +0） |
| 6 | 触发重排的具体规则码没进日志 | 只有 `VALIDATE` 一条 `success=0` | 无法分析「哪些规则最常导致重排」 |
| 7 | `qwen3.8-flash` 已不可用 | 476 s/次、失败率 55%、输出 token 是别家 13~16 倍 | 已从降级链末尾保留但不宜启用 |
| 8 | 现网 `sys_config` 与脚本漂移 | 缺 `llm.breaker.*` 与 `llm.price.qwen-*` 键 | 走代码默认值，**后台改不到** |

### 19.2 未实现 / 明确未做的

| # | 事项 | 说明 |
|---|---|---|
| 1 | **前端地图可视化** | 未接入百度地图 JS API；`VITE_BAIDU_MAP_AK` 只用于判断「能不能渲染」，`mapEl` 没有被任何初始化代码使用。有坐标时也不会画点连线 |
| 2 | **接口级限流** | 无实现（`rate:ip:*` 只是上一版文档的设想） |
| 3 | **`admin_operation_log` 写入** | 只有实体与 Mapper，没有写入代码；管理员敏感操作不会被记录 |
| 4 | **容器化部署（P8-B）** | 用户明确要求暂缓；无 Dockerfile |
| 5 | 举报模块 / 第三方登录 | 表已建，本期无代码 |
| 6 | 协同过滤推荐 | 只做标签匹配 |
| 7 | 多目的地串行行程 | 一次只支持一个目的地 |
| 8 | 行程导出为图片/PDF | 未做 |

### 19.3 数据与策略层面的客观局限

1. **县域 POI 覆盖不足**：地图服务对县级区域小众景点覆盖有限；有降级路径（扩半径/放宽标签），但行程丰富度受限。实测 `寿阳+古建筑` 返回 0 条、`大同+古建筑` 返回 14 条 —— **做演示要挑城市**。
2. **门票与评分为缺失字段**：两类大模型都无法提供，地图接口也常不返回，页面显示"未知"或用户手填，**不伪造**。
3. **估算模式误差未标定**：地图关闭时距离为模糊估算，无实测误差范围（已通过强制模糊表述缓解）。
4. **公交路线数据依赖区域**：县域 transit 常无数据，自动降级为自驾估算。
5. **百度 `tag` 参数填错不报错、只静默返回城市级垃圾**（实测 `tag=风景名胜` 时结果从真实 POI 变成「广州市/邵阳市」）—— **这比报错危险得多**，所以本项目只用 `query`。
6. **百度地点检索免费额度 100 次/天、并发 3 QPS**（`status=401` 是并发超限，不是日额度用完）。

### 19.4 扩展方向

1. 生成结果缓存与相似目的地复用（进一步降本）
2. 行程点赞/收藏的社交化沉淀
3. 多人协同编辑同一行程
4. 地图 Provider 扩展（高德等，接口已抽象，加实现即可）
5. 用户对估算距离的纠错回流（众包校准 `poi_cache`）
6. 接入前端地图 JS API，把有坐标的行程真正画出来
7. 行程导出为图片/PDF 分享

---

## 附录 A：命名规范

| 项 | 命名 |
|---|---|
| 英文代号 / 中文名 | Wayfare / 行走集 |
| 仓库 / 目录 | `Wayfare` / `wayfare-backend` / `wayfare-frontend` |
| Java 包名 | `com.wayfare`（子包 `trip` / `connector` / `profile` / `security` / `service`） |
| Maven artifactId | `wayfare-backend`（version 1.0.0） |
| npm name | `wayfare-frontend`（version 1.0.0） |
| 数据库名 | `wayfare` |
| 建表脚本 | `db/schema.sql`（内容域 14 张）+ `db/schema-trip.sql`（行程与治理域 8 张） |
| SSE 线程名前缀 | `wayfare-trip-sse-` |
| 地图 provider 名 | `baidu` / `cache` / `disabled` |
| 哨兵规则 | **本表之外的任何命名，动手前必须先确认** |

**命名纪律**：验收时须通过下面的检查。

```bash
# 口径 A（真正要保证的）：运行时代码与配置零残留
grep -rn "photoshare\|photo-share" \
  wayfare-backend/src wayfare-frontend/src db \
  wayfare-backend/pom.xml wayfare-frontend/package.json wayfare-frontend/vite.config.js
# → 无输出 = 通过
```

> ⚠️ **不要把「全仓 grep 为 0」当作验收标准** —— 它永远不可能为 0，因为有三类文件必然会命中：
> ① `docs/refs/` 里**有意留档**的旧项目原件；② `scripts/Init-Wayfare.ps1`（迁移脚本，职责就是替换旧名）；
> ③ 引用这条纪律本身或记录迁移决策表的文档（本行就是其中一个）。
> 逐文件的命中清单与说明见 [`交付说明.md` §6.1](交付说明.md#61-检查-4-需要特别说明grep-photoshare-不是全仓为-0)。

---

## 附录 B：术语表

| 术语 | 含义 |
|---|---|
| **VERIFIED** | 数据来自地图 API 实时调用 |
| **CACHED** | 数据来自本地缓存（`poi_cache` / Redis） |
| **ESTIMATED** | 未经地图校验；**禁止精确数值** |
| **USER** | 用户手动修改，最高优先级 |
| **候选池闭包** | 编排阶段只能从步骤 2 检索出的候选中选点，杜绝模型编造景点 |
| **三层开关** | L1 yml（启动）→ L2 sys_config（运行时不重启）→ L3 熔断（自动降级） |
| **降级出口** | SSE 中 `itinerary` 事件先于文案推送：文案失败时行程骨架仍可用 |
| **画像注入** | 用户旅行偏好经 `ProfileRenderer` 渲染为结构化文本块注入 system prompt |
| **needConfirm** | 意图解析中模型主动标注"靠猜测填写"的字段，前端渲染确认表单 |
| **厂商 fallback** | Qwen / GLM / DeepSeek / Mock 多级降级，配置改动不重启生效 |
| **CLOSURE** | 校验规则，检查所有点位在候选池内 —— 防幻觉的核心闸门 |
| **熔断前缀** | `llm` / `map`，决定读哪一套 `{prefix}.breaker.*` 阈值与快照 |
| **`FALLBACK` 状态** | `stage` 事件的一种 status：该阶段降级完成（**不是 `error`**） |

---

*本文档配套《Wayfare-重构提示词.md》（32 个任务块实施手册）使用。*
*代码变更后必须同步更新对应章节；文档中不得出现代码里不存在的能力。*
