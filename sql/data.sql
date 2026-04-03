-- ============================================================
-- 基于 PostgreSQL 的数字图像集成管理系统 — 测试数据
--
-- 注意: 先执行 schema.sql 再执行本文件
-- 使用方式: psql -U postgres -d image_manager -f data.sql
-- ============================================================

SET client_encoding TO 'UTF8';

-- ============================================================
-- 1. 目录测试数据
-- ============================================================

-- 磁盘根目录（parent_id = NULL 表示根目录）
INSERT INTO directories (dir_name, dir_path, parent_id) VALUES
    ('C:', 'C:\', NULL),
    ('D:', 'D:\', NULL);

-- C 盘目录结构
INSERT INTO directories (dir_name, dir_path, parent_id) VALUES
    ('Users', 'C:\Users', 1),
    ('Public', 'C:\Users\Public', 3),
    ('Pictures', 'C:\Users\Public\Pictures', 4),
    ('Sample Pictures', 'C:\Users\Public\Pictures\Sample Pictures', 5);

-- D 盘目录结构
INSERT INTO directories (dir_name, dir_path, parent_id) VALUES
    ('Photos', 'D:\Photos', 2),
    ('2026春游', 'D:\Photos\2026春游', 7),
    ('风景', 'D:\Photos\风景', 7),
    ('人像', 'D:\Photos\人像', 7);

-- ============================================================
-- 2. 图片测试数据
-- ============================================================
-- 注意: thumbnail 字段这里不填充实际二进制数据（应用首次加载时自动生成）
-- 这里的 file_path 和 file_size 为模拟数据，实际运行时会从磁盘读取

-- Sample Pictures 目录下的测试图片（目录 ID = 6）
INSERT INTO images (file_name, file_path, directory_id, file_size, width, height, format) VALUES
    ('1.JPG',  'C:\Users\Public\Pictures\Sample Pictures\1.JPG',  6, 204800, 1200, 552, 'JPG'),
    ('2.JPG',  'C:\Users\Public\Pictures\Sample Pictures\2.JPG',  6, 184320, 1024, 768, 'JPG'),
    ('3.JPG',  'C:\Users\Public\Pictures\Sample Pictures\3.JPG',  6, 163840, 800, 600, 'JPG'),
    ('4.JPG',  'C:\Users\Public\Pictures\Sample Pictures\4.JPG',  6, 245760, 1920, 1080, 'JPG'),
    ('5.JPG',  'C:\Users\Public\Pictures\Sample Pictures\5.JPG',  6, 307200, 1600, 1200, 'JPG'),
    ('6.JPG',  'C:\Users\Public\Pictures\Sample Pictures\6.JPG',  6, 143360, 640, 480, 'JPG'),
    ('7.JPG',  'C:\Users\Public\Pictures\Sample Pictures\7.JPG',  6, 512000, 2560, 1440, 'JPG'),
    ('8.JPG',  'C:\Users\Public\Pictures\Sample Pictures\8.JPG',  6, 276480, 1280, 960, 'JPG');

-- 2026春游 目录下的测试图片（目录 ID = 8）
INSERT INTO images (file_name, file_path, directory_id, file_size, width, height, format) VALUES
    ('春游合影.PNG',   'D:\Photos\2026春游\春游合影.PNG',   8, 1048576, 3840, 2160, 'PNG'),
    ('湖边风景.JPG',   'D:\Photos\2026春游\湖边风景.JPG',   8, 524288,  2560, 1440, 'JPG'),
    ('午餐时光.JPEG',  'D:\Photos\2026春游\午餐时光.JPEG',  8, 368640,  1920, 1080, 'JPEG'),
    ('樱花树下.GIF',   'D:\Photos\2026春游\樱花树下.GIF',   8, 2097152, 800, 600, 'GIF');

-- 风景 目录下的测试图片（目录 ID = 9）
INSERT INTO images (file_name, file_path, directory_id, file_size, width, height, format) VALUES
    ('日落.BMP',      'D:\Photos\风景\日落.BMP',      9, 5242880, 1920, 1080, 'BMP'),
    ('山峦.JPG',      'D:\Photos\风景\山峦.JPG',      9, 819200,  2560, 1600, 'JPG'),
    ('海滩.PNG',      'D:\Photos\风景\海滩.PNG',      9, 1572864, 3000, 2000, 'PNG');

-- 人像 目录下的测试图片（目录 ID = 10）
INSERT INTO images (file_name, file_path, directory_id, file_size, width, height, format) VALUES
    ('证件照.JPG',     'D:\Photos\人像\证件照.JPG',     10, 102400, 413, 579, 'JPG'),
    ('毕业照.JPG',     'D:\Photos\人像\毕业照.JPG',     10, 716800, 2048, 1536, 'JPG');

-- ============================================================
-- 3. 逻辑删除测试数据（用于验证 is_deleted 过滤）
-- ============================================================

-- 标记一张图片为已逻辑删除（测试视图过滤）
UPDATE images SET is_deleted = TRUE WHERE file_name = '6.JPG';

-- ============================================================
-- 4. 验证查询
-- ============================================================

-- 验证活跃图片视图（应该看不到 6.JPG）
-- SELECT * FROM v_active_images WHERE directory_id = 6;

-- 验证目录统计视图
-- SELECT * FROM v_directory_stats;

-- 验证触发器产生的操作日志
-- SELECT * FROM operation_logs ORDER BY operated_at;

-- 验证递归目录查询（从 D: 开始查所有子目录）
-- WITH RECURSIVE dir_tree AS (
--     SELECT id, dir_name, dir_path, parent_id, 0 AS depth FROM directories WHERE id = 2
--     UNION ALL
--     SELECT d.id, d.dir_name, d.dir_path, d.parent_id, dt.depth + 1
--     FROM directories d JOIN dir_tree dt ON d.parent_id = dt.id
-- )
-- SELECT * FROM dir_tree ORDER BY depth, dir_name;

-- 验证目录统计存储过程
-- CALL sp_directory_report(2, NULL, NULL, NULL);

DO $$
BEGIN
    RAISE NOTICE '测试数据导入完成！共导入: 10 个目录, 17 张图片';
END $$;
