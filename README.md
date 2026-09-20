# Wayfare · 行走集

> AI 旅游攻略平台 —— 输入目的地与天数，AI 帮你排出可执行的行程，再把它变成一篇能分享的图文攻略。

`Spring Boot 3.3.5` `JDK 17` `MyBatis-Plus 3.5.7` `MySQL 8.0` `Redis 7` `Vue 3.4` `Vite 5` `Element Plus 2.6`

---

## 这是什么

大多数"AI 旅游规划"演示的流程是：把问题扔给大模型，把返回的文本渲染成卡片。看起来很好，但它有两个致命伤——**行程里的距离和时长是模型编的**，以及**服务一挂整个功能就消失**。

Wayfare 把这两个问题当成架构问题来解决：

- **大模型只做语义决策**（分天、选点、写讲解），**坐标、距离、时长只来自地图 API**，拿不到就明确标记 `ESTIMATED`——绝不让模型编一个"距离 3.2 公里"。
- **连接器可插拔，任一时刻系统完整可用**：地图被管理员整体关闭后，全流程自动切换到估算模式继续跑，界面上只是角标从绿（实测）变成橙（估算）——这是**能力降级，不是功能降级**。
- **大模型可热切换**：智谱 GLM 与 DeepSeek 双厂商走同一套 OpenAI 兼容协议，改一条数据库配置即完成切换，无需重启；全部不可用时回落离线 Mock，链路照样演示。

一句话概括设计哲学：**模型只是强大的引擎，变速箱和离合器得自己调——这个项目调的就是那套变速箱。**

## 核心设计

### 1. 七步行程编排管线

AI 生成不是一次 prompt，而是一条有质检的流水线：

```
意图解析 → 候选检索 → 空间预排 → 行程编排 → 约束校验 → 事实补全 → 结果组装
  (LLM)     (地图/缓存)   (本地算法)     (LLM 主战场)   (本地规则)    (地图回填)     (指标输出)
```

- **空间预排**用本地地理聚类算法先排一遍顺序，避免"上午在城东、下午跑城西"这种外行行程——LLM 拿到的是已经按地理位置排好的候选序列，只做语义层面的取舍。
- **约束校验**是本地的六条规则（天数超限、点位重复、闭园时间冲突等），对 LLM 输出做质检，不合格打回重排——**最多两轮，绝不死循环**。
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

所有外部 HTTP 调用（GLM / DeepSeek / 百度地图）走同一个治理封装：

- **重试**：仅 GET 重试（指数退避），POST 一律不重试——重复调用可能产生费用；4xx 除 429 外不重试——客户端错误重试无意义。
- **日志**：每次尝试落一条 `external_call_log`（连接器、接口、耗时、成败、脱敏后的请求摘要），是成本统计与监控看板的数据源。
- **脱敏**：AK / API Key / Bearer 凭证在写日志前统一过 `MaskUtil`，库里不允许出现完整密钥。
- **缓存**：POI 检索 24h、详情 7d、路线 12h（Redis），**大模型调用一律不缓存**——同一个输入需要可复现的新结果。

### 4. 鉴权与安全

- 自建 `JwtInterceptor`（不引 Spring Security），无 token / token 无效 / 已登出（Redis 黑名单）统一 401。
- 管理接口与公开接口的边界由白名单显式定义，公开路径用正则约束（`/works/{id:[0-9]+}`）避免把 `/works/my` 一并放行。
- 密钥全部走环境变量或 gitignore 的本地配置文件，仓库内不含任何真实 Key；所有回显一律掩码（前 4 位 + `****`）。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 3.3.5 / JDK 17 / MyBatis-Plus 3.5.7 | 纯 Spring MVC，不引 WebFlux |
| 鉴权 | jjwt 0.12.6 + 自建拦截器 | 刻意不引 Spring Security 全家桶 |
| 数据 | MySQL 8.0 / Redis 7 | 17 张表（内容域 + 行程域） |
| 大模型 | 智谱 GLM / DeepSeek / Mock | OpenAI 兼容协议，公共逻辑收敛在抽象基类 |
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
#   编辑 .env.properties，填入 MYSQL_ROOT_PASSWORD 与 GLM_API_KEY

