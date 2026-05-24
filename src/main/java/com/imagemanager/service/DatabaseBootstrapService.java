package com.imagemanager.service;

import com.imagemanager.dao.DatabaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据库自检与初始化服务。
 * <p>
 * 这个服务只处理 PostgreSQL 连接、建库和执行内置 schema.sql，不依赖 JavaFX。
 */
public class DatabaseBootstrapService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBootstrapService.class);
    private static final String SCHEMA_RESOURCE = "/sql/schema.sql";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public record DatabaseCheck(boolean connected, boolean schemaReady, String message) {}

    public record BootstrapResult(boolean success, String message) {}

    public DatabaseCheck check() {
        var config = DatabaseConnection.configuredDatabase();
        try (Connection conn = openConnection(config.url(), config.username(), config.password())) {
            boolean ready = hasCoreTables(conn);
            if (ready) {
                return new DatabaseCheck(true, true, "数据库连接正常，核心表已就绪。");
            }
            return new DatabaseCheck(true, false, "数据库可以连接，但尚未初始化表结构。");
        } catch (SQLException e) {
            return new DatabaseCheck(false, false, "数据库连接失败：" + conciseSqlError(e));
        }
    }

    public BootstrapResult createDatabaseAndSchema() {
        var config = DatabaseConnection.configuredDatabase();
        String databaseName;
        try {
            databaseName = databaseNameFromJdbcUrl(config.url());
        } catch (IllegalArgumentException e) {
            return new BootstrapResult(false, e.getMessage());
        }

        try {
            ensureDatabaseExists(config, databaseName);
            DatabaseConnection.reinitialize();
            runSchemaScript();
            DatabaseConnection.reinitialize();
            return new BootstrapResult(true, "数据库 " + databaseName + " 已创建/确认存在，表结构已初始化。");
        } catch (Exception e) {
            logger.error("数据库一键初始化失败", e);
            return new BootstrapResult(false, "数据库一键初始化失败：" + rootMessage(e));
        }
    }

    public void runSchemaScript() {
        String sql = loadSchemaSql();
        List<String> statements = splitSqlStatements(sql);
        try (Connection conn = DatabaseConnection.getConnection();
             var stmt = conn.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isBlank()) {
                    stmt.execute(trimmed);
                }
            }
            logger.info("内置数据库 schema 初始化完成，执行 {} 条 SQL", statements.size());
        } catch (SQLException e) {
            throw new RuntimeException("执行 schema.sql 失败: " + conciseSqlError(e), e);
        }
    }

    private void ensureDatabaseExists(DatabaseConnection.DatabaseConfig config, String databaseName) throws SQLException {
        if (!SAFE_IDENTIFIER.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("数据库名不安全或不受支持: " + databaseName);
        }
        String maintenanceUrl = maintenanceJdbcUrl(config.url());
        try (Connection conn = openConnection(maintenanceUrl, config.username(), config.password())) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, databaseName);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE DATABASE " + quoteIdentifier(databaseName) + " ENCODING 'UTF8'");
            }
        }
    }

    private boolean hasCoreTables(Connection conn) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('directories', 'images', 'app_settings', 'tag_categories')
                """;
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) == 4;
        }
    }

    private Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private String loadSchemaSql() {
        try (var input = DatabaseBootstrapService.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("JAR 内未找到 " + SCHEMA_RESOURCE);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("读取内置 schema.sql 失败: " + e.getMessage(), e);
        }
    }

    private List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (lineComment) {
                current.append(c);
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (!singleQuoted && !doubleQuoted && c == '-' && next == '-') {
                current.append(c).append(next);
                i++;
                lineComment = true;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && c == '/' && next == '*') {
                current.append(c).append(next);
                i++;
                blockComment = true;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && c == '$') {
                String tag = readDollarTag(sql, i);
                if (tag != null) {
                    current.append(tag);
                    i += tag.length() - 1;
                    dollarTag = tag;
                    continue;
                }
            }
            if (!doubleQuoted && c == '\'') {
                current.append(c);
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (!singleQuoted && c == '"') {
                doubleQuoted = !doubleQuoted;
                current.append(c);
                continue;
            }
            if (!singleQuoted && !doubleQuoted && c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        if (!current.toString().trim().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private String readDollarTag(String sql, int start) {
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(start, end + 1);
        if ("$$".equals(tag) || tag.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$")) {
            return tag;
        }
        return null;
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        String prefix = "jdbc:postgresql://";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("当前只支持形如 jdbc:postgresql://localhost:5432/image_manager 的连接地址。");
        }
        int slash = jdbcUrl.indexOf('/', prefix.length());
        if (slash < 0 || slash == jdbcUrl.length() - 1) {
            throw new IllegalArgumentException("JDBC 地址里没有数据库名: " + jdbcUrl);
        }
        String rest = jdbcUrl.substring(slash + 1);
        int queryIndex = rest.indexOf('?');
        return queryIndex >= 0 ? rest.substring(0, queryIndex) : rest;
    }

    private String maintenanceJdbcUrl(String jdbcUrl) {
        String databaseName = databaseNameFromJdbcUrl(jdbcUrl);
        int nameIndex = jdbcUrl.indexOf(databaseName);
        return jdbcUrl.substring(0, nameIndex) + "postgres" + jdbcUrl.substring(nameIndex + databaseName.length());
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String conciseSqlError(SQLException e) {
        String state = e.getSQLState() == null ? "" : " SQLState=" + e.getSQLState();
        return e.getMessage() + state;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
