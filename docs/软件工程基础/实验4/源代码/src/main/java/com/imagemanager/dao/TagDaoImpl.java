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
    private static final int SEARCH_RESULT_LIMIT = 200;
    private static volatile boolean searchSchemaReady = false;

    // ==================== 标签分类 ====================

    @Override
    public List<TagCategory> findAllCategories() {
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
        if (categories == null || tagNames == null || confidences == null) {
            return;
        }

        int count = Math.min(categories.length, Math.min(tagNames.length, confidences.length));
        if (count == 0) {
            return;
        }

        String categorySql = """
                INSERT INTO tag_categories (name, display_name)
                VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET display_name = tag_categories.display_name
                RETURNING id
                """;
        String tagSql = """
                INSERT INTO tags (category_id, name)
                VALUES (?, ?)
                ON CONFLICT (category_id, name) DO UPDATE SET created_at = tags.created_at
                RETURNING id
                """;
        String linkSql = """
                INSERT INTO image_tags (image_id, tag_id, confidence, source)
                VALUES (?, ?, ?, 'AI')
                ON CONFLICT (image_id, tag_id) DO UPDATE SET
                    confidence = EXCLUDED.confidence,
                    source = EXCLUDED.source
                """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement categoryStmt = conn.prepareStatement(categorySql);
             PreparedStatement tagStmt = conn.prepareStatement(tagSql);
             PreparedStatement linkStmt = conn.prepareStatement(linkSql)) {
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < count; i++) {
                    String category = normalizeTagValue(categories[i]);
                    String tagName = normalizeTagValue(tagNames[i]);
                    if (category.isEmpty() || tagName.isEmpty()) {
                        continue;
                    }

                    categoryStmt.setString(1, category);
                    categoryStmt.setString(2, category);
                    int categoryId;
                    try (ResultSet rs = categoryStmt.executeQuery()) {
                        if (!rs.next()) {
                            continue;
                        }
                        categoryId = rs.getInt("id");
                    }

                    tagStmt.setInt(1, categoryId);
                    tagStmt.setString(2, tagName);
                    int tagId;
                    try (ResultSet rs = tagStmt.executeQuery()) {
                        if (!rs.next()) {
                            continue;
                        }
                        tagId = rs.getInt("id");
                    }

                    linkStmt.setInt(1, imageId);
                    linkStmt.setInt(2, tagId);
                    linkStmt.setFloat(3, confidences[i]);
                    linkStmt.addBatch();
                }

                linkStmt.executeBatch();
                conn.commit();
                logger.debug("批量插入标签完成: imageId={}, 标签数={}", imageId, count);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("批量插入标签失败: imageId={}", imageId, e);
        }
    }

    private String normalizeTagValue(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== AI分析结果 ====================

    @Override
    public void saveAnalysisResult(ImageAnalysisResult result) {
        ensureSearchSchema();
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
        ensureSearchSchema();
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
        ensureSearchSchema();
        return searchImagesByKeywordInternal(keyword, null);
    }

    @Override
    public List<Integer> searchImagesByKeyword(String keyword, int directoryId) {
        ensureSearchSchema();
        return searchImagesByKeywordInternal(keyword, directoryId);
    }

    private List<Integer> searchImagesByKeywordInternal(String keyword, Integer rootDirectoryId) {
        List<Integer> ids = new ArrayList<>();
        List<List<String>> termGroups = buildSearchTermGroups(keyword);
        if (termGroups.isEmpty()) {
            return ids;
        }
        String phrase = keyword.trim().toLowerCase(Locale.ROOT);
        String compactPhrase = compactSearchText(keyword);

        StringBuilder sql = new StringBuilder();
        if (rootDirectoryId != null) {
            sql.append("""
                    WITH RECURSIVE dir_tree AS (
                        SELECT id FROM directories WHERE id = ?
                        UNION ALL
                        SELECT d.id FROM directories d
                        JOIN dir_tree dt ON d.parent_id = dt.id
                    ),
                    """);
        } else {
            sql.append("WITH ");
        }

        sql.append("""
                tag_summary AS (
                    SELECT
                        it.image_id,
                        STRING_AGG(DISTINCT t.name, ' ') AS tag_names,
                        STRING_AGG(DISTINCT CONCAT_WS(' ', tc.name, tc.display_name), ' ') AS tag_categories
                    FROM image_tags it
                    JOIN tags t ON it.tag_id = t.id
                    LEFT JOIN tag_categories tc ON t.category_id = tc.id
                    GROUP BY it.image_id
                ),
                searchable AS (
                    SELECT
                        i.id,
                        LOWER(COALESCE(i.file_name, '')) AS file_name_text,
                        LOWER(CONCAT_WS(' ',
                            d.dir_name,
                            d.dir_path,
                            i.file_path,
                            i.format,
                            i.width::text,
                            i.height::text,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || '×' || i.height::text END,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || 'x' || i.height::text END,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || i.height::text END,
                            i.file_size::text,
                            ROUND((i.file_size::numeric / 1024), 1)::text || ' KB',
                            ROUND((i.file_size::numeric / 1048576), 2)::text || ' MB',
                            TO_CHAR(i.created_at, 'YYYY-MM-DD HH24:MI:SS'),
                            TO_CHAR(i.modified_at, 'YYYY-MM-DD HH24:MI:SS'),
                            i.file_hash
                        )) AS metadata_text,
                        LOWER(CONCAT_WS(' ', ts.tag_names, ts.tag_categories)) AS tag_text,
                        LOWER(CONCAT_WS(' ', ar.description, ar.raw_response, ar.people_count::text, ar.model_used)) AS ai_text,
                        LOWER(CONCAT_WS(' ',
                            i.file_name,
                            i.file_path,
                            d.dir_name,
                            d.dir_path,
                            i.format,
                            i.width::text,
                            i.height::text,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || '×' || i.height::text END,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || 'x' || i.height::text END,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || i.height::text END,
                            i.file_size::text,
                            ROUND((i.file_size::numeric / 1024), 1)::text || ' KB',
                            ROUND((i.file_size::numeric / 1048576), 2)::text || ' MB',
                            TO_CHAR(i.created_at, 'YYYY-MM-DD HH24:MI:SS'),
                            TO_CHAR(i.modified_at, 'YYYY-MM-DD HH24:MI:SS'),
                            i.file_hash,
                            ts.tag_names,
                            ts.tag_categories,
                            ar.description,
                            ar.raw_response,
                            ar.people_count::text,
                            ar.model_used
                        )) AS search_text,
                        LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(CONCAT_WS('',
                            i.file_name,
                            i.file_path,
                            d.dir_name,
                            d.dir_path,
                            i.format,
                            i.width::text,
                            i.height::text,
                            CASE WHEN i.width > 0 AND i.height > 0 THEN i.width::text || i.height::text END,
                            i.file_size::text,
                            i.file_hash,
                            ts.tag_names,
                            ts.tag_categories,
                            ar.description,
                            ar.raw_response,
                            ar.people_count::text,
                            ar.model_used
                        ), ' ', ''), '_', ''), '-', ''), '.', ''), '×', ''), '/', ''), ':', '')) AS compact_text
                    FROM images i
                    JOIN directories d ON i.directory_id = d.id
                    LEFT JOIN ai_analysis_results ar ON i.id = ar.image_id
                    LEFT JOIN tag_summary ts ON i.id = ts.image_id
                    WHERE i.is_deleted = FALSE
                """);

        if (rootDirectoryId != null) {
            sql.append("      AND i.directory_id IN (SELECT id FROM dir_tree)\n");
        }

        sql.append("""
                )
                SELECT id
                FROM searchable
                WHERE 1 = 1
                """);

        for (List<String> group : termGroups) {
            sql.append("  AND (\n");
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) {
                    sql.append("       OR ");
                } else {
                    sql.append("       ");
                }
                sql.append("""
                    (
                           search_text LIKE ? ESCAPE '\\'
                        OR compact_text LIKE ? ESCAPE '\\'
                        OR compact_text LIKE ? ESCAPE '\\'
                    )
                    """);
            }
            sql.append("      )\n");
        }

        sql.append("""
                ORDER BY (
                      CASE WHEN file_name_text LIKE ? ESCAPE '\\' THEN 120 ELSE 0 END
                    + CASE WHEN tag_text LIKE ? ESCAPE '\\' THEN 90 ELSE 0 END
                    + CASE WHEN ai_text LIKE ? ESCAPE '\\' THEN 70 ELSE 0 END
                    + CASE WHEN metadata_text LIKE ? ESCAPE '\\' THEN 50 ELSE 0 END
                    + CASE WHEN search_text LIKE ? ESCAPE '\\' THEN 25 ELSE 0 END
                    + CASE WHEN compact_text LIKE ? ESCAPE '\\' THEN 18 ELSE 0 END
                """);

        for (List<String> group : termGroups) {
            for (String ignored : group) {
                sql.append("""
                        + CASE WHEN file_name_text LIKE ? ESCAPE '\\' THEN 60 ELSE 0 END
                        + CASE WHEN tag_text LIKE ? ESCAPE '\\' THEN 50 ELSE 0 END
                        + CASE WHEN ai_text LIKE ? ESCAPE '\\' THEN 40 ELSE 0 END
                        + CASE WHEN metadata_text LIKE ? ESCAPE '\\' THEN 30 ELSE 0 END
                        + CASE WHEN search_text LIKE ? ESCAPE '\\' THEN 15 ELSE 0 END
                        + CASE WHEN compact_text LIKE ? ESCAPE '\\' THEN 10 ELSE 0 END
                        """);
            }
        }

        sql.append(") DESC, file_name_text, id LIMIT ").append(SEARCH_RESULT_LIMIT);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIndex = 1;
            if (rootDirectoryId != null) {
                ps.setInt(paramIndex++, rootDirectoryId);
            }
            for (List<String> group : termGroups) {
                for (String term : group) {
                    String compactTerm = compactSearchText(term);
                    ps.setString(paramIndex++, likePattern(term.toLowerCase(Locale.ROOT)));
                    ps.setString(paramIndex++, likePattern(compactTerm));
                    ps.setString(paramIndex++, wildcardLikePattern(compactTerm));
                }
            }

            String phrasePattern = likePattern(phrase);
            String compactPhrasePattern = likePattern(compactPhrase);
            ps.setString(paramIndex++, phrasePattern);
            ps.setString(paramIndex++, phrasePattern);
            ps.setString(paramIndex++, phrasePattern);
            ps.setString(paramIndex++, phrasePattern);
            ps.setString(paramIndex++, phrasePattern);
            ps.setString(paramIndex++, compactPhrasePattern);

            for (List<String> group : termGroups) {
                for (String term : group) {
                    String termPattern = likePattern(term.toLowerCase(Locale.ROOT));
                    String compactTermPattern = likePattern(compactSearchText(term));
                    ps.setString(paramIndex++, termPattern);
                    ps.setString(paramIndex++, termPattern);
                    ps.setString(paramIndex++, termPattern);
                    ps.setString(paramIndex++, termPattern);
                    ps.setString(paramIndex++, termPattern);
                    ps.setString(paramIndex++, compactTermPattern);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            logger.error("关键词搜索失败: keyword={}, rootDirectoryId={}", keyword, rootDirectoryId, e);
        }
        return ids;
    }

    private List<List<String>> buildSearchTermGroups(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        String[] split = normalized.split("[\\s,，;；]+");
        List<List<String>> groups = new ArrayList<>();
        for (String term : split) {
            if (!term.isBlank()) {
                groups.add(expandSynonyms(term));
            }
        }
        return groups;
    }

    private List<String> expandSynonyms(String term) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(term);

        Map<String, List<String>> synonymGroups = Map.ofEntries(
                Map.entry("海边", List.of("海滩", "沙滩", "海岸", "海景", "海洋")),
                Map.entry("海滩", List.of("海边", "沙滩", "海岸", "海景", "海洋")),
                Map.entry("风景", List.of("景色", "自然", "户外", "景观")),
                Map.entry("人物", List.of("人像", "人", "男生", "女生", "男人", "女人", "合影")),
                Map.entry("人像", List.of("人物", "人", "肖像", "自拍")),
                Map.entry("合影", List.of("人物", "多人", "集体照", "全家福")),
                Map.entry("汽车", List.of("车辆", "车", "轿车", "跑车")),
                Map.entry("车辆", List.of("汽车", "车", "交通工具")),
                Map.entry("猫", List.of("猫咪", "小猫", "宠物")),
                Map.entry("狗", List.of("狗狗", "小狗", "宠物")),
                Map.entry("夜晚", List.of("夜景", "晚上", "黑夜")),
                Map.entry("夜景", List.of("夜晚", "晚上", "黑夜")),
                Map.entry("建筑", List.of("楼房", "房子", "城市", "室内")),
                Map.entry("截图", List.of("屏幕截图", "截屏", "桌面")),
                Map.entry("游戏", List.of("电竞", "界面", "截图")),
                Map.entry("文字", List.of("文本", "字幕", "标语", "屏幕文字")),
                Map.entry("美食", List.of("食物", "餐饮", "饭菜", "甜点")),
                Map.entry("蓝色", List.of("蓝", "主色调蓝")),
                Map.entry("红色", List.of("红", "主色调红")),
                Map.entry("绿色", List.of("绿", "主色调绿"))
        );

        terms.addAll(synonymGroups.getOrDefault(term, List.of()));
        return new ArrayList<>(terms);
    }

    private String compactSearchText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-.×/:]+", "");
    }

    private String likePattern(String text) {
        return "%" + escapeLike(text == null ? "" : text) + "%";
    }

    private String wildcardLikePattern(String text) {
        if (text == null || text.length() < 2) {
            return likePattern(text);
        }
        StringBuilder pattern = new StringBuilder("%");
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) {
                pattern.append('%');
            }
            pattern.append(escapeLike(String.valueOf(text.charAt(i))));
        }
        pattern.append('%');
        return pattern.toString();
    }

    private String escapeLike(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Override
    public List<Integer> executeSearchSQL(String sql) {
        ensureSearchSchema();
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
            if (trimmed.matches(".*\\b" + word + "\\b.*")) {
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
                stmt.setMaxRows(1000);
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
            throw new RuntimeException("执行AI生成SQL失败: " + e.getMessage(), e);
        }
        return ids;
    }

    private void ensureSearchSchema() {
        if (searchSchemaReady) {
            return;
        }

        synchronized (TagDaoImpl.class) {
            if (searchSchemaReady) {
                return;
            }

            try (Connection conn = DatabaseConnection.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64)
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS ai_processed BOOLEAN NOT NULL DEFAULT FALSE
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS last_ai_scan TIMESTAMP
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS deleted_original_path TEXT
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS deleted_storage_path TEXT
                        """);
                stmt.execute("""
                        ALTER TABLE images
                        ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP
                        """);
                stmt.execute("ALTER TABLE images DROP CONSTRAINT IF EXISTS uq_images_dir_name");
                stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_images_active_dir_name_unique
                        ON images (directory_id, file_name) WHERE is_deleted = FALSE
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tag_categories (
                            id SERIAL PRIMARY KEY,
                            name VARCHAR(50) NOT NULL UNIQUE,
                            display_name VARCHAR(100) NOT NULL,
                            description TEXT
                        )
                        """);
                stmt.execute("""
                        INSERT INTO tag_categories (name, display_name, description) VALUES
                            ('scene', '场景', '图片整体场景'),
                            ('object', '物体', '图片中的主要物体'),
                            ('person', '人物', '图片中的人物'),
                            ('celebrity', '名人', '识别出的名人'),
                            ('color', '主色调', '图片主要颜色'),
                            ('emotion', '情绪', '图片氛围'),
                            ('action', '动作', '人物或物体动作'),
                            ('text_content', '文字内容', '图片中出现的文字'),
                            ('animal', '动物', '图片中的动物'),
                            ('food', '食物', '图片中的食物'),
                            ('location', '地点', '推测的地点'),
                            ('count_people', '人数', '图片中的人数')
                        ON CONFLICT (name) DO NOTHING
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tags (
                            id SERIAL PRIMARY KEY,
                            category_id INTEGER NOT NULL REFERENCES tag_categories(id) ON DELETE CASCADE,
                            name VARCHAR(255) NOT NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                            UNIQUE(category_id, name)
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS image_tags (
                            id SERIAL PRIMARY KEY,
                            image_id INTEGER NOT NULL REFERENCES images(id) ON DELETE CASCADE,
                            tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                            confidence REAL NOT NULL DEFAULT 1.0,
                            source VARCHAR(20) NOT NULL DEFAULT 'AI',
                            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                            UNIQUE(image_id, tag_id)
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS ai_analysis_results (
                            id SERIAL PRIMARY KEY,
                            image_id INTEGER NOT NULL UNIQUE REFERENCES images(id) ON DELETE CASCADE,
                            raw_response TEXT NOT NULL DEFAULT '',
                            description TEXT,
                            people_count INTEGER DEFAULT 0,
                            analyzed_at TIMESTAMP NOT NULL DEFAULT NOW(),
                            model_used VARCHAR(100)
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS search_history (
                            id SERIAL PRIMARY KEY,
                            query_text TEXT NOT NULL,
                            search_mode VARCHAR(20) NOT NULL,
                            generated_sql TEXT,
                            result_count INTEGER DEFAULT 0,
                            searched_at TIMESTAMP NOT NULL DEFAULT NOW()
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tags_name ON tags (name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_image_tags_image ON image_tags (image_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_image_tags_tag ON image_tags (tag_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ai_results_image ON ai_analysis_results (image_id)");
                // PostgreSQL 的 CREATE OR REPLACE VIEW 不能改变既有列名或插入新列。
                // 该视图不保存数据，直接重建可以兼容旧版 file_hash-only 结构。
                stmt.execute("DROP VIEW IF EXISTS v_image_search");
                stmt.execute("""
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
                                 i.file_size, i.width, i.height, i.format, i.thumbnail,
                                 i.file_hash, i.created_at, i.modified_at, d.dir_name,
                                 d.dir_path, ar.description, ar.raw_response,
                                 ar.people_count, ar.model_used
                        """);

                searchSchemaReady = true;
                logger.info("搜索相关数据库结构已确认");
            } catch (SQLException e) {
                logger.error("初始化搜索数据库结构失败", e);
                throw new RuntimeException("初始化搜索数据库结构失败: " + e.getMessage(), e);
            }
        }
    }
}
