package com.imagemanager.controller;

import com.imagemanager.model.ImageFile;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.List;

/**
 * 批量重命名对话框控制器。
 * <p>
 * 功能：
 * <ul>
 *   <li>输入名称前缀、起始编号、编号位数</li>
 *   <li>实时预览重命名后的文件名列表</li>
 *   <li>确认/取消操作</li>
 * </ul>
 */
public class RenameDialogController {

    // ==================== FXML 注入 ====================

    @FXML private TextField prefixField;
    @FXML private Spinner<Integer> startNumberSpinner;
    @FXML private Spinner<Integer> digitCountSpinner;
    @FXML private TextArea previewArea;
    @FXML private Button confirmButton;
    @FXML private Button cancelButton;

    // ==================== 状态 ====================

    /** 要重命名的图片列表 */
    private List<ImageFile> images;

    /** 确认回调（由 MainController 设置） */
    private RenameCallback onConfirmCallback;

    // ==================== 初始化 ====================

    @FXML
    public void initialize() {
        // 配置起始编号 Spinner: 范围 0~99999, 默认 1
        startNumberSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 99999, 1)
        );

        // 配置编号位数 Spinner: 范围 1~6, 默认 4
        digitCountSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 6, 4)
        );

        // 监听输入变化 → 实时更新预览
        prefixField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());
        startNumberSpinner.valueProperty().addListener((obs, oldVal, newVal) -> updatePreview());
        digitCountSpinner.valueProperty().addListener((obs, oldVal, newVal) -> updatePreview());
    }

    /**
     * 设置要重命名的图片列表（由 MainController 调用）。
     */
    public void initData(List<ImageFile> images) {
        this.images = images;
        updatePreview();
    }

    /**
     * 设置确认回调。
     */
    public void setOnConfirm(RenameCallback callback) {
        this.onConfirmCallback = callback;
    }

    // ==================== 预览 ====================

    /**
     * 根据当前输入实时生成重命名预览。
     */
    private void updatePreview() {
        if (images == null || images.isEmpty()) return;

        String prefix = prefixField.getText();
        if (prefix == null || prefix.isBlank()) {
            previewArea.setText("请输入名称前缀");
            return;
        }

        int startNumber = startNumberSpinner.getValue();
        int digitCount = digitCountSpinner.getValue();

        var sb = new StringBuilder();
        int previewCount = Math.min(images.size(), 10); // 最多预览10个

        for (int i = 0; i < previewCount; i++) {
            int number = startNumber + i;
            String paddedNumber = String.format("%0" + digitCount + "d", number);
            String extension = images.get(i).extension();
            sb.append(prefix).append(paddedNumber).append(extension);
            if (i < previewCount - 1) sb.append('\n');
        }

        if (images.size() > previewCount) {
            sb.append("\n... 共 ").append(images.size()).append(" 张");
        }

        previewArea.setText(sb.toString());
    }

    // ==================== 按钮操作 ====================

    /**
     * 确认按钮 — 调用回调执行批量重命名。
     */
    @FXML
    private void onConfirm() {
        String prefix = prefixField.getText();
        if (prefix == null || prefix.isBlank()) {
            previewArea.setText("⚠ 请输入名称前缀！");
            return;
        }

        if (onConfirmCallback != null) {
            onConfirmCallback.execute(
                    prefix,
                    startNumberSpinner.getValue(),
                    digitCountSpinner.getValue()
            );
        }
    }

    /**
     * 取消按钮 — 关闭对话框。
     */
    @FXML
    private void onCancel() {
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }

    // ==================== 回调接口 ====================

    /**
     * 重命名确认回调接口。
     * 使用函数式接口，方便 MainController 用 lambda 设置。
     */
    @FunctionalInterface
    public interface RenameCallback {
        void execute(String prefix, int startNumber, int digitCount);
    }
}
