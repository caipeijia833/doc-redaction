# Logging and diagnostics / 日志与诊断

## Public-safe by default / 默认不公开原始日志

运行日志和诊断可能包含项目名、文件名、本机用户名、绝对路径、哈希、组件路径、任务标识、压缩包内部路径或错误附近的内容。它们不是“天然脱敏”的，不得直接提交到 Git、Issue、PR 或 Release。

本地安全门禁把逐项原始日志保存在被 Git 忽略的 `audit/results/security-gate-<UTC>/`，公开摘要 `audit/results/security-gate-latest.json` 只保留检查名、通过状态、退出码、离线数据库快照时间和“日志仅在本机保留”标记。

## Diagnostic collection / 诊断采集

1. 先用合成文件复现。
2. 记录版本、系统、架构、任务状态和最小步骤。
3. 删除或替换用户名、绝对路径、项目/文件名、任务 UUID、内容片段、原件哈希、网络地址和内部目录结构。
4. 删除 Token、Cookie、API Key、口令、密钥、还原密文以及任何可恢复原件的材料。
5. 复查压缩包、截图和文件元数据；只提交最小必要摘要。

对安全问题使用 GitHub Private Vulnerability Reporting。即使私密报告也不要上传真实卷宗；使用最小合成样例。

## Retention and cleanup / 保存与清理

本项目不自动替用户制定日志保留期。维护者测试产生的逐次安全门禁日志、浏览器 QA 数据、压力数据和运行数据库均在 `.gitignore` 中排除，应在问题解决和证据摘要生成后按敏感级别清理。跟踪的公开证据只能包含合成输入标识、非本机路径、必要哈希和汇总指标。
