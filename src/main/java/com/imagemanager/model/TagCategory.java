package com.imagemanager.model;

/**
 * 标签分类实体 — 对应数据库 tag_categories 表。
 * <p>
 * 定义AI识别标签的大类，如 scene(场景)、object(物体)、person(人物)、celebrity(名人) 等。
 *
 * @param id          分类ID
 * @param name        英文标识（如 "scene", "object"）
 * @param displayName 中文显示名（如 "场景", "物体"）
 * @param description 分类描述
 */
public record TagCategory(
        int id,
        String name,
        String displayName,
        String description
) {
    public TagCategory {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("标签分类名称不能为空");
        }
    }
}
