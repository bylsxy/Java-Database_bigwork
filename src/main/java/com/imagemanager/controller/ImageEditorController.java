package com.imagemanager.controller;

import com.imagemanager.model.ImageFile;
import com.imagemanager.model.ImageVersion;
import com.imagemanager.service.EditService;
import com.imagemanager.util.AlertUtil;
import com.imagemanager.util.ImageUtil;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 图片编辑器控制器 — 管理画笔/裁切/文字/箭头/矩形工具和版本历史。
 * <p>
 * 编辑工作流：
 * <ol>
 *   <li>用户选择工具后在Canvas上绘制</li>
 *   <li>点击"保存版本"将Canvas叠加到原图上，生成新版本快照</li>
 *   <li>底部版本时间轴可浏览和恢复历史版本</li>
 * </ol>
 */
public class ImageEditorController {

    private static final Logger logger = LoggerFactory.getLogger(ImageEditorController.class);

    private final EditService editService = new EditService();

    // ==================== FXML 注入 ====================

    @FXML private StackPane canvasContainer;
    @FXML private ScrollPane editorScrollPane;
    @FXML private ImageView editorImageView;
    @FXML private Canvas drawCanvas;
    @FXML private ColorPicker colorPicker;
    @FXML private Spinner<Integer> lineWidthSpinner;
    @FXML private Label toolStatusLabel;
    @FXML private HBox versionTimeline;
    @FXML private ScrollPane versionScroll;

    // ==================== 状态 ====================

    private ImageFile currentImage;
    private Image originalImage;
    private GraphicsContext gc;

    /** 当前编辑工具 */
    private enum Tool { MOVE, DRAW, CROP, TEXT, ARROW, RECT }
    private Tool currentTool = Tool.DRAW;

    /** 鼠标拖拽起点 */
    private double startX, startY;
    private boolean isDrawing = false;

    /** 完整编辑状态撤销栈：底图 + 透明标注层。 */
    private final Deque<EditorState> undoStack = new ArrayDeque<>();

    private record EditorState(WritableImage baseImage, WritableImage overlayImage,
                               double canvasWidth, double canvasHeight) {}

    // ==================== 初始化 ====================

