package com.imagemanager.service;

import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.*;
import com.imagemanager.model.ImageFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 搜索服务 — 提供关键词搜索和AI智能搜索两种模式。
 * <p>
 * 关键词搜索：直接匹配文件名、标签名称、AI描述。
 * AI智能搜索：将自然语言转换为SQL，执行查询。
 */
public class SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final TagDao tagDao;
    private final ImageDao imageDao;
    private final AIService aiService;

    public SearchService() {
        this.tagDao = new TagDaoImpl();
        this.imageDao = new ImageDaoImpl();
        this.aiService = new OpenAICompatibleService();
    }

    /**
     * 搜索模式枚举。
     */
    public enum SearchMode {
        KEYWORD,  // 直接关键词搜索
        AI_SQL    // AI自然语言转SQL搜索
    }

    /**
     * 搜索结果。
     */
    public record SearchResult(
            List<ImageFile> images,
            String executedQuery,   // 实际执行的查询（关键词或SQL）
            int totalCount,
            String message          // 给用户的消息
    ) {}

    /**
     * 执行搜索。
     *
     * @param query 用户输入的查询文本
     * @param mode  搜索模式
     * @return 搜索结果
     */
    public SearchResult search(String query, SearchMode mode) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), "", 0, "请输入搜索内容");
        }

        return switch (mode) {
            case KEYWORD -> searchByKeyword(query);
            case AI_SQL -> searchByAI(query);
        };
    }

    /**
     * 关键词直接搜索。
     */
    private SearchResult searchByKeyword(String keyword) {
        logger.info("关键词搜索: {}", keyword);
        try {
            List<Integer> imageIds = tagDao.searchImagesByKeyword(keyword);
            List<ImageFile> images = loadImagesByIds(imageIds);

            // 记录搜索历史
            recordSearchHistory(keyword, "KEYWORD", null, images.size());

            String msg = images.isEmpty()
                    ? "未找到匹配 \"" + keyword + "\" 的图片"
                    : "找到 " + images.size() + " 张匹配的图片";

            return new SearchResult(images, keyword, images.size(), msg);
        } catch (Exception e) {
            logger.error("关键词搜索失败: {}", keyword, e);
            return new SearchResult(List.of(), keyword, 0, "搜索出错: " + e.getMessage());
        }
    }

    /**
     * AI自然语言转SQL搜索。
     */
    private SearchResult searchByAI(String naturalLanguageQuery) {
        logger.info("AI智能搜索: {}", naturalLanguageQuery);
        try {
            // 1. 调用AI将自然语言转为SQL
            Optional<String> sqlOpt = aiService.naturalLanguageToSQL(naturalLanguageQuery);
            if (sqlOpt.isEmpty()) {
                return new SearchResult(List.of(), naturalLanguageQuery, 0,
                        "AI无法生成查询语句，请检查API配置或尝试其他表达方式");
            }

            String sql = sqlOpt.get();
            logger.info("AI生成SQL: {}", sql);

            // 2. 执行SQL
            List<Integer> imageIds = tagDao.executeSearchSQL(sql);
            List<ImageFile> images = loadImagesByIds(imageIds);

            // 3. 记录搜索历史
            recordSearchHistory(naturalLanguageQuery, "AI_SQL", sql, images.size());

            String msg = images.isEmpty()
                    ? "AI搜索未找到匹配结果\n生成的SQL: " + sql
                    : "AI搜索找到 " + images.size() + " 张匹配的图片\nSQL: " + sql;

            return new SearchResult(images, sql, images.size(), msg);
        } catch (SecurityException e) {
            logger.warn("AI生成的SQL被安全策略拒绝: {}", e.getMessage());
            return new SearchResult(List.of(), naturalLanguageQuery, 0,
                    "AI生成的查询语句不安全，已被拒绝执行");
        } catch (Exception e) {
            logger.error("AI搜索失败: {}", naturalLanguageQuery, e);
            return new SearchResult(List.of(), naturalLanguageQuery, 0,
                    "AI搜索出错: " + e.getMessage());
        }
    }

    /**
     * 根据图片ID列表加载完整图片信息。
     */
    private List<ImageFile> loadImagesByIds(List<Integer> ids) {
        List<ImageFile> images = new ArrayList<>();
        for (int id : ids) {
            imageDao.findById(id).ifPresent(images::add);
        }
        return images;
    }

    /**
     * 记录搜索历史到数据库。
     */
    private void recordSearchHistory(String queryText, String mode, String sql, int resultCount) {
        String insertSql = """
                INSERT INTO search_history (query_text, search_mode, generated_sql, result_count)
                VALUES (?, ?, ?, ?)
                """;
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, queryText);
            ps.setString(2, mode);
            ps.setString(3, sql);
            ps.setInt(4, resultCount);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("记录搜索历史失败", e);
        }
    }
}
