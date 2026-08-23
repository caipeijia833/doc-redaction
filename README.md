# 智能文档脱敏系统：单机离线版 Alpha

[English](./README_EN.md) | 简体中文

这是一个面向中英文办公文档、电子卷宗和音视频证据的本地离线脱敏系统。服务与内置模型只监听 `127.0.0.1`，不调用云 API。Windows x64 包内置便携 Tesseract 5.5.2、LGPL-only FFmpeg、whisper.cpp、官方 OpenCV 4.13.0 Java JNI 视觉检测，以及 Qwen3-VL-2B-Instruct Q4_K_M；有至少 4 GiB 可用显存时优先使用 Vulkan，无可用显卡时回退 CPU，内存不足 8 GiB 时自动停用 Qwen 增强而保留确定性规则和 OCR。macOS 包需要在目标架构上提供并封装 Metal 版 llama.cpp、OpenCV JNI 等同架构运行时。

> 当前已进入“单机离线正式版优先”的 Alpha 建设阶段。Windows 本机已完成精确 1 GiB PDF、近上限 1 GiB DOCX/XLSX/PPTX 处理，以及精确 1 GiB TAR 卷宗接收/清单确认。音视频第一阶段已通过合成音轨、画面文字、文本字幕和官方人脸/二维码/中国车牌样例回归，但尚未完成真实噪声音频、长视频、多国车牌、快速运动及 1 GiB 媒体压力验收。任何已有合成结论都不能泛化为生产验收完成。

## 已实现能力

| 能力 | 当前状态 | 处理方式与边界 |
|---|---|---|
| DOCX | 已实现并自动测试 | OOXML 包级事件流改写；正文、表格、页眉页脚、脚注、尾注、批注及元数据；跨 run 等长替换并尽量保留样式 |
| XLSX | 已实现并自动测试 | OOXML 包级事件流改写；工作表、共享/内联字符串、数字单元格、批注、页眉页脚及元数据；敏感公式失败关闭 |
| PPTX | 已实现文本处理并自动测试 | OOXML 包级事件流改写；幻灯片、表格、备注、母版/版式及图表缓存文字；内嵌位图走本地 OCR；OLE/ActiveX/VBA/签名部件失败关闭 |
| PDF | 需本地 OCR，已自动测试 | 先按文本坐标遮挡，再对每页渲染结果执行本地 OCR 复核并栅格化重建；缺少可用 OCR 时失败关闭 |
| OFD | 条件支持、已通过上游实样兼容测试 | OFDRW 逐页渲染、OCR 遮挡后按原页物理宽高重建栅格 OFD；3 个 Apache-2.0 上游文字/JPEG/签章样例已通过渲染解码；真实业务票据和多阅读器仍待验收；原数字签名失效，必须重新签章 |
| PNG/JPG/JPEG/BMP | Windows 已完成合成样例端到端测试 | 本地 OCR 坐标遮挡并重新编码；真实低清、旋转、表格和复杂版面语料仍需召回率验收 |
| MP3/WAV/M4A/FLAC | 第一阶段已实现并自动测试 | 本地 whisper.cpp 多语言 ASR；规则命中的时间段静音；重新编码并清除元数据；分段转写会扩大静音区间 |
| MP4/MOV/MKV | 第一阶段已实现并自动测试 | 抽帧 OCR、人脸、二维码、中国车牌检测并按时间坐标遮挡；全部音轨 ASR；文本字幕脱敏后重新封装；位图字幕和未知轨道移除 |
| 压缩包 | ZIP/TAR/TAR.GZ/7Z 已测试 | 上传后先列出内部清单；支持文件脱敏，不支持文件默认排除；确认后原样保留的文件会在清单中标记 |
| RAR | 仅识别反馈 | 不解压、不处理，提示转换为第一阶段支持格式 |
| 人工复核 | 已实现基础工作台 | 显示规则、位置、识别值和上下文；可逐项忽略，忽略项仅作用于当前任务 |
| 本地预览 | 已实现自建预览层 | DOCX/XLSX/PPTX 安全文本预览，PDF/OFD/图片为本地栅格预览；音视频通过支持 Range 的回环流式播放器按时间跳转；未集成 kkFileView 或 wps-view-vue |
| 规则维护 | 已实现 | 205 条内置中英文及国际规则，优先补齐中国大陆身份、金融、医疗、教育、就业、网络、司法、房产、车辆以及省/地市/区县/乡镇街道离线词典；启停、黑白名单；自定义规则新增/编辑/复制/删除、保存前样例测试、JSON 导入导出、20 版历史回滚；自定义表达式使用 RE2/J 线性时间引擎并拒绝其不支持的反向引用和环视语法 |
| 新建任务向导 | 已实现并完成浏览器验收 | 五步流程：选择/拖放文件并预检 → 项目与模式 → 按任务选择规则分类并预览文件名 → 风险确认 → 进度与下载；未确认前不自动开始处理 |
| 文件名脱敏 | 已实现并自动测试 | 文件名主体与正文使用同一任务规则范围，保留扩展名；同项目重名按 Windows 不区分大小写规则追加序号；还原加密原件时恢复原文件名 |
| 可逆模式 | 已实现本机验证 | 界面可选择 AES-256-GCM 加密原件模式并二次确认口令；PBKDF2-HMAC-SHA-256（600,000 次）派生密钥；正确口令恢复后校验原始 SHA-256 |
| 稳定化名 | 已实现 | 同一本机主密钥与同一项目 UUID 下相同原值保持一致映射；跨批次项目隔离 |
| 历史任务 | 已实现 | SQLite WAL 持久索引、状态审计、属性兼容副本、启动恢复、项目分组/搜索/分页、取消/重试、任务或整项目永久删除 |
| 批量与卷宗 | 已实现底座 | 浏览器多选/拖放、逐文件本地预检、同批项目 UUID、最多 10,000 项持久队列、压缩包清单确认、失败隔离；结果包内部文件和目录路径同步脱敏并记录原路径→输出路径清单 |
| 1 GiB 接收与处理 | 分层验证 | 上传事务式逐块写盘并计算 SHA-256；精确 1 GiB PDF、近上限 1 GiB 高密度 DOCX/XLSX/PPTX 完成处理、结构复核及全量残留复扫；精确 1 GiB TAR 完成清单确认。最新 1 GiB PDF 逐页 OCR 为 128 页/256 命中/110.983 秒，进程树峰值提交内存 473,759,744 字节 |
| 解析资源隔离 | Windows 资源约束已实现，权限沙箱未完成 | 文档、压缩包、复核和预览均使用一次性子 JVM；默认堆 2 GiB、120 分钟超时；正式 Windows 包强制使用 Job Object 的 kill-on-close、32 子进程上限与 4 GiB 进程树提交内存上限，并记录内存/临时盘峰值。子进程仍继承当前用户的文件和网络权限，不能替代 AppContainer/低权限账户或 macOS App Sandbox |
| 本地存储配额 | 已实现 | 默认数据上限 20 GiB，至少保留 2 GiB 空闲空间，并对并发任务预留容量 |
| 环境预检与修复 | Windows 已实测；macOS 脚本待实机 | 启动前检查系统、架构、内存、磁盘、写权限及组件哈希；缺失/损坏组件从包内校验过的修复副本恢复；按资源选择 constrained/balanced/standard 档位，不联网、不提权 |

