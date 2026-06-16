package com.imagemanager.dao;

import com.imagemanager.model.ImageFile;
import com.imagemanager.model.FileOperationLog;
import com.imagemanager.model.RecycleBinItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 图片数据访问实现 — 封装所有针对 images 表的 SQL 操作。
 * <p>
 * 所有方法使用 PreparedStatement 参数化查询，防止 SQL 注入。
 * 所有数据库连接使用 try-with-resources 确保自动释放。
 */
public class ImageDaoImpl implements ImageDao {

    private static final Logger logger = LoggerFactory.getLogger(ImageDaoImpl.class);
    private static boolean schemaChecked = false;

    // ==================== SQL 常量 ====================
    // 集中管理 SQL 语句，方便维护和审查

    /** 查询活跃图片（通过视图） */
    private static final String SQL_FIND_BY_DIRECTORY =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at, is_deleted, ai_processed " +
            "FROM images WHERE directory_id = ? AND is_deleted = FALSE " +
            "ORDER BY file_name";

    /** 按 ID 查询 */
    private static final String SQL_FIND_BY_ID =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at, is_deleted, ai_processed " +
            "FROM images WHERE id = ?";

    /** 按文件路径查询 */
    private static final String SQL_FIND_BY_PATH =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at, is_deleted, ai_processed " +
            "FROM images WHERE file_path = ? AND is_deleted = FALSE";

    /** 查询缺少尺寸的活跃图片，不加载缩略图，避免一次性读出大块 bytea。 */
    private static final String SQL_FIND_MISSING_DIMENSIONS =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, CAST(NULL AS bytea) AS thumbnail, created_at, modified_at, is_deleted, ai_processed " +
            "FROM images WHERE is_deleted = FALSE " +
            "AND (width IS NULL OR width <= 0 OR height IS NULL OR height <= 0) " +
            "ORDER BY id";

    /** 查询目录下已有文件路径，不加载缩略图。 */
    private static final String SQL_FIND_FILE_PATHS_BY_DIRECTORY =
            "SELECT file_path FROM images WHERE directory_id = ? AND is_deleted = FALSE";

    /** 插入新图片 */
    private static final String SQL_INSERT =
            "INSERT INTO images (file_name, file_path, directory_id, file_size, " +
            "width, height, format, thumbnail) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";

    /** 更新文件名（重命名） */
    private static final String SQL_UPDATE_NAME =
            "UPDATE images SET file_name = ?, file_path = ? WHERE id = ?";

    /** 更新所在目录和路径（剪切移动、移动回滚） */
    private static final String SQL_UPDATE_LOCATION =
            "UPDATE images SET file_name = ?, file_path = ?, directory_id = ?, file_size = ?, " +
            "width = ?, height = ?, thumbnail = ?, modified_at = NOW() WHERE id = ?";

    /** 更新缩略图 */
    private static final String SQL_UPDATE_THUMBNAIL =
            "UPDATE images SET thumbnail = ? WHERE id = ?";

    /** 更新图片尺寸 */
    private static final String SQL_UPDATE_DIMENSIONS =
            "UPDATE images SET width = ?, height = ?, file_size = ?, modified_at = NOW() WHERE id = ?";

    /** 仅标记删除，不负责移动磁盘文件。 */
    private static final String SQL_SOFT_DELETE =
            "UPDATE images SET is_deleted = TRUE WHERE id = ?";

    /** 移入本地回收站 */
    private static final String SQL_MOVE_TO_RECYCLE_BIN =
            "UPDATE images SET is_deleted = TRUE, " +
            "deleted_original_path = COALESCE(deleted_original_path, file_path), " +
            "deleted_storage_path = ?, deleted_at = NOW(), modified_at = NOW() " +
            "WHERE id = ? AND is_deleted = FALSE";

    /** 查询回收站 */
    private static final String SQL_FIND_RECYCLE_BIN =
            "SELECT id, file_name, COALESCE(deleted_original_path, file_path) AS original_path, " +
            "deleted_storage_path, directory_id, file_size, width, height, format, deleted_at " +
            "FROM images WHERE is_deleted = TRUE ORDER BY deleted_at DESC NULLS LAST, id DESC";

    /** 从回收站恢复 */
    private static final String SQL_RESTORE_FROM_RECYCLE_BIN =
            "UPDATE images SET file_name = ?, file_path = ?, directory_id = ?, file_size = ?, " +
            "width = ?, height = ?, thumbnail = ?, is_deleted = FALSE, deleted_original_path = NULL, " +
            "deleted_storage_path = NULL, deleted_at = NULL, modified_at = NOW() WHERE id = ?";

