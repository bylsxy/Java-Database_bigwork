from __future__ import annotations

import html
import re
import shutil
from dataclasses import dataclass
from pathlib import Path

import win32com.client as win32
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
EXP3 = ROOT / "docs" / "软件工程基础" / "实验3"
EVIDENCE = ROOT / "docs" / "软件工程基础" / "综合性实验最终文档（还是旧的，未更新）" / "图表与证据"
EXPORT_DIR = ROOT / "C_tmp_visio_exports"
REVIEW_DIR = ROOT / "target" / "exp3-diagram-review"

PAGE_W = 13.2
PAGE_H = 8.4

COLORS = {
    "white": "RGB(255,255,255)",
    "line": "RGB(64,64,64)",
    "title": "RGB(245,245,245)",
    "actor": "RGB(240,240,240)",
    "boundary": "RGB(222,235,247)",
    "control": "RGB(226,239,218)",
    "entity": "RGB(234,226,247)",
    "database": "RGB(252,228,214)",
    "service": "RGB(217,234,211)",
    "util": "RGB(234,226,247)",
    "warn": "RGB(255,242,204)",
    "error": "RGB(248,203,173)",
    "group": "RGB(248,248,248)",
    "blue": "RGB(222,235,247)",
    "green": "RGB(226,239,218)",
    "purple": "RGB(234,226,247)",
    "orange": "RGB(252,228,214)",
    "gray": "RGB(242,242,242)",
}


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2

    @property
    def left(self) -> float:
        return self.x

    @property
    def right(self) -> float:
        return self.x + self.w

    @property
    def top(self) -> float:
        return self.y + self.h

    @property
    def bottom(self) -> float:
        return self.y


