package com.imagemanager.dao;

import com.imagemanager.model.ImageFile;
import com.imagemanager.model.FileOperationLog;
import com.imagemanager.model.RecycleBinItem;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 图片数据访问接口 — 定义对 images 表的所有数据库操作。
 * <p>
 * 遵循面向接口编程原则：Service 层只依赖此接口，不依赖具体的 SQL 实现。
 * 这样做的好处是：如果将来更换数据库（如切换到 MongoDB），只需提供新的实现类即可。
 */
public interface ImageDao {

    /**
     * 查询指定目录下所有活跃（未进入回收状态）的图片。
     * 通过 v_active_images 视图查询。
     *
     * @param directoryId 目录的数据库 ID
     * @return 该目录下的图片列表，可能为空列表
     */
    List<ImageFile> findByDirectoryId(int directoryId);

    /**
     * 根据主键 ID 查询单张图片。
     *
     * @param imageId 图片 ID
     * @return 包含图片的 Optional，不存在时为 empty
     */
    Optional<ImageFile> findById(int imageId);

    /**
     * 根据文件路径查询图片（用于判断图片是否已录入数据库）。
     *
     * @param filePath 图片文件的完整路径
     * @return 包含图片的 Optional，不存在时为 empty
     */
    Optional<ImageFile> findByFilePath(String filePath);

    /**
     * 查询仍缺少宽高信息的活跃图片。
     */
    List<ImageFile> findMissingDimensions();

    /**
     * 查询目录下已存在的活跃图片路径，不加载缩略图。
     */
    Set<String> findFilePathsByDirectoryId(int directoryId);

    /**
     * 插入一条新的图片记录。
     * 插入后触发器会自动在 operation_logs 中写入 INSERT 日志。
     *
     * @param image 要插入的图片实体（id 字段会被忽略，由数据库自增）
     * @return 数据库生成的自增 ID
     */
    int insert(ImageFile image);

    /**
     * 批量插入图片记录（使用 batch 提升性能）。
     *
     * @param images 要插入的图片列表
     */
    void batchInsert(List<ImageFile> images);

    /**
     * 更新图片的文件名和文件路径（重命名操作）。
     * 更新后触发器会自动记录 RENAME 日志。
     *
     * @param imageId     图片 ID
     * @param newFileName 新文件名（含扩展名）
     * @param newFilePath 新的完整路径
     */
    void updateFileName(int imageId, String newFileName, String newFilePath);

    /**
     * 更新图片所在目录、文件名和路径。剪切粘贴恢复时使用。
     */
    void updateLocation(int imageId, String newFileName, String newFilePath, int newDirectoryId,
                        long fileSize, int width, int height, byte[] thumbnail);

    /**
     * 更新图片的缩略图数据。
     *
     * @param imageId       图片 ID
     * @param thumbnailData 缩略图的二进制数据（PNG 格式）
     */
    void updateThumbnail(int imageId, byte[] thumbnailData);

    /**
     * 更新图片尺寸和当前文件大小。
     */
    void updateDimensions(int imageId, int width, int height, long fileSize);

    /**
     * 仅更新删除标记：用于外部磁盘文件已缺失等无法迁移回收站的场景。
     * 触发器会自动记录 DELETE 日志。
     *
     * @param imageId 图片 ID
     */
    void softDelete(int imageId);

    /**
     * 将图片标记为已删除，并记录实际回收站存储路径。
     */
    void moveToRecycleBin(int imageId, String storagePath);

    /**
     * 查询回收站图片。
     */
    List<RecycleBinItem> findRecycleBinItems();

    /**
     * 从回收站恢复图片数据库状态。
     */
    void restoreFromRecycleBin(int imageId, String restoredFileName, String restoredPath, int directoryId,
                               long fileSize, int width, int height, byte[] thumbnail);

    /**
     * 查询最近一次粘贴或剪切移动的同批操作日志。
     */
    List<FileOperationLog> findLatestTransferLogs();

    /**
     * 物理删除：从数据库中永久移除记录。
     * 触发器会自动记录 HARD_DELETE 日志。
     *
     * @param imageId 图片 ID
     */
    void hardDelete(int imageId);

    /**
     * 检查指定目录下是否存在同名文件。
     *
     * @param directoryId 目录 ID
     * @param fileName    文件名
     * @return 存在返回 true
     */
    boolean existsByDirectoryAndName(int directoryId, String fileName);
}
