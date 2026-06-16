package com.imagemanager.scanner;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 扫描过程的轻量级 ETA 估算器。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>只保留 O(1) 个状态量，不缓存历史样本；</li>
 *   <li>使用指数滑动平均平滑单张图片耗时；</li>
 *   <li>对显示用剩余时间做分档与迟滞处理，避免数值剧烈抖动。</li>
 * </ul>
 */
public final class ScanProgressEstimator {

    private static final double IMPORT_PHASE_SHARE = 0.18;
    private static final double EWMA_WARMUP_ALPHA = 0.34;
    private static final double EWMA_STABLE_ALPHA = 0.16;
    private static final long DEFAULT_IMPORT_MILLIS = 30;
    private static final long DEFAULT_AI_MILLIS = 3_500;
    private static final DateTimeFormatter STARTED_AT_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record Snapshot(
            String phaseText,
            String summaryText,
            String detailText,
            String startedAtText,
            String elapsedText,
            String remainingText,
            String rateText,
            double progress
    ) {
    }

    private enum Phase {
        DISCOVERY,
        IMPORT,
        AI,
        DONE,
        FAILED,
        CANCELLED
    }

    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startedAtMillis = System.currentTimeMillis();
    private final long requestDelayMillis;
    private final int batchLimit;

    private Phase phase = Phase.DISCOVERY;
    private String currentItemName = "";
    private String finalDetail = "";

    private int totalImages;
    private int importTotal;
    private int importCompleted;

    private int pendingTotal;
    private int aiBatchTotal;
    private int aiCompleted;
    private int aiSuccess;
    private int aiFailed;

    private int importSamples;
    private int aiSamples;
    private double importAverageMillis = DEFAULT_IMPORT_MILLIS;
    private double aiAverageMillis;

    private double lastProgress = -1.0;
    private long displayedRemainingMillis = -1L;
    private long lastRemainingRefreshMillis = startedAtMillis;

    public ScanProgressEstimator(long requestDelayMillis, int batchLimit) {
        this.requestDelayMillis = Math.max(0, requestDelayMillis);
        this.batchLimit = Math.max(1, batchLimit);
        this.aiAverageMillis = Math.max(DEFAULT_AI_MILLIS, this.requestDelayMillis + 2_600L);
    }

    public synchronized void beginImportPhase(int totalImages) {
        this.phase = Phase.IMPORT;
        this.totalImages = Math.max(0, totalImages);
        this.importTotal = this.totalImages;
        this.importCompleted = 0;
        this.currentItemName = "";
        this.finalDetail = "";
        updateProgressValue(0.0);
    }

    public synchronized void onImportItemStarted(String fileName) {
        currentItemName = sanitizeItemName(fileName);
    }

    public synchronized void onImportItemCompleted(long durationMillis) {
        importCompleted = Math.min(importTotal, importCompleted + 1);
        importAverageMillis = ewma(importAverageMillis, durationMillis, importSamples);
        importSamples++;
        updateProgressValue(importTotal == 0
                ? IMPORT_PHASE_SHARE
                : IMPORT_PHASE_SHARE * importCompleted / (double) importTotal);
    }

    public synchronized void beginAiPhase(int pendingTotal, int aiBatchTotal) {
        this.phase = Phase.AI;
        this.pendingTotal = Math.max(0, pendingTotal);
        this.aiBatchTotal = Math.max(0, aiBatchTotal);
        this.aiCompleted = 0;
        this.aiSuccess = 0;
        this.aiFailed = 0;
        this.currentItemName = "";
        updateProgressValue(IMPORT_PHASE_SHARE);
    }

    public synchronized void onAiItemStarted(String fileName) {
        currentItemName = sanitizeItemName(fileName);
    }

    public synchronized void onAiItemCompleted(long durationMillis, boolean success) {
        aiCompleted = Math.min(aiBatchTotal, aiCompleted + 1);
        aiAverageMillis = ewma(aiAverageMillis, durationMillis, aiSamples);
        aiSamples++;
        if (success) {
            aiSuccess++;
        } else {
            aiFailed++;
        }

        if (aiBatchTotal > 0) {
            updateProgressValue(IMPORT_PHASE_SHARE
                    + (1.0 - IMPORT_PHASE_SHARE) * aiCompleted / (double) aiBatchTotal);
        }
    }

