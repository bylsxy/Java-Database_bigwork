package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 回收站中的图片记录。
 * <p>
 * 删除后的图片不从数据库中移除，而是把原文件移动到 .versions/.trash，
 * 同时在 images 表中保留原路径和回收站路径，便于后续恢复。
 */
public record RecycleBinItem(
        int imageId,
        String fileName,
        String originalPath,
        String storagePath,
        int directoryId,
        long fileSize,
        int width,
        int height,
        String format,
        LocalDateTime deletedAt
) {

    public String displayText() {
        String deletedText = deletedAt == null ? "删除时间未知" : deletedAt.toString();
        return fileName + "  |  " + deletedText + "\n原路径: " + originalPath + "\n回收站: " + storagePath;
    }

    @Override
    public String toString() {
        return displayText();
    }
}
