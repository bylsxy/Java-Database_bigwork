package com.imagemanager.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI图像分析结果 — 对应数据库 ai_analysis_results 表，并聚合标签信息。
 *
 * @param id           结果ID
 * @param imageId      图片ID
 * @param rawResponse  AI原始返回JSON
 * @param description  AI生成的一句话自然语言描述
 * @param peopleCount  识别到的人数
 * @param analyzedAt   分析时间
 * @param modelUsed    使用的模型名称
 * @param tagsByCategory 按分类组织的标签映射（内存聚合用，不直接存库）
 */
public record ImageAnalysisResult(
        int id,
        int imageId,
        String rawResponse,
        String description,
        int peopleCount,
        LocalDateTime analyzedAt,
        String modelUsed,
        Map<String, List<String>> tagsByCategory
) {
    /**
     * 简化构造 — 从AI响应创建。
     */
    public ImageAnalysisResult(int imageId, String rawResponse, String description,
                               int peopleCount, String modelUsed,
                               Map<String, List<String>> tagsByCategory) {
        this(0, imageId, rawResponse, description, peopleCount,
                LocalDateTime.now(), modelUsed, tagsByCategory);
    }
}
