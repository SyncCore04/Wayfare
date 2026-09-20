-- ============================================================
-- Wayfare（行走集）· 行程域与配置域建表脚本
-- 数据库: MySQL 8.0+ / 字符集: utf8mb4
-- ------------------------------------------------------------
-- 本文件是继 db/schema.sql（内容域 14 张表）之后的第二份建表脚本。
-- 分工：
--   P1-A（本次）  → sys_config 运行时开关表
--   P2-B（后续）  → 行程表族、旅行偏好画像、日志与缓存表
-- 执行：mysql -uroot -p<你的MySQL密码> < db/schema-trip.sql
--      或 mysql -uroot -p<你的MySQL密码> -e "source .../db/schema-trip.sql"
-- ------------------------------------------------------------
-- 为什么配置要进数据库而不是只写在 application.yml：
--   写在 yml 里的配置改一次要重启；而「切厂商/开关地图」这类操作
--   在后台点一下就该生效。所以配置分三层：
--     L1 启动期 = application.yml（连接地址、Key 这类启动必需项）
--     L2 运行期 = 本表 + Redis 缓存（改完不重启生效）
--     L3 降级   = 熔断器 + 分级 Provider（P1-D 实现）
--   读取优先级：L2 覆盖 L1；L2 里没有的键回落到 L1。
-- ============================================================

CREATE DATABASE IF NOT EXISTS wayfare
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE wayfare;

