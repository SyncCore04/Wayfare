# P0-D 页面清单与操作路径

> 生成时间：2026-09-20
> 前端地址：**http://localhost:5173**（后端 8080，Vite 已配 `/api` 与 `/uploads` 代理）
> 本清单里的「验证方式」是 Agent 已经跑过的；标「需人工」的部分只能由你看。

## 一、页面清单

| # | 页面 | 路由 | 是否需要登录 | 文件 | 本轮改动 |
|---|---|---|---|---|---|
| 1 | 登录 | `/login` | 否 | `views/Login.vue` | Camera 图标→Compass；副标题去掉「光影故事」 |
| 2 | 注册 | `/register` | 否 | `views/Register.vue` | Camera 图标→Compass |
| 3 | 首页 | `/` | 否 | `views/Home.vue` | **新增筛选栏**（目的地/分类/天数）、每页 12→20、侧栏标签改用公开接口、推荐 tab 对匿名回落公开列表、文案去摄影化 |
| 4 | 攻略详情 | `/work/:id` | 否 | `views/WorkDetail.vue` | **新增目的地/天数**、**新增「完整行程」占位**、「相似作品」→「相似攻略」 |
| 5 | 发布/编辑 | `/publish`、`/edit/:id` | 是 | `views/WorkPublish.vue` | **分类改为拉 `/categories/tree`**（原来是硬编码 7 个摄影分类）、**新增目的地/天数**、**新增「存草稿」**、上传上限与后端对齐到 10MB、标签不再允许随意新建 |
| 6 | 个人中心 | `/profile` | 是 | `views/Profile.vue` | **新增「旅行偏好」页签占位**、「我的作品」→「我的攻略」、头像图标 Camera→Picture |
| 7 | 私信 | `/messages` | 是 | `views/Messages.vue` | **修 3 处接口路径**（原来全是错的）、**修会话列表取值**（原来取 `res.data` 而非 `.records`，左栏永远空） |
| 8 | 用户主页 | `/user/:id` | 是 | `views/UserHome.vue` | 未改 |
| 9 | 管理后台 | `/admin/*` | 是+管理员 | `views/admin/*.vue` | 未改（P6 处理） |
| 10 | 公共布局 | — | — | `layouts/DefaultLayout.vue` | logo 图标 Camera→Compass |
| — | 攻略卡片 | 组件 | — | `components/WorkCard.vue` | **新增目的地/天数/点赞收藏数**（原来是 hover 才显示，手机上根本看不到）、默认占位图换成「行走集 · 攻略图片」 |

**新增文件**：`api/category.js`（分类树/分页/增删改，此前整个分类模块前端没有任何 api 文件）。

## 二、操作路径（验收 1 的六步）

前置：浏览器打开 http://localhost:5173

1. **注册** → 点右上角「注册」→ 用户名 4–20 位字母数字、密码 6–20 位含字母和数字 → 提交
2. **登录** → 自动跳转，或点「登录」用刚注册的账号登录
3. **发 3 图攻略** → 顶部「发布」→ 上传区一次选 3 张图（**可用图上箭头调整顺序，第一张即封面**）→
   填标题、描述、分类（下拉数据来自接口）、目的地、行程天数 → 点「发布攻略」
4. **首页看到** → 回首页，瀑布流第一张应是你刚发的；卡片上能看到**目的地、天数、点赞收藏数**
   - 用筛选栏验证：目的地填「泉州」/ 选分类 / 选天数档位 → 列表随之变化
   - 向下滚动到底自动加载下一页（每页 20）
5. **详情页轮播 3 张** → 点卡片进详情 → 左侧轮播可左右切换 3 张图；右侧能看到目的地与天数、
   以及「完整行程（待接入）」占位块
6. **点赞 / 收藏 / 关注** → 详情页操作栏点星标与收藏，图标应立即变色、数字 +1；刷新后状态保持
7. **评论回复** → 底部评论框发一条 → 在自己的评论下点「回复」再发一条 → 应显示为二级回复
8. **私信** → 顶部信封图标 → 左栏会话列表（有最后一条与未读角标）→ 点会话进右侧聊天 → 发一条文本

## 三、页面涉及的接口（已逐条实测）

| 页面 | 接口 | 结果 |
|---|---|---|
| Register/Login | `POST /auth/register`、`POST /auth/login`、`GET /users/me` | 200 |
| Home（匿名） | `GET /works/page`、`GET /recommend/hot`、`GET /tags/hot`、`GET /categories/tree` | 全部 200 |
| Home 筛选 | `?destination=`、`?categoryId=`、`?tripDays=`、`?minTripDays=4` | 均生效（「4 天及以上」返回 0 条，因测试数据都是 3 天） |
| WorkPublish | `POST /files/batch`（3 文件）、`POST /works` | 200，work_image 落 3 条 |
| WorkDetail | `GET /works/{id}`（匿名可看）、`GET /comments/work/{id}`（匿名可看） | 200 |
| WorkDetail 交互 | `POST /likes/toggle`、`GET /likes/check`、`POST /favorites/toggle`、`GET /favorites/check`、`POST /follows/toggle` | 全部 200 |
| 评论 | `POST /comments`（一级与二级各一条）、`GET /comments/{id}/replies` | 200 |
| Messages | `POST /messages/send`、`GET /messages/conversations`、`GET /messages/conversation/{id}`、`GET /messages/unread/count`、`PUT /messages/read/{id}` | 200 |
| Profile | `GET /works/page?userId=`、`GET /favorites/my`、`GET /follows/following`、`GET /follows/followers` | 200 |
| DefaultLayout | `POST /auth/logout` → 再用旧 token 访问 | 200 → 401（黑名单生效） |

## 四、Agent 无法自证、需要你确认的部分

1. **页面观感与交互手感**（布局是否好看、动画是否顺、字号间距是否舒服）—— 我只能保证构建通过、
   接口 200、路由可达，**看不出「别扭」**。
2. **手机宽度下布局是否真的不破** —— 我加了媒体查询（筛选栏窄屏改纵向堆叠、瀑布流 2 列、
   发布页目的地/天数上下排列），但**是否挤压、文字是否溢出只能你看**。
   建议用浏览器 F12 → 设备模拟 375px 宽看首页、发布页、详情页。
3. **图片水印效果** —— 上传会自动加水印，**文字位置/透明度好不好看得你判断**
   （配置在 `application.yml` 的 `file.upload.watermark-*`）。
4. **轮播、点赞动效等视觉反馈**。

## 五、测试账号与数据（现成的）

| 用途 | 账号 / 数据 |
|---|---|
| 普通用户 | 注册一个即可；已存在 `d87193688` / `Test123456` |
| 管理员 | `admin` / `Admin123456` |
| 攻略数据 | 库里已有 3 条测试攻略（`work` id=1/2/3），其中 id=3 是 3 图 + 目的地「泉州」+ 3 天 |
| 图片 | 已上传 5 张在 `wayfare-backend/uploads/2026/09/20/` |
