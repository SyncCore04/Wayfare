-- ============================================================
-- Wayfare（行走集）- 数据库建表脚本
-- 数据库: MySQL 8.0+
-- 字符集: utf8mb4
-- 说明: 本脚本可直接在 MySQL 中执行，包含全部业务表
-- 全局约定（改表时请遵守）:
--   1. 无物理外键，关联一致性由应用层保证
--   2. 主键统一 BIGINT UNSIGNED AUTO_INCREMENT
--   3. 时间字段 created_at / updated_at；逻辑删除统一用 deleted（仅 work 表启用 @TableLogic）
--   4. 状态/类型类字段一律 TINYINT 存数字，中文描述在 VO 层转换
-- ============================================================

-- 创建数据库（如已存在请注释此行）
CREATE DATABASE IF NOT EXISTS wayfare
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE wayfare;

-- ============================================================
-- 1. 用户表 sys_user
-- ============================================================
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username        VARCHAR(50)  NOT NULL                COMMENT '登录用户名',
  password        VARCHAR(100) DEFAULT NULL             COMMENT '密码(BCrypt加密)，第三方登录用户可为空',
  nickname        VARCHAR(50)  NOT NULL                COMMENT '昵称/展示名',
  avatar          VARCHAR(255) DEFAULT ''               COMMENT '头像URL',
  email           VARCHAR(100) DEFAULT NULL             COMMENT '邮箱',
  phone           VARCHAR(20)  DEFAULT NULL             COMMENT '手机号',
  gender          TINYINT      DEFAULT 0                COMMENT '性别 0未知 1男 2女',
  bio             VARCHAR(200) DEFAULT ''               COMMENT '个人简介',
  role            VARCHAR(20)  DEFAULT 'user'           COMMENT '角色 user普通用户 admin管理员',
  status          TINYINT      DEFAULT 1                COMMENT '账号状态 0禁用 1正常',
  last_login_at   DATETIME     DEFAULT NULL             COMMENT '最后登录时间',
  last_login_ip   VARCHAR(50)  DEFAULT ''               COMMENT '最后登录IP',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted         TINYINT      DEFAULT 0                COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_email (email),
  KEY idx_phone (phone),
  KEY idx_nickname (nickname),
  KEY idx_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 2. 第三方登录账号表 user_third_account
