package com.imagemanager.service;

import com.imagemanager.dao.DatabaseConnection;
import com.imagemanager.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 标签存储统计与清理服务。
 * <p>
 * 本系统不会为每张图片生成独立标签文件，AI 标签和分析结果都保存在 PostgreSQL 数据库中。
 */
public class AiTagStorageService {

    private static final Logger logger = LoggerFactory.getLogger(AiTagStorageService.class);

    public record RelationStorage(String tableName, String relationPath, long bytes) {
        public String formattedSize() {
            return FileUtil.formatFileSize(bytes);
        }
    }

    public record StorageStats(
            String databaseName,
            String dataDirectory,
            long databaseBytes,
            long aiStorageBytes,
            long analyzedImageCount,
            long aiTagLinkCount,
            long totalTagCount,
            long aiOperationLogCount,
            List<RelationStorage> relations
    ) {
        public String formattedDatabaseSize() {
            return FileUtil.formatFileSize(databaseBytes);
        }

        public String formattedAiStorageSize() {
            return FileUtil.formatFileSize(aiStorageBytes);
        }

        public String summaryText() {
            StringBuilder sb = new StringBuilder();
            sb.append("标签数据位置：PostgreSQL 数据库，不是独立图片旁边的文件。\n");
            sb.append("数据库名称：").append(databaseName).append("\n");
            sb.append("数据库数据目录：").append(dataDirectory).append("\n");
            sb.append("数据库总大小：").append(formattedDatabaseSize()).append("\n\n");

            sb.append("当前 AI 标签/分析数据：\n");
            sb.append("已写入 AI 分析结果的图片：").append(analyzedImageCount).append(" 张\n");
            sb.append("AI 标签关联记录：").append(aiTagLinkCount).append(" 条\n");
            sb.append("标签词条总数：").append(totalTagCount).append(" 条\n");
            sb.append("AI 标签操作日志：").append(aiOperationLogCount).append(" 条\n");
            sb.append("估算占用空间：").append(formattedAiStorageSize()).append("\n");
            sb.append("不清理会继续占用约：").append(formattedAiStorageSize()).append("\n\n");

            sb.append("相关数据库物理关系文件：\n");
            for (RelationStorage relation : relations) {
                sb.append(" - ")
                        .append(relation.tableName())
                        .append("：")
                        .append(relation.formattedSize());
                if (relation.relationPath() != null && !relation.relationPath().isBlank()) {
                    sb.append("，").append(dataDirectory).append("\\").append(relation.relationPath());
                }
                sb.append("\n");
            }
            sb.append("\n清理只删除 AI 生成的标签关联和 AI 分析结果，不删除原始图片文件。");
            return sb.toString();
        }
    }

    public record CleanupResult(
            long deletedAnalysisResults,
            long deletedAiTagLinks,
            long deletedOrphanTags,
            long deletedAiTagLogs,
            long resetImageFlags,
            StorageStats before,
            StorageStats after
    ) {
        public String summaryText() {
            return """
                    AI 标签清理完成。

                    删除 AI 分析结果：%d 条
                    删除 AI 标签关联：%d 条
                    删除无引用标签词条：%d 条
                    删除 AI 标签操作日志：%d 条
                    重置 AI 处理状态：%d 张图片

                    清理前估算占用：%s
                    清理后估算占用：%s
                    """.formatted(
                    deletedAnalysisResults,
                    deletedAiTagLinks,
                    deletedOrphanTags,
                    deletedAiTagLogs,
                    resetImageFlags,
                    before.formattedAiStorageSize(),
                    after.formattedAiStorageSize()
            );
        }
    }