-- ============================================================
-- 1. 运行时配置表 sys_config
--    注意：本表存的是「可以随时改」的开关，不放密钥明文以外的敏感配置，
--    后台回显一律掩码（见 P6-A 连接器管理页）。
-- ============================================================
DROP TABLE IF EXISTS sys_config;
CREATE TABLE sys_config (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  config_key    VARCHAR(100)  NOT NULL                COMMENT '配置键，全局唯一',
  config_value  VARCHAR(1000) DEFAULT ''              COMMENT '配置值（统一按字符串存，按 value_type 解释）',
  value_type    VARCHAR(10)   NOT NULL DEFAULT 'STRING' COMMENT '值类型 STRING|INT|BOOL|JSON',
  group_name    VARCHAR(50)   NOT NULL DEFAULT ''     COMMENT '分组，后台按组分页展示 llm|map|trip',
  description   VARCHAR(200)  DEFAULT ''              COMMENT '配置说明',
  updated_by    BIGINT UNSIGNED DEFAULT 0             COMMENT '最后修改人ID，0 表示系统初始值',
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key),
  KEY idx_group_name (group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行时配置表(L2 开关)';

-- ============================================================
-- 2. POI 本地缓存表 poi_cache（P1-C 需要）
--    作用：地图服务熔断或断网时，靠它把「上次查过的点位」继续用起来，
--    并把数据标记为 CACHED 让用户知道不是刚查的。属三级降级的第二级。
--    坐标是百度坐标系 BD-09，不做转换（只接百度一家，存一份就够）。
--    说明：本表原本规划在 P2-B 的「行程表族」里，但 P1-C 的验收第 4 条
--    （熔断状态下能命中缓存）必须有它，所以提前建在这里。
-- ============================================================
DROP TABLE IF EXISTS poi_cache;
CREATE TABLE poi_cache (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  poi_uid       VARCHAR(64)   NOT NULL                COMMENT '百度POI唯一ID',
  name          VARCHAR(200)  NOT NULL                COMMENT 'POI名称',
  address       VARCHAR(500)  DEFAULT ''              COMMENT '地址',
  city          VARCHAR(50)   DEFAULT ''              COMMENT '所属城市，检索时按它过滤',
  lng           DECIMAL(10,7) DEFAULT NULL            COMMENT '经度(BD-09)',
  lat           DECIMAL(10,7) DEFAULT NULL            COMMENT '纬度(BD-09)',
  tag           VARCHAR(200)  DEFAULT ''              COMMENT '分类标签',
  shop_hours    VARCHAR(100)  DEFAULT ''              COMMENT '营业时间，百度常常不给',
  -- 下面两列允许 NULL 且刻意不设 DEFAULT 0：
  -- 百度的检索接口经常不返回评分、基本不返回票价，
  -- 用 0 冒充「未知」是数据造假，必须保留 null 语义。
  rating        DECIMAL(3,1)  DEFAULT NULL            COMMENT '评分，取不到为 NULL 表示未知',
  ticket_price  DECIMAL(10,2) DEFAULT NULL            COMMENT '票价，取不到为 NULL 表示未知',
  raw_json      TEXT                                  COMMENT '原始响应片段，排查字段缺失用',
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_poi_uid (poi_uid),
  KEY idx_city_name (city, name),
  KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='POI本地缓存表(地图降级用)';

-- ============================================================
-- 3. 外部调用日志表 external_call_log（P1-D 需要）
--    记录每一次出站调用（大模型 / 百度地图），是成本统计(P4)、
--    监控看板(P6)、简历指标(P7) 的唯一取数来源。
--    【铁律】request_summary 与 error_msg 写入前必须已脱敏（MaskUtil），
--    库里不允许出现完整密钥 —— P7 有测试专门验这一点。
--    说明：本表原本规划在 P2-C 的「日志与缓存表」里，P1-D 的验收需要它，故提前建。
-- ============================================================
DROP TABLE IF EXISTS external_call_log;
CREATE TABLE external_call_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id         BIGINT UNSIGNED DEFAULT NULL         COMMENT '触发调用的用户ID，系统任务为NULL',
  trip_id         BIGINT UNSIGNED DEFAULT NULL         COMMENT '关联行程ID，P3起才有值',
  connector       VARCHAR(20)  NOT NULL                COMMENT '连接器类型 LLM | BAIDU_MAP',
  api_name        VARCHAR(100) DEFAULT ''              COMMENT '接口名，如 chat/completions、place/v2/search',
  request_summary VARCHAR(600) DEFAULT ''              COMMENT '请求摘要（已脱敏，截断500字符）',
  http_status     INT          DEFAULT NULL            COMMENT 'HTTP状态码，未拿到响应为NULL',
  duration_ms     INT          DEFAULT NULL            COMMENT '耗时（毫秒）',
  success         TINYINT      DEFAULT 0               COMMENT '是否成功 0否 1是',
  error_msg       VARCHAR(500) DEFAULT NULL            COMMENT '失败原因（已脱敏）',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
  PRIMARY KEY (id),
  KEY idx_connector_created (connector, created_at),
  KEY idx_user_id (user_id),
  KEY idx_success (success),
  KEY idx_api_name (api_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部调用日志表';

-- ============================================================
-- 4. 初始化配置数据
--    约定：凡是「出厂设置」都写成这里的初始值，updated_by = 0。
-- ============================================================

-- ---------- 组 llm：大模型相关开关 ----------
INSERT INTO sys_config (config_key, config_value, value_type, group_name, description, updated_by) VALUES
('llm.enabled',         'true',          'BOOL',   'llm', '是否启用大模型能力。关闭后 AI 规划入口不可用', 0),
('llm.active-provider', 'glm',           'STRING', 'llm', '当前使用厂商：glm | deepseek | mock', 0),
('llm.fallback-order',  'glm,deepseek',  'STRING', 'llm', '降级顺序，逗号分隔。主力不可用时按序切换', 0),
('llm.timeout-ms',      '90000',         'INT',    'llm', '单次调用超时（毫秒）。行程编排是长输出，默认 90 秒', 0);

-- ---------- 组 map：地图相关开关 ----------
-- map.enabled 默认 false：没有百度地图 AK 也能全功能开发。
-- 这不是「功能缺失」——地图关闭时全流程改走估算(ESTIMATED)，系统照常可用。
INSERT INTO sys_config (config_key, config_value, value_type, group_name, description, updated_by) VALUES
('map.enabled',                'false', 'BOOL',   'map', '是否启用地图能力。默认关闭，关闭后距离/时长标记为 ESTIMATED', 0),
('map.baidu.ak',               '',      'STRING', 'map', '百度地图 Web 服务 AK。留空表示未配置；后台回显一律掩码', 0),
('map.breaker.fail-threshold', '5',     'INT',    'map', '熔断阈值：连续失败多少次后打开熔断', 0),
('map.breaker.open-seconds',   '300',   'INT',    'map', '熔断打开后保持多久（秒）再试探恢复', 0);

-- ---------- 组 trip：行程编排参数 ----------
INSERT INTO sys_config (config_key, config_value, value_type, group_name, description, updated_by) VALUES
('trip.max-days',           '5',  'INT', 'trip', '单次行程最大天数，超过则提示拆分', 0),
('trip.max-candidate',      '20', 'INT', 'trip', '候选点位池上限，控制送入大模型的上下文规模', 0),
('trip.max-replan-rounds',  '2',  'INT', 'trip', '约束校验不通过时最多重排轮数。绝不允许死循环', 0),
('trip.poi-cache-ttl-hours','24', 'INT', 'trip', 'POI 缓存有效期（小时），熔断降级时靠它兜底', 0);

-- ============================================================
-- 脚本执行完毕：本文件当前含 2 张表
--   sys_config  运行时开关（P1-A）
--   poi_cache   POI 本地缓存（P1-C）
-- P2-B 会把行程表族（行程三表 / 偏好画像 / 日志 / 缓存）追加到本文件后面
-- ============================================================
