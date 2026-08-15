# 候选组件第一轮静态安全与许可证审计

## 结论

可以继续开发 PoC，但不能直接构建全部上游仓库。当前批准进入 PoC 的核心组合是 Apache POI、Apache PDFBox、qpdf，以及经过模块白名单裁剪的 OFDRW。Presidio 和 RapidOCR 只有在关闭自动下载、固定本地模型及禁用云端可选依赖后才允许使用。JPDFium 暂不进入 MVP。

> 2026-08-14 实施补充：F-01 的“禁止 converter”是第一轮审计结论，现已由更严格的隔离方案替代。主程序不依赖 `ofdrw-converter`；OFD 专用 JVM 使用单独类路径、PDFBox 2.0.37，并排除全部 `com.itextpdf`。为避免 OFDRW AWT 渲染器因公共工具类方法签名引用 iText 而触发类加载，项目在 OFD worker JAR 中优先放置基于 OFDRW Apache-2.0 源码的最小无 iText 色彩、字体加载和 PPM 兼容实现。依赖锁、CycloneDX SBOM 和最终 OFD 目录均检查为零 iText/AGPL 构件；除合成 OFD 外，已对固定提交中的 `helloworld.ofd`、`containsJPEG.ofd`、`signout.ofd` 三个 Apache-2.0 真实测试文件完成渲染和图像解码测试。该例外只批准图像渲染和栅格 OFD 重建，不批准 OFD-to-PDF/iText 路径。

## 关键发现

### F-01 高风险：OFDRW 不能整体打包

OFDRW 根项目是 Apache-2.0，但 `ofdrw-converter` 明确依赖 iText 7.2.6 的 `kernel`、`layout`、`font-asian`。iText 官方说明其为 AGPL/商业双许可证。对闭源内部产品，除非另购商业许可或履行 AGPL 义务，否则不能分发该模块。

同一模块还固定使用 Log4j `2.16.0`。Apache 官方安全公告显示该版本落在 CVE-2021-45105 受影响区间，修复版本为 Java 8+ 的 `2.17.0`。因此即便排除许可证问题，也不能原样采用该依赖版本。

处置：

- 禁止依赖 `ofdrw-full` 和 `ofdrw-converter`。
- PoC 仅允许按需评估 `ofdrw-core`、`ofdrw-pkg`、`ofdrw-reader`、`ofdrw-layout`、`ofdrw-graphics2d`。
- 签名、加密、档案模块必须逐个展开传递依赖后再放行。
- PDF/OFD 转换由本项目使用 PDFBox 3.0.8 等独立实现，不通过 OFDRW converter。

证据：

