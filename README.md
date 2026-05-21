# 基于 PostgreSQL 的数字图像集成管理系统

> 本项目结合《面向对象程序设计》（Java）和《数据库系统》课程要求，使用 JavaFX + PostgreSQL 开发的数字图片管理桌面应用。

## 团队（面向对象程序设计实践第07组）

| 角色 | 姓名 | 学号 |
|---|---|---|
| 组长 | 毕振岚 | 202425220501 |
| 组员 | 陈厚华 | 202425220502 |
| 组员 | 徐阳 | 202425220527 |

## 技术栈

- **语言**: Java 21 编译目标（JDK 21+ 可运行，当前机器可用 JDK 26 运行）
- **GUI**: JavaFX 21.0.6
- **数据库**: PostgreSQL 18.3
- **构建**: Maven 3.9.14
- **连接池**: HikariCP 6.2.1
- **日志**: SLF4J + Logback

## 环境准备

### 1. 安装 Java

建议安装 Eclipse Temurin JDK 21 或更高版本。本项目按 Java 21 编译，当前机器已将 `.jar` 双击关联到 JDK 26 的 `javaw.exe`。

### 2. 安装 PostgreSQL 18

下载 [PostgreSQL 18](https://www.postgresql.org/download/)，安装后确保服务已启动。

### 3. 创建数据库

```bash
# 创建数据库
psql -U postgres -c "CREATE DATABASE image_manager ENCODING 'UTF8';"

# 执行建表脚本（含表、索引、视图、触发器、存储过程）
psql -U postgres -d image_manager -f sql/schema.sql

# 导入测试数据（不用每次都导入）
psql -U postgres -d image_manager -f sql/data.sql
```

### 4. 配置数据库连接

编辑 `src/main/resources/config/database.properties`：

```properties
db.url=jdbc:postgresql://localhost:5432/image_manager
db.username=postgres
db.password=1234
```

## 构建与运行

```bash
# 编译
mvn compile

# 运行应用
mvn javafx:run

# 打包 JAR
mvn package

# 双击使用的课程提交 JAR
docs/面向对象程序与设计/我们的实际写作/面向对象程序设计实践目标代码.JAR
```

## 版本管理说明

- `target/` 为 Maven 构建产物，不进入 Git；需要时执行 `mvn package` 重新生成。
- `logs/` 为本机运行日志，不进入 Git；日志格式由 `src/main/resources/logback.xml` 控制。
- 数据库和 AI 配置支持环境变量覆盖，避免把个人密钥写入源码。

## 项目结构

```
├── docs/                    # 工程文档
│   ├── 需求分析.md
│   ├── 概要设计.md
│   └── 详细设计.md
├── sql/                     # 数据库脚本
│   ├── schema.sql          # 建库脚本（表+索引+视图+触发器+存储过程）
│   └── data.sql            # 测试数据
├── src/main/java/com/imagemanager/
│   ├── App.java            # 应用入口
│   ├── model/              # 数据模型（Record 类）
│   ├── dao/                # 数据访问层（JDBC + PreparedStatement）
│   ├── service/            # 业务逻辑层（事务管理）
│   ├── controller/         # JavaFX 控制器
│   └── util/               # 工具类
├── src/main/resources/
│   ├── fxml/               # FXML 布局文件
│   ├── css/                # CSS 样式表
│   └── config/             # 配置文件
├── pom.xml                 # Maven 配置
├── WORK_LOG.md             # 开发日志
└── README.md               # 本文件
```

## 功能特性

- ✅ 目录树导航（磁盘浏览、懒加载）
- ✅ 缩略图预览（等比缩放、数据库缓存）
- ✅ 图片选择（单选、Ctrl+多选、框选）
- ✅ 图片删除（二次确认、逻辑删除）
- ✅ 图片复制/粘贴（跨目录、自动处理重名）
- ✅ 图片重命名（单张直接改名、多张批量重命名）
- ✅ 幻灯片播放（大图展示、前后切换、放大缩小、自动播放）
- ✅ 数据库持久化（bytea 缩略图、B-Tree 索引、递归 CTE 目录树）
- ✅ 触发器自动日志（INSERT/UPDATE/DELETE 操作自动记录）
- ✅ 存储过程报表（按月统计、目录空间分析）
- ✅ AI 识别配置（默认 CPA 代理节点，密钥优先读取环境变量，模型从 `/models` 下拉选择）
- ✅ AI 扫描安全控制（单批上限可在设置页调整，界面统一显示为 N(max)，可停止扫描并清理数据库中的 AI 标签）
