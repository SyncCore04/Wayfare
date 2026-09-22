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
-- 2. POI 本地缓存表 poi_cache（P1-C 建表，P2-C 补列）
--    作用：地图服务熔断或断网时，靠它把「上次查过的点位」继续用起来，
--    并把数据标记为 CACHED 让用户知道不是刚查的。属三级降级的第二级。
--    坐标是百度坐标系 BD-09，不做转换（只接百度一家，存一份就够）。
--    说明：本表原本规划在 P2-B 的「行程表族」里，但 P1-C 的验收第 4 条
--    （熔断状态下能命中缓存）必须有它，所以提前建在这里。
--
--    【P2-C 新增的 4 列：provider / keyword / fetched_at / expires_at】
--    现状：**这 4 列已建好但暂未写入，写入属 P3（候选检索阶段）。**
--    P1-C 的 BaiduMapProvider 已经在回写本表（按 poi_uid upsert），
--    但 P2 阶段的纪律是「纯数据层，不写业务逻辑」，所以没有去动连接器代码；
--    P3 的候选检索在写缓存时天然知道检索词与 TTL，届时一并写入即可。
--    **不写也不会出问题**：LocalCacheMapProvider 目前按 city + name/tag 模糊匹配读取，
--    完全不依赖这 4 列，所以它们为 NULL 时缓存降级照常工作。
--    （现在建列的收益：将来不必对已有数据的表做 ALTER。）
--
--    【为什么本表的 VARCHAR 仍保留 DEFAULT ''，没跟着改成 NULL】
--    「可空列一律 DEFAULT NULL」这条规则是为了避免「未填写」出现两种表示，
--    从而让 `WHERE x IS NULL` 静默漏行。但本表是**缓存**，不是用户数据：
--    city 只用于等值过滤（'' 永远匹配不到真实城市，语义上等价于没有），
--    address / tag / shop_hours 只用于展示。收益为零而改动面变大，
--    所以保持 P1-C 的既有定义不动。**规则要按场景用，不是无脑套。**
-- ============================================================
DROP TABLE IF EXISTS poi_cache;
CREATE TABLE poi_cache (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  provider      VARCHAR(20)   DEFAULT NULL            COMMENT '数据来源厂商 baidu（P2-C 新增，待 P3 写入）',
  poi_uid       VARCHAR(64)   NOT NULL                COMMENT '百度POI唯一ID',
  name          VARCHAR(200)  NOT NULL                COMMENT 'POI名称',
  address       VARCHAR(500)  DEFAULT ''              COMMENT '地址',
  city          VARCHAR(50)   DEFAULT ''              COMMENT '所属城市，检索时按它过滤',
  keyword       VARCHAR(100)  DEFAULT NULL            COMMENT '产生这条缓存时的检索词（P2-C 新增，待 P3 写入）',
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
  fetched_at    DATETIME      DEFAULT NULL            COMMENT '本次从地图抓取的时间（P2-C 新增，待 P3 写入）',
  expires_at    DATETIME      DEFAULT NULL            COMMENT '过期时间 = fetched_at + trip.poi-cache-ttl-hours（P2-C 新增，待 P3 写入）',
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_poi_uid (poi_uid),
  KEY idx_city_keyword (city, keyword),
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
('llm.active-provider', 'qwen',          'STRING', 'llm', '当前使用厂商：qwen | glm | deepseek | mock', 0),
('llm.fallback-order',  'qwen,glm,deepseek', 'STRING', 'llm', '降级顺序，逗号分隔。主力不可用时按序切换', 0),
('llm.timeout-ms',      '90000',         'INT',    'llm', '单次调用超时（毫秒）。行程编排是长输出，默认 90 秒', 0),
-- 【P4 修复新增】大模型熔断。实测 qwen 的失败是「长输出跑满 timeout 才判定超时」，
-- 一次白等 90 秒；没有熔断时坏掉的厂商会被反复选中、反复白等（两次真实运行合计白等约 9 分钟）。
-- 阈值比地图（5）低，因为地图一次失败只损失几百毫秒，大模型一次失败损失一整个 90 秒。
('llm.breaker.fail-threshold', '3',   'INT',    'llm', '连续失败多少次后熔断该厂商（后续请求直接跳过，不再白等超时）', 0),
('llm.breaker.open-seconds',   '300', 'INT',    'llm', '熔断保持多久（秒）后再放行一次试探；试探成功即恢复', 0),
-- 【单价留 0 是有意的】单位「元/百万 token」，需按厂商官网实际报价填写。
-- 0 表示「未配置」：此时 AiLogService.estCost 返回 null 而不是 0 ——
-- 成本是简历/答辩上会被追问的数字，宁可显示「未配置」也不能编一个看起来合理的值。
-- （P7-B 的量化指标必须来自实测，见交接文档第 9 节。）
--
-- 【P4-C 起：输入/输出分开计价】
-- 厂商的输入价与输出价通常不同（输出价往往贵 2~4 倍），混在一起算会明显偏低。
-- 所以新增 -input / -output 两个键，用 STRING 存小数字符串（如 '0.3'、'1.5'）——
-- 用 INT 会把 0.3 取整成 0，直接变成「未配置」。
-- 读取优先级：{provider}-input/-output  →  回落旧的 {provider}（整数，视同输入输出同价）。
-- 旧的 llm.price.glm / llm.price.deepseek 保留不删，免得旧部署一升级就算不出成本。
('llm.price.qwen-input',      '0', 'STRING', 'llm', 'Qwen 输入单价（元/百万token，支持小数如 0.3）。0 或缺失=未配置，estCost 返回 null；按阿里云百炼官网报价填', 0),
('llm.price.qwen-output',     '0', 'STRING', 'llm', 'Qwen 输出单价（元/百万token，支持小数）。同上', 0),
('llm.price.glm-input',       '0', 'STRING', 'llm', '智谱 GLM 输入单价（元/百万token，支持小数）。同上', 0),
('llm.price.glm-output',      '0', 'STRING', 'llm', '智谱 GLM 输出单价（元/百万token，支持小数）。同上', 0),
('llm.price.deepseek-input',  '0', 'STRING', 'llm', 'DeepSeek 输入单价（元/百万token，支持小数）。同上', 0),
('llm.price.deepseek-output', '0', 'STRING', 'llm', 'DeepSeek 输出单价（元/百万token，支持小数）。同上', 0),
-- 兼容保留：输入输出同价的单一价（P2-C 的旧键），仅在上面 6 个键都没配时兜底
('llm.price.glm',       '0',             'INT',    'llm', '【兼容保留】GLM 单一价（输入输出同价）。优先读 -input/-output；0=未配置', 0),
('llm.price.deepseek',  '0',             'INT',    'llm', '【兼容保留】DeepSeek 单一价（输入输出同价）。优先读 -input/-output；0=未配置', 0);

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
('trip.poi-cache-ttl-hours','24', 'INT', 'trip', 'POI 缓存有效期（小时），熔断降级时靠它兜底', 0),
-- SSE 通道超时（P4-A）。手册写的是 5 分钟，但实测一次生成要 194~507 秒（骨架本身 3 次大模型调用，
-- 主力模型屡次 90 秒超时后降级），5 分钟会把 done 事件掐掉、用户拿不到收尾。
-- 故默认放宽到 10 分钟，并做成 L2 可调（改完不重启即生效）。
('trip.sse-timeout-ms',     '600000', 'INT', 'trip', 'SSE 生成通道超时（毫秒）。默认 10 分钟，需大于最坏情况的一次生成耗时', 0);

-- ============================================================
-- 4. 用户旅行偏好画像表 user_travel_profile（P2-A）
--    作用：把用户「喜欢吃什么、怎么玩、跟谁去」沉淀成结构化画像，
--    在 P3 的行程编排阶段渲染成一段中文文本块注入 system prompt。
--
--    【铁律 3 的落点】画像注入是**增强**，不是必需：
--    用户把 allow_ai_use 置 0 后，规划照常跑通，只是不再个性化。
--    因此本表任何字段缺失都不影响主流程 —— 这也是为什么
--    pace / budget_level / walk_limit_km 允许 NULL 而不是默认 0：
--    「没填」与「填了 0」是两件事，用 0 冒充「未填写」就是数据造假
--    （与 poi_cache.rating 允许 NULL 是同一条原则）。
--
--    【可空列一律 DEFAULT NULL，不要写 DEFAULT ''】这是个踩过的坑：
--    MyBatis-Plus 插入时会**跳过值为 null 的字段**，该列便由 MySQL 取列默认值。
--    若写成 DEFAULT ''，用户在表单里清空某个字段后，库里存的是空串而不是 NULL，
--    「未填写」就同时有了 null 和 '' 两种表示 —— 将来任何
--    `WHERE cuisines IS NULL` 的查询都会静默漏行。默认 NULL 让「没填」只有一种表示。
--
--    【为什么单列而不是 EAV】字段就这十来个且不会频繁增删，
--    拍平成列后 P3 渲染时一次查询即可拿到全部，不必拼装。
-- ============================================================
DROP TABLE IF EXISTS user_travel_profile;
CREATE TABLE user_travel_profile (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id         BIGINT UNSIGNED NOT NULL                COMMENT '所属用户ID，一人一行',
  -- ---------- 口味 ----------
  cuisines        VARCHAR(255) DEFAULT NULL               COMMENT '喜欢菜系，逗号分隔，如 晋菜,面食,家常菜；NULL=未填写',
  flavors         VARCHAR(255) DEFAULT NULL               COMMENT '口味偏好，如 偏咸,微辣；NULL=未填写',
  taboos          VARCHAR(500) DEFAULT NULL               COMMENT '忌口与过敏（硬约束，任何推荐都不得包含），如 香菜,花生,海鲜；NULL=未填写',
  -- ---------- 风格 ----------
  travel_styles   VARCHAR(255) DEFAULT NULL               COMMENT '旅行风格，多选逗号分隔：古建探访/自然风光/博物馆/市井烟火/摄影旅拍/亲子出行/城市漫步/美食之旅；NULL=未填写',
  pace            TINYINT      DEFAULT NULL               COMMENT '节奏 1慢(每天2-3点) 2适中(3-4) 3紧凑(4-5)，NULL=未填写',
  budget_level    TINYINT      DEFAULT NULL               COMMENT '预算倾向 1经济 2舒适 3品质，NULL=未填写',
  companions      VARCHAR(50)  DEFAULT NULL               COMMENT '常同行人 独自/情侣/朋友/家庭带娃/带长辈；NULL=未填写',
  walk_limit_km   INT          DEFAULT NULL               COMMENT '单日步行上限（公里），用于约束点位密度，NULL=未填写',
  -- ---------- 其他 ----------
  hotel_pref      VARCHAR(255) DEFAULT NULL               COMMENT '住宿偏好；NULL=未填写',
  notes           VARCHAR(500) DEFAULT NULL               COMMENT '自由备注；NULL=未填写',
  allow_ai_use    TINYINT      NOT NULL DEFAULT 1         COMMENT '隐私开关：是否允许 AI 使用本画像 0否 1是',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户旅行偏好画像表(AI 增强用)';

-- ============================================================
-- 5. 行程主表 trip（P2-B）
--    一条记录 = 用户的一次行程规划。
--
--    【本表是「行程域」的根】trip_day 与 trip_item 都挂在它下面。
--    P3 的七步管线最终产物就是「一条 trip + N 条 trip_day + M 条 trip_item」。
--
--    【可空列的语义，不要用 0/'' 冒充】
--      · dest_lng / dest_lat：目的地中心坐标。**地图关闭时为 NULL 是正常状态**，
--        不是「数据缺失」—— 铁律 2 说地图关闭只是能力降级。写成 0,0 会被当成
--        「几内亚湾」这个真实坐标，比 NULL 危险得多。
--      · map_mode：本次生成的数据可信度 VERIFIED/CACHED/ESTIMATED，
--        与 trip_item.verify_status 同源，前端据此渲染整体角标。
--      · generation_rounds：约束校验打回重排的实际轮次，是「迭代优化」效果的观测量。
--
--    【status 与 deleted 是两件事】status 是业务状态（草稿/已生成/已编辑/已发布），
--    deleted 是逻辑删除。用户「删掉」一条行程是 deleted=1，不是 status 变化。
--
--    【关于 guide_text】P2-B 的任务块字段清单里没有它，但《Wayfare-开发文档》§8.2 的
--    trip 表定义有「guide_text TEXT 攻略文案（P4-B 写回）」。**以开发文档为准补上**：
--    现在加是零成本，等 P4-B 时表里已有数据再补就得 ALTER 线上表。
-- ============================================================
DROP TABLE IF EXISTS trip;
CREATE TABLE trip (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id           BIGINT UNSIGNED NOT NULL                COMMENT '所属用户ID',
  title             VARCHAR(100)  DEFAULT NULL              COMMENT '行程标题，用户可改；AI 生成的默认标题',
  raw_input         TEXT                                    COMMENT '用户原始输入（自然语言，保留原文便于复盘）',
  intent_json       JSON                                    COMMENT '意图解析结果（P3 Step1 写入）',
  destination       VARCHAR(50)   DEFAULT NULL              COMMENT '目的地名称',
  dest_lng          DECIMAL(10,7) DEFAULT NULL              COMMENT '目的地中心经度(BD-09)；地图关闭时为 NULL（正常状态）',
  dest_lat          DECIMAL(10,7) DEFAULT NULL              COMMENT '目的地中心纬度(BD-09)；地图关闭时为 NULL（正常状态）',
  days              INT           DEFAULT NULL              COMMENT '行程天数',
  start_date        DATE          DEFAULT NULL              COMMENT '出发日期',
  budget_total      DECIMAL(10,2) DEFAULT NULL              COMMENT '预算金额',
  budget_mode       TINYINT       DEFAULT NULL              COMMENT '预算口径 1人均 2总计',
  transport         VARCHAR(10)   DEFAULT NULL              COMMENT '交通方式 DRIVE/PUBLIC/WALK/MIX',
  companion         VARCHAR(20)   DEFAULT NULL              COMMENT '同行人',
  map_mode          VARCHAR(10)   DEFAULT NULL              COMMENT '本次生成的数据可信度 VERIFIED/CACHED/ESTIMATED',
  profile_used      TINYINT       DEFAULT 0                 COMMENT '本次生成是否使用了用户画像 0否 1是',
  guide_text        TEXT                                    COMMENT '攻略文案（P4-B 生成后写回，重复访问不重新生成）',
  status            TINYINT       NOT NULL DEFAULT 0        COMMENT '业务状态 0草稿 1已生成 2已编辑 3已发布',
  work_id           BIGINT UNSIGNED DEFAULT NULL            COMMENT '发布为攻略后关联 work.id，未发布为 NULL',
  model_name        VARCHAR(50)   DEFAULT NULL              COMMENT '本次使用的模型，如 glm-4-flash / deepseek-chat',
  generation_rounds INT           DEFAULT NULL              COMMENT '约束校验打回重排的实际轮次（观测迭代优化效果）',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted           TINYINT       DEFAULT 0                 COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (id),
  KEY idx_user_id (user_id),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程主表';

-- ============================================================
-- 6. 行程日表 trip_day（P2-B）
--    一天一行，day_index 从 1 起（对用户展示是「第 1 天」，不是「第 0 天」）。
--
--    【为什么 index 从 1 而 trip_item.seq 从 0】两者是给人看和给程序用的两种语义：
--    day_index 直接出现在界面上（「第 3 天」），从 1 起更自然；
--    seq 只在代码里做排序，从 0 起是数组下标习惯。**不要统一成同一个起点** ——
--    统一了反而要在渲染时到处 +1/-1，是 bug 温床。
--
--    本表按手册只有 created_at，没有 updated_at（当天主题几乎不会被单独编辑，
--    改行程是整份重存）。
-- ============================================================
DROP TABLE IF EXISTS trip_day;
CREATE TABLE trip_day (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  trip_id    BIGINT UNSIGNED NOT NULL                COMMENT '所属行程ID',
  day_index  INT          NOT NULL                   COMMENT '第几天，从 1 起',
  title      VARCHAR(100) DEFAULT NULL               COMMENT '当天主题，如「古城与开元寺」',
  summary    VARCHAR(500) DEFAULT NULL               COMMENT '当天概述',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_trip_day (trip_id, day_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程日表';

-- ============================================================
-- 7. 行程条目表 trip_item（P2-B，本项目的核心表）
--    一条 = 行程里的一个安排（景点/餐饮/住宿/交通/休息）。
--
--    【本表是「事实数据永不来自大模型」这条铁律的落点】
--    verify_status 与 data_source 是本项目的数据诚信机制，必须成对出现：
--      · verify_status —— 这个事实（坐标/距离/时长）可信到什么程度：
--          VERIFIED  绿「实测」   来自地图 API 的真实返回
--          CACHED    灰「缓存」   来自本地 poi_cache / 路线缓存（不是刚查的）
--          ESTIMATED 橙「估算」   地图关闭或没查到，由本地规则估算
--          USER      蓝「手动」   用户自己改过的值
--      · data_source —— 这个事实是谁给的：
--          BAIDU 地图 API / LLM 大模型（只能给语义类字段，如 reason、note）
--          / USER 用户
--    **任何进了本表的事实字段都必须带这两个标记；没有标记的事实字段视为设计缺陷。**
--    前端据此渲染来源角标，用户一眼能看出「这个距离是实测还是编的」。
--
--    【估算模式下怎么写】distance_meters / duration_seconds 留 NULL，
--    把模糊表述写进 note（如「步行约十几分钟」）—— 绝不在数值列里编一个具体数字。
--    这是「不让模型编距离」的最后一道防线：列里没有数字，前端就渲染不出假精度。
-- ============================================================
DROP TABLE IF EXISTS trip_item;
CREATE TABLE trip_item (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  trip_id                BIGINT UNSIGNED NOT NULL                COMMENT '所属行程ID',
  day_index              INT          NOT NULL                   COMMENT '第几天，从 1 起',
  seq                    INT          NOT NULL                   COMMENT '当天顺序，从 0 起',
  item_type              VARCHAR(10)  NOT NULL                   COMMENT '类型 SCENIC/FOOD/HOTEL/TRANSPORT/REST',
  poi_uid                VARCHAR(64)  DEFAULT NULL               COMMENT '地图POI唯一标识；地图关闭时为空',
  poi_name               VARCHAR(100) DEFAULT NULL               COMMENT '点位名称',
  address                VARCHAR(255) DEFAULT NULL               COMMENT '地址',
  lng                    DECIMAL(10,7) DEFAULT NULL              COMMENT '经度(BD-09)；地图关闭时可空',
  lat                    DECIMAL(10,7) DEFAULT NULL              COMMENT '纬度(BD-09)；地图关闭时可空',
  arrive_time            TIME         DEFAULT NULL               COMMENT '到达时间',
  leave_time             TIME         DEFAULT NULL               COMMENT '离开时间',
  stay_minutes           INT          DEFAULT NULL               COMMENT '停留分钟数',
  ticket_price           DECIMAL(10,2) DEFAULT NULL              COMMENT '门票价，未知为 NULL（免费景点是 0，两者不可混同）',
  cost_estimate          DECIMAL(10,2) DEFAULT NULL              COMMENT '费用估算',
  transport_mode_to_next VARCHAR(10)  DEFAULT NULL               COMMENT '到下一站的交通方式 DRIVE/PUBLIC/WALK/MIX',
  distance_meters        INT          DEFAULT NULL               COMMENT '到下一站距离（米）；估算模式下为 NULL',
  duration_seconds       INT          DEFAULT NULL               COMMENT '到下一站耗时（秒）；估算模式下为 NULL',
  verify_status          VARCHAR(10)  DEFAULT NULL               COMMENT '可信度 VERIFIED/CACHED/ESTIMATED/USER（数据诚信机制）',
  data_source            VARCHAR(10)  DEFAULT NULL               COMMENT '数据来源 BAIDU/LLM/USER（数据诚信机制）',
  reason                 VARCHAR(500) DEFAULT NULL               COMMENT 'AI 给出的安排理由（属语义字段，来自 LLM 是合规的）',
  note                   VARCHAR(500) DEFAULT NULL               COMMENT '备注；估算模式下放模糊表述，如「步行约十几分钟」',
  created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_trip_day_seq (trip_id, day_index, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行程条目表(数据诚信机制落点)';

-- ============================================================
-- 8. AI 生成日志表 ai_generation_log（P2-C）
--    作用：本项目的**成本与质量证据链**。
--    每一次管线阶段调用（P3 的 PARSE/CANDIDATE/PREORDER/COMPOSE/VALIDATE/ROUTE/COPY）
--    都在这里留一条：用了哪家厂商、哪个模型、烧了多少 token、耗时多久、成功没有。
--
--    【它和 external_call_log 的分工，别混】
--      · external_call_log —— **传输层**视角：每一次 HTTP 尝试一条（GET 重试会留 2 条），
--        由出站治理层自动写，关注的是「网络调用成没成」。
--      · ai_generation_log —— **业务阶段**视角：一个管线阶段一条，
--        关注的是「这一步的语义任务成没成、花了多少钱」。一次阶段调用可能对应
--        多条 external_call_log（重试），两者不是一一对应。
--
--    【error_code 用统一枚举】取值来自 {@code com.wayfare.trip.AiErrorCode}，
--    是 VARCHAR(30) 而不是 TINYINT：这些码要出现在日志、看板、接口响应里，
--    可读性比省几个字节重要得多 —— 排查线上问题时「MAP_BREAKER_OPEN」
--    比「2204」有用。
--
--    【token 列允许 NULL】失败时可能压根没拿到 usage，用 NULL 表示「没有数据」，
--    不要写 0 —— 0 会污染 SUM 出来的成本统计（把「不知道」算成「没花钱」）。
-- ============================================================
DROP TABLE IF EXISTS ai_generation_log;
CREATE TABLE ai_generation_log (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  user_id           BIGINT UNSIGNED DEFAULT NULL            COMMENT '触发用户ID，系统任务为 NULL',
  trip_id           BIGINT UNSIGNED DEFAULT NULL            COMMENT '关联行程ID，解析阶段可能还没有行程',
  stage             VARCHAR(20)   NOT NULL                  COMMENT '管线阶段 PARSE/CANDIDATE/PREORDER/COMPOSE/VALIDATE/ROUTE/COPY',
  provider          VARCHAR(20)   DEFAULT NULL              COMMENT '厂商 glm/deepseek/mock/baidu',
  model             VARCHAR(50)   DEFAULT NULL              COMMENT '模型名，如 glm-4-flash',
  prompt_tokens     INT           DEFAULT NULL              COMMENT '输入 token；拿不到为 NULL，不要写 0',
  completion_tokens INT           DEFAULT NULL              COMMENT '输出 token；拿不到为 NULL',
  total_tokens      INT           DEFAULT NULL              COMMENT '总 token；拿不到为 NULL',
  duration_ms       INT           DEFAULT NULL              COMMENT '本阶段耗时（毫秒）',
  success           TINYINT       NOT NULL DEFAULT 0        COMMENT '是否成功 0否 1是',
  error_code        VARCHAR(30)   DEFAULT NULL              COMMENT '统一错误码，取值见 AiErrorCode；成功时为 NULL',
  error_msg         VARCHAR(500)  DEFAULT NULL              COMMENT '失败详情（已脱敏）',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (id),
  KEY idx_user_created (user_id, created_at),
  KEY idx_stage_success (stage, success),
  KEY idx_trip_id (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI生成日志表(成本与质量证据链)';

-- ============================================================
-- 脚本执行完毕：本文件当前含 8 张表
--   sys_config           运行时开关（P1-A，P2-C 补 llm.price.* 两条）
--   poi_cache            POI 本地缓存（P1-C，P2-C 补 provider/keyword/fetched_at/expires_at）
--   external_call_log    外部调用日志（P1-D）
--   user_travel_profile  用户旅行偏好画像（P2-A）
--   trip                 行程主表（P2-B）
--   trip_day             行程日（P2-B）
--   trip_item            行程条目（P2-B，数据诚信机制落点）
--   ai_generation_log    AI 生成日志（P2-C，成本与质量证据链）
-- 至此与《开发文档》§8.2 的「行程域与治理域 8 张表」完全对齐，P2 阶段收官。
-- 下一阶段 P3 起不再新建表（除非 P4-C 的成本统计需要补列）。
-- ============================================================
