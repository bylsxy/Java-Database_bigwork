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
import java.util.Locale;
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
            return new DatabaseCheck(false, false, "数据库连接失败：" + describeFailure(e));
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
            return new BootstrapResult(false, "数据库一键初始化失败：" + describeFailure(e));
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
            throw new RuntimeException("执行 schema.sql 失败: " + describeFailure(e), e);
        }
    }

    public static String describeFailure(Throwable error) {
        if (error == null) {
            return "未知错误。";
        }
        String detail = rootMessage(error);
        String hint = recoveryHint(error);
        if (hint.isBlank()) {
            return detail;
        }
        return detail + "\n\n处理建议：" + hint;
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

    private static String conciseSqlError(SQLException e) {
        String state = e.getSQLState() == null ? "" : " SQLState=" + e.getSQLState();
        return e.getMessage() + state;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof SQLException sqlException) {
            return conciseSqlError(sqlException);
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static String recoveryHint(Throwable error) {
        String message = collectMessages(error).toLowerCase(Locale.ROOT);
        String sqlState = firstSqlState(error);

        if ("28P01".equals(sqlState) || message.contains("password authentication failed")) {
            return "数据库密码可能为空、未知或填写错误。请在向导的密码框重新输入安装 PostgreSQL 时设置的 postgres 密码，先点“保存配置”，再点“检测连接”或“一键创建数据库并初始化”。如果确实忘记了密码，需要先在 PostgreSQL/pgAdmin 中重置 postgres 用户密码。";
        }
        if ("3D000".equals(sqlState) || message.contains("database") && message.contains("does not exist")) {
            return "目标数据库还不存在。确认 JDBC URL 最后一段是 image_manager，然后点击“一键创建数据库并初始化”。";
        }
        if (message.contains("connection refused")
                || message.contains("connect timed out")
                || message.contains("the connection attempt failed")
                || message.contains("connection to") && message.contains("refused")) {
            return "PostgreSQL 可能未安装、服务未启动，或端口不是 5432。请点击向导里的“一键打开 PostgreSQL 安装页”完成安装，或在 Windows 服务中启动 PostgreSQL 后重试。";
        }
        if ("42501".equals(sqlState) || message.contains("permission denied")) {
            return "当前数据库用户权限不足。请使用 postgres 管理员账号，或给当前用户授予建库和 public schema 建表权限。";
        }
        if (message.contains("no suitable driver") || message.contains("org.postgresql.driver")) {
            return "运行包缺少 PostgreSQL JDBC 驱动。请使用本次重新生成的便携版 exe 或 fat jar，不要只复制单个旧 jar。";
        }
        if (message.contains("unknownhostexception") || message.contains("name or service not known")) {
            return "JDBC URL 中的主机名无法解析。新电脑本机数据库一般使用 jdbc:postgresql://localhost:5432/image_manager。";
        }
        return "请确认 PostgreSQL 已安装并正在运行，JDBC URL、用户名、密码和端口正确；密码不确定时可直接在向导中重新填写并保存。";
    }

    private static String firstSqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return "";
    }

    private static String collectMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
