package com.imagemanager.dao;

import com.imagemanager.model.DirectoryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 目录数据访问实现 — 封装所有针对 directories 表的 SQL 操作。
 * <p>
 * 核心亮点：使用 PostgreSQL 的 WITH RECURSIVE CTE 实现目录树递归查询。
 */
public class DirectoryDaoImpl implements DirectoryDao {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryDaoImpl.class);

    // ==================== SQL 常量 ====================

    /** 查询子目录 */
    private static final String SQL_FIND_CHILDREN =
            "SELECT id, dir_name, dir_path, parent_id, created_at " +
            "FROM directories WHERE parent_id = ? ORDER BY dir_name";

    /** 查询根目录 */
    private static final String SQL_FIND_ROOTS =
            "SELECT id, dir_name, dir_path, parent_id, created_at " +
            "FROM directories WHERE parent_id IS NULL ORDER BY dir_name";

    /** 按路径查找 */
    private static final String SQL_FIND_BY_PATH =
            "SELECT id, dir_name, dir_path, parent_id, created_at " +
            "FROM directories WHERE dir_path = ?";

    /** 按 ID 查找 */
    private static final String SQL_FIND_BY_ID =
            "SELECT id, dir_name, dir_path, parent_id, created_at " +
            "FROM directories WHERE id = ?";

    /** 插入目录 */
    private static final String SQL_INSERT =
            "INSERT INTO directories (dir_name, dir_path, parent_id) " +
            "VALUES (?, ?, ?) RETURNING id";

    /** 递归查询所有后代目录（WITH RECURSIVE CTE）*/
    private static final String SQL_FIND_DESCENDANTS =
            "WITH RECURSIVE dir_tree AS (" +
            "    SELECT id, dir_name, dir_path, parent_id, created_at " +
            "    FROM directories WHERE id = ? " +
            "    UNION ALL " +
            "    SELECT d.id, d.dir_name, d.dir_path, d.parent_id, d.created_at " +
            "    FROM directories d " +
            "    INNER JOIN dir_tree dt ON d.parent_id = dt.id" +
            ") SELECT * FROM dir_tree ORDER BY dir_path";

    // ==================== 接口实现 ====================

    @Override
    public List<DirectoryNode> findChildren(int parentId) {
        var children = new ArrayList<DirectoryNode>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_CHILDREN)) {

            stmt.setInt(1, parentId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    children.add(mapRowToDirectoryNode(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("查询子目录失败 (parentId={}): {}", parentId, e.getMessage());
            throw new RuntimeException("查询子目录失败", e);
        }
        return children;
    }

    @Override
    public List<DirectoryNode> findRootDirectories() {
        var roots = new ArrayList<DirectoryNode>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_ROOTS);
             var rs = stmt.executeQuery()) {

            while (rs.next()) {
                roots.add(mapRowToDirectoryNode(rs));
            }

        } catch (SQLException e) {
            logger.error("查询根目录失败: {}", e.getMessage());
            throw new RuntimeException("查询根目录失败", e);
        }
        return roots;
    }

    @Override
    public DirectoryNode findOrCreate(String dirPath) {
        // 先尝试查找
        Optional<DirectoryNode> existing = findByPath(dirPath);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 不存在则创建（需要确保父目录链也存在）
        File dir = new File(dirPath);
        String dirName = dir.getName();
        if (dirName.isEmpty()) {
            // 磁盘根目录，如 "C:\"
            dirName = dirPath.replace("\\", "").replace("/", "");
        }

        // 递归确保父目录存在
        Integer parentId = null;
        String parentPath = dir.getParent();
        if (parentPath != null) {
            DirectoryNode parentNode = findOrCreate(parentPath);
            parentId = parentNode.id();
        }

        // 创建当前目录
        var newNode = new DirectoryNode(0, dirName, dirPath, parentId, LocalDateTime.now());
        int newId = insert(newNode);

        logger.debug("创建目录记录: id={}, path={}", newId, dirPath);
        return new DirectoryNode(newId, dirName, dirPath, parentId, LocalDateTime.now());
    }

    @Override
    public Optional<DirectoryNode> findByPath(String dirPath) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_BY_PATH)) {

            stmt.setString(1, dirPath);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToDirectoryNode(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("按路径查找目录失败: {}", e.getMessage());
            throw new RuntimeException("查找目录失败", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<DirectoryNode> findById(int directoryId) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_BY_ID)) {

            stmt.setInt(1, directoryId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToDirectoryNode(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("按ID查找目录失败 (id={}): {}", directoryId, e.getMessage());
            throw new RuntimeException("查找目录失败", e);
        }
        return Optional.empty();
    }

    @Override
    public List<DirectoryNode> findDescendants(int directoryId) {
        var descendants = new ArrayList<DirectoryNode>();
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_FIND_DESCENDANTS)) {

            stmt.setInt(1, directoryId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    descendants.add(mapRowToDirectoryNode(rs));
                }
            }
            logger.debug("递归查询目录 {} 的后代: 共 {} 个", directoryId, descendants.size());

        } catch (SQLException e) {
            logger.error("递归查询后代目录失败 (id={}): {}", directoryId, e.getMessage());
            throw new RuntimeException("递归查询目录失败", e);
        }
        return descendants;
    }

    @Override
    public int insert(DirectoryNode node) {
        try (var conn = DatabaseConnection.getConnection();
             var stmt = conn.prepareStatement(SQL_INSERT)) {

            stmt.setString(1, node.dirName());
            stmt.setString(2, node.dirPath());
            if (node.parentId() != null) {
                stmt.setInt(3, node.parentId());
            } else {
                stmt.setNull(3, Types.INTEGER);
            }

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            throw new SQLException("INSERT 未返回生成的 ID");

        } catch (SQLException e) {
            // 可能是唯一约束冲突（并发创建同一目录）
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                // 唯一约束冲突，尝试再次查询
                return findByPath(node.dirPath())
                        .map(DirectoryNode::id)
                        .orElseThrow(() -> new RuntimeException("目录创建冲突", e));
            }
            logger.error("插入目录 {} 失败: {}", node.dirPath(), e.getMessage());
            throw new RuntimeException("插入目录失败", e);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 ResultSet 当前行映射为 DirectoryNode record。
     */
    private DirectoryNode mapRowToDirectoryNode(ResultSet rs) throws SQLException {
        Integer parentId = rs.getInt("parent_id");
        if (rs.wasNull()) {
            parentId = null;
        }

        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime createdAt = (ts != null) ? ts.toLocalDateTime() : LocalDateTime.now();

        return new DirectoryNode(
                rs.getInt("id"),
                rs.getString("dir_name"),
                rs.getString("dir_path"),
                parentId,
                createdAt
        );
    }
}