-- ============================================================
DROP TABLE IF EXISTS user_third_account;
CREATE TABLE user_third_account (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '关联平台用户ID',
  provider        VARCHAR(20)  NOT NULL                   COMMENT '第三方平台 weixin/qq/weibo/github',
  open_id         VARCHAR(100) NOT NULL                   COMMENT '第三方openid(平台内唯一)',
  union_id        VARCHAR(100) DEFAULT NULL               COMMENT '微信unionid(跨应用唯一)',
  nickname        VARCHAR(50)  DEFAULT ''                 COMMENT '第三方昵称快照',
  avatar          VARCHAR(255) DEFAULT ''                 COMMENT '第三方头像快照',
  access_token    VARCHAR(255) DEFAULT NULL               COMMENT '访问令牌(加密存储)',
  refresh_token   VARCHAR(255) DEFAULT NULL               COMMENT '刷新令牌(加密存储)',
  expires_at      DATETIME     DEFAULT NULL               COMMENT '令牌过期时间',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_openid (provider, open_id),
  KEY idx_user_id (user_id),
  KEY idx_union_id (union_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方登录账号表';

-- ============================================================
-- 3. 分类表 category
-- ============================================================
DROP TABLE IF EXISTS category;
CREATE TABLE category (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  name            VARCHAR(50)  NOT NULL                   COMMENT '分类名称',
  parent_id       BIGINT UNSIGNED DEFAULT 0                COMMENT '父分类ID，0表示一级分类',
  icon            VARCHAR(255) DEFAULT ''                  COMMENT '分类图标URL',
  sort            INT          DEFAULT 0                   COMMENT '排序值，数字越小越靠前',
  status          TINYINT      DEFAULT 1                   COMMENT '状态 0禁用 1启用',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_name_parent (name, parent_id),
  KEY idx_parent_id (parent_id),
  KEY idx_status_sort (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作品分类表';

-- ============================================================
-- 4. 标签表 tag
-- ============================================================
DROP TABLE IF EXISTS tag;
CREATE TABLE tag (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  name            VARCHAR(30)  NOT NULL                   COMMENT '标签名称',
  use_count       INT          DEFAULT 0                   COMMENT '被作品使用次数(冗余计数)',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_name (name),
  KEY idx_use_count (use_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签表';

-- ============================================================
-- 5. 作品表 work
-- ============================================================
DROP TABLE IF EXISTS work;
CREATE TABLE work (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '作品ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '作者用户ID',
  category_id     BIGINT UNSIGNED DEFAULT 0                COMMENT '分类ID',
  title           VARCHAR(100) NOT NULL                   COMMENT '作品标题',
  description     TEXT                                    COMMENT '作品描述/创作说明',
  cover_url       VARCHAR(255) NOT NULL                   COMMENT '封面图URL(首图)',
  cover_width     INT          DEFAULT 0                   COMMENT '封面宽度(px)',
  cover_height    INT          DEFAULT 0                   COMMENT '封面高度(px)',
  destination     VARCHAR(50)  DEFAULT ''                  COMMENT '目的地（攻略特有：按目的地筛选）',
  trip_days       INT          DEFAULT 1                   COMMENT '行程天数（攻略特有：按天数筛选）',
  view_count      INT          DEFAULT 0                   COMMENT '浏览量',
  like_count      INT          DEFAULT 0                   COMMENT '点赞数(冗余)',
  collect_count   INT          DEFAULT 0                   COMMENT '收藏数(冗余)',
  comment_count   INT          DEFAULT 0                   COMMENT '评论数(冗余)',
  is_watermarked  TINYINT      DEFAULT 1                   COMMENT '是否已加水印 0否 1是',
  status          TINYINT      DEFAULT 1                   COMMENT '状态 0草稿 1已发布 2审核中 3已下架',
  published_at    DATETIME     DEFAULT NULL                COMMENT '首次发布时间',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted         TINYINT      DEFAULT 0                   COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (id),
  KEY idx_user_id (user_id),
  KEY idx_category_id (category_id),
  KEY idx_status_published (status, published_at),
  KEY idx_user_status (user_id, status),
  KEY idx_view_count (view_count),
  -- 攻略的两个筛选维度：目的地、天数
  KEY idx_destination (destination),
  KEY idx_trip_days (trip_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作品(攻略)表';

-- ============================================================
-- 6. 作品图片表 work_image
--    一个作品可包含多张图片，单独建表支持多图与排序
-- ============================================================
DROP TABLE IF EXISTS work_image;
CREATE TABLE work_image (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  work_id         BIGINT UNSIGNED NOT NULL                COMMENT '所属作品ID',
  image_url       VARCHAR(255) NOT NULL                   COMMENT '图片URL',
  width           INT          DEFAULT 0                   COMMENT '图片宽度(px)',
  height          INT          DEFAULT 0                   COMMENT '图片高度(px)',
  sort            INT          DEFAULT 0                   COMMENT '在作品中的排序，0为封面',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_work_sort (work_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作品图片明细表';

-- ============================================================
-- 7. 作品标签关联表 work_tag
--    多对多关系：一个作品可打多个标签，一个标签可被多个作品使用
-- ============================================================
DROP TABLE IF EXISTS work_tag;
CREATE TABLE work_tag (
  work_id         BIGINT UNSIGNED NOT NULL                COMMENT '作品ID',
  tag_id          BIGINT UNSIGNED NOT NULL                COMMENT '标签ID',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
  PRIMARY KEY (work_id, tag_id),
  KEY idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作品-标签关联表';

-- ============================================================
-- 8. 评论表 comment
--    支持一级评论与二级回复（parent_id 指向父评论）
-- ============================================================
DROP TABLE IF EXISTS comment;
CREATE TABLE comment (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  work_id         BIGINT UNSIGNED NOT NULL                COMMENT '所属作品ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '评论者用户ID',
  parent_id       BIGINT UNSIGNED DEFAULT 0                COMMENT '父评论ID，0为一级评论',
  reply_to_user_id BIGINT UNSIGNED DEFAULT 0               COMMENT '回复目标用户ID（二级回复时有效）',
  content         VARCHAR(500) NOT NULL                   COMMENT '评论内容',
  like_count      INT          DEFAULT 0                   COMMENT '点赞数(冗余)',
  status          TINYINT      DEFAULT 1                   COMMENT '状态 0已删除 1正常',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_work_created (work_id, created_at),
  KEY idx_user_id (user_id),
  KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

-- ============================================================
-- 9. 点赞表 like_record
--    通用点赞：target_type 区分点赞对象（作品/评论）
-- ============================================================
DROP TABLE IF EXISTS like_record;
CREATE TABLE like_record (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '点赞者用户ID',
  target_type     TINYINT      NOT NULL                   COMMENT '点赞对象类型 1作品 2评论',
  target_id       BIGINT UNSIGNED NOT NULL                COMMENT '点赞对象ID',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_target (user_id, target_type, target_id),
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞记录表';

-- ============================================================
-- 10. 收藏表 favorite
-- ============================================================
DROP TABLE IF EXISTS favorite;
CREATE TABLE favorite (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '收藏者用户ID',
  work_id         BIGINT UNSIGNED NOT NULL                COMMENT '被收藏作品ID',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_work (user_id, work_id),
  KEY idx_work_id (work_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- ============================================================
-- 11. 关注表 follow
--     follower_id 关注者，following_id 被关注者
-- ============================================================
DROP TABLE IF EXISTS follow;
CREATE TABLE follow (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  follower_id     BIGINT UNSIGNED NOT NULL                COMMENT '关注者用户ID',
  following_id    BIGINT UNSIGNED NOT NULL                COMMENT '被关注者用户ID',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_follower_following (follower_id, following_id),
  KEY idx_following_id (following_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表';

-- ============================================================
-- 12. 私信表 private_message
--     conversation_id 由双方ID排序后拼接，用于会话分组
-- ============================================================
DROP TABLE IF EXISTS private_message;
CREATE TABLE private_message (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  sender_id       BIGINT UNSIGNED NOT NULL                COMMENT '发送者用户ID',
  receiver_id     BIGINT UNSIGNED NOT NULL                COMMENT '接收者用户ID',
  conversation_id VARCHAR(50)  NOT NULL                   COMMENT '会话ID(双方ID升序拼接，如 12_34)',
  content         VARCHAR(1000) NOT NULL                  COMMENT '消息内容',
  msg_type        TINYINT      DEFAULT 1                   COMMENT '消息类型 1文本 2图片',
  is_read         TINYINT      DEFAULT 0                   COMMENT '是否已读 0未读 1已读',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  PRIMARY KEY (id),
  KEY idx_conversation_created (conversation_id, created_at),
  KEY idx_sender_id (sender_id),
  KEY idx_receiver_id (receiver_id),
  KEY idx_unread (receiver_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私信表';

-- ============================================================
-- 13. 举报表 report
--     后台管理模块使用，支持举报作品/评论/用户
-- ============================================================
DROP TABLE IF EXISTS report;
CREATE TABLE report (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '举报ID',
  reporter_id     BIGINT UNSIGNED NOT NULL                COMMENT '举报人用户ID',
  target_type     TINYINT      NOT NULL                   COMMENT '举报对象类型 1作品 2评论 3用户',
  target_id       BIGINT UNSIGNED NOT NULL                COMMENT '举报对象ID',
  reason          VARCHAR(200) NOT NULL                   COMMENT '举报原因',
  status          TINYINT      DEFAULT 0                   COMMENT '处理状态 0待处理 1已处理 2已驳回',
  handler_id      BIGINT UNSIGNED DEFAULT 0                COMMENT '处理管理员ID',
  handle_remark   VARCHAR(200) DEFAULT ''                  COMMENT '处理备注',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '处理时间',
  PRIMARY KEY (id),
  KEY idx_target (target_type, target_id),
  KEY idx_status (status),
  KEY idx_reporter_id (reporter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='举报表';

-- ============================================================
-- 14. 后台操作日志表 admin_operation_log
-- ============================================================
DROP TABLE IF EXISTS admin_operation_log;
CREATE TABLE admin_operation_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  admin_id        BIGINT UNSIGNED NOT NULL                COMMENT '操作管理员ID',
  module          VARCHAR(50)  NOT NULL                   COMMENT '操作模块 user/work/comment/category/tag/report',
  action          VARCHAR(50)  NOT NULL                   COMMENT '操作动作 create/update/delete/audit/enable/disable',
  target_type     VARCHAR(20)  DEFAULT ''                  COMMENT '操作对象类型',
  target_id       BIGINT UNSIGNED DEFAULT 0                COMMENT '操作对象ID',
  detail          TEXT                                    COMMENT '操作详情(JSON格式)',
  ip              VARCHAR(50)  DEFAULT ''                  COMMENT '操作IP',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_admin_id (admin_id),
  KEY idx_module_action (module, action),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台操作日志表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 初始化管理员账号
-- 密码：Admin123456
-- 下面的哈希由项目真实依赖 spring-security-crypto 6.3.4 的 BCryptPasswordEncoder(strength=10)
-- 现场生成并自校验通过（matches(raw)=true / matches('admin123')=false），不是手写的假值。
-- 更换密码请重新生成，不要直接改这里的字符串。
INSERT INTO sys_user (username, password, nickname, role, status) VALUES
('admin', '$2a$10$HW0qSAf4Osh6eQlfCesrk.Lwb7kXbXGe7qeMDeJBf2EefLfOv67nm', '平台管理员', 'admin', 1);

-- 初始化 8 个攻略分类（一级分类，parent_id=0）
-- 与旧摄影平台的「人像/风光/街拍…」不同，这 8 个是旅游攻略的内容分类，
-- 前端不再硬编码，改为从 /categories/tree 拉取。
INSERT INTO category (name, parent_id, sort, status) VALUES
('古建探访', 0, 1, 1),
('自然风光', 0, 2, 1),
('博物馆',   0, 3, 1),
('市井烟火', 0, 4, 1),
('美食之旅', 0, 5, 1),
('亲子出行', 0, 6, 1),
('摄影旅拍', 0, 7, 1),
('城市漫步', 0, 8, 1);

-- ============================================================
-- 脚本执行完毕
-- 共 14 张业务表
-- ============================================================
