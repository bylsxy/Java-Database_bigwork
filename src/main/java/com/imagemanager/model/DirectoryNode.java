package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 目录节点实体 — 对应数据库 directories 表。
 * <p>
 * 目录树中的每一个节点，用于构建 TreeView 的数据模型。
 * 磁盘根目录（如 C:\）的 parentId 为 null。
 *
 * @param id        数据库自增主键
 * @param dirName   目录名称（最后一级文件夹名），如 "Sample Pictures"
 * @param dirPath   目录完整路径，如 "C:\Users\Public\Pictures\Sample Pictures"
 * @param parentId  父目录 ID，根目录时为 null
 * @param createdAt 首次录入时间
 */
public record DirectoryNode(
        int id,
        String dirName,
        String dirPath,
        Integer parentId,
        LocalDateTime createdAt
) {

    /**
     * Compact constructor — 校验目录名不能为空。
     */
    public DirectoryNode {
        if (dirName == null || dirName.isBlank()) {
            throw new IllegalArgumentException("目录名不能为空");
        }
    }

    /**
     * 判断是否为磁盘根目录（没有父目录）。
     * 例如 "C:\"、"D:\" 均为根目录。
     */
    public boolean isRoot() {
        return parentId == null;
    }

    @Override
    public String toString() {
        // TreeView 的默认显示文本
        return dirName;
    }
}