    public synchronized void markCompleted(String detail) {
        phase = Phase.DONE;
        finalDetail = sanitizeDetail(detail);
        displayedRemainingMillis = 0L;
        updateProgressValue(1.0);
    }

    public synchronized void markFailed(String detail) {
        phase = Phase.FAILED;
        finalDetail = sanitizeDetail(detail);
        displayedRemainingMillis = 0L;
        if (lastProgress < 0) {
            lastProgress = 0.0;
        }
    }

    public synchronized void markCancelled(String detail) {
        phase = Phase.CANCELLED;
        finalDetail = sanitizeDetail(detail);
        displayedRemainingMillis = 0L;
        if (lastProgress < 0) {
            lastProgress = 0.0;
        }
    }

    public synchronized Snapshot snapshot() {
        long nowMillis = System.currentTimeMillis();
        long elapsedMillis = Math.max(0L, nowMillis - startedAtMillis);
        long rawRemainingMillis = estimateRemainingMillis();
        long remainingMillis = stabilizeRemainingMillis(rawRemainingMillis, nowMillis);

        return new Snapshot(
                phaseText(),
                summaryText(),
                detailText(),
                STARTED_AT_FORMAT.format(startedAt),
                formatDuration(elapsedMillis),
                remainingMillis < 0 ? "估算中" : formatDuration(remainingMillis),
                rateText(),
                progressValue()
        );
    }

