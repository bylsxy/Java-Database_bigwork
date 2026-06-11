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

        Label title = new Label("PostgreSQL 数据库向导");
        title.getStyleClass().add("settings-title");

        Label summary = new Label(
                "exe 便携包已捆绑 Java 运行时和程序依赖；新电脑通常只需要补 PostgreSQL。未连接数据库时仍可浏览本机图片，但标签、搜索、AI识别、缩略图缓存和设置保存需要 PostgreSQL。");
        summary.setWrapText(true);
        summary.getStyleClass().add("settings-description");

        Button installButton = new Button("一键打开 PostgreSQL 安装页");
        installButton.getStyleClass().add("secondary-button");
        installButton.setOnAction(event -> openDownloadPage());

        Hyperlink downloadLink = new Hyperlink(POSTGRES_DOWNLOAD_URL);
        downloadLink.setOnAction(event -> openDownloadPage());
        HBox installRow = new HBox(10, installButton, downloadLink);
        installRow.setAlignment(Pos.CENTER_LEFT);

        TextArea guideArea = new TextArea("""
                首次搬到新电脑时按这个顺序操作：

                1. 点击上方按钮安装 PostgreSQL 16 或以上版本，并确保 PostgreSQL 服务已启动。
                   官方下载地址：https://www.postgresql.org/download/
                2. 在下方填写安装时设置的 postgres 用户密码。密码未知或错误时，重新填写后先点“保存配置”。
                3. 点击“检测连接”确认账号密码可用。
                4. 点击“一键创建数据库并初始化”，本向导会执行等价操作：
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
            resultArea.setText("配置已保存。密码未知或刚改过时，请重新输入 PostgreSQL 的 postgres 密码，然后点击“检测连接”或“一键创建数据库并初始化”。");
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

        VBox root = new VBox(12, title, summary, installRow, guideArea, configGrid,
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
            resultArea.setText("操作失败：" + DatabaseBootstrapService.describeFailure(error));
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
        try {
            DatabaseBootstrapService.DatabaseCheck check = new DatabaseBootstrapService().check();
            Throwable startupError = DatabaseConnection.getLastInitializationError();
            if (!check.connected() && startupError != null) {
                return check.message() + "\n\n启动自检提示："
                        + DatabaseBootstrapService.describeFailure(startupError);
            }
            return check.message();
        } catch (RuntimeException e) {
            return "读取数据库配置失败：" + DatabaseBootstrapService.describeFailure(e);
        }
    }

    private static void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(POSTGRES_DOWNLOAD_URL));
                return;
            }
        } catch (Exception ignored) {
            // 下面给出可复制地址，避免静默失败。
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("无法自动打开浏览器");
        alert.setHeaderText("请手动访问 PostgreSQL 官方下载页");
        alert.setContentText(POSTGRES_DOWNLOAD_URL);
        alert.showAndWait();
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
