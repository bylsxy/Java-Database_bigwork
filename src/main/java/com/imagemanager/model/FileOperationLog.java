package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 文件级操作日志，用于恢复中心撤销最近一次粘贴或剪切移动。
 */
public record FileOperationLog(
        int imageId,
        String operationType,
        String oldValue,
        String newValue,
        LocalDateTime operatedAt
) {
}
