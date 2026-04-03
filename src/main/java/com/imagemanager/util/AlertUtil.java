package com.imagemanager.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;

import java.util.Optional;

/**
 * 对话框/弹窗工具类 — 封装 JavaFX 常用的弹窗操作。
 * <p>
 * 向用户展示信息、警告、错误提示，以及确认对话框和文本输入对话框。
 * 所有方法设计为静态调用，无需实例化。
 */
public final class AlertUtil {

    private AlertUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 显示信息提示框。
     *
     * @param title   标题
     * @param message 消息内容
     */
    public static void showInfo(String title, String message) {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * 显示警告提示框。
     *
     * @param title   标题
     * @param message 警告内容
     */
    public static void showWarning(String title, String message) {
        var alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * 显示错误提示框。
     * 用于向用户展示友好的错误信息，而非技术性堆栈。
     *
     * @param title   标题
     * @param message 错误描述（用户能理解的语言）
     */
    public static void showError(String title, String message) {
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * 显示确认对话框，返回用户是否点击了"确认"。
     * <p>
     * 用于删除等危险操作的二次确认。
     *
     * @param title   标题
     * @param message 确认消息
     * @return true 表示用户确认，false 表示用户取消
     */
    public static boolean showConfirmation(String title, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        // 设置按钮文字
        alert.getButtonTypes().setAll(
                new ButtonType("确认"),
                new ButtonType("取消")
        );

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get().getText().equals("确认");
    }

    /**
     * 显示文本输入对话框，返回用户输入的内容。
     *
     * @param title        标题
     * @param prompt       提示信息
     * @param defaultValue 输入框默认值
     * @return 用户输入的值，取消时返回 empty
     */
    public static Optional<String> showTextInput(String title, String prompt, String defaultValue) {
        var dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        return dialog.showAndWait();
    }
}
