# 数据库系统课程设计最终交付进度

## 任务规则

1. pola 负责统筹最终交付，subagent 只做查找、审计和建议，不直接修改正式文档。
2. 正式材料以教师任务书、报告模板、重要答疑、打分依据、阶段文档和当前代码实现为最高依据。
3. 旧最终交付和其他小组材料只作为结构参考，不作为内容来源。
4. 正式文档不暴露过程记录原话、私下沟通、AI 提示词、截图来源等后台信息。

## 交付目录

- `01_阶段文档`
- `02_课程报告`
- `03_答辩PPT`
- `04_打印讲稿`
- `05_源码与运行包`
- `06_证据与清单`

## 已读材料

- 已读取本机记忆摘要与相关 rollout 摘要，确认本仓库当前实现和包装流程曾在 2026-06-12 验证通过，但本次仍以当前工作树重新验证为准。
- 已读取 `human-formal-writing` 技能说明，正式中文材料采用自然课程报告写法，避免 AI 味短标签堆叠。
- 已读取 `skill-installer` 技能说明，后续安装 `NyxTides/ppt-image-first` 时按本机技能安装流程执行。
- 已安装并读取 `ppt-image-first` 技能。安装命令采用 `--repo NyxTides/ppt-image-first --path . --name ppt-image-first --ref master --method download`，避开默认分支和 SSH host key 问题。
- 已通过 Word COM 抽取任务书、课程报告模板、重要答疑、需求分析、概要设计、详细设计、README、pom、SQL 和打包脚本文本，输出到 `06_证据与清单/extracted_text/`；原始过程记录不保留在最终提交证据中。
- 已查看 `txt中提到的打分依据.png`，评分项为数据库设计 30、功能设计 20、后台程序设计 15、界面设计 15、报告表述水平 10、PPT 表达能力 10。
- A/B/C 审计已完成并关闭：A 明确任务书、模板、答疑和评分项；B 明确当前代码真实实现、数据库亮点和不可夸大边界；C 明确阶段文档落后点和需修正口径。
- D 审计曾因外部模型代理错误失败；本轮已按用户要求重新启动 D 路审计，并已吸收其强制修正意见：PPT/演示节奏压缩、S11 命令块去乱码、索引数量统一为 19、清单状态刷新、运行包同步、最终证据目录移除原始过程记录抽取文本。
- 已抽取 `sql/schema.sql` 对象清单：13 张表、19 个索引（18 个普通索引 + 1 个唯一部分索引）、4 个视图、4 个函数、5 个触发器、5 个存储过程。

## 初始决策

- 先建立保护文件和交付目录，再读取教师材料和当前实现。
- 先形成“任务书逐条落实清单”和“打分点落实清单”，再批量生成正式文档。
- 云端/WebDAV 仅作为预留扩展写入，不作为已完成主流程。
- 运行包以 `target/image-manager-1.0.0.jar` 和 `scripts/package-stable.ps1` 生成的 Windows portable zip/exe 为准。

## 待办

已完成最终交付生成、D 路审计修补和最终验证。当前无未完成待办。

## 已完成产物

