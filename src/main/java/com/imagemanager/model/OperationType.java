package com.imagemanager.model;

/**
 * 操作类型枚举 — 对应 operation_logs 表的 operation_type 字段。
 * <p>
 * 明确枚举所有合法的操作类型，取代魔法字符串。
 * 每个枚举值有一个中文显示名称，方便在界面上展示。
 */
public enum OperationType {

    INSERT("新增"),
    RENAME("重命名"),
    DELETE("逻辑删除"),
    HARD_DELETE("物理删除"),
    COPY("复制"),
    PASTE("粘贴");

    /** 中文显示名称，用于界面和日志 */
    private final String displayName;

    OperationType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取操作类型的中文显示名称。
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 从数据库中的字符串值转换为枚举。
     * 使用 Pattern Matching 的理念进行安全转换。
     *
     * @param dbValue 数据库中存储的字符串，如 "RENAME"
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果字符串不是已知的操作类型
     */
    public static OperationType fromDbValue(String dbValue) {
        return switch (dbValue) {
            case "INSERT"      -> INSERT;
            case "RENAME"      -> RENAME;
            case "DELETE"      -> DELETE;
            case "HARD_DELETE" -> HARD_DELETE;
            case "COPY"        -> COPY;
            case "PASTE"       -> PASTE;
            default            -> throw new IllegalArgumentException("未知操作类型: " + dbValue);
        };
    }
}
