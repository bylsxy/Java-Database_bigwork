package com.imagemanager.service;

import com.imagemanager.dao.DatabaseConnection;
import com.imagemanager.dao.ImageDao;
import com.imagemanager.dao.ImageDaoImpl;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * One-time metadata repair for old database records that were created before
 * width/height were reliably written.
 */
public class ImageDimensionRepairService {

    private static final Logger logger = LoggerFactory.getLogger(ImageDimensionRepairService.class);
    private static final String REPAIR_FLAG_KEY = "image_dimensions_backfill_v1";
    private static volatile boolean running = false;

    private final ImageDao imageDao;
    private final SettingsDao settingsDao;

    public ImageDimensionRepairService() {
        this(new ImageDaoImpl(), new SettingsDaoImpl());
    }

    ImageDimensionRepairService(ImageDao imageDao, SettingsDao settingsDao) {
        this.imageDao = imageDao;
        this.settingsDao = settingsDao;
    }

    public static void runOnceInBackground() {
        if (!DatabaseConnection.isInitialized() || running) {
            return;
        }
        running = true;
        Thread thread = new Thread(() -> {
            try {
                new ImageDimensionRepairService().repairOnceIfNeeded();
            } catch (Exception e) {
                logger.warn("图片尺寸旧数据修复未完成: {}", e.getMessage());
            } finally {
                running = false;
            }
        }, "Image-Dimension-Repair");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void repairOnceIfNeeded() {
        if ("done".equalsIgnoreCase(settingsDao.getValueOrDefault(REPAIR_FLAG_KEY, ""))) {
            return;
        }

        List<ImageFile> missingDimensions = imageDao.findMissingDimensions();
        int repaired = 0;
        int skipped = 0;
        for (ImageFile image : missingDimensions) {
            File file = new File(image.filePath());
            if (!file.isFile()) {
                skipped++;
                continue;
            }
            int[] dimensions = ImageUtil.getImageDimensions(file.getAbsolutePath());
            if (dimensions[0] > 0 && dimensions[1] > 0) {
                imageDao.updateDimensions(image.id(), dimensions[0], dimensions[1], file.length());
                repaired++;
            } else {
                skipped++;
            }
            sleepBriefly();
        }

        settingsDao.upsert(REPAIR_FLAG_KEY, "done");
        logger.info("图片尺寸旧数据修复完成: repaired={}, skipped={}", repaired, skipped);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
