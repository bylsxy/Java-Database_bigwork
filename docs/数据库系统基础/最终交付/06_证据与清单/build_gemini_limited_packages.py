from __future__ import annotations

import shutil
import zipfile
from pathlib import Path


ROOT = Path.cwd()
FINAL_DIR = ROOT / "docs" / "数据库系统基础" / "最终交付"
OLD_DIR = FINAL_DIR / "07_Gemini交付包"
OUT_DIR = FINAL_DIR / "08_Gemini网页限额交付包"


def ensure_repo_root() -> None:
    if not (ROOT / "pom.xml").exists() or not (ROOT / "sql" / "schema.sql").exists():
        raise RuntimeError(f"请从仓库根目录运行脚本，当前目录为 {ROOT}")


def reset_output_dir() -> None:
    final_resolved = FINAL_DIR.resolve()
    out_resolved = OUT_DIR.resolve()
    if OUT_DIR.exists():
        if final_resolved not in out_resolved.parents:
            raise RuntimeError(f"拒绝删除非最终交付目录下的路径：{OUT_DIR}")
        shutil.rmtree(OUT_DIR)
    OUT_DIR.mkdir(parents=True, exist_ok=True)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n", encoding="utf-8")


def make_zip(zip_path: Path, base: Path, files: list[str]) -> dict[str, int | str]:
    if len(files) > 10:
        raise RuntimeError(f"{zip_path.name} 包含 {len(files)} 个文件，超过 Gemini ZIP 10 文件限制")
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for rel in files:
            src = base / rel
            if not src.exists():
                raise FileNotFoundError(src)
            if src.suffix.lower() in {".mp4", ".mov", ".avi", ".mkv", ".mp3", ".wav", ".flac", ".aac"}:
                raise RuntimeError(f"Gemini ZIP 中不放视频或音频文件：{src}")
            zf.write(src, arcname=rel.replace("\\", "/"))
    size = zip_path.stat().st_size
    if size > 100 * 1024 * 1024:
        raise RuntimeError(f"{zip_path.name} 大小超过 100MB：{size}")
    return {"name": zip_path.name, "files": len(files), "bytes": size}


def report_prompt() -> str:
    return """
# 给 Gemini 的提示词：按上传材料重写最终课程设计报告

你将收到 7 个按 Gemini 上传规则拆分的 ZIP 文件。每个 ZIP 内不超过 10 个文件，文件名已经按批次说明用途。请先解读全部 ZIP 中的材料，再为《基于 PostgreSQL 的数字图像集成管理系统》重写一份可以整理成 DOCX 提交的《数据库系统课程设计报告》正文。

材料优先级请这样把握：教师任务书、课程设计报告模板、评分依据图片和重要答疑优先级最高；`需求分析word正式版.doc` 是需求分析说明书的权威版本，请优先采用它，不要用当前生成草稿替代它；README、schema.sql、pom.xml、关键 Java 代码、截图和验证日志用于判断当前系统真实实现；当前报告草稿只用于判断覆盖范围，不要沿用它的写法、句式和排版思路。

请写出一份完整、扎实、自然的中文课程设计报告，正文至少 10000 个中文字符，建议控制在 10000 到 13000 字之间。不要写成短标签加冒号堆叠，不要把报告写成纯功能清单。语言可以平实，但必须具体、有证据、有数据库含金量。

教师评分导向必须明显体现：总成绩由文档、源代码、答辩和课程报告组成。PPT 同学评分依据中，数据库设计 30 分、功能设计 20 分、后台程序设计 15 分、界面设计 15 分、报告表述水平 10 分、PPT 表达能力 10 分。报告本身要服务于这些得分点，尤其把数据库设计写透。

报告应覆盖这些方面，但章节顺序和行文由你根据材料自行组织：引言、项目背景、系统目标、用户范围、系统边界；功能需求、系统范围、功能结构图或总用例图、总体流程、数据字典；数据库设计，包括实体来源、ER 关系、关系模式、外模式、物理结构、约束和空值取舍；PostgreSQL 选择理由，结合 bytea、JSONB、递归 CTE、视图、触发器、存储过程、索引、事务和连接池说明；schema.sql 中真实存在的数据库对象，包括 13 张表、19 个索引、4 个视图、4 个函数、5 个触发器、5 个存储过程；系统设计与实现，包括 JavaFX 桌面端、Controller/Service/DAO 分层、HikariCP、PreparedStatement、事务回滚、后台扫描、缩略图缓存、AI fallback、数据库初始化向导、离线降级；测试与截图说明；安装和使用说明；总结与不足。

当前真实功能请以 README、schema.sql 和关键代码为准，重点写入 JavaFX+PostgreSQL 桌面图片管理、完整磁盘目录树懒加载、缩略图和元数据入库、bytea 缩略图、AI 标签、关键词搜索、NL2SQL 搜索、版本历史、幻灯片音乐、设置页、逐个供应商验证、数据库建立引导。WebDAV/云端只能写成预留扩展，不能写成完整主流程。AI 功能需要用户自行配置 OpenAI-compatible endpoint 和密钥。存储过程可以作为数据库课程对象和扩展接口说明，但不要写成所有前台功能都直接调用存储过程。

正式报告不要出现群聊原话、私聊、AI 提示词、截图来源、参考同学作业、让模型生成等过程性表述。可以把材料中的要求转化为中性表达，例如“根据课程设计要求”“在需求修订中补充”“系统设计时增加”。不要暴露聊天记录来源。

输出要求：先直接给出完整报告正文，使用适合粘贴到 Word 的 Markdown 标题结构；图表位置请引用已上传的截图或图表文件名作为建议，不要虚构不存在的图片；数据字典、核心表、索引/视图/触发器/存储过程、测试项等适合用表格；最后附一个提交前检查表，对应教师要求和评分点。如果材料之间冲突，请优先采用 README、schema.sql、关键 Java 代码和验证日志能够证明的真实实现。

请直接开始写最终报告，不要先问我是否确认。
"""


