package com.imagemanager.scanner;

import com.imagemanager.ai.AIConfig;
import com.imagemanager.ai.AIService;
import com.imagemanager.ai.OpenAICompatibleService;
import com.imagemanager.dao.*;
import com.imagemanager.model.ImageAnalysisResult;
import com.imagemanager.model.ImageFile;
import com.imagemanager.util.ImageUtil;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final ScanProgressEstimator progressEstimator;
    private final long requestDelayMillis;
    private final int batchLimit;

    /**
     * @param rootDirectory 要扫描的根目录
     */
    public ScanTask(File rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.imageDao = new ImageDaoImpl();
        this.directoryDao = new DirectoryDaoImpl();
        this.tagDao = new TagDaoImpl();
        this.aiService = new OpenAICompatibleService();
        this.requestDelayMillis = AIConfig.getRequestDelay();
        this.batchLimit = AIConfig.getBatchLimit();
        this.progressEstimator = new ScanProgressEstimator(requestDelayMillis, batchLimit);
    }

    public ScanProgressEstimator.Snapshot getProgressSnapshot() {
        return progressEstimator.snapshot();
    }

    @Override
    protected Void call() throws Exception {
        logger.info("扫描任务启动: {}", rootDirectory.getAbsolutePath());
        publishSnapshot();
        ensureScanSchema();

        // ==================== Phase 1: 文件系统扫描 & 入库 ====================
        DirectoryScanner scanner = new DirectoryScanner();
        List<DirectoryScanner.ScannedImage> scannedImages = scanner.scan(rootDirectory, this::isCancelled);

        if (isCancelled()) {
            updateMessage("扫描已取消");
            return null;
        }

        int totalImages = scannedImages.size();
        logger.info("Phase 1: 发现 {} 张图片，开始入库...", totalImages);
        progressEstimator.beginImportPhase(totalImages);
        publishSnapshot();

        int insertedCount = 0;
        Map<String, Integer> directoryIdCache = new HashMap<>();
        Map<Integer, Set<String>> existingPathsByDirectory = new HashMap<>();
        for (int i = 0; i < totalImages; i++) {
            if (isCancelled()) return null;

            DirectoryScanner.ScannedImage img = scannedImages.get(i);
            progressEstimator.onImportItemStarted(img.fileName());

            long itemStart = System.nanoTime();
            try {
                int directoryId = getOrCreateDirectoryId(img.file().getParentFile(), directoryIdCache);
                Set<String> existingPaths = existingPathsByDirectory.computeIfAbsent(
                        directoryId,
                        imageDao::findFilePathsByDirectoryId);

                if (!existingPaths.contains(img.filePath())) {
                    int[] dimensions = ImageUtil.getImageDimensions(img.filePath());
                    ImageFile imageFile = new ImageFile(
                            0, img.fileName(), img.filePath(),
                            directoryId,
                            img.fileSize(), dimensions[0], dimensions[1], img.format(),
                            null, LocalDateTime.now(), LocalDateTime.now(), false, false
                    );
                    imageDao.insert(imageFile);
                    existingPaths.add(img.filePath());
                    insertedCount++;
                }
            } catch (Exception e) {
                logger.warn("入库失败，跳过: {} - {}", img.fileName(), e.getMessage());
            } finally {
                long itemDurationMillis = Math.max(1L, (System.nanoTime() - itemStart) / 1_000_000L);
                progressEstimator.onImportItemCompleted(itemDurationMillis);
                publishSnapshot();
            }
        }

        logger.info("Phase 1 完成: 新增 {} 张图片记录", insertedCount);

        // ==================== Phase 2: AI 图像识别 ====================
        if (!AIConfig.isConfigured()) {
            progressEstimator.markCompleted("AI API 未配置，已完成目录扫描与入库。");
            publishSnapshot();
            logger.info("AI API 未配置，跳过 Phase 2");
            return null;
        }

        // 只处理本次扫描根目录下的图片，避免切换目录后继续消费旧目录。
        int pendingTotal = countPendingAIImages();
        List<ImageFile> pendingImages = getPendingAIImages(batchLimit);
        int pendingCount = pendingImages.size();
        logger.info("Phase 2: 当前目录共 {} 张图片，待AI处理 {} 张，本批 {} 张，批量上限 {}",
                totalImages, pendingTotal, pendingCount, batchLimit);

        if (pendingCount == 0) {
            progressEstimator.markCompleted("本目录共 " + totalImages + " 张图片，所有图片都已完成 AI 识别。");
            publishSnapshot();
            return null;
        }

        progressEstimator.beginAiPhase(pendingTotal, pendingCount);
        publishSnapshot();

        int successCount = 0;
        int failedCount = 0;

        for (int i = 0; i < pendingCount; i++) {
            if (isCancelled()) {
                updateMessage("扫描已取消");
                return null;
            }

            ImageFile img = pendingImages.get(i);
            progressEstimator.onAiItemStarted(img.fileName());
            publishSnapshot();

            long aiStart = System.nanoTime();
            boolean success = false;
            try {
                File imageFile = new File(img.filePath());
                if (!imageFile.exists()) {
                    logger.warn("图片文件不存在，跳过: {}", img.filePath());
                    markAiProcessed(img.id(), false);
                    failedCount++;
                } else {
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
                        markAiProcessed(img.id(), true);
                        successCount++;
                        success = true;
                    } else {
                        markAiProcessed(img.id(), false);
                        failedCount++;
                    }
                }

                // 限流保护：请求间隔
                if (i < pendingCount - 1) {
                    sleepWithCancellation(requestDelayMillis);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                updateMessage("扫描已中断");
                return null;
            } catch (Exception e) {
                logger.error("AI识别失败: {} - {}", img.fileName(), e.getMessage());
                markAiProcessed(img.id(), false);
                failedCount++;
            } finally {
                long aiDurationMillis = Math.max(1L, (System.nanoTime() - aiStart) / 1_000_000L);
                progressEstimator.onAiItemCompleted(aiDurationMillis, success);
                publishSnapshot();
            }
        }

        String limitNotice = pendingTotal > batchLimit
                ? " 本次达到 " + batchLimit + "(max)，剩余图片可再次扫描继续处理。"
                : "";
        progressEstimator.markCompleted("本目录共 " + totalImages + " 张图片，本批成功 "
                + successCount + " 张，失败 " + failedCount + " 张。" + limitNotice);
        publishSnapshot();
        logger.info("扫描任务全部完成");
        return null;
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        progressEstimator.markCancelled("已停止当前扫描，已经写入数据库的 AI 标签会保留，可稍后继续补打。");
        publishSnapshot();
    }

    @Override
    protected void failed() {
        super.failed();
        Throwable error = getException();
        String detail = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "后台扫描任务异常结束。"
                : "后台扫描任务异常结束：" + error.getMessage();
        progressEstimator.markFailed(detail);
        publishSnapshot();
    }

    private void publishSnapshot() {
        ScanProgressEstimator.Snapshot snapshot = progressEstimator.snapshot();
        updateMessage(snapshot.summaryText());
        if (snapshot.progress() < 0) {
            updateProgress(-1, 1);
        } else {
            updateProgress(snapshot.progress(), 1);
        }
    }

    /**
     * 获取或创建目录的数据库ID。
     */
    private int getOrCreateDirectoryId(File dir) {
        var dirNode = directoryDao.findOrCreate(dir.getAbsolutePath());
        return dirNode.id();
    }

    private int getOrCreateDirectoryId(File dir, Map<String, Integer> directoryIdCache) {
        String path = dir.getAbsolutePath();
        Integer cached = directoryIdCache.get(path);
        if (cached != null) {
            return cached;
        }
        int directoryId = getOrCreateDirectoryId(dir);
        directoryIdCache.put(path, directoryId);
        return directoryId;
    }

    /**
     * 统计本次扫描根目录下所有待AI处理的图片。
     */
    private int countPendingAIImages() {
        String sql = """
                SELECT COUNT(*)
                FROM images
                WHERE ai_processed = FALSE
                  AND is_deleted = FALSE
                  AND (LOWER(file_path) = LOWER(?) OR POSITION(LOWER(?) IN LOWER(file_path)) = 1)
                """;
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(sql)) {
            bindRootPath(ps);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            logger.error("统计待处理图片失败", e);
            return 0;
        }
    }

    /**
     * 获取本次扫描根目录下待AI处理的图片，单批最多处理 limit 张。
     */
    private List<ImageFile> getPendingAIImages(int limit) {
        List<ImageFile> allImages = new ArrayList<>();
        String sql = """
                SELECT *
                FROM images
                WHERE ai_processed = FALSE
                  AND is_deleted = FALSE
                  AND (LOWER(file_path) = LOWER(?) OR POSITION(LOWER(?) IN LOWER(file_path)) = 1)
                ORDER BY id
                LIMIT ?
                """;
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(sql)) {
            bindRootPath(ps);
            ps.setInt(3, Math.max(1, limit));
            try (var rs = ps.executeQuery()) {
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
                            rs.getBoolean("is_deleted"),
                            rs.getBoolean("ai_processed")
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("查询待处理图片失败", e);
        }
        return allImages;
    }

    private void bindRootPath(java.sql.PreparedStatement ps) throws java.sql.SQLException {
        String rootPath = normalizedRootPath();
        ps.setString(1, rootPath);
        ps.setString(2, rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator);
    }

    private String normalizedRootPath() {
        try {
            return rootDirectory.getCanonicalPath();
        } catch (Exception e) {
            return rootDirectory.getAbsolutePath();
        }
    }

    private void sleepWithCancellation(long millis) throws InterruptedException {
        long remaining = Math.max(0, millis);
        while (remaining > 0) {
            if (isCancelled()) {
                throw new InterruptedException("扫描已取消");
            }
            long slice = Math.min(remaining, 200);
            Thread.sleep(slice);
            remaining -= slice;
        }
    }

    private void ensureScanSchema() {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS ai_processed BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS last_ai_scan TIMESTAMP");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_original_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_storage_path TEXT");
            stmt.execute("ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
            stmt.execute("ALTER TABLE images DROP CONSTRAINT IF EXISTS uq_images_dir_name");
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_images_active_dir_name_unique
                    ON images (directory_id, file_name) WHERE is_deleted = FALSE
                    """);
        } catch (Exception e) {
            logger.error("初始化扫描数据库字段失败", e);
            throw new RuntimeException("初始化扫描数据库字段失败: " + e.getMessage(), e);
        }
    }

    private void markAiProcessed(int imageId, boolean processed) {
        String sql = """
                UPDATE images
                SET ai_processed = ?, last_ai_scan = NOW(), modified_at = NOW()
                WHERE id = ?
                """;
        try (var conn = DatabaseConnection.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, processed);
            ps.setInt(2, imageId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("更新AI处理状态失败: imageId={}, processed={}", imageId, processed, e);
        }
    }
}
