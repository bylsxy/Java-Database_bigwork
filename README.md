# 基于 PostgreSQL 的数字图像集成管理系统

本项目是一个 JavaFX + PostgreSQL 桌面端数字图像管理系统，同时服务于《面向对象程序设计实践》和《数据库系统基础》两门课程的课程设计。程序围绕本地图片目录展开，提供目录扫描、缩略图管理、图片查看、批量操作、幻灯片播放、图片编辑、版本历史、AI 标签识别、智能搜索和数据库初始化引导等功能。

系统定位是单机桌面应用。数据库用于保存目录、图片元数据、缩略图、标签、AI 分析结果、搜索历史、版本历史和操作日志；AI、WebDAV 等网络能力均为可选增强，不影响基础本地图片浏览。

## 团队

面向对象程序设计实践第 07 组。

| 角色 | 姓名 | 学号 |
| --- | --- | --- |
| 组长 | 毕振岚 | 202425220501 |
| 组员 | 陈厚华 | 202425220502 |
| 组员 | 徐阳 | 202425220527 |

## 近期更新

| 日期 | 更新内容 |
| --- | --- |
| 2026-06-12 | 修复了ui显示问题，解决了侧边栏目录显示问题，新增文件夹功能，重做了项目打包方案；优化目录树懒加载和右键菜单，补齐扫描入库图片分辨率，并对旧版未知分辨率记录做一次性后台修复。 |
| 2026-06-15 | 重做图片删除语义：删除不再直接移除原文件，而是移动到原目录 `.versions/.trash` 隐藏回收区；新增恢复中心，支持恢复删除图片并撤销最近一次粘贴或剪切移动。 |
| 2026-06-11 | 补强 AI fallback 设置：支持从默认配置和 last-good 配置恢复，保存时阻止空 fallback 覆盖已有可用配置；同步更新课程交付包。 |
| 2026-06-10 | 重写 AI 识别、智能搜索和实验文档材料，补充真实界面截图和交付说明。 |
| 2026-05-24 | 新增数据库连接与初始化向导，数据库未就绪时支持离线打开主界面，JAR 内嵌 `sql/schema.sql`。 |
| 2026-05-23 | 优化主题背景、图片查看窗口和幻灯片播放体验，补充 JavaFX Media 背景音乐能力。 |

## 课程覆盖

1. 面向对象程序设计实践部分重点体现 Java 21、JavaFX、FXML、控制器分层、DAO/Service 分层、异常处理、文件操作、图片处理、多窗口交互、后台任务、剪贴板式复制粘贴、编辑器状态管理和可运行 JAR 交付。

2. 数据库系统基础部分重点体现 PostgreSQL 表设计、主外键约束、自引用目录树、多对多标签关系、`bytea` 缩略图、删除标记与隐藏回收区、操作日志、视图、索引、触发器、存储过程、递归 CTE、事务回滚、连接池和 PreparedStatement。

3. 后续创新功能包括 OpenAI-compatible 图像识别、AI 标签体系、自然语言转 SQL 搜索、AI fallback 端点管理、扫描限流与熔断、图片版本历史、主题背景、带音乐的幻灯片播放、数据库自举向导和界面截图生成链。

## 技术栈

| 类型 | 选型 |
| --- | --- |
| 语言与运行环境 | Java 21 编译目标；JDK 21 及以上可运行 |
| 桌面界面 | JavaFX 21.0.6、FXML、CSS |
| 数据库 | PostgreSQL 18.3 |
| 构建工具 | Maven 3.9.x |
| 数据库连接 | PostgreSQL JDBC 42.7.5、HikariCP 6.2.1 |
| 日志 | SLF4J 2.0.16、Logback 1.5.15 |
| AI 与 JSON | OkHttp 4.12.0、Jackson 2.18.2 |
| 媒体播放 | JavaFX Media |
| 扩展预留 | Sardine WebDAV 依赖；当前仅作为网盘扩展基础，不作为已完成前台主流程功能 |