def ppt_prompt() -> str:
    return """
# 给 Gemini 的提示词：生成交给豆包的 PPT 制作包

你将收到 8 个按 Gemini 上传规则拆分的 ZIP 文件。每个 ZIP 内不超过 10 个文件，包含教师要求、评分依据、正式需求分析、阶段文档、当前报告草稿、当前 PPT 草稿、页面视觉图、真实界面截图、数据库图表、README、schema.sql 和关键代码。请不要直接生成 PPT 文件，而是为“豆包 PPT”准备一份完整的 PPT 生成交付包。我要把你输出的内容和相关资料再交给豆包，让豆包直接生成美观、专业、适合答辩的 PPT。

请先阅读全部上传材料。材料优先级如下：教师任务书、评分依据图片、课程报告模板和重要答疑优先；`需求分析word正式版.doc` 是需求分析说明书的权威版本；README、schema.sql、关键 Java 代码和截图用于判断真实实现；当前报告草稿、当前 PPT 草稿和页面视觉图只作为覆盖范围参考，不要沿用低质视觉风格，也不要被原页序限制。

请输出一份“给豆包的 PPT 生成包”。其中必须包含一段可以直接复制给豆包的总提示词，提示词开头请明确写：“请严格参考老师要求和我同步上传的资料，为《基于 PostgreSQL 的数字图像集成管理系统》生成数据库系统课程设计答辩 PPT。”还要包含给豆包的资料上传清单、PPT 策划方案、页面文案草稿、视觉设计建议、15 分钟答辩节奏建议和演示路线。

评分导向必须清楚：数据库设计 30、功能设计 20、后台程序设计 15、界面设计 15、报告表述水平 10、PPT 表达能力 10。请让豆包把数据库设计作为主线，而不是把 PPT 做成普通软件介绍。PPT 应直入数据库含金量，少字、图像优先、有真实界面、有 ER/表/视图/索引/触发器/存储过程等数据库对象证据。

必须讲清楚但不要塞满页面：系统定位是 JavaFX + PostgreSQL 桌面端数字图像管理系统；数据库设计包括 13 张表、19 个索引、4 个视图、4 个函数、5 个触发器、5 个存储过程和递归 CTE；核心关系包括 directories 自引用、images 主表、tag_categories/tags/image_tags 多对多、ai_analysis_results 一对一、image_versions 一对多、operation_logs 审计；真实功能包括完整磁盘目录树懒加载、缩略图/元数据/bytea、AI 标签、关键词搜索、NL2SQL 搜索、版本历史、幻灯片音乐、数据库初始化向导、离线降级、HikariCP、PreparedStatement 和事务回滚；AI 与 NL2SQL 要说明结果先落库、查询走 `v_image_search`、只允许 SELECT、只读连接、超时和限行；运行交付要提 JAR、Windows portable zip/exe、源码包和验证日志。

真实性边界：WebDAV/云端只作为扩展预留。语音搜索、全网全盘搜索、SQLite 多数据库后端等只能作为后续展望或教师建议，不要写成已经实现。AI 需要用户自行配置 OpenAI-compatible endpoint 和密钥。

视觉要求：不要让豆包照抄当前 PPT 的设计。新的 PPT 应该像一次正式数据库课程答辩，视觉干净、有层次、有真实截图、有数据库对象图，不要廉价科技风、堆卡片、花哨渐变、模板感和 AI 味过重的排版。请给豆包留出设计发挥空间，但把老师要求、评分权重、数据库主线和真实性边界写清楚。

请直接输出“给豆包的交付包内容”，不要向我提问。
"""