    /** 查询最近一次粘贴/剪切移动同批操作 */
    private static final String SQL_FIND_LATEST_TRANSFER_LOGS =
            """
            WITH latest AS (
                SELECT operation_type, operated_at
                FROM operation_logs
                WHERE operation_type IN ('PASTE', 'MOVE')
                ORDER BY operated_at DESC
                LIMIT 1
            )
            SELECT l.image_id, l.operation_type, l.old_value, l.new_value, l.operated_at
            FROM operation_logs l
            JOIN latest x ON l.operation_type = x.operation_type
            WHERE l.image_id IS NOT NULL
              AND l.operated_at >= x.operated_at - INTERVAL '10 seconds'
              AND l.operated_at <= x.operated_at + INTERVAL '1 second'
            ORDER BY l.operated_at, l.id
            """;

    /** 物理删除 */
    private static final String SQL_HARD_DELETE =
            "DELETE FROM images WHERE id = ?";

    /** 检查同名文件 */
    private static final String SQL_EXISTS_BY_DIR_AND_NAME =
            "SELECT COUNT(*) FROM images WHERE directory_id = ? AND file_name = ? AND is_deleted = FALSE";

    // ==================== 接口实现 ====================

    @Override
    public List<ImageFile> findByDirectoryId(int directoryId) {
        ensureImageStateColumns();
        var images = new ArrayList<ImageFile>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_BY_DIRECTORY)) {

            stmt.setInt(1, directoryId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    images.add(mapRowToImageFile(rs, false));
                }
            }
            logger.debug("查询目录 {} 下的图片: 共 {} 张", directoryId, images.size());

        } catch (SQLException e) {
            logger.error("查询目录 {} 下的图片失败: {}", directoryId, e.getMessage());
            throw new RuntimeException("查询图片失败", e);
        }
        return images;
    }

    @Override
    public Optional<ImageFile> findById(int imageId) {
        ensureImageStateColumns();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_BY_ID)) {

            stmt.setInt(1, imageId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToImageFile(rs, true));
                }
            }
        } catch (SQLException e) {
            logger.error("查询图片 ID={} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("查询图片失败", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ImageFile> findByFilePath(String filePath) {
        ensureImageStateColumns();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_BY_PATH)) {

            stmt.setString(1, filePath);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToImageFile(rs, true));
                }
            }
        } catch (SQLException e) {
            logger.error("按路径查询图片失败: {}", e.getMessage());
            throw new RuntimeException("查询图片失败", e);
        }
        return Optional.empty();
    }

    @Override
    public List<ImageFile> findMissingDimensions() {
        ensureImageStateColumns();
        var images = new ArrayList<ImageFile>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_MISSING_DIMENSIONS);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                images.add(mapRowToImageFile(rs, true));
            }
        } catch (SQLException e) {
            logger.error("查询缺少尺寸的图片失败: {}", e.getMessage());
            throw new RuntimeException("查询缺少尺寸的图片失败", e);
        }
        return images;
    }

    @Override
    public Set<String> findFilePathsByDirectoryId(int directoryId) {
        ensureImageStateColumns();
        Set<String> paths = new HashSet<>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_FILE_PATHS_BY_DIRECTORY)) {
            stmt.setInt(1, directoryId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString("file_path"));
                }
            }
        } catch (SQLException e) {
            logger.error("查询目录 {} 已有图片路径失败: {}", directoryId, e.getMessage());
            throw new RuntimeException("查询图片路径失败", e);
        }
        return paths;
    }

    @Override
    public int insert(ImageFile image) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_INSERT)) {

            setInsertParams(stmt, image);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int generatedId = rs.getInt(1);
                    logger.debug("插入图片成功: id={}, name={}", generatedId, image.fileName());
                    return generatedId;
                }
            }
            throw new SQLException("INSERT 未返回生成的 ID");

        } catch (SQLException e) {
            logger.error("插入图片 {} 失败: {}", image.fileName(), e.getMessage());
            throw new RuntimeException("插入图片失败", e);
        }
    }

    @Override
    public void batchInsert(List<ImageFile> images) {
        if (images.isEmpty()) return;

        // 批量插入用另一条不带 RETURNING 的 SQL（batch 不支持 RETURNING）
        String batchSql = "INSERT INTO images (file_name, file_path, directory_id, " +
                "file_size, width, height, format, thumbnail) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(batchSql)) {

            conn.setAutoCommit(false);
            for (var image : images) {
                setInsertParams(stmt, image);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);

            logger.info("批量插入 {} 张图片成功", images.size());

        } catch (SQLException e) {
            logger.error("批量插入图片失败: {}", e.getMessage());
            throw new RuntimeException("批量插入图片失败", e);
        }
    }

    @Override
    public void updateFileName(int imageId, String newFileName, String newFilePath) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_UPDATE_NAME)) {

            stmt.setString(1, newFileName);
            stmt.setString(2, newFilePath);
            stmt.setInt(3, imageId);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new SQLException("图片 ID=" + imageId + " 不存在");
            }
            logger.debug("重命名图片 id={} → {}", imageId, newFileName);

        } catch (SQLException e) {
            logger.error("重命名图片 {} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("重命名失败", e);
        }
    }

    @Override
    public void updateLocation(int imageId, String newFileName, String newFilePath, int newDirectoryId,
                               long fileSize, int width, int height, byte[] thumbnail) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_UPDATE_LOCATION)) {
            stmt.setString(1, newFileName);
            stmt.setString(2, newFilePath);
            stmt.setInt(3, newDirectoryId);
            stmt.setLong(4, fileSize);
            stmt.setInt(5, width);
            stmt.setInt(6, height);
            if (thumbnail != null) {
                stmt.setBytes(7, thumbnail);
            } else {
                stmt.setNull(7, Types.BINARY);
            }
            stmt.setInt(8, imageId);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new SQLException("图片 ID=" + imageId + " 不存在");
            }
            logger.debug("移动图片 id={} → {}", imageId, newFilePath);
        } catch (SQLException e) {
            logger.error("移动图片 {} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("移动图片失败", e);
        }
    }

    @Override
    public void updateThumbnail(int imageId, byte[] thumbnailData) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_UPDATE_THUMBNAIL)) {

            stmt.setBytes(1, thumbnailData);
            stmt.setInt(2, imageId);
            stmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("更新缩略图 id={} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("更新缩略图失败", e);
        }
    }

    @Override
    public void updateDimensions(int imageId, int width, int height, long fileSize) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_UPDATE_DIMENSIONS)) {
            stmt.setInt(1, width);
            stmt.setInt(2, height);
            stmt.setLong(3, fileSize);
            stmt.setInt(4, imageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("更新图片尺寸失败 id={}: {}", imageId, e.getMessage());
            throw new RuntimeException("更新图片尺寸失败", e);
        }
    }

    @Override
    public void softDelete(int imageId) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_SOFT_DELETE)) {

            stmt.setInt(1, imageId);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                logger.warn("标记删除图片 id={} 时未找到记录", imageId);
            } else {
                logger.debug("标记删除图片 id={}", imageId);
            }

        } catch (SQLException e) {
            logger.error("标记删除图片 {} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("删除图片失败", e);
        }
    }

    @Override
    public void moveToRecycleBin(int imageId, String storagePath) {
        ensureImageStateColumns();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_MOVE_TO_RECYCLE_BIN)) {
            stmt.setString(1, storagePath);
            stmt.setInt(2, imageId);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                logger.warn("移入回收站时未找到活跃图片 id={}", imageId);
            } else {
                logger.debug("图片 id={} 已移入回收站: {}", imageId, storagePath);
            }
        } catch (SQLException e) {
            logger.error("移入回收站失败 imageId={}: {}", imageId, e.getMessage());
            throw new RuntimeException("移入回收站失败", e);
        }
    }

    @Override
    public List<RecycleBinItem> findRecycleBinItems() {
        ensureImageStateColumns();
        List<RecycleBinItem> items = new ArrayList<>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_RECYCLE_BIN);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp deletedAt = rs.getTimestamp("deleted_at");
                items.add(new RecycleBinItem(
                        rs.getInt("id"),
                        rs.getString("file_name"),
                        rs.getString("original_path"),
                        rs.getString("deleted_storage_path"),
                        rs.getInt("directory_id"),
                        rs.getLong("file_size"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getString("format"),
                        deletedAt == null ? null : deletedAt.toLocalDateTime()
                ));
            }
        } catch (SQLException e) {
            logger.error("查询回收站失败: {}", e.getMessage());
            throw new RuntimeException("查询回收站失败", e);
        }
        return items;
    }

    @Override
    public void restoreFromRecycleBin(int imageId, String restoredFileName, String restoredPath, int directoryId,
                                      long fileSize, int width, int height, byte[] thumbnail) {
        ensureImageStateColumns();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_RESTORE_FROM_RECYCLE_BIN)) {
            stmt.setString(1, restoredFileName);
            stmt.setString(2, restoredPath);
            stmt.setInt(3, directoryId);
            stmt.setLong(4, fileSize);
            stmt.setInt(5, width);
            stmt.setInt(6, height);
            if (thumbnail != null) {
                stmt.setBytes(7, thumbnail);
            } else {
                stmt.setNull(7, Types.BINARY);
            }
            stmt.setInt(8, imageId);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new SQLException("图片 ID=" + imageId + " 不存在");
            }
        } catch (SQLException e) {
            logger.error("从回收站恢复失败 imageId={}: {}", imageId, e.getMessage());
            throw new RuntimeException("从回收站恢复失败", e);
        }
    }

    @Override
    public List<FileOperationLog> findLatestTransferLogs() {
        var logs = new ArrayList<FileOperationLog>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_LATEST_TRANSFER_LOGS);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp operatedAt = rs.getTimestamp("operated_at");
                logs.add(new FileOperationLog(
                        rs.getInt("image_id"),
                        rs.getString("operation_type"),
                        rs.getString("old_value"),
                        rs.getString("new_value"),
                        operatedAt == null ? null : operatedAt.toLocalDateTime()
                ));
            }
        } catch (SQLException e) {
            logger.error("查询最近传输操作失败: {}", e.getMessage());
            throw new RuntimeException("查询最近传输操作失败", e);
        }
        return logs;
    }

    @Override
    public void hardDelete(int imageId) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_HARD_DELETE)) {

            stmt.setInt(1, imageId);
            stmt.executeUpdate();
            logger.debug("物理删除图片 id={}", imageId);

        } catch (SQLException e) {
            logger.error("物理删除图片 {} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("物理删除图片失败", e);
        }
    }

    @Override
    public boolean existsByDirectoryAndName(int directoryId, String fileName) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_EXISTS_BY_DIR_AND_NAME)) {

            stmt.setInt(1, directoryId);
            stmt.setString(2, fileName);
            try (var rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            logger.error("检查文件名重复失败: {}", e.getMessage());
            throw new RuntimeException("检查文件名失败", e);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 ResultSet 的当前行映射为 ImageFile record。
     *
     * @param rs             结果集（已定位到当前行）
     * @param includeDeleted 是否包含 is_deleted 字段（视图查询时可能没有此列）
     */
    private ImageFile mapRowToImageFile(ResultSet rs, boolean includeDeleted) throws SQLException {
        return new ImageFile(
                rs.getInt("id"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getInt("directory_id"),
                rs.getLong("file_size"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getString("format"),
                rs.getBytes("thumbnail"),
                toLocalDateTime(rs.getTimestamp("created_at")),
                toLocalDateTime(rs.getTimestamp("modified_at")),
                includeDeleted && rs.getBoolean("is_deleted"),
                rs.getBoolean("ai_processed")
        );
    }

    private static synchronized void ensureImageStateColumns() {
        if (schemaChecked) {
            return;
        }
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS ai_processed BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS last_ai_scan TIMESTAMP");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_original_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_storage_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
            stmt.execute("ALTER TABLE images DROP CONSTRAINT IF EXISTS uq_images_dir_name");
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_images_active_dir_name_unique
                    ON images (directory_id, file_name) WHERE is_deleted = FALSE
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_images_recycle_bin
                    ON images (deleted_at DESC) WHERE is_deleted = TRUE
                    """);
            schemaChecked = true;
        } catch (SQLException e) {
            logger.error("检查图片状态字段失败: {}", e.getMessage());
            throw new RuntimeException("检查图片状态字段失败", e);
        }
    }

    /**
     * 设置 INSERT 语句的 PreparedStatement 参数。
     */
    private void setInsertParams(PreparedStatement stmt, ImageFile image) throws SQLException {
        stmt.setString(1, image.fileName());
        stmt.setString(2, image.filePath());
        stmt.setInt(3, image.directoryId());
        stmt.setLong(4, image.fileSize());
        stmt.setInt(5, image.width());
        stmt.setInt(6, image.height());
        stmt.setString(7, image.format());

        if (image.thumbnail() != null) {
            stmt.setBytes(8, image.thumbnail());
        } else {
            stmt.setNull(8, Types.BINARY);
        }
    }

    /**
     * 安全地将 SQL Timestamp 转换为 LocalDateTime。
     */
    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return (ts != null) ? ts.toLocalDateTime() : LocalDateTime.now();
    }
}