## 快速运行

### 1. 准备 Java

安装 JDK 21 或更高版本。项目使用 Java 21 编译，`pom.xml` 已设置 `maven.compiler.release=21`，打包后的 JAR 主类为 `com.imagemanager.Launcher`。

### 2. 准备 PostgreSQL

建议安装 PostgreSQL 18，并确保本机服务已启动。首次运行时如果应用无法连接数据库，会进入降级流程：主界面仍可打开，用户可以通过“数据库连接与初始化向导”填写连接信息、检测连接、一键创建 `image_manager` 数据库并执行内嵌的 `schema.sql`。

也可以手动执行数据库脚本：

```bash
psql -U postgres -c "CREATE DATABASE image_manager ENCODING 'UTF8';"
psql -U postgres -d image_manager -f sql/schema.sql
psql -U postgres -d image_manager -f sql/data.sql
```

`sql/data.sql` 主要用于幂等补全默认标签分类和应用设置，不是大量演示图片数据。图片数据由程序扫描本机目录后入库。

### 3. 运行和打包

```bash
mvn compile
mvn javafx:run
mvn package
```

`mvn package` 只负责生成一个最终 JAR：`target/image-manager-1.0.0.jar`。这个 JAR 已经包含运行依赖和 `sql/*.sql`，适合本机已有 Java 21 或更高版本的电脑直接运行；Maven 过程中产生的 `original-*.jar` 等中间包会在打包阶段清理，不作为交付物保留。

