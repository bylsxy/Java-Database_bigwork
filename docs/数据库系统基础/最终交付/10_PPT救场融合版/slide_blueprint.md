# slide_blueprint.md

## S01 基于 PostgreSQL 的数字图像集成管理系统
- page_role: 封面
- core_message: JavaFX + PostgreSQL 桌面图片管理｜数据库系统课程设计答辩
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S02 答辩先对齐得分点
- page_role: 评分点总览
- core_message: 数据库设计是主线，功能、后台和界面都服务于落库与查询
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S03 系统功能结构：图片管理不是文件夹外壳
- page_role: 功能结构
- core_message: 目录、图片、标签、AI、版本、日志共同组成可查询的数据资产
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S04 ER 模型：围绕 images 主表展开
- page_role: 数据库设计
- core_message: 目录自引用、标签多对多、版本一对多、操作日志可追踪
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S05 物理结构：13 张表按职责分层
- page_role: 表结构
- core_message: 基础对象、AI 标签、历史审计、扩展预留边界清晰
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S06 高级数据库对象全景
- page_role: SQL 对象
- core_message: 索引、视图、触发器、存储过程和递归 CTE 共同支撑性能与一致性
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S07 完整磁盘目录树：懒加载 + 递归 CTE
- page_role: 目录树
- core_message: 前台只展开当前需要的节点，数据库保留完整目录层级与路径统计
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S08 图片入库链路：缩略图、元数据与 bytea
- page_role: 持久化
- core_message: 扫描目录后把文件系统信息结构化，缩略图缓存直接服务主界面渲染
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S09 后台基座：连接池、预编译与事务回滚
- page_role: 后台程序设计
- core_message: HikariCP 管连接，PreparedStatement 防注入，批量操作失败即回滚
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S10 AI 标签与 NL2SQL：能问，但只能安全地问
- page_role: AI 搜索
- core_message: AI 结果先落库；自然语言查询只允许走 v_image_search 只读视图
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S11 版本历史：从编辑行为回到数据库一致性
- page_role: 版本与审计
- core_message: image_versions 记录快照，sp_restore_version 支撑恢复，operation_logs 留痕
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S12 界面证据：核心流程都已能演示
- page_role: 界面设计
- core_message: 启动向导、主界面、图片查看、幻灯片、编辑器、数据库初始化
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S13 现场演示路线
- page_role: 演示路线
- core_message: 先证明数据库初始化，再展示落库、查询、版本和幻灯片主流程
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。

## S14 总结：用数据库重新组织本地图片
- page_role: 总结
- core_message: 已完成主流程，云端/WebDAV、语音搜索作为后续扩展预留
- content_basis_binding: README、schema_object_summary.json、真实截图和课程评分点。
- claim_status: user_provided / repository_verified。
- visual_strategy: 整页生成式背景承载氛围，准确中文和数据库对象由最终页面图层呈现。
- continuity_inheritance: 继承亮色冰蓝背景、深墨蓝标题、蓝绿强调线、圆角应用窗口和数据库卡片语法。
