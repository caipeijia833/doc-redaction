# GitHub 开源发布说明

本文件说明哪些内容进入 `https://github.com/caipeijia833/doc-redaction`、哪些只作为 GitHub Release 附件，以及当前 `v0.1.0-poc` Windows 离线包的分卷发布方式。主要维护者、版权主体、发布签名责任人和恢复密钥保管人均已由项目所有者确认为 `caipeijia833`；首次源代码提交仅发布经白名单审核的源代码、公开合成样例和治理文档，不发布任何二进制 Release 附件。

## 1. 源码仓库应上传

- `app/src/`、`app/build.gradle`、Gradle Wrapper、依赖锁和依赖校验元数据；
- `app/libs/opencv-4130.jar`、对应许可证和来源/哈希说明；该文件是官方 Java 绑定，不包含本地 DLL；
- `packaging/`、`tools/`、根目录构建/启动/打包脚本；
- `design/`、必要的 `audit/` 脚本、来源清单、公开安全摘要和机器可读构建清单；
- `samples/` 内完全合成的样例；
- `README.md`、`LICENSE`、`LICENSE_POLICY.md`、`THIRD_PARTY_NOTICES.md`、`CONTRIBUTING.md`、`SECURITY.md`；
- `.github/`、`.gitignore`、`.gitattributes` 和 `.gitleaks.toml`；
- `README_EN.md`、`CHANGELOG.md`、`MAINTAINERS.md`、`CODE_OF_CONDUCT.md`、`SUPPORT.md`、`NOTICE`、`GOVERNANCE.md`、`RELEASING.md`、`MAINTAINER_WORKFLOWS.md` 和 `docs/`；
- `audit/evidence/` 内经过筛选的合成压力测试摘要；不得上传对应的大型输入和运行目录。

## 2. 不得进入 Git 历史

以下内容已由 `.gitignore` 排除，不应使用 `git add -f` 强行加入：

- `.tools/`：JDK、Gradle、Tesseract、OpenCV、FFmpeg、whisper.cpp、Qwen/llama.cpp 等本地工具和模型；
- `dist/`、`release-assets/`、`app/build/`：构建输出和 Release 二进制；
- `upstream/`：调研时使用的第三方完整仓库镜像；
- `stress-data/`、`stress-debug/`、`stress-runs/`：最大可达数 GiB 的压力数据和临时运行目录；
- `browser-qa-data/`、`data/`、日志、`.env` 和任何本地运行状态；
- 根目录临时下载的 3 个 OFD；许可证清晰的正式测试副本已位于 `app/src/test/resources/public/ofdrw/`。
- `OPENAI_OSS_APPLICATION_DRAFT.md`、`PRE_PUBLICATION_CHECKLIST.md` 和 `audit/OPEN_SOURCE_READINESS_REVIEW_*.md`：仅供项目所有者本地决策，不属于产品源码或公开证据。

尤其不得把真实卷宗、上传历史、还原密文、口令、密钥、Token、模型缓存或本机绝对路径日志放入公开仓库和 Issue。

## 3. GitHub Release 应上传

> **当前阻断：** 公开源码仓库不受此项影响，但当前 Windows 二进制 Release 尚不能发布。BtbN FFmpeg 构建的对应源代码闭包仍不完整，详见 [`packaging/FFMPEG_SOURCE_COMPLIANCE.md`](./packaging/FFMPEG_SOURCE_COMPLIANCE.md)。Release 生成脚本会在闭包状态不是 `complete` 时失败，禁止通过手工复制旧分卷绕过。

运行：

```powershell
.\tools\New-GitHubReleaseBundle.ps1
```

输出目录默认是 `release-assets/v0.1.0-poc/`。将该目录内的下列文件作为同一个 `v0.1.0-poc` Release 的附件上传：

1. 所有 `doc-redaction-poc-windows-x64.zip.partNNN`；
2. `README-RELEASE.md`；
3. `RELEASE_ASSETS.json`；
4. `PARTS.txt`、`PART_SHA256SUMS.txt`、`FINAL_SHA256.txt`；
5. `RELEASE_SHA256SUMS.txt`；
6. `join-windows.ps1`、`join-unix.sh`；
7. FFmpeg 完整对应源代码闭包、闭包清单和合规说明。

此外应把 `SBOM.cdx.json`、`THIRD_PARTY_NOTICES.md`、`audit/BUILD_MANIFEST.json` 和最近一次公开安全门禁摘要作为独立 Release 附件提供，让审核者无需下载并合并完整离线包即可核验依赖、许可证、构建范围和扫描状态。

不要上传原始 3.63 GB ZIP：它超过 GitHub Release 单文件 2 GiB 限制。不要把分卷提交进 Git；分卷只属于 Release 附件。

## 4. 推荐公开顺序

1. 在本地再次运行安全门禁，并人工检查 `git status` 中即将提交的每个文件；
2. 创建空的公开仓库，先推送源码、许可证和文档；
3. 开启 GitHub Private Vulnerability Reporting；
4. 创建 `v0.1.0-poc` 预发布版，明确标记 `pre-release`；
5. 上传全部分卷和校验/合并文件；
6. 在另一空目录下载全部附件并分别用 Windows、macOS/Linux 合并脚本复验；
7. 比对最终 SHA-256，再公开 Release；
8. 最后再填写 Codex for OSS 申请；申请内容不得把未完成的真实业务或 macOS 验收写成已完成。

## 5. 当前边界

- 推荐把 Release 标为 **Alpha / pre-release**，不要标为 production-ready。
- 当前 Windows 包未做 Authenticode 签名；浏览器和 SmartScreen 可能提示未知发布者。
- macOS 运行包尚未完成目标架构构建、签名、公证和实机验收，不能把 Windows 包改名冒充 macOS 包。
- 第三方二进制和模型的许可证、归属和版本见 `THIRD_PARTY_NOTICES.md` 与各组件清单；发布时必须与分卷同时保留包内许可证。
