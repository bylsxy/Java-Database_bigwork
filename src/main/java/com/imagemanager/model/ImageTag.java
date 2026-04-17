package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 图片-标签关联实体 — 对应数据库 image_tags 表。
 *
 * @param id         关联ID
 * @param imageId    图片ID
 * @param tagId      标签ID
 * @param confidence AI识别置信度 (0.0~1.0)
 * @param source     来源：AI / MANUAL
 * @param createdAt  创建时间
 */
public record ImageTag(
        int id,
        int imageId,
        int tagId,
        float confidence,
        String source,
        LocalDateTime createdAt
) {
    public ImageTag {
        if (confidence < 0.0f || confidence > 1.0f) {
            throw new IllegalArgumentException("置信度必须在 0.0 ~ 1.0 之间: " + confidence);
        }
    }
}
