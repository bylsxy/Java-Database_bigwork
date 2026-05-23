package com.imagemanager.controller;

import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import com.imagemanager.dao.TagDao;
import com.imagemanager.dao.TagDaoImpl;
import com.imagemanager.model.ImageFile;
import com.imagemanager.model.Tag;
import com.imagemanager.model.TagCategory;
import com.imagemanager.scanner.ScanTask;
import com.imagemanager.service.AiTagStorageService;
import com.imagemanager.service.ImageService;
import com.imagemanager.service.ImageServiceImpl;
import com.imagemanager.service.SearchService;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.FileUtil;
import com.imagemanager.util.ImageUtil;
import com.imagemanager.util.ThemeUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String LOADING_TREE_ITEM_TEXT = "加载中...";

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
    @FXML
    private StackPane appRoot;
    @FXML
    private ImageView themeBackgroundImageView;
    @FXML
    private BorderPane mainContentPane;

    // v2.0 新增：搜索栏
    @FXML
    private ComboBox<String> searchModeCombo;
    @FXML
    private TextField searchField;
    @FXML
    private Button searchButton;

    // v2.0 新增：AI扫描进度
    @FXML
    private Label scanProgressLabel;
    @FXML
    private ProgressBar scanProgressBar;
    @FXML
    private Button stopScanButton;
    @FXML
    private Button cleanupAiButton;

    // ==================== 业务服务 ====================

    private final ImageService imageService = new ImageServiceImpl();
    private final SearchService searchService = new SearchService();
    private final AiTagStorageService aiTagStorageService = new AiTagStorageService();
    private final SettingsDao settingsDao = new SettingsDaoImpl();
    private final TagDao tagDao = new TagDaoImpl();

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
    private ScanTask activeScanTask;
    private String activeScanDirectoryPath = "";
    private String queuedScanDirectoryPath = "";
    private long directoryLoadRequestId = 0;
    private long searchRequestId = 0;
    private boolean promptCleanupAfterStop = false;

    private record TagViewItem(Tag tag, String categoryLabel) {
        @Override
        public String toString() {
            return categoryLabel + " / " + tag.name();
        }
    }

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

        // v2.0: 初始化搜索栏
        initSearchBar();
        setImageCountText("");

        // 应用全局主题背景
        applyTheme();

        logger.info("主界面初始化完成");
    }

    private void applyTheme() {
        ThemeUtil.applyThemeBackground(appRoot, themeBackgroundImageView, settingsDao);
        ThemeUtil.markThemedSurface(mainContentPane);
    }

    /**
     * 初始化搜索栏 — 下拉选择搜索模式，回车触发搜索。
     */
    private void initSearchBar() {
        if (searchModeCombo != null) {
            searchModeCombo.setItems(FXCollections.observableArrayList(
                    "关键词", "AI智能"
            ));
            searchModeCombo.getSelectionModel().selectFirst();
        }

        if (searchField != null) {
            searchField.setOnAction(event -> onSearch());
        }
    }

    // ==================== 目录树 ====================

    /**
     * 初始化目录树。若已配置扫描目录，则以扫描目录作为图片库根节点。
     */
    private void initDirectoryTree() {
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

        String scanDirectory = settingsDao.getValueOrDefault("scan_directory", "");
        if (!scanDirectory.isBlank() && new File(scanDirectory).isDirectory()) {
            showScanDirectoryRoot(scanDirectory, true);
        } else {
            showComputerRoot();
        }
    }

    /**
     * 兜底显示整台电脑的磁盘根目录。
     */
    private void showComputerRoot() {
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
    }

    /**
     * 切换图片库根目录，让左侧树和右侧缩略图都跟随当前扫描目录。
     */
    private void showScanDirectoryRoot(String directoryPath, boolean selectRoot) {
        File rootDir = new File(directoryPath);
        if (!rootDir.isDirectory()) {
            return;
        }

        String rootPath = normalizeDirectoryPath(rootDir);
        String displayName = rootDir.getName().isBlank() ? rootPath : rootDir.getName();
        TreeItem<String> rootItem = createLazyTreeItem(displayName, rootPath);
        directoryTree.setRoot(rootItem);
        directoryTree.setShowRoot(true);
        rootItem.setExpanded(true);

        if (selectRoot) {
            directoryTree.getSelectionModel().select(rootItem);
        }
    }

    /**
     * 创建一个懒加载的目录树节点。
     * 只有存在可见子目录时才放一个虚拟子节点让箭头可见，
     * 用户展开时再真正加载子目录。
     */
    private TreeItem<String> createLazyTreeItem(String displayName, String path) {
        TreeItem<String> item = new TreeItem<>(displayName);

        if (hasVisibleSubDirectory(path)) {
            TreeItem<String> placeholder = new TreeItem<>(LOADING_TREE_ITEM_TEXT);
            item.getChildren().add(placeholder);
        }

        // 监听展开事件 — 替换为真实子目录
        item.expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
            if (isNowExpanded && item.getChildren().size() == 1
                    && LOADING_TREE_ITEM_TEXT.equals(item.getChildren().getFirst().getValue())) {
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

    private boolean hasVisibleSubDirectory(String directoryPath) {
        File dir = new File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }

        File[] subDirs = dir.listFiles(file -> file.isDirectory() && !file.isHidden());
        return subDirs != null && subDirs.length > 0;
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
        long loadRequestId = ++directoryLoadRequestId;
        searchRequestId++;
        currentDirectoryPath = directoryPath;
        selectedImages.clear();
        cardMap.clear();

        // 更新路径显示
        pathLabel.setText(directoryPath);
        File dir = new File(directoryPath);
        directoryNameLabel.setText(dir.getName().isEmpty() ? directoryPath : dir.getName());
        setImageCountText("");

        // 在后台线程加载图片（避免阻塞 UI）
        Task<List<ImageFile>> loadTask = new Task<>() {
            @Override
            protected List<ImageFile> call() {
                return imageService.loadImagesFromDirectory(directoryPath);
            }
        };

        loadTask.setOnSucceeded(event -> {
            if (loadRequestId != directoryLoadRequestId || !directoryPath.equals(currentDirectoryPath)) {
                return;
            }
            currentImages = loadTask.getValue();
            displayThumbnails(currentImages);
            updateStatusBar();
            slideshowButton.setDisable(currentImages.isEmpty());
        });

        loadTask.setOnFailed(event -> {
            if (loadRequestId != directoryLoadRequestId || !directoryPath.equals(currentDirectoryPath)) {
                return;
            }
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

        setImageCountText(images.size() + " 张图片");

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
        imageView.setFitWidth(132);
        imageView.setFitHeight(98);
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
        imageContainer.setPrefSize(148, 112);
        if (image.aiProcessed()) {
            Label indexedBadge = createAiIndexedBadge();
            imageContainer.getChildren().add(indexedBadge);
        }

        // 文件名标签
        Label nameLabel = new Label(image.fileName());
        nameLabel.getStyleClass().add("thumbnail-name");
        nameLabel.setMaxWidth(148);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setTooltip(new Tooltip(image.fileName()));

        String formatText = image.format() == null ? "" : image.format().toUpperCase();
        Label metaLabel = new Label(image.resolution() + "  " + formatText);
        metaLabel.getStyleClass().add("thumbnail-meta");
        metaLabel.setMaxWidth(148);
        metaLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        // 卡片容器
        VBox card = new VBox(6, imageContainer, nameLabel, metaLabel);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("thumbnail-card");
        card.setPrefWidth(172);
        card.setMinWidth(172);

        // 单击选中
        card.setOnMouseClicked(event -> handleCardClick(image, card, event));

        // 双击进入图片查看器
        // （单击事件中检查 clickCount）

        // 右键菜单
        card.setOnContextMenuRequested(event -> {
            if (!selectedImages.contains(image)) {
                clearSelection();
                selectImage(image, card);
                updateStatusBar();
            }
            showContextMenu(card, event.getScreenX(), event.getScreenY());
        });

        return card;
    }

    private Label createAiIndexedBadge() {
        Label badge = new Label("✓");
        badge.getStyleClass().add("ai-indexed-badge");
        badge.setTooltip(new Tooltip("已完成AI索引，可通过标签或AI搜索"));
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(6));
        return badge;
    }

    // ==================== 选中交互 ====================

    /**
     * 缩略图卡片点击处理。
     */
    private void handleCardClick(ImageFile image, VBox card, MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (event.getClickCount() == 2) {
                // 双击 → 进入单张图片查看器
                openImageViewer(currentImages.indexOf(image));
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

        MenuItem viewItem = new MenuItem("查看图片");
        viewItem.setOnAction(e -> onViewImage());
        viewItem.setDisable(selectedImages.size() != 1);

        MenuItem editItem = new MenuItem("编辑图片");
        editItem.setOnAction(e -> onEditImage());
        editItem.setDisable(selectedImages.size() != 1);

        MenuItem playFromHereItem = new MenuItem("从此处播放幻灯片");
        playFromHereItem.setOnAction(e -> onPlayFromSelected());
        playFromHereItem.setDisable(selectedImages.size() != 1);

        MenuItem tagItem = new MenuItem("管理标签");
        tagItem.setOnAction(e -> onManageTags());
        tagItem.setDisable(selectedImages.size() != 1);

        MenuItem infoItem = new MenuItem("查看图片信息");
        infoItem.setOnAction(e -> onShowImageInfo());
        infoItem.setDisable(selectedImages.size() != 1);

        MenuItem openFolderItem = new MenuItem("打开所在文件夹");
        openFolderItem.setOnAction(e -> onOpenContainingFolder());
        openFolderItem.setDisable(selectedImages.size() != 1);

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> onDelete());

        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> onCopy());

        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> onPaste());
        pasteItem.setDisable(imageService.getClipboard().isEmpty());

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> onRename());

        contextMenu.getItems().addAll(
                viewItem,
                editItem,
                playFromHereItem,
                tagItem,
                infoItem,
                openFolderItem,
                new SeparatorMenuItem(),
                deleteItem,
                copyItem,
                pasteItem,
                new SeparatorMenuItem(),
                renameItem);

        contextMenu.show(anchor, screenX, screenY);
    }
    // ==================== 操作处理 ====================

    /**
     * 打开单张图片查看器。查看器只负责浏览，不包含自动播放和背景音乐。
     */
    private void onViewImage() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法查看", "请只选择一张图片");
            return;
        }

        ImageFile image = selectedImages.iterator().next();
        int startIndex = currentImages.indexOf(image);
        openImageViewer(startIndex < 0 ? 0 : startIndex);
    }

    private void openImageViewer(int startIndex) {
        if (currentImages.isEmpty()) {
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/ImageViewerView.fxml"));
            Parent viewerRoot = loader.load();

            ImageViewerController controller = loader.getController();
            controller.initViewer(currentImages, startIndex);
            controller.setEditAction(this::openImageEditor);
            controller.setPlayAction(this::openSlideshowFromImage);

            ImageFile image = currentImages.get(Math.max(0, Math.min(startIndex, currentImages.size() - 1)));
            Stage viewerStage = new Stage();
            viewerStage.setTitle("图片查看 - " + image.fileName());
            Window ownerWindow = thumbnailPane.getScene().getWindow();
            Rectangle2D screenBounds = ownerVisualBounds(ownerWindow);
            double sceneWidth = boundedSceneSize(1040, 760, screenBounds.getWidth(), 120);
            double sceneHeight = boundedSceneSize(760, 560, screenBounds.getHeight(), 120);
            Scene scene = new Scene(viewerRoot, sceneWidth, sceneHeight);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            viewerStage.setScene(scene);
            viewerStage.initOwner(ownerWindow);
            viewerStage.setResizable(true);
            viewerStage.setMinWidth(Math.min(720, sceneWidth));
            viewerStage.setMinHeight(Math.min(520, sceneHeight));
            viewerStage.setMaxWidth(screenBounds.getWidth());
            viewerStage.setMaxHeight(screenBounds.getHeight());
            viewerStage.setOnShown(event -> Platform.runLater(() -> {
                viewerStage.sizeToScene();
                keepStageInsideScreen(viewerStage, screenBounds);
                controller.refreshLayoutAfterShow();
            }));
            viewerStage.show();
        } catch (Exception e) {
            logger.error("打开图片查看器失败", e);
            AlertUtil.showError("打开查看器失败", e.getMessage());
        }
    }

    private void onPlayFromSelected() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法播放", "请只选择一张图片作为起点");
            return;
        }
        openSlideshowFromImage(selectedImages.iterator().next());
    }

    private void openSlideshowFromImage(ImageFile image) {
        int startIndex = currentImages.indexOf(image);
        openSlideshow(startIndex < 0 ? 0 : startIndex);
    }

    private void onShowImageInfo() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法查看信息", "请只选择一张图片");
            return;
        }

        ImageFile image = selectedImages.iterator().next();
        String modifiedAt = image.modifiedAt() == null ? "未知" : image.modifiedAt().toString();
        String createdAt = image.createdAt() == null ? "未知" : image.createdAt().toString();
        String content = """
                文件名: %s
                路径: %s
                格式: %s
                分辨率: %s
                大小: %s
                创建时间: %s
                修改时间: %s
                """.formatted(
                image.fileName(),
                image.filePath(),
                image.format(),
                image.resolution(),
                image.formattedSize(),
                createdAt,
                modifiedAt
        );

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("图片信息");
        alert.setHeaderText(image.fileName());
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void onOpenContainingFolder() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法打开文件夹", "请只选择一张图片");
            return;
        }

        File parentDir = new File(selectedImages.iterator().next().filePath()).getParentFile();
        if (parentDir == null || !parentDir.exists()) {
            AlertUtil.showWarning("打开失败", "图片所在文件夹不存在");
            return;
        }

        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                AlertUtil.showWarning("打开失败", "当前环境不支持打开文件夹");
                return;
            }
            java.awt.Desktop.getDesktop().open(parentDir);
        } catch (Exception e) {
            logger.error("打开图片所在文件夹失败", e);
            AlertUtil.showError("打开失败", e.getMessage());
        }
    }

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
     * 打开图片编辑器。当前编辑器只处理单张图片，编辑完成后刷新主界面。
     */
    private void onEditImage() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法编辑", "请只选择一张图片进行编辑");
            return;
        }

        openImageEditor(selectedImages.iterator().next());
    }

    private void openImageEditor(ImageFile image) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/ImageEditorView.fxml"));
            Parent editorRoot = loader.load();

            ImageEditorController controller = loader.getController();
            controller.initEditor(image);

            Stage editorStage = new Stage();
            editorStage.setTitle("图片编辑 - " + image.fileName());
            Window ownerWindow = thumbnailPane.getScene().getWindow();
            Rectangle2D screenBounds = ownerVisualBounds(ownerWindow);
            double sceneWidth = boundedSceneSize(1000, 760, screenBounds.getWidth(), 120);
            double sceneHeight = boundedSceneSize(750, 560, screenBounds.getHeight(), 120);
            Scene scene = new Scene(editorRoot, sceneWidth, sceneHeight);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            editorStage.setScene(scene);
            editorStage.initOwner(ownerWindow);
            editorStage.setResizable(true);
            editorStage.setMinWidth(Math.min(720, sceneWidth));
            editorStage.setMinHeight(Math.min(520, sceneHeight));
            editorStage.setMaxWidth(screenBounds.getWidth());
            editorStage.setMaxHeight(screenBounds.getHeight());
            editorStage.setOnShown(event -> Platform.runLater(() -> {
                editorStage.sizeToScene();
                keepStageInsideScreen(editorStage, screenBounds);
                controller.refreshLayoutAfterShow();
            }));
            editorStage.setOnHidden(event -> {
                if (currentDirectoryPath != null) {
                    onDirectorySelected(currentDirectoryPath);
                }
            });
            editorStage.show();
        } catch (Exception e) {
            logger.error("打开图片编辑器失败", e);
            AlertUtil.showError("打开编辑器失败", e.getMessage());
        }
    }

    /**
     * 查看并编辑单张图片的标签。手动添加的标签会立即进入搜索索引。
     */
    private void onManageTags() {
        if (selectedImages.size() != 1) {
            AlertUtil.showWarning("无法管理标签", "请只选择一张图片");
            return;
        }

        ImageFile image = selectedImages.iterator().next();
        try {
            List<TagCategory> categories = tagDao.findAllCategories();
            if (categories.isEmpty()) {
                AlertUtil.showWarning("无法管理标签", "标签分类尚未初始化");
                return;
            }

            Map<Integer, String> categoryLabels = new LinkedHashMap<>();
            for (TagCategory category : categories) {
                categoryLabels.put(category.id(), category.displayName() + " (" + category.name() + ")");
            }

            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("管理标签");
            dialog.setHeaderText(image.fileName());
            if (thumbnailPane.getScene() != null) {
                dialog.initOwner(thumbnailPane.getScene().getWindow());
            }
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

            ListView<TagViewItem> tagListView = new ListView<>();
            tagListView.setPrefHeight(230);

            ComboBox<TagCategory> categoryCombo = new ComboBox<>(
                    FXCollections.observableArrayList(categories));
            categoryCombo.setPrefWidth(190);
            categoryCombo.setCellFactory(list -> createCategoryCell());
            categoryCombo.setButtonCell(createCategoryCell());
            categoryCombo.getSelectionModel().selectFirst();

            TextField tagNameField = new TextField();
            tagNameField.setPromptText("输入标签名称");
            tagNameField.setPrefWidth(220);

            Button addButton = new Button("添加");
            Button removeButton = new Button("删除选中标签");

            Runnable reloadTags = () -> {
                List<TagViewItem> items = new ArrayList<>();
                for (Tag tag : tagDao.findTagsByImageId(image.id())) {
                    String categoryLabel = categoryLabels.getOrDefault(tag.categoryId(), "未分类");
                    items.add(new TagViewItem(tag, categoryLabel));
                }
                tagListView.setItems(FXCollections.observableArrayList(items));
            };

            addButton.setOnAction(event -> {
                TagCategory category = categoryCombo.getValue();
                String tagName = tagNameField.getText().trim();
                if (category == null) {
                    AlertUtil.showWarning("添加失败", "请选择标签分类");
                    return;
                }
                if (tagName.isBlank()) {
                    AlertUtil.showWarning("添加失败", "请输入标签名称");
                    return;
                }
                try {
                    Tag tag = tagDao.findOrCreateTag(category.id(), tagName);
                    tagDao.linkImageTag(image.id(), tag.id(), 1.0f, "MANUAL");
                    tagNameField.clear();
                    reloadTags.run();
                    statusLabel.setText("已添加标签: " + tagName);
                } catch (Exception e) {
                    logger.error("添加标签失败: imageId={}, tag={}", image.id(), tagName, e);
                    AlertUtil.showError("添加标签失败", e.getMessage());
                }
            });

            removeButton.setOnAction(event -> {
                TagViewItem selected = tagListView.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    AlertUtil.showWarning("删除失败", "请先选择一个标签");
                    return;
                }
                try {
                    tagDao.unlinkImageTag(image.id(), selected.tag().id());
                    reloadTags.run();
                    statusLabel.setText("已删除标签: " + selected.tag().name());
                } catch (Exception e) {
                    logger.error("删除标签失败: imageId={}, tagId={}", image.id(), selected.tag().id(), e);
                    AlertUtil.showError("删除标签失败", e.getMessage());
                }
            });

            tagNameField.setOnAction(event -> addButton.fire());

            GridPane form = new GridPane();
            form.setHgap(10);
            form.setVgap(10);
            form.add(new Label("分类:"), 0, 0);
            form.add(categoryCombo, 1, 0);
            form.add(new Label("标签:"), 0, 1);
            form.add(tagNameField, 1, 1);

            HBox actions = new HBox(10, addButton, removeButton);
            actions.setAlignment(Pos.CENTER_LEFT);

            VBox content = new VBox(10,
                    new Label("当前标签"),
                    tagListView,
                    new Separator(),
                    form,
                    actions);
            content.setPrefWidth(520);
            content.setPadding(new javafx.geometry.Insets(10));

            reloadTags.run();
            dialog.getDialogPane().setContent(content);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("打开标签管理失败: imageId={}", image.id(), e);
            AlertUtil.showError("标签管理失败", e.getMessage());
        }
    }

    private ListCell<TagCategory> createCategoryCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(TagCategory category, boolean empty) {
                super.updateItem(category, empty);
                setText(empty || category == null
                        ? null
                        : category.displayName() + " (" + category.name() + ")");
            }
        };
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
     * 点击播放按钮时，打开幻灯片播放窗口。
     * v2.0: 如果有 Ctrl 多选，仅播放选中的图片。
     */
    @FXML
    private void onSlideshow() {
        if (currentImages.isEmpty())
            return;

        // v2.0: 多选播放 — 如果选中了多张，只播放选中的
        List<ImageFile> playList;
        int startIndex = 0;

        if (selectedImages.size() > 1) {
            // 按原始顺序排列选中的图片
            playList = currentImages.stream()
                    .filter(selectedImages::contains)
                    .toList();
            logger.info("多选播放: {} 张图片", playList.size());
        } else {
            playList = currentImages;
            if (!selectedImages.isEmpty()) {
                ImageFile first = selectedImages.iterator().next();
                startIndex = currentImages.indexOf(first);
                if (startIndex < 0) startIndex = 0;
            }
        }

        openSlideshow(playList, startIndex);
    }

    private void openSlideshow(int startIndex) {
        openSlideshow(currentImages, startIndex);
    }

    private void openSlideshow(List<ImageFile> images, int startIndex) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/SlideshowView.fxml"));
            Parent slideshowRoot = loader.load();

            SlideshowController controller = loader.getController();
            controller.initSlideshow(images, startIndex);

            Stage slideshowStage = new Stage();
            slideshowStage.setTitle("幻灯片播放 - 数字图像管理系统");
            Scene scene = new Scene(slideshowRoot, 1000, 700);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            slideshowStage.setScene(scene);
            slideshowStage.setOnCloseRequest(event -> controller.dispose());
            slideshowStage.setOnShown(event -> controller.refreshLayoutAfterShow());
            slideshowStage.show();

        } catch (Exception e) {
            logger.error("打开幻灯片播放失败", e);
            AlertUtil.showError("错误", "无法打开播放窗口: " + e.getMessage());
        }
    }

    // ==================== 搜索功能 (v2.0新增) ====================

    /**
     * 搜索按钮或回车触发的搜索。
     */
    @FXML
    private void onSearch() {
        String query = searchField != null ? searchField.getText().trim() : "";
        if (query.isEmpty()) {
            // 清空搜索，恢复当前目录显示
            if (currentDirectoryPath != null) {
                onDirectorySelected(currentDirectoryPath);
            }
            return;
        }
        if (currentDirectoryPath == null || currentDirectoryPath.isBlank()) {
            statusLabel.setText("请先选择一个文件夹再搜索");
            return;
        }
        String searchDirectoryPath = currentDirectoryPath;

        // 判断搜索模式
        int modeIndex = searchModeCombo != null
                ? searchModeCombo.getSelectionModel().getSelectedIndex() : 0;
        SearchService.SearchMode mode = (modeIndex == 1)
                ? SearchService.SearchMode.AI_SQL
                : SearchService.SearchMode.KEYWORD;

        long requestId = ++searchRequestId;
        String initialMessage = mode == SearchService.SearchMode.AI_SQL
                ? "AI搜索：准备发送请求..."
                : "关键词搜索：准备匹配...";
        logger.info("执行搜索: mode={}, query={}", mode, query);
        statusLabel.setText(initialMessage);
        setSearchControlsRunning(true, mode);

        // 后台执行搜索
        Task<SearchService.SearchResult> searchTask = new Task<>() {
            @Override
            protected SearchService.SearchResult call() {
                updateMessage(initialMessage);
                return searchService.search(query, mode, searchDirectoryPath, this::updateMessage);
            }
        };
        searchTask.messageProperty().addListener((obs, oldValue, newValue) -> {
            if (requestId == searchRequestId
                    && searchDirectoryPath.equals(currentDirectoryPath)
                    && newValue != null && !newValue.isBlank()) {
                statusLabel.setText(newValue);
            }
        });

        searchTask.setOnSucceeded(event -> {
            setSearchControlsRunning(false, mode);
            if (requestId != searchRequestId || !searchDirectoryPath.equals(currentDirectoryPath)) {
                return;
            }
            SearchService.SearchResult result = searchTask.getValue();
            selectedImages.clear();
            currentImages = new ArrayList<>(result.images());
            displayThumbnails(currentImages);
            directoryNameLabel.setText("当前文件夹及子文件夹搜索: \"" + query + "\"");
            setImageCountText(result.totalCount() + " 张图片");
            selectionLabel.setText("");
            statusLabel.setText(result.message());
            slideshowButton.setDisable(currentImages.isEmpty());
        });

        searchTask.setOnFailed(event -> {
            setSearchControlsRunning(false, mode);
            if (requestId != searchRequestId || !searchDirectoryPath.equals(currentDirectoryPath)) {
                return;
            }
            logger.error("搜索任务失败", searchTask.getException());
            statusLabel.setText("搜索失败: " + searchTask.getException().getMessage());
        });

        searchTask.setOnCancelled(event -> {
            setSearchControlsRunning(false, mode);
            if (requestId != searchRequestId || !searchDirectoryPath.equals(currentDirectoryPath)) {
                return;
            }
            statusLabel.setText("搜索已取消");
        });

        Thread.startVirtualThread(searchTask);
    }

    private void setImageCountText(String text) {
        if (imageCountLabel == null) {
            return;
        }
        String safeText = text == null ? "" : text;
        boolean hasText = !safeText.isBlank();
        imageCountLabel.setText(safeText);
        imageCountLabel.setVisible(hasText);
        imageCountLabel.setManaged(hasText);
    }

    private void setSearchControlsRunning(boolean running, SearchService.SearchMode mode) {
        if (searchButton != null) {
            searchButton.setDisable(running);
            if (running) {
                searchButton.setText(mode == SearchService.SearchMode.AI_SQL ? "等待AI" : "搜索中");
            } else {
                searchButton.setText("搜索");
            }
        }
        if (searchField != null) {
            searchField.setDisable(running);
        }
        if (searchModeCombo != null) {
            searchModeCombo.setDisable(running);
        }
    }

    // ==================== 设置页面 (v2.0新增) ====================

    /**
     * 打开设置页面。
     */
    @FXML
    private void onOpenSettings() {
        openSettingsWindow(thumbnailPane.getScene().getWindow());
    }

    public void openSettingsWindow(Window ownerWindow) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/SettingsView.fxml"));
            Parent settingsRoot = loader.load();
            SettingsController controller = loader.getController();

            Stage settingsStage = new Stage();
            settingsStage.setTitle("系统设置 - 数字图像管理系统");
            Rectangle2D screenBounds = ownerVisualBounds(ownerWindow);
            double sceneWidth = Math.min(720, Math.max(560, screenBounds.getWidth() - 96));
            double sceneHeight = Math.min(760, Math.max(520, screenBounds.getHeight() - 96));
            Scene scene = new Scene(settingsRoot, sceneWidth, sceneHeight);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm());
            settingsStage.setScene(scene);
            settingsStage.initOwner(ownerWindow);
            settingsStage.setResizable(true);
            settingsStage.setMinWidth(Math.min(560, sceneWidth));
            settingsStage.setMinHeight(Math.min(480, sceneHeight));
            settingsStage.setMaxWidth(screenBounds.getWidth());
            settingsStage.setMaxHeight(screenBounds.getHeight());
            settingsStage.setOnShown(event -> keepStageInsideScreen(settingsStage, screenBounds));
            settingsStage.showAndWait();

            if (controller.isSaved()) {
                applyTheme();
                if (controller.isScanRequested()) {
                    String scanDirectory = controller.getSavedScanDirectory();
                    statusLabel.setText("设置已保存，开始扫描目录...");
                    startScanTask(scanDirectory);
                } else {
                    statusLabel.setText("设置已保存");
                }
            }
        } catch (Exception e) {
            logger.error("打开设置页面失败", e);
            AlertUtil.showError("错误", "无法打开设置页面: " + e.getMessage());
        }
    }

    private Rectangle2D ownerVisualBounds(Window ownerWindow) {
        if (ownerWindow == null) {
            return Screen.getPrimary().getVisualBounds();
        }
        return Screen.getScreensForRectangle(
                        ownerWindow.getX(),
                        ownerWindow.getY(),
                        Math.max(1, ownerWindow.getWidth()),
                        Math.max(1, ownerWindow.getHeight()))
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
    }

    private double boundedSceneSize(double preferred, double minimum, double available, double margin) {
        double maxSize = Math.max(360, available - margin);
        double desired = Math.min(preferred, Math.max(minimum, maxSize));
        return Math.min(desired, maxSize);
    }

    private void keepStageInsideScreen(Stage stage, Rectangle2D bounds) {
        double margin = 24;
        double maxWidth = Math.max(360, bounds.getWidth() - margin);
        double maxHeight = Math.max(320, bounds.getHeight() - margin);
        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }

        double x = bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2;
        double y = bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2;
        stage.setX(Math.max(bounds.getMinX(), x));
        stage.setY(Math.max(bounds.getMinY(), y));
    }

    // ==================== AI扫描 (v2.0新增) ====================

    /**
     * 启动后台AI扫描任务。在首次启动向导确认后被 App.java 调用。
     */
    public void startScanTask(String directoryPath) {
        File scanDir = new File(directoryPath);
        if (!scanDir.exists() || !scanDir.isDirectory()) {
            logger.warn("扫描目录不存在: {}", directoryPath);
            return;
        }
        String scanDirectoryPath = normalizeDirectoryPath(scanDir);
        showScanDirectoryRoot(scanDirectoryPath, true);

        if (activeScanTask != null && activeScanTask.isRunning()) {
            if (sameDirectoryPath(activeScanDirectoryPath, scanDirectoryPath)) {
                statusLabel.setText("当前目录正在扫描中");
                logger.info("扫描任务已在运行，当前目录不重复启动: {}", scanDirectoryPath);
                return;
            }

            queuedScanDirectoryPath = scanDirectoryPath;
            promptCleanupAfterStop = false;
            statusLabel.setText("正在切换扫描目录，先停止旧任务...");
            unbindScanProgress();
            if (scanProgressLabel != null) {
                scanProgressLabel.setVisible(true);
                scanProgressLabel.setManaged(true);
                scanProgressLabel.setText("正在切换扫描目录，先停止旧任务...");
            }
            if (stopScanButton != null) {
                stopScanButton.setVisible(true);
                stopScanButton.setManaged(true);
                stopScanButton.setDisable(true);
            }
            logger.info("扫描任务正在运行，取消旧任务后切换到新目录: {}", scanDirectoryPath);
            activeScanTask.cancel();
            return;
        }

        ScanTask scanTask = new ScanTask(scanDir);
        activeScanTask = scanTask;
        activeScanDirectoryPath = scanDirectoryPath;
        promptCleanupAfterStop = false;

        // 绑定进度到UI
        if (scanProgressLabel != null && scanProgressBar != null) {
            unbindScanProgress();
            scanProgressLabel.setVisible(true);
            scanProgressLabel.setManaged(true);
            scanProgressBar.setVisible(true);
            scanProgressBar.setManaged(true);
            if (stopScanButton != null) {
                stopScanButton.setVisible(true);
                stopScanButton.setManaged(true);
                stopScanButton.setDisable(false);
            }

            scanProgressLabel.textProperty().bind(scanTask.messageProperty());
            scanProgressBar.progressProperty().bind(scanTask.progressProperty());

            scanTask.setOnSucceeded(e -> {
                unbindScanProgress();
                scanProgressLabel.setText("扫描完成");
                scanProgressBar.setProgress(1.0);
                activeScanTask = null;
                activeScanDirectoryPath = "";
                if (startQueuedScanIfAny()) {
                    return;
                }
                hideStopScanButton();
                if (currentDirectoryPath != null && isInsideDirectory(currentDirectoryPath, scanDirectoryPath)) {
                    onDirectorySelected(currentDirectoryPath);
                }
                hideScanProgressLater();
            });

            scanTask.setOnFailed(e -> {
                unbindScanProgress();
                scanProgressLabel.setText("扫描失败");
                activeScanTask = null;
                activeScanDirectoryPath = "";
                if (startQueuedScanIfAny()) {
                    return;
                }
                hideStopScanButton();
                logger.error("扫描任务失败", scanTask.getException());
            });

            scanTask.setOnCancelled(e -> {
                unbindScanProgress();
                scanProgressLabel.setText("扫描已取消");
                activeScanTask = null;
                activeScanDirectoryPath = "";
                if (startQueuedScanIfAny()) {
                    return;
                }
                hideStopScanButton();
                if (promptCleanupAfterStop) {
                    promptCleanupAfterStop = false;
                    Platform.runLater(this::onCleanupAiData);
                }
            });
        }

        Thread scanThread = new Thread(scanTask);
        scanThread.setDaemon(true);
        scanThread.setName("AI-Scan-Thread");
        scanThread.start();
        logger.info("AI扫描任务已启动: {}", scanDirectoryPath);
    }

    private void unbindScanProgress() {
        if (scanProgressLabel != null && scanProgressLabel.textProperty().isBound()) {
            scanProgressLabel.textProperty().unbind();
        }
        if (scanProgressBar != null && scanProgressBar.progressProperty().isBound()) {
            scanProgressBar.progressProperty().unbind();
        }
    }

    private boolean startQueuedScanIfAny() {
        if (queuedScanDirectoryPath == null || queuedScanDirectoryPath.isBlank()) {
            return false;
        }

        String nextDirectory = queuedScanDirectoryPath;
        queuedScanDirectoryPath = "";
        statusLabel.setText("旧扫描已停止，开始扫描新目录...");
        startScanTask(nextDirectory);
        return true;
    }

    private void hideScanProgressLater() {
        Thread hideThread = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            Platform.runLater(() -> {
                if (activeScanTask != null && activeScanTask.isRunning()) {
                    return;
                }
                if (scanProgressLabel != null) {
                    scanProgressLabel.setVisible(false);
                    scanProgressLabel.setManaged(false);
                }
                if (scanProgressBar != null) {
                    scanProgressBar.setVisible(false);
                    scanProgressBar.setManaged(false);
                }
            });
        }, "Scan-Progress-Hide");
        hideThread.setDaemon(true);
        hideThread.start();
    }

    private String normalizeDirectoryPath(File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (Exception e) {
            return dir.getAbsolutePath();
        }
    }

    private boolean sameDirectoryPath(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private boolean isInsideDirectory(String path, String directoryPath) {
        if (path == null || directoryPath == null || directoryPath.isBlank()) {
            return false;
        }
        String normalizedPath = normalizeDirectoryPath(new File(path));
        String normalizedDirectory = normalizeDirectoryPath(new File(directoryPath));
        if (normalizedPath.equalsIgnoreCase(normalizedDirectory)) {
            return true;
        }
        String prefix = normalizedDirectory.endsWith(File.separator)
                ? normalizedDirectory
                : normalizedDirectory + File.separator;
        return normalizedPath.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    @FXML
    private void onStopScan() {
        if (activeScanTask == null || !activeScanTask.isRunning()) {
            statusLabel.setText("当前没有正在运行的扫描任务");
            return;
        }

        boolean viewCleanup = AlertUtil.showConfirmation(
                "停止扫描",
                "将停止继续扫描和后续AI识别。当前正在请求中的一张图片可能会先完成。\n\n停止后是否查看并清理已经写入数据库的AI标签？"
        );
        promptCleanupAfterStop = viewCleanup;
        statusLabel.setText("正在停止扫描...");
        unbindScanProgress();
        if (scanProgressLabel != null) {
            scanProgressLabel.setText("正在停止扫描...");
        }
        if (stopScanButton != null) {
            stopScanButton.setDisable(true);
        }
        activeScanTask.cancel();

        if (!viewCleanup) {
            promptCleanupAfterStop = false;
        }
    }

    @FXML
    private void onCleanupAiData() {
        if (activeScanTask != null && activeScanTask.isRunning()) {
            boolean stopFirst = AlertUtil.showConfirmation(
                    "清理AI标签",
                    "扫描任务仍在运行。清理前需要先停止扫描，避免一边删除一边继续写入。\n\n是否先停止扫描，停止后打开清理窗口？"
            );
            if (stopFirst) {
                promptCleanupAfterStop = true;
                statusLabel.setText("正在停止扫描，稍后打开清理窗口...");
                if (stopScanButton != null) {
                    stopScanButton.setDisable(true);
                }
                activeScanTask.cancel();
            }
            return;
        }

        AiTagStorageService.StorageStats stats;
        try {
            stats = aiTagStorageService.loadStats();
        } catch (Exception e) {
            logger.error("读取AI标签存储信息失败", e);
            AlertUtil.showError("读取失败", e.getMessage());
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("AI标签存储与清理");
        alert.setHeaderText("AI标签保存在 PostgreSQL 数据库中，不是图片旁边的独立文件。");
        TextArea detailArea = new TextArea(stats.summaryText());
        detailArea.setEditable(false);
        detailArea.setWrapText(true);
        detailArea.setPrefWidth(720);
        detailArea.setPrefHeight(360);
        alert.getDialogPane().setContent(detailArea);

        ButtonType cleanupButtonType = new ButtonType("清理AI标签", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cleanupButtonType, cancelButtonType);

        if (alert.showAndWait().orElse(cancelButtonType) != cleanupButtonType) {
            return;
        }

        Task<AiTagStorageService.CleanupResult> cleanupTask = new Task<>() {
            @Override
            protected AiTagStorageService.CleanupResult call() {
                updateMessage("正在清理AI标签数据...");
                return aiTagStorageService.cleanupAiTags();
            }
        };

        cleanupAiButton.setDisable(true);
        statusLabel.textProperty().bind(cleanupTask.messageProperty());

        cleanupTask.setOnSucceeded(event -> {
            statusLabel.textProperty().unbind();
            cleanupAiButton.setDisable(false);
            AiTagStorageService.CleanupResult result = cleanupTask.getValue();
            statusLabel.setText("AI标签已清理");
            AlertUtil.showInfo("清理完成", result.summaryText());
            if (currentDirectoryPath != null) {
                onDirectorySelected(currentDirectoryPath);
            }
        });

        cleanupTask.setOnFailed(event -> {
            statusLabel.textProperty().unbind();
            cleanupAiButton.setDisable(false);
            Throwable error = cleanupTask.getException();
            logger.error("AI标签清理失败", error);
            statusLabel.setText("AI标签清理失败");
            AlertUtil.showError("清理失败", error == null ? "未知错误" : error.getMessage());
        });

        Thread cleanupThread = new Thread(cleanupTask);
        cleanupThread.setDaemon(true);
        cleanupThread.setName("AI-Tag-Cleanup");
        cleanupThread.start();
    }

    private void hideStopScanButton() {
        if (stopScanButton != null) {
            stopScanButton.setVisible(false);
            stopScanButton.setManaged(false);
            stopScanButton.setDisable(false);
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
