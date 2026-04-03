package com.imagemanager.controller;

import com.imagemanager.model.ImageFile;
import com.imagemanager.service.ImageService;
import com.imagemanager.service.ImageServiceImpl;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.FileUtil;
import com.imagemanager.util.ImageUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主窗口控制器 — 协调目录树导航和图片预览区域。
 * <p>
 * 职责：
 * <ul>
 * <li>初始化并管理目录树 TreeView</li>
 * <li>加载和显示缩略图网格</li>
 * <li>处理选中/多选/框选交互</li>
 * <li>提供右键上下文菜单（删除/复制/粘贴/重命名）</li>
 * <li>管理状态栏信息更新</li>
 * <li>启动幻灯片播放窗口</li>
 * </ul>
 */
public class MainController {

    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    // ==================== FXML 注入的 UI 组件 ====================

    @FXML
    private TreeView<String> directoryTree;
    @FXML
    private FlowPane thumbnailPane;
    @FXML
    private ScrollPane thumbnailScroll;
    @FXML
    private Label pathLabel;
    @FXML
    private Label directoryNameLabel;
    @FXML
    private Label imageCountLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private Label selectionLabel;
    @FXML
    private Button slideshowButton;
    @FXML
    private SplitPane mainSplitPane;

    // ==================== 业务服务 ====================

    private final ImageService imageService = new ImageServiceImpl();

    // ==================== 状态变量 ====================

    /** 当前选中目录的路径 */
    private String currentDirectoryPath;

    /** 当前目录下的所有图片 */
    private List<ImageFile> currentImages = new ArrayList<>();

    /** 当前选中的图片集合 */
    private final Set<ImageFile> selectedImages = new HashSet<>();

    /** 缩略图卡片与图片的映射（用于选中/取消选中的 UI 更新） */
    private final java.util.Map<ImageFile, VBox> cardMap = new java.util.LinkedHashMap<>();

    /** 框选相关状态 */
    private double dragStartX, dragStartY;
    private boolean isDragging = false;

    // ==================== 初始化 ====================

    /**
     * FXML 加载完成后自动调用。
     * 初始化目录树和交互事件。
     */
    @FXML
    public void initialize() {
        logger.info("初始化主界面...");

        // 构建目录树
        initDirectoryTree();

        // 配置缩略图区域的交互
        initThumbnailInteraction();

        // 配置键盘快捷键
        initKeyboardShortcuts();

        logger.info("主界面初始化完成");
    }

    // ==================== 目录树 ====================

