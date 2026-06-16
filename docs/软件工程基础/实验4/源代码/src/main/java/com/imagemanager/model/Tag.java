package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 标签实体 — 对应数据库 tags 表。
 * <p>
 * 每个标签属于一个分类（如"瀑布"属于"场景"分类），可被多张图片引用。
 *
 * @param id         标签ID
 * @param categoryId 所属分类ID
 * @param name       标签名称（如"瀑布"、"爱因斯坦"）
 * @param createdAt  创建时间
 */
public record Tag(
        int id,
        int categoryId,
        String name,
        LocalDateTime createdAt
) {
    public Tag {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
    }

    /**
     * 简化构造 — 新建标签时使用。
     */
    public Tag(int categoryId, String name) {
        this(0, categoryId, name, LocalDateTime.now());
    }
}
