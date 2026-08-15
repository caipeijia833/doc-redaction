# 构建与测试报告

> 历史报告提示：本文包含 2026-08-13 和 2026-08-14 各阶段构建记录，其中“20 套件/70 项”“26 套件/96 项”和旧制品哈希仅作历史证据。当前基线为 30 套件/107 项（失败 0、错误 0、跳过 1）；最终离线包哈希、安全门禁、规则金标、OFD 上游实样和当前上线结论，以 [上线准备度代码复审 V3](./PRODUCTION_READINESS_REVIEW_2026-08-14_V3.md) 为准。不要将本文单独作为正式上线批准依据。

报告日期：2026-08-13
交付版本：`0.1.0-poc`（单机离线 Alpha）

## 1. 结论

> 2026-08-14 当前基线：30 个测试套件共 107 项自动化测试，失败 0、错误 0、因当前 Windows 账户无符号链接权限跳过 1 项；内置规则 205 条，中国大陆四级行政区划词典 44,712 项。下文保留各阶段历史验证记录，最终制品与上线判断以 `BUILD_MANIFEST.json` 和 `PRODUCTION_READINESS_REVIEW_2026-08-14_V3.md` 为准。

当前 Windows 中文开发机上的源码编译、Fat JAR、隔离 OFD Worker、CycloneDX Java SBOM、MSVC 静态 OCR 运行时、LGPL-only FFmpeg/whisper.cpp/OpenCV CPU 媒体组件、Qwen3-VL-2B-Instruct Q4_K_M、Windows x64 免安装包、包内哈希校验及包内 HTTP 启动冒烟均已通过。

大文件能力已经从“只测上传边界”推进到包级事件流实测：精确 1 GiB PDF 完成生产链路脱敏，精确 1 GiB TAR 完成上传与卷宗清单确认；近产品上限的高密度 DOCX/XLSX/PPTX 均在固定 2 GiB 工作堆下完成处理、全量残留复扫与结构复核。结论仅适用于完全合成的合法高密度 OOXML，不能泛化为所有复杂对象或真实 1 GiB 文档。

本轮还修复了两个会影响脱敏安全性的 XLSX 问题：源文件可能被 POI 写回，以及 `inlineStr` 旧原文可能残留在 OOXML。两者均已有回归测试。

音视频第一阶段已形成“探测与资源门限 → 全音轨 ASR/字幕/画面分析 → 人工复核 → 静音或烧录遮挡 → 重新编码 → 输出残留复扫”的闭环。自动化证据只覆盖合成中英文数字语音、画面文字、文本字幕、一次性子进程，以及官方/生成的人脸、二维码和中国车牌样例；不构成真实噪声、快速运动、跨帧跟踪、多国车牌、长视频或 1 GiB 媒体验收。

本轮新增完整自定义规则工作台、加密原件还原界面/API 闭环和离线环境预检。Windows 启动器已实际验证正常预检、资源档位注入和关键组件运行；另分别故意篡改既有组件、加入额外组件文件，均被全量 SHA-256/集合检查发现并从包内修复归档重建，恢复后哈希一致。这里的“自动补全”仅指包内软件组件修复和运行参数降档，不可能补充物理内存、磁盘或不兼容的 CPU 架构。

本轮还新增五步新建任务向导、文件选择/拖放后的逐文件本地预检、任务级规则分类、输出文件名脱敏与 Windows 不区分大小写重名分配，以及压缩包内部目录/文件路径脱敏。浏览器已实测自动进入下一步、规则清空阻断、风险确认门槛、中英文动态进度、RAR 只识别反馈和历史列表仅显示脱敏文件名；加密还原继续使用上传原文件名。

当前仍**不能表述为生产验收完成**。真实业务 OCR/OFD/ASR/视频金标语料、复杂 Office 对象、跨帧跟踪、长视频与 1 GiB 媒体、10,000 文件卷宗、完全断网抓包、恶意文件库、macOS 13 双架构、安装包签名/公证、离线 CVE/SAST 门禁和企业密钥治理仍未完成。

## 2. 已通过的构建与安全检查

