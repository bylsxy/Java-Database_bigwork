package com.imagemanager;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class UiSnapshotSmoke {
    private record Case(String fxml, int width, int height) {
    }

    private record LoadedFxml(Parent root, Map<String, Object> namespace) {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                runSmoke();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
                Platform.exit();
            }
        });

        done.await();
        if (failure.get() != null) {
            throw new RuntimeException(failure.get());
        }
    }

    private static void runSmoke() throws Exception {
        Path out = Path.of("target", "ui-smoke");
        Files.createDirectories(out);

        List<Case> cases = List.of(
                new Case("MainView.fxml", 900, 600),
                new Case("MainView.fxml", 1200, 800),
                new Case("MainView.fxml", 1440, 900),
                new Case("SettingsView.fxml", 680, 720),
                new Case("SettingsView.fxml", 850, 900),
                new Case("WelcomeDialog.fxml", 640, 620),
                new Case("WelcomeDialog.fxml", 800, 775),
                new Case("ImageViewerView.fxml", 960, 680),
                new Case("ImageViewerView.fxml", 1200, 850),
                new Case("SlideshowView.fxml", 1000, 700),
                new Case("SlideshowView.fxml", 1250, 875),
                new Case("ImageEditorView.fxml", 1000, 750),
                new Case("ImageEditorView.fxml", 1250, 938),
                new Case("RenameDialog.fxml", 450, 360),
                new Case("RenameDialog.fxml", 563, 450)
        );

        for (Case c : cases) {
            LoadedFxml loaded = loadStaticFxml(Path.of("src", "main", "resources", "fxml", c.fxml()));
            Parent root = loaded.root();
            Scene scene = new Scene(root, c.width(), c.height());
            scene.getStylesheets().add(Path.of("src", "main", "resources", "css", "style.css").toUri().toString());
            decorateStaticScene(loaded.namespace(), c.fxml(), c.width(), c.height());

            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.setWidth(c.width());
            stage.setHeight(c.height());
            stage.show();

            if (root instanceof Region region) {
                region.setMinSize(c.width(), c.height());
                region.setPrefSize(c.width(), c.height());
                region.setMaxSize(c.width(), c.height());
                region.resize(c.width(), c.height());
            }
            root.applyCss();
            root.layout();

            WritableImage image = new WritableImage(c.width(), c.height());
            scene.snapshot(image);
            String fileName = c.fxml().replace(".fxml", "") + "-" + c.width() + "x" + c.height() + ".png";
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out.resolve(fileName).toFile());
            stage.hide();
        }
    }

    private static LoadedFxml loadStaticFxml(Path fxmlPath) throws Exception {
        String xml = Files.readString(fxmlPath, StandardCharsets.UTF_8)
                .replaceAll("\\sfx:controller=\"[^\"]+\"", "")
                .replaceAll("\\son[A-Z][A-Za-z0-9]*=\"[^\"]+\"", "");
        FXMLLoader loader = new FXMLLoader();
        Parent root = loader.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new LoadedFxml(root, loader.getNamespace());
    }

    private static void decorateStaticScene(Map<String, Object> namespace, String fxml, int width, int height) {
        switch (fxml) {
            case "MainView.fxml" -> decorateMain(namespace);
            case "ImageViewerView.fxml" -> decorateImageViewer(namespace, width, height);
            case "SlideshowView.fxml" -> decorateSlideshow(namespace, width, height);
            case "ImageEditorView.fxml" -> decorateEditor(namespace, width, height);
            default -> {
                // Dialog and settings FXML already contain meaningful placeholder text.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T fx(Map<String, Object> namespace, String id, Class<T> type) {
        Object value = namespace.get(id);
        return type.isInstance(value) ? (T) value : null;
    }

    private static void decorateMain(Map<String, Object> namespace) {
        Label pathLabel = fx(namespace, "pathLabel", Label.class);
        Label directoryNameLabel = fx(namespace, "directoryNameLabel", Label.class);
        Label imageCountLabel = fx(namespace, "imageCountLabel", Label.class);
        Label statusLabel = fx(namespace, "statusLabel", Label.class);
        Label selectionLabel = fx(namespace, "selectionLabel", Label.class);
        if (pathLabel != null) {
            pathLabel.setText("D:\\Pictures\\课程设计样例");
        }
        if (directoryNameLabel != null) {
            directoryNameLabel.setText("课程设计样例");
        }
        if (imageCountLabel != null) {
            imageCountLabel.setText("12 张图片");
        }
        if (statusLabel != null) {
            statusLabel.setText("就绪：目录扫描完成，缩略图缓存已同步。");
        }
        if (selectionLabel != null) {
            selectionLabel.setText("已选择 2 张");
        }

        TreeView<String> tree = fx(namespace, "directoryTree", TreeView.class);
        if (tree != null) {
            TreeItem<String> root = new TreeItem<>("我的电脑");
            TreeItem<String> driveC = new TreeItem<>("本地磁盘 (C:)");
            TreeItem<String> driveD = new TreeItem<>("本地磁盘 (D:)");
            TreeItem<String> pictures = new TreeItem<>("Pictures");
            TreeItem<String> sample = new TreeItem<>("课程设计样例");
            driveC.getChildren().addAll(new TreeItem<>("Users"), pictures);
            pictures.getChildren().add(sample);
            driveD.getChildren().addAll(new TreeItem<>("AAAWorkSpace"), new TreeItem<>("素材归档"));
            root.getChildren().addAll(driveC, driveD);
            root.setExpanded(true);
            driveC.setExpanded(true);
            pictures.setExpanded(true);
            tree.setRoot(root);
            tree.getSelectionModel().select(sample);
        }

        FlowPane thumbnailPane = fx(namespace, "thumbnailPane", FlowPane.class);
        if (thumbnailPane != null) {
            thumbnailPane.getChildren().clear();
            for (int i = 1; i <= 12; i++) {
                thumbnailPane.getChildren().add(createThumbnailCard(i, i == 2 || i == 5));
            }
        }
    }

    private static void decorateImageViewer(Map<String, Object> namespace, int width, int height) {
        setMainImage(namespace, width, height, 1);
        Label infoLabel = fx(namespace, "infoLabel", Label.class);
        if (infoLabel != null) {
            infoLabel.setText("IMG_20260523_001.jpg    1920 x 1080    缩放 78%");
        }
    }

    private static void decorateSlideshow(Map<String, Object> namespace, int width, int height) {
        setMainImage(namespace, width, height, 2);
        Label infoLabel = fx(namespace, "infoLabel", Label.class);
        if (infoLabel != null) {
            infoLabel.setText("第 3 / 12 张    IMG_20260523_003.jpg    自动播放已暂停");
        }
        HBox thumbnailStrip = fx(namespace, "thumbnailStrip", HBox.class);
        if (thumbnailStrip != null) {
            thumbnailStrip.getChildren().clear();
            for (int i = 1; i <= 8; i++) {
                ImageView imageView = new ImageView(createSampleImage(120, 80, i));
                imageView.setFitWidth(86);
                imageView.setFitHeight(54);
                imageView.setPreserveRatio(true);
                imageView.getStyleClass().add("strip-thumbnail");
                if (i == 3) {
                    imageView.getStyleClass().add("active");
                }
                thumbnailStrip.getChildren().add(imageView);
            }
        }
        ScrollPane stripScroll = fx(namespace, "thumbnailStripScroll", ScrollPane.class);
        if (stripScroll != null) {
            stripScroll.setHvalue(0.25);
        }
    }

    private static void decorateEditor(Map<String, Object> namespace, int width, int height) {
        ImageView imageView = fx(namespace, "editorImageView", ImageView.class);
        if (imageView != null) {
            imageView.setImage(createSampleImage(960, 540, 4));
            imageView.setFitWidth(Math.min(width * 0.72, 860));
            imageView.setFitHeight(Math.min(height * 0.68, 560));
            imageView.setPreserveRatio(true);
        }
        Canvas canvas = fx(namespace, "drawCanvas", Canvas.class);
        if (canvas != null) {
            canvas.setWidth(Math.min(width * 0.72, 860));
            canvas.setHeight(Math.min(height * 0.68, 560));
            var gc = canvas.getGraphicsContext2D();
            gc.setStroke(Color.web("#EF4444"));
            gc.setLineWidth(4);
            gc.strokeRect(155, 95, 330, 210);
            gc.strokeLine(155, 95, 485, 305);
            gc.setFill(Color.web("#EF4444"));
            gc.fillText("课程展示重点区域", 185, 135);
        }
        Label status = fx(namespace, "toolStatusLabel", Label.class);
        if (status != null) {
            status.setText("当前工具: 矩形标注");
        }
        HBox versionTimeline = fx(namespace, "versionTimeline", HBox.class);
        if (versionTimeline != null) {
            versionTimeline.getChildren().clear();
            versionTimeline.getChildren().add(createVersionCard("v1", "原图", false));
            versionTimeline.getChildren().add(createVersionCard("v2", "裁切", false));
            versionTimeline.getChildren().add(createVersionCard("v3", "标注", true));
        }
    }

    private static void setMainImage(Map<String, Object> namespace, int width, int height, int seed) {
        ImageView imageView = fx(namespace, "mainImageView", ImageView.class);
        if (imageView != null) {
            imageView.setImage(createSampleImage(1200, 760, seed));
            imageView.setFitWidth(Math.min(width * 0.72, 920));
            imageView.setFitHeight(Math.min(height * 0.62, 540));
            imageView.setPreserveRatio(true);
        }
    }

    private static VBox createThumbnailCard(int index, boolean selected) {
        VBox card = new VBox(8);
        card.setAlignment(Pos.CENTER);
        card.setPrefSize(170, 188);
        card.getStyleClass().add("thumbnail-card");
        if (selected) {
            card.getStyleClass().add("selected");
        }

        StackPane imageBox = new StackPane();
        imageBox.getStyleClass().add("thumbnail-image-container");
        ImageView imageView = new ImageView(createSampleImage(240, 160, index));
        imageView.setFitWidth(136);
        imageView.setFitHeight(100);
        imageView.setPreserveRatio(true);
        imageBox.getChildren().add(imageView);

        Label name = new Label("IMG_2026_%04d.jpg".formatted(index));
        name.getStyleClass().add("thumbnail-name");
        Label meta = new Label(index % 3 == 0 ? "1080x720 JPG" : "1920x1080 JPG");
        meta.getStyleClass().add("thumbnail-meta");

        card.getChildren().addAll(imageBox, name, meta);
        return card;
    }

    private static VBox createVersionCard(String number, String type, boolean current) {
        VBox card = new VBox(2);
        card.setAlignment(Pos.CENTER);
        card.setPrefSize(82, 42);
        card.getStyleClass().add("version-card");
        if (current) {
            card.getStyleClass().add("version-card-current");
        }
        Label numberLabel = new Label(number);
        numberLabel.getStyleClass().add("version-number");
        Label typeLabel = new Label(type);
        typeLabel.getStyleClass().add("version-type");
        card.getChildren().addAll(numberLabel, typeLabel);
        return card;
    }

    private static Image createSampleImage(int width, int height, int seed) {
        WritableImage image = new WritableImage(width, height);
        PixelWriter writer = image.getPixelWriter();
        double hueBase = (seed * 37) % 360;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double hue = (hueBase + x * 0.035 + y * 0.018) % 360;
                double saturation = 0.42 + (seed % 4) * 0.08;
                double brightness = 0.72 + (double) y / height * 0.18;
                writer.setColor(x, y, Color.hsb(hue, saturation, brightness));
            }
        }
        return image;
    }
}
