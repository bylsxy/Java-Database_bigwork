-- ============================================================
-- 基于 PostgreSQL 的数字图像集成管理系统 — 数据库 Schema v2.0
--
-- 数据库: image_manager
-- PostgreSQL 版本: 18.3
--
-- v2.0 变更:
--   · 新增 AI 图像识别标签体系 (tag_categories, tags, image_tags)
--   · 新增 AI 分析结果缓存表 (ai_analysis_results)
--   · 新增 应用设置表 (app_settings)
--   · 新增 搜索历史表 (search_history)
--   · 新增 图片版本历史表 (image_versions)
--   · 新增 编辑操作记录表 (image_edit_operations)
--   · 新增 云服务配置表 (cloud_sources)
--   · 新增 云端图片缓存表 (cloud_images)
--   · images 表新增 file_hash, ai_processed, last_ai_scan 字段
--   · 新增全文搜索视图 v_image_search
--   · 新增版本管理存储过程 sp_restore_version
--
-- 使用方式:
--   psql -U postgres -c "CREATE DATABASE image_manager ENCODING 'UTF8';"
--   psql -U postgres -d image_manager -f schema.sql
-- ============================================================

-- 确保使用 UTF-8 编码
SET client_encoding TO 'UTF8';

-- ============================================================
-- 1. 核心基础表
-- ============================================================

-- 目录表：通过 parent_id 自引用实现树形结构
-- 每条记录对应磁盘上的一个文件夹
CREATE TABLE IF NOT EXISTS directories (
    id          SERIAL       PRIMARY KEY,
    dir_name    VARCHAR(255) NOT NULL,              -- 目录名称，如 "Sample Pictures"
    dir_path    TEXT         NOT NULL UNIQUE,        -- 完整路径，如 "C:\Users\Pictures"
    parent_id   INTEGER      REFERENCES directories(id) ON DELETE CASCADE,  -- 父目录，根目录为 NULL
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()  -- 首次录入时间
);

COMMENT ON TABLE directories IS '目录表 — 磁盘文件夹的数据库映射，通过 parent_id 自引用实现树结构';
COMMENT ON COLUMN directories.parent_id IS '父目录ID，磁盘根目录（如C:\）时为 NULL';

-- 图像文件表：系统的核心数据表
-- 每条记录对应一个图片文件，包含元数据和缩略图
CREATE TABLE IF NOT EXISTS images (
    id            SERIAL       PRIMARY KEY,
    file_name     VARCHAR(255) NOT NULL,              -- 文件名（含扩展名），如 "photo.jpg"
    file_path     TEXT         NOT NULL,              -- 磁盘上的完整文件路径
    directory_id  INTEGER      NOT NULL REFERENCES directories(id) ON DELETE CASCADE,  -- 所属目录
    file_size     BIGINT,                             -- 文件大小（字节）
    width         INTEGER,                            -- 图片宽度（像素）
    height        INTEGER,                            -- 图片高度（像素）
    format        VARCHAR(10),                        -- 格式：JPG, JPEG, GIF, PNG, BMP
    thumbnail     BYTEA,                              -- 缩略图二进制数据（通常 10~50 KB）
    file_hash     VARCHAR(64),                        -- SHA-256哈希，唯一绑定本地文件（v2.0新增）
    ai_processed  BOOLEAN      NOT NULL DEFAULT FALSE,-- 是否已完成AI识别处理（v2.0新增）
    last_ai_scan  TIMESTAMP,                          -- 上次AI扫描时间（v2.0新增）
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(), -- 首次录入数据库时间
    modified_at   TIMESTAMP    NOT NULL DEFAULT NOW(), -- 最后修改时间
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE, -- 逻辑删除标记

    -- 同一目录下文件名唯一（排除已逻辑删除的）
    CONSTRAINT uq_images_dir_name UNIQUE (file_name, directory_id)
);