def build_report_package() -> list[dict[str, int | str]]:
    src = OLD_DIR / "01_Gemini_最终报告包" / "上传资料"
    out = OUT_DIR / "01_最终报告_Gemini限额版"
    out.mkdir(parents=True, exist_ok=True)
    write_text(out / "01_复制给Gemini_最终报告提示词.md", report_prompt())
    write_text(
        out / "00_上传顺序.md",
        """
# Gemini 最终报告限额版上传顺序

本目录按 Gemini 网页上传限制重做：同一条提示最多 10 个附件；每个 ZIP 最多 10 个文件；每个 ZIP 小于 100MB；不包含视频或音频。

使用时不要上传整个文件夹，也不要上传旧的总 ZIP。请在同一条 Gemini 提示中上传本目录下 7 个 `批次*.zip`，然后把 `01_复制给Gemini_最终报告提示词.md` 的全文粘贴到输入框提交。

若网页端临时限制更低，优先上传批次 01 到 04；批次 05 到 07 可以在同一会话后续补充，并要求 Gemini 合并参考。
""",
    )
    batches = {
        "批次01_教师要求与正式需求.zip": [
            "01_教师要求与模板/2026数据库课程设计任务书.doc",
            "01_教师要求与模板/数据库课程设计报告模板.doc",
            "01_教师要求与模板/txt中提到的打分依据.png",
            "01_教师要求与模板/关于课程设计，群聊中的重要问题答疑.txt",
            "01_教师要求与模板/需求文档中的数据字典说明.txt",
            "01_教师要求与模板/重要TXT原文合并_只删无关噪声.txt",
            "02_权威阶段文档/需求分析word正式版.doc",
            "02_权威阶段文档/需求分析.md",
        ],
        "批次02_阶段设计与当前草稿.zip": [
            "02_权威阶段文档/概要设计.md",
            "02_权威阶段文档/详细设计.md",
            "06_当前草稿与验证/当前报告草稿_仅供覆盖范围参考.docx",
            "06_当前草稿与验证/任务书逐条落实清单.md",
            "06_当前草稿与验证/打分点落实清单.md",
        ],
        "批次03_实现证据与数据库.zip": [
            "03_当前实现与源码证据/README.md",
            "03_当前实现与源码证据/pom.xml",
            "03_当前实现与源码证据/schema.sql",
            "03_当前实现与源码证据/data.sql",
            "03_当前实现与源码证据/package-stable.ps1",
            "06_当前草稿与验证/schema_object_summary.json",
            "06_当前草稿与验证/验证日志.md",
        ],
        "批次04_关键源码_架构与检索.zip": [
            "04_关键代码/DatabaseConnection.java",
            "04_关键代码/DatabaseBootstrapService.java",
            "04_关键代码/DatabaseSetupDialog.java",
            "04_关键代码/ImageDaoImpl.java",
            "04_关键代码/ImageServiceImpl.java",
            "04_关键代码/DirectoryScanner.java",
            "04_关键代码/ScanTask.java",
            "04_关键代码/MainController.java",
            "04_关键代码/SearchService.java",
            "04_关键代码/TagDaoImpl.java",
        ],
        "批次05_关键源码_AI版本编辑.zip": [
            "04_关键代码/OpenAICompatibleService.java",
            "04_关键代码/AiTagStorageService.java",
            "04_关键代码/AIFallbackManager.java",
            "04_关键代码/VersionDaoImpl.java",
            "04_关键代码/EditService.java",
        ],
        "批次06_数据库图表.zip": [
            "05_界面截图与图表/图1_系统功能结构图.png",
            "05_界面截图与图表/图2_ER关系模式图.png",
            "05_界面截图与图表/图3_系统总体流程图.png",
            "05_界面截图与图表/图4_SQL对象与性能设计.png",
            "05_界面截图与图表/图5_NL2SQL安全链路.png",
            "05_界面截图与图表/图6_后台架构图.png",
            "05_界面截图与图表/图7_演示路线.png",
        ],
        "批次07_真实界面截图.zip": [
            "05_界面截图与图表/real_02_主界面_1200x800_默认窗口.png",
            "05_界面截图与图表/real_05_首次启动向导_800x775.png",
            "05_界面截图与图表/real_07_系统设置_850x900.png",
            "05_界面截图与图表/real_08_数据库连接与初始化向导_760x680.png",
            "05_界面截图与图表/real_12_幻灯片播放_1250x875.png",
            "05_界面截图与图表/real_14_图片编辑器_1250x938.png",
        ],
    }
    return [make_zip(out / name, src, files) for name, files in batches.items()]