| 检查项 | 结果 | 本轮证据 |
|---|---|---|
| Java 源码、测试、Fat JAR | 通过 | 两次 `package-windows.ps1` 均返回 `BUILD SUCCESSFUL` 与 `PACKAGE_OK` |
| 自动化测试 | 通过 | 20 个套件、70 项；失败 0、错误 0、跳过 0 |
| Windows 静态 OCR | 通过 | Tesseract 5.5.2 + Leptonica 1.87.0 + libpng 1.6.58 + zlib 1.3.2，MSVC `/MT` 静态构建 |
| OCR 动态依赖 | 通过 | `dumpbin /dependents` 只列 `KERNEL32.dll`；包内 OCR DLL 0，GCC/MSYS/MinGW 运行库文件 0 |
| OCR 许可证闭包 | 通过本轮固定产物审计 | Apache-2.0、BSD-2-Clause、libpng-2.0、Zlib；无随包 GPL/LGPL/AGPL/GCC Runtime Library Exception 组件 |
| 媒体供应链 | 通过固定产物检查 | FFmpeg `n8.1.2-34-g9b6c8969e0-20260812` 为共享构建，未启用 GPL/nonfree；whisper.cpp 1.9.2、ASR/人脸/车牌模型均校验固定 SHA-256，许可证文本随包 |
| Java SBOM | 通过 | CycloneDX JSON 65 个组件；iText/AGPL 命中 0 |
| 包内 SHA-256 | 通过 | `SHA256SUMS.txt` 列出的 445 个非清单文件逐项校验，失败 0；清单本身为第 446 个文件 |
| 离线自修复 | 通过 Windows 本机实测 | 320 个关键组件文件无集合差异；既有文件篡改和额外文件注入均触发重建；339,367,921 字节修复归档先校验 SHA-256 |
| 可复现构建 | 通过 | 连续两次 Windows ZIP 字节数和 SHA-256 完全一致 |
| 包内启动 | 通过 | 使用包内裁剪 JRE/OCR/媒体组件启动，`/api/health` 与首页均返回 200；媒体能力可用，FFmpeg/whisper 版本与人脸/车牌模型状态正确，CSP 含 `media-src 'self'` |
| Windows 启动脚本 | 通过 | 纯 ASCII、无 BOM、59/59 行为 CRLF；中文/空格路径下从最终包启动，环境档位 `standard`，健康页/首页均为 200，OCR/媒体能力均可用 |

## 3. 功能与回归验证

| 范围 | 结果 | 说明 |
|---|---|---|
| DOCX | 通过基础与 1 GiB 阶梯 | 包级事件流、跨 run、正文/表格/页眉等路径；近上限 1 GiB 高密度样本完成 |
| XLSX | 通过基础、1 GiB 与残留回归 | 包级事件流、单元格/批注/页眉；敏感公式失败关闭；输入 SHA 不变；近上限 1 GiB 完成 |
| PPTX | 通过基础与 1 GiB 阶梯 | 包级事件流、幻灯片、备注、母版与图表缓存文本；近上限 1 GiB 完成 |
| PDF | 通过至精确 1 GiB 合成样本 | 256 MiB、512 MiB、1 GiB 均完成；1 GiB 为 128 页，耗时 75.704 秒，输出结构复核通过 |
| 图片/OCR | 通过合成端到端 | 中英文真实 PNG OCR、TSV 坐标、遮挡、缺 OCR 失败关闭；图像头部尺寸门限覆盖 |
| 音频 | 通过第一阶段合成回归 | MP3/WAV/M4A/FLAC 探测；全部音轨 ASR；中英文数字归一化；规则命中时间段静音；重新编码与残留复扫 |
| 视频/字幕 | 通过第一阶段合成/官方样例回归 | MP4/MOV/MKV 探测；画面 OCR、人脸、二维码、中国车牌；坐标遮挡；文本字幕重写；未知/位图字幕移除；媒体 Range 预览；输出残留复扫 |
| OFD | 合成链路及 3 个上游实样通过 | 独立类路径，无 iText；OFDRW 上游文字、JPEG、签章样例已通过渲染解码，真实业务票据和多阅读器仍未验收 |
| ZIP/TAR/TAR.GZ/7Z | 通过 | 创建、检查、处理与重开；未支持文件默认排除，原样保留要求二次确认 |
| 文件名与向导 | 通过 | 输出文件名和压缩包内部路径脱敏、扩展名保留、重名序号、任务级规则分类；五步向导及中英文动态交互完成浏览器端到端验收 |
| 精确 1 GiB TAR | 通过接收与清单门 | 5 项中 4 项可脱敏、1 项未支持且默认排除；正确停在确认状态，测试随后取消并清理 |
| RAR | 通过边界 | 只识别并反馈转换建议，不解压、不处理 |
| 上传事务 | 通过 | `.part` 写入、精确 1 GiB 边界、超限 1 字节、截断流、声明长度不一致和孤儿临时文件清理 |
| 人工复核与残留复扫 | 通过基础链路 | 任务级忽略不污染全局白名单；输出重新提取规则命中，失败关闭 |
| 可逆模式 | 通过 | AES-256-GCM；界面口令二次确认；API 正确口令恢复原始字节且 SHA-256 一致，错误口令失败 |
| 规则与双语 | 通过 | 205 条中英文及国际规则、44,712 项中国大陆四级行政区划词典、校验码反例、规则维护、稳定化名、中英文静态/动态资源与布局测试；自定义规则新增/编辑/测试/导入导出/20版历史回滚及危险正则拒绝 |
| 历史与队列 | 通过基础链路 | SQLite WAL、启动恢复、取消、重试、任务/项目删除、锁顺序回归 |
| 存储保护 | 通过边界 | 默认 20 GiB 配额、2 GiB 空闲下限、格式级工作空间预留、默认单工作进程 |