## 压缩包策略

处理顺序固定为：安全预检 → 用户查看清单 → 用户确认 → 分项处理 → 生成同格式结果包和 `_doc_redaction_manifest.json`。

- 可执行文件、脚本、符号链接、不安全路径和路径穿越条目始终排除，不能通过二次确认放行。
- 普通未支持文件默认不进入结果包。
- 只有用户勾选“原样保留”并再次确认风险后，普通未支持文件才会原样进入结果包。
- 单压缩包最多 10,000 个条目；单条目实际展开上限 1 GiB；声明和被处理条目的累计实际展开量上限均为 8 GiB；ZIP 可疑压缩倍率阈值 200 倍。
- 嵌套压缩包第一阶段不递归展开。

## Windows 运行

当前 Windows 中文环境已使用 Temurin JDK 21 与 Gradle 8.14.3 构建验证。源码构建会优先使用已有 Java 21；未检测到时，`build-windows.ps1` 会把经过 SHA-256 校验的 Temurin 21 下载到项目内 `.tools`，不会修改系统 PATH、注册表或服务。Gradle Wrapper 同样校验发行包 SHA-256：

```powershell
Set-Location '<项目目录>'
.\build-windows.ps1
.\start-windows.bat
```

默认源码构建执行不依赖 OCR/媒体本地运行时的确定性测试。维护者准备好固定版本的 Tesseract、OpenCV、FFmpeg、whisper.cpp 和合成媒体样例后，使用 `build-windows.ps1 -WithNativeTests` 执行原生集成测试。首次完整封装前运行 `prepare-vlm-windows.ps1`，它只下载官方固定版本并校验 SHA-256；OpenCV 4.13.0 也从官方 Release 固定下载并核验 SHA-256，运行包本身不会联网。Windows 包另带 BtbN `win64-lgpl-shared` FFmpeg；启动时强制检查 `--enable-shared`，发现 `--enable-gpl` 或 `--enable-nonfree` 即拒绝启用。正式运行包启动前执行离线预检，组件哈希异常时只从包内 `repair/components.zip` 自修复，并把环境报告写入 `data/diagnostics`；它不会联网安装、修改系统 PATH/注册表/服务或请求管理员权限。固定版本、下载哈希和模型许可见 `audit/MEDIA_COMPONENTS.json` 与 `audit/VLM_COMPONENTS.json`。