面向没有 Java 环境的新电脑，统一使用便携打包脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-stable.ps1
```

脚本会先执行 Maven 打包，再生成运行发布物：

```text
target/DigitalImageManager-release/image-manager-1.0.0.jar
target/DigitalImageManager-release/DigitalImageManager/DigitalImageManager.exe
target/DigitalImageManager-windows-portable.zip
```

`DigitalImageManager.exe` 位于便携目录内，已由 `jpackage` 捆绑 Java 运行时；拷贝时需要保留同目录的 `app/` 和 `runtime/`。`DigitalImageManager-windows-portable.zip` 是给老师或新电脑使用的压缩包，解压后双击 exe 即可，不需要先安装 JDK、Maven 或 JavaFX。

课程提交物与运行发布物分开管理。脚本会把最终 JAR 同步为课程要求的目标代码 JAR，位置是：

```text
docs/面向对象程序与设计/面向对象程序设计实践/2024级软件工程R5班/第07组/面向对象程序设计实践目标代码.JAR
```

## 配置文件

| 配置项 | 路径与说明 |
| --- | --- |
| 数据库模板配置 | `src/main/resources/config/database.properties`，仓库内保留模板，`db.password` 默认为空。 |
| 本机数据库配置 | Windows 为 `%LOCALAPPDATA%\DigitalImageManager\database.properties`；其他系统为 `~/.dims/database.properties`。应用内数据库向导会写入这里。 |
| 数据库环境变量 | `DIMS_DB_URL`、`DIMS_DB_USERNAME`、`DIMS_DB_PASSWORD` 优先于配置文件。 |
| AI fallback 配置 | Windows 为 `%APPDATA%\ImageManager\ai-fallbacks.json`；其他系统为 `~/.image-manager/ai-fallbacks.json`。 |
| AI 恢复配置 | 与主配置同目录的 `ai-fallbacks.default.json` 和 `ai-fallbacks.last-good.json` 可用于恢复 fallback 列表。 |
| 运行日志 | `logs/` 为本机运行日志目录，不提交到 Git。 |
| 构建产物 | `target/` 由 Maven 生成，不提交到 Git。 |

## 完整功能清单

### 启动、向导与降级

1. 应用启动时先做 PostgreSQL 连接自检。
2. 数据库连接失败时仍可打开主界面，基础本地图片浏览不被阻断。
3. 首次启动欢迎向导支持选择扫描目录。
4. 欢迎向导会预估待扫描图片数量。
5. 欢迎向导会提示 AI 扫描可能产生的调用成本。
6. 欢迎向导可直接跳转系统设置页。
7. 欢迎向导支持“下次不展示”。
8. 数据库连接与初始化向导支持填写 JDBC URL、用户名和密码。
9. 数据库向导支持保存本机外部数据库配置。
10. 数据库向导支持检测当前连接是否可用。
11. 数据库向导支持一键创建 `image_manager` 数据库。
12. 数据库向导支持执行 JAR 内嵌的 `sql/schema.sql`。
13. 数据库向导提供 PostgreSQL 官方下载入口。
14. 数据库未连接时，标签、AI、数据库搜索、版本历史等能力会降级或不可用，界面会保留基础浏览体验。

### 主界面与目录浏览

1. 左侧目录树可浏览图片目录。
2. 目录树始终显示计算机的完整磁盘树。
3. 若已配置扫描目录，目录树会自动逐级展开并选中该目录。
4. 目录树采用懒加载方式展开子目录。
5. 顶部显示当前目录路径。
6. 主区域以缩略图网格展示图片。
7. 缩略图保留等比显示，不拉伸原图。
8. 缩略图卡片显示文件名。
9. 缩略图卡片显示格式、分辨率、时间等元信息。
10. 已完成 AI 识别的图片带有可见标记。
11. 状态栏显示当前目录图片数量。
12. 状态栏显示当前目录图片总体积。
13. 状态栏显示已选图片数量和体积。
14. 状态栏显示数据库连接状态。
15. 支持 `JPG`、`JPEG`、`PNG`、`GIF`、`BMP` 图片格式。
16. 加载目录时会把图片元数据写入数据库。
17. 首次入库时生成缩略图，数据库可缓存 `bytea` 缩略图。
18. 数据库不可用时会退化为直接读取磁盘文件并展示本地图片。
19. 新图片入库时会写入真实分辨率，旧版遗留的未知分辨率记录会在数据库就绪后做一次性后台修复。

### 选择、快捷键与图片管理

1. 支持单击选择图片。
2. 支持 `Ctrl` 多选。
3. 支持框选多张图片。
4. 支持空白处取消选择。
5. 支持双击缩略图打开单图查看器。
6. 支持 `Ctrl+C` 复制选中图片到应用剪贴板。
7. 支持 `Ctrl+X` 剪切选中图片，支持 `Ctrl+V` 粘贴到目标目录。
8. 支持 `Ctrl+A` 全选当前图片。
9. 支持 `Delete` 删除选中图片。
10. 支持 `F2` 对单张图片重命名。
11. 右键菜单可以查看图片。
12. 右键菜单可以编辑图片。
13. 右键菜单可以从当前图片开始播放幻灯片。
14. 右键菜单可以管理图片标签。
15. 右键菜单可以查看图片信息。
16. 右键菜单可以打开图片所在文件夹。
17. 删除图片前会进行二次确认。
18. 删除时会把原文件移动到同目录 `.versions/.trash` 隐藏回收区，并在数据库中保留原路径、回收路径和删除时间。
19. 恢复中心可以查看回收站图片，支持恢复选中图片；若原位置已有同名文件，系统自动追加序号。
20. 恢复中心可以撤销最近一次粘贴或剪切移动，误粘贴的文件会进回收区，误剪切移动的文件会回到原目录。
21. 数据库触发器和应用层日志会记录新增、删除标记、复制、粘贴、剪切移动、恢复和撤销操作。
22. 复制图片时会记录复制操作。
23. 粘贴图片时会处理目标目录重名冲突。
24. 粘贴图片后会为新文件写入数据库记录。
25. 单张重命名会同步修改磁盘文件名和数据库文件名。
26. 批量重命名支持自定义前缀。
27. 批量重命名支持设置起始编号。
28. 批量重命名支持设置编号位数。
29. 批量重命名窗口提供新旧文件名预览。
30. 批量重命名使用事务处理数据库更新，失败时回滚已改动的文件名。

### 图片查看器

1. 单图查看器支持上一张和下一张。
2. 查看器支持放大。
3. 查看器支持缩小。
4. 查看器支持适应窗口。
5. 查看器支持 1:1 原始比例。
6. 查看器显示当前图片序号。
7. 查看器显示图片分辨率。
8. 查看器显示文件大小。
9. 查看器显示当前缩放比例。
10. 查看器可直接跳转图片编辑器。
11. 查看器可直接进入幻灯片播放。
12. 查看器会应用当前主题背景。

### 图片编辑与版本历史

1. 图片编辑器支持移动视图。
2. 图片编辑器支持裁切。
3. 图片编辑器支持画笔标注。
4. 图片编辑器支持添加文字。
5. 图片编辑器支持绘制箭头。
6. 图片编辑器支持绘制矩形。
7. 编辑器可选择标注颜色。
8. 编辑器可设置线宽。
9. 编辑器支持撤销。
10. 保存编辑结果时会写回当前图片文件。
11. 首次编辑前会保存原始版本快照。
12. 每次保存会生成新的版本文件。
13. 版本文件存放在原图目录下的 `.versions` 子目录。
14. 数据库 `image_versions` 表记录版本号、文件路径、尺寸、缩略图、编辑类型和当前版本标记。
15. 编辑器底部版本时间轴可查看历史版本。
16. 版本时间轴显示版本号和编辑类型。
17. 用户可从历史版本恢复图片。
18. 恢复版本时会复制历史版本文件覆盖当前图片。
19. 恢复后会更新数据库当前版本标记。
20. 数据库层预留 `image_edit_operations` 表用于记录更细粒度的编辑操作参数；当前前台主流程以版本快照为准。

### 幻灯片播放

1. 支持整目录幻灯片播放。
2. 支持多选图片子集播放。
3. 支持从指定图片开始播放。
4. 支持上一张和下一张。
5. 支持自动播放。
6. 支持暂停和继续。
7. 支持循环播放。
8. 支持全屏播放。
9. 支持放大、缩小、适应窗口和 1:1 显示。
10. 底部缩略图条可直接跳转到指定图片。
11. 幻灯片播放间隔可在设置页配置。
12. 播放顺序可在设置页切换为顺序播放或随机播放。
13. 背景音乐支持内置的“轻松钢琴”。
14. 背景音乐支持内置的“自然之声”。
15. 背景音乐支持内置的“柔和吉他”。
16. 背景音乐支持选择自定义本地音频文件。
17. 背景音乐采用单曲循环播放。
18. 幻灯片窗口会应用当前主题背景。

### 标签、AI 识别与扫描任务

1. AI 图像识别采用 OpenAI-compatible Chat Completions 接口。
2. 图片会以 Base64 形式发送给配置的视觉模型。
3. AI 返回结果要求为严格 JSON。
4. AI 分析会生成图片自然语言描述。
5. AI 分析会识别场景标签。
6. AI 分析会识别主要物体。
7. AI 分析会识别普通人物描述。
8. AI 分析会识别名人。
9. AI 分析会识别动作。
10. AI 分析会识别主色调。
11. AI 分析会识别情绪氛围。
12. AI 分析会识别图片中文字。
13. AI 分析会识别动物。
14. AI 分析会识别食物。
15. AI 分析会推测地点。
16. AI 分析会统计人数。
17. AI 原始 JSON 会保存到 `ai_analysis_results`。
18. AI 描述、人数和模型名称会结构化入库。
19. 标签按分类保存到 `tag_categories` 和 `tags`。
20. 图片与标签通过 `image_tags` 多对多关联。
21. 标签关联记录置信度。
22. 标签关联记录来源，可区分 AI 标签和手动标签。
23. 主界面支持对当前目录执行 AI 标签扫描。
24. 设置页保存扫描目录后可触发扫描。
25. 扫描任务第一阶段遍历目录并导入图片元数据。
26. 扫描任务第二阶段只处理当前扫描根目录下尚未完成 AI 的图片。
27. AI 未配置时会跳过第二阶段，只完成目录扫描与入库。
28. 扫描进度面板显示当前阶段。
29. 扫描进度面板显示摘要。
30. 扫描进度面板显示当前文件细节。
31. 扫描进度面板显示开始时间。
32. 扫描进度面板显示已耗时。
33. 扫描进度面板显示预计剩余时间。
34. 扫描进度面板显示平均速度。
35. 扫描进度面板显示进度条。
36. 扫描可中途停止。
37. 停止后已写入数据库的 AI 标签会保留。
38. 支持对当前目录补打 AI 标签。
39. AI 请求间隔可配置，用于限流。
40. 单批 AI 处理上限可配置，界面按 `N(max)` 显示。
41. 可查看 AI 标签和分析结果占用统计。
42. 可清理 AI 标签、AI 分析结果、孤立标签和相关日志。
43. 清理 AI 数据不会删除原始图片文件。

### 搜索

1. 主界面提供“关键词”和“AI智能”两种搜索模式。
2. 关键词搜索支持当前目录及子目录范围。
3. 关键词搜索匹配文件名。
4. 关键词搜索匹配目录名和目录路径。
5. 关键词搜索匹配图片格式。
6. 关键词搜索匹配分辨率。
7. 关键词搜索匹配文件大小。
8. 关键词搜索匹配日期。
9. 关键词搜索匹配文件哈希。
10. 关键词搜索匹配标签名称。
11. 关键词搜索匹配标签分类。
12. 关键词搜索匹配 AI 描述。
13. 关键词搜索匹配 AI 原始返回文本。
14. 关键词搜索匹配人数。
15. 关键词搜索匹配模型名称。
16. 关键词搜索支持同义词扩展，例如海边、海滩、沙滩、海岸等。
17. 关键词搜索支持人物、人像、合影等口语词扩展。
18. 关键词搜索支持汽车、车辆、车等口语词扩展。
19. 关键词搜索支持夜晚、夜景等口语词扩展。
20. 关键词搜索会压缩空格、下划线、短横线等字符进行宽松匹配。
21. 搜索结果默认限制前 200 张用于界面展示。
22. AI 智能搜索会把自然语言转换为 PostgreSQL `SELECT` 查询。
23. AI 智能搜索优先使用 `v_image_search` 视图。
24. AI 智能搜索生成的 SQL 必须把图片 `id` 作为第一列。
25. SQL 执行前会拒绝非 `SELECT` 语句。
26. SQL 执行前会拒绝 `INSERT`、`UPDATE`、`DELETE`、`DROP`、`ALTER`、`TRUNCATE`、`CREATE`、`GRANT`、`REVOKE` 等危险关键词。
27. AI SQL 查询连接会设置只读模式。
28. AI SQL 查询设置 5 秒超时。
29. AI SQL 查询最多返回 1000 行候选结果。
30. 搜索历史会写入 `search_history`。
31. 数据库未连接时，搜索会降级为当前已加载图片的文件名搜索。

### 设置页

1. 设置页显示当前 AI fallback 配置文件路径。
2. 支持新增 AI endpoint。
3. 支持删除 AI endpoint。
4. 支持上移和下移 endpoint，调整 fallback 优先级。
5. 每个 endpoint 可配置名称。
6. 每个 endpoint 可配置 Base URL。
7. 每个 endpoint 可配置 API Key。
8. 每个 endpoint 可配置模型名称。
9. 每个 endpoint 可单独启用或禁用是否参与 fallback。
10. 模型下拉框可编辑。
11. 可从 endpoint 的 `/models` 接口刷新模型列表。
12. 可逐个验证全部 endpoint 的 `/models` 可用性。
13. 可重置 AI fallback 熔断状态。
14. 可从默认配置或 last-good 配置恢复 fallback 列表。
15. 保存时会阻止空 fallback 覆盖已有可恢复配置。
16. 可配置 AI 请求间隔。
17. 可配置熔断阈值。
18. 可配置单批 AI 扫描上限，范围由代码限制在 1 到 500。
19. 可选择一张本地图片进行实时 AI 连接测试。
20. 实时测试会显示模型、耗时、描述、人数和标签数量。
21. 可浏览并保存扫描目录。
22. 可保存后立即扫描当前配置目录。
23. 可重新启用首次启动欢迎向导。
24. 可选择主题背景图片。
25. 可清除主题背景图片。
26. 主题背景会应用到主窗口、设置页、图片查看器、幻灯片和编辑器。
27. 可设置幻灯片播放间隔。
28. 可设置幻灯片顺序播放或随机播放。
29. 设置窗口有单实例保护，重复点击会置顶已有窗口。

### 数据库功能

1. 使用 HikariCP 管理连接池。
2. 所有主要 DAO 通过 JDBC 和 PreparedStatement 访问数据库。
3. `directories` 表保存本地目录树。
4. `images` 表保存图片元数据、缩略图、哈希、AI 处理状态、删除标记、删除前原路径、隐藏回收区路径和删除时间。
5. `operation_logs` 表保存图片和标签相关操作日志。
6. `tag_categories` 表保存标签分类。
7. `tags` 表保存具体标签。
8. `image_tags` 表保存图片和标签的多对多关系。
9. `ai_analysis_results` 表保存 AI 原始结果和结构化描述。
10. `app_settings` 表保存扫描目录、欢迎页、幻灯片偏好等非敏感设置。
11. `search_history` 表保存关键词搜索和 AI SQL 搜索记录。
12. `image_versions` 表保存图片版本快照。
13. `image_edit_operations` 表作为编辑操作回放的数据库预留设计。
14. `cloud_sources` 和 `cloud_images` 表作为云端图片扩展预留设计。
15. `v_active_images` 视图过滤已移入回收区的图片并关联目录。
16. `v_directory_stats` 视图统计目录图片数量和总大小。
17. `v_image_search` 视图聚合图片元数据、目录、标签和 AI 描述，支撑关键词搜索和 NL2SQL。
18. `v_tag_stats` 视图统计标签使用次数。
19. 普通 B-Tree 索引用于目录、图片、日志、标签和版本查询。
20. `idx_images_active` 部分索引优化未删除图片查询。
21. `idx_images_ai_pending` 部分索引优化未处理 AI 图片查询。
22. `idx_images_hash` 唯一索引用于图片哈希定位。
23. `pg_trgm` GIN 索引用于标签名和 AI 描述模糊搜索。
24. `trg_image_after_insert` 自动记录图片新增日志。
25. `trg_image_before_update` 自动记录重命名和删除标记日志，并更新时间戳。
26. `trg_image_after_delete` 自动记录物理删除日志。
27. `trg_tag_after_insert` 自动记录标签新增日志。
28. `trg_tag_after_delete` 自动记录标签移除日志。
29. `sp_monthly_report` 在数据库层提供按月操作统计能力。
30. `sp_directory_report` 在数据库层提供递归目录空间统计能力。
31. `sp_batch_rename` 在数据库层提供批量重命名事务能力。
32. `sp_restore_version` 在数据库层提供版本恢复能力。
33. `sp_batch_insert_tags` 在数据库层提供批量插入 AI 标签能力。
34. 当前 Java 主流程有自己的事务和 DAO 实现；部分存储过程作为数据库课程设计对象和后续扩展能力保留。
35. `schema.sql` 支持幂等创建表、索引、视图、触发器和过程。
36. DAO 层也会在运行时补齐部分新增表和字段，兼容已经初始化过的旧数据库。

### 交付、截图与验证

1. `mvn package` 生成依赖完整的 `target/image-manager-1.0.0.jar`，并清理 Maven 中间 JAR。
2. `scripts/package-stable.ps1` 生成 Windows 便携版 exe 目录和 `DigitalImageManager-windows-portable.zip`，同时同步课程提交用目标代码 JAR。
3. JAR 内嵌 `sql/schema.sql` 和 `sql/data.sql`。
4. `src/test/java/com/imagemanager/UiSnapshotSmoke.java` 可生成主要 FXML 界面截图。
5. 界面截图输出到 `target/ui-smoke/`。
6. 截图覆盖主界面、设置页、欢迎向导、图片查看器、幻灯片、图片编辑器、批量重命名和数据库向导。
7. 面向对象课程最终材料位于 `docs/面向对象程序与设计/我们的实际写作/`。
8. 面向对象课程班级提交镜像位于 `docs/面向对象程序与设计/面向对象程序设计实践/2024级软件工程R5班/第07组/`。
9. 数据库课程最终材料位于 `docs/数据库系统基础/最终交付/`。
10. `docs/面向对象程序与设计/我们的实际写作/generate_final_docs.py` 用于生成和同步论文、评分表、附加说明等交付材料。
11. 过大的班级汇总 ZIP 和 `target/` 构建产物不进入 Git，避免触发 GitHub 单文件大小限制。
12. `docs/` 下的课程证据日志可以进入 Git；本机运行日志只保留在根目录 `logs/`，不进入 Git。

## 数据库对象概览

| 类型 | 对象 |
| --- | --- |
| 表 | `directories`、`images`、`operation_logs`、`tag_categories`、`tags`、`image_tags`、`ai_analysis_results`、`app_settings`、`search_history`、`image_versions`、`image_edit_operations`、`cloud_sources`、`cloud_images` |
| 视图 | `v_active_images`、`v_directory_stats`、`v_image_search`、`v_tag_stats` |
| 触发器 | `trg_image_after_insert`、`trg_image_before_update`、`trg_image_after_delete`、`trg_tag_after_insert`、`trg_tag_after_delete` |
| 存储过程 | `sp_monthly_report`、`sp_directory_report`、`sp_batch_rename`、`sp_restore_version`、`sp_batch_insert_tags` |
| 重点索引 | `idx_images_directory_id`、`idx_images_active`、`idx_images_hash`、`idx_images_ai_pending`、`idx_directories_parent_id`、`idx_tags_name`、`idx_tags_name_trgm`、`idx_ai_desc_trgm`、`idx_versions_current`、`idx_cloud_images_source` |

## 项目结构

```text
.
├── pom.xml
├── README.md
├── WORK_LOG.md
├── sql/
│   ├── schema.sql
│   └── data.sql
├── src/
│   ├── main/
│   │   ├── java/com/imagemanager/
│   │   │   ├── App.java
│   │   │   ├── Launcher.java
│   │   │   ├── ai/
│   │   │   ├── controller/
│   │   │   ├── dao/
│   │   │   ├── model/
│   │   │   ├── scanner/
│   │   │   ├── service/
│   │   │   └── util/
│   │   └── resources/
│   │       ├── config/
│   │       ├── css/
│   │       ├── fxml/
│   │       ├── logback.xml
│   │       └── music/
│   └── test/java/com/imagemanager/
│       └── UiSnapshotSmoke.java
├── docs/
│   ├── 数据库系统基础/
│   │   └── 最终交付/
│   ├── 面向对象程序与设计/
│   │   ├── 我们的实际写作/
│   │   └── 面向对象程序设计实践/2024级软件工程R5班/第07组/
│   └── 软件工程基础/
└── assets/
```

## 开发与提交注意事项

1. `target/`、`logs/`、本机数据库配置、AI fallback 配置和个人密钥不进入 Git。

2. 仓库中的 `src/main/resources/config/database.properties` 只保留可提交模板，实际密码通过应用内向导、本机外部配置或环境变量提供。

3. 修改影响交付材料的源码、配置或截图后，应重新执行构建和文档生成链，避免 JAR、DOCX、PDF、截图和源码之间内容不一致。

4. 云端图片相关依赖和数据库表属于扩展预留，不应在课程验收说明中写成已经具备完整云同步主流程。
