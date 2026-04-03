package com.imagemanager;

import com.imagemanager.dao.DatabaseConnection;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数字图像集成管理系统 — 应用程序入口。
 * <p>
 * 负责初始化数据库连接池、加载主界面 FXML、启动 JavaFX 事件循环。
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

        } catch (Exception e) {
            logger.error("应用程序启动失败", e);
            // 确保即使启动失败也关闭数据库连接池
            DatabaseConnection.close();
            throw new RuntimeException("无法启动应用程序: " + e.getMessage(), e);
        }
    }

    /**
     * JVM 入口。JavaFX 的 Application.launch() 会创建 App 实例并调用 start()。
     */
    public static void main(String[] args) {
        launch(args);
    }
}