- `07_Gemini交付包/01_Gemini_最终报告包`
- `07_Gemini交付包/01_Gemini_最终报告包.zip`
- `07_Gemini交付包/02_Gemini_生成豆包PPT包`
- `07_Gemini交付包/02_Gemini_生成豆包PPT包.zip`
- `08_Gemini网页限额交付包/01_最终报告_Gemini限额版`
- `08_Gemini网页限额交付包/02_豆包PPT策划_Gemini限额版`
- `09_豆包PPT最终交付包/上传文件_推荐/01_豆包PPT生成主资料.docx`
- `09_豆包PPT最终交付包/上传文件_推荐/02_课程报告正文参考.docx`
- `09_豆包PPT最终交付包/上传文件_推荐/03_视觉素材图集.docx`
- `06_证据与清单/任务书逐条落实清单.md`
- `06_证据与清单/打分点落实清单.md`
- `06_证据与清单/schema_object_summary.json`
- `01_阶段文档/第07组毕振岚-系统需求分析说明书.docx`
- `01_阶段文档/第07组毕振岚-概要设计说明书.docx`
- `01_阶段文档/第07组毕振岚-详细设计说明书.docx`
- `02_课程报告/第07组毕振岚-数据库课程设计报告.docx`
- `03_答辩PPT/第07组毕振岚-数据库课程设计答辩PPT.pptx`
- `04_打印讲稿/第07组毕振岚-数据库课程设计答辩讲稿.docx`
- `05_源码与运行包/第07组毕振岚-数据库课程设计源代码.zip`
- `05_源码与运行包/image-manager-1.0.0.jar`
- `05_源码与运行包/DigitalImageManager-windows-portable.zip`
- `05_源码与运行包/DigitalImageManager-release/DigitalImageManager/DigitalImageManager.exe`
- `06_证据与清单/最终交付清单.md`
- `06_证据与清单/验证日志.md`

## 验证记录

2026-06-15 00:47 已按用户反馈补充两个外部重写交付包。报告包面向 Gemini 网页版重写最终报告，明确 `需求分析word正式版.doc` 为权威需求文档，当前报告草稿仅作覆盖范围参考，并要求正文至少 10000 字。PPT 包面向 Gemini 生成给豆包的 PPT 制作包，明确当前 PPT 草稿只作覆盖范围参考，不沿用低质视觉风格；同时纳入教师要求、打分依据、阶段文档、当前报告/PPT草稿、截图、schema、README 和关键代码。

已检查两个压缩包：`01_Gemini_最终报告包.zip` 共 51 个条目，大小 9249756 字节；`02_Gemini_生成豆包PPT包.zip` 共 76 个条目，大小 9833519 字节。已确认包内包含提示词、上传清单和上传资料；筛选 TXT 保留原始内容摘录，仅删除无关聊天，未用本轮文字改写相关内容。`git diff --check` 返回 0。

2026-06-15 00:55 已依据 Gemini 网页上传规则重新生成限额版外部交付包，输出到 `08_Gemini网页限额交付包/`。旧的 `07_Gemini交付包` 总 ZIP 因 ZIP 内文件数超过 10，不作为 Gemini 直接上传对象；新包改为批次 ZIP。

限额版报告包位于 `08_Gemini网页限额交付包/01_最终报告_Gemini限额版/`，同一条提示上传 7 个 `批次*.zip`，每个 ZIP 内 5-10 个文件，均小于 100MB。限额版 PPT 策划包位于 `08_Gemini网页限额交付包/02_豆包PPT策划_Gemini限额版/`，同一条提示上传 8 个 `批次*.zip`，每个 ZIP 内 5-10 个文件，均小于 100MB。两组 ZIP 均未包含视频或音频文件。已运行独立 zipfile 校验，结果 OK；`git diff --check` 返回 0。

2026-06-15 01:08 已根据 `Gemini返回的待交付豆包的输出.md` 生成最终给豆包的 PPT 生成包，输出到 `09_豆包PPT最终交付包/`。打包前已核查豆包文件上传公开资料：官方公开材料确认对话内上传文件会进入 AI 云盘，但未给出 PPT 生成模式的精确单次数量/大小表；第三方公开资料常见保守口径为 DOCX/PDF/TXT、单文件 50MB 内、同一会话 3 个文件左右。本包因此默认只上传 3 个 DOCX，不上传 ZIP，不直接上传大量 PNG。

推荐上传文件为 `01_豆包PPT生成主资料.docx`（661315 字节）、`02_课程报告正文参考.docx`（50879 字节）、`03_视觉素材图集.docx`（1064088 字节）。已用 python-docx 验证三个 DOCX 可解析，段落数分别为 136、238、48；默认上传文件数为 3，均远低于 50MB。

