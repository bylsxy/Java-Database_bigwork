package com.imagemanager.util;

import com.imagemanager.dao.SettingsDao;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.io.File;

/**
 * 主题背景工具类。
 */
public final class ThemeUtil {

    public static final String THEME_BACKGROUND_PATH = "theme_background_path";
    public static final String THEME_BACKGROUND_OPACITY = "theme_background_opacity";
    public static final String DEFAULT_OPACITY = "1.0";
    private static final String BACKGROUND_ENABLED_STYLE_CLASS = "theme-background-enabled";

    private ThemeUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    public static void applyThemeBackground(StackPane root, ImageView backgroundImageView, SettingsDao settingsDao) {
        if (root == null || backgroundImageView == null || settingsDao == null) {
            return;
        }

        String imagePath = settingsDao.getValueOrDefault(THEME_BACKGROUND_PATH, "");
        applyThemeBackground(root, backgroundImageView, imagePath);
    }

    public static void applyThemeBackground(
            StackPane root,
            ImageView backgroundImageView,
            String imagePath
    ) {
        applyThemeBackground(root, backgroundImageView, imagePath, parseOpacity(DEFAULT_OPACITY));
    }

    public static void applyThemeBackground(
            StackPane root,
            ImageView backgroundImageView,
            String imagePath,
            double opacity
    ) {
        if (root == null || backgroundImageView == null) {
            return;
        }

        backgroundImageView.fitWidthProperty().unbind();
        backgroundImageView.fitHeightProperty().unbind();
        backgroundImageView.translateXProperty().unbind();
        backgroundImageView.translateYProperty().unbind();
        backgroundImageView.setX(0);
        backgroundImageView.setY(0);
        backgroundImageView.setPreserveRatio(true);
        backgroundImageView.setMouseTransparent(true);
        backgroundImageView.setManaged(false);
        installRootClip(root);

        if (imagePath == null || imagePath.isBlank()) {
            backgroundImageView.setImage(null);
            backgroundImageView.setUserData(null);
            backgroundImageView.setVisible(false);
            setBackgroundEnabled(root, false);
            return;
        }

        File imageFile = new File(imagePath);
        if (!imageFile.exists() || !imageFile.isFile()) {
            backgroundImageView.setImage(null);
            backgroundImageView.setUserData(null);
            backgroundImageView.setVisible(false);
            setBackgroundEnabled(root, false);
            return;
        }

        String imageUri = imageFile.toURI().toString();
        if (!imageUri.equals(backgroundImageView.getUserData())) {
            backgroundImageView.setImage(new Image(imageUri));
            backgroundImageView.setUserData(imageUri);
        }
        bindCoverSize(root, backgroundImageView);
        backgroundImageView.setOpacity(Math.max(0, Math.min(1, opacity)));
        backgroundImageView.setVisible(true);
        setBackgroundEnabled(root, true);
    }

    private static void setBackgroundEnabled(StackPane root, boolean enabled) {
        boolean contains = root.getStyleClass().contains(BACKGROUND_ENABLED_STYLE_CLASS);
        if (enabled && !contains) {
            root.getStyleClass().add(BACKGROUND_ENABLED_STYLE_CLASS);
        } else if (!enabled && contains) {
            root.getStyleClass().remove(BACKGROUND_ENABLED_STYLE_CLASS);
        }
    }

    private static void bindCoverSize(StackPane root, ImageView backgroundImageView) {
        Image image = backgroundImageView.getImage();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            backgroundImageView.fitWidthProperty().bind(root.widthProperty());
            backgroundImageView.fitHeightProperty().bind(root.heightProperty());
            return;
        }

        double imageAspect = image.getWidth() / image.getHeight();
        DoubleBinding coverWidth = Bindings.createDoubleBinding(() -> {
            double width = Math.max(1, root.getWidth());
            double height = Math.max(1, root.getHeight());
            return width / height > imageAspect ? width : height * imageAspect;
        }, root.widthProperty(), root.heightProperty());
        DoubleBinding coverHeight = Bindings.createDoubleBinding(() -> {
            double width = Math.max(1, root.getWidth());
            double height = Math.max(1, root.getHeight());
            return width / height > imageAspect ? width / imageAspect : height;
        }, root.widthProperty(), root.heightProperty());

        backgroundImageView.fitWidthProperty().bind(coverWidth);
        backgroundImageView.fitHeightProperty().bind(coverHeight);
        backgroundImageView.translateXProperty().bind(Bindings.createDoubleBinding(
                () -> (root.getWidth() - backgroundImageView.getFitWidth()) / 2,
                root.widthProperty(),
                backgroundImageView.fitWidthProperty()));
        backgroundImageView.translateYProperty().bind(Bindings.createDoubleBinding(
                () -> (root.getHeight() - backgroundImageView.getFitHeight()) / 2,
                root.heightProperty(),
                backgroundImageView.fitHeightProperty()));
    }

    private static void installRootClip(StackPane root) {
        if (root.getClip() instanceof Rectangle) {
            return;
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        root.setClip(clip);
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
