package com.imagemanager.dao;

import com.imagemanager.model.ImageVersion;

import java.util.List;
import java.util.Optional;

/**
 * 版本管理数据访问接口 — 对 image_versions 表的操作。
 */
public interface VersionDao {

    /**
     * 获取某张图片的所有版本（按版本号排序）。
     */
    List<ImageVersion> findByImageId(int imageId);

    /**
     * 获取某张图片的当前版本。
     */
    Optional<ImageVersion> findCurrentVersion(int imageId);

    /**
     * 创建新版本记录。
     */
    ImageVersion createVersion(ImageVersion version);

    /**
     * 将指定版本标记为当前版本。
     */
    void restoreVersion(int imageId, int versionId);

    /**
     * 获取某张图片的版本总数。
     */
    int countVersions(int imageId);
}