启动后访问命令窗口输出的 `DOC_REDACTION_URL`，通常为 `http://127.0.0.1:8765/`。演示仅使用 [samples](./samples) 中的合成样例。

## macOS 13+ 运行

业务代码没有 Windows 专用依赖，但当前没有 macOS 实机验收证据。源码构建要求本机 Java 21：

```bash
chmod +x build-macos.sh
./build-macos.sh
```

`build-macos.sh` 只执行确定性测试和构建；它不会把尚未验收的本地 OCR、媒体或模型测试标记为通过。构建完成后，如已按目标架构准备完整运行组件，再使用 `start-macos.command` 启动。

macOS 打包脚本现要求显式提供同架构 JRE、OCR、FFmpeg、whisper.cpp、OpenCV 4.13 JNI 和 llama.cpp Metal，并在打包与启动预检时使用 `file` 拒绝架构不匹配；同时生成组件哈希和包内修复副本。正式分发仍需分别在 Intel 与 Apple Silicon 机器生成运行时，并按 [macOS 虚拟机验收清单](./audit/MACOS_VM_ACCEPTANCE.md) 完成首次启动、签名、公证、NFC/NFD 中文路径和断网验证；Windows 构建成功不能替代这些证据。

## 离线升级与回滚

Windows 包已提供 Ed25519 签名更新格式、逐文件 SHA-256/大小/完整集合校验、数据架构版本门禁、单实例锁、旧程序树备份和回滚脚本。`data` 永不进入升级包，也不会在升级/回滚中被替换。发布者需把可信公钥写入 `update/trusted-public-keys.properties`；模板为空时系统拒绝所有更新。私钥只能保存在包外，由项目所有者自行保管。当前只允许数据架构版本 `1` 的同架构更新；涉及数据库迁移时必须另做迁移/回滚设计。

## 数据与隐私

- 上传只发生在浏览器与本机回环服务之间；程序无云端接口，不自动下载模型。
- 等待人工复核或压缩包确认时，任务明文必须暂存在 `data/projects/<任务ID>/original`。
- 任务完成、失败或用户取消后，程序清理任务明文副本；可逆模式另外保留 `vault/original.drenc` 加密原件。删除不等于 SSD 物理安全擦除。
- 人工复核命中的原值仅保存在内存并通过本机会话显示，不写入复核 JSON；全局黑白名单由用户主动保存时会写入本地规则配置。
- 历史记录、项目名、原文件名、压缩包内部路径和规则配置仍可能具有敏感性，应把整个 `data` 目录视为受控数据。
- 可逆还原的可靠来源是加密原件，不是“移除遮挡”。忘记口令后无法恢复；当前没有企业密钥托管、RBAC 或双人审批。
- 回环监听、Host 白名单和随机会话令牌用于阻断普通网页跨站调用；它们不抵御已经以同一系统用户权限运行的恶意程序。

## English documentation

See [README_EN.md](./README_EN.md) for the English overview, build instructions, verified scope, security boundaries, and contribution rules.

## 构建与验证

```powershell
.\build-windows.ps1
.\build-windows.ps1 -WithNativeTests
.\audit\run-local-security-gate.ps1
```

`audit/verify-upstream.ps1` 只供已经下载固定上游调研镜像的维护者核对来源，不是普通贡献者或干净克隆构建的前置条件。默认 Gradle `test` 排除标记为 `native` 的 OCR/媒体用例；`nativeIntegrationTest` 在固定本地组件齐备后单独执行，避免把“缺少原生工具”误报为源码单元测试失败。