    /**
     * 初始化目录树：以"我的电脑"为根，列出所有磁盘分区。
     * 使用懒加载策略——只有用户展开目录时才加载子目录。
     */
    private void initDirectoryTree() {
        TreeItem<String> rootItem = new TreeItem<>("我的电脑");
        rootItem.setExpanded(true);

        // 列出所有磁盘根目录
        File[] roots = File.listRoots();
        if (roots != null) {
            for (var root : roots) {
                String rootPath = root.getAbsolutePath();
                String displayName = "本地磁盘 (" + rootPath.replace("\\", "") + ")";

                TreeItem<String> diskItem = createLazyTreeItem(displayName, rootPath);
                rootItem.getChildren().add(diskItem);
            }
        }

        directoryTree.setRoot(rootItem);
        directoryTree.setShowRoot(true);

        // 监听选中变化
        directoryTree.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && newValue.getValue() != null) {
                        String path = getPathFromTreeItem(newValue);
                        if (path != null) {
                            onDirectorySelected(path);
                        }
                    }
                });
    }

    /**
     * 创建一个懒加载的目录树节点。
     * 初始时只放一个"虚拟子节点"让箭头可见，
     * 用户展开时才真正加载子目录。
     */
    private TreeItem<String> createLazyTreeItem(String displayName, String path) {
        TreeItem<String> item = new TreeItem<>(displayName);

        // 伪子节点 — 让展开箭头可见
        TreeItem<String> placeholder = new TreeItem<>("加载中...");
        item.getChildren().add(placeholder);

        // 监听展开事件 — 替换为真实子目录
        item.expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
            if (isNowExpanded && item.getChildren().size() == 1
                    && "加载中...".equals(item.getChildren().getFirst().getValue())) {
                loadSubDirectories(item, path);
            }
        });

        // 将路径存储在 TreeItem 的用户数据中
        item.setValue(displayName);
        // 使用 graphic 的 userData 存储路径
        Label label = new Label("📁");
        label.setUserData(path);
        item.setGraphic(label);

        return item;
    }

    /**
     * 加载指定目录的子目录，替换虚拟节点。
     */
    private void loadSubDirectories(TreeItem<String> parentItem, String parentPath) {
        List<File> subDirs = FileUtil.listSubDirectories(parentPath);
        parentItem.getChildren().clear();

        for (var subDir : subDirs) {
            String childPath = subDir.getAbsolutePath();
            String childName = subDir.getName();
            TreeItem<String> childItem = createLazyTreeItem(childName, childPath);
            parentItem.getChildren().add(childItem);
        }

        if (parentItem.getChildren().isEmpty()) {
            // 没有子目录的话，不显示空箭头
            parentItem.setExpanded(false);
        }
    }

    /**
     * 从 TreeItem 中提取目录路径。
     */
    private String getPathFromTreeItem(TreeItem<String> item) {
        if (item.getGraphic() instanceof Label label && label.getUserData() instanceof String path) {
            return path;
        }
        return null;
    }

    // ==================== 缩略图加载与显示 ====================

    /**
     * 目录选中事件处理器 — 加载该目录下的所有图片缩略图。
     */
    private void onDirectorySelected(String directoryPath) {
        logger.info("选中目录: {}", directoryPath);
        currentDirectoryPath = directoryPath;
        selectedImages.clear();
        cardMap.clear();

        // 更新路径显示
        pathLabel.setText("📁 " + directoryPath);
        File dir = new File(directoryPath);
        directoryNameLabel.setText(dir.getName().isEmpty() ? directoryPath : dir.getName());

        // 在后台线程加载图片（避免阻塞 UI）
        Task<List<ImageFile>> loadTask = new Task<>() {
            @Override
            protected List<ImageFile> call() {
                return imageService.loadImagesFromDirectory(directoryPath);
            }
        };

        loadTask.setOnSucceeded(event -> {
            currentImages = loadTask.getValue();
            displayThumbnails(currentImages);
            updateStatusBar();
            slideshowButton.setDisable(currentImages.isEmpty());
        });

        loadTask.setOnFailed(event -> {
            logger.error("加载图片失败", loadTask.getException());
            statusLabel.setText("加载失败: " + loadTask.getException().getMessage());
        });

        // 显示加载提示
        statusLabel.setText("正在加载...");
        thumbnailPane.getChildren().clear();

        Thread.startVirtualThread(loadTask);
    }

    /**
     * 在缩略图区域显示图片。
     */
    private void displayThumbnails(List<ImageFile> images) {
        thumbnailPane.getChildren().clear();
        cardMap.clear();

        imageCountLabel.setText(images.size() + " 张图片");

        for (var image : images) {
            VBox card = createThumbnailCard(image);
            cardMap.put(image, card);
            thumbnailPane.getChildren().add(card);
        }
    }

    /**
     * 创建单个缩略图卡片。
     * 每个卡片包含：ImageView（缩略图）+ Label（文件名）
     */
    private VBox createThumbnailCard(ImageFile image) {
        // 缩略图图片
        ImageView imageView = new ImageView();
        imageView.setFitWidth(120);
        imageView.setFitHeight(90);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // 优先从数据库 bytea 加载缩略图
        if (image.thumbnail() != null && image.thumbnail().length > 0) {
            Image thumbImage = ImageUtil.fromBytes(image.thumbnail());
            imageView.setImage(thumbImage);
        } else {
            // 从磁盘加载（后台生成并缓存）
            Image thumbImage = ImageUtil.loadThumbnailImage(
                    image.filePath(),
                    ImageUtil.DEFAULT_THUMBNAIL_WIDTH,
                    ImageUtil.DEFAULT_THUMBNAIL_HEIGHT);
            imageView.setImage(thumbImage);

            // 后台生成并缓存到数据库
            Thread.startVirtualThread(() -> {
                imageService.generateAndCacheThumbnail(
                        image,
                        ImageUtil.DEFAULT_THUMBNAIL_WIDTH,
                        ImageUtil.DEFAULT_THUMBNAIL_HEIGHT);
            });
        }

        // 图片容器（固定大小，居中显示）
        StackPane imageContainer = new StackPane(imageView);
        imageContainer.getStyleClass().add("thumbnail-image-container");
        imageContainer.setPrefSize(130, 100);

        // 文件名标签
        Label nameLabel = new Label(image.fileName());
        nameLabel.getStyleClass().add("thumbnail-name");
        nameLabel.setMaxWidth(130);

        // 卡片容器
        VBox card = new VBox(5, imageContainer, nameLabel);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("thumbnail-card");
        card.setPrefWidth(150);

        // 单击选中
        card.setOnMouseClicked(event -> handleCardClick(image, card, event));

        // 双击进入幻灯片
        // （单击事件中检查 clickCount）

        // 右键菜单
        card.setOnContextMenuRequested(event -> {
            if (selectedImages.contains(image)) {
                showContextMenu(card, event.getScreenX(), event.getScreenY());
            }
        });

        return card;
    }

    // ==================== 选中交互 ====================

    /**
     * 缩略图卡片点击处理。
     */
    private void handleCardClick(ImageFile image, VBox card, MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (event.getClickCount() == 2) {
                // 双击 → 进入幻灯片
                openSlideshow(currentImages.indexOf(image));
                return;
            }

            if (event.isControlDown()) {
                // Ctrl + 单击 → 切换选中状态
                if (selectedImages.contains(image)) {
                    deselectImage(image, card);
                } else {
                    selectImage(image, card);
                }
            } else {
                // 普通单击 → 清除其他选中，只选当前
                clearSelection();
                selectImage(image, card);
            }
            updateStatusBar();
        }
    }

    private void selectImage(ImageFile image, VBox card) {
        selectedImages.add(image);
        card.getStyleClass().add("selected");
    }

    private void deselectImage(ImageFile image, VBox card) {
        selectedImages.remove(image);
        card.getStyleClass().remove("selected");
    }

    private void clearSelection() {
        selectedImages.clear();
        for (var card : cardMap.values()) {
            card.getStyleClass().remove("selected");
        }
        updateStatusBar();
    }

    /**
     * 初始化缩略图区域的交互事件（框选、空白处取消选中）。
     */
    private void initThumbnailInteraction() {
        // 点击空白处取消选中
        thumbnailScroll.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getTarget() == thumbnailScroll) {
                clearSelection();
            }
        });

        thumbnailPane.setOnMouseClicked(event -> {
            // 只有点击 FlowPane 本身（空白区域）才取消选中
            if (event.getButton() == MouseButton.PRIMARY && event.getTarget() == thumbnailPane) {
                clearSelection();
            }
        });

        // 框选功能
        thumbnailPane.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !event.isControlDown()) {
                dragStartX = event.getX();
                dragStartY = event.getY();
                isDragging = false;
            }
        });

        thumbnailPane.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !event.isControlDown()) {
                isDragging = true;
                double currentX = event.getX();
                double currentY = event.getY();

                // 更新框选状态
                if (!event.isControlDown()) {
                    clearSelection();
                }

                // 计算框选矩形
                double minX = Math.min(dragStartX, currentX);
                double maxX = Math.max(dragStartX, currentX);
                double minY = Math.min(dragStartY, currentY);
                double maxY = Math.max(dragStartY, currentY);

                // 遍历所有卡片，判断是否在框选范围内
                for (var entry : cardMap.entrySet()) {
                    VBox card = entry.getValue();
                    Bounds cardBounds = card.getBoundsInParent();

                    if (cardBounds.getMaxX() >= minX && cardBounds.getMinX() <= maxX
                            && cardBounds.getMaxY() >= minY && cardBounds.getMinY() <= maxY) {
                        selectImage(entry.getKey(), card);
                    }
                }
                updateStatusBar();
            }
        });
    }

    /**
     * 初始化键盘快捷键。
     */
    private void initKeyboardShortcuts() {
        // 快捷键在 Scene 设置后才能绑定，这里通过 Platform.runLater 延迟
        Platform.runLater(() -> {
            if (thumbnailPane.getScene() != null) {
                thumbnailPane.getScene().setOnKeyPressed(event -> {
                    if (event.isControlDown()) {
                        switch (event.getCode()) {
                            case C -> onCopy();
                            case V -> onPaste();
                            case A -> selectAll();
                            default -> {
                                /* 其他快捷键暂不处理 */ }
                        }
                    } else if (event.getCode() == KeyCode.DELETE) {
                        onDelete();
                    } else if (event.getCode() == KeyCode.F2) {
                        onRename();
                    }
                });
            }
        });
    }

    // ==================== 右键菜单 ====================

    /**
     * 显示右键上下文菜单。
     */
    private void showContextMenu(Node anchor, double screenX, double screenY) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem deleteItem = new MenuItem("🗑 删除");
        deleteItem.setOnAction(e -> onDelete());

        MenuItem copyItem = new MenuItem("📋 复制");
        copyItem.setOnAction(e -> onCopy());

        MenuItem pasteItem = new MenuItem("📌 粘贴");
        pasteItem.setOnAction(e -> onPaste());
        pasteItem.setDisable(imageService.getClipboard().isEmpty());

        MenuItem renameItem = new MenuItem("✏ 重命名");
        renameItem.setOnAction(e -> onRename());

        contextMenu.getItems().addAll(
                deleteItem,
                copyItem,
                pasteItem,
                new SeparatorMenuItem(),
                renameItem);

        contextMenu.show(anchor, screenX, screenY);
    }

    // ==================== 操作处理 ====================

    /**
     * 删除选中的图片。
     */
    private void onDelete() {
        if (selectedImages.isEmpty())
            return;

        int count = selectedImages.size();
        boolean confirmed = AlertUtil.showConfirmation(
                "确认删除",
                "确定要删除选中的 " + count + " 张图片吗？此操作不可恢复。");

        if (confirmed) {
            try {
                imageService.deleteImages(new ArrayList<>(selectedImages));
                statusLabel.setText("已删除 " + count + " 张图片");
                // 刷新当前目录
                onDirectorySelected(currentDirectoryPath);
            } catch (Exception e) {
                AlertUtil.showError("删除失败", e.getMessage());
            }
        }
    }

    /**
     * 复制选中的图片到剪贴板。
     */
    private void onCopy() {
        if (selectedImages.isEmpty())
            return;

        imageService.copyImages(new ArrayList<>(selectedImages));
        statusLabel.setText("已复制 " + selectedImages.size() + " 张图片");
    }

    /**
     * 粘贴剪贴板中的图片到当前目录。
     */
    private void onPaste() {
        if (currentDirectoryPath == null) {
            AlertUtil.showWarning("粘贴失败", "请先选择一个目标目录");
            return;
        }
        if (imageService.getClipboard().isEmpty()) {
            AlertUtil.showWarning("粘贴失败", "剪贴板为空，请先复制图片");
            return;
        }

        try {
            int count = imageService.getClipboard().size();
            imageService.pasteImages(currentDirectoryPath);
            statusLabel.setText("已粘贴 " + count + " 张图片");
            // 刷新
            onDirectorySelected(currentDirectoryPath);
        } catch (Exception e) {
            AlertUtil.showError("粘贴失败", e.getMessage());
        }
    }

    /**
     * 重命名选中的图片。
     * 单选 → 直接输入新名称
     * 多选 → 打开批量重命名对话框
     */
    private void onRename() {
        if (selectedImages.isEmpty())
            return;

        if (selectedImages.size() == 1) {
            // 单张重命名
            ImageFile image = selectedImages.iterator().next();
            AlertUtil.showTextInput("重命名", "请输入新文件名：", image.baseName())
                    .ifPresent(newName -> {
                        try {
                            imageService.renameImage(image, newName);
                            statusLabel.setText("已重命名: " + newName + image.extension());
                            onDirectorySelected(currentDirectoryPath);
                        } catch (Exception e) {
                            AlertUtil.showError("重命名失败", e.getMessage());
                        }
                    });
        } else {
            // 多张 → 批量重命名对话框
            openBatchRenameDialog();
        }
    }

    /**
     * 打开批量重命名对话框。
     */
    private void openBatchRenameDialog() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/RenameDialog.fxml"));
            Parent dialogRoot = loader.load();

            RenameDialogController controller = loader.getController();
            List<ImageFile> imagesToRename = new ArrayList<>(selectedImages);
            controller.initData(imagesToRename);

            Stage dialogStage = new Stage();
            dialogStage.setTitle("批量重命名");
            Scene scene = new Scene(dialogRoot);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            dialogStage.setScene(scene);
            dialogStage.initOwner(thumbnailPane.getScene().getWindow());
            dialogStage.setResizable(false);

            // 设置确认回调
            controller.setOnConfirm((prefix, startNumber, digitCount) -> {
                try {
                    imageService.batchRename(imagesToRename, prefix, startNumber, digitCount);
                    statusLabel.setText("已批量重命名 " + imagesToRename.size() + " 张图片");
                    dialogStage.close();
                    onDirectorySelected(currentDirectoryPath);
                } catch (Exception e) {
                    AlertUtil.showError("批量重命名失败", e.getMessage());
                }
            });

            dialogStage.showAndWait();

        } catch (Exception e) {
            logger.error("打开批量重命名对话框失败", e);
            AlertUtil.showError("错误", "无法打开重命名对话框: " + e.getMessage());
        }
    }

    /**
     * 全选当前目录下的所有图片。
     */
    private void selectAll() {
        for (var entry : cardMap.entrySet()) {
            selectImage(entry.getKey(), entry.getValue());
        }
        updateStatusBar();
    }

    // ==================== 幻灯片播放 ====================

    /**
     * 点击播放按钮或双击缩略图时，打开幻灯片播放窗口。
     */
    @FXML
    private void onSlideshow() {
        if (currentImages.isEmpty())
            return;

        int startIndex = 0;
        if (!selectedImages.isEmpty()) {
            ImageFile first = selectedImages.iterator().next();
            startIndex = currentImages.indexOf(first);
            if (startIndex < 0)
                startIndex = 0;
        }
        openSlideshow(startIndex);
    }

    private void openSlideshow(int startIndex) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/SlideshowView.fxml"));
            Parent slideshowRoot = loader.load();

            SlideshowController controller = loader.getController();
            controller.initSlideshow(currentImages, startIndex);

            Stage slideshowStage = new Stage();
            slideshowStage.setTitle("幻灯片播放 - 数字图像管理系统");
            Scene scene = new Scene(slideshowRoot, 1000, 700);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            slideshowStage.setScene(scene);
            slideshowStage.show();

        } catch (Exception e) {
            logger.error("打开幻灯片播放失败", e);
            AlertUtil.showError("错误", "无法打开播放窗口: " + e.getMessage());
        }
    }

    // ==================== 状态栏 ====================

    /**
     * 更新底部状态栏信息。
     */
    private void updateStatusBar() {
        // 总图片信息
        long totalSize = currentImages.stream().mapToLong(ImageFile::fileSize).sum();
        statusLabel.setText(currentImages.size() + "张图片(" + FileUtil.formatFileSize(totalSize) + ")");

        // 选中信息
        if (selectedImages.isEmpty()) {
            selectionLabel.setText("");
        } else {
            long selectedSize = selectedImages.stream().mapToLong(ImageFile::fileSize).sum();
            selectionLabel.setText(
                    "选中 " + selectedImages.size() + " 张, " + FileUtil.formatFileSize(selectedSize));
        }
    }
}