def build_ppt_package() -> list[dict[str, int | str]]:
    src = OLD_DIR / "02_Gemini_生成豆包PPT包" / "上传资料"
    out = OUT_DIR / "02_豆包PPT策划_Gemini限额版"
    out.mkdir(parents=True, exist_ok=True)
    write_text(out / "01_复制给Gemini_生成豆包PPT包提示词.md", ppt_prompt())
    write_text(
        out / "00_上传顺序.md",
        """
# Gemini 生成豆包 PPT 包限额版上传顺序

本目录按 Gemini 网页上传限制重做：同一条提示最多 10 个附件；每个 ZIP 最多 10 个文件；每个 ZIP 小于 100MB；不包含视频或音频。

使用时不要上传整个文件夹，也不要上传旧的总 ZIP。请在同一条 Gemini 提示中上传本目录下 8 个 `批次*.zip`，然后把 `01_复制给Gemini_生成豆包PPT包提示词.md` 的全文粘贴到输入框提交。

Gemini 的任务不是直接生成 PPT，而是生成一套给豆包的 PPT 制作提示词、页面策划、素材清单和答辩节奏。
""",
    )
    batches = {
        "批次01_教师要求阶段文档与报告.zip": [
            "01_教师要求与评分/2026数据库课程设计任务书.doc",
            "01_教师要求与评分/数据库课程设计报告模板.doc",
            "01_教师要求与评分/txt中提到的打分依据.png",
            "01_教师要求与评分/关于课程设计，群聊中的重要问题答疑.txt",
            "06_筛选TXT原文/重要TXT原文合并_只删无关噪声.txt",
            "02_报告与阶段文档/需求分析word正式版.doc",
            "02_报告与阶段文档/需求分析.md",
            "02_报告与阶段文档/概要设计.md",
            "02_报告与阶段文档/详细设计.md",
            "02_报告与阶段文档/当前报告草稿_仅供内容覆盖参考.docx",
        ],
        "批次02_当前PPT草稿与策划文件.zip": [
            "03_当前PPT草稿/当前PPT草稿_仅供覆盖范围参考.pptx",
            "03_当前PPT草稿/content_report.md",
            "03_当前PPT草稿/design_spec.md",
            "03_当前PPT草稿/slide_blueprint.md",
            "03_当前PPT草稿/spec_lock.md",
        ],
        "批次03_当前PPT页面预览_A.zip": [
            "03_当前PPT草稿/S01.png",
            "03_当前PPT草稿/S02.png",
            "03_当前PPT草稿/S03.png",
            "03_当前PPT草稿/S04.png",
            "03_当前PPT草稿/S05.png",
            "03_当前PPT草稿/S06.png",
        ],
        "批次04_当前PPT页面预览_B.zip": [
            "03_当前PPT草稿/S07.png",
            "03_当前PPT草稿/S08.png",
            "03_当前PPT草稿/S09.png",
            "03_当前PPT草稿/S10.png",
            "03_当前PPT草稿/S11.png",
            "03_当前PPT草稿/S12.png",
        ],
        "批次05_真实界面截图.zip": [
            "04_界面截图与图表素材/01_主界面_目录树与缩略图.png",
            "04_界面截图与图表素材/02_幻灯片播放.png",
            "04_界面截图与图表素材/03_设置页_运行配置.png",
            "04_界面截图与图表素材/04_图片编辑与版本历史.png",
            "04_界面截图与图表素材/05_标签与扩展搜索.png",
            "04_界面截图与图表素材/real_05_首次启动向导_800x775.png",
            "04_界面截图与图表素材/real_08_数据库连接与初始化向导_760x680.png",
            "04_界面截图与图表素材/real_14_图片编辑器_1250x938.png",
        ],
        "批次06_数据库与演示图表.zip": [
            "04_界面截图与图表素材/图1_系统功能结构图.png",
            "04_界面截图与图表素材/图2_ER关系模式图.png",
            "04_界面截图与图表素材/图3_系统总体流程图.png",
            "04_界面截图与图表素材/图4_SQL对象与性能设计.png",
            "04_界面截图与图表素材/图5_NL2SQL安全链路.png",
            "04_界面截图与图表素材/图6_后台架构图.png",
            "04_界面截图与图表素材/图7_演示路线.png",
        ],
        "批次07_代码数据库证据_A.zip": [
            "05_代码与数据库证据/README.md",
            "05_代码与数据库证据/schema.sql",
            "05_代码与数据库证据/pom.xml",
            "05_代码与数据库证据/data.sql",
            "05_代码与数据库证据/DatabaseConnection.java",
            "05_代码与数据库证据/DatabaseBootstrapService.java",
            "05_代码与数据库证据/ImageDaoImpl.java",
            "05_代码与数据库证据/SearchService.java",
            "05_代码与数据库证据/OpenAICompatibleService.java",
            "05_代码与数据库证据/AiTagStorageService.java",
        ],
        "批次08_代码数据库证据_B.zip": [
            "05_代码与数据库证据/MainController.java",
            "05_代码与数据库证据/DirectoryScanner.java",
            "05_代码与数据库证据/ScanTask.java",
            "05_代码与数据库证据/VersionDaoImpl.java",
            "05_代码与数据库证据/AIFallbackManager.java",
        ],
    }
    return [make_zip(out / name, src, files) for name, files in batches.items()]


