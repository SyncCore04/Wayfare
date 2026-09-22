# Wayfare · AI 旅游攻略平台 · 开发文档

> 项目代号：**Wayfare** ｜ 中文名：行走集 ｜ 包名：`com.wayfare`
> 版本：v2.0 ｜ 日期：2026-09-19
> 配套手册：`Wayfare-重构提示词.md`（32 个任务块，P0–P8）
> 前身参考：光影集摄影作品分享平台（内容与社交底座的设计来源）
> 维护纪律：**本文档不允许描述代码里不存在的能力。** 代码变更后必须同步更新对应章节。

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

| 层级 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 后端框架 | Spring Boot | 3.3.5 | 单体 MVC，不使用 WebFlux |
| ORM | MyBatis-Plus | 3.5.7 | 逻辑删除、自动填充 |
| 数据库 | MySQL | 8.0 | 22 张业务表 |
| 缓存 | Redis | 7.x | 计数、配置缓存、路线缓存、熔断计数 |
| 鉴权 | jjwt | 0.12.6 | 自定义拦截器 + `Authorization: Bearer` |
| 密码 | spring-security-crypto | - | 仅用 BCrypt，不引入完整 Security |
| 流式输出 | SseEmitter | - | Spring MVC 原生，独立线程池 |
| 图片处理 | Java ImageIO | - | 文字水印 |
| API 文档 | springdoc-openapi | - | `/swagger-ui.html` |
| 前端框架 | Vue | 3.4 | JS，非 TS |
| 构建 | Vite | 5.x | 含 `/api` 代理 |
| UI | Element Plus | 2.6 | - |
| 状态/路由 | Pinia / Vue Router | 2.1 / 4.3 | - |
| HTTP | Axios + fetch | - | fetch 用于 SSE 流式读取 |
| 图表 | ECharts | - | 仅后台监控看板使用 |
| **大模型 A（主力）** | **阿里云百炼 Qwen** | OpenAI 兼容 | `qwen3.8-flash`（**模型名大小写敏感**，必须全小写） |
| **大模型 B** | **智谱 GLM** | v4 | `glm-4-flash` / `glm-4.5-flash` / `glm-4-plus` / `glm-4-air` |
| **大模型 C** | **DeepSeek** | - | `deepseek-chat` / `deepseek-reasoner` |
| 地图服务端 | 百度地图 Web 服务 API | v2 | POI 检索 / 详情 / 路线规划 |
| 地图前端 | 百度地图 JS API | - | 打点与路线可视化 |

**依赖纪律**：不引入 WebFlux、新 ORM、新状态管理库、Element Plus 之外的 UI 库。新依赖必须先说明用途并确认。

---

## 三、系统总体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                              浏览器                                    │
│   Vue3 + Element Plus + Pinia                                         │
│   用户端：首页 / 攻略详情 / AI规划(SSE) / 个人中心+旅行偏好 / 私信        │
│   管理端：内容·用户·分类 / 连接器管理 / AI监控 / 系统配置                │
│   （百度地图 JS API：行程打点与连线，仅作可视化展示）                    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ HTTP + SSE（fetch 流式，携带 JWT）
┌──────────────────────────────────▼───────────────────────────────────┐
│                       Spring Boot 3.3.5（/api）                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ 接入层   JwtInterceptor · 全局异常 · Result 统一返回 · 限流        │ │
│  ├────────────────────────────────────────────────────────────────┤ │
│  │ 业务层   内容域(work) · 社交域 · 画像域(profile) · 行程域(trip)     │ │
│  │          TripOrchestrator（7 步管线）· 后台管理                    │ │
│  ├────────────────────────────────────────────────────────────────┤ │
│  │ 连接器层（可插拔 · 三层开关 · 本系统核心）                          │ │
│  │   LlmProvider ─ QwenProvider / GlmProvider / DeepSeekProvider  │ │
│  │                 / MockLlmProvider（公共逻辑在同一抽象基类）      │ │
│  │   MapProvider ─ BaiduMapProvider / LocalCacheMapProvider         │ │
│  │                 / DisabledMapProvider                            │ │
│  │   LlmCapabilityResolver / MapCapabilityResolver（决策 + 降级）    │ │
│  │   Breaker（Redis 计数熔断）                                       │ │
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
   三家大模型互为降级（qwen → glm → deepseek）；地图可整体关闭，关闭后进入降级模式
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
⑤约束校验(本地六规则：闭包/时序/折返/通勤/预算/忌口/密度)
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
    String name();                                   // glm / deepseek / mock
    String chat(String systemPrompt, String userPrompt);
    String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint);
    void chatStream(String systemPrompt, String userPrompt,
                    Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError);
    LlmInfo info();                                  // provider / model / available
}
```

#### 4.1.1 双厂商实现

| 实现 | Base URL | 模型 | 关键差异点 |
|---|---|---|---|
| `GlmProvider` | `https://open.bigmodel.cn/api/paas/v4` | glm-4-plus / glm-4-flash / glm-4-air | JSON 模式需同时加 `response_format` **与** prompt 中出现 "json" 字样；流式末块可能只有 usage 无内容，需容忍 `content` 为 null |
| `DeepSeekProvider` | `https://api.deepseek.com/v1` | deepseek-chat / deepseek-reasoner | `deepseek-reasoner` 的 delta 含 `reasoning_content`，**必须忽略**（否则思维链混入正文）；流式需加 `stream_options.include_usage=true` 才有 token 统计 |
| `MockLlmProvider` | - | - | 离线兜底，按 prompt 特征返回合法假 JSON |

