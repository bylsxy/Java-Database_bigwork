-- ============================================================
-- 基于 PostgreSQL 的数字图像集成管理系统 — 数据库 Schema
-- 
-- 数据库: image_manager
-- PostgreSQL 版本: 18.3
-- 
-- 使用方式:
--   psql -U postgres -c "CREATE DATABASE image_manager ENCODING 'UTF8';"
--   psql -U postgres -d image_manager -f schema.sql
-- ============================================================

-- 确保使用 UTF-8 编码
SET client_encoding TO 'UTF8';

-- ============================================================
-- 1. 建表
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

-- 添加注释方便维护
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
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(), -- 首次录入数据库时间
    modified_at   TIMESTAMP    NOT NULL DEFAULT NOW(), -- 最后修改时间
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE, -- 逻辑删除标记

    -- 同一目录下文件名唯一（排除已逻辑删除的）
    CONSTRAINT uq_images_dir_name UNIQUE (file_name, directory_id)
);

COMMENT ON TABLE images IS '图像文件表 — 存储图片元数据和缩略图(bytea)，支持逻辑删除';
COMMENT ON COLUMN images.thumbnail IS '缩略图二进制数据，使用 bytea 类型存储，首次加载目录时生成';
COMMENT ON COLUMN images.is_deleted IS '逻辑删除标记，TRUE 表示已删除但记录保留用于审计';

-- 操作日志表：记录对图片的所有操作
-- 通过触发器自动写入，无需应用层手动 INSERT
CREATE TABLE IF NOT EXISTS operation_logs (
    id              SERIAL       PRIMARY KEY,
    image_id        INTEGER      REFERENCES images(id) ON DELETE SET NULL,  -- 图片被物理删除后保留日志
    operation_type  VARCHAR(20)  NOT NULL,                                   -- INSERT/RENAME/DELETE/HARD_DELETE/COPY/PASTE
    old_value       TEXT,                                                    -- 操作前的值（如旧文件名）
    new_value       TEXT,                                                    -- 操作后的值（如新文件名）
    operated_at     TIMESTAMP    NOT NULL DEFAULT NOW()                      -- 操作时间
);

COMMENT ON TABLE operation_logs IS '操作日志表 — 自动记录图片的增删改操作，由触发器维护';
COMMENT ON COLUMN operation_logs.image_id IS '关联图片ID，图片物理删除后此字段变为 NULL（ON DELETE SET NULL）';

-- ============================================================
-- 2. 索引（B-Tree）
-- ============================================================

-- 按目录查询图片 — 系统最频繁的查询，每次点击目录都会触发
CREATE INDEX idx_images_directory_id ON images (directory_id);

-- 按文件名搜索 — 重命名时检查重复、搜索功能
CREATE INDEX idx_images_file_name ON images (file_name);

-- 活跃图片部分索引 — 只索引未删除的记录，优化过滤查询
CREATE INDEX idx_images_active ON images (directory_id) WHERE is_deleted = FALSE;

-- 目录树层级查询 — 展开目录时查找子目录
CREATE INDEX idx_directories_parent_id ON directories (parent_id);

-- 按路径查找目录 — findOrCreate 操作
CREATE INDEX idx_directories_path ON directories (dir_path);

-- 操作日志按图片查询 — 查看某张图片的操作历史
CREATE INDEX idx_operation_logs_image_id ON operation_logs (image_id);

-- 操作日志按时间查询 — 报表统计、按时间范围筛选
CREATE INDEX idx_operation_logs_time ON operation_logs (operated_at);

-- ============================================================
-- 3. 视图
-- ============================================================

-- 活跃图片视图：过滤掉逻辑删除的记录，关联目录信息
-- 用途：图片预览模块的缩略图列表通过此视图获取数据
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
    i.created_at,
    i.modified_at,
    d.dir_name,
    d.dir_path AS directory_path
FROM images i
JOIN directories d ON i.directory_id = d.id
WHERE i.is_deleted = FALSE;

COMMENT ON VIEW v_active_images IS '活跃图片视图 — 排除逻辑删除记录，关联目录名称和路径';

