package com.imagemanager.dao;

import com.imagemanager.model.ImageAnalysisResult;
import com.imagemanager.model.Tag;
import com.imagemanager.model.TagCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * 标签 DAO 实现 — 使用 JDBC 操作标签体系和AI分析结果表。
 */
public class TagDaoImpl implements TagDao {

    private static final Logger logger = LoggerFactory.getLogger(TagDaoImpl.class);

    // ==================== 标签分类 ====================

    @Override
    public List<TagCategory> findAllCategories() {
        String sql = "SELECT id, name, display_name, description FROM tag_categories ORDER BY id";
        List<TagCategory> categories = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                categories.add(new TagCategory(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("display_name"),
                        rs.getString("description")
                ));
            }
        } catch (SQLException e) {
            logger.error("查询标签分类失败", e);
        }
        return categories;
    }

    @Override
    public Optional<TagCategory> findCategoryByName(String name) {
        String sql = "SELECT id, name, display_name, description FROM tag_categories WHERE name = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new TagCategory(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("display_name"),
                            rs.getString("description")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("查询标签分类失败: name={}", name, e);
        }
        return Optional.empty();
    }

    // ==================== 标签 ====================

    @Override
    public Tag findOrCreateTag(int categoryId, String tagName) {
        String upsertSql = """
                INSERT INTO tags (category_id, name)
                VALUES (?, ?)
                ON CONFLICT (category_id, name) DO UPDATE SET created_at = tags.created_at
                RETURNING id, category_id, name, created_at
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setInt(1, categoryId);
            ps.setString(2, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Tag(
                            rs.getInt("id"),
                            rs.getInt("category_id"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toLocalDateTime()
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("查找或创建标签失败: categoryId={}, name={}", categoryId, tagName, e);
        }
        throw new RuntimeException("无法创建标签: " + tagName);
    }

    @Override
    public List<Tag> findTagsByImageId(int imageId) {
        String sql = """
                SELECT t.id, t.category_id, t.name, t.created_at
                FROM tags t
                JOIN image_tags it ON t.id = it.tag_id
                WHERE it.image_id = ?
                ORDER BY t.category_id, t.name
                """;
        List<Tag> tags = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.add(new Tag(
                            rs.getInt("id"),
                            rs.getInt("category_id"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("查询图片标签失败: imageId={}", imageId, e);
        }
        return tags;
    }

    @Override
    public List<Tag> searchTags(String keyword) {
        String sql = """
                SELECT id, category_id, name, created_at
                FROM tags
                WHERE name ILIKE ?
                ORDER BY name
                LIMIT 50
                """;
        List<Tag> tags = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tags.add(new Tag(
                            rs.getInt("id"),
                            rs.getInt("category_id"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("搜索标签失败: keyword={}", keyword, e);
        }
        return tags;
    }

    // ==================== 图片-标签关联 ====================

    @Override
    public void linkImageTag(int imageId, int tagId, float confidence, String source) {
        String sql = """
                INSERT INTO image_tags (image_id, tag_id, confidence, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (image_id, tag_id) DO UPDATE SET confidence = EXCLUDED.confidence
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            ps.setInt(2, tagId);
            ps.setFloat(3, confidence);
            ps.setString(4, source);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("关联图片标签失败: imageId={}, tagId={}", imageId, tagId, e);
        }
    }

    @Override
    public void unlinkImageTag(int imageId, int tagId) {
        String sql = "DELETE FROM image_tags WHERE image_id = ? AND tag_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            ps.setInt(2, tagId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("删除图片标签关联失败: imageId={}, tagId={}", imageId, tagId, e);
        }
    }

    @Override
    public void batchInsertTags(int imageId, String[] categories, String[] tagNames, float[] confidences) {
        String sql = "CALL sp_batch_insert_tags(?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            ps.setArray(2, conn.createArrayOf("TEXT", categories));
            ps.setArray(3, conn.createArrayOf("TEXT", tagNames));

            // 转换 float[] 为 Float[] 以便创建数组
            Float[] confBoxed = new Float[confidences.length];
            for (int i = 0; i < confidences.length; i++) {
                confBoxed[i] = confidences[i];
            }
            ps.setArray(4, conn.createArrayOf("REAL", confBoxed));

            ps.execute();
            logger.debug("批量插入标签完成: imageId={}, 标签数={}", imageId, tagNames.length);
        } catch (SQLException e) {
            logger.error("批量插入标签失败: imageId={}", imageId, e);
        }
    }

    // ==================== AI分析结果 ====================

    @Override
    public void saveAnalysisResult(ImageAnalysisResult result) {
        String sql = """
                INSERT INTO ai_analysis_results (image_id, raw_response, description, people_count, model_used)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (image_id) DO UPDATE SET
                    raw_response = EXCLUDED.raw_response,
                    description = EXCLUDED.description,
                    people_count = EXCLUDED.people_count,
                    model_used = EXCLUDED.model_used,
                    analyzed_at = NOW()
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, result.imageId());
            ps.setString(2, result.rawResponse());
            ps.setString(3, result.description());
            ps.setInt(4, result.peopleCount());
            ps.setString(5, result.modelUsed());
            ps.executeUpdate();
            logger.debug("保存AI分析结果: imageId={}", result.imageId());
        } catch (SQLException e) {
            logger.error("保存AI分析结果失败: imageId={}", result.imageId(), e);
        }
    }

    @Override
    public Optional<ImageAnalysisResult> findAnalysisResult(int imageId) {
        String sql = """
                SELECT id, image_id, raw_response, description, people_count, analyzed_at, model_used
                FROM ai_analysis_results
                WHERE image_id = ?
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ImageAnalysisResult(
                            rs.getInt("id"),
                            rs.getInt("image_id"),
                            rs.getString("raw_response"),
                            rs.getString("description"),
                            rs.getInt("people_count"),
                            rs.getTimestamp("analyzed_at").toLocalDateTime(),
                            rs.getString("model_used"),
                            null // tagsByCategory 不从这里加载
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("查询AI分析结果失败: imageId={}", imageId, e);
        }
        return Optional.empty();
    }

    // ==================== 搜索 ====================

    @Override
    public List<Integer> searchImagesByKeyword(String keyword) {
        String sql = """
                SELECT DISTINCT id FROM v_image_search
                WHERE file_name ILIKE ?
                   OR all_tags ILIKE ?
                   OR ai_description ILIKE ?
                ORDER BY id
                LIMIT 200
                """;
        List<Integer> ids = new ArrayList<>();
        String pattern = "%" + keyword + "%";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            logger.error("关键词搜索失败: keyword={}", keyword, e);
        }
        return ids;
    }

    @Override
    public List<Integer> executeSearchSQL(String sql) {
        // 安全校验：只允许 SELECT 语句
        String trimmed = sql.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            logger.warn("拒绝执行非SELECT语句: {}", sql);
            throw new SecurityException("只允许执行 SELECT 查询语句");
        }
        // 禁止危险关键词
        String[] forbidden = {"INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "GRANT", "REVOKE"};
        for (String word : forbidden) {
            // 检查是否作为独立关键词出现（前后有空格或在开头/结尾）
            if (trimmed.matches(".*\\b" + word + "\\b.*") && !word.equals("CREATE")) {
                // CREATE 可能出现在子查询中，额外检查
                if (word.equals("DELETE") && trimmed.contains("IS_DELETED")) continue;
                logger.warn("拒绝执行包含 {} 的语句: {}", word, sql);
                throw new SecurityException("SQL语句包含不允许的关键词: " + word);
            }
        }

        List<Integer> ids = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            // 设置只读和超时
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(5); // 5秒超时
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        ids.add(rs.getInt("id"));
                    }
                }
            } finally {
                conn.setReadOnly(false);
            }
        } catch (SQLException e) {
            logger.error("执行搜索SQL失败: {}", sql, e);
        }
        return ids;
    }
}
