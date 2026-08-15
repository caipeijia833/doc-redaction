# 单机离线版威胁模型

更新时间：2026-08-13

## 1. 安全目标

系统的首要目标不是“尽可能输出结果”，而是避免把未经可靠处理的敏感内容误判为可外发。对无法识别、无法解析、缺少 OCR 依赖或超过资源上限的输入，默认停止该任务并给出明确原因。

受保护资产包括：上传原文、压缩包内部文件、脱敏结果、可逆模式密文、用户口令、项目化名密钥、黑白名单、自定义规则、文件名和卷宗目录结构、任务审计记录。

## 2. 信任边界

1. 浏览器只通过 `127.0.0.1` 与父进程通信；API 使用每次启动随机生成的会话令牌。
2. 父进程负责队列、状态、配额、加密保管和 HTTP；不直接解析上传文档。
3. DOCX/XLSX/PPTX/PDF/OFD、图片、音视频、压缩包和预览解析在一次性子 JVM 中进行。默认 `-Xmx2048m`、120 分钟超时、一个并行工作进程；可显式配置为 1–2 个。
4. Tesseract、FFmpeg/ffprobe 和 whisper.cpp 是子 JVM 再启动的本地原生进程（Windows 包内置，macOS 按同架构配置）；数据通过本机匿名管道和任务临时目录传递，不调用网络服务。
5. SQLite、任务目录和本机主密钥均位于 `data` 目录。该目录必须视为受控敏感数据，而不是普通缓存。

## 3. 已实施控制

- 单文件流式接收上限 1 GiB，并在写盘时计算 SHA-256。
- 数据目录默认 20 GiB 配额，并保留至少 2 GiB 磁盘空闲；并发任务先预留空间。
- SQLite 使用 WAL、`synchronous=FULL` 和 5 秒忙等待；状态另存原子替换的 `job.properties` 兼容副本。
- 启动时恢复 `UPLOADED/INSPECTING/ANALYZING/PROCESSING` 状态；缺少原文的活动任务转为 `INTERRUPTED`，不伪装成完成。
- 用户可取消、重试中断任务和永久删除任务；“完成”状态只会在输出落盘且明文副本清理后出现。
- 压缩包限制为 10,000 项、单项 1 GiB、声明展开总量 8 GiB、压缩倍率 200；拒绝路径穿越、绝对路径、设备名、符号链接和危险脚本。
- 不支持文件默认不进入结果包；原样保留必须二次确认，并写入结果包清单。
- 扫描 PDF、OFD、图片和 Office 内嵌位图缺少中文 OCR 时失败关闭；不允许生成“看似成功但图片未检查”的结果。
- 音视频只有在 FFmpeg、ASR、OCR 与视觉模型全部就绪时才允许接收；输出必须重新编码，未知/位图字幕移除，并对输出重新分析。FFmpeg 若启用 GPL 或 nonfree 配置则拒绝媒体能力。
- 可逆模式使用 AES-256-GCM；口令不保存，密钥派生在本机完成。同一批次使用项目 UUID 隔离稳定化名。
- 依赖已锁定并启用 Gradle SHA-256 验证；构建生成 CycloneDX 1.6 SBOM。

## 4. 仍然存在的风险

| 风险 | 当前结论 | 正式发布前要求 |
|---|---|---|
| 子 JVM 不是操作系统级沙箱 | 内存、超时和进程生命周期受控，但解析器漏洞仍可能以当前用户权限读取本机文件 | Windows 使用低完整性/AppContainer 或专用受限账户；macOS 使用经过签名的沙箱 entitlement，并做逃逸测试 |
| Tesseract 原生进程无硬内存上限 | 有单页超时，但没有跨平台等价的 cgroup/Job Object 限额 | Windows Job Object、macOS 子进程资源限制；测试超大图和畸形图 |
| SQLite 与历史元数据未加密 | 原文完成后会清理，但文件名、项目名、规则和审计仍敏感 | 提供数据目录加密/系统密钥链方案，或明确要求 BitLocker/FileVault |
| 删除不是物理安全擦除 | SSD、日志型文件系统和备份可能保留已删除块 | 文档中声明边界；高等级环境使用全盘加密和密钥销毁，不承诺逐文件擦除 |
| OCR 可能漏字或错框 | OCR 是概率性识别，不能证明零漏检 | 建立中文扫描件金标集、召回率门槛、低置信度复核和抽检流程 |
| SmartArt、图表缓存、OLE、宏及特殊对象 | 图表缓存文字纳入包级处理；其余不保证转换 | OOXML 包级全量残留扫描；OLE、ActiveX、VBA、签名部件失败关闭；SmartArt/矢量对象继续补实样 |
| 视频及音频漏检 | 第一阶段已覆盖全部音轨 ASR、文本字幕、抽帧 OCR、人脸、二维码和中国车牌，但抽帧不是逐帧跟踪，ASR/CV 均可能漏检 | 建立真实金标集；增加场景切换与跨帧跟踪；短暂画面/低置信度进入强制复核；完成长视频、1 GiB 和多国车牌验收 |
| 原生媒体工具链 | Windows 已固定版本/哈希并拒绝 GPL/nonfree FFmpeg 配置；macOS 同架构产物与原生进程硬限额未验证 | 两个平台独立构建、许可证复核、Job Object/资源限制、畸形媒体模糊测试和离线 CVE 扫描 |
| 可逆密钥治理 | 单机口令模式没有双人审批、KMS、轮换和吊销 | 留到企业多用户版实现，不在单机版中伪造企业能力 |

## 5. 明确不作出的保证

- 虽已通过精确 1 GiB 合成 PDF 和 TAR 边界，未经过真实业务 1 GiB 文档、10,000 项真实卷宗、Windows/macOS 目标机和恶意样本测试前，仍不声称生产验收通过。
- 未安装并验证 `chi_sim` 中文语言包时，不声称扫描 PDF、OFD、图片或 Office 图片可用。
- 当前只声称第一阶段支持 MP3/WAV/M4A/FLAC 与 MP4/MOV/MKV；不声称音视频零漏检、逐帧跟踪、变声、多国车牌或 1 GiB 媒体验收完成，也不支持 RAR 解压及旧版 DOC/XLS/PPT/WPS 原格式脱敏。
- 单机版不提供 RBAC、SSO、多人并发隔离、集中 KMS、不可抵赖审计或法律业务系统写接口。

## 6. 依据

- SQLite WAL 只适用于同一主机上的进程协作，并允许并发读与单写者：[SQLite WAL](https://www.sqlite.org/wal.html)
- Java 子进程通过 `ProcessBuilder` 创建：[Java 21 ProcessBuilder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessBuilder.html)
- Tesseract 支持 UTF-8、100 多种语言和 TSV/hOCR 等输出，代码为 Apache-2.0：[Tesseract 官方仓库](https://github.com/tesseract-ocr/tesseract)
- OFDRW 按 GB/T 33190-2016 提供 OFD 解析、转换和导出，采用 Apache-2.0：[OFDRW 官方说明](https://gitee.com/ofdrw/ofdrw/blob/master/README.md)
