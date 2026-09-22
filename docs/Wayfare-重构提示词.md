# Wayfare · AI 旅游攻略平台 · Vibe Coding 实施提示词手册

> 项目代号：**Wayfare** ｜ 中文名：行走集 ｜ 包名：`com.wayfare`
> 来源：由 `毕设/重构提示词-AI旅游攻略平台.md` 适配而来（v1.0 → v2.0）
> 适用：从零开工的独立项目（非原地改造），可整份复制到新仓库根目录
> 日期：2026-09-19
> 用法：**每个阶段单独开一个会话**，按「G 块 → C 块 → Pn 阶段提示词 → 验收指令」投喂

---

## 目录

- [0. 本版与 v1.0 的差异](#0-本版与-v10-的差异)
- [1. 项目代号与命名规范](#1-项目代号与命名规范)
- [2. 前置准备](#2-前置准备)
- [3. 全局约束块（G 块）](#3-全局约束块g-块)
- [4. 上下文交接块（C 块）](#4-上下文交接块c-块)
- [5. 阶段总览](#5-阶段总览)
- [6. 阶段提示词 P0 ~ P8](#6-阶段提示词-p0--p8)
- [7. 三条硬规则](#7-三条硬规则)
- [8. 简历视角：三个可量化亮点的落地要求](#8-简历视角三个可量化亮点的落地要求)
- [9. Agent 崩了怎么办](#9-agent-崩了怎么办)
- [10. 总验收清单](#10-总验收清单)

---

## 0. 本版与 v1.0 的差异

本手册是独立开工版，与 `毕设/` 目录里的 v1.0 有四处结构性差异：

| 维度 | v1.0（毕设目录内改造） | **v2.0（本手册，新项目）** |
|---|---|---|
| P0 的性质 | 修复摄影平台的 4 个真实缺陷 | **P0 不复存在**，改为「新仓库初始化 + 内容域自建」 |
| 项目命名 | 沿用 `photo-share-*` / `com.photoshare` | 全新命名，见[第 1 章](#1-项目代号与命名规范) |
| 大模型接入 | 泛指的 OpenAI 兼容协议 | **明确支持 GLM 与 DeepSeek 双厂商全家桶**，见 [P2](#p2--连接器层数据层与双厂商大模型接入★-地基) |
| 提示词粒度 | 9 个阶段 | **拆成 30+ 个可独立投喂的任务块**，每块都有交付物与验收，防 Agent 崩 |

**为什么要拆得更细**：v1.0 的 P1/P2/P3 单块工作量仍然偏大（P3 要求一次产出 7 步管线）。实测经验是单块超过 4 个文件就会开始丢设定。本版把每阶段的提示词拆成 `A/B/C…` 子任务，一次只投喂一块。

---

## 1. 项目代号与命名规范

### 1.1 命名对照表

| 项 | 命名 | 说明 |
|---|---|---|
| 英文代号 | **Wayfare** | 项目对外名称，README / 文档 / 简历统一用这个 |
| 中文名 | 行走集 | 保留「集」的社区感，与英文名 `Way` 语义呼应 |
| GitHub 仓库 | `wayfare` | - |
| 根目录 | `wayfare/` | - |
| 后端目录 | `wayfare-backend/` | - |
| 前端目录 | `wayfare-frontend/` | - |
| Java 包名 | `com.wayfare` | 子包：`com.wayfare.trip` / `.connector` / `.profile` 等 |
| Maven artifactId | `wayfare-backend` | - |
| npm name | `wayfare-frontend` | - |
| 数据库名 | `wayfare` | - |
| 建表脚本 | `schema.sql`（内容域）+ `schema-trip.sql`（行程域） | 分两个脚本，便于分阶段执行 |
| 前端路由前缀 | 无特殊前缀 | - |
| **本表之外的任何命名，动手前必须先问** | - | - |

### 1.2 命名纪律（写进 G 块）

- 一旦确定包名 `com.wayfare`，**任何阶段都不允许出现 `com.photoshare` / `photo-share` 残留**。
- 阶段结束时必须能通过：`grep -r "photoshare\|photo-share" .` 结果为空。
- 推荐的前端品牌名文案：中文「行走集」，英文「Wayfare」。

---

## 2. 前置准备

### 2.1 账号与密钥清单

| 事项 | 说明 | 拿不到怎么办 |
|---|---|---|
| **智谱 GLM API Key** | 申请地址 `open.bigmodel.cn`，选 `glm-4-plus` 或 `glm-4-flash`（后者便宜）。**用 `glm-4-flash` 做开发调试，`glm-4-plus` 做最终演示** | 拿不到就先用另一个厂商 |
| **DeepSeek API Key** | 申请地址 `platform.deepseek.com`，模型 `deepseek-chat`。便宜、上下文长、中文稳，**适合做行程编排这种需要长输出的场景** | 同上 |
| 百度地图 Web 服务 AK | 服务端 POI 检索与路线规划 | **拿不到不影响开工**，全程走「地图关闭模式」，功能照样完整 |
| 百度地图 JS API AK | 前端打点，需配 Referer 白名单 | 同上，拿不到就前端不显示地图，只显示时间轴 |

### 2.2 预算预估

| 场景 | 消耗 |
|---|---|
| 一次完整行程生成（解析+编排+文案，3 个 LLM 阶段） | 约 8k–15k tokens |
| GLM-4-Flash | 每百万 token 约 0.1 元 → **单次约 0.001–0.002 元** |
| GLM-4-Plus | 每百万 token 约 5 元 → 单次约 0.04–0.08 元 |
| DeepSeek-Chat | 每百万 token 约 1–2 元 → **单次约 0.01–0.03 元** |

> **建议**：开发期两个 Key 都充 20 元。用 GLM-4-Flash 做日常调试（几乎不花钱），DeepSeek 做效果对比，最终演示用 GLM-4-Plus 或 DeepSeek-Chat。这两个厂商的选择本身就是一个简历亮点（见[第 8 章](#8-简历视角三个可量化亮点的落地要求)）。

### 2.3 环境

JDK 17 / Maven 3.6+ / Node 16+ / MySQL 8.0 / Redis 6.0+

---

## 3. 全局约束块（G 块）

> 每开新会话**整块贴给 Agent**。它解决「乱猜版本、乱重写、给半截代码、用旧包名」四类崩溃。

```
【全局约束 · 本会话全程必须遵守】

一、项目身份
- 英文代号 Wayfare，中文名「行走集」
- Java 包名 com.wayfare，后端目录 wayfare-backend，前端 wayfare-frontend，数据库 wayfare
- 本项目为**从零新建的独立仓库**，不存在任何历史代码
- 严禁出现 photoshare / photo-share / 光影集 等旧命名残留

二、技术栈锁定（不升级、不替换、不新增同类库）
- 后端：JDK 17 / Spring Boot 3.3.5 / MyBatis-Plus 3.5.7 / MySQL 8.0 / Redis 7 / Maven
- 鉴权：自建 JwtInterceptor + jjwt 0.12.6，不要用 Spring Security
- 前端：Vue 3.4 + Vite 5 + Element Plus 2.6 + Pinia 2.1 + Axios，JS 不用 TypeScript
- 禁止引入：WebFlux（与 Spring MVC 冲突）、新 ORM、新状态管理库、UI 框架外的组件库
- 需要新依赖：先说明用途与版本，等我确认后再写进 pom.xml / package.json

三、大模型与地图（本项目核心约束）
- 只支持两个大模型厂商：**GLM（智谱）** 与 **DeepSeek**，均走 OpenAI 兼容协议
- 键名固定为 glm / deepseek，任何地方不允许出现硬编码的 baseUrl 或模型名
- 百度地图必须可整体关闭（sys_config 开关），关闭后系统全功能可用
- 【不可违反】坐标、距离、时长只能来自地图 API 或标记为 ESTIMATED；
  大模型永远不允许输出精确的公里数与分钟数

四、增量优先
- 允许新增文件；修改已有文件只做最小必要改动
- 禁止重写整个类、整个页面、整个配置文件
- 若认为必须重构已有文件，先输出「文件名 + 原因 + 影响范围」，等我确认

五、输出纪律
- 给完整文件内容，禁止「...省略」「此处同上」「保持不变」这类占位
- 单次回复太长时，先给「本轮文件清单 + 第 1 个文件」，我说「继续」再给下一个
- 中文注释写清「为什么这么做」，不要只写「设置xxx」

六、事实纪律
- 接口路径、字段名、方法名必须来自我提供的文件或真实存在的依赖
- 不确定的（如某个 API 的参数名）先说明「我不确定，需要查文档」，不要编造
- 不要假设表或字段存在，先读我给的 schema 脚本

七、安全与规范
- 所有 API Key 走 application.yml 占位符 + 环境变量覆盖，禁止硬编码
- 日志统一 slf4j，禁止 System.out.println
- 用户输入入库前做基础校验（长度、类型、枚举范围）
- 不要动 .workbuddy 目录

八、交付标准
- 每轮结束输出：① 编译/启动命令 ② 验证步骤（curl 示例或页面路径）
  ③ 本轮文件清单 ④ 已知未完成项与风险
- 代码必须能跑，不接受「理论上可以」
```

---

## 4. 上下文交接块（C 块）

> 接在 G 块后。**按阶段替换文件清单**，原则是「只让它读与本阶段强相关的文件」，读太多反而分散注意力。

### 4.1 初始化阶段（P0）

```
【上下文交接 · 请先确认再动手】

本项目是全新仓库，当前为空。请先做三件事：
① 输出你计划创建的完整目录树（后端 + 前端 + 文档），等我确认
② 确认包名、artifactId、数据库名与《Wayfare 开发文档》一致
③ 列出 P0-A 这一步要产出哪些文件

不要在我确认前写任何代码。
```

### 4.2 涉及已有代码的阶段

```
【上下文交接 · 请先读这些文件，读完复述，等我确认后再写代码】

必读：
- README.md
- Wayfare开发文档.md
- <本阶段相关的 schema 脚本>
- <本阶段要改的类，逐个列出完整路径>
- <本阶段的参照实现：找一个已写好的同类，让它照着写>

读完请复述三点，然后停下等我确认：
① 本阶段要达成的目标（一句话）
② 现有相关代码的结构与调用链
③ 你计划新增/修改的文件清单
```

> **强烈建议每次都给一个「参照实现」**。比如写 `GlmProvider` 时，让它先读 `DeepSeekProvider`，照着写。这比任何文字描述都有效，且能保证风格统一。

---

## 5. 阶段总览

| 阶段 | 子任务 | 核心产出 | 依赖 |
|---|---|---|---|
| **P0** | A–D | 仓库骨架 + 内容域自建 + 鉴权 + 前台浏览 | - |
| **P1** | A–E | 双厂商大模型接入 + 可插拔连接器层 | P0 |
| **P2** | A–C | 数据层：画像 + 行程表族 + 日志表 | P1 |
| **P3** | A–F | 7 步 AI 行程编排管线 | P2 |
| **P4** | A–C | SSE 流式 + 降级出口 + 生成日志 | P3 |
| **P5** | A–D | 前端：偏好中心 + 规划四步流程 | P4 |
| **P6** | A–B | 后台：连接器开关 + AI 监控 | P5 |
| **P7** | A–C | 测试 + 指标埋点 + 降级演练 | P6 |
| **P8** | A–B | 文档同步 + Docker 部署 | P7 |

**总任务块数：30 个。** 每个块对应一次投喂，块内工作量控制在 3–5 个文件。

> 关键路径：P1 → P2 → P3。**P3-F（管线跑通并输出指标）完成的那天，项目就立住了。**

---

## 6. 阶段提示词 P0 ~ P8

---

## P0 ｜ 项目初始化与内容域自建

> P0 共 4 块。目标：有一个能跑起来、能浏览、能发帖的骨架，但还没有 AI 能力。

### P0-A ｜仓库骨架初始化

```
【P0-A · 仓库骨架初始化】

任务：从零搭建 Wayfare 的前后端脚手架，跑起来为空壳。

后端要求：
1) Maven 项目 wayfare-backend，groupId com.wayfare，artifactId wayfare-backend，
   Spring Boot 3.3.5，JDK 17
2) 依赖：spring-boot-starter-web / validation / data-redis、
   mybatis-plus-spring-boot3-starter 3.5.7、mysql-connector-j、lombok、
   spring-security-crypto（只要 BCrypt）、jjwt 0.12.6（api/impl/jackson）、
   spring-boot-starter-test、springdoc-openapi-starter-webmvc-ui
3) 包结构（一次建全，后续阶段只往里加）：
   com.wayfare
   ├── WayfareApplication.java
   ├── common/{result,exception,config,util}
   ├── security/
   ├── entity/ mapper/ service/{impl} controller/ dto/ vo/
   ├── profile/      （用户旅行偏好，P2 用）
   ├── trip/         （行程域，P2 用）
   └── connector/    （连接器层，P1 用）
       ├── llm/      { glm, deepseek, mock }
       ├── map/      { baidu, cache, disabled }
       └── governance/（熔断 / 能力决策 / 统一 HTTP 客户端）
4) application.yml：
   - server.port 8080，servlet.context-path /api
   - MySQL: jdbc:mysql://localhost:3306/wayfare
   - Redis: localhost:6379
   - mybatis-plus：logic-delete-field deleted、mapper-locations
   - 上传目录、允许类型、水印文案「行走集 Wayfare」
   - springdoc 配置，便于 /swagger-ui.html
5) 实现 Result<T> 统一返回、ResultCode 枚举、GlobalExceptionHandler、
   CorsConfig、MybatisPlusConfig（分页插件 + 自动填充 created_at/updated_at）
6) HealthController：GET /api/health 返回 {status:"UP", app:"wayfare", version}

前端要求：
1) Vite + Vue3（JS）+ Element Plus + Pinia + Vue Router + Axios + Sass
2) 目录：src/{api,stores,router,layouts,components,views,assets}
3) vite.config.js 配 /api 代理到 localhost:8080，@ 别名指向 src
4) request.js：Axios 实例，请求拦截器注入 Authorization Bearer，响应拦截器统一处理
   401（清 token 跳登录）与非 200 业务码（ElMessage 提示）
5) DefaultLayout：顶部导航（首页 / AI 规划 / 发布 / 私信 / 头像下拉），响应式
6) 占位页面：Home / Login / Register / NotFound

【不要做】
- 不要写任何业务表、业务实体
- 不要写 AI、地图相关代码
- 不要写 Docker 相关文件

【验收】
1) 后端 mvn spring-boot:run 启动成功，GET http://localhost:8080/api/health 返回 UP
2) http://localhost:8080/swagger-ui.html 能打开（证明 springdoc 依赖真实生效）
3) 前端 npm run dev 启动，导航栏正常，路由切换无报错
4) 给出完整目录树与「本轮文件清单」
```

### P0-B ｜数据库与内容域实体

```
【P0-B · 数据库与内容域实体】

任务：建库脚本 + 内容域全部实体与 Mapper。

数据库：wayfare，字符集 utf8mb4，无物理外键，主键 BIGINT UNSIGNED AUTO_INCREMENT

【一、schema.sql — 内容域与用户域，共 14 张表】
1) sys_user：id / username(唯一) / password(BCrypt) / nickname / avatar / email / phone /
   gender / bio / role(user|admin) / status / last_login_at / last_login_ip /
   created_at / updated_at / deleted
2) category：id / name / parent_id(0为一级) / icon / sort / status / 时间
3) tag：id / name(唯一) / use_count
4) work：id / user_id / category_id / title / description / cover_url / cover_width /
   cover_height / destination（攻略特有：目的地）/ trip_days（攻略特有：天数）/
   view_count / like_count / collect_count / comment_count / is_watermarked /
   status(0草稿 1已发布 2审核中 3已下架) / published_at / 时间 / deleted
5) work_image：id / work_id / image_url / width / height / sort
6) work_tag：work_id + tag_id 联合主键
7) comment：id / work_id / user_id / parent_id / reply_to_user_id / content /
   like_count / status
8) like_record：id / user_id / target_type(1作品 2评论) / target_id / created_at
   （唯一索引 user_id + target_type + target_id）
9) favorite：id / user_id / work_id（唯一索引 user_id + work_id）
10) follow：id / follower_id / following_id（唯一索引）
11) private_message：id / sender_id / receiver_id / conversation_id / content /
    msg_type / is_read
12) user_third_account：预留，建表但不写代码
13) report：预留，建表但不写代码
14) admin_operation_log：id / admin_id / module / action / target_type / target_id /
    detail(JSON) / ip / created_at —— 本版要**真实写入**，不要留空

初始数据：
- 管理员账号 admin（密码 Admin123456，BCrypt 值你在脚本里算好直接插入）
- 8 个攻略分类：古建探访 / 自然风光 / 博物馆 / 市井烟火 / 美食之旅 / 亲子出行 /
  摄影旅拍 / 城市漫步

【二、实体类（MyBatis-Plus 注解风格）】
为上面每张表建 entity + mapper。注意：
- 字段用 @TableField(fill=...) 自动填充 created_at / updated_at
- work 表要 @TableLogic 逻辑删除 deleted
- 枚举字段在实体里用 Integer，在 VO 里转成中文描述

【三、关键设计说明】
- work 表新增 destination 与 trip_days 两个字段，这是「攻略」相比「摄影作品」的
  核心差异：攻略必须能按目的地和天数筛选
- cover_width / cover_height 保留，用于瀑布流布局

【不要做】
- 不写 Controller / Service 业务逻辑
- 不写任何 AI / 地图 / 行程相关表（P2 单独做）

【验收】
1) schema.sql 在 MySQL 8 执行成功，14 张表全部建好
2) 启动项目，MyBatis-Plus 能映射全部实体，无 SQL 报错
3) 输出「表 - 实体 - 索引」对照表
```

### P0-C ｜鉴权 + 文件上传 + 内容域接口

```
【P0-C · 鉴权、文件服务与内容域接口】

参照实现：请先读你自己在 P0-A / P0-B 建的 Result / ResultCode / GlobalExceptionHandler
与 entity / mapper，保持风格一致。

【一、鉴权】
- JwtUtil：生成/解析 token，有效期 7 天，secret 走配置
- LoginUser：userId / username / role
- UserContext：ThreadLocal 存取当前登录用户
- JwtInterceptor：从 Authorization: Bearer 取 token，校验后塞 UserContext，
  失败返回 401
- WebMvcConfig：注册拦截器，排除 /auth/login、/auth/register、/health、
  /uploads/**、/error、/swagger-ui/**、/v3/api-docs/**，
  以及公开浏览接口（/works/page、/works/{id}、/categories/tree、/tags/hot、
  /comments/work/**、/recommend/hot）
- 注意：管理接口一律走鉴权，不允许放进排除名单

接口：
POST /api/auth/register  用户名4-20位 + 密码6-20位含字母数字，BCrypt 加密
POST /api/auth/login     返回 {token, user}
POST /api/auth/logout    token 加入 Redis 黑名单

【二、文件服务】
- FileController：POST /api/files/upload（单图）、/batch（多图）、/avatar（头像）
- FileServiceImpl：本地存储到配置目录，格式白名单（jpg/jpeg/png/gif/webp），
  单文件 ≤10MB，**上传即调用 WatermarkUtil 加文字水印**（ImageIO 实现，不用 Thumbnailator）
- WebMvcConfig 映射 /uploads/** 到本地目录
- 返回 UploadVO {url, width, height}

【三、内容域接口】
- Category：GET /categories/tree（公开，二级树）、GET /categories/page、
  POST/PUT/DELETE /categories（管理员，有作品关联时拒绝删除）
- Work：
  POST   /works            发布（body 含 imageUrls 数组）
  PUT    /works/{id}       编辑（仅作者或管理员）
  DELETE /works/{id}       逻辑删除
  GET    /works/{id}       详情（浏览量 +1）
  GET    /works/page       分页（支持 destination / tripDays / categoryId / keyword / status）
  GET    /works/my
  PUT    /works/{id}/status  改状态（管理员）
- WorkServiceImpl 关键要求（这是 v1.0 踩过的坑，必须避免）：
  1) create：先 insert work 拿到 id，再**批量 insert work_image**（sort 从 0 递增），
     cover_url 取第一张。绝不能把 imageUrls 写成只有封面一张
  2) update：imageUrls 非空时先删旧 work_image 再重插
  3) getById：从 work_image 按 sort 正序查真实图片列表
  4) page：用一次 IN 查询批量捞 work_image 再填充，禁止在循环里查库
- 社交接口：点赞 / 收藏 / 关注 / 评论（二级回复）/ 私信（会话列表 + 未读统计），
  点赞收藏关注用 Redis 做计数缓存

【四、内容审核】
- ContentAuditServiceImpl：从配置读敏感词列表，audit(text, fieldName) 命中即抛业务异常
- 在作品标题/描述、评论内容的写入前调用

【不要做】
- 不要写 AI / 行程相关代码
- 不要用 Thumbnailator

【验收】
1) 注册 → 登录 → 拿到 token → 调 /users/me 成功
2) 上传 3 张图 → 发布作品 → **查 work_image 表有 3 条记录** → 详情页返回 3 张图
3) 按目的地 / 天数筛选作品生效
4) 未登录调 PUT /works/1/status 返回 401，普通用户返回 403
5) 敏感词命中时发布被拒绝并提示
6) 输出接口清单（方法 / 路径 / 权限 / 说明）
```

### P0-D ｜前端内容域页面

```
【P0-D · 前端页面：内容域】

参照：读取你自己的 src/api/request.js、router/index.js、stores/user.js、DefaultLayout.vue

【一、登录注册】
- Login.vue / Register.vue，表单校验（用户名4-20位、密码规则、确认密码一致）
- 登录成功写 Pinia（token 持久化到 localStorage）→ 跳 redirect 或首页

【二、首页 Home.vue】
- 顶部：目的地搜索框 + 分类筛选 + 天数筛选（1/2/3天/更多）
- 瀑布流卡片：封面图 / 标题 / 目的地 / 天数 / 作者 / 点赞收藏数
- 无限滚动加载（每页 20）
- 右侧或顶部区块：热门攻略、热门标签

【三、攻略详情 WorkDetail.vue】
- 图片轮播（多图真实生效）、标题、作者、发布时间、目的地、天数
- 点赞 / 收藏 / 关注按钮（状态实时查询）
- 评论区：一级评论 + 二级回复、发表评论、删除自己的评论
- 预留「查看完整行程」区块占位（P5 填内容）

【四、发布 / 编辑 WorkPublish.vue】
- 多图上传（拖拽排序、删除、预览），提交时把图片 URL 数组一起提交
- 标题、描述（富文本或 textarea）、分类（**从 /categories/tree 拉取，禁止硬编码**）、
  标签、目的地、天数
- 草稿 / 立即发布

【五、个人中心 Profile.vue】
- 基本信息修改（昵称 / 头像 / 性别 / 简介）
- 我的作品、我的收藏、我的关注 / 粉丝
- 预留「旅行偏好」页签占位（P5 填内容）

【六、私信 Messages.vue】
- 左侧会话列表（最后一条消息 + 未读角标），右侧聊天窗口，发送文本

【不要做】
- 不要用 TypeScript
- 不要删除或简化上面任何一项功能
- 不要引入 vuedraggable 之外的新 UI 库（拖拽排序可用按钮上下移替代）

【验收】
1) 完整走通：注册 → 登录 → 发 3 图攻略 → 首页看到 → 详情页轮播 3 张 → 点赞收藏 →
   评论回复 → 私信
2) 手机宽度下布局不破
3) 分类下拉来自接口
4) 输出页面清单与操作路径
```

---

## P1 ｜ 连接器层：数据层与双厂商大模型接入（★ 地基）

> P1 共 5 块。目标：两个大模型厂商可热切换、地图可整体关闭、出站调用有治理。**这一步做扎实，后面全是顺水推舟。**

### P1-A ｜配置与运行时开关层

```
【P1-A · 配置层与运行时开关（sys_config）】

【一、schema-trip.sql 的第一部分：sys_config 表】
id / config_key(唯一) / config_value / value_type(STRING|INT|BOOL|JSON) /
group_name / description / updated_by / updated_at

初始数据（分组 group_name 用于后台分页展示）：
组 llm：
  llm.enabled            = true
  llm.active-provider    = glm            （当前使用的厂商：glm | deepseek | mock）
  llm.fallback-order     = glm,deepseek   （降级顺序，逗号分隔）
  llm.timeout-ms         = 90000
组 map：
  map.enabled            = false          （★ 默认关闭，没 AK 也能全功能开发）
  map.baidu.ak           = ''
  map.breaker.fail-threshold  = 5
  map.breaker.open-seconds    = 300
组 trip：
  trip.max-days              = 5
  trip.max-candidate         = 20
  trip.max-replan-rounds     = 2
  trip.poi-cache-ttl-hours   = 24

【二、SysConfigService】
- String get(String key) / int getInt / boolean getBool
- void set(String key, String value, Long operatorId)
- **所有读取走 Redis 缓存 30 秒**（key: sys:config，Hash 结构），
  set 时立即删除缓存 → 保证「后台改完不重启即生效」
- 提供 List<SysConfig> listByGroup(String group)

【三、配置类 Properties（启动期配置，与运行期开关分层）】
LlmProperties：providers 为 Map<String, LlmProviderConfig>，每项含
  baseUrl / apiKey / model / temperature / maxTokens
  apiKey 一律写 ${GLM_API_KEY:} / ${DEEPSEEK_API_KEY:}
MapProperties：enabled / baidu.{ak, baseUrl, connectTimeoutMs, readTimeoutMs}

application.yml：
llm:
  enabled: true
  active-provider: glm
  fallback-order: glm,deepseek
  timeout-ms: 90000
  providers:
    glm:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${GLM_API_KEY:}
      model: glm-4-flash
      temperature: 0.3
      max-tokens: 4096
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY:}
      model: deepseek-chat
      temperature: 0.3
      max-tokens: 4096
map:
  enabled: false
  baidu:
    ak: ${BAIDU_MAP_AK:}
    base-url: https://api.map.baidu.com
    connect-timeout-ms: 3000
    read-timeout-ms: 8000

【四、分层说明（写进代码注释）】
L1 启动期配置 = application.yml（LlmProperties / MapProperties）
L2 运行期开关 = sys_config 表 + Redis（改完不重启生效）
L3 降级       = 熔断器 + 分级 Provider（P1-D 实现）
读取优先级：L2 覆盖 L1；L2 未配置时回落 L1

【不要做】
- 不要写任何 LLM / 地图的实际调用代码（P1-B/P1-C 做）
- 不要把 apiKey 的默认值写成真实 key

【验收】
1) schema-trip.sql 执行成功，sys_config 有全部初始数据
2) 启动后调一个临时测试接口能读到 sys_config 的值
3) 手动改数据库某条配置，30 秒内（或清缓存后立即）读到的值变化 —— 证明缓存生效
4) 输出 L1/L2/L3 分层说明表
```

### P1-B ｜GLM 与 DeepSeek 双厂商接入

```
【P1-B · 大模型连接器：GLM + DeepSeek 双厂商】

【一、统一接口（先定义接口，再写实现）】
connector/llm/LlmProvider：
- String chat(String systemPrompt, String userPrompt)
- String chatJson(String systemPrompt, String userPrompt, String jsonSchemaHint)
- void chatStream(String systemPrompt, String userPrompt,
                 Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError)
- LlmInfo info()   →  {provider, model, available}
- String name()    →  "glm" / "deepseek" / "mock"

【二、抽象基类 AbstractOpenAiCompatibleProvider】
GLM 与 DeepSeek 都走 OpenAI 兼容协议，把 90% 的公共逻辑放基类：
- 统一构造请求体：{model, messages, temperature, max_tokens, stream}
- 统一解析响应：非流式取 choices[0].message.content；
  流式逐行解析 "data: {...}"，取 choices[0].delta.content，遇 "data: [DONE]" 结束
- 统一异常转换：HTTP 401 → LLM_AUTH_FAIL；429 → LLM_RATE_LIMIT；
  超时 → LLM_TIMEOUT；5xx → LLM_SERVER_ERROR
- 统一 token 统计：从 usage 字段取 prompt_tokens / completion_tokens / total_tokens，
  通过回调上报给调用方（P4 会落库）
- 请求走 P1-D 的 ExternalHttpClient，不要自己 new HttpClient

两个子类只负责「差异部分」，各自写清楚：
【GlmProvider（智谱）】
- baseUrl: https://open.bigmodel.cn/api/paas/v4
- 认证：Authorization: Bearer {apiKey}
- 支持的模型：glm-4-plus / glm-4-flash / glm-4-air
- **注意点（写进注释）**：
  · GLM 的 JSON 输出建议在请求体加 response_format: {"type":"json_object"}，
    并在 prompt 里**必须**出现 "json" 字样，否则可能不生效
  · 流式返回格式与 OpenAI 一致，但个别版本会在最后一个 chunk 只带 usage 不带
    增量内容，解析时要容忍 content 为 null / 空字符串的情况
  · 若遇到 400 且提示 response_format 不支持，自动降级为纯 prompt 约束重试一次

【DeepSeekProvider】
- baseUrl: https://api.deepseek.com/v1
- 认证：Authorization: Bearer {apiKey}
- 支持的模型：deepseek-chat / deepseek-reasoner
- **注意点（写进注释）**：
  · deepseek-reasoner 会在 delta 里额外返回 reasoning_content 字段，
    解析时要**忽略**它，只取 content（否则思维链会混进攻略文案）
  · 非流式响应里有 usage；流式需在请求体加 stream_options: {"include_usage": true}
    才会返回 usage，否则 token 统计拿不到
  · 长输出（行程编排）建议 max_tokens 提到 4096 以上

【三、MockLlmProvider】
- 离线兜底，不需要网络与 Key
- 按 prompt 内容判断要返回什么：
  · 意图解析 → 返回一份合法的 IntentDTO JSON
  · 行程编排 → 返回一份 2 天 6 个 item 的合法 TripDraftDTO JSON
  · 文案 → 返回一段 200 字左右的假攻略文案
- 用于 llm.active-provider=mock 时跑通全链路

【四、LlmProviderFactory + LlmCapabilityResolver】
- Factory：按 name 返回对应 Provider 实例（Spring 注入 Map<String, LlmProvider>）
- Resolver：
  · 读 sys_config.llm.active-provider 决定主力 Provider
  · 主力不可用（未配 Key / 熔断打开）时，按 llm.fallback-order 依次尝试
  · 全部不可用 → 回落 MockLlmProvider，并记录 reason
  · 返回 ResolvedLlm {provider, model, isFallback, reason}

【不要做】
- 不要写业务逻辑（行程编排是 P3）
- 不要把两个厂商的 baseUrl 硬编码在方法体里，必须走配置

【验收】
1) 只配 GLM Key → llm.ping 用 glm-4-flash 成功返回
2) 把 active-provider 改成 deepseek（配了 DeepSeek Key）→ ping 成功，返回模型名是
   deepseek-chat，**不需要重启**
3) 两个 Key 都不配 → 自动回落 mock，ping 仍返回成功，日志显示 reason
4) 故意把 GLM Key 改错 → 收到 LLM_AUTH_FAIL 且自动降级到 deepseek（若配了）
5) 流式接口：分别用 GLM 与 DeepSeek 调一次 chatStream，
   打印每段 delta，确认 deepseek-reasoner 的思维链没有混进正文
6) 输出两个厂商的接入对照表（baseUrl / 认证 / 模型名 / 需要注意的差异点）
```

### P1-C ｜百度地图连接器与三级降级

```
【P1-C · 地图连接器：百度地图 + 缓存 + 关闭态】

【一、统一接口】
connector/map/MapProvider：
- boolean isAvailable()
- String name() / String unavailableReason()
- List<PoiDTO> searchPoi(PoiQueryDTO q)   // city/keyword/tag/bounds/pageNum/pageSize
- PoiDTO detail(String poiUid)
- RouteDTO route(RouteQueryDTO q)         // from/to lng,lat + mode

DTO 字段：
PoiDTO { poiUid, name, address, lng, lat, tag, shopHours, rating, ticketPrice, rawJson }
RouteDTO { distanceMeters, durationSeconds, mode, polyline }

【二、BaiduMapProvider】
接口地址（务必核对，不确定时先说明）：
- 地点检索：/place/v2/search    参数 {query 或 tag, region, output=json, ak, page_num, page_size}
- 地点详情：/place/v2/detail
- 路线规划：/directionlite/v1/{driving|walking|riding|transit}
AK 读取顺序：sys_config.map.baidu.ak → MapProperties.baidu.ak → 空则不可用

【关键：字段缺失必须容忍】
百度的 POI 检索接口**通常不返回评分与门票**。要求：
- rating / ticketPrice 只从 detail 接口尝试取，取不到就**保持 null**
- 上层用 null 表示「未知」，前端显示「未知」
- **绝对不允许填 0 或编造一个数字冒充真实值**（这是数据诚信的底线）

【三、LocalCacheMapProvider】
- 只查 poi_cache 表（P2 建）与 Redis 路线缓存，零网络请求
- 命中返回真实数据，未命中返回空集合（不是抛异常）

【四、DisabledMapProvider】
- isAvailable() 恒 false，各方法返回空，unavailableReason() 返回明确中文原因

【五、MapCapabilityResolver（核心，严格按序实现）】
resolve() 返回 { mode, provider, reason }，mode ∈ VERIFIED | CACHED | ESTIMATED
决策顺序：
1) sys_config.map.enabled == false
   → mode=ESTIMATED, provider=DisabledMapProvider, reason="管理员已关闭地图连接器"
2) 熔断器处于打开状态
   → 试 LocalCacheMapProvider：有数据 mode=CACHED；无数据 mode=ESTIMATED,
     reason="地图服务连续失败已熔断"
3) 否则 mode=VERIFIED, provider=BaiduMapProvider, reason=null

【六、熔断器（不引 Resilience4j，用 Redis 计数）】
- 记录连续失败次数，≥ map.breaker.fail-threshold（默认5）→ 打开熔断
  map.breaker.open-seconds（默认300秒）
- 熔断期间直接走降级，不发起请求
- 到期后半开：放行一次请求，成功则清零并关闭熔断，失败则重新计时
- 状态要能被诊断接口读到

【不要做】
- 不要写行程编排逻辑
- 不要在业务代码里直接 new HttpClient / 写死 AK

【验收】
1) 默认 map.enabled=false → 调诊断接口显示 mode=ESTIMATED 与明确 reason
2) 后台把 map.enabled 改为 true 且 AK 合法 → mode 变 VERIFIED，POI 检索返回真实数据
3) 故意填错 AK → 连续 5 次失败后 breakerOpen=true，mode 降为 CACHED 或 ESTIMATED
4) 手工往 poi_cache 插一条数据 → 熔断状态下检索能命中它，mode=CACHED
5) 找一个百度不返回 rating 的 POI，确认 rating 是 null 而不是 0
6) 输出决策顺序流程图（文字版即可）
```

### P1-D ｜统一出站治理：重试、缓存、脱敏、日志

```
【P1-D · ExternalHttpClient：出站调用的统一治理层】

所有外部 HTTP 调用（GLM / DeepSeek / 百度地图）必须走这一个封装，禁止任何地方
绕开它直接发请求。

【一、超时与重试】
- 超时：按调用方传入的 timeout 参数；地图默认 连接 3s / 读 8s；
  大模型非流式 90s；流式不设读超时（由上层控制）
- 重试：**仅 GET 失败重试 2 次**，指数退避 200ms / 600ms
- **POST 一律不自动重试**（大模型与地图的 POST 请求重复执行可能产生费用或副作用）
- 重试需跳过 4xx（除 429）——客户端错误重试无意义

【二、缓存（Spring Cache + Redis）】
| 用途 | key | TTL |
|---|---|---|
| POI 检索 | map:poi:{provider}:{city}:{keyword}:{pageNum} | 24h |
| 路线规划 | map:route:{mode}:{fromLng},{fromLat}:{toLng},{toLat} | 12h |
| POI 详情 | map:detail:{poiUid} | 7d |
注意：**大模型调用一律不做缓存**（同一输入需要可复现的新结果）

【三、调用日志与脱敏】
- 每次调用写 external_call_log：
  id / user_id / trip_id / connector(LLM|BAIDU_MAP) / api_name / request_summary /
  http_status / duration_ms / success / error_msg / created_at
- request_summary 截断到 500 字符，且**必须脱敏**：
  · ak / api_key / Authorization 的值只保留前 4 位 + "****"
  · 提供一个 MaskUtil.maskSecret(String) 工具方法，统一调用
- 日志与任何接口响应中都不得出现完整密钥（P7 会有测试验证这一点）

【四、token 统计通道】
- 提供 TokenUsageCallback，供 LlmProvider 上报 {promptTokens, completionTokens, totalTokens}
- 用 ThreadLocal 或调用方传入的上下文对象承载，避免污染方法签名

【不要做】
- 不要引 Resilience4j / Feign / RestTemplate 之外的 HTTP 库
- 不要写业务逻辑

【验收】
1) 单元测试：MaskUtil 对 "sk-abcdefghijklmn" 返回 "sk-a****"
2) 单元测试：GET 首次失败第二次成功 → 最终成功且日志有 2 条记录
3) 单元测试：POST 失败不重试，只 1 条记录
4) 连续两次相同 POI 检索 → 第二次命中缓存（响应时间显著下降，且百度侧无新请求）
5) external_call_log 表里有真实记录，且 request_summary 中看不到完整密钥
6) 输出「key 设计 + TTL + 脱敏规则」说明
```

### P1-E ｜连接器诊断接口

```
【P1-E · 连接器诊断接口】

【一、接口（全部管理员权限）】
GET  /api/diagnostics/connectors
     返回 {
       llm: { enabled, activeProvider, model, available, isFallback, reason,
              lastSuccessAt, lastErrorAt, failRate1h, todayCallCount, todayTokens, estCost },
       map: { enabled, mode, provider, available, reason, breakerOpen,
              lastSuccessAt, failRate1h, todayCallCount }
     }
POST /api/diagnostics/llm/ping      body {provider?: "glm"|"deepseek"}，
     发一句「请只回复：OK」，返回 {provider, model, durationMs, reply}
POST /api/diagnostics/map/ping      body {city, keyword}，
     真实发起一次 POI 检索，返回 {durationMs, hitCount, samples[], rawSummary}
POST /api/admin/config/map/enabled  body {enabled}，改 sys_config 并清缓存，立即生效
POST /api/admin/config/llm          body {activeProvider?, fallbackOrder?, model?}
GET  /api/admin/configs             按 group 分组返回全部配置
PUT  /api/admin/configs/{key}       body {value}

【二、实现要求】
- ping 接口要能区分「未配置 Key」「网络不通」「认证失败」「限流」四类结果，
  返回可读的中文原因，而不是笼统的"调用失败"
- 所有返回中不得出现完整 Key（只回显掩码）

【验收】
1) 分别 ping GLM 与 DeepSeek，能看到各自耗时与模型名
2) 不配 Key 时 ping 返回「未配置 API Key」而不是 500
3) ping 地图时故意用错 AK，返回「认证失败（AK 无效或未开启该服务）」
4) 调 /admin/config/map/enabled 关闭地图后，立即（不重启）再调
   /diagnostics/connectors 能看到 mode=ESTIMATED
5) 输出诊断接口清单与返回示例
```

---

## P2 ｜ 数据层：偏好画像、行程表族与日志

> P2 共 3 块。目标：把 AI 会写到的所有表建好。**这一阶段不写业务逻辑，纯数据层。**

### P2-A ｜用户旅行偏好（向 LLM 分析注入的画像）

```
【P2-A · 用户旅行偏好画像】

【一、表：user_travel_profile】
- id / user_id（唯一索引）
- 口味相关：
  cuisines          VARCHAR(255)  喜欢菜系，逗号分隔，如 "晋菜,面食,家常菜"
  flavors           VARCHAR(255)  口味偏好，如 "偏咸,微辣"
  taboos            VARCHAR(500)  忌口与过敏，如 "香菜,花生,海鲜"
- 风格相关：
  travel_styles     VARCHAR(255)  旅行风格，多选：古建探访/自然风光/博物馆/
                                  市井烟火/摄影旅拍/亲子出行/城市漫步/美食之旅
  pace              TINYINT       节奏 1慢(每天2-3点) 2适中(3-4) 3紧凑(4-5)
  budget_level      TINYINT       预算倾向 1经济 2舒适 3品质
  companions        VARCHAR(50)   常同行人 独自/情侣/朋友/家庭带娃/带长辈
  walk_limit_km     INT           单日步行上限（公里），约束点位密度
- 其他：
  hotel_pref        VARCHAR(255)  住宿偏好
  notes             VARCHAR(500)  自由备注
  allow_ai_use      TINYINT       隐私开关：是否允许 AI 使用本画像，默认 1
- created_at / updated_at

【二、实体 + Mapper + Service + Controller】
GET  /api/profile/travel     获取当前用户画像（不存在时返回空对象，不报 404）
PUT  /api/profile/travel     保存（存在则更新，不存在则插入 —— upsert）

【三、画像渲染工具（关键）：ProfileRenderer】
提供一个方法 render(profile, overrides) → String，把结构化字段渲染成
**一段中文文本块**，供 P3 注入 system prompt。

格式要求（严格照抄，P3 会依赖这个格式）：
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

规则：
- 字段为空的行**整行省略**，不要输出"无""未填写"
- 忌口字段若为空，则输出「忌口过敏：无」并注明"这点可以更自由地推荐餐饮"
- overrides（本次临时条件）优先级高于长期画像：
  · overrides.taboos 非空时，与画像 taboos **合并**（不是覆盖），去重
  · overrides.pace 非空时**覆盖**画像 pace
- 提供 hasAnyContent(profile) 方法，用于判断「画像是否为空」——
  全空时 P3 应跳过注入，并在响应里标记 profileUsed=false

【验收】
1) 保存后再获取，字段完整往返
2) 全空画像调 render 时返回空字符串，hasAnyContent 返回 false
3) 只填了忌口和时间节奏时，render 输出**只有这两行**，没有空字段行
4) overrides 合并逻辑：画像忌口 ["香菜"] + 本次忌口 ["花生"] →
   输出包含两者；画像节奏=慢 + 本次节奏=紧凑 → 输出紧凑
5) 输出一个完整的 render 输出示例（用真实中文）
```

### P2-B ｜行程表族

```
【P2-B · 行程表族：trip / trip_day / trip_item】

【一、trip 行程主表】
- id / user_id
- title              VARCHAR(100)  用户可改，AI 生成的默认标题
- raw_input          TEXT          用户原始输入
- intent_json        JSON          意图解析结果（P3 Step1 写入）
- destination        VARCHAR(50)   目的地名称
- dest_lng / dest_lat DECIMAL     目的地中心坐标，**地图关闭时为 NULL（正常状态）**
- days               INT           天数
- start_date         DATE
- budget_total       DECIMAL(10,2) 预算
- budget_mode        TINYINT       1人均 2总计
- transport          VARCHAR(10)   DRIVE/PUBLIC/WALK/MIX
- companion          VARCHAR(20)
- map_mode           VARCHAR(10)   **VERIFIED / CACHED / ESTIMATED**（本次生成的数据可信度）
- profile_used       TINYINT       本次生成是否使用了用户画像
- status             TINYINT       0草稿 1已生成 2已编辑 3已发布
- work_id            BIGINT        发布后关联 work.id，可空
- model_name         VARCHAR(50)   本次使用的模型（如 glm-4-flash / deepseek-chat）
- generation_rounds  INT           实际重排轮次（观测迭代优化效果）
- created_at / updated_at / deleted
索引：idx_user_id、idx_status_created

【二、trip_day 行程日】
- id / trip_id / day_index（从1起）/ title（当天主题）/ summary / created_at
索引：(trip_id, day_index)

【三、trip_item 行程条目（核心表）】
- id / trip_id / day_index / seq（当天顺序）
- item_type         VARCHAR(10)   SCENIC/FOOD/HOTEL/TRANSPORT/REST
- poi_uid           VARCHAR(64)   地图 POI 唯一标识，**地图关闭时为空**
- poi_name          VARCHAR(100)
- address           VARCHAR(255)
- lng / lat         DECIMAL       **可空**
- arrive_time / leave_time  TIME
- stay_minutes      INT
- ticket_price      DECIMAL(10,2) 门票，可为 NULL（未知）
- cost_estimate     DECIMAL(10,2) 费用估算
- transport_mode_to_next VARCHAR(10)
- distance_meters   INT           到下一站距离，**估算模式下为 NULL**
- duration_seconds  INT
- verify_status     VARCHAR(10)   **VERIFIED / CACHED / ESTIMATED / USER**
- data_source       VARCHAR(10)   **BAIDU / LLM / USER**
- reason            VARCHAR(500)  AI 给出的安排理由
- note              VARCHAR(500)  备注（估算模式下放模糊表述，如"步行约十几分钟"）
- created_at / updated_at
索引：(trip_id, day_index, seq)

【关键设计说明（写进注释）】
verify_status 与 data_source 是本项目的数据诚信机制：
- 任何进了 trip_item 的事实字段，都必须有这两个标记
- 前端据此渲染来源角标：绿「实测」/ 灰「缓存」/ 橙「估算」/ 蓝「手动」
- 没有标记的事实字段视为设计缺陷

【四、实体 + Mapper + Service 接口】
- Service 接口方法可以先留空实现，但签名要定好：
  TripService: createDraft / saveFullTrip / getDetail / pageMy / logicDelete / updateItemOrder
  不要写业务逻辑

【不要做】
- 不要写编排逻辑
- 不要动 P0 的内容域表

【验收】
1) schema-trip.sql 执行成功，4 张表（含 sys_config）全部就绪
2) 启动无报错，实体能映射
3) 手工插一条 trip + 2 条 trip_day + 5 条 trip_item，能按 day+seq 正序查出
4) 输出「表 - 实体 - 索引 - 查询场景」对照表
```

### P2-C ｜日志与缓存表

```
【P2-C · 日志表与 POI 缓存表】

【一、poi_cache（地图降级的第二级数据源）】
- id / provider / city / keyword / poi_uid（唯一）
- name / address / lng / lat / tag / shop_hours
- rating / ticket_price（可为 NULL，表示地图未提供）
- raw_json TEXT / fetched_at / expires_at
索引：(city, keyword)、(poi_uid)
用途：BaiduMapProvider 每次成功检索后异步回写；LocalCacheMapProvider 只读它

【二、ai_generation_log（AI 生成日志 —— 本项目的成本与质量证据链）】
- id / user_id / trip_id
- stage        VARCHAR(20)  PARSE/CANDIDATE/PREORDER/COMPOSE/VALIDATE/ROUTE/COPY
- provider     VARCHAR(20)  glm / deepseek / mock / baidu
- model        VARCHAR(50)
- prompt_tokens / completion_tokens / total_tokens   INT
- duration_ms  INT
- success      TINYINT
- error_code   VARCHAR(30)
- error_msg    VARCHAR(500)
- created_at
索引：(user_id, created_at)、(stage, success)、(trip_id)

【三、external_call_log（外部调用日志）】
- id / user_id / trip_id / connector(LLM|BAIDU_MAP) / api_name
- request_summary VARCHAR(500)（已脱敏）/ http_status / duration_ms
- success / error_msg / created_at
索引：(connector, created_at)、(success)

【四、AiLogService（写入与聚合）】
- recordStage(...)  写一条 ai_generation_log
- recordCall(...)   写一条 external_call_log
- 聚合查询：
  · sumTokensByUserAndDate(userId, date) → 当日 token 与预估成本
  · successRateByStage(from, to)
  · topErrors(from, to, limit)
- 成本计算：提供 estCost(provider, tokens) 方法，单价从 sys_config 读
  （键：llm.price.glm / llm.price.deepseek，单位「元/百万token」）
  这两个单价配置项加进 sys_config 初始数据

【五、统一 error_code 枚举（定死，后续所有阶段复用）】
LLM_TIMEOUT / LLM_RATE_LIMIT / LLM_AUTH_FAIL / LLM_PARSE_FAIL / LLM_SERVER_ERROR /
MAP_UNAVAILABLE / MAP_AUTH_FAIL / MAP_BREAKER_OPEN /
CANDIDATE_SHORTAGE / VALIDATION_FAILED / SCHEMA_INVALID / CLIENT_DISCONNECTED

【验收】
1) schema-trip.sql 追加这三张表与单价配置，执行成功
2) 手工插入几条日志，调用聚合方法能算出正确的 token 合计与成本
3) 输出 error_code 枚举表与触发场景说明
```

---

## P3 ｜ AI 行程编排管线（★ 核心）

> P3 共 6 块，是全程最关键的部分。**每一块单独投喂，跑通再进下一块。**
> 核心纪律：LLM 只做语义决策，事实数据只能来自地图或标记为估算。

### P3-A ｜意图解析（Step 1）

```
【P3-A · 管线 Step 1：意图解析】

【目标】把一句自然语言 + 用户画像，解析成结构化 IntentDTO。

【一、IntentDTO 字段】
destination      String     目的地
destLng/destLat  Double     中心坐标，**地图关闭时留 null**
days             Integer    天数
startDate        LocalDate  起始日期
budgetTotal      BigDecimal 预算金额
budgetMode       String     PER_PERSON | TOTAL（人均 / 总计）
transport        String     DRIVE | PUBLIC | WALK | MIX
companion        String     同行人
preferenceTags   List<String> 偏好标签（古建筑 / 自然风光 / 博物馆 / 美食 …）
pace             Integer    1慢 2适中 3紧凑
dietaryOverrides List<String> 本次临时忌口
needConfirm      List<String> **模型主动标注「我靠猜的」字段名**
shortageHint     String     可选
confidence       Double     0-1

【二、实现 parseIntent(rawInput, profile, overrides, capability)】
1) 组装 system prompt，必须包含：
   - 角色：「你是旅行需求解析器，只输出 JSON，不要任何解释文字」
   - 完整字段说明与枚举取值范围
   - 今天的日期（用于推算"周末""下个月"这类相对时间）
   - 【画像块】仅当 profile 非空且允许使用时，用 ProfileRenderer 渲染后插入
   - 【本次覆盖】overrides 中的临时条件
   - 明确要求：**凡是你靠猜测填的字段，必须把字段名放进 needConfirm 数组**
     例如预算口径不明（"预算500"没说人均还是总计）→ needConfirm 加 "budgetMode"
2) 调 chatJson，拿回字符串后做 JSON 解析 + Schema 校验：
   - 字段类型（days 必须是数字且 1 ≤ days ≤ trip.max-days）
   - 枚举越界（transport / budgetMode / pace 必须在允许值内）
   - 必填项（destination / days 不能为空）
3) 校验失败 → 把**具体的校验错误信息**拼进新的 user prompt 重试 1 次
4) 仍失败 → 抛 BusinessException(SCHEMA_INVALID)，**不要静默兜底编一个**
5) 目的地区县消歧：若 capability.mode == VERIFIED，用地图检索一次目的地名称，
   验证存在性并补 destLng / destLat；检索不到则记入 needConfirm（"目的地名称可能有误"）
6) 写 ai_generation_log（stage=PARSE，含 tokens 与耗时）

【三、Prompt 示例（用三段测试输入自测）】
输入1：周末想去寿阳玩两天，喜欢古建筑，预算 500
输入2：三天，想去大同看古建和博物馆，不吃辣，一个人，预算 1500
输入3：两天，带爸妈，走不动，想轻松点，想吃面食

对输入1，正确输出应包含：destination=寿阳, days=2, preferenceTags 含古建筑,
budgetTotal=500, needConfirm 含 "budgetMode"（因为没说人均还是总计）,
companion 可能为 null 并进入 needConfirm

【不要做】
- 不要把解析逻辑和 P3-B 的检索混在一起
- 不要在解析阶段调用行程编排

【验收】
1) 三条测试输入逐个跑通，把完整 IntentDTO 的 JSON 贴出来
2) 输入1 的 needConfirm 必须包含 budgetMode
3) 故意让模型返回非法 JSON（临时改 prompt 测试）→ 确认会重试 1 次后抛
   SCHEMA_INVALID，而不是返回一个错误的 IntentDTO
4) 输入3 中"走不动"应被解析为 pace=1（慢）且 companion 含长辈
5) ai_generation_log 里能看到 PARSE 阶段的真实 token 与耗时
```

### P3-B ｜候选检索（Step 2）

```
【P3-B · 管线 Step 2：候选景点检索】

【目标】按偏好从地图检索真实 POI，形成候选池。**这是防幻觉的第一道闸门。**

【一、偏好 → 地图关键词映射字典】
新建 resources/map-preference-tag.json，可维护，初始内容至少覆盖：
古建筑 / 文物古迹 → ["风景名胜;文物古迹", "文物古迹", "古建筑", "寺", "庙", "塔"]
自然风光        → ["风景名胜;公园广场", "自然地名", "风景区"]
博物馆          → ["科教文化服务;博物馆", "博物馆", "纪念馆"]
市井烟火        → ["购物服务;特色街区", "步行街", "老街"]
摄影旅拍        → ["风景名胜", "公园广场"]
美食            → ["美食", "中餐厅", "小吃"]
亲子            → ["风景名胜;公园广场", "游乐园", "动物园"]
说明：地图的 tag 体系与用户口语不对应，这个字典就是翻译层。
若某偏好查不到映射，回退为直接用偏好词作为 query。

【二、实现 searchCandidates(intent, profile, capability)】
1) 分两路检索：
   - 景点路：用 intent.preferenceTags 逐个查字典，取 tag/query 到目的地范围检索
   - 餐饮路：用 profile.cuisines 与 dietaryOverrides 中的菜系词检索
2) 清洗：
   - 丢弃无坐标的点（地图关闭场景例外，见下）
   - 按 poiUid 去重
   - 按 name 做二次去重（相似度 > 0.85 视为同一个点，防止"XX寺"重复）
   - 剔除名字中含 profile.taboos 的餐饮点（忌口第一道过滤）
   - 截断到 trip.max-candidate（默认 20）
3) 每个候选打标记：dataSource=BAIDU、verifyStatus=VERIFIED、建议停留时长
   （可按 item_type 给默认：景点 90min、博物馆 120min、餐饮 60min）
4) 【地图关闭 / ESTIMATED 模式】改为调大模型生成候选清单：
   - prompt 里必须明确写：「你不掌握实时数据，**不要输出经纬度**，
     不要编造具体门牌号。只输出：名称、所属区域（如"寿阳县城东"）、
     建议停留时长（分钟）、一句话亮点」
   - lat/lng 一律留空
   - 标记 dataSource=LLM、verifyStatus=ESTIMATED
   - 生成数量控制在 10-15 个，避免长尾幻觉
5) 【候选不足降级】景点候选 < 6 个时按序尝试：
   a) 扩大检索半径（region 从区县放宽到地级市，或去掉 bounds 限制）
   b) 放宽偏好（把 preferenceTags 相关的一级 tag 全部纳入）
   c) 仍 < 6 → 不失败，正常返回，但带 shortage=true 与 shortageHint
      （如"寿阳县城可检索到的古建点位较少，已为你放宽到周边区县"）
   **绝不为了让流程走下去而编造景点**
6) 写 ai_generation_log（stage=CANDIDATE）
7) 候选池要能被后续步骤序列化进 prompt，因此提供一个
   CandidateSerializer 输出紧凑格式：
   [1] 名称 | 类型 | 区域 | 坐标(可空) | 建议停留 | 亮点

【不要做】
- 不要在这里做排序（那是 P3-C）
- 不要因为地图关闭就抛异常 —— 必须走 LLM 生成路径

【验收】
1) 地图关闭模式：输入"寿阳 古建筑"，能得到 10+ 个候选，且 lat/lng 全为 null，
   verifyStatus 全为 ESTIMATED
2) 地图开启模式：同样输入走百度检索，能得到带真实坐标与 poiUid 的候选，
   verifyStatus=VERIFIED
3) 用一个特别冷门的目的地（如某个小镇）测试，确认触发 shortage=true 且
   提示文案合理，**没有出现编造的景点**
4) 喂一个忌口"海鲜"，确认候选里没有海鲜类餐饮点
5) 输出两份候选池示例（地图开 / 关各一份）
```

### P3-C ｜空间预排（Step 3）

```
【P3-C · 管线 Step 3：空间预排（纯本地算法）】

【目标】用确定性算法把候选点排成空间上顺的一条线。**这一步绝对不调大模型。**

【为什么要有这一步】
大模型做的是「生成看起来合理的文本」，不是在解带约束的路径优化问题。
把它排的点直接交给大模型分天，经常出现 A→C→F→B 这种空间乱跳。
所以：**空间顺序用算法算，大模型只负责分天、分配时长、写理由。**

【实现 PreOrderService】
输入：List<PoiDTO>（含坐标与无坐标两类）、起点坐标（目的地中心）
输出：PreOrderResult { orderedList, totalDistanceMeters, skippedNoCoord }

算法（三步）：
1) 起点确定：
   - 有目的地中心坐标 → 用它作起点
   - 没有 → 取第一个有坐标的候选点
2) 贪心最近邻（Nearest Neighbor）：
   - 从起点出发，反复选择「距当前点最近的未访问点」
   - 距离用 Haversine 公式计算（自己实现，不要引地理库）
3) 2-opt 局部优化：
   - 反复尝试反转任意子路径 [i, j]，若总里程下降则接受
   - 迭代上限 200 次或连续 20 次无改进即停止（防止大数据集卡死）
   - **必须保证：优化后总里程 ≤ 优化前**（这是正确性断言，测试要验）

【无坐标候选的处理】
地图关闭时所有点都没有坐标 —— 此时**跳过 1)2)3)，保持输入顺序返回**，
并在结果里标记 skippedNoCoord=true。交由 P3-D 让大模型按常识排相对顺序。

【工程要求】
- 独立的 PreOrderService 类，无任何 Spring 依赖注入（纯函数式，便于单测）
- 日志打印：优化前里程 → 优化后里程 → 节省百分比（这个数字 P7 会用来出指标）
- 这个方法 P7 要写单元测试，请一并给出测试类骨架

【不要做】
- 不要调用大模型或地图
- 不要在这里分天（分天是 P3-D 的事）

【验收】
1) 手工构造 8 个坐标点（如正方形 + 中心点），确认：
   - 输出包含全部点，无遗漏无重复
   - 2-opt 后的总里程 ≤ 贪心结果
   - 打印出的节省百分比合理
2) 构造极端用例：所有点无坐标 → 输出保持原顺序，skippedNoCoord=true
3) 构造单点用例 → 不报错，总里程 0
4) 贴出一次真实的「优化前 → 优化后」里程对比日志
```

### P3-D ｜行程编排（Step 4，LLM 主战场）

```
【P3-D · 管线 Step 4：行程编排（大模型）】

【目标】把预排好的候选池 + 意图 + 画像，交给大模型分天、排时段、配餐饮、写理由。

【一、输出 DTO】
TripDraftDTO {
  title: String                     // 建议标题，如"寿阳古建两日慢行"
  days: [
    { dayIndex: 1, title: "古城寻塔", summary: "...",
      items: [
        { poiRef: "候选池中的名称或序号", itemType: "SCENIC",
          startTime: "09:00", endTime: "10:30", stayMinutes: 90,
          costEstimate: 30, reason: "上午光线适合拍照，且离前一站步行 5 分钟" }
      ] }
  ]
}

【二、system prompt 必须包含的硬约束（逐条写进 prompt，不要漏）】
1) 总天数必须**恰好等于** intent.days，不能多也不能少
2) 每天至少 2 个点；活动窗口 09:00–18:00；午餐排在 11:30–13:00；
   晚餐排在 18:00 之后（可超出 18:00 窗口）
3) **只能从给定候选池里选点**，不得新增任何候选池以外的地名。
   每个 item 的 poiRef 必须能在候选池里找到
4) 每个 item 必须写 reason（为什么安排在这一天/这个时段），
   要具体，禁止"因为很值得去"这类空话
5) 【节奏约束】按 intent.pace：
   1 慢 → 每天 2–3 个点 ｜ 2 适中 → 3–4 个 ｜ 3 紧凑 → 4–5 个
6) 【餐饮】每天至少 1 个 FOOD 类型的 item，从候选池的餐饮候选中选
7) 【画像注入】把 ProfileRenderer 的输出整块放进 prompt，并明确写：
   - 「忌口过敏是硬约束，任何餐饮推荐都不得包含这些食材」
   - 「你必须在每天的 summary 里体现出你记住了这些偏好
      （例如：'避开香菜，选了几家本地面食馆'）」
   - 预算倾向为经济时，餐饮与门票要优先选低价的
8) 【地图关闭时，额外加一段】
   - 「你没有坐标数据，请按地理常识给出同一区域内相对合理的先后顺序」
   - 「**禁止输出精确的距离与时长**，如需说明用'相距不远''步行约十几分钟'
      这类模糊表述，写在 reason 里」
   - 「distanceMeters / durationSeconds 字段一律留空（null）」

【三、user prompt 内容】
- 目的地、天数、日期、预算（含口径）、交通方式、同行人
- 候选池（用 P3-B 的 CandidateSerializer 输出）
- 预排顺序（P3-C 的结果，明确告诉模型「这是建议的空间顺序，你可以微调，
  但尽量避免来回折返」）
- 画像块

【四、解析与重试】
- chatJson 拿结果 → JSON 解析 → Schema 校验（天数、item 数、itemType 枚举、
  poiRef 非空、时间格式 HH:mm）
- 校验失败 → 把错误信息回喂重试 1 次
- 仍失败 → 返回 LLM_PARSE_FAIL，但**保留候选池**供前端手选（不要整体失败）
- 写 ai_generation_log（stage=COMPOSE，记录 tokens）

【不要做】
- 不要在这里做闭包校验与预算校验（那是 P3-E）
- 不要让模型输出坐标

【验收】
1) 输入三条测试语句，各跑一次，把完整 TripDraftDTO 贴出来
2) 检查三条硬约束是否真的生效：
   - 天数恰好等于要求
   - 每个 item 的 poiRef 都在候选池里（**不能有池外景点**）
   - 每天都有 FOOD 条目
3) 输入"两天，带爸妈，走不动，想轻松点"→ 确认 pace=1，每天只有 2-3 个点
4) 关闭地图再跑一次 → 确认输出里**没有任何精确公里数或分钟数**，
   全是"相距不远"这类模糊表述，且 distanceMeters 为 null
5) 故意在 prompt 里删掉「只能从候选池选点」这条约束，验证模型是否会编造景点
   —— 把这次对比结果记录下来（P8 写进文档，作为"为什么需要闭包校验"的证据）
```

### P3-E ｜约束校验（Step 5，本地六条规则）

```
【P3-E · 管线 Step 5：约束校验与回喂重排】

【目标】本地校验六条规则，不通过就把违规项回喂给模型重排。**这是防幻觉的第二道闸门。**

【一、ValidationReport】
ValidationReport { passed: boolean, violations: List<Violation> }
Violation { code, severity(HIGH|MEDIUM|LOW), dayIndex, itemRef, message, suggestion }

【二、六条规则（逐条实现，每条一个独立方法便于单测）】
1) CLOSURE 闭包（★最重要）
   每个 item 的 poiRef 必须在候选池中找到（按名称或 uid 匹配，容忍空格与
   全半角差异）。找不到 → HIGH。
   这条规则的唯一使命：**识破大模型编造的景点**
2) TIME_OVERLAP 时序
   同一天内：startTime 严格递增、相邻项不重叠、不超出 09:00–18:00 窗口
   （晚餐项豁免）。违规 → MEDIUM
3) BACKTRACK 折返
   相邻两段无坐标时**跳过**；有坐标时某段距离 > 阈值 → HIGH
   阈值：DRIVE 40km / WALK 5km / RIDING 15km / PUBLIC 40km
4) DETOUR 单日通勤总量
   有坐标时，单日累计通勤时长 DRIVE > 120min 或 WALK > 40min → MEDIUM
5) BUDGET_EXCEED 预算
   所有 item 的 costEstimate 之和 vs（budgetTotal，按 budgetMode 换算）
   超支 10% 以内 → LOW（提示）；超过 10% → MEDIUM
6) TABOO 忌口（★硬约束）
   任何 item 的 poiName / reason / note 中出现 profile.taboos（含 overrides 合并后）
   的任一食材 → HIGH，必须重排
7) TOO_DENSE 点位密度
   按坐标估算当日步行总里程，与 profile.walkLimitKm 比较，超出 → MEDIUM

【三、回喂重排机制】
- 有 HIGH 违规 → 必须重排；只有 MEDIUM/LOW → 根据配置决定（默认也重排一次）
- 重排时构造纠正 prompt，结构如下：
  「上一版行程存在以下问题，请修正后重新输出完整 JSON：」
  + 逐条列出 violation.message 与 suggestion
  + 「特别注意：第 N 天第 M 项 'XXX' 不在候选池中，请从候选池里换一个」
  + 用 <delimiter> 分隔，避免格式错乱
- 最多重排 trip.max-replan-rounds（默认 2）轮
- **达上限仍不通过 → 不抛异常**，返回当前最优版本 + violations 风险提示，
  让前端展示"以下问题未能自动解决"清单
- 每轮重排都要写 ai_generation_log 并累加 trip.generation_rounds
- **必须有硬性轮次上限，绝不允许死循环**（P7 会用超时断言验证）

【不要做】
- 不要在这里调用地图补全距离（那是 P3-F 的 Step 6）
- 不要静默丢弃 violations

【验收】
1) 单元测试六条规则各一个正例 + 一个反例
2) **CLOSURE 专项**：手工在 TripDraft 里塞一个候选池外的景点名
   （如"寿阳天外来客塔"），确认被识别为 HIGH 并触发重排
3) TABOO 专项：画像忌口"香菜"，构造一个 poiName 含"香菜拌面"的 item，
   确认报 HIGH 并重排掉
4) 构造一个必然无法通过的场景（如候选池只有 1 个点但要求 3 天每天 2 点），
   确认 2 轮后返回风险提示而不是死循环或抛异常
5) 输出一次真实的重排日志，能看到第几轮、修掉了什么
```

### P3-F ｜事实补全 + 结果组装 + 同步接口

```
【P3-F · 管线 Step 6/7：事实补全与结果组装 + 同步接口】

【一、Step 6 · enrichRoutes(tripDraft, capability)】
按 mode 分三路：
1) VERIFIED（地图可用）
   - 逐段调路线规划：相邻 item 之间，按 intent.transport 选
     driving / walking / riding / transit
   - **并发上限 4**（用线程池或 CompletableFuture，不要串行等 10 段）
   - 县域交通：transit 若返回无数据 → 自动降级为 driving，
     并在该 item 的 note 写明「该区域无公共交通数据，按自驾估算」
   - 回填 distanceMeters / durationSeconds / transportModeToNext
     并设 verifyStatus=VERIFIED、dataSource=BAIDU
2) CACHED（熔断降级）
   - 命中 Redis 路线缓存的段 → VERIFIED
   - 未命中的段 → ESTIMATED（**保持 null**，不编数字）
3) ESTIMATED（地图关闭）
   - **完全不调地图**
   - 距离与时长一律保持 null；仅从模型输出里取模糊表述写入 note
   - verifyStatus=ESTIMATED、dataSource=LLM
4) 补全完成后**重跑校验规则的 3) BACKTRACK 与 5) BUDGET_EXCEED**；
   若违反 → 回到 Step 3 重排（计入一轮，受 max-replan-rounds 约束）
5) trip.mapMode 按本次实际使用的 mode 落库

【二、Step 7 · 结果组装与落库】
- 落库顺序：trip → trip_day（批量）→ trip_item（批量）
- trip.generationRounds 写入实际轮次，trip.modelName 写实际模型
- trip.profileUsed 写本次是否注入画像
- 返回 TripDraftDTO（每一项都带 verifyStatus 与 dataSource）

【三、同步接口】
POST /api/trip/plan/sync      登录用户
     body { rawInput, useProfile: true|false,
            overrides: { taboos: [], pace: 2 } }
     返回 { tripId, intent, shortage, shortageHint, validation, trip: TripDraftDTO,
            meta: { rounds, mapMode, modelName, profileUsed, durationMs, tokens, estCost } }
     **meta 里的性能指标是 P7 出数据、P8 写文档的依据，务必都返回**

GET  /api/trip/{id}           本人可见，返回完整行程
GET  /api/trip/my             分页，我的行程列表
POST /api/trip/{id}/replan    body { feedback: "第2天太赶" }
     要求：**只重排指定范围**（解析 feedback 定位到 dayIndex），不要全量重算
DELETE /api/trip/{id}         逻辑删除

【四、TripOrchestrator 编排器】
- 用外观模式把 Step1–7 串起来，每个 Step 前后写 ai_generation_log 并记录耗时
- 每步之间做「模式检查」：若 map.enabled 在生成过程中被改了，以当前值为准
  （体现「不重启即生效」）
- 提供一个 orchestrateTiming 对象，累计各步骤耗时，最终放进 meta

【五、画像使用规则（务必实现）】
- 仅当 useProfile=true 且 profile.allowAiUse=1 时才注入
- 否则 profileUsed=false，且**不把画像放进任何 prompt**
- overrides 优先级高于画像（见 P2-A 的合并规则）

【不要做】
- 不要做 SSE（P4 做）
- 不要在业务代码里直接发 HTTP

【验收】
把下面三条逐个跑通并贴出完整 JSON：
  1) 周末想去寿阳玩两天，喜欢古建筑，预算 500
  2) 三天，想去大同看古建和博物馆，不吃辣，一个人，预算 1500
  3) 两天，带爸妈，走不动，想轻松点，想吃面食
然后：
  4) 对第 1 条，在「地图开启」与「地图关闭」两种状态下各跑一次，
     对比 verifyStatus 分布、距离字段是否有值、文案措辞差异，列表对比
  5) 关闭 useProfile 再跑第 3 条，确认 profileUsed=false 且每日点位数变多
     （因为不再受"慢节奏"约束）—— 证明画像注入真实生效
  6) 给出 meta 里的 rounds / mapMode / durationMs / tokens / estCost 实际数值
  7) 数据库里 trip / trip_day / trip_item 数据正确，图片与顺序与响应一致
```

---

## P4 ｜ 流式输出与成本控制

> P4 共 3 块。目标：行程骨架先于文案返回（降级出口），且能感知客户端断开。

### P4-A ｜SSE 通道与事件协议

```
【P4-A · SSE 流式接口与事件协议】

【一、接口】
POST /api/trip/plan/stream    登录用户，返回 text/event-stream
     body 同 /plan/sync

【二、事件协议（严格实现，前端将按此解析）】
event: stage
data: {"stage":"PARSE","status":"RUNNING|DONE|FALLBACK","message":"正在理解你的需求"}
      覆盖 PARSE / CANDIDATE / PREORDER / COMPOSE / VALIDATE / ROUTE 六阶段
      FALLBACK 用于告知前端「地图不可用，已切换为估算模式」

event: itinerary
data: {完整 TripDraftDTO}
      **关键：Step 6 事实补全完成后立即推送这一条。**
      此时前端已可渲染地图、时间轴、费用表。
      这是**降级出口**：之后文案生成失败也不影响用户看到可用的行程

event: delta
data: {"text":"第一天的行程从..."}
      攻略文案的增量文本

event: done
data: {"tripId":123,"rounds":1,"mapMode":"ESTIMATED","durationMs":38200,
       "tokens":11800,"estCost":0.041}

event: error
data: {"code":"LLM_TIMEOUT","message":"文案生成超时，行程已保留，可稍后重试"}
      发完 error 仍要 complete，已生成内容不丢弃

【三、技术实现要求】
- 用 Spring MVC 的 SseEmitter，**禁止引入 WebFlux**
- **必须异步**：SseEmitter + 独立线程池
  （core 4 / max 8 / queue 50 / 线程名前缀 wayfare-trip-sse-）
  绝不能占用 Tomcat 请求线程 —— 一次生成耗时 10~60 秒
- emitter timeout 设 5 分钟
- onTimeout / onCompletion / onError 都要正确清理线程与日志
- 鉴权仍走 JwtInterceptor 的 Authorization 头，**不因流式而放开鉴权**
- 事件格式：每条事件以 "event: xxx\ndata: {...}\n\n" 结尾（两个换行）

【四、客户端断开处理（★ 这是成本控制的关键）】
- 发送时捕获 IOException（客户端关闭连接会抛）
- 一旦捕获，**立即中断大模型流**（调用 chatStream 的中断机制，如关闭
  InputStream / 设置 cancel 标志），不要让它在后台跑完
- 写一条 ai_generation_log，success=0，error_code=CLIENT_DISCONNECTED，
  并记录「已中断，节省 tokens 约 N」（N 用已生成 token 数估算）
- 这个机制 P7 要测：断开后后端必须在数秒内停止生成

【五、前端约定（写进接口文档给 P5 用）】
不要用原生 EventSource（它不能自定义请求头，无法携带 JWT），改用：
  fetch('/api/trip/plan/stream', {
    method:'POST',
    headers:{ 'Authorization': `Bearer ${token}`, 'Content-Type':'application/json' },
    body: JSON.stringify(payload),
    signal: abortController.signal
  })
然后 response.body.getReader() 逐块读取，按 "\n\n" 切分事件块，解析 event: 与 data:

【不要做】
- 不要改 /plan/sync 的签名（必须继续可用）
- 不要写前端页面

【验收】
1) curl -N 调 /plan/stream，能依次看到 stage → itinerary → delta → done 完整事件流
2) 打印每次事件到达的时间戳，证明 **itinerary 明显早于 done**
   （目标：itinerary 约 15s 内到达，done 约 40s 左右）
3) 中途 Ctrl+C 断开 → 后端日志在数秒内出现「客户端断开，已中断生成」
4) ai_generation_log 里有 success=0 且 error_code=CLIENT_DISCONNECTED 的记录
5) 贴出完整的事件流原始输出
```

### P4-B ｜攻略文案生成与服务级缓存

```
【P4-B · 攻略文案流式生成】

【目标】把结构化行程渲染成人读的攻略文案，流式推送。这是"锦上添花"环节，
失败不能影响行程可用性。

【一、实现 generateCopy(tripDraft, intent, profile, onDelta)】
1) prompt 要求：
   - 按天组织，每天一整段，开头一句话点出当天主题
   - 每个点位说清楚：**为什么去**、**看什么**、**大概花多久**、**要花多少钱**
   - 融入用户画像（忌口、预算档、节奏）—— 例如"考虑到你忌香菜，
     这家面馆点单时记得说一声"
   - 【地图关闭时】明确要求：不要写出精确距离与时间，
     用"顺路""不远""慢慢走过去"这类表达
   - 语气：像朋友给的攻略，不要写成官方宣传稿
   - 长度：每天 150–250 字，总长控制在 800 字以内
2) 通过 chatStream 调用，onDelta 里逐个把增量文本用 event: delta 推给前端
3) 失败处理（★）：
   - LLM 超时/限流/报错 → 发 event: error（带具体 code），
     但**行程数据已经在之前的 itinerary 事件里推送过了，不受影响**
   - 同时写 ai_generation_log，stage=COPY，success=0
4) 成功后把文案写回 trip（新增字段 `guide_text` TEXT，加进 schema-trip.sql）
   这样用户重进详情页不用重新生成

【二、文案重试接口】
POST /api/trip/{id}/regenerate-copy   登录用户
     用已落库的行程数据重新生成文案（**不重跑整个管线**），
     同样支持 SSE 或直接同步返回
     这个接口的价值：文案失败后用户可以只重试文案，不用再花一次全量 token

【三、DeepSeek 与 GLM 的文案风格差异（顺带对比）】
- 在 meta 里记录本次文案用的是哪个模型
- 提供一个对比入口：同一行程分别用两个模型生成文案，前端可切换查看
  （这个对比是 P8 文档与答辩的素材：说明你验证过两个厂商的效果差异）

【验收】
1) 完整生成一次，文案流式输出流畅，无卡顿、无乱码
2) 文案里能看到画像被真实使用（忌口、预算档、节奏至少体现一处）
3) 手动制造文案阶段失败（把 Key 改错）→ 收到 error 事件，
   **但前端已收到的 itinerary 数据完整可用**
4) 调 regenerate-copy 只重生成文案，ai_generation_log 里只有 COPY 阶段记录，
   tokens 明显低于全量生成
5) 同一行程用 GLM 与 DeepSeek 各生成一次文案，贴出两段文本做效果对比
```

### P4-C ｜成本与中断的精细化记录

```
【P4-C · 成本核算与中断节省的精确记录】

【目标】让 token 与成本数据可量化 —— 这是简历里"均摊成本 0.03–0.06 元"
这类数字的来源，必须真实可复现。

【一、每阶段 token 归属】
- 每个 stage 单独记 prompt_tokens / completion_tokens
  （PARSE / COMPOSE / COPY 三个 LLM 阶段 + 其他本地阶段记 0）
- 汇总到 trip 级：提供一个查询返回本次生成的
  { stages: [{stage, tokens, durationMs}], totalTokens, estCost }

【二、成本计算】
- 单价从 sys_config 读：llm.price.glm / llm.price.deepseek（元/百万 token）
- 区分输入输出价（若厂商不同价，配置里拆成 price-input / price-output）
- estCost = promptTokens × priceInput/1e6 + completionTokens × priceOutput/1e6
- 精度保留 4 位小数（单次成本是分级数字，2 位不够）
- **不要用硬编码的单价**，全部走配置，这样价格变了不用改代码

【三、中断节省的量化】
- chatStream 被中断时，记录：
  · 已产生的 completion_tokens（若上游返回了 usage）
  · 未能产生但本来会产生的部分 —— 用「同类请求的平均 completion_tokens」估算
- 写入 ai_generation_log.error_msg，格式：
  「客户端断开已中断，已产出 {n} tokens，按均值估算本次节省约 {m} tokens」
- 这个数字 P7 要聚合出「单次中断平均节省约 N tokens」

【四、聚合接口（给后台用，P6 对接）】
GET /api/admin/generation/stats?from=&to=
返回 {
  totalCount, successCount, successRate,
  avgDurationMs, p95DurationMs,
  totalTokens, avgTokensPerTrip, totalEstCost, avgCostPerTrip,
  stageBreakdown: [{stage, avgTokens, avgDurationMs, successRate}],
  mapModeBreakdown: [{mapMode, count, avgDurationMs}],
  topErrors: [{code, count}]
}

【验收】
1) 跑 5 次完整生成，调 stats 接口，返回的数字与 ai_generation_log 手工核对一致
2) 成本计算与实际厂商账单量级相符（差异说明为"厂商计费口径差异"即可）
3) 制造 3 次中断，确认 error_msg 里都有节省估算，并能聚合出平均值
4) 输出一份「一次完整生成的 token 与成本拆解表」（分阶段列出）
```

---

## P5 ｜ 前端：偏好中心与 AI 规划交互

> P5 共 4 块。目标：把后端能力变成用户能用、能看懂、能信任的界面。

### P5-A ｜旅行偏好中心

```
【P5-A · 个人中心「旅行偏好」页签】

【一、路由与文件】
- Profile.vue 内新增 el-tabs 页签「旅行偏好」
- 新增 src/api/profile.js：getTravelProfile / saveTravelProfile

【二、表单字段与控件】
- 喜欢菜系：多选标签（晋菜/川菜/粤菜/淮扬/面食/火锅/烧烤/日料/西餐/家常菜）
  + 支持自定义输入回车添加
- 口味偏好：多选（偏清淡/偏咸/偏辣/微辣/重辣/偏甜/偏酸）
- 忌口与过敏：标签输入框，回车添加，可删除
  **下方粗体标注：「AI 生成餐饮建议时会严格避开这些食材」**
- 旅行风格：多选（古建探访/自然风光/博物馆/市井烟火/摄影旅拍/亲子出行/
  城市漫步/美食之旅）
- 节奏：el-radio-group 单选，每项配说明文字：
  慢「每天 2-3 个点，留足闲逛时间」/ 适中「每天 3-4 个点」/ 紧凑「每天 4-5 个点」
- 预算倾向：单选 经济 / 舒适 / 品质
- 常同行人：单选 独自/情侣/朋友/家庭带娃/带长辈
- 单日步行上限：el-slider 1–20 公里，带实时提示文字
- 住宿偏好：输入框
- 自由备注：textarea
- 【隐私开关】el-switch「允许 AI 生成行程时使用我的偏好」，默认开启，
  下方说明「关闭后 AI 不会读取这些信息，生成的行程将不体现你的个人偏好」

【三、交互细节】
- 保存成功 ElMessage 提示
- 未填写任何字段时保存不报错（允许空画像）
- 页面加载时若有数据则回填，无数据则全空
- 每个字段的 label 旁加 el-tooltip 说明「这项会怎么影响 AI 生成结果」
  （例：步行上限 → 「AI 会据此控制每天的点位密度」）

【验收】
1) 填写全部字段 → 保存 → 刷新 → 数据完整回填
2) 关闭隐私开关 → 保存 → 重新加载 → 开关状态保持关闭
3) 标签类字段支持添加、删除、去重
4) 空表单保存不报错
5) 响应式：手机宽度下表单不溢出
```

### P5-B ｜AI 规划页（四步流程）

```
【P5-B · AI 规划页 /plan（四步流程）】

【一、路由与文件】
- router/index.js 注册 /plan，meta.requiresAuth = true
- 新增 src/api/trip.js：
  planSync / planStream / getTrip / myTrips / replan / regenerateCopy
- planStream 用 fetch + ReadableStream 实现（不要用 EventSource），
  解析逻辑封装成一个独立的 sseParser 工具函数，便于测试

【二、页面用 el-steps 串成四步】

Step 1 · 输入
- 大 textarea，placeholder：「周末想去寿阳玩两天，喜欢古建筑，预算 500」
- 下方「本次将参考的偏好」chips 区：
  · 从 /profile/travel 读取，渲染成可取消的 chips（默认全选）
  · 每取消一个，对应字段不进 overrides
  · 若画像为空 → 显示「你还没有填写旅行偏好，去完善」
  · 若隐私开关关闭 → 显示「已关闭偏好使用」灰色提示，chips 区不展示
- 「本次临时忌口」输入框（覆盖 / 合并长期画像）
- 按钮「开始规划」

Step 2 · 确认参数（★ 这步是关键，别省）
- 先调 /plan/sync 的**解析部分**（或单独提供 /trip/parse 接口）拿到 IntentDTO
- 渲染成可编辑表单：目的地、出发日期、天数、预算（含口径切换 人均/总计）、
  交通方式、同行人、节奏
- 凡是在 needConfirm 数组里的字段，label 旁加橙色 el-tag「AI 猜测，请确认」
- 用户点「确认，开始生成」才发起流式生成请求
- 说明文案：「确认后 AI 会检索真实景点并排布行程，预计需要 30–60 秒」

Step 3 · 生成中
- 顶部六阶段进度条，按 stage 事件点亮：
  理解需求 → 检索景点 → 排布路线 → 校验约束 → 查证距离 → 撰写攻略
- 收到 FALLBACK 状态时，对应阶段显示橙色感叹号 + 提示「地图不可用，已切换估算模式」
- 收到 itinerary 事件后：**立即**切到结果视图渲染行程骨架（不要等 delta）
- 文案区在骨架下方显示打字机效果，边收边渲染
- 显示「本次已参考你的偏好：古建探访 / 慢节奏 / 忌香菜」可见提示
  （数据来自 meta.profileUsed 与 overrides）

Step 4 · 结果与编辑
- 顶部状态条：
  · mapMode=VERIFIED → 绿色「距离与时长为地图实测」
  · mapMode=ESTIMATED → 橙色「未启用地图校验，里程与时间为估算值」
  · mapMode=CACHED → 蓝色「部分数据来自本地缓存」
- 地图区：可用时用百度地图 JS API 打点并从 itinerary 的坐标连线；
  不可用时显示占位提示，不显示空白地图
- 时间轴：按天 el-tabs，每天一张卡片，卡片内按 seq 列出条目
- 每个条目右侧**来源角标**：
  绿「实测」VERIFIED ｜ 灰「缓存」CACHED ｜ 橙「估算」ESTIMATED ｜ 蓝「手动」USER
- 编辑能力：上移/下移（或拖拽）、删除条目、修改停留时长、换一个候选点、
  「这一天太赶」→ 调 /replan 局部重排
- 费用汇总区：门票 + 餐饮 + 住宿 + 交通，估算部分显式标注
- 底部按钮：「保存草稿」「发布为攻略」「重新生成」
- 发布：复用 P0 的发布链路，把行程摘要 + 文案 + 多图作为 work 内容提交，
  成功后把返回的 workId 写回 trip（调一个 PUT /trip/{id}/publish）

【三、错误与降级可见性】
- LLM 超时 → 「攻略文案生成失败，行程已保存，可点击重试生成文案」（行程不清空）
- 候选不足 → 「该目的地可玩点位较少，已为你放宽范围」+ shortageHint 原文
- 校验未通过 → 顶部黄条列出未解决的问题条目

【验收】
1) 填写偏好 → 规划页 chips 正确展示 → 取消某项后确认它没进请求
2) 输入示例句 → 四步走完 → 结果页正确渲染行程、角标、地图或降级提示
3) 关闭地图连接器再跑一遍 → 出现橙色估算提示条，所有距离显示为「估算」
4) 编辑排序后保存，刷新页面顺序保持
5) 发布为攻略后，首页能看到，trip.work_id 正确关联
6) 生成中途点「取消」→ 请求被 abort，后端日志显示中断
7) 手机宽度下布局不破
```

### P5-C ｜首页与详情页改造

```
【P5-C · 首页与攻略详情页】

【一、首页 Home.vue】
- 顶部搜索区：目的地输入框 + 天数筛选（1天/2天/3天/更多）+ 分类筛选
- 卡片信息改为攻略维度：封面图 / 标题 / 目的地 / 天数 / 预算区间 / 作者 / 点赞收藏
- 「AI 生成」的攻略在卡片右上角加一个小角标（数据来自 trip 关联），
  让用户能区分人工原创与 AI 辅助生成 —— 这是内容诚信，也是差异化卖点
- 保留热门攻略、热门标签区块
- 无 AI 生成内容时，区块不显示空白，给出引导入口「试试 AI 规划你的行程」

【二、攻略详情 WorkDetail.vue】
- 新增「完整行程」区块，位置在正文之后：
  · 若有 trip 关联 → 按天渲染时间轴（只读，复用 P5-B 的时间轴组件）
  · 显示来源角标与 mapMode 提示条
  · 显示「本次生成使用模型：glm-4-flash」这类透明度信息
  · 无 trip 关联（纯图文攻略）→ 不显示该区块
- 把时间轴组件抽成独立组件 src/components/TripTimeline.vue，
  P5-B 与详情页共用（只读模式传 prop）

【三、行程公开/私密控制】
- trip 默认仅本人可见
- 发布为攻略时，行程随之公开
- 详情页只展示已发布（work.status=1）的行程

【验收】
1) 首页按目的地/天数筛选生效
2) 详情页能看到完整时间轴，角标正确
3) 未发布的 trip 通过 /trip/{id} 访问，非本人返回 403
4) 纯图文攻略（无 trip）详情页不显示行程区块，且不报错
5) 响应式正常
```

### P5-D ｜前端体验收尾

```
【P5-D · 前端体验收尾】

【一、全局加载与错误处理】
- 所有网络请求统一 loading 状态（请求拦截器里维护一个计数器）
- 生成过程禁止误刷新丢失：若生成中途用户尝试离开页面，用
  beforeunload 或路由守卫弹确认框
- 全局错误提示统一用 ElMessage，避免各页面各写一套

【二、空状态与引导】
- 我的行程为空 → 引导去规划页
- 偏好未填写 → 规划页顶部提示条
- 未配大模型 Key 时（后端返回 LLM 不可用）→ 提示管理员配置，
  而不是显示"生成失败"这种无信息量的错误

【三、性能】
- 图片懒加载
- 瀑布流无限滚动
- 时间轴长列表做虚拟滚动或用分天 tabs 控制单次渲染量

【四、无障碍与响应式】
- 移动端：导航折叠成汉堡菜单，规划页步骤条改纵向
- 地图关闭时时间轴占满宽度（不留地图空白区）

【验收】
1) 全部页面在 375px 宽度下可用
2) 生成中途关闭页面弹出确认
3) 空状态都有引导文案
4) 输出一份「界面清单 + 每个界面的关键交互」表
```

---

## P6 ｜ 后台管理：连接器开关与 AI 监控

> P6 共 2 块。这一块在答辩里性价比极高：**能现场演示「一键关掉地图，系统照样跑」**。

### P6-A ｜连接器管理页

```
【P6-A · 后台连接器管理页 /admin/connectors】

【一、大模型区块】
- 当前使用厂商：el-radio-group（GLM / DeepSeek / Mock），改动即写 sys_config
- 降级顺序：可拖拽排序或逗号输入（默认 glm,deepseek）
- 每个厂商一张卡片，展示：
  · 模型名（可编辑，如 glm-4-flash / glm-4-plus / deepseek-chat）
  · Base URL（只读展示）
  · API Key：**掩码显示，只回显前 4 位 + ******，输入框留空表示不修改
  · 状态：已配置 / 未配置 / 最近一次调用成功时间 / 最近失败原因
  · 按钮「测试连通性」→ 调 /diagnostics/llm/ping
- 顶部状态条：当前生效的 provider、model、是否处于 fallback、今日调用次数与 token

【二、百度地图区块】
- 总开关 el-switch（对应 sys_config map.enabled）—— **这是答辩要演示的按钮**
- AK 掩码输入
- 当前状态卡片：可用性 / 当前模式（VERIFIED|CACHED|ESTIMATED）/
  熔断是否打开 / 近 1 小时调用次数与失败率 / 最近成功时间与失败原因
- 按钮「测试连通性」→ 调 /diagnostics/map/ping，返回耗时与命中数
- **【演示按钮】「一键降级演练」**：关闭地图 → 弹出提示
  「已切换为估算模式，后续生成的行程将标注为估算值，系统功能不受影响」

【三、行程参数区块】
- trip.max-days / trip.max-candidate / trip.max-replan-rounds
- trip.poi-cache-ttl-hours
- 熔断参数：fail-threshold / open-seconds
- 单价配置：llm.price.glm / llm.price.deepseek（元/百万 token）

【四、所有改动必须「不重启即生效」】
- 后端通过 SysConfigService.set 写库 + 清 Redis 缓存
- 前端保存后立即刷新状态卡片，无需重启

【不要做】
- 任何位置不得回显完整 Key
- 不要改前台功能

【验收】
1) 在页面上关闭地图 → 前台立即（不重启）生成一次行程 → 显示估算模式
2) 切换 active-provider 从 GLM 到 DeepSeek → 生成一次行程，
   数据库 trip.model_name 变成 deepseek-chat
3) 故意填错 AK → 状态卡片显示失败原因，连续 5 次后显示熔断已打开
4) 检查页面源码与接口响应，确认没有任何完整 Key
5) 一键降级演练按钮可用且有明确反馈
```

### P6-B ｜AI 生成监控看板

```
【P6-B · AI 生成监控看板 /admin/generation】

对接 P4-C 的 /admin/generation/stats 接口。

【一、顶部统计卡片】
- 今日生成次数 / 成功率 / 平均耗时 / P95 耗时
- 今日总 token / 平均单次 token
- 今日总成本 / 平均单次成本（预估，四舍五入到分）

【二、图表区（用 ECharts，此时才引入）】
- 近 7 天生成次数与成本的折线图（双 Y 轴）
- 各阶段平均耗时占比（饼图或横向柱状图）：
  PARSE / CANDIDATE / PREORDER / COMPOSE / VALIDATE / ROUTE / COPY
- mapMode 分布（VERIFIED / CACHED / ESTIMATED 占比）——
  这张图能直观说明降级体系的运行情况
- 失败原因 Top 5（按 error_code 聚合的横向柱状图）

【三、明细列表】
- 分页表格：时间 / 用户 / 目的地 / 天数 / 阶段 / 模型 / 耗时 / token / 成功与否
- 筛选：时间范围、是否成功、目的地、model、mapMode
- 每行可展开，展示该 trip 的各阶段耗时与失败点
  （数据来自 ai_generation_log 与 external_call_log）

【四、外部调用日志页签】
- connector（LLM / BAIDU_MAP）切换
- 表格展示 api_name / http_status / duration_ms / success / request_summary
- **确认 request_summary 里看不到完整密钥**（这是安全验收点）

【不要做】
- 不要为图表引入 ECharts 之外的库
- 不要把 AI 生成日志做成可删除的（日志只追加）

【验收】
1) 跑 5 次生成后，看板所有数字与数据库手工统计一致
2) 阶段耗时饼图能看出 COMPOSE 与 COPY 是耗时大头（预期结论）
3) mapMode 分布图能体现你做过降级演练（有 ESTIMATED 记录）
4) 外部调用日志里所有密钥都是掩码状态
5) 筛选与分页正常
```

---

## P7 ｜ 测试、指标埋点与降级演练

> P7 共 3 块。**这一节直接产出简历里那些数字，也是答辩时最硬的证据。**

### P7-A ｜单元测试与集成测试

```
【P7-A · 单元测试与集成测试】

【一、单元测试（JUnit 5 + Mockito）】
1) PreOrderService：
   - 固定坐标集（8 个点），断言输出不遗漏不重复
   - 断言 2-opt 后总里程 ≤ 贪心结果
   - 断言起点为最近邻序列的合理性
   - 无坐标输入 → 保持原序，skippedNoCoord=true
   - 单点输入 → 不报错
   **用固定数据不用随机，保证可重复**
2) TripValidator：六条规则各一正一反，重点：
   - CLOSURE 能识破候选池外的景点名（含全半角/空格差异的容错）
   - TABOO 能命中 poiName 与 reason 中的忌口词
3) IntentSchemaValidator：非法 JSON / 缺必填 / 枚举越界 三类都要拦住
4) BudgetCalculator：人均 与 总计 两种口径各一组用例
5) MaskUtil：密钥脱敏正确
6) ProfileRenderer：空字段省略 / overrides 合并与覆盖 / 全空返回空串

【二、集成测试（@SpringBootTest + MockMvc）】
用 @MockBean 替换 LlmProvider 与 MapProvider，覆盖七类场景：
1) 正常流程（地图可用）→ verifyStatus 全 VERIFIED
2) 地图关闭 → 全 ESTIMATED，接口 200，流程不报错
3) 地图连续失败触发熔断 → 自动降级，诊断接口 breakerOpen=true
4) 候选不足 → 返回 shortage 标记，**响应中不存在候选池外的景点**
5) 校验 2 轮仍不通过 → 返回风险提示，**用 @Timeout 断言不死循环**
6) 大模型超时 → 收到 error 事件，已推送的 itinerary 数据仍可取到
7) 忌口命中 → 校验必须报 TABOO
8) 双厂商切换 → active-provider 改后，下一次调用的 provider 立即变化

【三、密钥安全测试】
- 断言 /diagnostics/* 与 /admin/* 的响应里不含完整 Key（正则匹配 sk- 开头长串）
- 断言 external_call_log.request_summary 已脱敏

【验收】
1) mvn test 全绿
2) 给出重点类覆盖率报告（目标 > 60%，PreOrderService / TripValidator 应 > 85%）
3) 输出测试用例表：测试项 / 输入 / 期望 / 实际 / 结论
4) 单独一节列「已发现的真实缺陷及修复」（有就写，没有就写"未发现"）
```

### P7-B ｜量化指标采集

```
【P7-B · 量化指标采集（简历数字的来源）】

目标：跑出一套**可复现**的指标数据，用于简历与论文。每一项都要给出测量方法。

【一、2-opt 优化效果】
- 方法：准备 5 组测试数据（6–14 个点，含不同地理分布），
  分别记录「贪心结果总里程」与「2-opt 后总里程」
- 输出：每组节省百分比 + 平均节省百分比
- 写入 ai_generation_log 或独立表 preorder_metrics，便于后台展示
- **简历口径**：写成「行程总里程再降约 X%」——
  X 必须来自你自己的实测数据，不要抄任何外部数字

【二、多级缓存对响应时间的影响】
- 方法：对同一组 POI 检索，第一次（冷启动，落库 + Redis）与第二次（命中缓存）
  各测 20 次，取平均值与 P95
- 输出：冷调用 vs 热调用的耗时对比
- **简历口径**：「POI 缓存命中响应从约 X ms 降至 Y ms 内」

【三、校验收敛率】
- 方法：跑 30 次真实生成（可自动化脚本循环，用 mock 也行），统计：
  · 第 1 轮通过次数
  · 第 2 轮通过次数
  · 2 轮仍不通过次数
  · 平均重排轮次
- 输出：两轮内收敛率 =（第1轮 + 第2轮通过）/ 总次数
- **简历口径**：「两轮内收敛率约 Z%」
- 注意：要有 2 轮仍不通的样本才真实，全是 100% 反而可疑

【四、流式输出的时间对比】
- 方法：记录 5 次生成中，itinerary 事件到达时间与 done 事件到达时间
- 输出：骨架先返回的平均时间 vs 全文完成的平均时间
- **简历口径**：「行程骨架约 Xs 先于文案返回（全文约 Ys）」

【五、中断节省 tokens】
- 方法：在 delta 阶段的不同时点（接收 20% / 50% / 80% 文案时）各中断 3 次
- 输出：每次已产出 tokens 与估算节省 tokens，取平均
- **简历口径**：「客户端断开即中断 LLM 流，单次中断平均节省约 N tokens」

【六、单次生成成本】
- 方法：分别用 GLM-4-Flash / GLM-4-Plus / DeepSeek-Chat 各跑 5 次完整生成
- 输出：三种模型的平均 tokens 与平均成本（用 P4-C 的算法）
- **简历口径**：「单次生成约 M tokens、均摊成本 A–B 元」
  其中 A 是 GLM-4-Flash 的量级，B 是 DeepSeek 或 GLM-4-Plus 的量级

【输出要求】
产出一份 `metrics.md`，包含：
- 每个指标的测量方法（可复现的操作步骤）
- 原始数据表
- 结论与口径建议（明确写出"简历里可以怎么写"）
- 每个数字的测量日期与环境（模型版本、是否配 Key），避免答辩时被问倒

【不要做】
- 不要编造任何数字
- 数字不好看就如实写，并说明瓶颈在哪 —— 诚实比漂亮更有说服力
```

### P7-C ｜端到端降级演练脚本

```
【P7-C · 端到端降级演练脚本】

目标：一条命令跑完「降级 → 验证 → 恢复 → 验证 → 出报告」全流程，
产出一张能直接放进答辩 PPT 的对比表。

【一、脚本 scripts/drill-fallback.ps1（Windows）+ drill-fallback.sh（可选）】
步骤：
1) 调 /admin/config/map/enabled 关闭地图，断言诊断接口 mode=ESTIMATED
2) 跑一次完整生成（用固定的测试输入），保存结果 A
   断言：所有 trip_item.verifyStatus == ESTIMATED
   断言：所有 distanceMeters == null
   断言：meta.mapMode == "ESTIMATED"
   断言：响应中没有出现任何"公里""分钟"的精确数字（正则校验）
3) 打开地图，断言 mode 变为 VERIFIED（或配置了 AK 时）
4) 再跑一次同样的输入，保存结果 B
   断言：存在 verifyStatus == VERIFIED 的条目
5) 恢复初始状态
6) 输出对比报告 report.md：

   | 维度 | 地图关闭（估算模式） | 地图开启（实测模式） |
   |---|---|---|
   | 数据来源标记 | ESTIMATED × N | VERIFIED × M |
   | 距离字段 | 全部为空 | 全部有值 |
   | 文案距离表述 | 模糊（"相距不远"） | 精确（"约 3.2 公里"） |
   | 生成耗时 | Xs | Ys |
   | 是否可用 | ✔ 完整可用 | ✔ 完整可用 |

【二、故障注入（可选但强烈建议）】
- 用一个 Mock MapProvider 让它固定抛异常，模拟「百度地图服务挂了」
- 断言：连续 5 次失败后熔断打开，系统自动切到 CACHED 或 ESTIMATED，
  生成流程**不中断**
- 把这段录屏或截图，是答辩"容错设计"章节最有力的素材

【三、演练不通过时的处理】
- 任何断言失败都要明确报出是哪条断言、实际值是什么
- 不要吞掉失败继续跑

【验收】
1) 脚本能一键跑通，输出 report.md 与终端彩色结果
2) 报告表格能直接复制进 PPT
3) 断言失败时能明确指认问题
4) 把脚本与报告一起放进仓库，README 里有使用说明
```

---

## P8 ｜ 文档同步与部署

> P8 共 2 块。

### P8-A ｜文档与代码对齐

```
【P8-A · 文档与代码强制对齐】

逐项核对，**不允许文档里出现一句代码里没有的能力**。

【一、必须产出的文档】
1) README.md
   - 项目简介（Wayfare / 行走集）、功能清单、技术栈
   - 快速开始（数据库 → Redis → 后端 → 前端）
   - 接口清单（自动从 Controller 整理，不要手写）
   - 表清单（从 schema 脚本整理）
   - Redis key 设计
   - 环境变量说明
   - 测试账号
2) Wayfare开发文档.md
   - 把《开发文档-AI旅游攻略平台.md》更新为 Wayfare 版本：
     架构图、7 步管线、三层开关、表结构、接口清单、SSE 协议、
     降级矩阵、可观测性、已知局限
   - 命名全部替换为 wayfare / com.wayfare
   - 双厂商（GLM / DeepSeek）接入说明独立一节
3) 部署说明.md
   - 环境要求
   - GLM / DeepSeek / 百度地图 AK 三个 Key 的申请与配置步骤
   - 如何关闭百度地图、如何使用 mock 模式（无 Key 也能演示）
   - 常见故障排查表
4) metrics.md（来自 P7-B）
5) 交付说明.md
   - 功能对照表（功能 → 实现位置 → 验证方式）
   - 已知局限与未完成项（诚实列出）
   - 后续扩展方向

【二、一致性校验（必须真的执行，不要口头承诺）】
逐条检查并给出检查结果：
- [ ] 文档里提到的每个接口路径，都能在 Controller 里找到
- [ ] 文档里提到的每张表，都在 schema 脚本里存在
- [ ] 文档里提到的每个配置项，都在 application.yml 或 sys_config 里存在
- [ ] grep -r "photoshare\|photo-share" . 结果为空
- [ ] README 里的启动命令实际执行通过
- [ ] 技术栈版本与 pom.xml / package.json 一致（这是最容易出错的地方）

【不要做】
- 不要为了"好看"而描写未实现的功能
- 不要把 v1.0 手册里的内容原样复制（那是改造版，本版是从零建）

【验收】
1) 五项一致性检查逐条给出结论与证据（grep 结果 / 文件路径）
2) 文档里所有接口都能在代码里找到对应实现
3) README 的快速开始步骤，在干净环境下能跑通
```

### P8-B ｜Docker 部署

```
【P8-B · Docker 容器化部署】

【一、后端 Dockerfile（多阶段构建）】
- 阶段 1：maven:3.9-eclipse-temurin-17 构建，先 COPY pom.xml 下载依赖（利用缓存），
  再 COPY src 编译打包
- 阶段 2：eclipse-temurin:17-jre 运行，非 root 用户，暴露 8080
- 健康检查：HEALTHCHECK 轮询 /api/health

【二、前端 Dockerfile】
- 阶段 1：node:20-alpine 构建（npm ci + npm run build）
- 阶段 2：nginx:alpine 托管 dist，nginx.conf 里配置
  · try_files 支持 history 路由
  · /api 反代到 backend:8080
  · 上传文件目录通过 volume 挂载

【三、docker-compose.yml】
services：
- mysql:8.0
  · 初始化挂载 schema.sql 与 schema-trip.sql 到 /docker-entrypoint-initdb.d
  · 数据卷持久化
- redis:7-alpine
- backend：依赖 mysql + redis 健康后启动，环境变量注入三个 Key
- frontend：依赖 backend

要求：
- 环境变量通过 .env 注入，compose 文件里**不出现任何明文密钥**
- 提供 .env.example，逐项注释说明：
  MYSQL_ROOT_PASSWORD / GLM_API_KEY / DEEPSEEK_API_KEY /
  BAIDU_MAP_AK / BAIDU_MAP_JS_AK / LLM_ACTIVE_PROVIDER
- 提供 .gitignore，确保 .env / uploads / target / node_modules 不入库

【四、部署文档】
- 服务器环境要求
- 一键启动：docker compose up -d
- 首次启动后需要做的事（导入初始数据、后台配置 Key）
- 日志查看、数据备份、升级流程
- 常见问题：MySQL 初始化失败、端口占用、Key 未生效

【验收】
1) 在干净环境执行 docker compose up -d，四个服务全部健康
2) 浏览器访问前端能登录、能生成行程
3) 用 .env 里的 Key 生效（改 Key 重启后生效）
4) 仓库里没有 .env，只有 .env.example
5) 关闭地图容器外的配置（sys_config 开关）仍能演示降级
6) 输出部署文档与「首次部署检查清单」
```

---

## 7. 三条硬规则

> 建议在 P3 / P4 / P5 每块提示词后面**再重复贴一次**。这三条是本项目区别于"调个 API 就完事"的关键。

### 规则一：事实数据永不来自大模型

| 数据 | 允许来源 | 地图关闭时 |
|---|---|---|
| 经纬度、POI uid、地址 | 地图连接器 | **留空**，不填假值 |
| 距离、时长 | 地图连接器 | 标记 `ESTIMATED`，**禁止精确数值** |
| 门票价格、评分 | 地图详情接口（可能为 null → 显示"未知"）或用户手填 | 同左 |
| 景点名称 | 候选池闭包（CLOSURE 强校验） | 同左 |
| 分天、时长分配、讲解文案 | 大模型 | 同左 |

**判定方式**：任何进了 `trip_item` 的事实字段，都必须有 `verify_status` 与 `data_source` 标记。没有标记的字段视为设计缺陷。

### 规则二：三层开关，任一时刻系统都完整可用

```
L1 启动层   application.yml + LlmProperties / MapProperties
L2 运行层   sys_config + Redis（30 秒缓存，改完不重启即生效）
L3 降级层   熔断器 + 分级 Provider + 多厂商 fallback
            GLM → DeepSeek → Mock
            BaiduMap → LocalCache → Disabled
```

- 默认 `map.enabled=false`、`llm.active-provider=glm`。**没有任何 Key 也能全功能跑通**（靠 mock + 地图关闭）。
- 降级必须是**能力降级**（切换数据来源），不是**功能降级**（告诉用户"做不了"）。

### 规则三：画像注入是"增强"，不是"必需"

- 只注入结构化字段（经 `ProfileRenderer` 渲染），不注入自由文本。
- 用户关掉隐私开关 → 不注入，且 UI 上明确告知「本次未使用你的偏好」。
- 前端临时 `overrides` 优先级高于长期画像；`taboos` 是**合并**语义，`pace` 是**覆盖**语义。
- **忌口/过敏是硬约束**：命中即校验失败，必须重排。

---

## 8. 简历视角：三个可量化亮点的落地要求

> 你简历里那三条写得不错，但每个数字都必须能被验证，否则面试当场就会露。下面把"怎么写"翻译成"怎么做"。

### 亮点一：7 步管线 + 本地算法排序

| 简历表述 | 代码里必须存在的东西 | 验证方式 |
|---|---|---|
| 「7 步 AI 行程编排管线」 | `TripOrchestrator` 里能数出 7 个 step，每步有独立方法 | 读代码 + 日志里 7 个 stage |
| 「LLM 语义决策 + 地图 API 事实数据」 | `LlmProvider` 与 `MapProvider` 两个接口，业务层只依赖接口 | 说明规则一 |
| 「贪心最近邻 + 2-opt，总里程再降约 10%」 | `PreOrderService` 里有这两个算法；P7-B 有 5 组实测数据 | 演示对比日志与 metrics.md |
| 「六条本地校验规则 + 自动回喂重排」 | `TripValidator` 六个方法；`Violation.code` 六个取值 | 单测 + 人为制造违规 |
| 「两轮内收敛率约 95%」 | P7-B 的 30 次统计有原始数据表 | 出示原始数据 |
| 「杜绝死循环」 | `max-replan-rounds` 硬上限 + `@Timeout` 测试 | 跑那个必然失败的用例 |

> ⚠️ 「约 10%」这个数字你自己测出来是多少就写多少。不同地理分布的数据集结果差异很大，写一个自己没测过的数字是最容易被追问的。

### 亮点二：多级降级容错

| 简历表述 | 代码里必须存在的东西 |
|---|---|
| 「可插拔连接器层」 | `LlmProvider` / `MapProvider` 两个接口 + 多个实现类 |
| 「三层开关降级」 | `application.yml`（L1）+ `sys_config`+Redis（L2）+ 熔断（L3） |
| 「超时指数退避重试」 | `ExternalHttpClient` 的 200ms/600ms 退避，且仅 GET |
| 「多级缓存（POI 缓存命中响应从 400ms 降至 10ms 内）」 | P1-D 的三级缓存 key；P7-B 的冷热对比数据 |
| 「密钥脱敏」 | `MaskUtil` + 后台只回显掩码 + P7-A 的正则断言测试 |
| 「自建估算模式」 | `ESTIMATED` 模式 + `verify_status` 标记 + 强制模糊表述 |
| 「故障注入演练下熔断后仍全功能可用」 | P7-C 的演练脚本 + 报告 |

> ⚠️ 加一句「**双厂商大模型热切换**（GLM / DeepSeek，配置改动不重启生效）」会是这条的一个新加分点，而且 P1-B 已经天然支持。

### 亮点三：SSE 流式与成本控制

| 简历表述 | 代码里必须存在的东西 |
|---|---|
| 「SseEmitter + 独立线程池」 | `ThreadPoolTaskExecutor` 配置 + 线程名前缀 `wayfare-trip-sse-` |
| 「自定义事件协议」 | stage / itinerary / delta / done / error 五种事件 |
| 「行程骨架约 15s 先于文案返回（全文约 40s）」 | P7-B 的时间戳实测记录 |
| 「客户端断开即中断 LLM 流」 | IOException 捕获 + 取消标志 + 中断日志 |
| 「单次中断平均节省约 1200 tokens」 | P7-B 的三档中断测试 + 聚合计算 |
| 「全链路 Token 日志落库」 | `ai_generation_log` 的 tokens 字段 + 每 stage 一条 |
| 「单次约 1.2 万 tokens、均摊成本 0.03–0.06 元」 | P4-C 的成本算法 + P7-B 三模型对比数据 |

> ⚠️ 「0.03–0.06 元」这个区间看起来像是 GLM-4-Plus 或 DeepSeek 的量级。**建议明确写出是哪个模型的均价**，比如「DeepSeek-Chat 单次约 0.04 元 / GLM-4-Flash 约 0.002 元」——把两个量级都放上去，反而更能说明你做过成本对比，而且显得你懂"选型要算账"。

---

## 9. Agent 崩了怎么办

### 9.1 症状与对症处理

| 症状 | 原因 | 处理 |
|---|---|---|
| 开始重写整个类 / 文件 | 上下文太长，丢了「增量优先」 | 中止，开新会话，重贴 G + C + 该任务块，并加「只允许新增文件 X/Y，只允许修改 Z 的第 N 行附近」 |
| 代码带「...省略」 | 单次输出预算不够 | 回：「分多次输出，先给文件清单 + 第 1 个文件，我说继续再给下一个」 |
| 编造不存在的接口 / 表 / 字段 | 没读项目文件 | 中止，让它先执行 C 块的「读文件并复述」，核对无误再继续 |
| 用回 `com.photoshare` 包名 | 从旧手册复制了代码 | 指出并让它全局替换，验收时跑 `grep -r "photoshare" .` |
| 两个厂商混在一起写 | 没理解工厂 + 基类结构 | 让它先画类图再写码，强调「基类放公共逻辑，子类只放差异」 |
| 把地图关闭当成异常抛 | 没理解降级是设计的一部分 | 指出并让它读 P1-C 的决策器定义 |
| 卡在同一个错误反复试 | 调试死循环 | 中止，让它输出「报错全文 + 3 个假设 + 需要我提供什么」，补信息后开新会话 |
| 一个任务块做了 20 分钟没结果 | 粒度还是太大 | 按「接口 → 实现 → 测试」再拆成子会话 |

### 9.2 保险措施

1. **每个任务块开始前 `git commit`**，通过后再 commit。崩了能干净回退。
2. **P1-B / P1-C / P3-D / P3-E 是最容易崩的四块**，务必单块投喂，不要合并。
3. **急救句**（随时可用）：

```
停。先不要写代码，只回答三个问题：
1) 你现在认为本任务块的目标是什么？（一句话）
2) 你打算新增哪些文件、修改哪些文件？（清单）
3) 你认为最不确定的一点是什么？
我确认后你再写。
```

---

## 10. 总验收清单

**P0 骨架**
- [ ] 后端启动、health 通、Swagger 可访问
- [ ] 14 张内容域表建好，实体映射正常
- [ ] 3 图发布 → work_image 有 3 条 → 详情页轮播 3 张
- [ ] 分类来自接口，未登录调管理接口返回 401
- [ ] 前端全套页面可用（登录/首页/详情/发布/个人中心/私信）

**P1 连接器**
- [ ] GLM 与 DeepSeek 均能 ping 通，切换不重启即生效
- [ ] 双 Key 都不配时自动回落 mock，全链路可跑
- [ ] 地图可整体关闭，关闭后 mode=ESTIMATED
- [ ] 错 AK 连续 5 次触发熔断，breakerOpen=true
- [ ] 重试仅 GET、POST 不重试
- [ ] 缓存命中生效；密钥全程掩码

**P2 数据层**
- [ ] 画像表、行程三表、缓存表、两张日志表、sys_config 全部就绪
- [ ] ProfileRenderer 空字段省略、overrides 合并/覆盖正确

**P3 管线**
- [ ] 三条测试输入均跑通，能贴出完整 JSON
- [ ] CLOSURE 能拦下编造景点（有测试证据）
- [ ] TABOO 能拦下忌口
- [ ] 预算超支能识别
- [ ] 2 轮收敛，不死循环
- [ ] **地图开 / 关两种模式对比数据齐全**
- [ ] `profileUsed` 开关真实影响输出

**P4 流式**
- [ ] stage → itinerary → delta → done 完整
- [ ] itinerary 明显早于 done（有时间戳证据）
- [ ] 断开后数秒内中断生成，日志有记录
- [ ] 文案失败不影响行程可用
- [ ] 成本按 stage 拆解，数字与日志一致

**P5 前端**
- [ ] 偏好表单完整可用，隐私开关生效
- [ ] 四步流程走通，needConfirm 提示可见
- [ ] 结果页角标正确，降级提示明确
- [ ] 编辑排序保存后保持
- [ ] 发布为攻略后首页可见，trip.work_id 关联正确
- [ ] 375px 宽度可用

**P6 后台**
- [ ] 连接器页可开关地图、切换厂商，均不重启生效
- [ ] 状态卡片含熔断状态与失败率
- [ ] 监控看板数字与数据库一致
- [ ] 页面与接口无完整 Key

**P7 测试与指标**
- [ ] mvn test 全绿，重点类覆盖率 > 60%
- [ ] metrics.md 有六项指标的原始数据
- [ ] 降级演练脚本一键跑通，产出的对比表可进 PPT
- [ ] 故障注入演练有记录

**P8 文档与部署**
- [ ] 五项一致性检查全部通过（含 grep 无旧包名）
- [ ] README 快速开始在干净环境可跑通
- [ ] docker compose up 一键起全套服务
- [ ] 仓库无 .env，有 .env.example
- [ ] 简历里的每个数字都能在 metrics.md 里找到出处

---

## 附：任务块索引（便于排期）

| 块 | 名称 | 预估工作量 |
|---|---|---|
| P0-A | 仓库骨架 | 小 |
| P0-B | 数据库与内容域实体 | 中 |
| P0-C | 鉴权 · 文件 · 内容域接口 | **大** |
| P0-D | 前端内容域页面 | **大** |
| P1-A | 配置与运行时开关 | 中 |
| P1-B | GLM + DeepSeek 双厂商 | **大** |
| P1-C | 百度地图 + 三级降级 | **大** |
| P1-D | 出站治理 | 中 |
| P1-E | 诊断接口 | 小 |
| P2-A | 用户旅行偏好 | 中 |
| P2-B | 行程表族 | 中 |
| P2-C | 日志与缓存表 | 中 |
| P3-A | 意图解析 | 中 |
| P3-B | 候选检索 | **大** |
| P3-C | 空间预排 | 中 |
| P3-D | 行程编排 | **大** |
| P3-E | 约束校验 | **大** |
| P3-F | 补全 · 组装 · 同步接口 | **大** |
| P4-A | SSE 通道 | **大** |
| P4-B | 文案流式 | 中 |
| P4-C | 成本与中断记录 | 中 |
| P5-A | 偏好中心 | 中 |
| P5-B | AI 规划四步 | **大** |
| P5-C | 首页与详情页 | 中 |
| P5-D | 体验收尾 | 小 |
| P6-A | 连接器管理页 | 中 |
| P6-B | AI 监控看板 | 中 |
| P7-A | 单测与集成测试 | **大** |
| P7-B | 指标采集 | 中 |
| P7-C | 降级演练脚本 | 中 |
| P8-A | 文档对齐 | 中 |
| P8-B | Docker 部署 | 中 |

> 共 32 个任务块。**每次投喂一块，跑通再下一块。** 急于合并是崩溃的唯一原因。

---

*手册结束。配套文档：《Wayfare 开发文档》（架构与设计说明）。*
*所有数字（里程降幅、收敛率、成本）必须来自 P7-B 的实测数据，禁止编造。*
