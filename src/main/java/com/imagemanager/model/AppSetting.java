package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 应用设置实体 — 对应数据库 app_settings 表。
 * <p>
 * 键值对存储应用配置，包括AI配置、扫描目录、显示偏好等。
 *
 * @param key       设置键名
 * @param value     设置值
 * @param updatedAt 最后更新时间
 */
public record AppSetting(
        String key,
        String value,
        LocalDateTime updatedAt
) {
    public AppSetting {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("设置键名不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("设置值不能为 null");
        }
    }

    /**
     * 简化构造 — 新建或更新设置时使用。
     */
    public AppSetting(String key, String value) {
        this(key, value, LocalDateTime.now());
    }
}