**公共逻辑全部下沉到 `AbstractOpenAiCompatibleProvider`**：请求构造、流式 `data:` 行解析、异常映射（401→`LLM_AUTH_FAIL`、429→`LLM_RATE_LIMIT`、超时→`LLM_TIMEOUT`、5xx→`LLM_SERVER_ERROR`）、token 上报。两个子类只负责写差异部分。

#### 4.1.2 厂商决策与降级

`LlmCapabilityResolver.resolve()`：

```
读 sys_config.llm.active-provider（Redis 缓存 30s）
  → 该厂商可用？ → 用它
  → 不可用（未配 Key / 熔断打开）？ → 按 llm.fallback-order 依次尝试
  → 全不可用？ → 回落 MockLlmProvider（reason 明确记录）
返回 ResolvedLlm { provider, model, isFallback, reason }
```

**厂商热切换**：改动 `sys_config.llm.active-provider` 后**不重启即生效**。

### 4.2 地图连接器

```java
public interface MapProvider {
    boolean isAvailable();
    String name();
    String unavailableReason();
    List<PoiDTO> searchPoi(PoiQueryDTO q);
    PoiDTO detail(String poiUid);
    RouteDTO route(RouteQueryDTO q);   // driving / walking / riding / transit
}
```

| 实现 | 行为 | 降级顺位 |
|---|---|---|
| `BaiduMapProvider` | 真实调用百度 Web 服务 API。AK 取自 `sys_config` → yml → 空（不可用）。**容忍字段缺失：POI 检索接口通常不返回评分与门票，取不到保持 `null` 标记"未知"，绝不填 0 冒充** | 1 |
| `LocalCacheMapProvider` | 只查 `poi_cache` 表与 Redis 路线缓存，零网络请求 | 2 |
| `DisabledMapProvider` | 短路，`isAvailable()=false`，返回空集合 + 明确中文 reason | 3 |

### 4.3 三层开关

| 层级 | 载体 | 生效方式 | 操作者 |
|---|---|---|---|
| **L1 启动层** | `application.yml` + `LlmProperties` / `MapProperties` | 启动时 | 开发者 |
| **L2 运行层** | `sys_config` 表 + Redis 缓存 30s | **改完不重启即生效** | 管理员（后台面板） |
| **L3 降级层** | 熔断器 + 分级 Provider + 厂商 fallback | 自动 | 系统 |

读取优先级：**L2 覆盖 L1，L2 未配置时回落 L1**。

### 4.4 熔断器

不引 Resilience4j，用 Redis 计数实现：

```
连续失败 ≥ map.breaker.fail-threshold（默认 5）
  → 打开熔断 map.breaker.open-seconds（默认 300 秒）
  → 期间直接走降级，不发起请求
  → 到期后半开放行一次：成功则清零关闭，失败则重新计时
```

状态可被 `/api/diagnostics/connectors` 读取。

### 4.5 统一出站治理 `ExternalHttpClient`

所有外部 HTTP 必须经此封装：

- **超时**：地图 连接 3s / 读 8s；大模型非流式 90s；流式不设读超时
- **重试**：**仅 GET** 失败重试 2 次，指数退避 200ms / 600ms；**POST 一律不自动重试**（避免重复计费与副作用）；跳过 4xx（429 除外）
- **缓存**：见[第九章](#九redis-设计)；**大模型调用一律不缓存**
- **日志**：每次调用写 `external_call_log`，`request_summary` 截断 500 字符并脱敏
- **脱敏**：`MaskUtil.maskSecret()` 对 ak / api_key / Authorization 只保留前 4 位 + `****`

---

## 五、AI 行程编排管线（7 步）

由 `TripOrchestrator` 串联，每步前后写 `ai_generation_log` 并累计耗时。

| 步骤 | 名称 | 执行者 | 关键规则 | 失败行为 |
|---|---|---|---|---|
| 1 | 意图解析 `parseIntent` | **LLM**（chatJson） | 输出 IntentDTO；服务端 Schema 校验（类型/枚举/必填）；**必须输出 `needConfirm[]` 标注靠猜测填的字段**（如预算口径未说明、同名地名消歧） | 校验失败重试 1 次（错误回喂），仍失败抛 `SCHEMA_INVALID`，**不静默兜底** |
| 2 | 候选检索 `searchCandidates` | 地图连接器 / LLM | 偏好→百度 tag 映射字典（`resources/map-preference-tag.json`）；景点 + 餐饮两路；按 uid 与名称相似度双重去重；剔除含忌口的餐饮点；限量 `trip.max-candidate`。**地图关闭时改由 LLM 生成候选：只填名称/区域/建议停留/亮点，`lat/lng` 强制留空**，标记 `dataSource=LLM` | 景点候选 <6 → 扩半径 → 放宽标签 → 仍不足返回 `shortage=true` + `shortageHint`，**绝不编造景点** |
| 3 | 空间预排 `preOrder` | **本地算法，零外部调用** | 起点=目的地中心；贪心最近邻 + 2-opt 局部优化（迭代上限 200 次 / 连续 20 次无改进停止）；优化后**总里程必须 ≤ 优化前**；无坐标候选保持原序放尾部 | 不可失败，但设迭代上限防卡死 |
| 4 | 行程编排 `composeItinerary` | **LLM**（chatJson） | 分天、时段（活动 09:00–18:00、午餐 11:30–13:00、晚餐 18:00 后）；每 item 必写 `reason`；**只能从候选池选点**；节奏约束（慢 2–3 / 适中 3–4 / 紧凑 4–5 点）；每天至少 1 个 FOOD；画像硬约束注入 | 解析失败重试 1 次；仍失败返回 `LLM_PARSE_FAIL` 但**保留候选池**供用户手选 |
| 5 | 约束校验 `validate` | **本地六规则** | 见 [5.1](#51-六条本地校验规则) | 不通过 → 带 violations 回喂重排，最多 `trip.max-replan-rounds`（默认 2）轮；达上限返回当前最优 + 风险提示，**绝不死循环** |
| 6 | 事实补全 `enrichRoutes` | 地图连接器 / LLM | VERIFIED：逐段调路线规划（**并发≤4**），县域 transit 无数据自动降级 driving 并标注；回填后**重跑规则 3) 与 5)**，违反则回步骤 3（计一轮）。CACHED：命中记 VERIFIED，未命中记 ESTIMATED。ESTIMATED：**完全不调地图，距离时长保持 `null`**，只把模糊表述写入 `note` | 部分段失败只标该段 ESTIMATED，不整体失败 |
| 7 | 结果组装 | 本地 | 批量落库 `trip` → `trip_day` → `trip_item`；回写 `generationRounds` / `mapMode` / `modelName` / `profileUsed` | - |

