# Wayfare · 文件迁移清单与目标目录结构

> 用途：把 `毕设/` 里的内容搬到新文件夹 `Wayfare/` 时，按本清单执行
> 原则：**只搬「有复用价值」的**，编译产物、依赖、日志、IDE 配置一律不搬
> 自动化：`Init-Wayfare.ps1` 已实现本清单第 1～3 步（见[第五章](#五执行顺序)）
> 日期：2026-09-19

---

## 一、迁移决策总表

按「搬 / 不搬 / 搬后改」三分类，逐项过一遍：

| 源路径 | 目标位置 | 处理方式 | 原因 |
|---|---|---|---|
| `README.md` | **不搬** | 丢弃 | 面向摄影平台，P8-A 会重写 |
| `schema.sql` | `db/schema.sql` | **搬 + 重命名表** | 14 张表结构可复用；表名不动但要改库名 |
| `需求分析文档.md` | `docs/refs/需求分析文档-摄影平台版.md` | **搬（仅参考）** | 社交域需求的原始依据，答辩可引 |
| `技术文档-学习版.md` | `docs/refs/技术文档-学习版.md` | **搬（仅参考）** | 技术选型的学习笔记，有复用价值 |
| `提示词.md` | **不搬** | 丢弃 | 旧版 7 阶段提示词，已被取代 |
| `重构提示词-AI旅游攻略平台.md` | **不搬** | 丢弃 | v1.0 手册，已被 v2.0 取代 |
| `开发文档-AI旅游攻略平台.md` | **不搬** | 丢弃 | v1.0 文档，已被 v2.0 取代 |
| `Wayfare-重构提示词.md` | `docs/Wayfare-重构提示词.md` | **搬** | ★ 核心实施手册 |
| `Wayfare-开发文档.md` | `docs/Wayfare-开发文档.md` | **搬** | ★ 核心开发文档 |
| `Wayfare-文件迁移清单.md`（本文件） | `docs/Wayfare-文件迁移清单.md` | **搬** | 迁移记录，留档 |
| `photo-share-backend/` | `wayfare-backend/` | **选择性搬**（见第二章） | 只搬 Java 源码，其余丢弃 |
| `photo-share-frontend/` | `wayfare-frontend/` | **选择性搬**（见第三章） | 只搬 src + 配置，node_modules 重建 |
| `uploads/` | `wayfare-backend/uploads/` | **搬** | 测试数据，共 15 张图 |
| `photo-share-backend/接口测试结果/` | `docs/refs/接口测试结果/` | **搬** | 接口测试截图，答辩可用 |
| `.git/` | **不搬** | 丢弃 | 旧仓库历史，新项目重新 `git init` |
| `.gitignore` | `Wayfare/.gitignore` | **搬 + 重写** | 内容需适配新结构 |
| `.workbuddy/` | **不搬** | 保留在原处 | 这是当前会话的工作区元数据，不属于项目 |
| `app.log` / `app-err.log` / `frontend.log` / `frontend-err.log` | **不搬** | 丢弃 | 运行时日志，不入库 |
| `mvnw.bat` / `启动前端.bat` | **不搬** | 丢弃 | 手动创建的启动脚本，P8 用 Docker 替代 |
| `user-upload.png` | **不搬** | 丢弃 | 临时截图 |
| `Init-Wayfare.ps1` | `scripts/Init-Wayfare.ps1` | **搬** | ★ 一键迁移脚本，留档备查 |

---

## 二、后端迁移明细

### 2.1 要搬的（Java 源码，实测共 79 个 .java 文件）

目标：`Wayfare/wayfare-backend/src/main/java/com/wayfare/`

**关键动作：搬过去后统一改包名 `com.photoshare` → `com.wayfare`，目录也要跟着改。**
（实测 79 个 .java 全部需要改写，`grep -r "photoshare"` 必须为 0）

| 源目录 | 文件数 | 目标子包 | 备注 |
|---|---|---|---|
| `common/config/` | 6 | `common/config/` | CorsConfig / RedisConfig / MybatisPlusConfig / MyMetaObjectHandler / FileStorageProperties / WebMvcConfig |
| `common/exception/` | 2 | `common/exception/` | BusinessException / GlobalExceptionHandler |
| `common/result/` | 2 | `common/result/` | Result / ResultCode |
| `controller/` | 12 | `controller/` | Auth / User / Work / Tag / Comment / File / Like / Favorite / Follow / Message / Recommend / Health |
| `entity/` | 9 | `entity/` | User / Work / WorkTag / Tag / Comment / LikeRecord / Favorite / Follow / PrivateMessage |
| `mapper/` | 9 | `mapper/` | 每个实体一个 Mapper |
| `dto/` | 9 | `dto/` | 登录/注册/作品/评论/分页等 |
| `security/` | 4 | `security/` | JwtUtil / JwtInterceptor / LoginUser / UserContext |
| `service/` + `service/impl/` | 24 | `service/` + `service/impl/` | 12 个接口 + 12 个实现 |
| `util/WatermarkUtil.java` | 1 | `common/util/` | 水印工具，P0-C 要用 |
| `PhotoShareApplication.java` | 1 | `WayfareApplication.java` | **必须重命名为 WayfareApplication** |
| **合计** | **79** | | |

### 2.2 不搬的

| 源路径 | 原因 |
|---|---|
| `photo-share-backend/target/` | Maven 编译产物，57 个 .class，新项目重新编译 |
| `photo-share-backend/.idea/` | IDE 配置，含旧项目路径，会冲突 |
| `photo-share-backend/pom.xml` | 需按新 artifactId `wayfare-backend` 重写 |
| `photo-share-backend/src/main/resources/application.yml` | 需按新库名/包名重写 |
| `photo-share-backend/*.md`（4 个接口文档） | 面向摄影平台的接口说明，P8-A 重新生成 |

> **重要**：`pom.xml`、`application.yml`、`PhotoShareApplication.java` 这三个文件**建议不搬、直接重写**。搬过去再改容易残留旧配置，重写更干净。P0-A 任务块里已经包含这三个文件的完整要求。

### 2.3 搬过去后必须做的改名动作

```
① 目录改名：src/main/java/com/photoshare/ → src/main/java/com/wayfare/
② 全文件替换：package com.photoshare; → package com.wayfare;
③ 全文件替换：import com.photoshare. → import com.wayfare.
④ 类重命名：PhotoShareApplication → WayfareApplication
⑤ 验收命令：grep -r "photoshare" . 结果必须为空
```

---

## 三、前端迁移明细

### 3.1 要搬的（源码 + 配置，实测 32 + 4 个文件）

目标：`Wayfare/wayfare-frontend/`

| 源路径 | 处理 | 备注 |
|---|---|---|
| `src/`（实测 32 个文件） | **搬** | 含 api / stores / router / layouts / components / views / assets / utils |
| `index.html` | **搬 + 改** | `<title>` 改为「行走集 Wayfare」 |
| `package.json` | **搬 + 改** | `name` 改 `wayfare-frontend` |
| `package-lock.json` | **搬 + 改** | `name` 同步改，保持依赖版本锁定 |
| `vite.config.js` | **搬，无需改** | 代理目标（8080）与 @ 别名都保持原样 |
| `README.md` | **不搬** | 前端自带说明，P8-A 统一写 |

### 3.2 不搬的

| 源路径 | 原因 |
|---|---|
| `photo-share-frontend/node_modules/` | **实测 11987 个文件**，新项目 `npm install` 重建 |

> `node_modules` 是这次迁移里体量最大的部分（**占整个源目录文件总数的 96%**）。**千万不要整个文件夹拖拽复制**，否则会慢得离谱且可能复制出损坏的依赖。正确做法是只拷 `src` + 配置，然后在新目录跑 `npm install`。

### 3.3 前端页面级的改造工作量提示

搬过去的前端页面**不是直接能用**的，P0-D 会改一批。心里有个数：

| 页面 | 改造动作 |
|---|---|
| `Home.vue` | 加目的地/天数筛选，卡片信息改为攻略维度 |
| `WorkPublish.vue` | 分类改为**从接口拉取**（原第 110 行是硬编码 8 个分类，这是必须修的坑）；加多图真实上传 |
| `WorkDetail.vue` | 加「完整行程」区块 |
| `Profile.vue` | 加「旅行偏好」页签 |
| 新增 | `views/Plan.vue`（AI 规划页）、`api/trip.js`、`api/profile.js` |
| `router/index.js` | 注册 `/plan` 路由 |

---

## 四、目标目录结构（完整）

迁移完成后，`Wayfare/` 应该是这个样子。**注意：带 ★ 的是最终态产物，迁移阶段还没有，属于 P0–P8 逐步产出。**

```
Wayfare/
├── .gitignore                          ← 从旧项目搬来后重写
├── README.md                           ★ P8-A 产出
├── docker-compose.yml                  ★ P8-B 产出
├── .env.example                        ★ P8-B 产出
│
├── db/                                 ← 新建目录
│   ├── schema.sql                      ← 从毕设根目录搬来（14 张内容域表）
│   └── schema-trip.sql                 ★ P1-A / P2-A / P2-B / P2-C 逐步产出（8 张新表）
│
├── docs/                               ← 新建目录
│   ├── Wayfare-重构提示词.md            ← ★ 搬来的核心手册
│   ├── Wayfare-开发文档.md              ← ★ 搬来的核心文档
│   ├── Wayfare-文件迁移清单.md          ← 本文件
│   ├── 部署说明.md                      ★ P8-A 产出
│   ├── metrics.md                       ★ P7-B 产出（简历数字的证据）
│   ├── 交付说明.md                      ★ P8-A 产出
│   └── refs/                           ← 参考资料，非交付物
│       ├── 需求分析文档-摄影平台版.md    ← 搬来
│       ├── 技术文档-学习版.md            ← 搬来
│       ├── application.yml-摄影平台版.yml ← 搬来（唯一一份旧运行配置，供 P0-A 参照）
│       └── 接口测试结果/                 ← 搬来（截图）
│
├── scripts/                            ← 新建目录
│   ├── Init-Wayfare.ps1                ← ★ 一键迁移脚本（本清单的自动化实现）
│   └── drill-fallback.ps1              ★ P7-C 产出（降级演练脚本）
│
├── wayfare-backend/                    ← 从 photo-share-backend 改名
│   ├── pom.xml                         ★ 重写（artifactId=wayfare-backend）
│   ├── Dockerfile                      ★ P8-B 产出
│   ├── uploads/                        ← 从毕设根目录 uploads/ 搬来
│   └── src/main/
│       ├── java/com/wayfare/           ← 从 com/photoshare 改名
│       │   ├── WayfareApplication.java
│       │   ├── common/
│       │   │   ├── config/             ← 搬 6 个
│       │   │   ├── exception/          ← 搬 2 个
│       │   │   ├── result/             ← 搬 2 个
│       │   │   └── util/               ← 搬 WatermarkUtil，★ P1-D 加 MaskUtil
│       │   ├── security/               ← 搬 4 个
│       │   ├── entity/                 ← 搬 8 个
│       │   ├── mapper/                 ← 搬 9 个
│       │   ├── dto/                    ← 搬 9 个
│       │   ├── vo/                     ★ 新建
│       │   ├── service/ + impl/        ← 搬 24 个
│       │   ├── controller/             ← 搬 12 个
│       │   ├── profile/                ★ P2-A 产出（旅行偏好画像）
│       │   ├── trip/                   ★ P2-B / P3 产出（行程域 + 7 步管线）
│       │   └── connector/              ★ P1 产出（本项目地基）
│       │       ├── llm/                { glm, deepseek, mock }
│       │       ├── map/                { baidu, cache, disabled }
│       │       └── governance/         熔断 / 能力决策 / ExternalHttpClient
│       └── resources/
│           ├── application.yml         ★ 重写（新库名 + 双厂商配置）
│           └── map-preference-tag.json ★ P3-B 产出（偏好→地图 tag 字典）
│
└── wayfare-frontend/                   ← 从 photo-share-frontend 改名
    ├── package.json                    ← 搬 + 改 name
    ├── package-lock.json               ← 搬 + 改 name
    ├── vite.config.js                  ← 搬，无需改
    ├── index.html                      ← 搬 + 改 title
    ├── Dockerfile                      ★ P8-B 产出
    ├── nginx.conf                      ★ P8-B 产出
    └── src/                            ← 搬 32 个文件（含 App.vue / main.js），P0-D/P5 逐步改造
        ├── App.vue / main.js           ← 搬 2 个
        ├── api/                        ← 搬 10 个 ★ P2/P5 加 profile.js、trip.js
        ├── assets/                     ← 搬 2 个
        ├── components/                 ← 搬 1 个 ★ P5-C 加 TripTimeline.vue
        ├── layouts/                    ← 搬 2 个
        ├── router/                     ← 搬 1 个 ★ P5 加 /plan 路由
        ├── stores/                     ← 搬 1 个
        ├── utils/                      ← 空目录 ★ P5-B 放 sseParser
        └── views/                      ← 搬 9 个 ★ P5 新增 Plan.vue
            └── admin/                  ← 搬 4 个 ★ P6 新增 Connectors.vue、Generation.vue
```

---

## 五、执行顺序

### 方式 A：一键脚本（推荐）

**第 1～3 步已经全部自动化**，脚本是 `Init-Wayfare.ps1`（在 `毕设/` 根目录）。

```powershell
# 在哪个目录下执行都可以 —— 脚本用 $PSScriptRoot 定位自己，
# 工作目录完全不影响结果（实测从 毕设\ 和从 C:\ 跑，解析出的路径一致）。

# ① 先空跑，只看计划，不写任何文件
& "D:\Code\Vibe coding test\毕设\Init-Wayfare.ps1" -DryRun

# ② 确认无误后正式执行（默认生成到 D:\Code\Vibe coding test\Wayfare）
& "D:\Code\Vibe coding test\毕设\Init-Wayfare.ps1"
```

> ⚠️ **不要双击运行、也不要用右键「使用 PowerShell 运行」** —— 脚本没有 pause，
> 窗口会在你读到任何信息之前就关掉，而它默认是**正式执行**而不是 DryRun。
> 一律从 PowerShell 终端里调用。

脚本做的事，正是下面第 1～3 步的全部内容：建目录骨架、复制 11 个文档/配置文件、
复制 3 棵源码树、改 79 个 .java 的包名、重命名启动类、改 schema 库名、
改前端 5 个文件的品牌文案、写 .gitignore，**最后自动跑 6 项校验**。

安全设计：

| 机制 | 行为 |
|---|---|
| 只读源目录 | 全过程对 `毕设/` 只读；结束时比对文件数，不一致就报警 |
| 目标非空即停 | 目标目录已存在且非空时**默认报错退出**，不覆盖任何东西 |
| `-Force` 只改名、不删除 | 显式加 `-Force` 才继续；做法是把旧目录改名为 `Wayfare.__backup_<时间戳>`，**一个文件都不删**，确认新目录没问题后自己删备份即可 |
| 拒绝危险目标 | 目标 = 源目录、目标 = 源目录父级、目标 = 盘符根，三种情况直接拒绝 |
| 拒绝认错的源 | 源目录缺 `photo-share-backend` / `photo-share-frontend` / `schema.sql` 任一即报错退出 |
| 编码零损失 | 用「字节视图」替换，不重新编码文件、不引入 BOM（实测 123 个文件 0 BOM、0 非法 UTF-8） |
| 自动校验 | 旧品牌名残留、package 声明一致性、启动类名、schema 库名与表数、源目录完整性 |

参数：

| 参数 | 说明 |
|---|---|
| `-Source` | 源目录，默认取脚本所在目录（`$PSScriptRoot`），**与当前工作目录无关** |
| `-Destination` | 目标目录，默认 `毕设` 的同级 `Wayfare` |
| `-Force` | 目标非空时把旧目录改名备份为 `Wayfare.__backup_<时间戳>` 再重建（不删除） |
| `-SkipRebrand` | 跳过界面文案改写（光影集 → 行走集 等）；package.json 的 name 仍会改 |
| `-DryRun` | 只打印计划，不写任何文件 |

> **实测结论（2026-09-19，PowerShell 5.1）**：源目录 12457 个文件，脚本约 1 秒跑完；
> 产出 89 个文件被改写，硬残留 0 处。参数分支逐一实跑验证：
> DryRun 不落盘 ✓ / 全新跑通 141 个文件 ✓ / 非空无 `-Force` 拒绝且用户文件完好 ✓ /
> `-Force` 改名备份（备份内 142 个文件含用户文件，新目录 141 个）✓ /
> `-SkipRebrand` 保留原品牌文案而包名照改 ✓ / 三种危险目标全拒绝 ✓ /
> 源目录文件数前后一致 ✓。
> 唯一已知限制：**仅支持 Windows**。

第 4 步（重写 pom.xml / application.yml、建库、起服务）仍需手动，因为那是 P0-A 的活。

---

### 方式 B：手动四步

如果你想自己控制每一步，按下面的顺序做：

#### 第 1 步：建骨架 + 搬文档

```
1. 新建文件夹 D:\Code\Vibe coding test\Wayfare
2. 建子目录：db/  docs/  docs/refs/  scripts/
3. 搬文档：
   Wayfare-重构提示词.md       → docs/
   Wayfare-开发文档.md         → docs/
   Wayfare-文件迁移清单.md     → docs/
   Init-Wayfare.ps1            → scripts/
   schema.sql                  → db/
   需求分析文档.md             → docs/refs/需求分析文档-摄影平台版.md
   技术文档-学习版.md          → docs/refs/
   application.yml             → docs/refs/application.yml-摄影平台版.yml
   接口测试结果/               → docs/refs/接口测试结果/
4. 搬测试数据：uploads/        → wayfare-backend/uploads/（先建目录）
```

#### 第 2 步：搬后端源码 + 改名

```
1. 复制 photo-share-backend/src/main/java/com/photoshare/
   → wayfare-backend/src/main/java/com/wayfare/
2. 复制 photo-share-backend/src/main/resources/（application.yml 稍后重写）
3. 全局替换包名（IDE 里用 Replace in Path，或用脚本）：
   com.photoshare → com.wayfare
4. PhotoShareApplication.java → WayfareApplication.java
5. 验证：在 wayfare-backend 下搜 "photoshare"，应为 0 结果
```

> ⚠️ **不要复制 `target/` 和 `.idea/`**。target 里是旧包名的 .class，留着会让编译报奇怪的错。

#### 第 3 步：搬前端源码

```
1. 复制 photo-share-frontend/src/        → wayfare-frontend/src/
2. 复制 index.html / package.json / package-lock.json / vite.config.js
3. 改 package.json 与 package-lock.json 的 name → wayfare-frontend
4. 改 index.html 的 title → 行走集 Wayfare
5. 在 wayfare-frontend 下跑 npm install（重建 node_modules）
```

#### 第 4 步：验证骨架能跑

```
1. 后端：重写 pom.xml + application.yml（按 P0-A 的要求）
2. 建库：mysql -u root -p 执行 db/schema.sql（库名已改为 wayfare）
3. 启动后端：mvn spring-boot:run
   期望：Spring Boot 3.3.5 启动成功，GET /api/health 返回 UP
4. 启动前端：npm run dev
   期望：页面能打开，导航栏正常
```

> 到这一步，你就回到了「原项目能跑」的状态，但换了名字和结构。**接下来才是真正的重构——从 P0-A 开始按手册逐块投喂。**

---

## 六、三个最容易出错的点

1. **不要整个文件夹拖拽复制。** `node_modules`（实测 11987 个文件）和 `target`（85 个文件，其中 57 个是旧包名的 `.class`）会拖慢甚至搞坏复制。只挑 `src` + 配置文件。

2. **包名替换要彻底。** 改完必须搜一遍 `photoshare`，0 结果才算过。残留一个会让 Spring 扫描不到 Bean，报错信息还很难定位。

3. **`schema.sql` 只改库名，表名不动。** 14 张表的表名（`sys_user`、`work`、`comment`…）保持不变，这样搬过来的实体类不用改 `@TableName`。真正变化的只有建库语句 `CREATE DATABASE photo_share` → `CREATE DATABASE wayfare`。

---

## 七、迁移后仍存在的「旧业务语义」（不算错误）

脚本只搬代码与改名，**不改业务语义**。以下 3 处仍带着摄影平台的措辞，属内容域改写范围，由后续阶段处理：

| 位置 | 内容 | 何时处理 |
|---|---|---|
| `service/RecommendService.java:17` | Javadoc「推荐带有同类标签的**摄影作品**」 | P0-C |
| `views/Login.vue:8` | 「登录继续分享你的**光影**故事」 | P5-D |
| `views/WorkPublish.vue:49` | placeholder「创作灵感、**拍摄**故事…」 | P0-D |

另有一项设计层面的遗留：4 个页面用了 Element Plus 的 `<Camera />` 图标（`Login` / `Register` / `DefaultLayout` / `Profile`），主题不符但能正常编译。P0-D / P5 改版时换成 `Compass` 或 `MapLocation` 即可。

---

## 附录：迁移产物清单（实测，跑完可逐项对照）

脚本实测产出 **141 个文件**。下面是完整结构，跑完后用
`Get-ChildItem -Recurse -File | Measure-Object` 核一下总数对不对。

```
D:\Code\Vibe coding test\Wayfare\          141 个文件
├── .gitignore                                  ← 重写（11 个忽略段）
├── db\
│   └── schema.sql                              ← 库名已改 wayfare，14 张表名未动
├── docs\                                  7 个  ← 「文本文件夹」
│   ├── Wayfare-重构提示词.md                    ★ 32 个任务块的实施手册
│   ├── Wayfare-开发文档.md                      ★ 19 章开发文档
│   ├── Wayfare-文件迁移清单.md                  ← 本文件
│   └── refs\                              4 个  ← 参考资料，非交付物
│       ├── 需求分析文档-摄影平台版.md
│       ├── 技术文档-学习版.md
│       ├── application.yml-摄影平台版.yml       ← 唯一一份旧运行配置，供 P0-A 参照
│       └── 接口测试结果\                   1 个
│           └── PixPin_2026-08-29_15-13-18.png
├── scripts\
│   └── Init-Wayfare.ps1                        ← 脚本自身留档
├── wayfare-backend\                       95 个  ← 后端程序
│   ├── src\main\java\com\wayfare\         79 个
│   │   ├── WayfareApplication.java             ← 由 PhotoShareApplication 重命名
│   │   ├── common\   10 个（config 6 / exception 2 / result 2）
│   │   ├── controller\ 12 个
│   │   ├── dto\        9 个
│   │   ├── entity\     9 个
│   │   ├── mapper\     9 个
│   │   ├── security\   4 个
│   │   ├── service\   24 个（12 接口 + 12 实现）
│   │   └── util\       1 个（WatermarkUtil）
│   ├── src\main\resources\
│   │   └── .gitkeep                            ★ application.yml 由 P0-A 生成，此处留空占位
│   └── uploads\2026\08\29\                15 个 ← 测试图（14 png + 1 jpg）
└── wayfare-frontend\                      36 个  ← 前端程序
    ├── index.html                              ← title 改为「行走集 Wayfare」
    ├── package.json                            ← name 改为 wayfare-frontend
    ├── package-lock.json                       ← name 同步改
    ├── vite.config.js                          ← 未改动
    └── src\                               32 个
        ├── App.vue / main.js
        ├── api\        10 个（auth/comment/file/message/recommend/request/social/tag/user/work）
        ├── assets\      2 个（global.scss / variables.scss）
        ├── components\  1 个（WorkCard.vue）
        ├── layouts\     2 个（AdminLayout / DefaultLayout）
        ├── router\      1 个（index.js）
        ├── stores\      1 个（user.js）
        ├── utils\       0 个 ← 空目录，P5-B 放 sseParser
        └── views\      13 个（含 admin\ 4 个）
```

### 有意不进来的文件（共约 12316 个）

「迁移全部文件」这句话要打个折 —— **搬的是全部有价值的文件，不是字面意义的全部**。
下面这些留下来是有原因的，不是漏搬：

| 类别 | 数量 | 为什么不搬 |
|---|---|---|
| `photo-share-frontend/node_modules/` | **11987** | 占了源目录文件总数的 96%。`npm install` 重建更干净，且旧依赖里可能有针对旧包名的构建缓存 |
| `.git/` | 218 | 旧仓库历史。新项目应该 `git init` 重新开始 |
| `photo-share-backend/target/` | 85 | 编译产物，里面全是**旧包名**的 `.class`，带过去会让编译报莫名错误 |
| `photo-share-backend/.idea/` | 8 | IDE 配置，含旧项目路径 |
| 运行日志（4 个 `.log`） | 4 | `app.log` 已 149KB，无价值 |
| 旧版 v1 文档 | 4 | `提示词.md` / `重构提示词-AI旅游攻略平台.md` / `开发文档-AI旅游攻略平台.md` / `README.md`，已被 v2 取代 |
| 摄影平台的接口文档 | 4 | `photo-share-backend/` 下 4 个 `.md` + 前端 `README.md`，P8-A 会重写 |
| 手动启动脚本 | 3 | `mvnw.bat` / `启动前端.bat`，P8-B 用 Docker 替代 |
| `pom.xml` | 1 | P0-A 会重写（artifactId 改 `wayfare-backend` + 加 GLM/DeepSeek 依赖） |
| `.workbuddy/` | 2 | 当前会话的工作区元数据，不属于项目 |
| `user-upload.png` | 1 | 临时截图 |

### 一项需要你知道的取舍

**`node_modules` 没搬，所以新目录的前端不会「开箱即跑」**，第一次要先：

```powershell
cd "D:\Code\Vibe coding test\Wayfare\wayfare-frontend"
npm install
```

这是有意的。硬拷 `node_modules`（11987 个文件）通常会在换路径后出现各种难查的问题，
而且 P0-D / P5 本来就要加依赖，反正要重装一次。同理 `target/` 必须重新 `mvn compile`。

**如果你确实想让新目录立刻可运行**（不想跑 `npm install`），告诉我，我给脚本加一个
`-WithNodeModules` 开关。但我的建议是不加——这条路踩坑的概率高于省下的那两分钟。

### 原目录不会被删除

脚本是**复制**，不是移动。跑完之后 `D:\Code\Vibe coding test\毕设\` 原封不动，
文件数前后一致（实测 12457 → 12457）。确认 `Wayfare` 没问题后，原目录要留要删由你决定。
