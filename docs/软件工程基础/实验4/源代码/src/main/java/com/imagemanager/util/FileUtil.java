package com.imagemanager.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文件系统操作工具类 — 提供文件扫描、路径处理等功能。
 */
public final class FileUtil {

    private static final Logger logger = LoggerFactory.getLogger(FileUtil.class);

    private FileUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 列出指定目录下所有受支持格式的图片文件。
     * 不递归子目录，仅列出当前目录下的文件。
     *
     * @param directoryPath    目录路径
     * @param supportedFormats 支持的格式集合（大写，如 "JPG", "PNG"）
     * @return 图片文件列表，按文件名排序
     */
    public static List<File> listImageFiles(String directoryPath, Set<String> supportedFormats) {
        var images = new ArrayList<File>();
        File dir = new File(directoryPath);

        if (!dir.exists() || !dir.isDirectory()) {
            logger.warn("目录不存在或不是目录: {}", directoryPath);
            return images;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            logger.warn("无法列出目录内容: {}", directoryPath);
            return images;
        }

        for (var file : files) {
            if (file.isFile()) {
                String ext = getExtension(file.getName()).toUpperCase();
                if (supportedFormats.contains(ext)) {
                    images.add(file);
                }
            }
        }

        // 按文件名排序
        images.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return images;
    }

    /**
     * 列出指定目录下的所有子目录（不递归）。
     *
     * @param directoryPath 目录路径
     * @return 子目录的 File 列表，按名称排序
     */
    public static List<File> listSubDirectories(String directoryPath) {
        var dirs = new ArrayList<File>();
        File dir = new File(directoryPath);

        if (!dir.exists() || !dir.isDirectory()) {
            return dirs;
        }

        File[] files = dir.listFiles();
        if (files == null) return dirs;

        for (var file : files) {
            if (file.isDirectory() && !file.isHidden()) {
                dirs.add(file);
            }
        }

        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return dirs;
    }

    /**
     * 判断目录是否适合作为图片扫描目录。
     * <p>
     * 这里明确排除磁盘根目录，避免用户把整块磁盘当成扫描根目录。
     */
    public static boolean isUsableScanDirectory(File dir) {
        return dir != null
                && dir.exists()
                && dir.isDirectory()
                && dir.getParentFile() != null;
    }

    /**
     * 获取文件的扩展名（不含点号）。
     * 例如 "photo.jpg" → "jpg"，无扩展名返回空字符串。
     */
    public static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0 && dotIndex < fileName.length() - 1)
                ? fileName.substring(dotIndex + 1)
                : "";
    }

    /**
     * 获取文件的主名称（不含扩展名和点号）。
     * 例如 "photo.jpg" → "photo"
     */
    public static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 格式化文件大小为用户友好的字符串。
     * <ul>
     *   <li>≥ 1 MB → "X.XX MB"</li>
     *   <li>＜ 1 MB → "X.X KB"</li>
     * </ul>
     */
    public static String formatFileSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.2f MB".formatted(bytes / (1024.0 * 1024.0));
        }
        return "%.1f KB".formatted(bytes / 1024.0);
    }
}
