package com.imagemanager.dao;

import com.imagemanager.model.ImageAnalysisResult;
import com.imagemanager.model.Tag;
import com.imagemanager.model.TagCategory;

import java.util.List;
import java.util.Optional;

/**
 * 标签数据访问接口 — 对 tag_categories, tags, image_tags, ai_analysis_results 表的操作。
 */
public interface TagDao {

    // ==================== 标签分类 ====================

    /**
     * 获取所有标签分类。
     */
    List<TagCategory> findAllCategories();

    /**
     * 根据英文名获取分类。
     */
    Optional<TagCategory> findCategoryByName(String name);

    // ==================== 标签 ====================

    /**
     * 根据分类和名称查找标签（不存在则创建）。
     */
    Tag findOrCreateTag(int categoryId, String tagName);

    /**
     * 获取某张图片的所有标签。
     */
    List<Tag> findTagsByImageId(int imageId);

    /**
     * 按关键词搜索标签名称（模糊匹配）。
     */
    List<Tag> searchTags(String keyword);

    // ==================== 图片-标签关联 ====================

    /**
     * 为图片关联一个标签。
     */
    void linkImageTag(int imageId, int tagId, float confidence, String source);

    /**
     * 移除图片的某个标签。
     */
    void unlinkImageTag(int imageId, int tagId);

    /**
     * 批量为图片插入标签（调用存储过程 sp_batch_insert_tags）。
     */
    void batchInsertTags(int imageId, String[] categories, String[] tagNames, float[] confidences);

    // ==================== AI分析结果 ====================

    /**
     * 保存AI分析结果。
     */
    void saveAnalysisResult(ImageAnalysisResult result);

    /**
     * 获取某张图片的AI分析结果。
     */
    Optional<ImageAnalysisResult> findAnalysisResult(int imageId);

    // ==================== 搜索 ====================

    /**
     * 按关键词搜索图片（匹配标签、AI描述、文件名）。
     * 返回匹配的图片ID列表。
     */
    List<Integer> searchImagesByKeyword(String keyword);

    /**
     * 执行AI生成的SQL查询（只读，带安全校验）。
     * 返回匹配的图片ID列表。
     */
    List<Integer> executeSearchSQL(String sql);
}
