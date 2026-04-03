package com.imagemanager.dao;

import com.imagemanager.model.DirectoryNode;

import java.util.List;
import java.util.Optional;

/**
 * 目录数据访问接口 — 定义对 directories 表的所有数据库操作。
 * <p>
 * 包含递归查询目录树的方法，利用 PostgreSQL 的 WITH RECURSIVE CTE 实现。
 */
public interface DirectoryDao {

    /**
     * 查询指定目录的所有直接子目录（一级子目录）。
     *
     * @param parentId 父目录的数据库 ID
     * @return 子目录列表，按目录名排序
     */
    List<DirectoryNode> findChildren(int parentId);

    /**
     * 查询所有根目录（parent_id 为 NULL 的记录）。
     * 对应磁盘的根分区，如 C:\, D:\。
     *
     * @return 根目录列表
     */
    List<DirectoryNode> findRootDirectories();

    /**
     * 根据路径查找目录。如果该路径在数据库中不存在，
     * 则自动创建一条新记录（包括必要的父目录链）。
     * <p>
     * 这是一个 "find or create" 模式，保证调用后该路径一定存在于数据库中。
     *
     * @param dirPath 目录的完整路径
     * @return 对应的目录节点
     */
    DirectoryNode findOrCreate(String dirPath);

    /**
     * 根据路径查找目录（不自动创建）。
     *
     * @param dirPath 目录的完整路径
     * @return 包含目录的 Optional，不存在时为 empty
     */
    Optional<DirectoryNode> findByPath(String dirPath);

    /**
     * 根据 ID 查找目录。
     *
     * @param directoryId 目录 ID
     * @return 包含目录的 Optional
     */
    Optional<DirectoryNode> findById(int directoryId);

    /**
     * 递归查询指定目录及其所有后代目录（利用 WITH RECURSIVE CTE）。
     * 用于统计整个目录树下的图片信息。
     *
     * @param directoryId 根目录 ID
     * @return 该目录及所有后代目录的列表
     */
    List<DirectoryNode> findDescendants(int directoryId);

    /**
     * 插入一条新的目录记录。
     *
     * @param node 要插入的目录节点
     * @return 数据库生成的自增 ID
     */
    int insert(DirectoryNode node);
}
