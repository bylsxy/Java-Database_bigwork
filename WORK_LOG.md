# 工作日志 — 基于 PostgreSQL 的数字图像集成管理系统

> 本文件用于记录项目进度，确保协作和上下文不丢失。

---

## 2026-04-03 项目启动

### 已确认配置
| 配置项 | 值 |
|---|---|
| Java | OpenJDK 26 (Temurin) |
| Maven | 3.9.14 |
| JavaFX | 25 (LTS, org.openjfx) |
| PostgreSQL | 18.3, localhost:5432, DB=image_manager, user=postgres, pwd=1234 |
| 架构 | 纯 JavaFX + JDBC（三层架构：DAO → Service → Controller） |
| 连接池 | HikariCP |

### 团队信息
| 角色 | 姓名 | 学号 |
|---|---|---|
| 组长 | 毕振岚 | 202425220501 |
| 组员1 | 陈厚华 | 202425220502 |
| 组员2 | 徐阳 | 202425220527 |

### 当前进度
- [x] 阅读完整课设指导文档
- [x] 确认技术栈和配置
- [x] 创建实施计划
- [x] Phase 1: 项目搭建（pom.xml, 目录结构）
- [x] Phase 2: 工程文档（需求分析、概要设计、详细设计）
- [x] Phase 3: 数据库脚本（schema.sql, data.sql）
- [x] Phase 4: Java 代码实现（17个源文件，编译通过）
- [ ] Phase 5: 集成测试与 UI 校验

### 接口约定
#### 数据库连接
```java
// DatabaseConnection 提供 HikariCP 连接池
// 配置文件位于 src/main/resources/config/database.properties
DatabaseConnection.getConnection() → java.sql.Connection
```

#### DAO 层接口
```java
// ImageDao: CRUD 操作 + 批量重命名 + 逻辑/物理删除
// DirectoryDao: 目录树递归查询 + 目录 CRUD
```

#### Service 层接口
```java
// ImageService: 业务逻辑 + 事务管理
// DirectoryService: 目录浏览 + 统计
```

### 待办事项
1. 创建 Maven 项目结构
2. 编写三份工程文档
3. 实现数据库脚本
4. 开发 Java 应用