2026-06-15 00:27-00:29 已执行最终验证。`git diff --check`、`mvn -q -DskipTests compile`、`mvn -q test`、`mvn -q -DskipTests package`、`powershell -ExecutionPolicy Bypass -File .\scripts\package-stable.ps1` 均返回 0。

交付物结构检查共 46 项，失败 0 项。已确认 DOCX 可解析，正式文档未出现过程记录原话、私下沟通、AI 提示词、截图来源、同学作业等禁写词；PPT 为 16:9、共 12 页且每页有页面视觉图；S11 命令块已人工查看，无乱码和明显断行问题；`image-manager-1.0.0.jar` 含 `com/imagemanager/Launcher.class`、`sql/schema.sql`、`sql/data.sql`；源码 ZIP 已排除 `target`、`logs`、最终交付套娃和密钥类文件；portable ZIP 含 exe 与运行时；最终证据目录未保留原始过程记录抽取文本。

详细日志见 `06_证据与清单/验证日志.md` 和 `06_证据与清单/verification_logs/`。

2026-06-15 01:37-01:58 已按用户反馈救场答辩 PPT。复核结论：`NyxTides/ppt-image-first` skill 已完整安装，包含 `SKILL.md`、preview/review/candidate shell、模板和 demo；此前 PPT “平”的根因不是插件缺文件，而是执行路径没有真正进入 image-first 视觉决策，且旧 design_spec 锁定了亮色卡片式普通学术模板。

生图链路验证结果：首选 image2 通道权限不足，未能用于批量生成；随后改用本机已配置的可用 OpenAI-compatible 生图通道，并成功生成 PPT 所需 PNG 背景。正式 PPT 救场未把任何密钥写入仓库。

已以 `docs/数据库系统基础/收齐其他小组作业/PPT.pptx` 为视觉参考，导出并查看 sample 页，确认目标风格为亮色冰蓝、整页 UI/数据库场景、深墨蓝大标题、蓝绿强调线、真实/拟真窗口和关系卡片。已生成 `10_PPT救场融合版/content_report.md`、`design_spec.md`、`slide_blueprint.md`、`spec_lock.md`、`image_generation_prompts.jsonl`、14 张 `page_backgrounds/Sxx.png`、14 张最终 `page_visuals/Sxx.png`、`review_rescue_deck.html` 和 `第07组毕振岚-数据库课程设计答辩PPT_融合救场版.pptx`。最终 PPT 已同步覆盖 `03_答辩PPT/第07组毕振岚-数据库课程设计答辩PPT.pptx`。

PowerPoint COM 已打开最终 PPT 并导出 14 页截图到 `10_PPT救场融合版/final_exports/`，确认页数为 14。已人工查看封面、SQL 对象页、AI/NL2SQL 页、界面证据页、总结页等关键页；修正了封面标题换行、页眉背景伪文字干扰、S06 旧图表表名断行和 CTE 中文字体 tofu 问题。当前 `rescue_deck_validation.json` 记录页面尺寸检查通过，issues 为空。

2026-06-15 02:32-02:34 最终提交前复验完成。已移除中间抽取文本、其他小组示例 PPT 导出图和生图通道探测图，提交前敏感扫描未发现密钥、Bearer token 或生图通道地址。`git diff --check`、`git diff --cached --check`、`mvn -q -DskipTests compile`、`mvn -q test`、`mvn -q -DskipTests package`、`powershell -ExecutionPolicy Bypass -File .\scripts\package-stable.ps1` 均返回 0。重新生成的 `target/image-manager-1.0.0.jar` 与 `target/DigitalImageManager-windows-portable.zip` 已同步到 `05_源码与运行包/`，SHA256 与 target 版本一致。正式 PPT 与救场版 PPT 均为 14 页、16:9；`final_exports` 共 14 张导出截图；JAR 内已确认包含 `com/imagemanager/Launcher.class`、`sql/schema.sql`、`sql/data.sql`。已修正 `validate_deliverables.py` 中旧版 12 页硬编码，复跑结果为 failed_count=0、total=48。