class VisioBuilder:
    def __init__(self, app, title: str, page_w: float = PAGE_W, page_h: float = PAGE_H):
        self.app = app
        self.doc = app.Documents.Add("")
        self.page = app.ActivePage
        self.page_w = page_w
        self.page_h = page_h
        self.page.PageSheet.CellsU("PageWidth").FormulaU = f"{page_w} in"
        self.page.PageSheet.CellsU("PageHeight").FormulaU = f"{page_h} in"
        background = self.page.DrawRectangle(0, 0, page_w, page_h)
        self.cell(background, "FillForegnd", COLORS["white"])
        self.cell(background, "FillPattern", "1")
        self.cell(background, "LineColor", COLORS["white"])
        self.cell(background, "LineWeight", "0 pt")
        self.cell(background, "LinePattern", "0")
        try:
            background.SendToBack()
        except Exception:
            pass
        self.title(title)

    def cell(self, shape, name: str, value: str) -> None:
        try:
            shape.CellsU(name).FormulaU = value
        except Exception:
            pass

    def text_style(self, shape, size: float = 8, bold: bool = False, color: str = "RGB(0,0,0)") -> None:
        self.cell(shape, "Char.Size", f"{size} pt")
        self.cell(shape, "Char.Font", "SimSun")
        self.cell(shape, "Char.Color", color)
        self.cell(shape, "Char.Style", "17" if bold else "0")
        self.cell(shape, "Para.HorzAlign", "1")
        self.cell(shape, "VerticalAlign", "1")
        self.cell(shape, "TxtPinX", "Width*0.5")
        self.cell(shape, "TxtPinY", "Height*0.5")
        for margin in ("LeftMargin", "RightMargin", "TopMargin", "BottomMargin"):
            self.cell(shape, margin, "0.04 in")

    def box(
        self,
        x: float,
        y: float,
        w: float,
        h: float,
        text: str,
        fill: str = "white",
        size: float = 8,
        bold: bool = False,
        line: str = "line",
        dashed: bool = False,
    ) -> Box:
        shape = self.page.DrawRectangle(x, y, x + w, y + h)
        shape.Text = text
        self.cell(shape, "FillForegnd", COLORS[fill])
        self.cell(shape, "LineColor", COLORS[line])
        self.cell(shape, "LineWeight", "0.75 pt")
        if dashed:
            self.cell(shape, "LinePattern", "2")
        self.text_style(shape, size, bold)
        return Box(x, y, w, h)

    def title(self, text: str) -> None:
        self.box(0.25, self.page_h - 0.5, self.page_w - 0.5, 0.32, text, "title", 11, True)

    def label(self, x: float, y: float, w: float, h: float, text: str, size: float = 7.2, fill: str = "white") -> Box:
        b = self.box(x, y, w, h, text, fill, size, False)
        shape = self.page.Shapes.Item(self.page.Shapes.Count)
        self.cell(shape, "LinePattern", "0")
        return b

    def line(
        self,
        x1: float,
        y1: float,
        x2: float,
        y2: float,
        arrow: bool = True,
        dashed: bool = False,
        weight: float = 1.0,
    ):
        line = self.page.DrawLine(x1, y1, x2, y2)
        self.cell(line, "LineColor", COLORS["line"])
        self.cell(line, "LineWeight", f"{weight} pt")
        if arrow:
            self.cell(line, "EndArrow", "13")
        if dashed:
            self.cell(line, "LinePattern", "2")
        return line

    def arrow(self, start: tuple[float, float], end: tuple[float, float], label: str = "", dashed: bool = False):
        x1, y1 = start
        x2, y2 = end
        self.line(x1, y1, x2, y2, True, dashed, 1.0)
        if label:
            midx = (x1 + x2) / 2
            midy = (y1 + y2) / 2
            width = min(max(0.55 + len(label) * 0.055, 1.2), 3.0)
            self.label(midx - width / 2, midy + 0.05, width, 0.18, label, 6.8)

    def route(self, start: tuple[float, float], end: tuple[float, float], label: str = "", dashed: bool = False):
        x1, y1 = start
        x2, y2 = end
        if abs(x1 - x2) < 0.05 or abs(y1 - y2) < 0.05:
            self.arrow(start, end, label, dashed)
            return
        midy = (y1 + y2) / 2
        self.line(x1, y1, x1, midy, False, dashed, 0.9)
        self.line(x1, midy, x2, midy, False, dashed, 0.9)
        self.line(x2, midy, x2, y2, True, dashed, 0.9)
        if label:
            width = min(max(0.55 + len(label) * 0.055, 1.25), 2.8)
            self.label((x1 + x2) / 2 - width / 2, midy + 0.06, width, 0.18, label, 6.8)

    def actor(self, x: float, y: float, name: str) -> Box:
        head = self.page.DrawOval(x + 0.52, y + 0.62, x + 0.72, y + 0.82)
        self.cell(head, "FillForegnd", "RGB(150,150,150)")
        self.cell(head, "LinePattern", "0")
        body = self.page.DrawLine(x + 0.62, y + 0.6, x + 0.62, y + 0.28)
        self.cell(body, "LineWeight", "2 pt")
        self.line(x + 0.38, y + 0.48, x + 0.86, y + 0.48, False, False, 2)
        self.line(x + 0.62, y + 0.28, x + 0.42, y + 0.05, False, False, 2)
        self.line(x + 0.62, y + 0.28, x + 0.82, y + 0.05, False, False, 2)
        return self.box(x, y - 0.35, 1.25, 0.32, name, "actor", 7.5, True)

    def save(self, path: Path, png: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        png.parent.mkdir(parents=True, exist_ok=True)
        self.doc.SaveAs(str(path.resolve()))
        self.page.Export(str(png.resolve()))
        remove_export_artifacts(png)
        self.doc.Close()


def remove_export_artifacts(png: Path) -> None:
    """Remove Visio's occasional pale-yellow page-edge artifact from PNG exports."""
    image = Image.open(png).convert("RGB")
    pixels = image.load()
    width, height = image.size
    for x in range(width):
        yellowish = 0
        for y in range(height):
            r, g, b = pixels[x, y]
            if r > 220 and g > 200 and b < 175:
                yellowish += 1
        if yellowish > height * 0.2:
            for y in range(height):
                pixels[x, y] = (255, 255, 255)
    for x in range(width):
        for y in range(height):
            r, g, b = pixels[x, y]
            if r > 235 and g > 210 and b < 160:
                pixels[x, y] = (255, 255, 255)
    image.save(png)


def sequence_diagram(
    app,
    title: str,
    participants: list[tuple[str, str]],
    messages: list[dict],
    path: Path,
    png: Path,
    frames: list[dict] | None = None,
    note: str | None = None,
) -> None:
    # Wider than the default landscape page so participant heads never clip at
    # the edge when seven or eight lifelines are present.
    seq_w = max(PAGE_W, 1.85 * len(participants) + 0.4)
    b = VisioBuilder(app, title, page_w=seq_w, page_h=9.0)
    n = len(participants)
    left, right = 1.0, b.page_w - 1.0
    top_y, bottom_y = b.page_h - 1.45, 0.65
    xs = [left + i * ((right - left) / (n - 1)) for i in range(n)]
    name_to_x = {}
    for idx, ((name, kind), x) in enumerate(zip(participants, xs)):
        if kind == "actor":
            b.actor(x - 0.62, top_y + 0.1, name)
            head_y = top_y - 0.05
        else:
            fill = {"boundary": "boundary", "control": "control", "entity": "entity", "database": "database"}.get(kind, "gray")
            stereo = {"boundary": "<<boundary>>\n", "control": "<<control>>\n", "entity": "<<entity>>\n", "database": "<<database>>\n"}.get(kind, "")
            b.box(x - 0.68, top_y, 1.36, 0.5, f"{stereo}{name}", fill, 7, True)
            head_y = top_y
        name_to_x[name] = x
        b.line(x, head_y - 0.02, x, bottom_y, False, True, 0.8)

    frames = frames or []
    frame_tags: list[tuple[float, float, float, str]] = []
    for frame in frames:
        y_top = frame["top"]
        y_bottom = frame["bottom"]
        x1 = max(0.42, xs[frame.get("from", 0)] - 0.62)
        x2 = min(b.page_w - 0.42, xs[frame.get("to", n - 1)] + 0.62)
        b.box(x1, y_bottom, x2 - x1, y_top - y_bottom, "", "white", 6.8, False, dashed=True)
        frame_shape = b.page.Shapes.Item(b.page.Shapes.Count)
        b.cell(frame_shape, "FillPattern", "0")
        tag = frame["label"]
        tag_w = min(3.2, max(1.75, len(tag) * 0.12))
        frame_tags.append((x1 + 0.05, y_top - 0.24, tag_w, tag))

    activation_ranges: dict[str, list[tuple[float, float]]] = {}
    for msg in messages:
        if "activate" in msg:
            activation_ranges.setdefault(msg["activate"], []).append((msg["y_top"], msg["y_bottom"]))
    for name, ranges in activation_ranges.items():
        x = name_to_x[name]
        for y_top, y_bottom in ranges:
            b.box(x - 0.045, y_bottom, 0.09, y_top - y_bottom, "", "white", 5)

    message_labels: list[tuple[float, float, float, float, str, float]] = []
    for msg in messages:
        if msg.get("type") == "self":
            x = name_to_x[msg["from"]]
            y = msg["y"]
            b.line(x, y, x + 0.35, y, False, msg.get("dashed", False), 1)
            b.line(x + 0.35, y, x + 0.35, y - 0.22, False, msg.get("dashed", False), 1)
            b.line(x + 0.35, y - 0.22, x + 0.02, y - 0.22, True, msg.get("dashed", False), 1)
            label = msg["label"]
            width = min(max(0.7 + len(label) * 0.055, 1.2), 2.45)
            message_labels.append((x + 0.08, y + 0.05, width, 0.2, label, 6.8))
            continue
        x1 = name_to_x[msg["from"]]
        x2 = name_to_x[msg["to"]]
        y = msg["y"]
        dashed = msg.get("dashed", False) or msg.get("return", False)
        b.line(x1, y, x2, y, True, dashed, 1.0)
        label = msg.get("label", "")
        if label:
            width = min(max(0.65 + len(label) * 0.052, 1.0), abs(x2 - x1) - 0.1 if abs(x2 - x1) > 1.0 else 1.2)
            message_labels.append(((x1 + x2) / 2 - width / 2, y + 0.06, width, 0.18, label, 6.6))

    if note:
        b.box(0.55, 0.16, min(5.6, b.page_w - 1.1), 0.32, note, "warn", 6.8, False, dashed=True)
    for x, y, w, tag in frame_tags:
        b.box(x, y, w, 0.24, tag, "white", 6.8, True)
    for x, y, w, h, label, size in message_labels:
        b.label(x, y, w, h, label, size)
    b.save(path, png)


def add_package(app):
    b = VisioBuilder(app, "图1 体系结构分层包图")
    boxes = {
        "ui": b.box(0.65, 5.95, 2.2, 0.9, "<<package>>\nui.controller / fxml\nMainController\nImageViewer\nSettings", "boundary", 7),
        "service": b.box(3.4, 5.95, 2.3, 0.9, "<<package>>\nservice\nImageService\nSearchService\nEditService", "control", 7),
        "scanner": b.box(6.15, 5.95, 2.2, 0.9, "<<package>>\nscanner\nScanTask\nDirectoryScanner", "green", 7),
        "ai": b.box(8.85, 5.95, 2.25, 0.9, "<<package>>\nai\nAIService\nOpenAICompatible", "purple", 7),
        "dao": b.box(3.4, 4.35, 2.3, 0.9, "<<package>>\ndao\nImageDao\nTagDao\nVersionDao", "database", 7),
        "model": b.box(6.15, 4.35, 2.2, 0.9, "<<package>>\nmodel\nImageFile\nTag\nVersion", "entity", 7),
        "util": b.box(8.85, 4.35, 2.25, 0.9, "<<package>>\nutil / config\nImageUtil\nFileUtil\nAIConfig", "gray", 7),
        "db": b.box(3.4, 2.55, 2.3, 0.9, "<<database>>\nPostgreSQL\nschema.sql\nviews/indexes", "database", 7),
        "fs": b.box(6.15, 2.55, 2.2, 0.9, "<<storage>>\n本地文件系统\n原图 / .versions", "gray", 7),
    }
    b.route((boxes["ui"].right, boxes["ui"].cy), (boxes["service"].left, boxes["service"].cy))
    b.route((boxes["service"].right, boxes["service"].cy), (boxes["scanner"].left, boxes["scanner"].cy))
    b.line(boxes["service"].cx, boxes["service"].top, boxes["service"].cx, 7.35, False)
    b.line(boxes["service"].cx, 7.35, boxes["ai"].cx, 7.35, False)
    b.line(boxes["ai"].cx, 7.35, boxes["ai"].cx, boxes["ai"].top, True)
    b.label((boxes["service"].cx + boxes["ai"].cx) / 2 - 0.5, 7.42, 1.0, 0.18, "optional AI", 6.6)
    b.route((boxes["service"].cx, boxes["service"].bottom), (boxes["dao"].cx, boxes["dao"].top))
    b.route((boxes["dao"].right, boxes["dao"].cy), (boxes["model"].left, boxes["model"].cy))
    b.route((boxes["scanner"].cx, boxes["scanner"].bottom), (boxes["model"].cx, boxes["model"].top))
    b.route((boxes["ai"].cx, boxes["ai"].bottom), (boxes["util"].cx, boxes["util"].top))
    b.route((boxes["dao"].cx, boxes["dao"].bottom), (boxes["db"].cx, boxes["db"].top))
    b.line(boxes["service"].cx + 0.35, boxes["service"].bottom, boxes["service"].cx + 0.35, 3.75, False)
    b.line(boxes["service"].cx + 0.35, 3.75, boxes["fs"].cx, 3.75, False)
    b.line(boxes["fs"].cx, 3.75, boxes["fs"].cx, boxes["fs"].top, True)
    b.box(0.8, 1.05, 10.9, 0.55, "分层约束：Controller 只做界面协调；Service 负责业务事务；DAO 只封装数据库；AI 与本地功能解耦。", "warn", 8)
    b.save(EXP3 / "图1_体系结构分层包图.vsdx", EVIDENCE / "图1_体系结构分层包图.png")


def add_deployment(app):
    b = VisioBuilder(app, "图2 物理部署图")
    user = b.box(0.55, 5.8, 2.25, 1.0, "<<device>>\n用户 Windows PC\nJava 21 / JavaFX", "boundary", 7.5, True)
    app_box = b.box(0.9, 4.75, 1.55, 0.55, "<<artifact>>\nimage-manager.jar", "white", 7)
    cfg = b.box(0.9, 4.05, 1.55, 0.55, "本机配置\nDB / AI endpoint", "white", 7)
    disk = b.box(3.8, 5.25, 2.0, 1.0, "<<storage>>\n本地图片目录\n原图 / .versions", "gray", 7.5, True)
    pg = b.box(7.05, 5.25, 2.15, 1.0, "<<database server>>\nPostgreSQL\nmetadata schema", "database", 7.5, True)
    ai = b.box(9.95, 5.25, 2.1, 1.0, "<<external service>>\nOpenAI-compatible\nVision / NL2SQL", "purple", 7.2, True)
    pkg = b.box(0.75, 2.55, 2.1, 0.9, "<<artifact>>\nWindows portable zip\n带启动脚本/运行时", "green", 7.2)
    schema = b.box(7.2, 3.55, 1.85, 0.55, "schema.sql\ndata.sql", "white", 7)
    b.route((user.right, user.cy), (disk.left, disk.cy), "读取/写入图片")
    b.route((user.right, user.cy - 0.2), (pg.left, pg.cy - 0.2), "JDBC 5432")
    b.route((user.right, user.cy + 0.2), (ai.left, ai.cy + 0.2), "HTTPS 可选")
    b.route((schema.cx, schema.top), (pg.cx, pg.bottom), "初始化")
    b.route((pkg.cx, pkg.top), (user.cx, user.bottom), "启动")
    b.route((cfg.right, cfg.cy), (pg.left, pg.bottom + 0.15), "连接参数")
    b.box(3.75, 1.45, 5.4, 0.55, "降级策略：数据库未就绪时进入初始化向导；外部 AI 不可用时保留本地浏览、查看、重命名和编辑。", "warn", 7.5)
    b.save(EXP3 / "图2_物理部署图.vsdx", EVIDENCE / "图2_物理部署图.png")


def add_component(app):
    b = VisioBuilder(app, "图3 构件图")
    comps = {
        "view": b.box(0.55, 5.95, 2.0, 0.75, "<<component>>\nJavaFX Views\nFXML / CSS", "boundary", 7.5),
        "ctrl": b.box(3.0, 5.95, 2.1, 0.75, "<<component>>\nControllers\nMain / Editor / Setup", "boundary", 7.5),
        "svc": b.box(5.55, 5.95, 2.1, 0.75, "<<component>>\nApplication Services\nImage / Search / Edit", "control", 7.5),
        "scan": b.box(8.05, 6.65, 2.05, 0.65, "<<component>>\nScanner", "green", 7.5),
        "ai": b.box(8.05, 5.55, 2.05, 0.65, "<<component>>\nAI Adapter", "purple", 7.5),
        "dao": b.box(5.55, 4.2, 2.1, 0.75, "<<component>>\nDAO / Repository", "database", 7.5),
        "model": b.box(3.0, 4.2, 2.1, 0.75, "<<component>>\nDomain Model", "entity", 7.5),
        "db": b.box(8.05, 3.85, 2.05, 0.65, "<<database>>\nPostgreSQL", "database", 7.5),
        "fs": b.box(10.55, 4.75, 1.9, 0.65, "<<storage>>\nFile System", "gray", 7.5),
        "cfg": b.box(10.55, 6.0, 1.9, 0.65, "<<component>>\nSettings", "warn", 7.5),
    }
    links = [
        ("view", "ctrl", "events"),
        ("ctrl", "svc", "service API"),
        ("svc", "scan", "scan task"),
        ("svc", "ai", "AI task"),
        ("svc", "dao", "transaction"),
        ("dao", "db", "JDBC"),
        ("svc", "fs", "file IO"),
        ("svc", "model", "DTO"),
        ("cfg", "ai", "endpoint"),
        ("cfg", "db", "DB params"),
    ]
    for a, c, label in links:
        b.route((comps[a].right, comps[a].cy), (comps[c].left, comps[c].cy), label)
    b.box(0.8, 1.2, 11.0, 0.55, "组件边界：界面事件进入 Controller；业务由 Service 编排；DAO 统一访问数据库；AI 与文件系统都通过服务层隔离。", "warn", 7.5)
    b.save(EXP3 / "图3_构件图.vsdx", EVIDENCE / "图3_构件图.png")


def add_sequence_ui(app):
    participants = [
        ("机主", "actor"),
        ("WelcomeDialog", "boundary"),
        ("MainView", "boundary"),
        ("SettingsView", "boundary"),
        ("ImageViewerView", "boundary"),
        ("SlideshowView", "boundary"),
        ("RenameDialog", "boundary"),
        ("DatabaseSetupDialog", "boundary"),
    ]
    messages = [
        {"from": "机主", "to": "WelcomeDialog", "y": 6.25, "label": "启动应用"},
        {"from": "WelcomeDialog", "to": "MainView", "y": 5.85, "label": "gotoMain(scanRoot)", "activate": "MainView", "y_top": 5.95, "y_bottom": 4.45},
        {"from": "机主", "to": "SettingsView", "y": 5.45, "label": "openSettings()"},
        {"from": "SettingsView", "to": "MainView", "y": 5.05, "label": "saveAndRefresh()", "return": True},
        {"from": "机主", "to": "ImageViewerView", "y": 4.55, "label": "doubleClickThumbnail(imageId)"},
        {"from": "ImageViewerView", "to": "SlideshowView", "y": 4.15, "label": "startSlideshow(list,index)"},
        {"from": "SlideshowView", "to": "MainView", "y": 3.75, "label": "closeAndReturn()", "return": True},
        {"from": "机主", "to": "RenameDialog", "y": 3.25, "label": "rightClickRename(selected)"},
        {"from": "RenameDialog", "to": "MainView", "y": 2.85, "label": "renameResult(success)", "return": True},
        {"from": "MainView", "to": "DatabaseSetupDialog", "y": 2.35, "label": "openWhenDbUnavailable()"},
        {"from": "DatabaseSetupDialog", "to": "MainView", "y": 1.95, "label": "dbReadyAndReload()", "return": True},
    ]
    frames = [
        {"label": "opt [数据库未连接]", "top": 2.55, "bottom": 1.75, "from": 2, "to": 7},
        {"label": "opt [多选图片]", "top": 3.45, "bottom": 2.65, "from": 0, "to": 6},
    ]
    sequence_diagram(app, "图4 用户界面跳转顺序图", participants, messages, EXP3 / "图4_用户界面跳转顺序图.vsdx", EVIDENCE / "图4_用户界面跳转顺序图.png", frames)


def add_sequence_thumbnail(app):
    participants = [
        ("机主", "actor"),
        ("MainView", "boundary"),
        ("MainController", "control"),
        ("ImageServiceImpl", "control"),
        ("ImageDaoImpl", "entity"),
        ("ImageUtil", "control"),
        ("images", "database"),
    ]
    messages = [
        {"from": "机主", "to": "MainView", "y": 6.25, "label": "selectDirectory(dirId)"},
        {"from": "MainView", "to": "MainController", "y": 5.85, "label": "loadDirectory(dirId)", "activate": "MainController", "y_top": 5.95, "y_bottom": 2.0},
        {"from": "MainController", "to": "ImageServiceImpl", "y": 5.45, "label": "getImages(dirId)", "activate": "ImageServiceImpl", "y_top": 5.55, "y_bottom": 3.05},
        {"from": "ImageServiceImpl", "to": "ImageDaoImpl", "y": 5.05, "label": "findByDirectory(dirId)"},
        {"from": "ImageDaoImpl", "to": "images", "y": 4.65, "label": "SELECT image metadata"},
        {"from": "images", "to": "ImageDaoImpl", "y": 4.25, "label": "image rows", "return": True},
        {"from": "ImageDaoImpl", "to": "ImageServiceImpl", "y": 3.85, "label": "List<ImageFile>", "return": True},
        {"from": "ImageServiceImpl", "to": "ImageUtil", "y": 3.45, "label": "generateMissingThumbnail()", "activate": "ImageUtil", "y_top": 3.55, "y_bottom": 2.95},
        {"from": "ImageUtil", "to": "ImageServiceImpl", "y": 3.05, "label": "thumbnail bytes", "return": True},
        {"from": "ImageServiceImpl", "to": "ImageDaoImpl", "y": 2.65, "label": "updateThumbnail(imageId)"},
        {"from": "ImageServiceImpl", "to": "MainController", "y": 2.25, "label": "imageCards", "return": True},
        {"from": "MainController", "to": "MainView", "y": 1.85, "label": "renderCards()", "return": True},
    ]
    frames = [{"label": "loop [缩略图缺失]", "top": 3.7, "bottom": 2.45, "from": 2, "to": 6}]
    sequence_diagram(app, "图5 缩略图预览用例实现顺序图", participants, messages, EXP3 / "图5_缩略图预览用例实现顺序图.vsdx", EVIDENCE / "图5_缩略图预览用例实现顺序图.png", frames)


def add_sequence_rename(app):
    participants = [
        ("机主", "actor"),
        ("RenameDialog", "boundary"),
        ("MainController", "control"),
        ("ImageServiceImpl", "control"),
        ("FileSystem", "entity"),
        ("ImageDaoImpl", "entity"),
        ("operation_logs", "database"),
    ]
    messages = [
        {"from": "机主", "to": "RenameDialog", "y": 6.25, "label": "confirm(prefix,start,digits)"},
        {"from": "RenameDialog", "to": "MainController", "y": 5.85, "label": "batchRenameRequest()", "activate": "MainController", "y_top": 5.95, "y_bottom": 1.75},
        {"from": "MainController", "to": "ImageServiceImpl", "y": 5.45, "label": "batchRename(images,rule)", "activate": "ImageServiceImpl", "y_top": 5.55, "y_bottom": 2.05},
        {"from": "ImageServiceImpl", "to": "ImageServiceImpl", "type": "self", "y": 5.05, "label": "validateNamesAndConflict()"},
        {"from": "ImageServiceImpl", "to": "FileSystem", "y": 4.6, "label": "rename(oldPath,newPath)"},
        {"from": "FileSystem", "to": "ImageServiceImpl", "y": 4.2, "label": "fileOk", "return": True},
        {"from": "ImageServiceImpl", "to": "ImageDaoImpl", "y": 3.8, "label": "updatePathAndName()"},
        {"from": "ImageDaoImpl", "to": "operation_logs", "y": 3.4, "label": "trigger log"},
        {"from": "operation_logs", "to": "ImageDaoImpl", "y": 3.0, "label": "logged", "return": True},
        {"from": "ImageDaoImpl", "to": "ImageServiceImpl", "y": 2.6, "label": "updateSuccess", "return": True},
        {"from": "ImageServiceImpl", "to": "MainController", "y": 2.2, "label": "commit / rollback result", "return": True},
        {"from": "MainController", "to": "RenameDialog", "y": 1.8, "label": "showResult()", "return": True},
    ]
    frames = [
        {"label": "loop [每张选中图片]", "top": 4.85, "bottom": 2.85, "from": 2, "to": 6},
        {"label": "alt [失败则回滚]", "top": 2.55, "bottom": 1.55, "from": 1, "to": 6},
    ]
    sequence_diagram(app, "图6 批量重命名用例实现顺序图", participants, messages, EXP3 / "图6_批量重命名用例实现顺序图.vsdx", EVIDENCE / "图6_批量重命名用例实现顺序图.png", frames)


def add_sequence_ai(app):
    participants = [
        ("机主", "actor"),
        ("MainView", "boundary"),
        ("SearchService", "control"),
        ("AIService", "control"),
        ("AiTagStorageService", "control"),
        ("TagDaoImpl", "entity"),
        ("PostgreSQL", "database"),
    ]
    messages = [
        {"from": "机主", "to": "MainView", "y": 6.25, "label": "scanTagsOrAiSearch()"},
        {"from": "MainView", "to": "SearchService", "y": 5.85, "label": "dispatch(mode,input)", "activate": "SearchService", "y_top": 5.95, "y_bottom": 1.7},
        {"from": "SearchService", "to": "AIService", "y": 5.45, "label": "analyzeImage / nl2sql", "activate": "AIService", "y_top": 5.55, "y_bottom": 4.35},
        {"from": "AIService", "to": "SearchService", "y": 5.05, "label": "description/tags/sql", "return": True},
        {"from": "SearchService", "to": "SearchService", "type": "self", "y": 4.65, "label": "validateSqlSelectOnly()"},
        {"from": "SearchService", "to": "AiTagStorageService", "y": 4.15, "label": "storeTags(result)", "activate": "AiTagStorageService", "y_top": 4.25, "y_bottom": 3.25},
        {"from": "AiTagStorageService", "to": "TagDaoImpl", "y": 3.75, "label": "upsertTagsAndAnalysis()"},
        {"from": "TagDaoImpl", "to": "PostgreSQL", "y": 3.35, "label": "INSERT/SELECT metadata"},
        {"from": "PostgreSQL", "to": "TagDaoImpl", "y": 2.95, "label": "rows", "return": True},
        {"from": "TagDaoImpl", "to": "SearchService", "y": 2.55, "label": "imageResults", "return": True},
        {"from": "SearchService", "to": "MainView", "y": 2.15, "label": "renderResultCards()", "return": True},
        {"from": "MainView", "to": "机主", "y": 1.75, "label": "showResult()", "return": True},
    ]
    frames = [
        {"label": "opt [AI 已配置]", "top": 5.7, "bottom": 4.35, "from": 1, "to": 4},
        {"label": "alt [SQL 安全通过 / 拒绝]", "top": 4.85, "bottom": 2.25, "from": 1, "to": 6},
    ]
    sequence_diagram(app, "图7 AI标签扫描与智能搜索用例实现顺序图", participants, messages, EXP3 / "图7_AI标签扫描与智能搜索用例实现顺序图.vsdx", EVIDENCE / "图7_AI标签扫描与智能搜索用例实现顺序图.png", frames)


def add_class_diagram(app):
    b = VisioBuilder(app, "图8 核心设计类图")
    def cls(x, y, w, h, name, attrs, methods, fill="white"):
        b.box(x, y, w, h, "", fill, 6.5)
        header_h = 0.28
        b.box(x, y + h - header_h, w, header_h, name, fill, 7.2, True)
        b.line(x, y + h - header_h, x + w, y + h - header_h, False, False, 0.8)
        b.line(x, y + h - header_h - 0.42, x + w, y + h - header_h - 0.42, False, False, 0.8)
        b.label(x + 0.04, y + h - header_h - 0.38, w - 0.08, 0.34, "\n".join(attrs), 5.9, fill)
        b.label(x + 0.04, y + 0.06, w - 0.08, h - header_h - 0.5, "\n".join(methods), 5.9, fill)
        return Box(x, y, w, h)
    boxes = {
        "MainController": cls(0.45, 4.65, 2.15, 1.55, "<<boundary>>\nMainController", ["- imageService", "- searchService"], ["+ loadDirectory()", "+ renderCards()", "+ startScan()"], "boundary"),
        "ImageServiceImpl": cls(3.25, 5.25, 2.2, 1.35, "<<control>>\nImageServiceImpl", ["- imageDao", "- directoryDao"], ["+ loadImages()", "+ batchRename()", "+ generateAndCacheThumbnail()"], "control"),
        "SearchService": cls(3.25, 3.55, 2.2, 1.35, "<<control>>\nSearchService", ["- aiService", "- tagDao"], ["+ search()", "+ validateSql()"], "control"),
        "EditService": cls(3.25, 1.85, 2.2, 1.35, "<<control>>\nEditService", ["- versionDao"], ["+ saveEditedVersion()", "+ restoreVersion()"], "control"),
        "ImageDao": cls(6.15, 5.25, 2.1, 1.35, "<<entity>>\nImageDaoImpl", ["- dataSource"], ["+ findByDirectory()", "+ updatePath()", "+ updateThumbnail()"], "database"),
        "TagDao": cls(6.15, 3.55, 2.1, 1.35, "<<entity>>\nTagDaoImpl", ["- dataSource"], ["+ searchImages()", "+ upsertTags()", "+ saveAnalysis()"], "database"),
        "VersionDao": cls(6.15, 1.85, 2.1, 1.35, "<<entity>>\nVersionDaoImpl", ["- dataSource"], ["+ createVersion()", "+ markCurrent()", "+ findHistory()"], "database"),
        "ImageUtil": cls(9.05, 5.25, 2.05, 1.35, "<<utility>>\nImageUtil", [" "], ["+ readSize()", "+ makeThumbnail()", "+ toBytes()"], "util"),
        "AIService": cls(9.05, 3.55, 2.05, 1.35, "<<interface>>\nAIService", [" "], ["+ analyzeImage()", "+ naturalLanguageToSql()"], "purple"),
        "ImageFile": cls(9.05, 1.85, 2.05, 1.35, "<<entity>>\nImageFile", ["id, path, name", "width, height"], ["+ hasThumbnail()", "+ isAiProcessed()"], "entity"),
        "PostgreSQL": cls(11.65, 3.3, 1.25, 1.65, "<<database>>\nPostgreSQL", ["images", "tags", "versions"], ["views", "triggers"], "database"),
    }
    b.route((boxes["MainController"].right, boxes["MainController"].cy + 0.35), (boxes["ImageServiceImpl"].left, boxes["ImageServiceImpl"].cy))
    b.route((boxes["MainController"].right, boxes["MainController"].cy), (boxes["SearchService"].left, boxes["SearchService"].cy))
    b.route((boxes["MainController"].right, boxes["MainController"].cy - 0.35), (boxes["EditService"].left, boxes["EditService"].cy))
    b.route((boxes["ImageServiceImpl"].right, boxes["ImageServiceImpl"].cy), (boxes["ImageDao"].left, boxes["ImageDao"].cy))
    b.route((boxes["SearchService"].right, boxes["SearchService"].cy), (boxes["TagDao"].left, boxes["TagDao"].cy))
    b.route((boxes["EditService"].right, boxes["EditService"].cy), (boxes["VersionDao"].left, boxes["VersionDao"].cy))
    b.line(boxes["ImageServiceImpl"].cx, boxes["ImageServiceImpl"].top, boxes["ImageServiceImpl"].cx, 7.0, False)
    b.line(boxes["ImageServiceImpl"].cx, 7.0, boxes["ImageUtil"].cx, 7.0, False)
    b.line(boxes["ImageUtil"].cx, 7.0, boxes["ImageUtil"].cx, boxes["ImageUtil"].top, True)
    b.line(boxes["SearchService"].cx, boxes["SearchService"].top, boxes["SearchService"].cx, 5.05, False)
    b.line(boxes["SearchService"].cx, 5.05, boxes["AIService"].cx, 5.05, False)
    b.line(boxes["AIService"].cx, 5.05, boxes["AIService"].cx, boxes["AIService"].top, True)
    b.save(EXP3 / "图8_核心设计类图.vsdx", EVIDENCE / "图8_核心设计类图.png")


def add_data_model(app, path: Path, png: Path, title: str):
    b = VisioBuilder(app, title)
    def entity(x, y, w, h, name, fields, fill="entity"):
        b.box(x, y, w, h, "", fill, 6.2)
        b.box(x, y + h - 0.28, w, 0.28, name, fill, 7.2, True)
        b.line(x, y + h - 0.28, x + w, y + h - 0.28, False, False, 0.8)
        b.label(x + 0.04, y + 0.08, w - 0.08, h - 0.4, "\n".join(fields), 5.9, fill)
        return Box(x, y, w, h)
    boxes = {
        "directories": entity(0.55, 5.35, 1.65, 1.05, "directories", ["PK id", "dir_path", "parent_id"], "database"),
        "images": entity(2.75, 5.05, 1.85, 1.35, "images", ["PK id", "directory_id", "file_path", "thumbnail"], "database"),
        "image_tags": entity(5.2, 5.2, 1.7, 1.05, "image_tags", ["image_id", "tag_id", "confidence"], "entity"),
        "tags": entity(7.45, 5.2, 1.65, 1.05, "tags", ["PK id", "name", "category_id"], "entity"),
        "tag_categories": entity(9.75, 5.2, 1.75, 1.05, "tag_categories", ["PK id", "name"], "entity"),
        "ai": entity(5.15, 3.25, 1.85, 1.15, "ai_analysis_results", ["image_id", "description", "confidence"], "purple"),
        "versions": entity(2.75, 3.05, 1.85, 1.25, "image_versions", ["image_id", "version_num", "file_path", "is_current"], "database"),
        "edit": entity(0.55, 3.05, 1.65, 1.25, "image_edit_operations", ["version_id", "operation_type", "params"], "database"),
        "logs": entity(7.45, 3.15, 1.65, 1.05, "operation_logs", ["target_type", "operation_type", "operated_at"], "gray"),
        "search": entity(9.75, 3.15, 1.75, 1.05, "search_history", ["query_text", "search_mode", "result_count"], "gray"),
        "settings": entity(9.75, 1.5, 1.75, 0.9, "app_settings", ["setting_key", "setting_value"], "warn"),
    }
    links = [
        ("directories", "images", "1", "n"),
        ("images", "image_tags", "1", "n"),
        ("tags", "image_tags", "1", "n"),
        ("tag_categories", "tags", "1", "n"),
        ("images", "ai", "1", "n"),
        ("images", "versions", "1", "n"),
        ("versions", "edit", "1", "n"),
        ("images", "logs", "1", "n"),
        ("images", "search", "result", "n"),
    ]
    for a, c, _la, _lc in links:
        b.route((boxes[a].right, boxes[a].cy), (boxes[c].left, boxes[c].cy))
    b.box(0.8, 1.05, 6.6, 0.45, "设计边界：原图保存在本地文件系统；数据库保存路径、缩略图、标签、版本、检索与日志。", "warn", 7.2)
    b.save(path, png)


def activity_diagram(app, title: str, lanes: list[str], steps: list[tuple[int, str, str]], links: list[tuple[int, int, str]], path: Path, png: Path):
    b = VisioBuilder(app, title)
    lane_w = (b.page_w - 1.0) / len(lanes)
    lane_boxes = []
    for i, lane in enumerate(lanes):
        x = 0.5 + i * lane_w
        b.box(x, 0.75, lane_w, 6.15, "", "white", 6.5)
        b.box(x, 6.9, lane_w, 0.35, lane, ["boundary", "control", "green", "database", "gray"][i % 5], 8, True)
        lane_boxes.append(Box(x, 0.75, lane_w, 6.15))
    step_boxes = []
    y = 6.25
    for lane_idx, text, fill in steps:
        lane = lane_boxes[lane_idx]
        h = 0.43
        box = b.box(lane.x + 0.12, y, lane.w - 0.24, h, text, fill, 6.6)
        step_boxes.append(box)
        y -= 0.48
    for a, c, label in links:
        start = (step_boxes[a].cx, step_boxes[a].bottom)
        end = (step_boxes[c].cx, step_boxes[c].top)
        b.route(start, end, label)
    b.save(path, png)


def add_activity_rename(app):
    steps = [
        (0, "选择多张图片\n输入规则", "boundary"),
        (1, "生成目标名\n校验空值/非法字符", "control"),
        (1, "检测同名冲突\n建立事务上下文", "control"),
        (2, "逐个重命名磁盘文件", "gray"),
        (3, "更新 images 路径\n触发日志", "database"),
        (1, "全部成功则提交", "control"),
        (1, "任一步失败则回滚\n提示冲突文件", "error"),
        (0, "刷新缩略图列表\n清空旧选择", "boundary"),
    ]
    links = [(0, 1, ""), (1, 2, ""), (2, 3, "ok"), (3, 4, ""), (4, 5, "ok"), (5, 7, ""), (2, 6, "fail"), (3, 6, "fail"), (6, 7, "show")]
    activity_diagram(app, "图10 批量重命名事务活动图", ["用户界面", "ImageService", "文件系统", "PostgreSQL"], steps, links, EXP3 / "图10_批量重命名事务活动图.vsdx", EVIDENCE / "图10_批量重命名事务活动图.png")


def add_activity_scan(app):
    steps = [
        (0, "选择扫描目录\n点击开始", "boundary"),
        (1, "创建 ScanTask\n绑定进度", "control"),
        (2, "递归遍历目录\n过滤图片扩展名", "green"),
        (2, "读取尺寸/格式\n跳过损坏文件", "green"),
        (3, "findOrCreateDirectory", "database"),
        (3, "批量写入 images\n更新 thumbnail", "database"),
        (1, "汇总新增/跳过/失败\n发布进度", "control"),
        (0, "刷新目录树与缩略图\n显示完成状态", "boundary"),
    ]
    links = [(0, 1, ""), (1, 2, ""), (2, 3, ""), (3, 4, ""), (4, 5, ""), (5, 6, ""), (6, 7, "")]
    activity_diagram(app, "图11 目录扫描入库详细活动图", ["用户界面", "ScanTask", "DirectoryScanner", "PostgreSQL"], steps, links, EXP3 / "图11_目录扫描入库详细活动图.vsdx", EXPORT_DIR / "图11_目录扫描入库详细活动图.png")


def add_subsystem(app):
    b = VisioBuilder(app, "图13 子系统与构件职责展开图")
    groups = {
        "ui": b.box(0.55, 5.55, 2.15, 1.25, "界面交互子系统\nMainController\nViewer/Editor/Settings\nDatabaseSetupDialog", "boundary", 7.2),
        "image": b.box(3.25, 5.55, 2.15, 1.25, "图片业务子系统\nImageServiceImpl\nEditService\nImageUtil / FileUtil", "control", 7.2),
        "scan": b.box(5.95, 5.55, 2.15, 1.25, "扫描进度子系统\nScanTask\nDirectoryScanner\nProgressEstimator", "green", 7.2),
        "ai": b.box(8.65, 5.55, 2.15, 1.25, "搜索与AI子系统\nSearchService\nAIService\nAiTagStorage", "purple", 7.2),
        "dao": b.box(3.25, 3.35, 2.15, 1.25, "数据持久化子系统\nImageDao\nTagDao\nVersionDao", "database", 7.2),
        "cfg": b.box(5.95, 3.35, 2.15, 1.25, "配置启动子系统\nApp\nDatabaseBootstrap\nSettingsDao", "warn", 7.2),
        "db": b.box(8.65, 3.35, 2.15, 1.25, "PostgreSQL\nimages / tags\nversions / logs", "database", 7.2),
    }
    for a, c, label in [("ui", "image", "calls"), ("image", "scan", "scan"), ("image", "dao", "metadata"), ("ai", "dao", "tags/sql"), ("cfg", "db", "init"), ("dao", "db", "JDBC"), ("ui", "cfg", "setup")]:
        b.route((groups[a].right, groups[a].cy), (groups[c].left, groups[c].cy), label)
    b.box(0.85, 1.35, 10.7, 0.55, "职责规则：界面只协调状态；业务子系统处理事务；DAO 屏蔽 SQL；配置启动子系统负责新环境修复。", "warn", 7.5)
    b.save(EXP3 / "图13_子系统与构件职责展开图.vsdx", EXPORT_DIR / "图13_子系统与构件职责展开图.png")


def add_ai_security(app):
    participants = [
        ("机主", "actor"),
        ("SettingsView", "boundary"),
        ("SearchService", "control"),
        ("AIService", "control"),
        ("SqlSafetyGuard", "control"),
        ("TagDaoImpl", "entity"),
        ("PostgreSQL", "database"),
    ]
    messages = [
        {"from": "机主", "to": "SettingsView", "y": 6.25, "label": "saveEndpointAndModel()"},
        {"from": "机主", "to": "SearchService", "y": 5.8, "label": "naturalLanguageSearch(text)", "activate": "SearchService", "y_top": 5.9, "y_bottom": 1.7},
        {"from": "SearchService", "to": "AIService", "y": 5.35, "label": "nl2sql(text, schemaHint)", "activate": "AIService", "y_top": 5.45, "y_bottom": 4.75},
        {"from": "AIService", "to": "SearchService", "y": 4.95, "label": "candidate SQL", "return": True},
        {"from": "SearchService", "to": "SqlSafetyGuard", "y": 4.5, "label": "validateSelectOnly(sql)", "activate": "SqlSafetyGuard", "y_top": 4.6, "y_bottom": 3.85},
        {"from": "SqlSafetyGuard", "to": "SearchService", "y": 4.05, "label": "allow / reject", "return": True},
        {"from": "SearchService", "to": "TagDaoImpl", "y": 3.55, "label": "executeSafeSearch(sql)"},
        {"from": "TagDaoImpl", "to": "PostgreSQL", "y": 3.15, "label": "SELECT from v_image_search"},
        {"from": "PostgreSQL", "to": "TagDaoImpl", "y": 2.75, "label": "image rows", "return": True},
        {"from": "TagDaoImpl", "to": "SearchService", "y": 2.35, "label": "SearchResult", "return": True},
        {"from": "SearchService", "to": "机主", "y": 1.9, "label": "show cards / safety error", "return": True},
    ]
    frames = [{"label": "alt [安全通过 / 拒绝执行]", "top": 4.75, "bottom": 1.75, "from": 1, "to": 6}]
    sequence_diagram(app, "图14 AI标签扫描与智能搜索安全链路图", participants, messages, EXP3 / "图14_AI标签扫描与智能搜索安全链路图.vsdx", EXPORT_DIR / "图14_AI标签扫描与智能搜索安全链路图.png", frames, "只允许读取型 SELECT，禁止修改性 SQL。")


def add_bootstrap(app):
    steps = [
        (0, "启动应用", "boundary"),
        (1, "读取本机配置\n环境变量", "control"),
        (2, "检测连接池", "database"),
        (2, "检查 schema\n版本是否完整", "database"),
        (0, "加载主界面", "boundary"),
        (0, "显示数据库不可用\n打开初始化向导", "warn"),
        (1, "填写连接参数\n测试连接", "control"),
        (2, "创建数据库\n执行 schema/data", "database"),
        (1, "保存配置\n刷新连接状态", "control"),
        (0, "返回主界面\n启用数据库功能", "boundary"),
    ]
    links = [(0, 1, ""), (1, 2, ""), (2, 3, ""), (3, 4, "ok"), (3, 5, "fail"), (5, 6, ""), (6, 7, ""), (7, 8, "ok"), (8, 9, "")]
    activity_diagram(app, "图15 数据库初始化与离线降级流程图", ["界面", "启动/配置服务", "PostgreSQL"], steps, links, EXP3 / "图15_数据库初始化与离线降级流程图.vsdx", EXPORT_DIR / "图15_数据库初始化与离线降级流程图.png")


def add_sequence_versions(app):
    participants = [
        ("机主", "actor"),
        ("ImageEditorController", "boundary"),
        ("EditService", "control"),
        ("ImageUtil", "control"),
        ("VersionDaoImpl", "entity"),
        ("FileSystem", "entity"),
        ("images/image_versions", "database"),
    ]
    messages = [
        {"from": "机主", "to": "ImageEditorController", "y": 6.25, "label": "saveEditedImage()"},
        {"from": "ImageEditorController", "to": "EditService", "y": 5.85, "label": "saveEditedVersion(image,ops)", "activate": "EditService", "y_top": 5.95, "y_bottom": 1.7},
        {"from": "EditService", "to": "VersionDaoImpl", "y": 5.45, "label": "ensureOriginalVersion()"},
        {"from": "VersionDaoImpl", "to": "images/image_versions", "y": 5.05, "label": "SELECT/INSERT original"},
        {"from": "images/image_versions", "to": "VersionDaoImpl", "y": 4.65, "label": "originalReady", "return": True},
        {"from": "EditService", "to": "ImageUtil", "y": 4.25, "label": "composeAndThumbnail()", "activate": "ImageUtil", "y_top": 4.35, "y_bottom": 3.75},
        {"from": "ImageUtil", "to": "EditService", "y": 3.85, "label": "imageBytes/thumb", "return": True},
        {"from": "EditService", "to": "FileSystem", "y": 3.45, "label": "write .versions file"},
        {"from": "FileSystem", "to": "EditService", "y": 3.05, "label": "filePath", "return": True},
        {"from": "EditService", "to": "VersionDaoImpl", "y": 2.65, "label": "insertVersionAndMarkCurrent()"},
        {"from": "VersionDaoImpl", "to": "images/image_versions", "y": 2.25, "label": "UPDATE images + INSERT version"},
        {"from": "images/image_versions", "to": "VersionDaoImpl", "y": 1.85, "label": "commit", "return": True},
        {"from": "EditService", "to": "ImageEditorController", "y": 1.45, "label": "refreshTimeline()", "return": True},
    ]
    frames = [{"label": "opt [首次编辑]", "top": 5.65, "bottom": 4.45, "from": 1, "to": 6}]
    sequence_diagram(app, "图16 图片编辑与版本历史详细设计图", participants, messages, EXP3 / "图16_图片编辑与版本历史详细设计图.vsdx", EXPORT_DIR / "图16_图片编辑与版本历史详细设计图.png", frames, "编辑保存必须保留旧版本，恢复时同步文件与数据库当前标记。")


def build_html_review() -> None:
    REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    items = [
        ("图4 原图", ROOT / "target" / "exp3-template-review" / "compare_图4_用户界面跳转顺序图.png"),
        ("RR06 用户界面跳转顺序图", ROOT / "target" / "exp3-template-review" / "compare_用户界面跳转顺序图.png"),
    ]
    for p in sorted(EVIDENCE.glob("图*_*.png")):
        if re.match(r"图(?:[1-9]|10)_", p.name):
            items.append((p.stem, p))
    for p in sorted(EXPORT_DIR.glob("图1[1-6]_*.png")):
        items.append((p.stem, p))
    html_parts = [
        "<!doctype html><meta charset='utf-8'><title>实验3图件重绘预览</title>",
        "<style>body{font-family:Arial,'Microsoft YaHei',sans-serif;margin:24px;background:#f6f7f8;color:#222}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(420px,1fr));gap:18px}.card{background:white;border:1px solid #ddd;padding:12px}.card img{max-width:100%;height:auto;border:1px solid #eee}.name{font-weight:700;margin-bottom:8px}</style>",
        "<h1>实验3图件重绘预览</h1><div class='grid'>",
    ]
    for name, path in items:
        if path.exists():
            rel = path.resolve().as_uri()
            html_parts.append(f"<div class='card'><div class='name'>{html.escape(name)}</div><img src='{rel}'></div>")
    html_parts.append("</div>")
    (REVIEW_DIR / "index.html").write_text("\n".join(html_parts), encoding="utf-8")


def copy_png12() -> None:
    # Keep figure 12 available in the same export folder used by the report builder.
    source = EXPORT_DIR / "图12_数据库导出表关系图.png"
    if source.exists():
        return
    add_data_model(win32.DispatchEx("Visio.Application"), EXP3 / "图12_数据库导出表关系图.vsdx", source, "图12 数据库导出表关系图")


def main() -> None:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    app = win32.DispatchEx("Visio.Application")
    app.Visible = False
    try:
        add_package(app)
        add_deployment(app)
        add_component(app)
        add_sequence_ui(app)
        add_sequence_thumbnail(app)
        add_sequence_rename(app)
        add_sequence_ai(app)
        add_class_diagram(app)
        add_data_model(app, EXP3 / "图9_数据模型设计图.vsdx", EVIDENCE / "图9_数据模型设计图.png", "图9 数据模型设计图")
        add_activity_rename(app)
        add_activity_scan(app)
        add_data_model(app, EXP3 / "图12_数据库导出表关系图.vsdx", EXPORT_DIR / "图12_数据库导出表关系图.png", "图12 数据库导出表关系图")
        add_subsystem(app)
        add_ai_security(app)
        add_bootstrap(app)
        add_sequence_versions(app)
    finally:
        app.Quit()
    build_html_review()
    print("regenerated 16 VSDX files")
    print(REVIEW_DIR / "index.html")


if __name__ == "__main__":
    main()