    public StorageStats loadStats() {
        ensureCleanupSchema();
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            String databaseName = scalarString(stmt, "SELECT current_database()");
            String dataDirectory = scalarString(stmt, "SHOW data_directory");
            long databaseSize = scalarLong(stmt, "SELECT pg_database_size(current_database())");

            List<RelationStorage> relations = new ArrayList<>();
            relations.add(loadRelation(stmt, "ai_analysis_results"));
            relations.add(loadRelation(stmt, "image_tags"));
            relations.add(loadRelation(stmt, "tags"));
            relations.add(loadRelation(stmt, "operation_logs"));

            long aiStorageBytes = relations.stream().mapToLong(RelationStorage::bytes).sum();
            long analyzedImageCount = scalarLong(stmt, "SELECT COUNT(*) FROM ai_analysis_results");
            long aiTagLinkCount = scalarLong(stmt, "SELECT COUNT(*) FROM image_tags WHERE source = 'AI'");
            long totalTagCount = scalarLong(stmt, "SELECT COUNT(*) FROM tags");
            long aiOperationLogCount = scalarLong(stmt,
                    "SELECT COUNT(*) FROM operation_logs WHERE operation_type IN ('TAG_ADD', 'TAG_REMOVE')");

            return new StorageStats(
                    databaseName,
                    dataDirectory,
                    databaseSize,
                    aiStorageBytes,
                    analyzedImageCount,
                    aiTagLinkCount,
                    totalTagCount,
                    aiOperationLogCount,
                    relations
            );
        } catch (SQLException e) {
            logger.error("读取 AI 标签存储统计失败", e);
            throw new RuntimeException("读取 AI 标签存储统计失败: " + e.getMessage(), e);
        }
    }

    public CleanupResult cleanupAiTags() {
        StorageStats before = loadStats();
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            long deletedAiTagLinks;
            long deletedAnalysisResults;
            long deletedOrphanTags;
            long deletedAiTagLogs;
            long resetImageFlags;
            try {
                deletedAiTagLinks = stmt.executeUpdate("DELETE FROM image_tags WHERE source = 'AI'");
                deletedAnalysisResults = stmt.executeUpdate("DELETE FROM ai_analysis_results");
                deletedOrphanTags = stmt.executeUpdate("""
                        DELETE FROM tags t
                        WHERE NOT EXISTS (
                            SELECT 1 FROM image_tags it WHERE it.tag_id = t.id
                        )
                        """);
                deletedAiTagLogs = stmt.executeUpdate("""
                        DELETE FROM operation_logs
                        WHERE operation_type IN ('TAG_ADD', 'TAG_REMOVE')
                        """);
                resetImageFlags = stmt.executeUpdate("""
                        UPDATE images
                        SET ai_processed = FALSE, last_ai_scan = NULL, modified_at = NOW()
                        WHERE ai_processed = TRUE OR last_ai_scan IS NOT NULL
                        """);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            vacuumAfterCleanup();
            StorageStats after = loadStats();
            return new CleanupResult(
                    deletedAnalysisResults,
                    deletedAiTagLinks,
                    deletedOrphanTags,
                    deletedAiTagLogs,
                    resetImageFlags,
                    before,
                    after
            );
        } catch (SQLException e) {
            logger.error("清理 AI 标签失败", e);
            throw new RuntimeException("清理 AI 标签失败: " + e.getMessage(), e);
        }
    }

    private void ensureCleanupSchema() {
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS operation_logs (
                        id SERIAL PRIMARY KEY,
                        image_id INTEGER REFERENCES images(id) ON DELETE SET NULL,
                        operation_type VARCHAR(20) NOT NULL,
                        old_value TEXT,
                        new_value TEXT,
                        operated_at TIMESTAMP NOT NULL DEFAULT NOW()
                    )
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
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS ai_processed BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS last_ai_scan TIMESTAMP");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_original_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_storage_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
        } catch (SQLException e) {
            logger.error("初始化 AI 标签清理结构失败", e);
            throw new RuntimeException("初始化 AI 标签清理结构失败: " + e.getMessage(), e);
        }
    }

    private RelationStorage loadRelation(Statement stmt, String tableName) throws SQLException {
        String sql = """
                SELECT
                    COALESCE(pg_total_relation_size(to_regclass('public.%1$s')), 0) AS bytes,
                    COALESCE(pg_relation_filepath(to_regclass('public.%1$s')), '') AS path
                """.formatted(tableName);
        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new RelationStorage(tableName, rs.getString("path"), rs.getLong("bytes"));
            }
        }
        return new RelationStorage(tableName, "", 0);
    }

    private String scalarString(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private long scalarLong(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void vacuumAfterCleanup() {
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM (ANALYZE) ai_analysis_results");
            stmt.execute("VACUUM (ANALYZE) image_tags");
            stmt.execute("VACUUM (ANALYZE) tags");
            stmt.execute("VACUUM (ANALYZE) operation_logs");
            stmt.execute("VACUUM (ANALYZE) images");
        } catch (SQLException e) {
            logger.warn("AI 标签清理后的 VACUUM 未完成，数据库空间仍可被 PostgreSQL 复用", e);
        }
    }
}
