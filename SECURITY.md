# Security Policy / 安全策略

## Supported versions / 支持版本

| Version | Status |
|---|---|
| `0.1.x` | Supported while it is the latest Alpha line / 仅在其为最新 Alpha 分支时维护 |
| `< 0.1.0` | Unsupported / 不支持 |

Alpha 表示接口、数据格式和安全边界仍可能调整，不代表通过生产安全认证。Windows x64 的本地验证最完整；macOS 13 Intel/Apple Silicon 仍须在真实目标机完成验收、签名和公证。

## Report a vulnerability / 报告漏洞

Use [GitHub Private Vulnerability Reporting](https://github.com/caipeijia833/doc-redaction/security/advisories/new). Do not disclose an unpatched vulnerability in a public Issue.

请优先使用上述 GitHub 私密漏洞报告入口。若入口尚未由仓库所有者启用，只能公开提出“需要私密安全联系方式”，不要附带漏洞细节、利用代码或真实文件。

报告至少包含：

- 受影响版本、操作系统和 CPU 架构；
- 最小复现步骤、预期结果、实际结果和影响范围；
- 完全合成或已安全脱敏的样例；
- 已移除本机路径、用户名、Token、密钥、原件口令和还原密文的诊断摘要；
- 已知缓解措施（如有）。

Never upload real case files, personal information, credentials, passwords, API keys, session tokens, restore ciphertext, encryption keys, or raw runtime logs.

## Scope / 范围

属于安全范围：回环 Web 服务、上传与解析、压缩包解包、规则导入、预览、原件加密库及还原、离线升级、子进程隔离、本地模型与随包原生组件。

通常不作为漏洞：仅影响外观且无安全后果的问题；已公开并标明的 Alpha 功能缺口；需要攻击者先取得同一操作系统用户完整权限且没有扩大权限或数据暴露的问题。即使不确定，也可通过私密入口报告，由维护者判断。

## Handling process / 处理流程

本项目暂不承诺固定响应 SLA。维护者应按以下顺序处理：确认收到、复现与分级、准备最小修复和回归测试、生成新版本及校验材料、发布安全公告、与报告者协调披露。修复发布前不要公开可利用细节。

安全修复不得包含真实业务文件。回归样例必须是合成数据或具有明确再分发许可并记录来源的数据。

## Security boundaries / 安全边界

- 服务仅监听 `127.0.0.1`；这不能抵御已在同一系统用户权限下运行的恶意程序。
- “可还原”模式保存的是加密原件；遮挡、栅格化或静音后的结果文件本身不可逆。
- 忘记项目还原口令后无法恢复。维护者没有后门或云端找回能力。
- 详细威胁模型和未完成项见 [design/PRODUCTION_THREAT_MODEL.md](./design/PRODUCTION_THREAT_MODEL.md) 与 [design/IMPLEMENTATION_STATUS.md](./design/IMPLEMENTATION_STATUS.md)。