## 4. 自动化测试分布

| 测试套件 | 项数 |
|---|---:|
| `ArchiveProcessorIntegrationTest` | 7 |
| `DocumentProcessorIntegrationTest` | 5 |
| `JobServiceSecurityTest` | 9 |
| `RuleEngineTest` | 7 |
| `LocalOcrEngineTest` | 4 |
| `StreamingUploadTest` | 3 |
| `JobStoreTest` | 3 |
| `WebI18nTest` | 3 |
| `InternationalRuleEngineTest` | 4 |
| `OoxmlPackageSecurityTest` | 5 |
| `MediaProcessorIntegrationTest` | 6 |
| `RedactionServerIntegrationTest` | 2 |
| `FilenameRedactorTest` | 3 |
| 其余 7 个套件 | 9 |
| **合计** | **70** |

测试报告入口：`app/build/reports/tests/test/index.html`。

## 5. 大文件实测边界

详细环境、输入/输出哈希和分层结果见 `audit/LARGE_FILE_ACCEPTANCE_REPORT.md`。关键边界如下：

- PDF：256 MiB 23.695 秒；512 MiB 41.188 秒；精确 1 GiB 75.704 秒，均完成。
- DOCX：512 MiB 1,152.492 秒；近 1 GiB 2,548.602 秒，完成。
- XLSX：512 MiB 1,257.944 秒；近 1 GiB 2,991.889 秒，完成。
- PPTX：512 MiB 1,064.113 秒；近 1 GiB 2,327.863 秒，完成。
- TAR：精确 1 GiB 在 6.880 秒内完成上传、SHA-256 与 5 项清单分类，进入二次确认。

Office 处理现已改为 ZIP 包级事件流。1 GiB 阶梯仍固定使用 2 GiB 堆；工作进程私有内存抽样约 186–188 MiB，但没有连续峰值采样，不能据此给出正式内存 SLA。

## 6. Windows OCR 与媒体供应链结论

固定输入：

| 组件 | 版本/提交 | 许可证 |
|---|---|---|
| Tesseract | 5.5.2 / `6e1d56a847e697de07b38619356550e5cf4e8633` | Apache-2.0 |
| Leptonica | 1.87.0 / `13275a278eb55b5746e33f95fbf5a2c8f604b3ab` | BSD-2-Clause |
| tessdata_fast | 4.1.0 / `65727574dfcd264acbb0c3e07860e4e9e9b22185` | Apache-2.0 |
| vcpkg | 2026.07.29 / `9e593bb18ea69cc5095e012465dcd675a822ed0d` | MIT |
| libpng / zlib | 1.6.58 / 1.3.2 | libpng-2.0 / Zlib |

Tesseract 可执行文件 SHA-256：`112effb15d42b9beac0bdc06fdfe4c8d9f8bd43b142afc9da6dad56a1d0ba366`。运行包包含 38 个 OCR 文件，共 20,546,153 字节；其中软件组件为上述 OCR/图像库与三种训练数据，不应把“文件数”误写成“组件数”。详细证据见 `audit/TESSERACT_WINDOWS_PROVENANCE.md`。

媒体固定输入与政策：

