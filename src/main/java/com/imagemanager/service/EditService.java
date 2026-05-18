package com.imagemanager.service;

import com.imagemanager.dao.DatabaseConnection;
import com.imagemanager.dao.VersionDao;
import com.imagemanager.dao.VersionDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.model.ImageVersion;
import com.imagemanager.util.ImageUtil;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 图片编辑服务 — 管理编辑操作和版本历史。
 * <p>
 * 负责：
 * <ul>
 *   <li>裁切、标注、绘制等编辑操作</li>
 *   <li>每次编辑自动创建新版本快照</li>
 *   <li>版本时间轴浏览和恢复</li>
 * </ul>
 */
public class EditService {

    private static final Logger logger = LoggerFactory.getLogger(EditService.class);

    private final VersionDao versionDao = new VersionDaoImpl();

    /** 版本文件存储目录 — 在原图目录下创建 .versions 子目录 */
    private static final String VERSIONS_DIR = ".versions";

    /**
     * 获取所有版本列表。
     */
    public List<ImageVersion> getVersionHistory(int imageId) {
        return versionDao.findByImageId(imageId);
    }

    /**
     * 为图片创建初始版本（原始版本）。
     * 应在图片首次被编辑前调用。
     */
    public ImageVersion createOriginalVersion(ImageFile image) {
        // 检查是否已有版本
        int existingCount = versionDao.countVersions(image.id());
        if (existingCount > 0) {
            return versionDao.findCurrentVersion(image.id()).orElse(null);
        }

        try {
            File originalFile = new File(image.filePath());
            ensureVersionsDir(originalFile);
            File versionFile = versionFileFor(originalFile, 1);
            Files.copy(originalFile.toPath(), versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            byte[] thumbnail = ImageUtil.generateThumbnailBytes(
                    versionFile.getAbsolutePath(),
                    ImageUtil.DEFAULT_THUMBNAIL_WIDTH,
                    ImageUtil.DEFAULT_THUMBNAIL_HEIGHT);
            int[] dimensions = ImageUtil.getImageDimensions(versionFile.getAbsolutePath());

            ImageVersion original = new ImageVersion(
                    0, image.id(), 1, versionFile.getAbsolutePath(),
                    versionFile.length(), dimensions[0], dimensions[1],
                    thumbnail, "ORIGINAL", "原始版本",
                    java.time.LocalDateTime.now(), true
            );

            return versionDao.createVersion(original);
        } catch (IOException e) {
            logger.error("创建原始版本快照失败: {}", image.filePath(), e);
            throw new RuntimeException("创建原始版本快照失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存编辑后的图片为新版本。
     *
     * @param imageId      原图数据库ID
     * @param editedImage  编辑后的 JavaFX Image
     * @param editType     编辑类型 (CROP / DRAW / ANNOTATE)
     * @param description  编辑描述
     * @param originalPath 原始文件路径（用于确定版本存放目录）
     * @return 新创建的版本
     */
    public ImageVersion saveEditedVersion(int imageId, WritableImage editedImage,
                                          String editType, String description,
                                          String originalPath) {
        try {
            // 1. 确定版本存储路径
            File originalFile = new File(originalPath);
            ensureVersionsDir(originalFile);

            // 2. 计算版本号
            int nextVersionNum = versionDao.countVersions(imageId) + 1;

            // 3. 生成版本文件名
            File versionFile = versionFileFor(originalFile, nextVersionNum);

            // 4. 保存编辑后的图片到磁盘
            writeImage(editedImage, versionFile);

            // 5. 覆盖当前工作文件，让主界面和后续打开编辑器时看到最新版本
            writeImage(editedImage, originalFile);

            // 6. 生成缩略图
            byte[] thumbnail = ImageUtil.generateThumbnailBytes(
                    versionFile.getAbsolutePath(),
                    ImageUtil.DEFAULT_THUMBNAIL_WIDTH,
                    ImageUtil.DEFAULT_THUMBNAIL_HEIGHT);

            // 7. 更新 images 表中的当前图片元数据
            updateCurrentImageMetadata(
                    imageId,
                    originalFile.length(),
                    (int) editedImage.getWidth(),
                    (int) editedImage.getHeight(),
                    thumbnail);

            // 8. 创建版本记录
            ImageVersion newVersion = new ImageVersion(
                    0, imageId, nextVersionNum,
                    versionFile.getAbsolutePath(),
                    versionFile.length(),
                    (int) editedImage.getWidth(),
                    (int) editedImage.getHeight(),
                    thumbnail, editType, description,
                    java.time.LocalDateTime.now(), true
            );

            ImageVersion saved = versionDao.createVersion(newVersion);
            logger.info("新版本已保存: imageId={}, version={}, path={}",
                    imageId, nextVersionNum, versionFile.getAbsolutePath());
            return saved;

        } catch (IOException e) {
            logger.error("保存编辑版本失败: imageId={}", imageId, e);
            throw new RuntimeException("保存编辑版本失败: " + e.getMessage(), e);
        }
    }

    /**
     * 恢复到指定版本。
     */
    public void restoreVersion(int imageId, int versionId, String currentImagePath) {
        ImageVersion version = getVersionHistory(imageId).stream()
                .filter(v -> v.id() == versionId)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("版本不存在: " + versionId));

        try {
            Path sourcePath = Path.of(version.filePath());
            Path currentPath = Path.of(currentImagePath);
            Files.copy(sourcePath, currentPath, StandardCopyOption.REPLACE_EXISTING);

            byte[] thumbnail = ImageUtil.generateThumbnailBytes(
                    currentPath.toString(),
                    ImageUtil.DEFAULT_THUMBNAIL_WIDTH,
                    ImageUtil.DEFAULT_THUMBNAIL_HEIGHT);
            int[] dimensions = ImageUtil.getImageDimensions(currentPath.toString());
            updateCurrentImageMetadata(
                    imageId,
                    Files.size(currentPath),
                    dimensions[0],
                    dimensions[1],
                    thumbnail);
        } catch (IOException e) {
            logger.error("恢复版本文件失败: imageId={}, versionId={}", imageId, versionId, e);
            throw new RuntimeException("恢复版本文件失败: " + e.getMessage(), e);
        }

        versionDao.restoreVersion(imageId, versionId);
        logger.info("已恢复版本: imageId={}, versionId={}", imageId, versionId);
    }

    /**
     * 裁切图片。
     *
     * @param source 原始图片
     * @param x      裁切起始X
     * @param y      裁切起始Y
     * @param width  裁切宽度
     * @param height 裁切高度
     * @return 裁切后的图片
     */
    public WritableImage cropImage(Image source, double x, double y,
                                   double width, double height) {
        int ix = (int) Math.max(0, x);
        int iy = (int) Math.max(0, y);
        int iw = (int) Math.min(width, source.getWidth() - ix);
        int ih = (int) Math.min(height, source.getHeight() - iy);

        WritableImage cropped = new WritableImage(
                source.getPixelReader(), ix, iy, iw, ih);
        return cropped;
    }

    /**
     * 在图片上绘制标注（返回带有 Canvas 叠加的新图片）。
     *
     * @param source  原始图片
     * @param canvas  包含绘制内容的 Canvas
     * @return 合成后的图片
     */
    public WritableImage mergeCanvasWithImage(Image source, Canvas canvas) {
        // 创建新 Canvas 并先画原图再画标注
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();

        Canvas mergeCanvas = new Canvas(w, h);
        GraphicsContext gc = mergeCanvas.getGraphicsContext2D();

        // 画原图
        gc.drawImage(source, 0, 0, w, h);

        // 画标注层（缩放到原图尺寸）
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage canvasSnapshot = canvas.snapshot(params, null);
        gc.drawImage(canvasSnapshot, 0, 0, w, h);

        // 最终快照
        WritableImage merged = mergeCanvas.snapshot(params, null);
        return merged;
    }

    private void updateCurrentImageMetadata(int imageId, long fileSize, int width, int height, byte[] thumbnail) {
        String sql = """
                UPDATE images
                SET file_size = ?, width = ?, height = ?, thumbnail = ?, modified_at = NOW()
                WHERE id = ?
                """;
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fileSize);
            ps.setInt(2, width);
            ps.setInt(3, height);
            ps.setBytes(4, thumbnail);
            ps.setInt(5, imageId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("更新当前图片元数据失败: imageId={}", imageId, e);
            throw new RuntimeException("更新当前图片元数据失败: " + e.getMessage(), e);
        }
    }

    private File ensureVersionsDir(File originalFile) throws IOException {
        File parent = originalFile.getParentFile();
        if (parent == null) {
            throw new IOException("无法确定图片所在目录: " + originalFile.getAbsolutePath());
        }
        File versionsDir = new File(parent, VERSIONS_DIR);
        if (!versionsDir.exists() && !versionsDir.mkdirs()) {
            throw new IOException("无法创建版本目录: " + versionsDir.getAbsolutePath());
        }
        return versionsDir;
    }

    private File versionFileFor(File originalFile, int versionNum) {
        File versionsDir = new File(originalFile.getParentFile(), VERSIONS_DIR);
        String fileName = originalFile.getName();
        int dotIdx = fileName.lastIndexOf('.');
        String nameWithoutExt = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
        String ext = dotIdx > 0 ? fileName.substring(dotIdx) : ".png";
        return new File(versionsDir, nameWithoutExt + "_v" + versionNum + ext);
    }

    private void writeImage(WritableImage image, File targetFile) throws IOException {
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
        String formatName = getImageFormatName(targetFile);

        if ("JPEG".equals(formatName)) {
            BufferedImage rgbImage = new BufferedImage(
                    bufferedImage.getWidth(),
                    bufferedImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            var graphics = rgbImage.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
            graphics.drawImage(bufferedImage, 0, 0, null);
            graphics.dispose();
            bufferedImage = rgbImage;
        }

        if (!ImageIO.write(bufferedImage, formatName, targetFile)) {
            throw new IOException("不支持的图片格式: " + formatName);
        }
    }

    private String getImageFormatName(File file) {
        String name = file.getName();
        int dotIdx = name.lastIndexOf('.');
        String ext = dotIdx > 0 ? name.substring(dotIdx + 1).toUpperCase() : "PNG";
        return switch (ext) {
            case "JPG", "JPEG" -> "JPEG";
            case "BMP" -> "BMP";
            case "GIF" -> "GIF";
            default -> "PNG";
        };
    }
}
