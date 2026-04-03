package com.imagemanager.util;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

/**
 * 图片处理工具类 — 提供图片加载、缩略图生成、尺寸读取等功能。
 * <p>
 * 支持格式：JPG, JPEG, PNG, GIF, BMP
 * <p>
 * 缩略图生成使用等比缩放算法，保证不变形。
 */
public final class ImageUtil {

    private static final Logger logger = LoggerFactory.getLogger(ImageUtil.class);

    /** 默认缩略图最大宽度 */
    public static final int DEFAULT_THUMBNAIL_WIDTH = 150;

    /** 默认缩略图最大高度 */
    public static final int DEFAULT_THUMBNAIL_HEIGHT = 120;

    private ImageUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 加载图片为 JavaFX Image 对象。
     * 使用后台加载模式（JavaFX 在单独线程中加载，不阻塞 UI）。
     *
     * @param filePath 图片文件路径
     * @return JavaFX Image，加载失败返回 null
     */
    public static Image loadImage(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                logger.warn("图片文件不存在: {}", filePath);
                return null;
            }
            // 使用 file URI 加载
            return new Image(file.toURI().toString());
        } catch (Exception e) {
            logger.error("加载图片失败: {} - {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 加载图片并指定最大尺寸（等比缩放，用于缩略图显示）。
     *
     * @param filePath  图片文件路径
     * @param maxWidth  最大宽度
     * @param maxHeight 最大高度
     * @return 等比缩放后的 JavaFX Image
     */
    public static Image loadThumbnailImage(String filePath, int maxWidth, int maxHeight) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return null;

            // JavaFX Image 构造器支持指定尺寸和保持比例
            return new Image(
                    file.toURI().toString(),
                    maxWidth, maxHeight,
                    true,   // preserveRatio: 保持宽高比
                    true    // smooth: 平滑缩放
            );
        } catch (Exception e) {
            logger.error("加载缩略图失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 byte[] 数据（数据库 bytea）构建 JavaFX Image。
     *
     * @param data 图片的二进制数据
     * @return JavaFX Image，失败返回 null
     */
    public static Image fromBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            return new Image(new ByteArrayInputStream(data));
        } catch (Exception e) {
            logger.error("从 byte[] 构建 Image 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成缩略图并返回 PNG 格式的 byte[]（用于存入数据库 bytea）。
     * <p>
     * 缩放算法：计算 min(maxWidth/原宽, maxHeight/原高) 作为缩放比，
     * 然后用该比例缩放，保证既不超过最大尺寸又不变形。
     *
     * @param filePath  源图片文件路径
     * @param maxWidth  缩略图最大宽度
     * @param maxHeight 缩略图最大高度
     * @return PNG 格式的二进制数据，失败返回 null
     */
    public static byte[] generateThumbnailBytes(String filePath, int maxWidth, int maxHeight) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return null;

            BufferedImage original = ImageIO.read(file);
            if (original == null) {
                logger.warn("无法读取图片: {}", filePath);
                return null;
            }

            int origWidth = original.getWidth();
            int origHeight = original.getHeight();

            // 计算等比缩放比例
            double ratio = Math.min(
                    (double) maxWidth / origWidth,
                    (double) maxHeight / origHeight
            );

            // 如果原图已经够小，就不放大
            if (ratio >= 1.0) ratio = 1.0;

            int newWidth = (int) (origWidth * ratio);
            int newHeight = (int) (origHeight * ratio);

            // 使用 Java2D 高质量缩放
            var scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            var g2d = scaledImage.createGraphics();
            g2d.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
            g2d.dispose();

            // 输出为 PNG 格式 byte[]
            var baos = new ByteArrayOutputStream();
            ImageIO.write(scaledImage, "PNG", baos);
            return baos.toByteArray();

        } catch (IOException e) {
            logger.error("生成缩略图失败: {} - {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 读取图片尺寸（宽、高），不加载整个图片到内存。
     *
     * @param filePath 图片文件路径
     * @return int[]{width, height}，失败时返回 {0, 0}
     */
    public static int[] getImageDimensions(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return new int[]{0, 0};

            // 使用 ImageIO 的 reader 只读取元数据
            try (var input = ImageIO.createImageInputStream(file)) {
                var readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        return new int[]{width, height};
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("读取图片尺寸失败: {}", e.getMessage());
        }
        return new int[]{0, 0};
    }
}
