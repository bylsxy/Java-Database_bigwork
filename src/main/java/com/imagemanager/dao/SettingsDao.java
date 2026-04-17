package com.imagemanager.dao;

import com.imagemanager.model.AppSetting;

import java.util.List;
import java.util.Optional;

/**
 * 应用设置数据访问接口 — 对 app_settings 表的 CRUD 操作。
 */
public interface SettingsDao {

    /**
     * 根据键名获取设置值。
     */
    Optional<AppSetting> findByKey(String key);

    /**
     * 获取所有设置。
     */
    List<AppSetting> findAll();

    /**
     * 插入或更新设置（UPSERT）。
     */
    void upsert(String key, String value);

    /**
     * 删除设置。
     */
    void delete(String key);

    /**
     * 获取设置值，不存在时返回默认值。
     */
    String getValueOrDefault(String key, String defaultValue);
}