    private String phaseText() {
        return switch (phase) {
            case DISCOVERY -> "扫描目录";
            case IMPORT -> "写入目录";
            case AI -> "AI 打标签";
            case DONE -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    private String summaryText() {
        return switch (phase) {
            case DISCOVERY -> "正在扫描目录结构";
            case IMPORT -> importTotal <= 0
                    ? "正在写入目录记录"
                    : String.format("目录录入 %d/%d 张", importCompleted, importTotal);
            case AI -> aiBatchTotal <= 0
                    ? "正在准备 AI 打标签"
                    : String.format("AI 打标签 %d/%d 张（%.0f%%）",
                    aiCompleted,
                    aiBatchTotal,
                    aiCompleted * 100.0 / aiBatchTotal);
            case DONE -> "AI 标签处理完成";
            case FAILED -> "扫描失败";
            case CANCELLED -> "扫描已取消";
        };
    }

    private String detailText() {
        return switch (phase) {
            case DISCOVERY -> "正在遍历目录与计算文件摘要，目录总量明确后会开始稳定收敛剩余时间。";
            case IMPORT -> String.format(
                    "当前文件：%s；正在写入数据库记录。",
                    currentItemOrFallback()
            );
            case AI -> String.format(
                    "当前文件：%s；成功 %d，失败 %d，本批上限 %d(max)。",
                    currentItemOrFallback(),
                    aiSuccess,
                    aiFailed,
                    batchLimit
            );
            case DONE, FAILED, CANCELLED -> finalDetail.isBlank()
                    ? "任务已结束。"
                    : finalDetail;
        };
    }

    private String rateText() {
        if (phase == Phase.AI || aiSamples > 0) {
            return formatRate(effectiveAiMillis());
        }
        if (phase == Phase.IMPORT || importSamples > 0) {
            return formatRate(importAverageMillis);
        }
        return "计算中";
    }

    private long estimateRemainingMillis() {
        return switch (phase) {
            case DISCOVERY -> -1L;
            case IMPORT -> estimateImportPhaseRemaining();
            case AI -> estimateAiPhaseRemaining();
            case DONE, FAILED, CANCELLED -> 0L;
        };
    }

    private long estimateImportPhaseRemaining() {
        if (importTotal <= 0) {
            return 0L;
        }
        long importRemaining = Math.round(Math.max(0, importTotal - importCompleted) * importAverageMillis);
        int provisionalAiCount = Math.min(Math.max(totalImages, 0), batchLimit);
        long aiRemaining = Math.round(provisionalAiCount * effectiveAiMillis());
        return importRemaining + aiRemaining;
    }

    private long estimateAiPhaseRemaining() {
        if (aiBatchTotal <= 0) {
            return 0L;
        }
        int remainingItems = Math.max(0, aiBatchTotal - aiCompleted);
        return Math.round(remainingItems * effectiveAiMillis());
    }

    private double effectiveAiMillis() {
        return aiAverageMillis + requestDelayMillis;
    }

    private long stabilizeRemainingMillis(long rawRemainingMillis, long nowMillis) {
        if (rawRemainingMillis < 0) {
            displayedRemainingMillis = -1L;
            lastRemainingRefreshMillis = nowMillis;
            return -1L;
        }

        long quantizedRaw = quantizeRemainingMillis(rawRemainingMillis);
        if (displayedRemainingMillis < 0) {
            displayedRemainingMillis = quantizedRaw;
            lastRemainingRefreshMillis = nowMillis;
            return quantizedRaw;
        }

        long elapsedSinceRefresh = Math.max(0L, nowMillis - lastRemainingRefreshMillis);
        long countdownCandidate = Math.max(0L, displayedRemainingMillis - elapsedSinceRefresh);
        long bucketSize = bucketSizeMillis(quantizedRaw);

        if (quantizedRaw <= countdownCandidate) {
            displayedRemainingMillis = quantizedRaw;
        } else {
            long upwardGap = quantizedRaw - countdownCandidate;
            if (upwardGap > bucketSize * 2L) {
                displayedRemainingMillis = countdownCandidate + Math.min(upwardGap / 3L, bucketSize * 2L);
            } else {
                displayedRemainingMillis = countdownCandidate;
            }
        }

        lastRemainingRefreshMillis = nowMillis;
        return displayedRemainingMillis;
    }

    private long quantizeRemainingMillis(long rawRemainingMillis) {
        long bucketSize = bucketSizeMillis(rawRemainingMillis);
        if (bucketSize <= 1L) {
            return rawRemainingMillis;
        }
        return Math.max(0L, Math.round(rawRemainingMillis / (double) bucketSize) * bucketSize);
    }

    private long bucketSizeMillis(long remainingMillis) {
        if (remainingMillis < 30_000L) {
            return 1_000L;
        }
        if (remainingMillis < 120_000L) {
            return 5_000L;
        }
        if (remainingMillis < 600_000L) {
            return 15_000L;
        }
        return 30_000L;
    }

    private String formatRate(double millisPerItem) {
        if (millisPerItem <= 0) {
            return "计算中";
        }

        double itemsPerMinute = 60_000.0 / millisPerItem;
        if (itemsPerMinute >= 10.0) {
            return String.format("%.0f 张/分", itemsPerMinute);
        }
        if (itemsPerMinute >= 1.0) {
            return String.format("%.1f 张/分", itemsPerMinute);
        }
        return String.format("%.1f 张/小时", itemsPerMinute * 60.0);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1_000L);
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format("%d小时%02d分", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%d分%02d秒", minutes, seconds);
        }
        return seconds + "秒";
    }

    private double ewma(double previous, long latestMillis, int sampleCount) {
        if (latestMillis <= 0) {
            return previous;
        }
        double alpha = sampleCount < 3 ? EWMA_WARMUP_ALPHA : EWMA_STABLE_ALPHA;
        return previous + alpha * (latestMillis - previous);
    }

    private void updateProgressValue(double candidate) {
        if (candidate < 0) {
            return;
        }
        if (lastProgress < 0) {
            lastProgress = candidate;
            return;
        }
        lastProgress = Math.min(1.0, Math.max(lastProgress, candidate));
    }

    private double progressValue() {
        if (phase == Phase.DISCOVERY) {
            return -1.0;
        }
        if (phase == Phase.DONE) {
            return 1.0;
        }
        return lastProgress < 0 ? 0.0 : lastProgress;
    }

    private String currentItemOrFallback() {
        String itemName = currentItemName.isBlank() ? "准备下一个文件" : currentItemName;
        if (itemName.length() <= 34) {
            return itemName;
        }
        return itemName.substring(0, 16) + "..." + itemName.substring(itemName.length() - 14);
    }

    private String sanitizeItemName(String fileName) {
        return fileName == null ? "" : fileName.trim();
    }

    private String sanitizeDetail(String detail) {
        return detail == null ? "" : detail.trim();
    }
}
