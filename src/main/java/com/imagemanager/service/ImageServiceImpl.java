package com.imagemanager.service;

import com.imagemanager.dao.*;
import com.imagemanager.model.DirectoryNode;
import com.imagemanager.model.ImageFile;
import com.imagemanager.util.FileUtil;
import com.imagemanager.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 图片业务服务实现 — 协调 DAO 层与文件系统操作。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>扫描磁盘 ↔ 同步数据库</li>
 *   <li>事务管理（批量重命名必须在单个事务中完成）</li>
 *   <li>剪贴板管理（复制/粘贴）</li>
 *   <li>文件名合法性校验</li>
 * </ul>
 */
public class ImageServiceImpl implements ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageServiceImpl.class);

    /** 支持的图片格式后缀（全大写） */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("JPG", "JPEG", "GIF", "PNG", "BMP");

    /** 文件名中不允许出现的字符 */
    private static final String ILLEGAL_CHARS = "\\/:*?\"<>|";

    private final ImageDao imageDao;
    private final DirectoryDao directoryDao;

    /** 内部剪贴板 — 存储"复制"操作选中的图片 */
    private List<ImageFile> clipboard = new ArrayList<>();

    public ImageServiceImpl() {
        this.imageDao = new ImageDaoImpl();
        this.directoryDao = new DirectoryDaoImpl();
    }

    // 可用于测试注入的构造器
    public ImageServiceImpl(ImageDao imageDao, DirectoryDao directoryDao) {
        this.imageDao = imageDao;
        this.directoryDao = directoryDao;
    }

    @Override
    public List<ImageFile> loadImagesFromDirectory(String directoryPath) {
        logger.info("扫描目录: {}", directoryPath);
        if (!DatabaseConnection.isInitialized()) {
            return loadImagesFromDiskOnly(directoryPath);
        }

        try {
            // 1. 确保目录在数据库中存在
            DirectoryNode dirNode = directoryDao.findOrCreate(directoryPath);

            // 2. 扫描磁盘上的图片文件
            List<File> diskFiles = FileUtil.listImageFiles(directoryPath, SUPPORTED_FORMATS);

            // 3. 读取数据库中当前目录的图片记录
            List<ImageFile> dbImages = imageDao.findByDirectoryId(dirNode.id());

            // 4. 同步：磁盘有但数据库没有的 → 新增
            var dbPaths = new java.util.HashSet<String>();
            for (var img : dbImages) {
                dbPaths.add(img.filePath());
            }

            var newImages = new ArrayList<ImageFile>();
            for (var file : diskFiles) {
                String filePath = file.getAbsolutePath();
                if (!dbPaths.contains(filePath)) {
                    // 磁盘上有，数据库没有 → 创建新记录
                    var imageFile = createImageFileFromDisk(file, dirNode.id());
                    newImages.add(imageFile);
                }
            }

            // 批量插入新发现的图片
            if (!newImages.isEmpty()) {
                imageDao.batchInsert(newImages);
                logger.info("新增 {} 张图片到数据库", newImages.size());
            }
            boolean changed = !newImages.isEmpty();

            // 5. 同步：数据库有但磁盘没有的 → 标记删除
            var diskPaths = new java.util.HashSet<String>();
            for (var file : diskFiles) {
                diskPaths.add(file.getAbsolutePath());
            }
            for (var img : dbImages) {
                if (!diskPaths.contains(img.filePath())) {
                    imageDao.softDelete(img.id());
                    changed = true;
                    logger.debug("磁盘文件不存在，标记删除: {}", img.filePath());
                }
            }

            // 6. 重新查询并返回最新列表
            return changed ? imageDao.findByDirectoryId(dirNode.id()) : dbImages;
        } catch (RuntimeException e) {
            logger.warn("数据库同步不可用，改用离线文件浏览: {}", e.getMessage());
            return loadImagesFromDiskOnly(directoryPath);
        }
    }

    @Override
    public void deleteImages(List<ImageFile> images) {
        logger.info("删除 {} 张图片", images.size());

        for (var image : images) {
            try {
                // 1. 数据库逻辑删除（触发器会自动记录日志）
                if (DatabaseConnection.isInitialized() && image.id() > 0) {
                    imageDao.softDelete(image.id());
                }

                // 2. 磁盘物理删除
                Path path = Path.of(image.filePath());
                if (Files.exists(path)) {
                    Files.delete(path);
                    logger.debug("已删除磁盘文件: {}", image.filePath());
                }
            } catch (IOException e) {
                logger.error("删除文件失败: {} - {}", image.filePath(), e.getMessage());
                // 继续处理其他文件，不中断整个操作
            }
        }
    }

    @Override
    public void copyImages(List<ImageFile> images) {
        this.clipboard = new ArrayList<>(images);
        for (var image : images) {
            if (DatabaseConnection.isInitialized() && image.id() > 0) {
                logOperation(image.id(), "COPY", image.filePath(), "复制到应用剪贴板");
            }
        }
        logger.info("已复制 {} 张图片到剪贴板", images.size());
    }

    @Override
    public List<ImageFile> getClipboard() {
        return List.copyOf(clipboard);
    }

    @Override
    public void pasteImages(String targetDirectoryPath) {
        if (clipboard.isEmpty()) {
            logger.warn("剪贴板为空，无法粘贴");
            return;
        }
        if (!DatabaseConnection.isInitialized()) {
            pasteImagesWithoutDatabase(targetDirectoryPath);
            return;
        }

        logger.info("粘贴 {} 张图片到 {}", clipboard.size(), targetDirectoryPath);

        DirectoryNode targetDir = directoryDao.findOrCreate(targetDirectoryPath);

        for (var image : clipboard) {
            try {
                // 确定目标文件名（处理重名冲突）
                String targetFileName = resolveConflictName(targetDir.id(), image.fileName());
                Path sourcePath = Path.of(image.filePath());
                Path targetPath = Path.of(targetDirectoryPath, targetFileName);

                // 复制磁盘文件
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

                // 在数据库中创建新记录。目标文件可能被重新命名，尺寸必须按目标文件重新读取，
                // 避免把旧版本遗留的未知分辨率继续复制到新记录。
                var newImage = createImageFileFromDisk(targetPath.toFile(), targetDir.id());
                int newImageId = imageDao.insert(newImage);
                logOperation(newImageId, "PASTE", image.filePath(), targetPath.toString());

                logger.debug("已粘贴: {} → {}", image.fileName(), targetPath);

            } catch (IOException e) {
                logger.error("粘贴文件失败: {} - {}", image.fileName(), e.getMessage());
                throw new RuntimeException("粘贴文件失败: " + image.fileName(), e);
            }
        }
    }

    @Override
    public void renameImage(ImageFile image, String newName) {
        // 校验文件名合法性
        validateFileName(newName);

        String newFileName = newName + image.extension();
        Path oldPath = Path.of(image.filePath());
        Path newPath = oldPath.getParent().resolve(newFileName);

        try {
            // 1. 磁盘重命名
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);

            // 2. 数据库更新（触发器自动记录日志）
            if (DatabaseConnection.isInitialized() && image.id() > 0) {
                imageDao.updateFileName(image.id(), newFileName, newPath.toString());
            }

            logger.info("重命名: {} → {}", image.fileName(), newFileName);

        } catch (IOException e) {
            logger.error("重命名文件失败: {}", e.getMessage());
            throw new RuntimeException("重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void batchRename(List<ImageFile> images, String prefix, int startNumber, int digitCount) {
        logger.info("批量重命名 {} 张图片: prefix={}, start={}, digits={}",
                images.size(), prefix, startNumber, digitCount);

        // 校验前缀合法性
        validateFileName(prefix);

        // 用于回滚的记录：保存已经在磁盘上成功重命名的文件
        var rollbackList = new ArrayList<Path[]>();

        try (var conn = DatabaseConnection.getConnection()) {
            // 开启事务
            conn.setAutoCommit(false);

            try {
                for (int i = 0; i < images.size(); i++) {
                    var image = images.get(i);
                    int number = startNumber + i;

                    // 生成新文件名：前缀 + 补零编号 + 原扩展名
                    String paddedNumber = String.format("%0" + digitCount + "d", number);
                    String newFileName = prefix + paddedNumber + image.extension();

                    Path oldPath = Path.of(image.filePath());
                    Path newPath = oldPath.getParent().resolve(newFileName);

                    // 磁盘重命名
                    Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                    rollbackList.add(new Path[]{newPath, oldPath}); // 记录用于回滚

                    // 数据库更新（通过 DAO，但使用当前事务的连接）
                    // 这里直接用 connection 执行，不走默认的 DAO（因为需要共享同一个事务连接）
                    try (var stmt = conn.prepareStatement(
                            "UPDATE images SET file_name = ?, file_path = ? WHERE id = ?")) {
                        stmt.setString(1, newFileName);
                        stmt.setString(2, newPath.toString());
                        stmt.setInt(3, image.id());
                        stmt.executeUpdate();
                    }
                }

                // 全部成功 → 提交事务
                conn.commit();
                logger.info("批量重命名成功完成");

            } catch (Exception e) {
                // 任何失败 → 回滚数据库事务
                conn.rollback();
                logger.error("批量重命名失败，回滚事务: {}", e.getMessage());

                // 同时回滚磁盘上的文件名
                for (var paths : rollbackList) {
                    try {
                        Files.move(paths[0], paths[1], StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException rollbackEx) {
                        logger.error("磁盘回滚失败: {} → {}", paths[0], paths[1]);
                    }
                }
                throw new RuntimeException("批量重命名失败，已回滚: " + e.getMessage(), e);

            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("获取数据库连接失败: {}", e.getMessage());
            throw new RuntimeException("批量重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] generateAndCacheThumbnail(ImageFile image, int maxWidth, int maxHeight) {
        // 如果数据库中已有缩略图，直接返回
        if (image.thumbnail() != null && image.thumbnail().length > 0) {
            return image.thumbnail();
        }

        // 从磁盘生成缩略图
        byte[] thumbnailData = ImageUtil.generateThumbnailBytes(image.filePath(), maxWidth, maxHeight);

        // 缓存到数据库
        if (thumbnailData != null && thumbnailData.length > 0
                && DatabaseConnection.isInitialized() && image.id() > 0) {
            imageDao.updateThumbnail(image.id(), thumbnailData);
        }

        return thumbnailData;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从磁盘文件创建 ImageFile 实体（不含缩略图，缩略图后续按需生成）。
     */
    private ImageFile createImageFileFromDisk(File file, int directoryId) {
        String fileName = file.getName();
        String extension = FileUtil.getExtension(fileName).toUpperCase();
        long fileSize = file.length();

        // 尝试读取图片尺寸（可能失败，此时设为 0）
        int[] dimensions = ImageUtil.getImageDimensions(file.getAbsolutePath());

        return new ImageFile(
                0, fileName, file.getAbsolutePath(), directoryId,
                fileSize, dimensions[0], dimensions[1],
                extension, null,
                LocalDateTime.now(), LocalDateTime.now(), false, false
        );
    }

    private List<ImageFile> loadImagesFromDiskOnly(String directoryPath) {
        List<File> diskFiles = FileUtil.listImageFiles(directoryPath, SUPPORTED_FORMATS);
        List<ImageFile> images = new ArrayList<>();
        for (File file : diskFiles) {
            images.add(createImageFileFromDisk(file, 0));
        }
        return images;
    }

    private void pasteImagesWithoutDatabase(String targetDirectoryPath) {
        logger.info("离线粘贴 {} 张图片到 {}", clipboard.size(), targetDirectoryPath);
        for (var image : clipboard) {
            try {
                String targetFileName = resolveConflictNameOnDisk(targetDirectoryPath, image.fileName());
                Path sourcePath = Path.of(image.filePath());
                Path targetPath = Path.of(targetDirectoryPath, targetFileName);
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.error("离线粘贴文件失败: {} - {}", image.fileName(), e.getMessage());
                throw new RuntimeException("粘贴文件失败: " + image.fileName(), e);
            }
        }
    }

    /**
     * 解决文件名冲突：如果目标目录已有同名文件，自动添加序号。
     * 例如：photo.jpg → photo(1).jpg → photo(2).jpg → ...
     */
    private String resolveConflictName(int directoryId, String fileName) {
        if (!imageDao.existsByDirectoryAndName(directoryId, fileName)) {
            return fileName;
        }

        String baseName = FileUtil.getBaseName(fileName);
        String extension = FileUtil.getExtension(fileName);

        for (int i = 1; i <= 9999; i++) {
            String candidate = baseName + "(" + i + ")." + extension;
            if (!imageDao.existsByDirectoryAndName(directoryId, candidate)) {
                return candidate;
            }
        }

        throw new RuntimeException("无法为 " + fileName + " 生成唯一文件名");
    }

    private String resolveConflictNameOnDisk(String directoryPath, String fileName) {
        if (!Files.exists(Path.of(directoryPath, fileName))) {
            return fileName;
        }
        String baseName = FileUtil.getBaseName(fileName);
        String extension = FileUtil.getExtension(fileName);
        for (int i = 1; i <= 9999; i++) {
            String candidate = baseName + "(" + i + ")." + extension;
            if (!Files.exists(Path.of(directoryPath, candidate))) {
                return candidate;
            }
        }
        throw new RuntimeException("无法为 " + fileName + " 生成唯一文件名");
    }

    /**
     * 校验文件名合法性。
     *
     * @throws IllegalArgumentException 包含非法字符或超长时抛出
     */
    private void validateFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("文件名不能超过 100 个字符");
        }
        for (char c : name.toCharArray()) {
            if (ILLEGAL_CHARS.indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                        "文件名不能包含字符 '" + c + "'（不允许使用 \\ / : * ? \" < > |）"
                );
            }
        }
    }

    private void logOperation(int imageId, String operationType, String oldValue, String newValue) {
        if (!DatabaseConnection.isInitialized() || imageId <= 0) {
            return;
        }
        String sql = """
                INSERT INTO operation_logs (image_id, operation_type, old_value, new_value)
                VALUES (?, ?, ?, ?)
                """;
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, imageId);
            stmt.setString(2, operationType);
            stmt.setString(3, oldValue);
            stmt.setString(4, newValue);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("记录操作日志失败: imageId={}, operation={}", imageId, operationType, e);
        }
    }
}