2026-06-15 02:40 已将 Gemini 返回的长篇报告正文转为正式课程报告 DOCX，覆盖 `02_课程报告/第07组毕振岚-数据库课程设计报告.docx`。转换时移除了 Markdown 分隔符，嵌入系统图、ER 图、SQL 对象图、界面截图和演示路线图，并将报告正文中的“提示词上下文”等后台表述改为中性的“结构化上下文”。转换后 python-docx 统计为 234 段、1 张表、正文段落字符数 12253，已满足用户要求的万字级篇幅；`validate_deliverables.py` 复跑结果仍为 failed_count=0、total=48。

2026-06-15 03:05 已按用户要求将正式答辩 PPT 从“整页图片版”改为“可编辑组件版”。新的正式文件仍覆盖 `03_答辩PPT/第07组毕振岚-数据库课程设计答辩PPT.pptx`，另保留副本 `10_PPT救场融合版/第07组毕振岚-数据库课程设计答辩PPT_可编辑组件版.pptx`。每页标题、正文、卡片、流程线、表格、ER/SQL 对象块均为 PowerPoint 原生可编辑对象；只有真实 UI 截图作为局部图片插入，不再使用整页图片。PowerPoint COM 已导出 14 页到 `10_PPT救场融合版/editable_exports/`，并生成 `contact_sheet.png` 进行整体复核。`editable_deck_validation.json` 记录 14 页均无整页图片，且每页均有多个可编辑文本组件；`validate_deliverables.py` 已改为检查可编辑组件与无整页图片，复跑结果 failed_count=0、total=49。

2026-06-15 14:55 已按用户反馈修正 ER 关系表达。全项目核对 `sql/schema.sql`、README、DAO/Service 与最终交付材料后，确认统一口径为“子表外键指向父表主键”，并补充 `06_证据与清单/ER关系最终口径.md`。正式 PPT 第 4 页已改为“外键引用、基数、说明”双表格版，不再依赖指向不明的斜线箭头；明确 `image_tags` 将 `images` 与 `tags` 的 M:N 关系拆为两条 N:1，`app_settings` 与 `search_history` 无外键，`cloud_*` 与 `image_edit_operations` 为扩展或非主流程对象。已重新生成正式 PPT、可编辑副本与 `editable_exports/contact_sheet.png`，`editable_deck_validation.json` 显示第 4 页 48 个可编辑文本组件、0 张整页图片；`validate_deliverables.py` 复跑结果 failed_count=0、total=49，`git diff --check` 仅保留既有 CRLF 提示。

2026-06-16 14:03 已按用户要求修正删除语义并完成代码落地。当前实现不再把用户删除理解为“纯软删除”或“先软删再硬删”，而是采用 `images.is_deleted` 删除状态 + `deleted_original_path`、`deleted_storage_path`、`deleted_at` 恢复元数据 + 原目录 `.versions/.trash` 隐藏回收区。主界面新增“恢复中心”，右键菜单和快捷键新增剪切；恢复中心支持恢复已删除图片，也支持撤销最近一次粘贴或剪切移动。同步更新 `schema.sql`、README、源码包、JAR 与 Windows portable 包。验证命令 `git diff --check`、`mvn -q -DskipTests compile`、`mvn -q test`、`mvn -q -DskipTests package`、`package-stable.ps1` 均返回 0；`validate_deliverables.py` 返回 failed_count=0、total=49；JAR 内确认包含 `com/imagemanager/Launcher.class`、`sql/schema.sql`、`sql/data.sql`、`RecycleBinItem.class`、`FileOperationLog.class`。最终 `git diff --check` 仅有既有 CRLF 提示，无空白错误。
