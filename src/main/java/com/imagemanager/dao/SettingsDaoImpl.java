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

    @Override
    public Optional<AppSetting> findByKey(String key) {
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
            logger.debug("设置已更新: {} = {}", key, value);
        } catch (SQLException e) {
            logger.error("更新设置失败: key={}, value={}", key, value, e);
        }
    }

    @Override
    public void delete(String key) {
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

    private AppSetting mapRow(ResultSet rs) throws SQLException {
        return new AppSetting(
                rs.getString("key"),
                rs.getString("value"),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