### 5.1 六条本地校验规则

| 规则 | 校验内容 | 严重度 |
|---|---|---|
| **CLOSURE** | 每个 item 的 `poiRef` 必须在候选池中找到（容忍空格与全半角差异）。**唯一使命：识破大模型编造的景点** | HIGH |
| TIME_OVERLAP | 同天内 `startTime` 严格递增、不重叠、不超窗口（晚餐豁免） | MEDIUM |
| BACKTRACK | 有坐标时单段距离超阈值：DRIVE 40km / WALK 5km / RIDING 15km / PUBLIC 40km。**无坐标则跳过** | HIGH |
| DETOUR | 单日累计通勤：DRIVE > 120min 或 WALK > 40min | MEDIUM |
| BUDGET_EXCEED | 费用之和 vs 预算（按口径换算）：超支 ≤10% LOW，>10% MEDIUM | LOW/MEDIUM |
| **TABOO** | 任何 item 的 `poiName` / `reason` / `note` 命中忌口食材。**硬约束，命中即必须重排** | HIGH |
| TOO_DENSE | 按坐标估算当日步行里程 vs `profile.walkLimitKm` | MEDIUM |

**回喂机制**：构造纠正 prompt（逐条列出 `message` + `suggestion`，用分隔符包裹避免格式错乱），最多 2 轮。达上限**返回当前最优版本 + violations 风险提示**，由前端展示"以下问题未能自动解决"。

### 5.2 事实边界（铁律一的落地）

| 数据 | 允许来源 | 地图关闭时 |
|---|---|---|
| 经纬度、POI uid、地址 | 地图连接器 | **留空**，不填假值 |
| 距离、时长 | 地图连接器 | 标记 `ESTIMATED`，**禁止精确数值**（`distanceMeters` 保持 null） |
| 门票价格、评分 | 地图详情（可能为 `null` → 显示"未知"）或用户手填 | 同左 |
| 景点名称 | 候选池闭包（CLOSURE 强校验） | 同左 |
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

**门槛**：仅当请求 `useProfile=true` **且** `profile.allowAiUse=1` 时才注入。否则 `profileUsed=false`，且**画像不进任何 prompt**。

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
- 忌口为空时输出「忌口过敏：无」并注明「这点可以更自由地推荐餐饮」
- `overrides.taboos` 与画像 taboos **合并**（去重），不是覆盖
- `overrides.pace` **覆盖**画像 pace
- 全空时 `render` 返回空字符串，`hasAnyContent` 返回 false

**UI 可见性**：规划页显示可取消的偏好 chips，结果页显示「本次已参考你的偏好：古建探访 / 慢节奏 / 忌香菜」；隐私开关关闭时显示「未使用偏好」。

**硬约束落地**：忌口走校验规则 TABOO，命中即判 HIGH 并重排 —— 不依赖模型"记得"。

---

## 七、流式输出（SSE）

### 7.1 事件协议

