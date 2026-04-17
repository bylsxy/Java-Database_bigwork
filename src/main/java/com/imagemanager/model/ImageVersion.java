package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 图片版本实体 — 对应数据库 image_versions 表。
 * <p>
 * 每次编辑操作（裁切、标注等）产生一个新版本，支持时间轴浏览和快照恢复。
 *
 * @param id          版本记录ID
 * @param imageId     关联的原图ID
 * @param versionNum  版本号（从1开始递增）
 * @param filePath    该版本文件的磁盘路径
 * @param fileSize    文件大小（字节）
 * @param width       图片宽度
 * @param height      图片高度
 * @param thumbnail   缩略图二进制数据
 * @param editType    编辑类型：ORIGINAL / CROP / ANNOTATE / DRAW / RESTORE
 * @param description 编辑描述
 * @param createdAt   创建时间
 * @param isCurrent   是否为当前激活版本
 */
public record ImageVersion(
        int id,
        int imageId,
        int versionNum,
        String filePath,
        long fileSize,
        int width,
        int height,
        byte[] thumbnail,
        String editType,
        String description,
        LocalDateTime createdAt,
        boolean isCurrent
) {
    public ImageVersion {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("版本文件路径不能为空");
        }
    }
}
