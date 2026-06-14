# design_spec.md

项目名称：基于 PostgreSQL 的数字图像集成管理系统。
使用场景：数据库系统课程设计 15 分钟答辩。
受众：数据库课程老师与同学评委。
页面范围：14 页，16:9。

叙事主线：先以评分点总览建立“数据库含金量”预期，再展示 ER 模型、物理表结构和高级 SQL 对象，随后解释 JavaFX 后台如何通过 HikariCP、PreparedStatement 和事务控制安全访问数据库，最后用真实截图证明功能可演示，并诚实说明扩展边界。

视觉方向：参考同目录“收齐其他小组作业/PPT.pptx”的 image-first 答辩风格。整套 PPT 保持亮色、冰蓝、深墨蓝、青绿色强调线；背景使用整页生成式 UI/数据库场景，不使用廉价深色科技风。正文页采用大标题、短句、真实截图或数据库图表，避免密集文字。

deck-level continuity anchor：
- brightness_world: high-key bright product-defense board
- background_tendency: white / ice-blue / soft 3D database and desktop-app scene
- palette_roles: deep navy for title, vivid blue for structure, cyan-green for proof accent, orange only for warnings and boundaries
- lighting_model: soft luminous depth, broad shadows, no neon cyberpunk
- material_language: translucent white panels, rounded app windows, database cylinders, connected relation cards
- text_policy: large accurate Chinese text is rendered by deterministic slide layer; generated backgrounds must avoid random small text
