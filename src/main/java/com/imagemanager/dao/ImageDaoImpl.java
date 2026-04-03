package com.imagemanager.dao;

import com.imagemanager.model.ImageFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 图片数据访问实现 — 封装所有针对 images 表的 SQL 操作。
 * <p>
 * 所有方法使用 PreparedStatement 参数化查询，防止 SQL 注入。
 * 所有数据库连接使用 try-with-resources 确保自动释放。
 */
public class ImageDaoImpl implements ImageDao {

    private static final Logger logger = LoggerFactory.getLogger(ImageDaoImpl.class);

    // ==================== SQL 常量 ====================
    // 集中管理 SQL 语句，方便维护和审查

    /** 查询活跃图片（通过视图） */
    private static final String SQL_FIND_BY_DIRECTORY =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at " +
            "FROM images WHERE directory_id = ? AND is_deleted = FALSE " +
            "ORDER BY file_name";

    /** 按 ID 查询 */
    private static final String SQL_FIND_BY_ID =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at, is_deleted " +
            "FROM images WHERE id = ?";

    /** 按文件路径查询 */
    private static final String SQL_FIND_BY_PATH =
            "SELECT id, file_name, file_path, directory_id, file_size, width, height, " +
            "format, thumbnail, created_at, modified_at, is_deleted " +
            "FROM images WHERE file_path = ? AND is_deleted = FALSE";

    /** 插入新图片 */
    private static final String SQL_INSERT =
            "INSERT INTO images (file_name, file_path, directory_id, file_size, " +
            "width, height, format, thumbnail) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";

    /** 更新文件名（重命名） */
    private static final String SQL_UPDATE_NAME =
            "UPDATE images SET file_name = ?, file_path = ? WHERE id = ?";

    /** 更新缩略图 */
    private static final String SQL_UPDATE_THUMBNAIL =
            "UPDATE images SET thumbnail = ? WHERE id = ?";

    /** 逻辑删除 */
    private static final String SQL_SOFT_DELETE =
            "UPDATE images SET is_deleted = TRUE WHERE id = ?";

    /** 物理删除 */
    private static final String SQL_HARD_DELETE =
            "DELETE FROM images WHERE id = ?";

    /** 检查同名文件 */
    private static final String SQL_EXISTS_BY_DIR_AND_NAME =
            "SELECT COUNT(*) FROM images WHERE directory_id = ? AND file_name = ? AND is_deleted = FALSE";

    // ==================== 接口实现 ====================

    @Override
    public List<ImageFile> findByDirectoryId(int directoryId) {
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
    public void softDelete(int imageId) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_SOFT_DELETE)) {

            stmt.setInt(1, imageId);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                logger.warn("逻辑删除图片 id={} 时未找到记录", imageId);
            } else {
                logger.debug("逻辑删除图片 id={}", imageId);
            }

        } catch (SQLException e) {
            logger.error("逻辑删除图片 {} 失败: {}", imageId, e.getMessage());
            throw new RuntimeException("删除图片失败", e);
        }
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
                includeDeleted && rs.getBoolean("is_deleted")
        );
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