# 4. 启动后端（8080，context-path /api）
mvn spring-boot:run
#    启动日志会自检：LLM 厂商配置状态 / 地图开关状态，一眼可见

# 5. 启动前端（5173，已代理 /api 与 /uploads）
cd ../wayfare-frontend
npm install
npm run dev
```

> 没有任何大模型 Key？不需要申请也能跑：双厂商都未配置时自动回落离线 Mock，
> 全链路（含前端流式渲染）照常可演示，只是内容由 Mock 生成。

- 健康检查：`GET http://localhost:8080/api/health`
- 接口文档：`http://localhost:8080/api/swagger-ui/index.html`
- 连接器诊断：`GET /api/diagnostics/connectors`（管理员）

## 目录结构

```
Wayfare/
├── db/
│   ├── schema.sql          内容域 14 张表（用户/作品/评论/社交…）
│   └── schema-trip.sql     行程域与治理域 8 张表（运行时开关/POI 缓存/调用日志/
│                           偏好画像/行程三表/AI 生成日志）
├── wayfare-backend/
│   └── src/main/java/com/wayfare/
│       ├── common/         统一返回、全局异常、配置、脱敏工具
│       ├── connector/
│       │   ├── llm/        大模型连接器（glm/deepseek/mock + 决策器）
│       │   ├── map/        地图连接器（baidu/cache/disabled + 三级降级）
│       │   └── governance/ 出站治理（重试/熔断/日志/脱敏）
│       ├── controller/     REST 接口（内容域 + 社交域 + 偏好 + 诊断）
│       ├── entity|mapper/  22 张表的实体与 Mapper
│       ├── profile/        用户画像渲染器（渲染成注入 prompt 的中文文本块）
│       ├── trip/           行程域：错误码枚举、阶段记录（编排管线 P3 落地于此）
│       └── security/       JWT 拦截器、Token 黑名单、用户上下文
└── wayfare-frontend/
    └── src/{api,stores,router,layouts,components,views}/
```

## 工程实践

- **测试**：53 个单元 / 集成测试全绿，覆盖脱敏规则（6）、出站重试与降级策略（9）、
  用户画像渲染规则（11）、画像读写与 upsert 的 null 语义（7）、行程表族排序与数据诚信字段（5）、
  AI 日志聚合与成本计算（11）、实体与表结构映射（4）。
- **AI 辅助开发的工程化**：本项目使用"任务块"方式驱动 AI 编码——每个任务块有独立的目标、交付物与验收标准，AI 读完复述确认后才动手，跑通一块再投喂下一块。全套实施手册与设计文档暂未开源，需要的可以通过 issue 联系我。
- **命名纪律**：仓库内不允许出现旧项目残留（`photoshare` / `photo-share`），验收时以 grep 结果为零为准。

## 开发进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 仓库骨架 / 内容域 / 鉴权 / 前台页面 | ✅ 已完成 |
| P1 | 双厂商大模型 / 地图连接器 / 出站治理 / 诊断接口 | ✅ 已完成 |
| P2 | 偏好画像 / 行程表族 / 日志缓存 | ✅ 已完成 |
| P3 | 七步 AI 行程编排管线（核心） | ⏳ 规划中 |
| P4 | SSE 流式输出 / 成本控制 | ⏳ 规划中 |
| P5 | 前端偏好中心 / AI 规划交互 | ⏳ 规划中 |
| P6-P8 | 后台管理 / 测试指标 / 部署 | ⏳ 规划中 |

---

*行走集（Wayfare）——把每一次出发，都变成可以分享的路线。*
