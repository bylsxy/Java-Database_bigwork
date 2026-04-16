package com.imagemanager.model;

import java.time.LocalDateTime;

/**
 * 图片文件实体 — 对应数据库 images 表的一条记录。
 * <p>
 * 使用 Java 26 的 record 类型，天生不可变、自动生成 equals/hashCode/toString。
 * Record 的 compact constructor 用于参数校验（JEP 513 Flexible Constructor Bodies 思想）。
 *
 * @param id          数据库自增主键，新建时为 0
 * @param fileName    文件名（含扩展名），如 "photo.jpg"
 * @param filePath    文件在磁盘上的完整路径
 * @param directoryId 所属目录的数据库 ID
 * @param fileSize    文件大小（字节）
 * @param width       图片宽度（像素），未加载时为 0
 * @param height      图片高度（像素），未加载时为 0
 * @param format      图片格式大写字符串（JPG/JPEG/PNG/GIF/BMP）
 * @param thumbnail   缩略图二进制数据（数据库 bytea），可为 null
 * @param createdAt   首次录入数据库的时间
 * @param modifiedAt  最后修改时间
 * @param deleted     是否已逻辑删除
 */
public record ImageFile(
        int id,
        String fileName,
        String filePath,
        int directoryId,
        long fileSize,
        int width,
        int height,
        String format,
        byte[] thumbnail,
        LocalDateTime createdAt,
        LocalDateTime modifiedAt,
        boolean deleted
) {

    /**
     * Compact constructor — 在规范构造器赋值之前执行参数校验。
     * 如果文件名或路径为空，直接抛出异常，防止脏数据进入系统。
     */
    public ImageFile {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
    }

    /**
     * 获取不含扩展名的主文件名。
     * 例如 "photo.jpg" → "photo"，"archive.tar.gz" → "archive.tar"
     */
    public String baseName() {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 获取文件扩展名（含点号）。
     * 例如 "photo.jpg" → ".jpg"，无扩展名则返回空字符串
     */
    public String extension() {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(dotIndex) : "";
    }

    /**
     * 将文件大小格式化为用户友好的字符串。
     * <ul>
     *   <li>≥ 1 MB 时显示 MB，保留两位小数，如 "2.50 MB"</li>
     *   <li>＜ 1 MB 时显示 KB，保留一位小数，如 "856.3 KB"</li>
     * </ul>
     */
    public String formattedSize() {
        if (fileSize >= 1024 * 1024) {
            return "%.2f MB".formatted(fileSize / (1024.0 * 1024.0));
        }
        return "%.1f KB".formatted(fileSize / 1024.0);
    }

    /**
     * 获取分辨率的显示字符串，如 "1920×1080"。
     * 如果宽高未加载（为0），返回 "未知"
     */
    public String resolution() {
        if (width > 0 && height > 0) {
            return width + "×" + height;
        }
        return "未知";
    }

    /**
     * 创建一个文件名已修改的新实例（record 是不可变的，所以需要新建）。
     * 这在重命名操作中使用。
     */
    public ImageFile withFileName(String newFileName) {
        return new ImageFile(
                id, newFileName, filePath, directoryId, fileSize,
                width, height, format, thumbnail, createdAt,
                LocalDateTime.now(), deleted
        );
    }

    /**
     * 创建一个路径和目录已修改的新实例（用于复制/粘贴操作）。
     */
    public ImageFile withNewLocation(String newFilePath, int newDirectoryId) {
        return new ImageFile(
                0, fileName, newFilePath, newDirectoryId, fileSize,
                width, height, format, thumbnail, LocalDateTime.now(),
                LocalDateTime.now(), false
        );
    }
}