- `upstream/ofdrw/ofdrw-converter/pom.xml:17-18`
- `upstream/ofdrw/ofdrw-converter/pom.xml:78-99`
- [iText官方许可证说明](https://github.com/itext/itext-java)
- [Apache Log4j安全公告](https://logging.apache.org/security.html)

### F-02 高风险：默认配置可能在运行期访问网络

> 2026-08-13 实施状态：Presidio 与 RapidOCR 均未进入运行时依赖或发布包。当前主链路使用本地确定性规则和包内静态 Tesseract，不存在缺模型自动下载路径。以下内容保留为将来引入模型时的门禁。

Presidio 的 `SlimSpacyNlpEngine` 默认参数 `auto_download=true`，缺失模型时会调用 spaCy 下载。RapidOCR 默认配置的模型路径为空，ONNX Runtime 路径会根据远程模型表自动下载模型。

处置：

- Presidio 必须显式配置 `auto_download=false`，基础模式优先使用无模型规则识别器。
- 禁止安装 Presidio 的 Azure、OpenAI、LangExtract 等云端可选依赖。
- RapidOCR 必须显式传入本地模型绝对路径，禁止使用默认远程模型解析。
- 模型由独立离线模型包提供，记录来源、许可证、SHA-256和允许的用途。
- 产品进程默认设置出站网络阻断；离线测试必须验证零外联。

证据：

- `upstream/presidio/presidio-analyzer/presidio_analyzer/nlp_engine/slim_spacy_nlp_engine.py:67-72`
- `upstream/presidio/presidio-analyzer/presidio_analyzer/nlp_engine/slim_spacy_nlp_engine.py:148-163`
- `upstream/rapidocr/python/rapidocr/config.yaml:130-180`
- `upstream/rapidocr/python/rapidocr/inference_engine/onnxruntime/main.py:33-61`

### F-03 高风险：Presidio REST API不含内建认证

Presidio 官方 FAQ 明确说明其 API 端点不内建身份认证。不能把原生服务直接暴露给局域网或不可信客户端。

处置：

- MVP 不直接暴露 Presidio HTTP 服务。
- Java 主进程通过标准输入输出、命名管道或一次性本地套接字调用 Python worker。
- 内网 API 由主服务统一提供 RBAC、审计、TLS、限流和请求大小控制。

证据：`upstream/presidio/docs/faq.md:135-138`。

### F-04 中高风险：JPDFium不适合作为MVP硬依赖

JPDFium 提供真删除、栅格化、元数据净化等能力，但当前版本要求 Java 25，并包含 C++、Rust 和多个本地库。其完整构建流程会下载约 15GB 的 PDFium 源码；文档还说明缺失的本地库可能被静默跳过，相关功能在运行时返回空结果。

处置：

- MVP 使用 PDFBox + qpdf，并提供高安全栅格化发布模式。
- JPDFium 只在隔离研究分支构建。
- 在进入生产前必须完成 Windows x64、macOS x64/arm64 的原生库哈希、签名、线程模型和恶意 PDF 测试。

证据：`upstream/jpdfium/README.md:1619-1679`、`1806-1812`。

### F-05 中风险：Office/PDF/OFD解析属于不可信输入边界

复杂或恶意文档可能造成压缩炸弹、XML实体问题、解析器崩溃、极端内存/CPU消耗或临时文件泄露。1GB接收上限不能代替内容复杂度限制。

处置：

- 上传采用磁盘流式写入，不进入内存或数据库 BLOB。
- OOXML/OFD 解包禁止路径穿越，限制展开后总大小、文件数量、嵌套深度和压缩倍率。
- 每个格式解析器运行在独立子进程，设置内存、CPU、时间和输出大小上限。
- 临时目录使用每任务 ACL；任务完成后清理。高保密环境应使用全盘加密，不能把 SSD 上的普通删除宣称为安全擦除。
- 解析失败必须形成可审计错误，不允许静默跳过对象。

2026-08-13 已落地补充：DOCX/XLSX/PPTX 使用 ZIP 包级事件流，限制部件数、单部件与总展开量、路径和 XML 外部实体；单语义单元限制为 16 MiB 文本/250,000 个事件；OLE、ActiveX、VBA 和 Office 签名部件失败关闭；输出执行全量事件流残留复扫。

### F-06 中风险：模型、字体和测试素材许可证仍需逐项核验

RapidOCR 源码采用 Apache-2.0，但 OCR 权重源自其他项目，不能自动推定所有模型均适合再分发。OFDRW包含测试字体和标准文件，测试素材也不应未经审查进入产品包。

处置：

- 源码许可、模型许可、字体许可和测试数据许可分别记录。
- 生产包只纳入明确允许再分发的模型和字体。
- 测试资源不随生产包发布。

### F-07 中风险：RapidOcrOnnxJvm成熟度不足

该仓库没有稳定发布标签，Java层仅通过 `System.loadLibrary("RapidOcrOnnx")` 调用外部本地库，构建文件没有完整声明该本地库的可复现来源。不能据此形成可信的单Java运行时方案。

处置：RapidOcrOnnxJvm 与 Python OCR worker 均未进入当前 MVP。Windows 包采用固定提交、MSVC `/MT` 静态构建的 Tesseract 5.5.2，包内只带可执行文件与 `chi_sim`/`eng`/`osd` 数据；后续多模态/ONNX 能力必须重新执行许可证、原生库和离线模型审计。

### F-08 信息：未发现强特征硬编码秘密

对当前源码树进行强特征扫描，排除测试数据目录后，未发现私钥头、典型 AWS Access Key、GitHub PAT 或 OpenAI Key 格式命中。Presidio 根目录 `.env` 仅包含镜像构建变量名。

这不等价于完整秘密扫描。完整 Git 历史、弱格式凭证、二进制和生成产物尚未覆盖。

### F-09 架构决策：不整体集成通用预览服务

kkFileView 能覆盖大量文件类型，但其服务端、Office 转换、媒体/CAD/压缩包等依赖和配置面会显著扩大离线桌面 PoC 的安装体积、攻击面与跨平台验收范围。wps-view-vue 依赖 WPS 开放平台回调和在线页面嵌入，不满足本项目“本地离线、文件不出机”的基础边界。

处置：

- 第一阶段不整体集成 kkFileView，也不使用 wps-view-vue。
- 自建最小预览层：DOCX/XLSX/PPTX 只生成 HTML 转义后的受限文本预览，PDF 按页渲染 PNG。
- 预览接口只接受本地任务 ID，不接受任意文件路径；沿用随机会话 Cookie/令牌、CSP 和禁缓存响应。
- 预览是快速复核辅助，不作为版式保真证据；Office 最终版式仍在本机 Word/WPS/LibreOffice 中验收。

参考：[kkFileView](https://gitee.com/kekingcn/file-online-preview)、[wps-view-vue](https://gitee.com/mose-x/wps-view-vue)。

## 模块准入清单

| 组件 | MVP状态 | 强制条件 |
|---|---|---|
| Apache POI | 准入 | 只引入实际需要模块；启用 ZIP 防护；单独测试复杂 Office 对象 |
| Apache PDFBox | 准入 | 子进程隔离；限制内存和页数；输出后残留扫描 |
| qpdf | 准入 | 固定本地二进制和哈希；不把结构重写误认为内容脱敏 |
| OFDRW OFD worker | 条件准入 | 独立进程/类路径；只用图像渲染与栅格重建；排除 iText；禁止 OFD-to-PDF/full；SBOM 持续门禁 |
| Presidio Analyzer/Anonymizer | 条件准入 | 禁用下载和云端 extras；不暴露原生 API |
| RapidOCR Python | 条件准入 | 本地模型绝对路径；模型哈希和许可证锁定；禁止下载 |
| JPDFium | 暂缓 | 仅隔离研究，不进入MVP安装包 |
| RapidOcrOnnxJvm | 拒绝 | 依赖来源和发布成熟度不足 |
| kkFileView | 不整体集成 | 如未来按需复用，必须拆分为独立可选服务并重新做许可证、依赖与平台审计 |
| wps-view-vue | 拒绝当前离线路径 | 依赖 WPS 在线平台能力，不符合文件不出机边界 |

## 尚未验证

- 2026-08-14 已对当时 CycloneDX SBOM 中的 Maven 组件执行 OSV Batch API 只读扫描，返回已知漏洞 0 项；新增源码和资源后的当前结果以最新安全门禁目录为准。这只代表扫描时点与本地/OSV 已收录数据，不替代持续门禁、SAST 或第三方渗透测试。
- 上游标签签名。
- Windows/macOS 实际构建和安装包签名。
- 1GB 文件、10,000 文件批次和断点续作性能。
- 中文 Office/OFD/PDF 的版式往返质量。
- OCR 模型精度、模型许可证和 CPU 基准。
