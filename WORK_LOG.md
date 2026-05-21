# 工作日志 — 基于 PostgreSQL 的数字图像集成管理系统

> 本文件用于记录项目进度，确保协作和上下文不丢失。

---

## 2026-04-03 项目启动

### 已确认配置
| 配置项 | 值 |
|---|---|
| Java | Java 21 编译目标，JDK 21+ 可运行；当前 Windows 双击 JAR 关联到 JDK 26 |
| Maven | 3.9.14 |
| JavaFX | 21.0.6 (org.openjfx) |
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
- [x] Phase 4: Java 代码实现（45个源文件，编译通过）
- [x] Phase 5: 集成测试与 UI 校验

### 2026-05-21 稳定性与体验修复
- [x] AI 默认 Base URL 统一为 `https://cpa.ystone.top/v1`，API Key 优先读取环境变量，源码和提交包不保存密钥。
- [x] 设置页模型改为从兼容 `/models` 接口自动获取并下拉选择，不再要求手填模型名称。
- [x] 首次向导中的“打开设置页面配置 AI API”已接入主窗口设置页，不再只打印日志。
- [x] 首次向导改为可滚动、可动态限高，选择目录后底部确定/取消按钮不会被挤出屏幕。
- [x] AI 扫描不再随每次启动自动触发；只有首次向导确认、设置页保存目录变更或点击“扫描当前目录”才会启动。
- [x] 切换目录时会取消旧扫描任务，待识别 SQL 限定在当前扫描根目录下，避免继续识别上一个目录。
- [x] 单次 AI 批处理上限改为设置项，默认 100(max)，可在设置页调整；状态栏显示目录总数、本批数量和待识别数量。
- [x] 主界面增加“停止扫描”和“清理AI标签”，清理前会显示标签数据位置、占用大小和保留时的空间浪费。
- [x] 控制台中文日志按 GBK 输出，日志文件仍保持 UTF-8，减轻 Windows 控制台乱码问题。

### 2026-05-21 Git 提交整理
- [x] 新增 `.gitignore`，排除 Maven 构建产物、运行日志、本机环境变量文件和常见 IDE 元数据。
- [x] 新增 `.gitattributes`，明确源码文本换行和文档/图片/压缩包等二进制文件类型。
- [x] 从版本控制中移除 `target/` 与 `logs/` 生成内容，保留本地文件，后续提交只包含源码、SQL、资源和课程文档。

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
