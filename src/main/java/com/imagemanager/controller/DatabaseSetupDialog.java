package com.imagemanager.controller;

import com.imagemanager.dao.DatabaseConnection;
import com.imagemanager.service.DatabaseBootstrapService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;

/**
 * PostgreSQL 安装、连接和一键初始化向导。
 */
public final class DatabaseSetupDialog {

    private static final String POSTGRES_DOWNLOAD_URL = "https://www.postgresql.org/download/";
    private static Stage currentStage;

    private DatabaseSetupDialog() {
    }

    public static void show(Window owner, Runnable onStatusChanged) {
        if (currentStage != null && currentStage.isShowing()) {
            currentStage.setIconified(false);
            currentStage.toFront();
            currentStage.requestFocus();
            return;
        }

        var config = DatabaseConnection.configuredDatabase();
        Stage stage = new Stage();
        stage.setTitle("数据库连接与初始化向导");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }

        Label title = new Label("PostgreSQL 18 数据库向导");
        title.getStyleClass().add("settings-title");

        Label summary = new Label(
                "未连接数据库时仍可浏览本机图片，但标签、搜索、AI识别、缩略图缓存和设置保存需要 PostgreSQL。");
        summary.setWrapText(true);
        summary.getStyleClass().add("settings-description");

        Hyperlink downloadLink = new Hyperlink("打开 PostgreSQL 官方下载页（PostgreSQL 18）");
        downloadLink.setOnAction(event -> openDownloadPage());

        TextArea guideArea = new TextArea("""
                手动安装时按 README 的顺序操作：

                1. 安装 PostgreSQL 18，并确保 PostgreSQL 服务已启动。
                   官方下载地址：https://www.postgresql.org/download/
                2. 使用安装时设置的 postgres 用户密码。
                3. 本向导可在应用内执行等价操作：
                   psql -U postgres -c "CREATE DATABASE image_manager ENCODING 'UTF8';"
                   psql -U postgres -d image_manager -f sql/schema.sql

                如果一键初始化失败，通常是 PostgreSQL 未安装、服务未启动、端口不是 5432，或 postgres 密码不正确。
                """);
        guideArea.setEditable(false);
        guideArea.setWrapText(true);
        guideArea.setPrefRowCount(8);

        TextField jdbcUrlField = new TextField(config.url());
        TextField usernameField = new TextField(config.username());
        PasswordField passwordField = new PasswordField();
        passwordField.setText(config.password());
        Label configPathLabel = new Label("本机配置保存位置：" + DatabaseConnection.userConfigPath());
        configPathLabel.setWrapText(true);
        configPathLabel.getStyleClass().add("settings-description");

        GridPane configGrid = new GridPane();
        configGrid.setHgap(10);
        configGrid.setVgap(10);
        configGrid.add(new Label("JDBC URL:"), 0, 0);
        configGrid.add(jdbcUrlField, 1, 0);
        configGrid.add(new Label("用户名:"), 0, 1);
        configGrid.add(usernameField, 1, 1);
        configGrid.add(new Label("密码:"), 0, 2);
        configGrid.add(passwordField, 1, 2);
        GridPane.setHgrow(jdbcUrlField, Priority.ALWAYS);
        GridPane.setHgrow(usernameField, Priority.ALWAYS);
        GridPane.setHgrow(passwordField, Priority.ALWAYS);

        TextArea resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setPrefRowCount(5);
        resultArea.setText(currentStatusText());

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(28, 28);

        Button detectButton = new Button("检测连接");
        Button initButton = new Button("一键创建数据库并初始化");
        Button saveButton = new Button("保存配置");
        Button closeButton = new Button("关闭");
        detectButton.getStyleClass().add("secondary-button");
        initButton.getStyleClass().add("primary-button");
        saveButton.getStyleClass().add("secondary-button");
        closeButton.getStyleClass().add("cancel-button");

        saveButton.setOnAction(event -> {
            saveConfig(jdbcUrlField, usernameField, passwordField);
            resultArea.setText("配置已保存。点击“检测连接”或“一键创建数据库并初始化”继续。");
            runCallback(onStatusChanged);
        });
        detectButton.setOnAction(event -> runDatabaseTask(
                "正在检测数据库连接...",
                progress,
                resultArea,
                detectButton,
                initButton,
                saveButton,
                () -> {
                    saveConfig(jdbcUrlField, usernameField, passwordField);
                    return new DatabaseBootstrapService().check().message();
                },
                onStatusChanged
        ));
        initButton.setOnAction(event -> runDatabaseTask(
                "正在创建数据库并执行内置 schema.sql...",
                progress,
                resultArea,
                detectButton,
                initButton,
                saveButton,
                () -> {
                    saveConfig(jdbcUrlField, usernameField, passwordField);
                    DatabaseBootstrapService.BootstrapResult result =
                            new DatabaseBootstrapService().createDatabaseAndSchema();
                    return result.message();
                },
                onStatusChanged
        ));
        closeButton.setOnAction(event -> stage.close());

        HBox buttons = new HBox(10, progress, detectButton, initButton, saveButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, title, summary, downloadLink, guideArea, configGrid,
                configPathLabel, resultArea, buttons);
        root.setPadding(new Insets(18));
        root.getStyleClass().add("settings-root");
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        Scene scene = new Scene(root, 760, 680);
        var css = DatabaseSetupDialog.class.getResource("/css/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setMinWidth(660);
        stage.setMinHeight(560);
        stage.setOnHidden(event -> {
            if (currentStage == stage) {
                currentStage = null;
            }
        });
        currentStage = stage;
        stage.show();
    }

    private static void runDatabaseTask(String runningText,
                                        ProgressIndicator progress,
                                        TextArea resultArea,
                                        Button detectButton,
                                        Button initButton,
                                        Button saveButton,
                                        DatabaseAction action,
                                        Runnable onStatusChanged) {
        progress.setVisible(true);
        detectButton.setDisable(true);
        initButton.setDisable(true);
        saveButton.setDisable(true);
        resultArea.setText(runningText);

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return action.run();
            }
        };
        task.setOnSucceeded(event -> {
            progress.setVisible(false);
            detectButton.setDisable(false);
            initButton.setDisable(false);
            saveButton.setDisable(false);
            resultArea.setText(task.getValue());
            runCallback(onStatusChanged);
        });
        task.setOnFailed(event -> {
            progress.setVisible(false);
            detectButton.setDisable(false);
            initButton.setDisable(false);
            saveButton.setDisable(false);
            Throwable error = task.getException();
            resultArea.setText("操作失败：" + (error == null ? "未知错误" : error.getMessage()));
            runCallback(onStatusChanged);
        });

        Thread thread = new Thread(task, "Database-Bootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    private static void saveConfig(TextField jdbcUrlField, TextField usernameField, PasswordField passwordField) {
        DatabaseConnection.saveUserConfig(
                jdbcUrlField.getText(),
                usernameField.getText(),
                passwordField.getText()
        );
    }

    private static String currentStatusText() {
        DatabaseBootstrapService.DatabaseCheck check = new DatabaseBootstrapService().check();
        return check.message();
    }

    private static void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(POSTGRES_DOWNLOAD_URL));
            }
        } catch (Exception ignored) {
            // 用户仍可复制界面上的链接文本。
        }
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            Platform.runLater(callback);
        }
    }

    @FunctionalInterface
    private interface DatabaseAction {
        String run();
    }
}
