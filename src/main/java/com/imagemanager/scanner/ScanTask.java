package com.imagemanager.scanner;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.*;
import com.imagemanager.model.ImageAnalysisResult;
import com.imagemanager.model.ImageFile;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扫描任务 — JavaFX 后台 Task，负责：
 * <ol>
 *   <li>Phase 1: 深度遍历目录，将新图片录入数据库</li>
 *   <li>Phase 2: 对未处理的图片调用 AI 识别，生成标签入库</li>
 * </ol>
 * <p>
 * 通过 {@link #updateProgress(long, long)} 和 {@link #updateMessage(String)} 向UI报告进度。
 * 右下角进度条绑定此 Task 的 progress 和 message 属性。
 */
public class ScanTask extends Task<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ScanTask.class);

    private final File rootDirectory;
    private final ImageDao imageDao;
    private final DirectoryDao directoryDao;
    private final TagDao tagDao;
    private final AIService aiService;

    /**
     * @param rootDirectory 要扫描的根目录
     */
    public ScanTask(File rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.imageDao = new ImageDaoImpl();
        this.directoryDao = new DirectoryDaoImpl();
        this.tagDao = new TagDaoImpl();
        this.aiService = new OpenAICompatibleService();
    }

    @Override
    protected Void call() throws Exception {
        logger.info("扫描任务启动: {}", rootDirectory.getAbsolutePath());
        updateMessage("正在扫描目录结构...");

        // ==================== Phase 1: 文件系统扫描 & 入库 ====================
        DirectoryScanner scanner = new DirectoryScanner();
        List<DirectoryScanner.ScannedImage> scannedImages = scanner.scan(rootDirectory);

        if (isCancelled()) return null;

        int totalImages = scannedImages.size();
        logger.info("Phase 1: 发现 {} 张图片，开始入库...", totalImages);
        updateMessage("发现 " + totalImages + " 张图片，正在入库...");

        int insertedCount = 0;
        for (int i = 0; i < totalImages; i++) {
            if (isCancelled()) return null;

            DirectoryScanner.ScannedImage img = scannedImages.get(i);
            updateMessage("正在入库: " + img.fileName() + "  [" + (i + 1) + "/" + totalImages + "]");
            updateProgress(i, totalImages * 2L); // Phase1占前半段进度

            try {
                // 检查是否已存在（通过哈希）
                // 如果存在就跳过
                var existingImages = imageDao.findByDirectoryId(
                        getOrCreateDirectoryId(img.file().getParentFile()));
                boolean exists = existingImages.stream()
                        .anyMatch(existing -> existing.filePath().equals(img.filePath()));

                if (!exists) {
                    ImageFile imageFile = new ImageFile(
                            0, img.fileName(), img.filePath(),
                            getOrCreateDirectoryId(img.file().getParentFile()),
                            img.fileSize(), 0, 0, img.format(),
                            null, LocalDateTime.now(), LocalDateTime.now(), false
                    );
                    imageDao.insert(imageFile);
                    insertedCount++;
                }
            } catch (Exception e) {
                logger.warn("入库失败，跳过: {} - {}", img.fileName(), e.getMessage());
            }
        }

        logger.info("Phase 1 完成: 新增 {} 张图片记录", insertedCount);

        // ==================== Phase 2: AI 图像识别 ====================
        if (!AIConfig.isConfigured()) {
            updateMessage("AI API 未配置，跳过图像识别。扫描完成。");
            logger.info("AI API 未配置，跳过 Phase 2");
            updateProgress(1, 1);
            return null;
        }

        // 获取所有未AI处理的图片
        List<ImageFile> pendingImages = getPendingAIImages();
        int pendingCount = pendingImages.size();
        logger.info("Phase 2: {} 张图片待AI处理", pendingCount);

        if (pendingCount == 0) {
            updateMessage("所有图片已完成AI识别。扫描完成。");
            updateProgress(1, 1);
            return null;
        }

        long requestDelay = AIConfig.getRequestDelay();

        for (int i = 0; i < pendingCount; i++) {
            if (isCancelled()) {
                updateMessage("扫描已取消");
                return null;
            }

            ImageFile img = pendingImages.get(i);
            String progressText = String.format("正在AI识别: %s  [%d/%d  %.1f%%]",
                    img.filePath(), (i + 1), pendingCount,
                    (i + 1.0) / pendingCount * 100);
            updateMessage(progressText);
            updateProgress(totalImages + i, totalImages + (long) pendingCount);

            try {
                File imageFile = new File(img.filePath());
                if (!imageFile.exists()) {
                    logger.warn("图片文件不存在，跳过: {}", img.filePath());
                    continue;
                }

                Optional<ImageAnalysisResult> result = aiService.analyzeImage(imageFile, img.id());
                if (result.isPresent()) {
                    ImageAnalysisResult analysis = result.get();

                    // 保存AI分析结果
                    tagDao.saveAnalysisResult(analysis);

                    // 批量插入标签
                    Map<String, List<String>> tagsByCategory = analysis.tagsByCategory();
                    if (tagsByCategory != null) {
                        List<String> categories = new ArrayList<>();
                        List<String> tagNames = new ArrayList<>();
                        List<Float> confidences = new ArrayList<>();

                        tagsByCategory.forEach((category, tagList) -> {
                            for (String tagName : tagList) {
                                categories.add(category);
                                tagNames.add(tagName);
                                confidences.add(1.0f);
                            }
                        });

                        if (!categories.isEmpty()) {
                            float[] confArray = new float[confidences.size()];
                            for (int j = 0; j < confidences.size(); j++) {
                                confArray[j] = confidences.get(j);
                            }
                            tagDao.batchInsertTags(
                                    img.id(),
                                    categories.toArray(new String[0]),
                                    tagNames.toArray(new String[0]),
                                    confArray
                            );
                        }
                    }

                    logger.info("AI识别完成: {} (标签数: {})", img.fileName(),
                            tagsByCategory != null ? tagsByCategory.values().stream()
                                    .mapToInt(List::size).sum() : 0);
                }

                // 限流保护：请求间隔
                if (i < pendingCount - 1) {
                    Thread.sleep(requestDelay);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                updateMessage("扫描已中断");
                return null;
            } catch (Exception e) {
                logger.error("AI识别失败: {} - {}", img.fileName(), e.getMessage());
            }
        }

        updateMessage("扫描完成！共处理 " + pendingCount + " 张图片。");
        updateProgress(1, 1);
        logger.info("扫描任务全部完成");
        return null;
    }

    /**
     * 获取或创建目录的数据库ID。
     */
    private int getOrCreateDirectoryId(File dir) {
        var dirNode = directoryDao.findOrCreate(dir.getAbsolutePath());
        return dirNode.id();
    }

    /**
     * 获取所有待AI处理的图片。
     */
    private List<ImageFile> getPendingAIImages() {
        // 通过SQL直接查询 ai_processed = FALSE 的图片
        List<ImageFile> allImages = new ArrayList<>();
        String sql = "SELECT * FROM images WHERE ai_processed = FALSE AND is_deleted = FALSE ORDER BY id LIMIT 500";
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                allImages.add(new ImageFile(
                        rs.getInt("id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getInt("directory_id"),
                        rs.getLong("file_size"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getString("format"),
                        null, // 不加载缩略图
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("modified_at").toLocalDateTime(),
                        rs.getBoolean("is_deleted")
                ));
            }
        } catch (Exception e) {
            logger.error("查询待处理图片失败", e);
        }
        return allImages;
    }
}
