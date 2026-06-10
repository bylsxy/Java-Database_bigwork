package com.imagemanager.dao;

import com.imagemanager.model.AppSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用设置 DAO 实现 — 使用 JDBC 操作 app_settings 表。
 */
public class SettingsDaoImpl implements SettingsDao {

    private static final Logger logger = LoggerFactory.getLogger(SettingsDaoImpl.class);
    private static volatile boolean schemaReady = false;

    @Override
    public Optional<AppSetting> findByKey(String key) {
        ensureSchema();
        String sql = "SELECT key, value, updated_at FROM app_settings WHERE key = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询设置失败: key={}", key, e);
        }
        return Optional.empty();
    }

    @Override
    public List<AppSetting> findAll() {
        ensureSchema();
        String sql = "SELECT key, value, updated_at FROM app_settings ORDER BY key";
        List<AppSetting> settings = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                settings.add(mapRow(rs));
            }
        } catch (SQLException e) {
            logger.error("查询所有设置失败", e);
        }
        return settings;
    }

    @Override
    public void upsert(String key, String value) {
        ensureSchema();
        String sql = """
                INSERT INTO app_settings (key, value, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            logger.debug("设置已更新: {} = {}", key, safeLogValue(key, value));
        } catch (SQLException e) {
            logger.error("更新设置失败: key={}, value={}", key, safeLogValue(key, value), e);
        }
    }

    @Override
    public void delete(String key) {
        ensureSchema();
        String sql = "DELETE FROM app_settings WHERE key = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("删除设置失败: key={}", key, e);
        }
    }

    @Override
    public String getValueOrDefault(String key, String defaultValue) {
        return findByKey(key).map(AppSetting::value).orElse(defaultValue);
    }

    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (SettingsDaoImpl.class) {
            if (schemaReady) {
                return;
            }
            String createSql = """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        key VARCHAR(100) PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                    )
                    """;
            String defaultsSql = """
                    INSERT INTO app_settings (key, value) VALUES
                        ('scan_directory', ''),
                        ('show_welcome', 'true'),
                        ('thumbnail_storage', 'database'),
                        ('slideshow_interval', '3'),
                        ('slideshow_order', 'SEQUENTIAL'),
                        ('slideshow_music', 'none'),
                        ('theme_background_path', ''),
                        ('theme_background_opacity', '0.35')
                    ON CONFLICT (key) DO NOTHING
                    """;
            try (Connection conn = DatabaseConnection.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(createSql);
                stmt.execute(defaultsSql);
                schemaReady = true;
            } catch (SQLException e) {
                logger.error("初始化设置表失败", e);
            }
        }
    }

    private AppSetting mapRow(ResultSet rs) throws SQLException {
        return new AppSetting(
                rs.getString("key"),
                rs.getString("value"),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private String safeLogValue(String key, String value) {
        if (key != null && key.toLowerCase().contains("key")) {
            return value == null || value.isBlank() ? "" : "******";
        }
        return value;
    }
}