当前 30 个测试套件、107 项自动化测试通过：默认确定性层 99 项（失败 0、错误 0、因当前 Windows 无符号链接权限跳过 1 项），固定本地组件的 `nativeIntegrationTest` 层 8 项（失败 0、错误 0、跳过 0）。测试覆盖 205 条中英文/国际规则及校验反例、44,712 项中国大陆四级行政区划词典、32 条精确规则金标用例、文件名与压缩包内部路径脱敏/重名处理、任务级规则分类、五标签规则库、DOCX/XLSX/PPTX/PDF/OFD、OOXML 包级安全门、真实 Tesseract、图片残留复扫、事务式上传、双语资源、压缩包、人工复核、加密原件 API/批量还原审计、SQLite、磁盘配额、本地 API、Host 白名单、历史状态路径防篡改与自定义正则线性时间约束；同时覆盖 3 个许可证清晰的上游 OFD 实样、音频静音、视频 OCR 遮挡、文本字幕重封装、人脸/二维码/中国车牌检测、跨采样帧保守轨迹桥接、Qwen 区域响应安全校验、Windows Job Object、资源峰值遥测、签名离线升级/回滚和一次性子进程端到端回归。安全门禁使用 Gitleaks 8.30.0 与 OSV-Scanner 2.4.0 的本地 Maven 漏洞库快照；源码不上传。构建同时生成 Java CycloneDX SBOM；Windows 原生组件清单位于 `ocr/OCR_COMPONENTS.json`、`media/MEDIA_COMPONENTS.json` 和 `vlm/VLM_COMPONENTS.json`。

## 工程资料

- [实现状态与生产差距](./design/IMPLEMENTATION_STATUS.md)
- [生产威胁模型](./design/PRODUCTION_THREAT_MODEL.md)
- [格式支持矩阵](./design/FORMAT_SUPPORT_MATRIX.md)
- [中英文及国际规则目录](./design/INTERNATIONAL_RULE_CATALOG.md)
- [公开样例来源与准确率门槛](./audit/GOLD_CORPUS_AND_THRESHOLDS.md)
- [公开数据机器可读判定](./audit/PUBLIC_GOLD_SOURCE_CATALOG.json)
- [正式版路线图](./design/PRODUCTION_ROADMAP.md)
- [架构基线](./design/MVP_ARCHITECTURE.md)
- [验收矩阵](./design/ACCEPTANCE_TEST_MATRIX.md)
- [构建与测试报告](./audit/BUILD_AND_TEST_REPORT.md)
- [上游版本与来源](./audit/UPSTREAM_PROVENANCE.md)
- [Windows OCR 来源与构建边界](./audit/TESSERACT_WINDOWS_PROVENANCE.md)
- [静态安全与许可证审计](./audit/STATIC_SECURITY_REVIEW.md)
- [2026-08-14 上线准备度代码审查](./audit/PRODUCTION_READINESS_REVIEW_2026-08-14.md)
- [2026-08-14 上线准备度复审 V2](./audit/PRODUCTION_READINESS_REVIEW_2026-08-14_V2.md)
- [当前构建与发布清单（机器可读）](./audit/BUILD_MANIFEST.json)
- [2026-08-14 上线准备度复审 V3（历史快照）](./audit/PRODUCTION_READINESS_REVIEW_2026-08-14_V3.md)
- `app/gradle.lockfile`：Maven 依赖锁定
- `app/gradle/verification-metadata.xml`：下载构件 SHA-256 校验
- [第三方组件说明](./THIRD_PARTY_NOTICES.md)
- [项目许可证与第三方 MIT 组件策略](./LICENSE_POLICY.md)
- [GitHub 开源发布与 Release 分卷说明](./OPEN_SOURCE_PUBLISHING.md)
- [贡献指南](./CONTRIBUTING.md)
- [安全漏洞报告策略](./SECURITY.md)
- [中文用户操作指南](./docs/USER_GUIDE_ZH-CN.md)
- [English user guide](./docs/USER_GUIDE_EN.md)
- [故障排查](./docs/TROUBLESHOOTING.md)
- [备份、数据恢复与卸载](./docs/BACKUP_RESTORE_UNINSTALL.md)
- [日志与诊断数据规范](./docs/LOGGING_AND_DIAGNOSTICS.md)
- [维护者发布流程](./RELEASING.md)
- [英文项目说明](./README_EN.md)
- [变更记录](./CHANGELOG.md)
- [维护者职责与身份](./MAINTAINERS.md)
- [项目治理](./GOVERNANCE.md)
- [维护自动化和可选 Codex/API 使用边界](./MAINTAINER_WORKFLOWS.md)
- [公开合成压力证据](./audit/evidence/README.md)

## 开源发布状态

公开仓库为 `https://github.com/caipeijia833/doc-redaction`，本地目录使用 `main` 分支并配置该地址为 `origin`。源码许可证、贡献规范、安全策略、日志规范和 GitHub Release 分卷工具已准备；发布分卷位于被 Git 忽略的 `release-assets/`，只能作为 Release 附件。版权主体、主要维护者、Release 签名责任人和恢复密钥保管人均已确认为 `caipeijia833`。当前不发布二进制 Release，macOS 13+ 实机验收和签名/公证仍为后续事项。Codex for OSS 申请已提交，正在等待 OpenAI 审核；尚未获得任何项目权益，不能视为已获批。
