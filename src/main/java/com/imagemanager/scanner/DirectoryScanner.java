package com.imagemanager.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 目录扫描器 — 深度遍历指定目录，收集所有受支持格式的图片文件。
 * <p>
 * 负责第一阶段扫描：遍历文件系统，收集文件路径和元信息。
 * AI 识别在第二阶段由 {@link ScanTask} 异步执行。
 */
public class DirectoryScanner {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryScanner.class);

    /**
     * 支持的图片格式（小写）。
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"
    );

    /**
     * 扫描结果 — 一个图片文件的信息。
     */
    public record ScannedImage(
            File file,
            String fileName,
            String filePath,
            long fileSize,
            String format,
            String sha256Hash
    ) {}

    /**
     * 深度遍历目录，收集所有图片文件。
     *
     * @param rootDir 根目录
     * @return 所有找到的图片文件列表
     */
    public List<ScannedImage> scan(File rootDir) {
        List<ScannedImage> images = new ArrayList<>();

        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            logger.warn("无效的扫描目录: {}", rootDir);
            return images;
        }

        logger.info("开始扫描目录: {}", rootDir.getAbsolutePath());

        try {
            Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    String ext = getExtension(fileName);

                    if (SUPPORTED_EXTENSIONS.contains(ext)) {
                        try {
                            String hash = computeSHA256(file);
                            String format = ext.substring(1).toUpperCase(); // 去掉点号并大写

                            images.add(new ScannedImage(
                                    file.toFile(),
                                    fileName,
                                    file.toAbsolutePath().toString(),
                                    attrs.size(),
                                    format,
                                    hash
                            ));
                        } catch (Exception e) {
                            logger.warn("处理文件失败，已跳过: {}", file, e);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    logger.warn("无法访问文件: {} ({})", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 跳过系统隐藏目录和回收站
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.startsWith(".") || dirName.equals("$RECYCLE.BIN")
                            || dirName.equals("System Volume Information")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("扫描目录时发生IO错误: {}", rootDir, e);
        }

        logger.info("扫描完成: 在 {} 中找到 {} 张图片", rootDir.getAbsolutePath(), images.size());
        return images;
    }

    /**
     * 快速预估目录下的图片数量（不计算哈希，速度更快）。
     */
    public int estimateImageCount(File rootDir) {
        if (rootDir == null || !rootDir.exists()) return 0;

        int[] count = {0};
        try {
            Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (SUPPORTED_EXTENSIONS.contains(getExtension(file.getFileName().toString()))) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.startsWith(".") || dirName.equals("$RECYCLE.BIN")
                            || dirName.equals("System Volume Information")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("预估图片数量时出错: {}", rootDir, e);
        }
        return count[0];
    }

    /**
     * 计算文件的 SHA-256 哈希值（用于唯一标识图片）。
     */
    public static String computeSHA256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        return HexFormat.of().formatHex(hash);
    }

    private static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0) ? fileName.substring(dotIndex).toLowerCase() : "";
    }
}
