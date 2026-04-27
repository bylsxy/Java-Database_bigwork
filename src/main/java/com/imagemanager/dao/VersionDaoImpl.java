package com.imagemanager.dao;

import com.imagemanager.model.ImageVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 版本管理 DAO 实现 — 使用 JDBC 操作 image_versions 表。
 */
public class VersionDaoImpl implements VersionDao {

    private static final Logger logger = LoggerFactory.getLogger(VersionDaoImpl.class);

    @Override
    public List<ImageVersion> findByImageId(int imageId) {
        String sql = """
                SELECT id, image_id, version_num, file_path, file_size,
                       width, height, thumbnail, edit_type, description,
                       created_at, is_current
                FROM image_versions
                WHERE image_id = ?
                ORDER BY version_num ASC
                """;
        List<ImageVersion> versions = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询图片版本失败: imageId={}", imageId, e);
        }
        return versions;
    }

    @Override
    public Optional<ImageVersion> findCurrentVersion(int imageId) {
        String sql = """
                SELECT id, image_id, version_num, file_path, file_size,
                       width, height, thumbnail, edit_type, description,
                       created_at, is_current
                FROM image_versions
                WHERE image_id = ? AND is_current = TRUE
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询当前版本失败: imageId={}", imageId, e);
        }
        return Optional.empty();
    }

    @Override
    public ImageVersion createVersion(ImageVersion version) {
        // 先取消当前版本标记
        String resetSql = "UPDATE image_versions SET is_current = FALSE WHERE image_id = ?";
        String insertSql = """
                INSERT INTO image_versions (image_id, version_num, file_path, file_size,
                    width, height, thumbnail, edit_type, description, is_current)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                RETURNING id, created_at
                """;
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 取消旧的当前版本
                try (PreparedStatement ps = conn.prepareStatement(resetSql)) {
                    ps.setInt(1, version.imageId());
                    ps.executeUpdate();
                }

                // 插入新版本
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setInt(1, version.imageId());
                    ps.setInt(2, version.versionNum());
                    ps.setString(3, version.filePath());
                    ps.setLong(4, version.fileSize());
                    ps.setInt(5, version.width());
                    ps.setInt(6, version.height());
                    ps.setBytes(7, version.thumbnail());
                    ps.setString(8, version.editType());
                    ps.setString(9, version.description());

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            conn.commit();
                            return new ImageVersion(
                                    rs.getInt("id"),
                                    version.imageId(),
                                    version.versionNum(),
                                    version.filePath(),
                                    version.fileSize(),
                                    version.width(),
                                    version.height(),
                                    version.thumbnail(),
                                    version.editType(),
                                    version.description(),
                                    rs.getTimestamp("created_at").toLocalDateTime(),
                                    true
                            );
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("创建版本失败: imageId={}", version.imageId(), e);
        }
        return version;
    }

    @Override
    public void restoreVersion(int imageId, int versionId) {
        String sql = "CALL sp_restore_version(?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            ps.setInt(2, versionId);
            ps.execute();
            logger.info("版本恢复成功: imageId={}, versionId={}", imageId, versionId);
        } catch (SQLException e) {
            logger.error("版本恢复失败: imageId={}, versionId={}", imageId, versionId, e);
        }
    }

    @Override
    public int countVersions(int imageId) {
        String sql = "SELECT COUNT(*) FROM image_versions WHERE image_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("统计版本数量失败: imageId={}", imageId, e);
        }
        return 0;
    }

    private ImageVersion mapRow(ResultSet rs) throws SQLException {
        return new ImageVersion(
                rs.getInt("id"),
                rs.getInt("image_id"),
                rs.getInt("version_num"),
                rs.getString("file_path"),
                rs.getLong("file_size"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getBytes("thumbnail"),
                rs.getString("edit_type"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getBoolean("is_current")
        );
    }
}
