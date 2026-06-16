package com.imagemanager.service;

import com.imagemanager.model.ImageFile;
import com.imagemanager.model.RecycleBinItem;

import java.util.List;

/**
 * 图片业务服务接口 — 定义图片管理的核心业务逻辑。
 * <p>
 * Service 层负责：
 * <ul>
 *   <li>协调 DAO 层和文件系统操作</li>
 *   <li>管理数据库事务（特别是批量重命名）</li>
 *   <li>执行业务规则校验（如文件名合法性）</li>
 * </ul>
 */
public interface ImageService {

    /**
     * 扫描本地磁盘目录，将图片信息同步到数据库。
     * <p>
     * 同步策略：
     * <ul>
     *   <li>磁盘有 + 数据库无 → 新增到数据库</li>
     *   <li>数据库有 + 磁盘无 → 标记为已删除</li>
     *   <li>两边都有 → 保持不变</li>
     * </ul>
     *
     * @param directoryPath 目录的完整路径
     * @return 该目录下所有活跃图片的列表
     */
    List<ImageFile> loadImagesFromDirectory(String directoryPath);

    /**
     * 删除图片（设置数据库删除状态 + 移入同目录 .versions/.trash 回收站）。
     * 调用前 Controller 层应已完成二次确认。
     *
     * @param images 要删除的图片列表
     * @throws RuntimeException 删除失败时抛出，事务将回滚
     */
    void deleteImages(List<ImageFile> images);

    /**
     * 将图片列表存入内部剪贴板，准备粘贴。
     *
     * @param images 要复制的图片列表
     */
    void copyImages(List<ImageFile> images);

    /**
     * 将图片列表以“剪切”模式存入内部剪贴板，下一次粘贴会移动原文件和数据库记录。
     */
    void cutImages(List<ImageFile> images);

    /**
     * 将剪贴板中的图片粘贴到指定目录。
     * 如果目标目录已有同名文件，自动在文件名后添加序号。
     *
     * @param targetDirectoryPath 目标目录的完整路径
     * @throws RuntimeException 粘贴失败时抛出
     */
    void pasteImages(String targetDirectoryPath);

    /**
     * 获取当前剪贴板中的图片列表。
     *
     * @return 剪贴板图片，为空时返回空列表
     */
    List<ImageFile> getClipboard();

    /**
     * 当前剪贴板是否为剪切模式。
     */
    boolean isClipboardCutMode();

    /**
     * 查询回收站图片。
     */
    List<RecycleBinItem> getRecycleBinItems();

    /**
     * 从回收站恢复图片。
     */
    void restoreImagesFromRecycleBin(List<RecycleBinItem> items);

    /**
     * 撤销最近一次粘贴或剪切移动。返回处理的图片数量。
     */
    int rollbackLastTransferOperation();

    /**
     * 单张图片重命名。
     *
     * @param image   要重命名的图片
     * @param newName 新的主文件名（不含扩展名）
     * @throws IllegalArgumentException 文件名非法时抛出
     * @throws RuntimeException         重命名失败时抛出
     */
    void renameImage(ImageFile image, String newName);

    /**
     * 批量重命名 — 在单个数据库事务中执行。
     * <p>
     * 命名规则：prefix + 左填充0的编号 + 原扩展名
     * 例如：prefix="Photo", startNumber=1, digitCount=4
     * → "Photo0001.jpg", "Photo0002.jpg", ...
     *
     * @param images      要重命名的图片列表
     * @param prefix      名称前缀
     * @param startNumber 起始编号
     * @param digitCount  编号位数（不足时用0填充）
     * @throws RuntimeException 任何一张重命名失败时整个事务回滚
     */
    void batchRename(List<ImageFile> images, String prefix, int startNumber, int digitCount);

    /**
     * 为图片生成缩略图并缓存到数据库。
     *
     * @param image    图片实体
     * @param maxWidth  缩略图最大宽度
     * @param maxHeight 缩略图最大高度
     * @return 缩略图的二进制数据（PNG 格式）
     */
    byte[] generateAndCacheThumbnail(ImageFile image, int maxWidth, int maxHeight);
}
