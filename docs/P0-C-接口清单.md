# Wayfare 接口清单（P0-C 产出）

> 生成时间：2026-09-20
> 生成方式：逐文件读取 `com.wayfare.controller` 包内的真实映射注解 + 权限判断，**不是按设计文档抄的**。
> 所有路径都带 context-path `/api`，例如 `GET /works/page` 的真实地址是 `http://localhost:8080/api/works/page`。

## 一、鉴权机制（先看这段，否则会误判权限）

- 拦截器 `JwtInterceptor` 注册在 `/**`，**公开路径白名单写在它内部的 `PUBLIC_PATHS` 常量**里。
  要增删公开接口，改那个常量，**不要去 `WebMvcConfig` 找 excludePathPatterns**（那里已经没有排除了）。
- 判定顺序：先认身份（带有效 token 就写入 `UserContext`，公开路径也不例外），
  再判放行（**没带 token 且路径不公开 → 401**）。
- 带 token 但无效/已登出：**一律 401**，即使路径公开。这样前端收到 401 会清 token 跳登录，下一次请求即以匿名身份继续，公开页面照常能看。
- 权限列含义：
  - **公开** = 在白名单里，未登录可访问
  - **登录** = 需要有效 token
  - **作者/管理员** = 需登录，且是资源作者本人或 admin
  - **管理员** = 需登录，且 role=admin（非管理员返回业务码 403）

## 二、接口清单

### 鉴权
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/register` | 公开 | 注册。用户名 4–20 位、密码 6–20 位且含字母和数字，BCrypt 入库 |
| POST | `/auth/login` | 公开 | 登录，返回 `{token, user}` |
| POST | `/auth/logout` | 登录 | 把当前 token 写入 Redis 黑名单（key 存 SHA-256 摘要，TTL 取剩余有效期） |

### 健康检查
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/health` | 公开 | 返回 `{status:"UP", app, version}` |

### 分类
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/categories/tree` | 公开 | 二级分类树，只返回启用状态 |
| GET | `/categories/page` | 管理员 | 分类分页（含禁用），支持 status / keyword |
| GET | `/categories/{id}` | 登录 | 分类详情 |
| POST | `/categories` | 管理员 | 新建分类，同级同名拒绝 |
| PUT | `/categories/{id}` | 管理员 | 更新分类，不允许挂到自己下面 |
| DELETE | `/categories/{id}` | 管理员 | 删除；有子分类或有作品关联时拒绝 |

### 作品（攻略）
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/works` | 登录 | 发布。body 含 `imageUrls` 字符串数组，多图真实落 `work_image` |
| PUT | `/works/{id}` | 作者/管理员 | 编辑。`imageUrls` 非空时整体替换图集并把封面换成新的第一张 |
| DELETE | `/works/{id}` | 作者/管理员 | 逻辑删除（`work.deleted`），同时物理清理 `work_image` |
| GET | `/works/{id}` | 公开 | 详情，浏览量 +1，返回按 sort 正序的真实图集 |
| GET | `/works/page` | 公开 | 分页。支持 `destination` / `tripDays` / `categoryId` / `keyword` / `status` / `userId` |
| GET | `/works/my` | 登录 | 我的作品 |
| PUT | `/works/{id}/status` | 管理员 | 改作品状态（审核） |

### 文件
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/files/upload` | 登录 | 单图上传，参数名 `file`，**上传即加文字水印**；单文件上限 10MB，超限返回 413 |
| POST | `/files/batch` | 登录 | 多图上传，参数名 `files`，最多 9 张 |
| POST | `/files/avatar` | 登录 | 头像上传，不加水印 |

> 返回体是 `UploadVO {fileName, fileUrl, fileSize, contentType, width, height}`。
> 注意字段名是 **`fileUrl`** 而不是手册里写的 `url` —— 沿用旧项目既有字段，改它会影响前端 `api/file.js`，故未动。

### 标签
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/tags/hot` | 公开 | 热门标签（按 use_count） |
| GET | `/tags/{id}` | 登录 | 标签详情 |
| GET | `/tags/page` | 登录 | 标签分页，支持 keyword |
| POST | `/tags/batch` | 登录 | 按 id 列表批量取标签 |
| POST | `/tags` | 管理员 | 新建标签，同名拒绝 |
| PUT | `/tags/{id}` | 管理员 | 重命名 |
| DELETE | `/tags/{id}` | 管理员 | 删除，并清理 work_tag 关联 |

