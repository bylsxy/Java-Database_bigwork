package com.imagemanager.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 数据库连接管理器 — 基于 HikariCP 高性能连接池。
 * <p>
 * 采用单例模式（通过静态字段），整个应用共享唯一的连接池实例。
 * <p>
 * <b>生命周期：</b>
 * <ol>
 *   <li>应用启动时调用 {@link #initialize()} 创建连接池</li>
 *   <li>运行时通过 {@link #getConnection()} 获取连接（必须用 try-with-resources）</li>
 *   <li>应用退出时调用 {@link #close()} 销毁连接池</li>
 * </ol>
 * <p>
 * <b>线程安全性：</b> HikariDataSource 本身是线程安全的，多线程可同时调用 getConnection()。
 */
public final class DatabaseConnection {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnection.class);

    /** 配置文件路径（classpath 内） */
    private static final String CONFIG_FILE = "/config/database.properties";

    /** HikariCP 数据源（连接池），整个应用唯一实例 */
    private static HikariDataSource dataSource;
    private static Throwable lastInitializationError;

    public record DatabaseConfig(String url, String username, String password) {}

    // 禁止实例化 — 所有方法均为静态的
    private DatabaseConnection() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 初始化数据库连接池。
     * <p>
     * 从 classpath 中读取 database.properties，配置 HikariCP 并创建连接池。
     * 如果连接池已初始化，则跳过。
     *
     * @throws RuntimeException 如果配置文件读取失败或连接池创建失败
     */
    public static synchronized void initialize() {
        if (dataSource != null) {
            logger.warn("数据库连接池已经初始化过了，跳过重复初始化");
            return;
        }

        try {
            // 读取配置文件
            Properties props = loadProperties();

            // 配置 HikariCP
            var config = new HikariConfig();
            DatabaseConfig databaseConfig = configuredDatabase();
            config.setJdbcUrl(databaseConfig.url());
            config.setUsername(databaseConfig.username());
            config.setPassword(databaseConfig.password());

            // 连接池参数
            config.setMaximumPoolSize(
                    Integer.parseInt(props.getProperty("db.pool.maximumPoolSize", "10"))
            );
            config.setMinimumIdle(
                    Integer.parseInt(props.getProperty("db.pool.minimumIdle", "2"))
            );
            config.setConnectionTimeout(
                    Math.min(Long.parseLong(props.getProperty("db.pool.connectionTimeout", "5000")), 5000)
            );
            config.setIdleTimeout(
                    Long.parseLong(props.getProperty("db.pool.idleTimeout", "600000"))
            );

            // 连接池名称（方便日志排查）
            config.setPoolName("DIMS-HikariPool");

            // PostgreSQL 特定优化
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");

            // 创建连接池
            dataSource = new HikariDataSource(config);
            lastInitializationError = null;

            logger.info("数据库连接池初始化成功 [URL={}] [池大小={}]",
                    databaseConfig.url(),
                    config.getMaximumPoolSize());

        } catch (Exception e) {
            dataSource = null;
            lastInitializationError = e;
            logger.error("数据库连接池初始化失败", e);
            throw new RuntimeException("无法初始化数据库连接: " + e.getMessage(), e);
        }
    }

    public static synchronized boolean tryInitialize() {
        try {
            initialize();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static synchronized void reinitialize() {
        close();
        initialize();
    }

    /**
     * 从连接池获取一个数据库连接。
     * <p>
     * <b>重要：</b> 调用方必须使用 try-with-resources 确保连接在使用后归还连接池：
     * <pre>{@code
     * try (var conn = DatabaseConnection.getConnection()) {
     *     // 使用 conn 执行 SQL
     * }
     * }</pre>
     *
     * @return 一个可用的数据库连接
     * @throws SQLException 如果连接池未初始化或获取连接超时
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("数据库连接池尚未初始化，请先调用 initialize()");
        }
        return dataSource.getConnection();
    }

    /**
     * 关闭并销毁连接池，释放所有数据库连接资源。
     * <p>
     * 应在应用退出时调用。重复调用是安全的（幂等）。
     */
    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
            logger.info("数据库连接池已关闭");
        }
    }

    /**
     * 判断连接池是否已初始化且可用。
     */
    public static boolean isInitialized() {
        return dataSource != null && !dataSource.isClosed();
    }

    public static Throwable getLastInitializationError() {
        return lastInitializationError;
    }

    public static DatabaseConfig configuredDatabase() {
        try {
            Properties props = loadProperties();
            return new DatabaseConfig(
                    configValue(props, "db.url", "DIMS_DB_URL"),
                    configValue(props, "db.username", "DIMS_DB_USERNAME"),
                    configValue(props, "db.password", "DIMS_DB_PASSWORD")
            );
        } catch (IOException e) {
            throw new RuntimeException("无法读取数据库配置: " + e.getMessage(), e);
        }
    }

    public static synchronized void saveUserConfig(String jdbcUrl, String username, String password) {
        try {
            Properties props = loadProperties();
            props.setProperty("db.url", jdbcUrl == null ? "" : jdbcUrl.trim());
            props.setProperty("db.username", username == null ? "" : username.trim());
            props.setProperty("db.password", password == null ? "" : password);
            Path configPath = userConfigPath();
            Files.createDirectories(configPath.getParent());
            try (OutputStream output = Files.newOutputStream(configPath)) {
                props.store(output, "Digital Image Manager local database configuration");
            }
            close();
            logger.info("数据库连接配置已保存到 {}", configPath);
        } catch (IOException e) {
            throw new RuntimeException("保存数据库配置失败: " + e.getMessage(), e);
        }
    }

    public static Path userConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path baseDir = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".dims")
                : Path.of(localAppData, "DigitalImageManager");
        return baseDir.resolve("database.properties");
    }

    /**
     * 从 classpath 加载 database.properties 配置文件。
     */
    private static Properties loadProperties() throws IOException {
        var props = new Properties();
        try (InputStream input = DatabaseConnection.class.getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                throw new IOException("找不到配置文件: " + CONFIG_FILE);
            }
            props.load(input);
        }
        Path userConfig = userConfigPath();
        if (Files.isRegularFile(userConfig)) {
            try (InputStream input = Files.newInputStream(userConfig)) {
                props.load(input);
            }
        }
        return props;
    }

    private static String configValue(Properties props, String propertyName, String envName) {
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return props.getProperty(propertyName);
    }
}