def main() -> None:
    ensure_repo_root()
    reset_output_dir()
    report = build_report_package()
    ppt = build_ppt_package()
    write_text(
        OUT_DIR / "使用说明.md",
        """
# Gemini 网页限额交付包

本目录是根据 Gemini 网页上传规则重新生成的版本。不要再上传旧的 `07_Gemini交付包` 里的总 ZIP，因为旧 ZIP 内文件数超过 Gemini 对 ZIP 的 10 文件限制。

使用方式：

1. 写最终报告时，进入 `01_最终报告_Gemini限额版`，上传 7 个 `批次*.zip`，再复制提示词提交。
2. 让 Gemini 生成豆包 PPT 制作包时，进入 `02_豆包PPT策划_Gemini限额版`，上传 8 个 `批次*.zip`，再复制提示词提交。

这两个子目录都满足：同一条提示上传附件不超过 10 个；每个 ZIP 内文件不超过 10 个；每个 ZIP 小于 100MB；ZIP 内不放视频或音频。
""",
    )
    summary_lines = ["# Gemini 限额包生成结果", "", "## 最终报告包"]
    for item in report:
        summary_lines.append(f"- {item['name']}：{item['files']} 个文件，{item['bytes']} 字节")
    summary_lines.append("")
    summary_lines.append("## 豆包 PPT 策划包")
    for item in ppt:
        summary_lines.append(f"- {item['name']}：{item['files']} 个文件，{item['bytes']} 字节")
    write_text(OUT_DIR / "生成校验摘要.md", "\n".join(summary_lines))
    print("\n".join(summary_lines))


if __name__ == "__main__":
    main()
