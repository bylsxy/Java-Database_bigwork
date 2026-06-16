# AGENTS.md

- pola 负责统筹本仓库数据库系统课程设计最终交付，并管理所有用于查找、审计和建议的 subagent。
- 本次正式交付目录为 `docs/数据库系统基础/最终交付/`，必须包含阶段文档、课程报告、答辩 PPT、打印讲稿、源码与运行包、证据与清单。
- 正式文档不得写入群聊原话、私聊、AI 提示词、截图来源、同学作业参考过程等后台信息；这些内容只能转化为教师反馈、设计依据或验证证据。
- 关键验证命令为 `git diff --check`、`mvn -q -DskipTests compile`、`mvn -q test`、`mvn -q -DskipTests package`，环境允许时还要运行 `powershell -ExecutionPolicy Bypass -File .\scripts\package-stable.ps1`。
- 课程报告、PPT 和讲稿必须围绕数据库设计、功能设计、后台程序设计、界面设计、报告表述和 PPT 表达这些得分点组织，不能夸大未完成的云端/WebDAV 功能。
