package com.imagemanager;

import com.imagemanager.controller.MainController;
import com.imagemanager.controller.WelcomeDialogController;
import com.imagemanager.dao.DatabaseConnection;
import com.imagemanager.dao.SettingsDao;
import com.imagemanager.dao.SettingsDaoImpl;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 数字图像集成管理系统 — 应用程序入口。
 * <p>
 * 负责初始化数据库连接池、显示首次启动向导、加载主界面 FXML、启动 JavaFX 事件循环。
 * 应用退出时自动关闭数据库连接池，确保资源不泄漏。
 */
public class App extends Application {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) {
        try {
            // 1. 初始化数据库连接池
            logger.info("正在初始化数据库连接...");
            DatabaseConnection.initialize();
            logger.info("数据库连接初始化成功");

            // 2. 加载主界面 FXML
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/MainView.fxml")
            );
            Parent root = loader.load();
            MainController mainController = loader.getController();

            // 3. 配置主窗口
            Scene scene = new Scene(root, 1200, 800);
            scene.getStylesheets().add(
                    getClass().getResource("/css/style.css").toExternalForm()
            );

            primaryStage.setTitle("数字图像集成管理系统");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);

            // 4. 窗口关闭时清理资源
            primaryStage.setOnCloseRequest(event -> {
                logger.info("应用程序关闭中，释放资源...");
                DatabaseConnection.close();
                logger.info("资源释放完毕");
            });

            primaryStage.show();
            logger.info("应用程序启动成功");

            // 5. v2.0: 首次启动向导 — 在主窗口显示后弹出
            showWelcomeDialogIfNeeded(primaryStage, mainController);

        } catch (Exception e) {
            logger.error("应用程序启动失败", e);
            // 确保即使启动失败也关闭数据库连接池
            DatabaseConnection.close();
            throw new RuntimeException("无法启动应用程序: " + e.getMessage(), e);
        }
    }

    /**
     * 检查是否需要显示首次启动向导。
     * <p>
     * 调试阶段每次启动都显示（直到用户勾选"下次不展示"）。
     * 在设置页面中可以重新调出此向导。
     */
    private void showWelcomeDialogIfNeeded(Stage owner, MainController mainController) {
        try {
            if (!WelcomeDialogController.shouldShowWelcome()) {
                // 不需要显示向导，但如果已有配置的扫描目录，自动启动扫描
                SettingsDao settingsDao = new SettingsDaoImpl();
                String scanDir = settingsDao.getValueOrDefault("scan_directory", "");
                if (!scanDir.isBlank()) {
                    mainController.startScanTask(scanDir);
                }
                return;
            }

            // 加载向导 FXML
            FXMLLoader dialogLoader = new FXMLLoader(
                    getClass().getResource("/fxml/WelcomeDialog.fxml")
            );
            DialogPane dialogPane = dialogLoader.load();
            WelcomeDialogController welcomeController = dialogLoader.getController();

            // 创建对话框
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("欢迎 — 数字图像集成管理系统");
            dialog.setDialogPane(dialogPane);
            dialog.initOwner(owner);

            // 显示并等待用户响应
            Optional<ButtonType> result = dialog.showAndWait();

            if (result.isPresent() && result.get() == ButtonType.OK) {
                // 用户确认了目录选择
                welcomeController.saveSettings();
                String selectedDir = welcomeController.getSelectedDirectory();
                if (!selectedDir.isBlank()) {
                    logger.info("用户选择扫描目录: {}", selectedDir);
                    mainController.startScanTask(selectedDir);
                }
            } else {
                logger.info("用户取消了首次启动向导");
            }

        } catch (Exception e) {
            logger.error("显示首次启动向导失败", e);
            // 向导失败不影响主程序运行
        }
    }

    /**
     * JVM 入口。JavaFX 的 Application.launch() 会创建 App 实例并调用 start()。
     */
    public static void main(String[] args) {
        launch(args);
    }
}