| 事件 | data | 说明 |
|---|---|---|
| `stage` | `{stage, status, message}` | stage ∈ PARSE/CANDIDATE/PREORDER/COMPOSE/VALIDATE/ROUTE；status ∈ RUNNING/DONE/**FALLBACK** |
| **`itinerary`** | 完整 TripDraftDTO | **步骤 6 完成后立即推送。此时前端已可渲染地图、时间轴、费用表。这是降级出口：之后文案失败不影响行程可用** |
| `delta` | `{text}` | 攻略文案增量 |
| `done` | `{tripId, rounds, mapMode, durationMs, tokens, estCost}` | 结束 |
| `error` | `{code, message}` | 发完 error 仍 complete，已生成内容不丢弃 |

事件格式：`event: xxx\ndata: {...}\n\n`（两换行结尾）。

### 7.2 技术约束

- Spring MVC `SseEmitter`，**禁止引入 WebFlux**
- **必须异步**：独立线程池（core 4 / max 8 / queue 50，前缀 `wayfare-trip-sse-`），不占 Tomcat 请求线程
- emitter timeout 5 分钟；`onTimeout`/`onCompletion`/`onError` 正确清理
- **客户端断开必须可感知**：捕获 IOException → **立即中断 LLM 流** → 写 `success=0` + `error_code=CLIENT_DISCONNECTED` 日志（含节省 token 估算）
- 鉴权不变：仍走 `Authorization` 头
- **前端不用 `EventSource`**（不能自定义请求头、无法携带 JWT），用 `fetch` + `response.body.getReader()` 手动按 `\n\n` 切分事件块

### 7.3 文案生成

- 通过 `chatStream` 流式输出，按天组织，每天 150–250 字，总长 ≤800 字
- 必须体现画像（忌口/预算档/节奏至少一处）
- 地图关闭时禁止输出精确距离与时间，用"顺路""不远"这类表达
- 生成成功后写回 `trip.guide_text`，重复访问不重新生成
- 独立重试接口 `POST /api/trip/{id}/regenerate-copy`：**只重跑文案，不重跑全量管线**，省 token

---

## 八、数据库设计

共 **22 张表** = 内容域与用户域 14 张 + 行程域与治理域 8 张。
字符集 `utf8mb4`，主键 `BIGINT UNSIGNED AUTO_INCREMENT`，**无物理外键**，逻辑删除字段 `deleted`。

分两个脚本：`schema.sql`（内容域）+ `schema-trip.sql`（行程与治理域），便于分阶段执行。

### 8.1 内容域与用户域（14 张，`schema.sql`）

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
| `user_third_account` | 第三方登录 | **预留，建表不写代码** |
| `report` | 举报 | **预留，建表不写代码** |
| `admin_operation_log` | 操作日志 | **本版真实写入**（记录管理员敏感操作） |

初始数据：管理员账号 + 8 个分类（古建探访 / 自然风光 / 博物馆 / 市井烟火 / 美食之旅 / 亲子出行 / 摄影旅拍 / 城市漫步）。

### 8.2 行程域与治理域（8 张，`schema-trip.sql`）

#### `sys_config` 运行时开关

`id / config_key(UNIQUE) / config_value / value_type(STRING|INT|BOOL|JSON) / group_name / description / updated_by / updated_at`

| key | 默认值 | 说明 |
|---|---|---|
| `llm.enabled` | true | - |
| `llm.active-provider` | **glm** | 当前厂商：glm / deepseek / mock |
| `llm.fallback-order` | glm,deepseek | 降级顺序 |
| `llm.timeout-ms` | 90000 | - |
| `llm.price.glm` | 按厂商实际填 | 元/百万 token |
| `llm.price.deepseek` | 按厂商实际填 | 元/百万 token |
| `map.enabled` | **false** | 地图总开关（默认关闭，无 AK 也能全功能开发） |
| `map.baidu.ak` | '' | 运行时 AK，优先于 yml |
| `map.breaker.fail-threshold` | 5 | 熔断阈值 |
| `map.breaker.open-seconds` | 300 | 熔断时长 |
| `trip.max-days` | 5 | 行程天数上限 |
| `trip.max-candidate` | 20 | 候选点上限 |
| `trip.max-replan-rounds` | 2 | 最大重排轮次 |
| `trip.poi-cache-ttl-hours` | 24 | POI 缓存时长 |

#### `user_travel_profile` 用户旅行偏好

`id / user_id(UNIQUE) / cuisines / flavors / taboos / travel_styles / pace / budget_level / companions / walk_limit_km / hotel_pref / notes / allow_ai_use / created_at / updated_at`

#### `trip` 行程主表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `user_id` | - | - |
| `title` | VARCHAR(100) | 用户可改，AI 生成默认值 |
| `raw_input` | TEXT | 用户原始输入 |
| `intent_json` | JSON | 意图解析结果 |
| `destination` | VARCHAR(50) | 目的地 |
| `dest_lng` / `dest_lat` | DECIMAL | 中心坐标，**地图关闭时为 NULL（正常状态）** |
| `days` / `start_date` | INT / DATE | - |
| `budget_total` / `budget_mode` | DECIMAL(10,2) / TINYINT | 口径 1 人均 2 总计 |
| `transport` / `companion` | VARCHAR | - |
| `map_mode` | VARCHAR(10) | **VERIFIED / CACHED / ESTIMATED** |
| `profile_used` | TINYINT | 本次是否注入画像 |
| `guide_text` | TEXT | 攻略文案（P4-B 写回） |
| `status` | TINYINT | 0 草稿 / 1 已生成 / 2 已编辑 / 3 已发布 |
| `work_id` | BIGINT | 发布后关联 `work.id` |
| `model_name` | VARCHAR(50) | 本次模型（glm-4-flash / deepseek-chat…） |
| `generation_rounds` | INT | 实际重排轮次 |

索引：`idx_user_id`、`idx_status_created`。

#### `trip_day` 行程日

`id / trip_id / day_index（从1起） / title（当天主题） / summary / created_at`
索引：`(trip_id, day_index)`

#### `trip_item` 行程条目（核心表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` / `trip_id` / `day_index` / `seq` | - | 当天顺序 |
| `item_type` | VARCHAR(10) | SCENIC / FOOD / HOTEL / TRANSPORT / REST |
| `poi_uid` | VARCHAR(64) | **地图关闭时为空** |
| `poi_name` / `address` | VARCHAR | - |
| `lng` / `lat` | DECIMAL | **可空** |
| `arrive_time` / `leave_time` | TIME | - |
| `stay_minutes` | INT | - |
| `ticket_price` | DECIMAL(10,2) | 可为 NULL（地图未提供，表示"未知"） |
| `cost_estimate` | DECIMAL(10,2) | 费用估算 |
| `transport_mode_to_next` | VARCHAR(10) | - |
| `distance_meters` / `duration_seconds` | INT | **估算模式下为 NULL** |
| **`verify_status`** | VARCHAR(10) | **VERIFIED / CACHED / ESTIMATED / USER** |
| **`data_source`** | VARCHAR(10) | **BAIDU / LLM / USER** |
| `reason` | VARCHAR(500) | AI 给出的安排理由 |
| `note` | VARCHAR(500) | 备注（估算模式下放模糊表述） |

索引：`(trip_id, day_index, seq)`

#### `poi_cache` POI 本地缓存

`id / provider / city / keyword / poi_uid(UNIQUE) / name / address / lng / lat / tag / shop_hours / rating(可NULL) / ticket_price(可NULL) / raw_json / fetched_at / expires_at`
索引：`(city, keyword)`、`(poi_uid)`
用途：`BaiduMapProvider` 成功检索后异步回写；`LocalCacheMapProvider` 只读它。

#### `ai_generation_log` AI 生成日志

`id / user_id / trip_id / stage / provider / model / prompt_tokens / completion_tokens / total_tokens / duration_ms / success / error_code / error_msg / created_at`
索引：`(user_id, created_at)`、`(stage, success)`、`(trip_id)`

#### `external_call_log` 外部调用日志

`id / user_id / trip_id / connector(LLM|BAIDU_MAP) / api_name / request_summary(已脱敏) / http_status / duration_ms / success / error_msg / created_at`
索引：`(connector, created_at)`、`(success)`

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

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `sys:config` | Hash | 30s | 运行时配置缓存（保证改完不重启生效） |
| `map:poi:{provider}:{city}:{keyword}:{pageNum}` | String(JSON) | 24h | POI 检索缓存 |
| `map:route:{mode}:{fromLng},{fromLat}:{toLng},{toLat}` | String(JSON) | 12h | 路线缓存 |
| `map:detail:{poiUid}` | String(JSON) | 7d | POI 详情缓存 |
| `map:breaker:fail:{provider}` | String | 5min | 熔断失败计数 |
| `map:breaker:open:{provider}` | String | 300s | 熔断打开标记 |
| `llm:breaker:*` | String | - | 大模型侧同类熔断（可选） |
| `token:blacklist:{token}` | String | 同 token 有效期 | 登出黑名单 |
| `login:fail:{username}` | String | 15min | 登录失败锁定 |
| `like:count:{targetType}:{targetId}` | String | 永久 | 点赞计数 |
| `favorite:count:{workId}` / `follow:*` | String | 永久 | 收藏 / 关注计数 |
| `browse:history:{userId}` | ZSet | 7d | 浏览历史 |
| `rate:ip:{ip}` | String | 1min | 接口限流计数 |

---

## 十、REST 接口清单

统一前缀 `/api`。权限：【登】需登录，【管】需管理员，【公】公开。

### 10.1 行程与 AI

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/trip/plan/sync` | 【登】 | 同步生成完整行程，body `{rawInput, useProfile, overrides}`；响应含 `meta{rounds, mapMode, modelName, profileUsed, durationMs, tokens, estCost}` |
| POST | `/trip/plan/stream` | 【登】 | SSE 流式生成 |
| POST | `/trip/{id}/replan` | 【登】 | 局部重排，body `{feedback}`；**只重排指定范围** |
| POST | `/trip/{id}/regenerate-copy` | 【登】 | 只重生成文案，不重跑管线 |
| PUT | `/trip/{id}/publish` | 【登】 | 发布为攻略，写回 `work_id` |
| GET | `/trip/{id}` | 【登】 | 详情（本人可见；未发布的非本人返回 403） |
| GET | `/trip/my` | 【登】 | 我的行程（分页） |
| DELETE | `/trip/{id}` | 【登】 | 逻辑删除 |

### 10.2 偏好画像

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/profile/travel` | 【登】 |
| PUT | `/profile/travel` | 【登】 |

### 10.3 诊断与连接器管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/diagnostics/connectors` | 【管】 | 双连接器状态（可用性 / 模式 / 熔断 / 失败率 / 成本） |
| POST | `/diagnostics/llm/ping` | 【管】 | 指定厂商连通性测试 |
| POST | `/diagnostics/map/ping` | 【管】 | POI 检索连通性测试 |
| POST | `/admin/config/map/enabled` | 【管】 | 开关地图，立即生效 |
| POST | `/admin/config/llm` | 【管】 | 切厂商 / 改降级顺序 |
| GET | `/admin/configs` / `PUT /admin/configs/{key}` | 【管】 | sys_config 管理 |
| GET | `/admin/generation/logs` | 【管】 | 生成日志（分页 + 筛选） |
| GET | `/admin/generation/stats` | 【管】 | 聚合统计，见 [14.2](#142-聚合接口) |

### 10.4 内容域与社交域

| 模块 | 路径 | 说明 |
|---|---|---|
| 认证 | `/auth/register` `/auth/login` `/auth/logout` | 后两个公开 |
| 用户 | `/users/me` `/users/{id}` `/users/page` `/users/{id}/status` | 后两个【管】 |
| 分类 | `/categories/tree`【公】 `/categories/page`【管】 `/categories` CRUD【管】 | - |
| 内容 | `/works` CRUD、`/works/page`【公】、`/works/my`、`/works/{id}/status`【管】 | 发布/编辑支持 `imageUrls` 多图数组与 `destination`/`tripDays` |
| 标签 | `/tags` `/tags/page` `/tags/hot` | - |
| 评论 | `/comments` `/comments/work/{workId}` `/comments/{id}/replies` | - |
| 点赞/收藏/关注 | `/likes/*` `/favorites/*` `/follows/*` | toggle 模式 |
| 私信 | `/messages/*` | 会话列表 / 消息 / 未读统计 / 标记已读 |
| 推荐 | `/recommend/feed` `/recommend/hot` `/recommend/personal` `/recommend/similar/{id}` | - |
| 文件 | `/files/upload` `/files/batch` `/files/avatar` | 上传即水印 |
| 健康 | `/health`【公】 | - |

> **鉴权纪律**：管理接口一律走 JwtInterceptor + Controller 内 `isAdmin` 双重校验，**不允许放进排除名单**。

---

## 十一、前端设计

### 11.1 路由与页面

| 路由 | 页面 | 权限 | 说明 |
|---|---|---|---|
| `/` | 首页 | 公开 | 攻略瀑布流（目的地/天数/分类筛选）、推荐、热门；AI 生成的攻略带角标 |
| `/plan` | AI 规划页 | 【登】 | 四步流程，见 11.2 |
| `/work/:id` | 攻略详情 | 公开 | 含「完整行程」时间轴区块 |
| `/publish` `/edit/:id` | 发布/编辑 | 【登】 | 多图 + 目的地 + 天数 |
| `/profile` | 个人中心 | 【登】 | 含「旅行偏好」页签 |
| `/user/:id` `/messages` | 用户主页 / 私信 | 登 | - |
| `/admin/*` | 后台 | 【管】 | 内容管理 + 连接器管理 + AI 监控 + 系统配置 |

### 11.2 AI 规划四步流程（el-steps）

```
Step 1 输入
  大 textarea + 「本次将参考的偏好」chips（可临时取消）+ 本次临时忌口 + 「开始规划」
  画像为空 / 隐私开关关闭时，显示对应引导文案

Step 2 确认参数
  意图解析结果渲染为可编辑表单（目的地/日期/天数/预算含口径切换/交通/同行人/节奏）
  needConfirm 中的字段标橙色「AI 猜测，请确认」
  用户点「确认」才发起真正的生成请求 ← 避免 AI 猜错导致整条流水线白跑

Step 3 生成中
  六阶段进度条按 stage 事件点亮；FALLBACK 状态显示橙色提示
  收到 itinerary 事件立即切到结果视图渲染骨架（不等文案）
  文案区打字机效果；显示「本次已参考你的偏好：…」

Step 4 结果与编辑
  顶部 mapMode 状态条（绿实测 / 蓝缓存 / 橙估算）
  地图打点连线（可用时）；不可用时显示提示条而非空白地图
  时间轴按天 tabs；条目可上移下移/删除/改时长/换点/局部重排
  费用汇总（估算部分显式标注）
  底部：保存草稿 / 发布为攻略 / 重新生成
```

### 11.3 数据来源角标体系

| 角标 | `verify_status` | 含义 |
|---|---|---|
| 绿「实测」 | VERIFIED | 地图 API 实测 |
| 灰「缓存」 | CACHED | 本地缓存命中 |
| 橙「估算」 | ESTIMATED | LLM 估算，未经地图校验 |
| 蓝「手动」 | USER | 用户手改 |

### 11.4 组件复用

- `TripTimeline.vue`：时间轴组件，规划页（可编辑）与详情页（只读）共用
- `sseParser.js`：SSE 流解析工具，独立可测

---

## 十二、配置与密钥管理

### 12.1 环境变量

| 变量 | 用途 |
|---|---|
| `QWEN_API_KEY` | 阿里云百炼 Qwen 密钥（主力厂商） |
| `GLM_API_KEY` | 智谱 GLM 密钥 |
| `DEEPSEEK_API_KEY` | DeepSeek 密钥 |
| `BAIDU_MAP_AK` | 百度地图 Web 服务 AK（服务端） |
| `BAIDU_MAP_JS_AK` | 百度地图 JS API AK（前端，**必须配 Referer 白名单**） |
| `MYSQL_ROOT_PASSWORD` | 数据库密码 |

### 12.2 application.yml 相关段

```yaml
llm:
  enabled: true
  active-provider: glm              # L2 的 sys_config 优先于此
  fallback-order: glm,deepseek
  timeout-ms: 90000
  providers:
    glm:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${GLM_API_KEY:}
      model: glm-4-flash            # 开发用 flash，演示用 plus
      temperature: 0.3
      max-tokens: 4096
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY:}
      model: deepseek-chat
      temperature: 0.3
      max-tokens: 4096
map:
  enabled: false                    # 默认关闭：无 AK 也能全功能开发
  baidu:
    ak: ${BAIDU_MAP_AK:}
    base-url: https://api.map.baidu.com
    connect-timeout-ms: 3000
    read-timeout-ms: 8000
```

### 12.3 密钥安全规范

1. Key 只存在于服务端（环境变量 / `sys_config`），**任何接口、日志、前端代码不得出现明文**
2. 后台回显一律掩码（前 4 位 + `****`），输入框留空表示不修改
3. `external_call_log.request_summary` 强制脱敏
4. `.env` 不入库，只提交 `.env.example`

---

## 十三、降级与容错设计

### 13.1 失败降级矩阵

| 失败点 | 系统行为 | 用户感知 |
|---|---|---|
| 意图解析失败 | 重试 1 次（错误回喂）后抛 `SCHEMA_INVALID` | 提示换个说法，不消耗下游 token |
| 大模型主力不可用 | 按 fallback-order 切下一厂商 | 无感（meta 里记录 fallback） |
| 双厂商都不可用 | 回落 MockLlmProvider | 链路可演示，meta 标记 provider=mock |
| 地图被管理员关闭 | 全流程切 ESTIMATED，stage 发 FALLBACK | 行程照常生成，距离标"估算" |
| 地图连续失败 | 熔断打开 → 查 `poi_cache` → 命中 CACHED / 未命中 ESTIMATED | 同上 |
| 候选不足 | 扩半径 → 放宽标签 → 仍不足带 `shortage` 返回 | "该目的地可玩点位较少，是否扩大范围？" |
| 编排解析失败 | 重试 1 次；仍失败返回错误但**保留候选池** | 用户可手选点位 |
| 校验 2 轮仍不通过 | 返回当前最优 + violations | 行程带风险标注，**绝不死循环** |
| 路线补全部分失败 | 成功段填真实值，失败段标 ESTIMATED | 局部角标变化 |
| 公交无数据（县域） | 自动降级 driving 并在 note 标注 | 备注可见 |
| 文案流式失败 | **itinerary 已推送，行程不丢**；发 error | "文案生成失败，可重试" |
| 客户端断开 | 感知 IOException，中断 LLM 流 | 不再烧 token |
| 地图 AK 无效 | 熔断计数 +1，诊断接口可见原因 | 同"地图连续失败" |

### 13.2 熔断参数与状态

`fail-threshold=5` / `open-seconds=300` / 半开重试一次。状态通过 `/api/diagnostics/connectors` 读取，后台状态卡片展示。

---

## 十四、可观测性与成本核算

### 14.1 日志落点

1. **`ai_generation_log`**：每个 stage 一条（provider / model / tokens / duration / success / error_code）
2. **`external_call_log`**：每次出站 HTTP（connector / api_name / http_status / 耗时 / 脱敏摘要）
3. **诊断接口**：双连接器实时状态
4. **后台看板**：统计卡片 + 四张图表（7 天趋势 / 阶段耗时占比 / mapMode 分布 / 失败原因 Top5）

### 14.2 聚合接口

`GET /api/admin/generation/stats?from=&to=` 返回：

```
totalCount / successCount / successRate
avgDurationMs / p95DurationMs
totalTokens / avgTokensPerTrip / totalEstCost / avgCostPerTrip
stageBreakdown[]:  {stage, avgTokens, avgDurationMs, successRate}
mapModeBreakdown[]: {mapMode, count, avgDurationMs}
topErrors[]:       {code, count}
```

### 14.3 成本计算

```
estCost = promptTokens × priceInput/1e6 + completionTokens × priceOutput/1e6
```

- 单价从 `sys_config` 读（`llm.price.glm` / `llm.price.deepseek`，元/百万 token），**不硬编码**
- 精度保留 **4 位小数**（单次成本是分级数字）
- 中断时的节省估算：记录已产出 tokens，未产出部分按同类请求均值估算，写入 `error_msg`

### 14.4 统一 error_code 枚举

`LLM_TIMEOUT` / `LLM_RATE_LIMIT` / `LLM_AUTH_FAIL` / `LLM_PARSE_FAIL` / `LLM_SERVER_ERROR` /
`MAP_UNAVAILABLE` / `MAP_AUTH_FAIL` / `MAP_BREAKER_OPEN` /
`CANDIDATE_SHORTAGE` / `VALIDATION_FAILED` / `SCHEMA_INVALID` / `CLIENT_DISCONNECTED`

---

## 十五、安全设计

| 项 | 措施 |
|---|---|
| 鉴权 | JWT 拦截器（`Authorization: Bearer`），SSE 同样走头校验，**不因流式放开鉴权** |
| 权限 | 资源归属校验 + 管理接口双重校验；未发布的行程非本人 403 |
| 密钥 | 环境变量注入 + 接口/日志全脱敏 + `.env` 不入库 |
| **防幻觉（核心）** | ① 候选集闭包校验 CLOSURE，识破编造景点 ② 事实字段强制 `verify_status` 标记 ③ 估算模式禁止精确数值 ④ 门票评分缺失时保持 null 而非填 0 |
| 输入校验 | 长度/类型/枚举校验；LLM 输出走 JSON Schema 校验 |
| 成本防护 | 客户端断开立即中断；候选数/天数/重排轮次均有上限；接口限流 |
| 内容安全 | 敏感词过滤（标题/描述/评论），发布时校验 |
| 图片 | 格式白名单 + 大小限制 + 上传即水印 |

---

## 十六、量化指标

所有指标必须来自实测，**禁止编造**。测量方法详见手册 P7-B。

| 指标 | 测量方法 | 用途 |
|---|---|---|
| 2-opt 里程降幅 | 5 组测试数据（6–14 点）对比贪心 vs 2-opt 总里程 | 验证空间预排效果 |
| POI 缓存加速比 | 同一检索冷调用 / 热调用各 20 次取均值与 P95 | 验证多级缓存价值 |
| 校验收敛率 | 30 次生成统计第 1 轮 / 第 2 轮 / 未通过次数 | 验证回喂重排有效性 |
| 流式时间对比 | itinerary 与 done 事件到达时间戳（5 次） | 验证降级出口的价值 |
| 中断节省 tokens | 在文案 20% / 50% / 80% 处各中断 3 次 | 验证成本控制 |
| 单次生成成本 | GLM-4-Flash / GLM-4-Plus / DeepSeek 各 5 次 | 厂商成本对比 |

产出物：`metrics.md`（原始数据表 + 结论 + 测量环境）。

---

## 十七、测试策略

### 17.1 单元测试

| 目标 | 覆盖要点 |
|---|---|
| `PreOrderService` | 不遗漏不重复；2-opt 后里程 ≤ 贪心；无坐标保持原序；单点不报错 |
| `TripValidator` | 六规则各一正一反；**CLOSURE 能识破编造景点**（含全半角/空格容错）；TABOO 命中 poiName 与 reason |
| `IntentSchemaValidator` | 非法 JSON / 缺必填 / 枚举越界 |
| `BudgetCalculator` | 人均与总计两种口径 |
| `ProfileRenderer` | 空字段省略 / overrides 合并与覆盖 / 全空返回空串 |
| `MaskUtil` | 密钥脱敏格式正确 |

### 17.2 集成测试（Mock LLM 与地图）

1. 正常流程（地图可用）→ 全 VERIFIED
2. 地图关闭 → 全 ESTIMATED，接口 200
3. 连续失败触发熔断 → 自动降级，`breakerOpen=true`
4. 候选不足 → 带 `shortage`，**响应中无候选池外景点**
5. 校验 2 轮不通过 → 返回风险提示，**`@Timeout` 断言不死循环**
6. 大模型超时 → 收到 error 事件，已推送 itinerary 仍可取
7. 忌口命中 → 报 TABOO
8. 切厂商 → 下一次调用 provider 立即变化

### 17.3 安全测试

- 断言 `/diagnostics/*` 与 `/admin/*` 响应不含完整 Key（正则匹配）
- 断言 `external_call_log.request_summary` 已脱敏

### 17.4 端到端降级演练

`scripts/drill-fallback.ps1`：关地图 → 生成 → 断言全 ESTIMATED 且距离为 null → 开地图 → 生成 → 断言出现 VERIFIED → 输出对比报告 `report.md`（可直接进答辩 PPT）。

另建议做**故障注入演练**：用 Mock MapProvider 固定抛异常，验证熔断后系统仍全功能可用。

---

## 十八、部署

```
docker-compose
├── mysql:8.0    初始化执行 schema.sql + schema-trip.sql（挂载到 initdb.d）
├── redis:7-alpine
├── backend      多阶段构建（maven build → temurin-17-jre 运行）
│                非 root 用户，HEALTHCHECK 轮询 /api/health
│                三把 Key 通过 .env 注入
└── frontend     node:20-alpine 构建 → nginx:alpine 托管
                 try_files 支持 history 路由；/api 反代 backend:8080
```

- 环境变量通过 `.env` 注入，compose 文件里**不出现明文密钥**
- 提供 `.env.example`（逐项注释）+ `.gitignore`（排除 `.env` / `uploads` / `target` / `node_modules`）
- 首次部署后需在后台完成：配置 Key → 测试连通性 → 按需开启地图

---

## 十九、已知局限与扩展方向

### 已知局限（主动说明，比被问出来好）

1. **县域 POI 覆盖不足**：地图服务对县级区域小众景点覆盖有限；有降级路径（扩半径/放宽标签），但行程丰富度受限。
2. **门票与评分为缺失字段**：两类大模型都无法提供，地图接口也常不返回，页面显示"未知"或用户手填，**不伪造**。
3. **估算模式误差未标定**：地图关闭时距离为 LLM 模糊估算，无实测误差范围（已通过强制模糊表述缓解）。
4. **公交路线数据依赖区域**：县域 transit 常无数据，自动降级为自驾估算。
5. **推荐算法较简单**：标签匹配，未做协同过滤。
6. **举报模块与第三方登录为预留**：表已建，本期无代码。
7. **单目的地**：不支持一次生成多目的地串行行程。

### 扩展方向

1. 生成结果缓存与相似目的地复用（进一步降本）
2. 行程点赞/收藏的社交化沉淀
3. 多人协同编辑同一行程
4. 地图 Provider 扩展（高德等，接口已抽象，加实现即可）
5. 用户对估算距离的纠错回流（众包校准 `poi_cache`）
6. 行程导出为图片/PDF 分享

---

## 附录 A：命名规范

| 项 | 命名 |
|---|---|
| 英文代号 / 中文名 | Wayfare / 行走集 |
| 仓库 / 目录 | `wayfare` / `wayfare-backend` / `wayfare-frontend` |
| Java 包名 | `com.wayfare`（子包 `trip` / `connector` / `profile`） |
| Maven artifactId | `wayfare-backend` |
| npm name | `wayfare-frontend` |
| 数据库名 | `wayfare` |
| 建表脚本 | `schema.sql`（内容域）+ `schema-trip.sql`（行程域） |
| SSE 线程名前缀 | `wayfare-trip-sse-` |
| 哨兵规则 | **本表之外的任何命名，动手前必须先确认** |

**命名纪律**：验收时须通过 `grep -r "photoshare\|photo-share" .` 结果为空。

---

## 附录 B：术语表

| 术语 | 含义 |
|---|---|
| **VERIFIED** | 数据来自地图 API 实时调用 |
| **CACHED** | 数据来自本地缓存（`poi_cache` / Redis） |
| **ESTIMATED** | 数据来自 LLM 估算，未经地图校验；**禁止精确数值** |
| **USER** | 用户手动修改，最高优先级 |
| **候选池闭包** | 编排阶段只能从步骤 2 检索出的候选中选点，杜绝模型编造景点 |
| **三层开关** | L1 yml（启动）→ L2 sys_config（运行时不重启）→ L3 熔断（自动降级） |
| **降级出口** | SSE 中 `itinerary` 事件先于文案推送：文案失败时行程骨架仍可用 |
| **画像注入** | 用户旅行偏好经 `ProfileRenderer` 渲染为结构化文本块注入 system prompt |
| **needConfirm** | 意图解析中模型主动标注"靠猜测填写"的字段，前端渲染确认表单 |
| **厂商 fallback** | Qwen / GLM / DeepSeek / Mock 多级降级，配置改动不重启生效 |
| **CLOSURE** | 校验规则，检查所有点位在候选池内 —— 防幻觉的核心闸门 |

---

*本文档配套《Wayfare-重构提示词.md》（32 个任务块实施手册）使用。*
*代码变更后必须同步更新对应章节；文档中不得出现代码里不存在的能力。*
