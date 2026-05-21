package com.imagemanager.service;

import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.*;
import com.imagemanager.model.ImageFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 搜索服务 — 提供关键词搜索和AI智能搜索两种模式。
 * <p>
 * 关键词搜索：匹配文件名、目录、格式、分辨率、大小、日期、标签和 AI 描述。
 * AI智能搜索：将自然语言转换为SQL，执行查询。
 */
public class SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_DISPLAY_RESULTS = 200;

    private final TagDao tagDao;
    private final ImageDao imageDao;
    private final DirectoryDao directoryDao;
    private final AIService aiService;

    public SearchService() {
        this.tagDao = new TagDaoImpl();
        this.imageDao = new ImageDaoImpl();
        this.directoryDao = new DirectoryDaoImpl();
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
        return search(query, mode, null);
    }

    public SearchResult search(String query, SearchMode mode, String directoryPath) {
        return search(query, mode, directoryPath, null);
    }

    public SearchResult search(String query, SearchMode mode, String directoryPath, Consumer<String> progressReporter) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), "", 0, "请输入搜索内容");
        }

        report(progressReporter, "正在确认当前搜索目录...");
        Optional<Integer> directoryId = resolveDirectoryId(directoryPath);
        if (directoryPath != null && !directoryPath.isBlank() && directoryId.isEmpty()) {
            return new SearchResult(List.of(), query, 0, "当前目录尚未入库，请先加载该文件夹");
        }

        return switch (mode) {
            case KEYWORD -> searchByKeyword(query, directoryId, progressReporter);
            case AI_SQL -> searchByAI(query, directoryId, progressReporter);
        };
    }

    /**
     * 关键词直接搜索。
     */
    private SearchResult searchByKeyword(String keyword, Optional<Integer> directoryId,
                                         Consumer<String> progressReporter) {
        logger.info("关键词搜索: {}", keyword);
        try {
            report(progressReporter, "关键词搜索：正在匹配文件名、目录、格式、分辨率、大小、日期、标签和 AI 描述...");
            List<Integer> imageIds = directoryId.isPresent()
                    ? tagDao.searchImagesByKeyword(keyword, directoryId.get())
                    : tagDao.searchImagesByKeyword(keyword);
            boolean capped = imageIds.size() >= MAX_DISPLAY_RESULTS;
            report(progressReporter, "关键词搜索：命中 " + imageIds.size() + " 张，正在加载缩略图和元数据...");
            List<ImageFile> images = loadImagesByIds(imageIds);

            // 记录搜索历史
            recordSearchHistory(keyword, "KEYWORD", null, images.size());

            String msg = images.isEmpty()
                    ? "当前文件夹及子文件夹未找到匹配 \"" + keyword + "\" 的图片"
                    : "当前文件夹及子文件夹找到 " + images.size() + " 张匹配的图片"
                    + (capped ? "（已显示前 " + MAX_DISPLAY_RESULTS + " 张）" : "");

            return new SearchResult(images, keyword, images.size(), msg);
        } catch (Exception e) {
            logger.error("关键词搜索失败: {}", keyword, e);
            return new SearchResult(List.of(), keyword, 0, "搜索出错: " + e.getMessage());
        }
    }

    /**
     * AI自然语言转SQL搜索。
     */
    private SearchResult searchByAI(String naturalLanguageQuery, Optional<Integer> directoryId,
                                    Consumer<String> progressReporter) {
        logger.info("AI智能搜索: {}", naturalLanguageQuery);
        try {
            // 1. 调用AI将自然语言转为SQL
            report(progressReporter, "AI搜索：正在发送请求，等待模型生成 SQL...");
            Optional<String> sqlOpt = aiService.naturalLanguageToSQL(naturalLanguageQuery);
            if (sqlOpt.isEmpty()) {
                report(progressReporter, "AI搜索：模型未返回可执行 SQL");
                return new SearchResult(List.of(), naturalLanguageQuery, 0,
                        "AI无法生成查询语句，请检查API配置或尝试其他表达方式");
            }

            String sql = sqlOpt.get();
            logger.info("AI生成SQL: {}", sql);

            // 2. 执行SQL
            report(progressReporter, "AI搜索：已收到模型返回，正在执行数据库查询...");
            List<Integer> imageIds = tagDao.executeSearchSQL(sql);
            report(progressReporter, "AI搜索：数据库返回 " + imageIds.size() + " 条候选结果，正在加载缩略图和元数据...");
            List<ImageFile> images = loadImagesByIds(imageIds);
            if (directoryId.isPresent()) {
                Set<Integer> allowedDirectoryIds = descendantDirectoryIds(directoryId.get());
                images = images.stream()
                        .filter(image -> allowedDirectoryIds.contains(image.directoryId()))
                        .toList();
            }
            boolean capped = images.size() > MAX_DISPLAY_RESULTS;
            if (capped) {
                images = images.subList(0, MAX_DISPLAY_RESULTS);
            }
            report(progressReporter, "AI搜索：当前目录内命中 " + images.size() + " 张，正在刷新界面...");

            // 3. 记录搜索历史
            recordSearchHistory(naturalLanguageQuery, "AI_SQL", sql, images.size());

            String msg = images.isEmpty()
                    ? "当前文件夹及子文件夹 AI 搜索未找到匹配结果\n生成的SQL: " + sql
                    : "当前文件夹及子文件夹 AI 搜索找到 " + images.size() + " 张匹配的图片"
                    + (capped ? "（已显示前 " + MAX_DISPLAY_RESULTS + " 张）" : "")
                    + "\nSQL: " + sql;

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

    private Optional<Integer> resolveDirectoryId(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return Optional.empty();
        }
        return directoryDao.findByPath(directoryPath).map(directory -> directory.id());
    }

    private Set<Integer> descendantDirectoryIds(int rootDirectoryId) {
        Set<Integer> ids = new HashSet<>();
        for (var directory : directoryDao.findDescendants(rootDirectoryId)) {
            ids.add(directory.id());
        }
        return ids;
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

    private void report(Consumer<String> progressReporter, String message) {
        if (progressReporter != null) {
            progressReporter.accept(message);
        }
    }
}