### 用户
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/users/me` | 登录 | 当前登录用户 |
| PUT | `/users/me` | 登录 | 修改我的资料 |
| PUT | `/users/me/password` | 登录 | 修改我的密码 |
| GET | `/users/{id}` | 登录 | 用户详情 |
| GET | `/users/page` | 管理员 | 用户分页 |
| PUT | `/users/{id}/status` | 管理员 | 启用/禁用用户 |
| DELETE | `/users/{id}` | 管理员 | 删除用户 |

### 评论
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/comments/work/{workId}` | 公开 | 某作品的评论列表 |
| GET | `/comments/{id}` | 登录 | 评论详情 |
| GET | `/comments/{id}/replies` | 登录 | 二级回复列表 |
| GET | `/comments/my` | 登录 | 我的评论 |
| DELETE | `/comments/{id}` | 登录（作者/管理员） | 删除评论 |

### 点赞 / 收藏 / 关注（计数走 Redis 缓存）
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/likes/toggle` | 登录 | 点赞/取消（Redis 计数） |
| GET | `/likes/check` | 登录 | 是否已点赞 |
| GET | `/likes/count` | 登录 | 点赞数 |
| GET | `/likes/my` | 登录 | 我的点赞 |
| POST | `/favorites/toggle` | 登录 | 收藏/取消（Redis 计数） |
| GET | `/favorites/check` | 登录 | 是否已收藏 |
| GET | `/favorites/count` | 登录 | 收藏数 |
| GET | `/favorites/my` | 登录 | 我的收藏 |
| POST | `/follows/toggle` | 登录 | 关注/取关（Redis 计数） |
| GET | `/follows/check` | 登录 | 是否已关注 |
| GET | `/follows/{userId}/followers/count` | 登录 | 某用户粉丝数 |
| GET | `/follows/{userId}/following/count` | 登录 | 某用户关注数 |
| GET | `/follows/{userId}/followers` | 登录 | 粉丝列表 |
| GET | `/follows/{userId}/following` | 登录 | 关注列表 |
| GET | `/follows/following` | 登录 | 我关注的 |
| GET | `/follows/followers` | 登录 | 我的粉丝 |
| GET | `/follows/my/stats` | 登录 | 我的关注统计 |

### 私信
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/messages/send` | 登录 | 发私信 |
| GET | `/messages/conversations` | 登录 | 会话列表 |
| GET | `/messages/conversation/{otherUserId}` | 登录 | 与某人的消息 |
| GET | `/messages/unread/count` | 登录 | 未读总数 |
| GET | `/messages/unread/count/{otherUserId}` | 登录 | 与某人的未读数 |
| PUT | `/messages/read/{otherUserId}` | 登录 | 标记已读 |

### 推荐
| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/recommend/hot` | 公开 | 热门推荐 |
| POST | `/recommend/browse/{workId}` | 登录 | 记录浏览（Redis ZSet） |
| GET | `/recommend/feed` | 登录 | 信息流 |
| GET | `/recommend/personal` | 登录 | 个性化推荐 |
| GET | `/recommend/similar/{workId}` | 登录 | 相似作品 |
| GET | `/recommend/user-tags` | 登录 | 我的偏好标签 |
| DELETE | `/recommend/history` | 登录 | 清空浏览历史 |

## 三、本次 P0-C 的新增与修改

**新增接口**：`POST /auth/logout`、`GET /categories/tree`、`GET /categories/page`、`GET /categories/{id}`、
`POST /categories`、`PUT /categories/{id}`、`DELETE /categories/{id}`（整个分类模块此前完全没有 Controller/Service）。

**修改的接口**：
- `POST /works`：请求体新增 `imageUrls` / `destination` / `tripDays`；`coverUrl` 由必填改为可选（封面取第一张图）
- `PUT /works/{id}`：同上，且 `imageUrls` 非空时整体替换图集
- `GET /works/page`：新增 `destination` / `tripDays` 两个筛选参数
- 全站鉴权行为：见下节

## 四、行为变化提醒（P0-D 改前端前必须知道）

以下接口**此前匿名可访问，P0-C 起需要登录**（因为它们不在手册规定的公开清单里）：

| 接口 | 影响 |
|---|---|
| `GET /recommend/feed` | 首页信息流若匿名调用会拿到 401 |
| `GET /recommend/similar/{workId}` | 详情页「相似作品」同理 |
| `GET /tags/page`、`GET /tags/{id}` | 标签页 |
| `GET /users/{id}` | 看别人的主页 |

**处理方式二选一**（P0-D 时定）：① 把它们加进 `JwtInterceptor.PUBLIC_PATHS`；② 前端改为登录后可见。
默认按手册的清单从严，**没有**擅自把它们放进白名单 —— 宁可先关紧，也不要漏开管理接口。

另：`/works/{id:[0-9]+}` 为了让未登录用户能看作品详情而公开，白名单按路径匹配不区分方法，
因此 `WorkController` 里对写操作额外做了「必须登录」校验兜底，两者配合才完整。