-- 兼容已运行过 v1.x 脚本的数据库：CREATE TABLE IF NOT EXISTS 不会自动补齐新增字段。
ALTER TABLE images ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64);
ALTER TABLE images ADD COLUMN IF NOT EXISTS ai_processed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE images ADD COLUMN IF NOT EXISTS last_ai_scan TIMESTAMP;
ALTER TABLE images ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE images ADD COLUMN IF NOT EXISTS modified_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE images ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON TABLE images IS '图像文件表 — 存储图片元数据和缩略图(bytea)，支持逻辑删除和AI标签';
COMMENT ON COLUMN images.thumbnail IS '缩略图二进制数据，使用 bytea 类型存储，首次加载目录时生成';
COMMENT ON COLUMN images.is_deleted IS '逻辑删除标记，TRUE 表示已删除但记录保留用于审计';
COMMENT ON COLUMN images.file_hash IS 'SHA-256文件哈希，用于唯一标识图片文件，避免重复处理';
COMMENT ON COLUMN images.ai_processed IS 'AI识别状态标记，FALSE表示尚未进行AI分析';

-- 操作日志表：记录对图片的所有操作
-- 图片新增/重命名/删除由触发器写入；复制/粘贴等交互操作由应用层补充写入
CREATE TABLE IF NOT EXISTS operation_logs (
    id              SERIAL       PRIMARY KEY,
    image_id        INTEGER      REFERENCES images(id) ON DELETE SET NULL,  -- 图片被物理删除后保留日志
    operation_type  VARCHAR(20)  NOT NULL,                                   -- INSERT/RENAME/DELETE/HARD_DELETE/COPY/PASTE
    old_value       TEXT,                                                    -- 操作前的值（如旧文件名）
    new_value       TEXT,                                                    -- 操作后的值（如新文件名）
    operated_at     TIMESTAMP    NOT NULL DEFAULT NOW()                      -- 操作时间
);

COMMENT ON TABLE operation_logs IS '操作日志表 — 记录图片增删改、复制粘贴和标签变更操作，由触发器与应用层共同维护';
COMMENT ON COLUMN operation_logs.image_id IS '关联图片ID，图片物理删除后此字段变为 NULL（ON DELETE SET NULL）';

-- ============================================================
-- 2. AI图像识别标签体系（v2.0新增）
-- ============================================================

-- 标签分类表：定义标签的大类
CREATE TABLE IF NOT EXISTS tag_categories (
    id           SERIAL       PRIMARY KEY,
    name         VARCHAR(50)  NOT NULL UNIQUE,   -- 英文标识：scene, object, person, celebrity, color, emotion, action, text_content, animal, food, location, count_people
    display_name VARCHAR(100) NOT NULL,           -- 中文显示名
    description  TEXT                              -- 分类描述
);

COMMENT ON TABLE tag_categories IS '标签分类表 — 定义AI识别标签的大类，如场景、物体、人物、名人等';

-- 预插入标签分类
INSERT INTO tag_categories (name, display_name, description) VALUES
    ('scene',        '场景',   '图片整体场景，如瀑布、海滩、办公室、教室'),
    ('object',       '物体',   '图片中的主要物体，如汽车、花朵、建筑'),
    ('person',       '人物',   '图片中的普通人物描述'),
    ('celebrity',    '名人',   '图片中识别出的名人，如爱因斯坦、乔布斯'),
    ('color',        '主色调', '图片的主要颜色，如红色、蓝色、暖色调'),
    ('emotion',      '情绪',   '图片传达的情绪/氛围，如欢乐、宁静、紧张'),
    ('action',       '动作',   '图片中人物或物体的动作，如跑步、跳舞、飞行'),
    ('text_content', '文字内容', '图片中出现的文字，如标牌、标语'),
    ('animal',       '动物',   '图片中的动物，如猫、狗、鸟'),
    ('food',         '食物',   '图片中的食物，如蛋糕、水果、饮料'),
    ('location',     '地点',   '推测的拍摄地点，如巴黎、长城、校园'),
    ('count_people', '人数',   '图片中的人数，如1、2、3、多人')
ON CONFLICT (name) DO NOTHING;