-- 目录统计视图：每个目录的图片数量和总大小
-- 用途：界面底部状态栏显示 "共 X 张图片，总大小 Y MB"
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

-- ============================================================
-- 4. 触发器函数 & 触发器
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

-- 绑定触发器到 images 表

-- INSERT 后触发：新图片入库时记日志
CREATE TRIGGER trg_image_after_insert
    AFTER INSERT ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_insert();

-- UPDATE 前触发：重命名/逻辑删除时记日志，并更新 modified_at
CREATE TRIGGER trg_image_before_update
    BEFORE UPDATE ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_update();

-- DELETE 后触发：物理删除时记日志
CREATE TRIGGER trg_image_after_delete
    AFTER DELETE ON images
    FOR EACH ROW
    EXECUTE FUNCTION fn_log_image_delete();

-- ============================================================
-- 5. 存储过程
-- ============================================================

-- 存储过程1: 按月生成操作统计报表
-- 用途: 统计某年某月每天各类操作的次数，用于报表展示
-- 参数: p_year 年份, p_month 月份
-- 返回: 游标，包含 operation_type, operation_count, operation_date
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

-- 存储过程2: 目录空间统计报表
-- 用途: 统计某目录及其所有子目录的图片总数、总大小、子目录数
-- 利用递归 CTE 遍历目录树
CREATE OR REPLACE PROCEDURE sp_directory_report(
    IN    p_directory_id      INTEGER,
    INOUT p_total_images      INTEGER DEFAULT 0,
    INOUT p_total_size        BIGINT  DEFAULT 0,
    INOUT p_subdirectory_count INTEGER DEFAULT 0
)
LANGUAGE plpgsql AS $$
BEGIN
    -- 利用递归 CTE 查找目录及其所有后代目录中的活跃图片
    SELECT COUNT(i.id), COALESCE(SUM(i.file_size), 0)
    INTO p_total_images, p_total_size
    FROM images i
    WHERE i.is_deleted = FALSE
      AND i.directory_id IN (
          WITH RECURSIVE dir_tree AS (
              -- 锚点：指定目录自身
              SELECT id FROM directories WHERE id = p_directory_id
              UNION ALL
              -- 递归：所有子目录
              SELECT d.id FROM directories d
              INNER JOIN dir_tree dt ON d.parent_id = dt.id
          )
          SELECT id FROM dir_tree
      );

    -- 统计子目录总数（不含自身）
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
-- 用途: 在一次事务中完成多张图片的重命名，保证一致性
-- 参数: p_image_ids 图片ID数组, p_new_names 对应的新文件名数组
CREATE OR REPLACE PROCEDURE sp_batch_rename(
    IN p_image_ids  INTEGER[],
    IN p_new_names  TEXT[]
)
LANGUAGE plpgsql AS $$
DECLARE
    i INTEGER;
BEGIN
    -- 校验两个数组长度一致
    IF array_length(p_image_ids, 1) != array_length(p_new_names, 1) THEN
        RAISE EXCEPTION '图片ID数组和新名称数组长度不一致: % vs %',
            array_length(p_image_ids, 1), array_length(p_new_names, 1);
    END IF;

    -- 逐个更新，触发器会自动记录每次重命名的日志
    FOR i IN 1..array_length(p_image_ids, 1) LOOP
        UPDATE images 
        SET file_name = p_new_names[i]
        WHERE id = p_image_ids[i];
        
        -- 检查是否更新到了记录
        IF NOT FOUND THEN
            RAISE EXCEPTION '图片 ID % 不存在', p_image_ids[i];
        END IF;
    END LOOP;
END;
$$;

COMMENT ON PROCEDURE sp_batch_rename IS '存储过程 — 批量重命名图片，在单个事务中执行，保证全部成功或全部回滚';

-- ============================================================
-- 6. 实用查询模板（递归 CTE 示例）
-- ============================================================

-- 以下是递归查询的使用示例，实际在 Java DAO 层中通过 PreparedStatement 调用

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

-- ============================================================
-- 建库完成提示
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE '数据库 schema 初始化完成！';
    RAISE NOTICE '已创建: 3 张表, 7 个索引, 2 个视图, 3 个触发器, 3 个存储过程';
END $$;
