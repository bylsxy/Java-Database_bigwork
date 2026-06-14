from __future__ import annotations

import json
from pathlib import Path
import sys

import win32com.client  # type: ignore


ROOT = Path(__file__).resolve().parents[4]
OUT_DIR = ROOT / "docs" / "数据库系统基础" / "最终交付" / "06_证据与清单" / "extracted_text"

FILES = [
    ROOT / "docs" / "数据库系统基础" / "2026春-数据库系统课程设计教师提供的资料" / "2026数据库课程设计任务书.doc",
    ROOT / "docs" / "数据库系统基础" / "2026春-数据库系统课程设计教师提供的资料" / "数据库课程设计报告模板.doc",
    ROOT / "docs" / "数据库系统基础" / "2026春-数据库系统课程设计教师提供的资料" / "需求文档中的数据字典说明.txt",
    ROOT / "docs" / "数据库系统基础" / "需求分析word正式版.doc",
    ROOT / "docs" / "数据库系统基础" / "需求分析.md",
    ROOT / "docs" / "数据库系统基础" / "概要设计.md",
    ROOT / "docs" / "数据库系统基础" / "详细设计.md",
    ROOT / "README.md",
    ROOT / "pom.xml",
    ROOT / "sql" / "schema.sql",
    ROOT / "sql" / "data.sql",
    ROOT / "scripts" / "package-stable.ps1",
]


def read_text_file(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(errors="replace")


def extract_word_text(word, path: Path) -> str:
    doc = word.Documents.Open(str(path), ReadOnly=True, AddToRecentFiles=False, Visible=False)
    try:
        parts: list[str] = [doc.Content.Text]
        table_lines: list[str] = []
        for table_index in range(1, doc.Tables.Count + 1):
            table = doc.Tables(table_index)
            table_lines.append(f"\n[Table {table_index}]")
            for row_index in range(1, table.Rows.Count + 1):
                cells = []
                for col_index in range(1, table.Columns.Count + 1):
                    try:
                        text = table.Cell(row_index, col_index).Range.Text
                    except Exception:
                        text = ""
                    text = text.replace("\r", "").replace("\x07", "").strip()
                    cells.append(text)
                table_lines.append(" | ".join(cells))
        if table_lines:
            parts.append("\n".join(table_lines))
        return "\n\n".join(parts)
    finally:
        doc.Close(False)


def safe_name(path: Path) -> str:
    name = path.name
    for ch in '<>:"/\\|?*':
        name = name.replace(ch, "_")
    return name + ".txt"


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    word = win32com.client.Dispatch("Word.Application")
    word.Visible = False
    try:
        for path in FILES:
            if not path.exists():
                manifest.append({"path": str(path), "status": "missing"})
                continue
            if path.suffix.lower() in {".doc", ".docx"}:
                text = extract_word_text(word, path)
            else:
                text = read_text_file(path)
            out = OUT_DIR / safe_name(path)
            out.write_text(text, encoding="utf-8")
            manifest.append({"path": str(path), "status": "ok", "out": str(out), "chars": len(text)})
    finally:
        word.Quit()

    (OUT_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