    @FXML
    public void initialize() {
        // 颜色选择器默认红色
        colorPicker.setValue(Color.RED);

        // 线宽 Spinner
        lineWidthSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, 3));
    }

    /**
     * 由外部调用 — 传入要编辑的图片。
     */
    public void initEditor(ImageFile image) {
        this.currentImage = image;
        this.originalImage = ImageUtil.loadImage(image.filePath());

        if (originalImage == null) {
            AlertUtil.showError("错误", "无法加载图片: " + image.filePath());
            return;
        }

        // 配置 ImageView
        editorImageView.setImage(originalImage);
        editorImageView.setFitWidth(originalImage.getWidth());
        editorImageView.setFitHeight(originalImage.getHeight());

        // 配置 Canvas 大小匹配图片
        drawCanvas.setWidth(originalImage.getWidth());
        drawCanvas.setHeight(originalImage.getHeight());
        gc = drawCanvas.getGraphicsContext2D();

        // 绑定鼠标事件到 Canvas
        drawCanvas.setOnMousePressed(this::onMousePressed);
        drawCanvas.setOnMouseDragged(this::onMouseDragged);
        drawCanvas.setOnMouseReleased(this::onMouseReleased);
        applyToolInteractionMode();

        // 创建原始版本（如果尚不存在）
        editService.createOriginalVersion(image);

        // 加载版本时间轴
        refreshVersionTimeline();

        logger.info("编辑器已初始化: {}", image.fileName());
    }

    // ==================== 工具切换 ====================

    @FXML private void onMoveMode() {
        currentTool = Tool.MOVE;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 移动 - 拖动画面");
    }

    @FXML private void onCropMode() {
        currentTool = Tool.CROP;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 裁切 - 拖拽选择区域");
    }

    @FXML private void onDrawMode() {
        currentTool = Tool.DRAW;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 画笔 - 自由绘制");
    }

    @FXML private void onTextMode() {
        currentTool = Tool.TEXT;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 文字 - 点击添加文字");
    }

    @FXML private void onArrowMode() {
        currentTool = Tool.ARROW;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 箭头 - 拖拽绘制箭头");
    }

    @FXML private void onRectMode() {
        currentTool = Tool.RECT;
        applyToolInteractionMode();
        toolStatusLabel.setText("当前工具: 矩形 - 拖拽绘制矩形");
    }

    private void applyToolInteractionMode() {
        boolean moveMode = currentTool == Tool.MOVE;
        if (editorScrollPane != null) {
            editorScrollPane.setPannable(moveMode);
        }
        if (drawCanvas != null) {
            drawCanvas.setMouseTransparent(moveMode);
        }
    }

    // ==================== 鼠标事件处理 ====================

    private void onMousePressed(MouseEvent event) {
        if (currentTool == Tool.MOVE) {
            return;
        }

        startX = event.getX();
        startY = event.getY();
        isDrawing = true;

        gc.setStroke(colorPicker.getValue());
        gc.setFill(colorPicker.getValue());
        gc.setLineWidth(lineWidthSpinner.getValue());

        switch (currentTool) {
            case DRAW -> {
                pushUndoState();
                gc.beginPath();
                gc.moveTo(startX, startY);
                gc.stroke();
            }
            case TEXT -> {
                // 弹出文字输入
                AlertUtil.showTextInput("添加文字", "请输入要标注的文字:", "")
                        .ifPresent(text -> {
                            pushUndoState();
                            gc.setFont(javafx.scene.text.Font.font(lineWidthSpinner.getValue() * 6));
                            gc.fillText(text, startX, startY);
                        });
                isDrawing = false;
            }
            case CROP, ARROW, RECT -> pushUndoState();
            default -> {}
        }
        event.consume();
    }

    private void onMouseDragged(MouseEvent event) {
        if (!isDrawing) return;

        double x = event.getX();
        double y = event.getY();

        if (currentTool == Tool.DRAW) {
            gc.lineTo(x, y);
            gc.stroke();
        }
        // CROP/ARROW/RECT 的预览可以在这里用 XOR 模式绘制，
        // 简化起见，这里只在 release 时画最终结果
        event.consume();
    }

    private void onMouseReleased(MouseEvent event) {
        if (!isDrawing) return;
        isDrawing = false;

        double endX = event.getX();
        double endY = event.getY();

        switch (currentTool) {
            case DRAW -> gc.closePath();
            case ARROW -> drawArrow(startX, startY, endX, endY);
            case RECT -> {
                double rx = Math.min(startX, endX);
                double ry = Math.min(startY, endY);
                double rw = Math.abs(endX - startX);
                double rh = Math.abs(endY - startY);
                gc.strokeRect(rx, ry, rw, rh);
            }
            case CROP -> {
                double cx = Math.min(startX, endX);
                double cy = Math.min(startY, endY);
                double cw = Math.abs(endX - startX);
                double ch = Math.abs(endY - startY);

                if (cw > 10 && ch > 10) {
                    boolean confirmed = AlertUtil.showConfirmation(
                            "确认裁切",
                            String.format("裁切区域: %.0f×%.0f，确定吗？", cw, ch));
                    if (confirmed) {
                        WritableImage cropped = editService.cropImage(originalImage, cx, cy, cw, ch);
                        // 更新显示
                        originalImage = cropped;
                        editorImageView.setImage(cropped);
                        drawCanvas.setWidth(cropped.getWidth());
                        drawCanvas.setHeight(cropped.getHeight());
                        gc.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
                        // 自动保存版本
                        saveCurrentVersion("CROP", "裁切为 " + (int) cw + "×" + (int) ch);
                    }
                }
            }
            default -> {}
        }
        event.consume();
    }

    /**
     * 绘制箭头。
     */
    private void drawArrow(double x1, double y1, double x2, double y2) {
        gc.strokeLine(x1, y1, x2, y2);

        // 箭头头部
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double arrowLen = 15;
        double arrowAngle = Math.toRadians(25);

        double ax1 = x2 - arrowLen * Math.cos(angle - arrowAngle);
        double ay1 = y2 - arrowLen * Math.sin(angle - arrowAngle);
        double ax2 = x2 - arrowLen * Math.cos(angle + arrowAngle);
        double ay2 = y2 - arrowLen * Math.sin(angle + arrowAngle);

        gc.strokeLine(x2, y2, ax1, ay1);
        gc.strokeLine(x2, y2, ax2, ay2);
    }

    // ==================== 撤销 ====================

    private void pushUndoState() {
        undoStack.push(new EditorState(
                copyImage(originalImage),
                snapshotCanvasLayer(),
                drawCanvas.getWidth(),
                drawCanvas.getHeight()
        ));
    }

    @FXML
    private void onUndo() {
        if (!undoStack.isEmpty()) {
            restoreState(undoStack.pop());
            toolStatusLabel.setText("已撤销上一步操作");
        } else {
            toolStatusLabel.setText("没有可撤销的操作");
        }
    }

    private void restoreState(EditorState state) {
        originalImage = copyImage(state.baseImage());
        editorImageView.setImage(originalImage);
        editorImageView.setFitWidth(state.canvasWidth());
        editorImageView.setFitHeight(state.canvasHeight());

        drawCanvas.setWidth(state.canvasWidth());
        drawCanvas.setHeight(state.canvasHeight());
        gc = drawCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
        gc.drawImage(state.overlayImage(), 0, 0);
    }

    private WritableImage snapshotCanvasLayer() {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return drawCanvas.snapshot(params, null);
    }

    private WritableImage copyImage(Image image) {
        int width = Math.max(1, (int) Math.round(image.getWidth()));
        int height = Math.max(1, (int) Math.round(image.getHeight()));
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return new WritableImage(width, height);
        }
        return new WritableImage(reader, width, height);
    }

    // ==================== 保存版本 ====================

    @FXML
    private void onSaveVersion() {
        saveCurrentVersion("ANNOTATE", "手动保存");
    }

    private void saveCurrentVersion(String editType, String description) {
        try {
            WritableImage merged = editService.mergeCanvasWithImage(originalImage, drawCanvas);
            editService.saveEditedVersion(
                    currentImage.id(), merged, editType, description,
                    currentImage.filePath()
            );

            // 更新当前显示的原图为合并后的
            originalImage = merged;
            editorImageView.setImage(merged);
            gc.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
            undoStack.clear();

            // 刷新版本时间轴
            refreshVersionTimeline();

            AlertUtil.showInfo("保存成功", "版本已保存: " + description);
        } catch (Exception e) {
            logger.error("保存版本失败", e);
            AlertUtil.showError("保存失败", e.getMessage());
        }
    }

    // ==================== 版本时间轴 ====================

    /**
     * 刷新底部版本时间轴。
     */
    private void refreshVersionTimeline() {
        versionTimeline.getChildren().clear();

        List<ImageVersion> versions = editService.getVersionHistory(currentImage.id());
        for (ImageVersion v : versions) {
            VBox versionNode = createVersionNode(v);
            versionTimeline.getChildren().add(versionNode);
        }
    }

    /**
     * 创建单个版本节点（时间轴中的一个卡片）。
     */
    private VBox createVersionNode(ImageVersion version) {
        Label numLabel = new Label("v" + version.versionNum());
        numLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label typeLabel = new Label(version.editType());
        typeLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        VBox node = new VBox(2, numLabel, typeLabel);
        node.setAlignment(javafx.geometry.Pos.CENTER);
        node.setPrefWidth(60);
        node.setPrefHeight(40);
        node.setStyle(version.isCurrent()
                ? "-fx-background-color: #1976d2; -fx-background-radius: 6; -fx-padding: 4;"
                : "-fx-background-color: #f0f0f0; -fx-background-radius: 6; -fx-padding: 4; -fx-cursor: hand;");

        if (version.isCurrent()) {
            numLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: white;");
            typeLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #ddd;");
        }

        // 点击恢复到该版本
        if (!version.isCurrent()) {
            node.setOnMouseClicked(event -> {
                boolean confirmed = AlertUtil.showConfirmation(
                        "恢复版本",
                        "确定恢复到版本 v" + version.versionNum() + " (" + version.editType() + ") 吗？");
                if (confirmed) {
                    editService.restoreVersion(currentImage.id(), version.id(), currentImage.filePath());
                    // 重新加载图片
                    originalImage = ImageUtil.loadImage(currentImage.filePath());
                    editorImageView.setImage(originalImage);
                    if (originalImage != null) {
                        drawCanvas.setWidth(originalImage.getWidth());
                        drawCanvas.setHeight(originalImage.getHeight());
                    }
                    gc.clearRect(0, 0, drawCanvas.getWidth(), drawCanvas.getHeight());
                    undoStack.clear();
                    refreshVersionTimeline();
                }
            });
        }

        return node;
    }
}