- BtbN FFmpeg `win64-lgpl-shared` ZIP：SHA-256 `c0692b85d56f2995656406425c095700117dfd7a84f8ca5af75ebf92ed08b8a9`；运行时要求 `--enable-shared`，检测到 `--enable-gpl` 或 `--enable-nonfree` 即拒绝启用。
- whisper.cpp 1.9.2 Windows x64 ZIP：SHA-256 `49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a`；多语言 `ggml-base-q5_1` 模型 SHA-256 `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`。
- YuNet 人脸模型 SHA-256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`；中国车牌模型 SHA-256 `6d4978a7b6d25514d5e24811b82bfb511d166bdd8ca3b03aa63c1623d4d039c7`。
- 固定来源、提交、许可证和分发范围详见 `audit/MEDIA_COMPONENTS.json`；包内只复制运行所需 CLI、共享 DLL、模型及许可证，不复制播放器、服务端、训练或基准工具。

## 7. 未通过或尚未验证

| 需求项 | 当前状态 | 原因与下一步 |
|---|---|---|
| 1 GiB Office | 合成阶梯通过 | 近上限高密度 DOCX/XLSX/PPTX 均完成；真实复杂对象、OFD 与多阅读器兼容仍未验收 |
| 真实 10,000 文件卷宗 | 未验证 | 已有队列和分页边界，尚无吞吐、恢复时间与交互压力数据 |
| 真实中文 OCR 金标 | 部分验证 | 清晰合成图通过；旋转、低清、表格、印章、手写体召回率未量化 |
| 真实 OFD 脱敏 | 部分实现 | 合成生成/预览通过；真实票据、签章失效和多阅读器兼容未验收 |
| 复杂 Office 对象 | 部分实现 | 图表缓存与自定义 XML 已纳入包级处理；OLE、ActiveX、VBA、Office 签名失败关闭；SmartArt、修订、矢量图真实语料不足 |
| macOS 13 双架构 | 未验证 | 需 Intel/Apple Silicon 实机生成、中文路径、断网、签名和公证 |
| 音视频 | 第一阶段实现、未完成真实验收 | 合成闭环通过；仍缺真实噪声/口音/多人重叠、快速闪现、跨帧跟踪、多国车牌、30 分钟/4 小时边界和 1 GiB 媒体压力 |
| 企业权限与密钥托管 | 后置 | 无 RBAC、双人审批、KMS/OS 密钥库、轮换和防篡改审计 |
| 安全发布门禁 | 未完成 | 尚缺离线 CVE/SAST/秘密扫描、恶意样本回归和第三方安全评估 |

## 8. 最终构件

- Windows x64 离线包：`dist/doc-redaction-poc-windows-x64.zip`
- ZIP 大小：3,795,580,869 字节
- ZIP SHA-256：`8eec32d57312de443706cafa196ad8d8defde29a208209485d373043c6140230`
- Fat JAR：147,881,207 字节；SHA-256 `a15c986264e1874cbf23117d69362ef0596e073b84b2babbeb58ae4b1aeede32`
- OFD Worker JAR：11,383 字节；SHA-256 `7f234f12cf2ce4db27cd74698129df341d303621bd9992fbadcbee02e5ea33fd`
- SBOM：810,853 字节；SHA-256 `47627a813e60fb4545ae29d4ed19daf4b7bf1afa8bf71c1f457410ef3a15aeb8`
- 包文件：446 个；哈希清单覆盖 445 个非清单文件，校验失败 0
- 包内修复归档：1,897,434,189 字节；SHA-256 `87177991ec642770e1554f876642170afa9ad274344854c10966d58c791afef3`
- 最终 ZIP 中央目录可读取，包含 505 个文件/目录条目；包内 README 已包含五步向导与文件名脱敏说明。本轮最终说明修订后未再次执行相同输入的连续两次字节级对比，因此不把旧的可复现哈希结论沿用到本最终 ZIP。

## 9. 开源操作边界

本目录尚未初始化、提交或发布 GitHub 仓库，也未提交 Codex for OSS 申请。`.gitignore` 已排除本地工具链、构建物、5.481 GiB 压力语料、隔离运行目录、JSON 压测结果、运行数据，以及包含上游测试密钥样例和嵌套 Git 元数据的完整 `upstream/` 调研镜像；公开前仍须由项目所有者明确授权，并补许可证、NOTICE/来源头、贡献规范、安全策略、秘密扫描和发布签名。
