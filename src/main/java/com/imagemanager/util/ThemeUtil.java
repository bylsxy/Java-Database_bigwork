package com.imagemanager.util;

import com.imagemanager.dao.SettingsDao;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.io.File;

/**
 * 主题背景工具类。
 */
public final class ThemeUtil {

    public static final String THEME_BACKGROUND_PATH = "theme_background_path";
    public static final String THEME_BACKGROUND_OPACITY = "theme_background_opacity";
    public static final String DEFAULT_OPACITY = "0.35";

    private ThemeUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    public static void applyThemeBackground(StackPane root, ImageView backgroundImageView, SettingsDao settingsDao) {
        if (root == null || backgroundImageView == null || settingsDao == null) {
            return;
        }

        backgroundImageView.fitWidthProperty().unbind();
        backgroundImageView.fitHeightProperty().unbind();
        backgroundImageView.fitWidthProperty().bind(root.widthProperty());
        backgroundImageView.fitHeightProperty().bind(root.heightProperty());
        backgroundImageView.setPreserveRatio(false);
        backgroundImageView.setMouseTransparent(true);

        String imagePath = settingsDao.getValueOrDefault(THEME_BACKGROUND_PATH, "");
        double opacity = parseOpacity(settingsDao.getValueOrDefault(THEME_BACKGROUND_OPACITY, DEFAULT_OPACITY));

        if (imagePath == null || imagePath.isBlank()) {
            backgroundImageView.setImage(null);
            backgroundImageView.setVisible(false);
            return;
        }

        File imageFile = new File(imagePath);
        if (!imageFile.exists() || !imageFile.isFile()) {
            backgroundImageView.setImage(null);
            backgroundImageView.setVisible(false);
            return;
        }

        backgroundImageView.setImage(new Image(imageFile.toURI().toString()));
        backgroundImageView.setOpacity(opacity);
        backgroundImageView.setVisible(true);
    }

    public static void markThemedSurface(Region region) {
        if (region == null) {
            return;
        }
        if (!region.getStyleClass().contains("theme-surface")) {
            region.getStyleClass().add("theme-surface");
        }
    }

    public static double parseOpacity(String value) {
        try {
            double opacity = Double.parseDouble(value);
            if (opacity < 0) {
                return 0;
            }
            if (opacity > 1) {
                return 1;
            }
            return opacity;
        } catch (NumberFormatException e) {
            return Double.parseDouble(DEFAULT_OPACITY);
        }
    }
}