-- 标签表：具体的标签值
CREATE TABLE IF NOT EXISTS tags (
    id          SERIAL       PRIMARY KEY,
    category_id INTEGER      NOT NULL REFERENCES tag_categories(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,            -- 标签值，如 "瀑布", "爱因斯坦", "3"
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE(category_id, name)
);

COMMENT ON TABLE tags IS '标签表 — 存储具体的标签值，每个标签属于一个分类';

-- 图片-标签关联表（多对多）
CREATE TABLE IF NOT EXISTS image_tags (
    id          SERIAL       PRIMARY KEY,
    image_id    INTEGER      NOT NULL REFERENCES images(id) ON DELETE CASCADE,
    tag_id      INTEGER      NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    confidence  REAL         NOT NULL DEFAULT 1.0,     -- AI识别置信度 0.0~1.0
    source      VARCHAR(20)  NOT NULL DEFAULT 'AI',    -- 'AI' / 'MANUAL' 来源
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE(image_id, tag_id)
);

COMMENT ON TABLE image_tags IS '图片-标签关联表 — 多对多关系，记录每张图片的所有标签及置信度';

-- AI完整分析结果缓存表
CREATE TABLE IF NOT EXISTS ai_analysis_results (
    id           SERIAL       PRIMARY KEY,
    image_id     INTEGER      NOT NULL UNIQUE REFERENCES images(id) ON DELETE CASCADE,
    raw_response TEXT         NOT NULL,                -- AI原始返回JSON
    description  TEXT,                                 -- AI生成的一句话自然语言描述
    people_count INTEGER      DEFAULT 0,               -- 识别到的人数
    analyzed_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    model_used   VARCHAR(100)                          -- 使用的模型名称
);

COMMENT ON TABLE ai_analysis_results IS 'AI分析结果缓存表 — 保存每张图片的完整AI分析结果（原始JSON+结构化数据）';

-- ============================================================
-- 3. 应用设置与搜索历史（v2.0新增）
-- ============================================================

-- 应用设置表：键值对存储应用配置
CREATE TABLE IF NOT EXISTS app_settings (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE app_settings IS '应用设置表 — 键值对存储扫描目录、显示偏好等非敏感配置';

-- 预插入默认设置
INSERT INTO app_settings (key, value) VALUES
    ('scan_directory',     ''),                                   -- 用户首次启动时选择
    ('show_welcome',       'true'),                               -- 是否显示首次启动向导
    ('thumbnail_storage',  'database'),                            -- database / none
    ('slideshow_interval', '3'),                                   -- 幻灯片播放间隔（秒）
    ('slideshow_order',    'SEQUENTIAL'),                          -- SEQUENTIAL / RANDOM
    ('slideshow_music',    'none')                                 -- none / music_1 / music_2 / music_3
ON CONFLICT (key) DO NOTHING;

-- 搜索历史表
CREATE TABLE IF NOT EXISTS search_history (
    id           SERIAL       PRIMARY KEY,
    query_text   TEXT         NOT NULL,             -- 用户原始输入
    search_mode  VARCHAR(20)  NOT NULL,             -- 'KEYWORD' / 'AI_SQL'
    generated_sql TEXT,                              -- AI生成的SQL（仅AI模式）
    result_count INTEGER      DEFAULT 0,            -- 结果数量
    searched_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE search_history IS '搜索历史表 — 记录用户的搜索查询，支持关键词和AI-SQL两种模式';

-- ============================================================
-- 4. 图片编辑与版本历史（v2.0新增）
-- ============================================================

-- 图片版本历史表
CREATE TABLE IF NOT EXISTS image_versions (
    id           SERIAL       PRIMARY KEY,
    image_id     INTEGER      NOT NULL REFERENCES images(id) ON DELETE CASCADE,
    version_num  INTEGER      NOT NULL DEFAULT 1,        -- 版本号
    file_path    TEXT         NOT NULL,                   -- 该版本文件的磁盘路径
    file_size    BIGINT,
    width        INTEGER,
    height       INTEGER,
    thumbnail    BYTEA,                                   -- 该版本的缩略图
    edit_type    VARCHAR(50),                              -- 'ORIGINAL'/'CROP'/'ANNOTATE'/'DRAW'/'RESTORE'
    description  TEXT,                                     -- 编辑描述
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    is_current   BOOLEAN      NOT NULL DEFAULT FALSE,     -- 是否是当前版本
    UNIQUE(image_id, version_num)
);

COMMENT ON TABLE image_versions IS '图片版本历史表 — 保存每次编辑产生的新版本，支持时间轴浏览和快照恢复';

-- 编辑操作记录表
CREATE TABLE IF NOT EXISTS image_edit_operations (
    id           SERIAL       PRIMARY KEY,
    version_id   INTEGER      NOT NULL REFERENCES image_versions(id) ON DELETE CASCADE,
    operation    VARCHAR(50)  NOT NULL,            -- 'CROP','DRAW_LINE','ADD_TEXT','ADD_ARROW','ADD_RECT'
    parameters   JSONB        NOT NULL,            -- 操作参数JSON
    op_order     INTEGER      NOT NULL,            -- 操作顺序
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE image_edit_operations IS '编辑操作记录表 — 记录每个版本中的具体编辑操作（JSON参数）';

-- ============================================================
-- 5. 云服务集成（v2.0新增）
-- ============================================================

-- 云服务配置表
CREATE TABLE IF NOT EXISTS cloud_sources (
    id          SERIAL       PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,              -- 如 "我的百度网盘", "NAS"
    type        VARCHAR(50)  NOT NULL,              -- 'WEBDAV' / 'BAIDU_PAN' / 'ONEDRIVE'
    config      JSONB        NOT NULL,              -- 连接配置 {"url":"...","token":"..."}
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE cloud_sources IS '云服务配置表 — 存储WebDAV/百度网盘等云端存储的连接配置';

-- 云端图片缓存表
CREATE TABLE IF NOT EXISTS cloud_images (
    id           SERIAL       PRIMARY KEY,
    source_id    INTEGER      NOT NULL REFERENCES cloud_sources(id) ON DELETE CASCADE,
    remote_path  TEXT         NOT NULL,              -- 云端路径
    file_name    VARCHAR(255) NOT NULL,
    file_size    BIGINT,
    thumbnail    BYTEA,                              -- 云端缩略图缓存
    local_cache  TEXT,                               -- 本地缓存路径（如已下载）
    synced_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE(source_id, remote_path)
);

COMMENT ON TABLE cloud_images IS '云端图片缓存表 — 缓存云端图片的元信息和缩略图，避免重复拉取';

-- ============================================================
-- 6. 索引设计
-- ============================================================

-- === 核心表索引 ===

-- 按目录查询图片 — 系统最频繁的查询，每次点击目录都会触发
CREATE INDEX IF NOT EXISTS idx_images_directory_id ON images (directory_id);

-- 按文件名搜索 — 重命名时检查重复、搜索功能
CREATE INDEX IF NOT EXISTS idx_images_file_name ON images (file_name);

-- 活跃图片部分索引 — 只索引未删除的记录，优化过滤查询
CREATE INDEX IF NOT EXISTS idx_images_active ON images (directory_id) WHERE is_deleted = FALSE;

-- 文件哈希唯一索引 — 用于快速查找已存在的图片（增量扫描）
CREATE UNIQUE INDEX IF NOT EXISTS idx_images_hash ON images (file_hash) WHERE file_hash IS NOT NULL;

-- AI处理状态索引 — 快速定位未处理的图片
CREATE INDEX IF NOT EXISTS idx_images_ai_pending ON images (ai_processed) WHERE ai_processed = FALSE AND is_deleted = FALSE;

-- 目录树层级查询 — 展开目录时查找子目录
CREATE INDEX IF NOT EXISTS idx_directories_parent_id ON directories (parent_id);

-- 按路径查找目录 — findOrCreate 操作
CREATE INDEX IF NOT EXISTS idx_directories_path ON directories (dir_path);

-- 操作日志按图片查询 — 查看某张图片的操作历史
CREATE INDEX IF NOT EXISTS idx_operation_logs_image_id ON operation_logs (image_id);

-- 操作日志按时间查询 — 报表统计、按时间范围筛选
CREATE INDEX IF NOT EXISTS idx_operation_logs_time ON operation_logs (operated_at);

-- === AI标签体系索引 ===

-- 标签名称索引 — 关键词搜索
CREATE INDEX IF NOT EXISTS idx_tags_name ON tags (name);

-- 标签分类索引
CREATE INDEX IF NOT EXISTS idx_tags_category ON tags (category_id);

-- 图片-标签关联索引（双向查询加速）
CREATE INDEX IF NOT EXISTS idx_image_tags_image ON image_tags (image_id);
CREATE INDEX IF NOT EXISTS idx_image_tags_tag ON image_tags (tag_id);

-- AI分析结果索引
CREATE INDEX IF NOT EXISTS idx_ai_results_image ON ai_analysis_results (image_id);

-- === 版本历史索引 ===
CREATE INDEX IF NOT EXISTS idx_versions_image ON image_versions (image_id);
CREATE INDEX IF NOT EXISTS idx_versions_current ON image_versions (image_id) WHERE is_current = TRUE;

-- === 云服务索引 ===
CREATE INDEX IF NOT EXISTS idx_cloud_images_source ON cloud_images (source_id);

-- === 全文搜索GIN索引 — 加速标签名称和AI描述的模糊搜索 ===
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_tags_name_trgm ON tags USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_ai_desc_trgm ON ai_analysis_results USING GIN (description gin_trgm_ops);

-- ============================================================
-- 7. 视图
-- ============================================================

-- 兼容旧版视图结构升级：CREATE OR REPLACE VIEW 不能改变既有列名和列顺序。
DROP VIEW IF EXISTS v_tag_stats;
DROP VIEW IF EXISTS v_image_search;
DROP VIEW IF EXISTS v_directory_stats;
DROP VIEW IF EXISTS v_active_images;

-- 活跃图片视图：过滤掉逻辑删除的记录，关联目录信息
CREATE OR REPLACE VIEW v_active_images AS
SELECT
    i.id,
    i.file_name,
    i.file_path,
    i.directory_id,
    i.file_size,
    i.width,
    i.height,
    i.format,
    i.thumbnail,
    i.file_hash,
    i.ai_processed,
    i.created_at,
    i.modified_at,
    d.dir_name,
    d.dir_path AS directory_path
FROM images i
JOIN directories d ON i.directory_id = d.id
WHERE i.is_deleted = FALSE;

COMMENT ON VIEW v_active_images IS '活跃图片视图 — 排除逻辑删除记录，关联目录名称和路径';

-- 目录统计视图：每个目录的图片数量和总大小
CREATE OR REPLACE VIEW v_directory_stats AS
SELECT
    d.id AS directory_id,
    d.dir_name,
    d.dir_path,
    COUNT(i.id) AS image_count,
    COALESCE(SUM(i.file_size), 0) AS total_size
FROM directories d
LEFT JOIN images i ON d.id = i.directory_id AND i.is_deleted = FALSE
GROUP BY d.id, d.dir_name, d.dir_path;

COMMENT ON VIEW v_directory_stats IS '目录统计视图 — 汇总每个目录的活跃图片数量和总大小（字节）';

-- 全文搜索视图（v2.0新增）：聚合所有标签，用于关键词和AI搜索
CREATE OR REPLACE VIEW v_image_search AS
SELECT
    i.id,
    i.file_name,
    i.file_path,
    i.directory_id,
    i.file_size,
    i.width,
    i.height,
    i.format,
    i.thumbnail,
    i.file_hash,
    i.created_at,
    i.modified_at,
    d.dir_name,
    d.dir_path AS directory_path,
    ar.description AS ai_description,
    ar.raw_response AS ai_raw_response,
    ar.people_count,
    ar.model_used,
    STRING_AGG(DISTINCT t.name, ', ' ORDER BY t.name) AS all_tags
FROM images i
JOIN directories d ON i.directory_id = d.id
LEFT JOIN ai_analysis_results ar ON i.id = ar.image_id
LEFT JOIN image_tags it ON i.id = it.image_id
LEFT JOIN tags t ON it.tag_id = t.id
WHERE i.is_deleted = FALSE
GROUP BY i.id, i.file_name, i.file_path, i.directory_id,
         i.file_size, i.width, i.height, i.format, i.thumbnail, i.file_hash,
         i.created_at, i.modified_at, d.dir_name, d.dir_path,
         ar.description, ar.raw_response, ar.people_count, ar.model_used;

COMMENT ON VIEW v_image_search IS '全文搜索视图 — 聚合图片元数据、AI描述和所有标签，支持关键词/NL2SQL搜索';

-- 标签统计视图（v2.0新增）：每个标签被使用的次数
CREATE OR REPLACE VIEW v_tag_stats AS
SELECT
    tc.name AS category_name,
    tc.display_name AS category_display,
    t.id AS tag_id,
    t.name AS tag_name,
    COUNT(it.id) AS usage_count
FROM tag_categories tc
JOIN tags t ON tc.id = t.category_id
LEFT JOIN image_tags it ON t.id = it.tag_id
GROUP BY tc.name, tc.display_name, t.id, t.name
ORDER BY usage_count DESC;

COMMENT ON VIEW v_tag_stats IS '标签统计视图 — 显示每个标签的使用次数，便于热门标签展示';

-- ============================================================
-- 8. 触发器函数 & 触发器
-- ============================================================

-- 触发器函数：当新图片被插入时，自动在 operation_logs 中写入 INSERT 记录
CREATE OR REPLACE FUNCTION fn_log_image_insert()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO operation_logs (image_id, operation_type, new_value)
    VALUES (NEW.id, 'INSERT', NEW.file_name);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_log_image_insert() IS '触发器函数 — 图片新增时自动记录日志';

-- 触发器函数：当图片记录被更新时，捕获重命名和逻辑删除操作
CREATE OR REPLACE FUNCTION fn_log_image_update()
RETURNS TRIGGER AS $$
BEGIN
    -- 场景1: 文件名发生变更 → 记录 RENAME 操作
    IF OLD.file_name IS DISTINCT FROM NEW.file_name THEN
        INSERT INTO operation_logs (image_id, operation_type, old_value, new_value)
        VALUES (NEW.id, 'RENAME', OLD.file_name, NEW.file_name);
    END IF;

    -- 场景2: is_deleted 从 FALSE 变为 TRUE → 记录 DELETE 操作
    IF OLD.is_deleted = FALSE AND NEW.is_deleted = TRUE THEN
        INSERT INTO operation_logs (image_id, operation_type, old_value)
        VALUES (NEW.id, 'DELETE', OLD.file_name);
    END IF;

    -- 自动更新 modified_at 为当前时间
    NEW.modified_at = NOW();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_log_image_update() IS '触发器函数 — 图片更新时自动记录重命名或逻辑删除日志';

-- 触发器函数：当图片记录被物理删除时，记录 HARD_DELETE 日志
CREATE OR REPLACE FUNCTION fn_log_image_delete()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO operation_logs (image_id, operation_type, old_value)
    VALUES (OLD.id, 'HARD_DELETE', OLD.file_name);
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_log_image_delete() IS '触发器函数 — 图片物理删除时自动记录日志';

-- 触发器函数（v2.0新增）：AI标签变更时记录日志
CREATE OR REPLACE FUNCTION fn_log_tag_change()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO operation_logs (image_id, operation_type, new_value)
        VALUES (NEW.image_id, 'TAG_ADD', (SELECT name FROM tags WHERE id = NEW.tag_id));
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO operation_logs (image_id, operation_type, old_value)
        VALUES (OLD.image_id, 'TAG_REMOVE', (SELECT name FROM tags WHERE id = OLD.tag_id));
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_log_tag_change() IS '触发器函数 — 图片标签增删时自动记录日志（v2.0新增）';

-- 绑定触发器到 images 表
DROP TRIGGER IF EXISTS trg_image_after_insert ON images;
DROP TRIGGER IF EXISTS trg_image_before_update ON images;
DROP TRIGGER IF EXISTS trg_image_after_delete ON images;

CREATE TRIGGER trg_image_after_insert
    AFTER INSERT ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_insert();

CREATE TRIGGER trg_image_before_update
    BEFORE UPDATE ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_update();

CREATE TRIGGER trg_image_after_delete
    AFTER DELETE ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_delete();

-- 绑定标签变更触发器（v2.0新增）
DROP TRIGGER IF EXISTS trg_tag_after_insert ON image_tags;
DROP TRIGGER IF EXISTS trg_tag_after_delete ON image_tags;

CREATE TRIGGER trg_tag_after_insert
    AFTER INSERT ON image_tags
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_tag_change();

CREATE TRIGGER trg_tag_after_delete
    AFTER DELETE ON image_tags
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_tag_change();

-- ============================================================
-- 9. 存储过程
-- ============================================================

-- 存储过程1: 按月生成操作统计报表
CREATE OR REPLACE PROCEDURE sp_monthly_report(
    IN p_year   INTEGER,
    IN p_month  INTEGER,
    INOUT p_result REFCURSOR DEFAULT 'monthly_report_cursor'
)
LANGUAGE plpgsql AS $$
BEGIN
    OPEN p_result FOR
    SELECT
        operation_type                          AS "操作类型",
        COUNT(*)                                AS "操作次数",
        DATE_TRUNC('day', operated_at)::DATE    AS "操作日期"
    FROM operation_logs
    WHERE EXTRACT(YEAR FROM operated_at) = p_year
      AND EXTRACT(MONTH FROM operated_at) = p_month
    GROUP BY operation_type, DATE_TRUNC('day', operated_at)
    ORDER BY "操作日期", "操作类型";
END;
$$;

COMMENT ON PROCEDURE sp_monthly_report IS '存储过程 — 生成按月统计的操作报表（每日各类操作次数）';

-- 存储过程2: 目录空间统计报表（递归CTE）
CREATE OR REPLACE PROCEDURE sp_directory_report(
    IN    p_directory_id      INTEGER,
    INOUT p_total_images      INTEGER DEFAULT 0,
    INOUT p_total_size        BIGINT  DEFAULT 0,
    INOUT p_subdirectory_count INTEGER DEFAULT 0
)
LANGUAGE plpgsql AS $$
BEGIN
    SELECT COUNT(i.id), COALESCE(SUM(i.file_size), 0)
    INTO p_total_images, p_total_size
    FROM images i
    WHERE i.is_deleted = FALSE
      AND i.directory_id IN (
          WITH RECURSIVE dir_tree AS (
              SELECT id FROM directories WHERE id = p_directory_id
              UNION ALL
              SELECT d.id FROM directories d
              INNER JOIN dir_tree dt ON d.parent_id = dt.id
          )
          SELECT id FROM dir_tree
      );

    SELECT COUNT(*) INTO p_subdirectory_count
    FROM (
        WITH RECURSIVE dir_tree AS (
            SELECT id FROM directories WHERE parent_id = p_directory_id
            UNION ALL
            SELECT d.id FROM directories d
            INNER JOIN dir_tree dt ON d.parent_id = dt.id
        )
        SELECT id FROM dir_tree
    ) sub;
END;
$$;

COMMENT ON PROCEDURE sp_directory_report IS '存储过程 — 递归统计目录及子目录的图片总数、总大小、子目录数';

-- 存储过程3: 批量重命名（数据库事务内执行）
CREATE OR REPLACE PROCEDURE sp_batch_rename(
    IN p_image_ids  INTEGER[],
    IN p_new_names  TEXT[]
)
LANGUAGE plpgsql AS $$
DECLARE
    i INTEGER;
BEGIN
    IF array_length(p_image_ids, 1) != array_length(p_new_names, 1) THEN
        RAISE EXCEPTION '图片ID数组和新名称数组长度不一致: % vs %',
            array_length(p_image_ids, 1), array_length(p_new_names, 1);
    END IF;

    FOR i IN 1..array_length(p_image_ids, 1) LOOP
        UPDATE images
        SET file_name = p_new_names[i]
        WHERE id = p_image_ids[i];

        IF NOT FOUND THEN
            RAISE EXCEPTION '图片 ID % 不存在', p_image_ids[i];
        END IF;
    END LOOP;
END;
$$;

COMMENT ON PROCEDURE sp_batch_rename IS '存储过程 — 批量重命名图片，在单个事务中执行，保证全部成功或全部回滚';

-- 存储过程4（v2.0新增）: 版本快照恢复
CREATE OR REPLACE PROCEDURE sp_restore_version(
    IN p_image_id   INTEGER,
    IN p_version_id INTEGER
)
LANGUAGE plpgsql AS $$
DECLARE
    v_file_path TEXT;
    v_width     INTEGER;
    v_height    INTEGER;
    v_file_size BIGINT;
BEGIN
    -- 获取目标版本信息
    SELECT file_path, width, height, file_size
    INTO v_file_path, v_width, v_height, v_file_size
    FROM image_versions
    WHERE id = p_version_id AND image_id = p_image_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION '版本 % 不存在（图片ID: %）', p_version_id, p_image_id;
    END IF;

    -- 取消所有当前版本标记
    UPDATE image_versions SET is_current = FALSE WHERE image_id = p_image_id;

    -- 设置目标版本为当前
    UPDATE image_versions SET is_current = TRUE WHERE id = p_version_id;

    -- 更新images表的主文件路径和元数据
    UPDATE images
    SET file_path = v_file_path,
        width = v_width,
        height = v_height,
        file_size = v_file_size,
        modified_at = NOW()
    WHERE id = p_image_id;

    -- 记录恢复操作日志
    INSERT INTO operation_logs (image_id, operation_type, new_value)
    VALUES (p_image_id, 'VERSION_RESTORE', 'Restored to version ' || p_version_id);
END;
$$;

COMMENT ON PROCEDURE sp_restore_version IS '存储过程 — 恢复图片到指定历史版本（快照恢复），更新images表和版本标记';

-- 存储过程5（v2.0新增）: 批量插入AI标签
CREATE OR REPLACE PROCEDURE sp_batch_insert_tags(
    IN p_image_id    INTEGER,
    IN p_categories  TEXT[],
    IN p_tag_names   TEXT[],
    IN p_confidences REAL[]
)
LANGUAGE plpgsql AS $$
DECLARE
    i INTEGER;
    v_category_id INTEGER;
    v_tag_id INTEGER;
BEGIN
    IF array_length(p_categories, 1) != array_length(p_tag_names, 1) THEN
        RAISE EXCEPTION '分类数组和标签名数组长度不一致';
    END IF;

    FOR i IN 1..array_length(p_categories, 1) LOOP
        -- 查找分类ID
        SELECT id INTO v_category_id FROM tag_categories WHERE name = p_categories[i];
        IF v_category_id IS NULL THEN
            CONTINUE; -- 未知分类跳过
        END IF;

        -- 查找或创建标签
        INSERT INTO tags (category_id, name)
        VALUES (v_category_id, p_tag_names[i])
        ON CONFLICT (category_id, name) DO UPDATE SET created_at = tags.created_at
        RETURNING id INTO v_tag_id;

        -- 关联图片和标签
        INSERT INTO image_tags (image_id, tag_id, confidence, source)
        VALUES (p_image_id, v_tag_id, COALESCE(p_confidences[i], 1.0), 'AI')
        ON CONFLICT (image_id, tag_id) DO UPDATE SET confidence = EXCLUDED.confidence;
    END LOOP;

    -- 标记图片为已处理
    UPDATE images SET ai_processed = TRUE, last_ai_scan = NOW() WHERE id = p_image_id;
END;
$$;

COMMENT ON PROCEDURE sp_batch_insert_tags IS '存储过程 — 为一张图片批量插入AI分析标签（自动创建不存在的标签）';

-- ============================================================
-- 10. 实用查询模板（递归 CTE 示例）
-- ============================================================

-- 示例: 查询某目录的完整目录树（所有后代）
-- WITH RECURSIVE directory_tree AS (
--     SELECT id, dir_name, dir_path, parent_id, 0 AS depth
--     FROM directories
--     WHERE id = :root_id
--     UNION ALL
--     SELECT d.id, d.dir_name, d.dir_path, d.parent_id, dt.depth + 1
--     FROM directories d
--     INNER JOIN directory_tree dt ON d.parent_id = dt.id
-- )
-- SELECT * FROM directory_tree ORDER BY depth, dir_name;

-- 示例: 获取从某目录到根的路径（面包屑导航）
-- WITH RECURSIVE path_to_root AS (
--     SELECT id, dir_name, dir_path, parent_id, 0 AS depth
--     FROM directories
--     WHERE id = :current_id
--     UNION ALL
--     SELECT d.id, d.dir_name, d.dir_path, d.parent_id, ptr.depth + 1
--     FROM directories d
--     INNER JOIN path_to_root ptr ON d.id = ptr.parent_id
-- )
-- SELECT * FROM path_to_root ORDER BY depth DESC;

-- 示例（v2.0新增）: 按标签搜索图片
-- SELECT DISTINCT i.* FROM v_image_search i
-- WHERE i.all_tags ILIKE '%瀑布%'
--    OR i.ai_description ILIKE '%瀑布%'
--    OR i.file_name ILIKE '%瀑布%';

-- ============================================================
-- 建库完成提示
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE '数据库 schema v2.0 初始化完成！';
    RAISE NOTICE '已创建: 13 张表, 19 个索引, 4 个视图, 5 个触发器, 5 个存储过程';
    RAISE NOTICE '新增功能: AI标签体系, 版本历史, 搜索历史, 云服务集成, 应用设置';
END $$;
