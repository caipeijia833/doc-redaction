# 大文件与 1 GiB 分层验收报告

> 2026-08-14 补充：已在 Windows Job Object 强制生效并采集资源遥测的正式链路上重新完成精确 1 GiB 合成 PDF。输入 1,073,741,824 字节、128 页、256 个命中，耗时 110,983 ms；进程树峰值提交内存 473,759,744 字节，任务目录新增磁盘峰值 5,781,663 字节。公开证据见 `audit/evidence/pdf-1gib-jobobject-telemetry-20260814.json`，当前结论以 [上线准备度代码复审 V3](./PRODUCTION_READINESS_REVIEW_2026-08-14_V3.md) 为准。

报告日期：2026-08-13
结论范围：Windows 11 本机、完全合成语料、单机离线正式处理链路
工作进程限制：`-Xmx2048m`、`-XX:+ExitOnOutOfMemoryError`；1 GiB 验收显式使用 120 分钟时限

## 1. 结论

Office 处理器已从 Apache POI 完整对象模型改为 OOXML ZIP 包级事件流重写。DOCX、XLSX、PPTX 均按 ZIP 条目逐项读取与写出，只为单个段落、共享字符串、单元格或图表点保留有界缓冲；处理后再对输出执行不受 5,000 项复核列表限制的完整事件流残留扫描。

本轮得到的可复核事实是：

- 近产品上限的高密度 DOCX、XLSX、PPTX 合成样本均通过真实 `JobService → 一次性工作进程 → 脱敏 → 全量残留复扫 → 结构复核` 链路。三个输入均约 1,071.647 MB，距 1 GiB 上限约 2 MiB。
- 三种 Office 1 GiB 阶梯均保持 `-Xmx2048m`；抽样观察工作进程私有内存约 186–188 MiB。该数值不是连续峰值监控，不能表述为正式峰值 RSS。
- 精确 1 GiB PDF 已通过处理与结构复核；精确 1 GiB TAR 已通过事务式上传与卷宗清单确认，不支持条目默认排除。
- 这些结论只适用于生成器构造的高密度、合法 OOXML/PDF/TAR。宏、OLE、ActiveX、Office 签名等无法证明安全的部件当前失败关闭；真实复杂业务文档、OFD 1 GiB、恶意样本和 macOS 仍未验收。

## 2. 环境

| 项目 | 值 |
|---|---|
| 操作系统 | Microsoft Windows 11 Pro 10.0.26200（Build 26200） |
| CPU | AMD Ryzen 9 9950X，16 核 / 32 逻辑处理器 |
| 内存 | 61.41 GiB |
| Java | Temurin OpenJDK 21.0.12+8 LTS |
| 报告时 E 盘可用空间 | 约 687 GiB |
| 工作进程堆 | 固定 `-Xmx2048m`，没有为大文件临时提高 |
| 产品默认并发 | 1；可显式配置为 1–2 |
| 大文件验收方式 | 三格式使用互相隔离的数据目录并行运行；表内时间为各单格式自身墙钟时间 |

## 3. 合成语料与复现方式

生成器：`io.github.caipeijia833.docredaction.tools.StressCorpusGenerator`
随机种子：`5999162255487883845`

```text
--generate-stress-corpus <目录> full
--generate-office-tier <office-content-ladder目录> 512
--generate-office-tier <office-content-ladder目录> 1024
--acceptance-run <输入文件> <隔离数据目录> <报告JSON>
```

Office 样本不是随机字节改扩展名：ZIP 内含有效的 `[Content_Types].xml`、关系部件和真实 Word 段落、Excel 行/单元格、PowerPoint 段落。1 GiB Office 输入为有效 OOXML 包，物理大小低于上传上限；生成器不会用稀疏文件或随意尾部字节伪造 Office 体积。所有姓名、证件、电话、邮箱与案号均为固定的虚构内容。

## 4. 最终结果

### 4.1 Office 包级事件流阶梯

| 格式 | 输入字节 | 状态 | 用时 | 输出字节 | 命中 | 结构与残留复核 |
|---|---:|---|---:|---:|---:|---|
| DOCX 256 MiB | 266,339,786 | 完成 | 550.474 s | 21,432,477 | 11,511 | `OOXML_OK entries=3`；完整复扫通过 |
| XLSX 256 MiB | 266,340,705 | 完成 | 581.750 s | 25,231,184 | 19,073 | `OOXML_OK entries=5`；完整复扫通过 |
| PPTX 256 MiB | 266,340,610 | 完成 | 609.113 s | 20,906,415 | 10,740 | `OOXML_OK entries=5`；完整复扫通过 |
| DOCX 512 MiB | 534,774,942 | 完成 | 1,152.492 s | 42,988,268 | 18,206 | `OOXML_OK entries=3`；完整复扫通过 |
| XLSX 512 MiB | 534,775,991 | 完成 | 1,257.944 s | 26,686,383 | 7,915 | `OOXML_OK entries=5`；完整复扫通过 |
| PPTX 512 MiB | 534,776,099 | 完成 | 1,064.113 s | 41,952,789 | 16,873 | `OOXML_OK entries=5`；完整复扫通过 |
| DOCX 近 1 GiB | 1,071,646,102 | 完成 | 2,548.602 s | 86,086,548 | 36,411 | `OOXML_OK entries=3`；完整复扫通过 |
| XLSX 近 1 GiB | 1,071,647,560 | 完成 | 2,991.889 s | 36,960,509 | 10,392 | `OOXML_OK entries=5`；完整复扫通过 |
| PPTX 近 1 GiB | 1,071,647,287 | 完成 | 2,327.863 s | 84,006,258 | 33,913 | `OOXML_OK entries=5`；完整复扫通过 |

1 GiB 阶梯 SHA-256：

| 格式 | 输入 SHA-256 | 输出 SHA-256 |
|---|---|---|
| DOCX | `431457ecd8358c5afb23dfb3cbd708789174156c9cd2fb107e31693240535b30` | `70652b8cc174051c6ac2a59998f65eb12c7fa6246abcb64dd5e99303ad61563d` |
| XLSX | `5cfcef90aab5ab86ad4530f27d537af7fb4544284fd056dc26978763a3eacebf` | `8e59cf211636e1dbf15beb0630e8b2ba22b1b4ab2023b8af0280bc01362746c7` |
| PPTX | `4c0aebd1d5ecf3926aa96accae0c4e6219cd80b8db29d2ddcf27064968d61e7a` | `99fb8fb4abbbf1d5b02f394d09e20f71523cc5a43988eee0a401b78d28e2d9b9` |

命中数量不代表合成语料包含同等数量的真实敏感字段。高熵十六进制占位数据可能偶然通过银行卡、IBAN 等校验规则，系统按安全优先策略过度脱敏。意大利税号已收紧到真实字段结构，印度 Aadhaar 已增加字段上下文要求，以减少明显误报。

### 4.2 PDF

| 输入 | 输入字节 | 状态 | 用时 | 输出字节 | 页数 | 结构复核 |
|---|---:|---|---:|---:|---:|---|
| PDF 256 MiB | 268,435,456 | 完成 | 23.695 s | 1,285,704 | 32 | `PDF_OK` |
| PDF 512 MiB | 536,870,912 | 完成 | 41.188 s | 2,576,535 | 64 | `PDF_OK` |
| PDF 精确 1 GiB | 1,073,741,824 | 完成 | 75.704 s | 5,152,390 | 128 | `PDF_OK` |

精确 1 GiB PDF 输入 SHA-256：`1018aa7c748c61d26b91f3818ba71b30fd6f129da4c118954e9620300c9ae684`。

### 4.3 精确 1 GiB TAR 卷宗

| 项目 | 结果 |
|---|---|
| 输入 | 1,073,741,824 字节 |
| SHA-256 | `6a24c88450ead902b7835336004e53d6224583fd80ac66b8c5c57c0f2d195d8d` |
| 清单用时 | 6.880 s |
| 条目 | 5 个：4 个 `REDACTABLE`，1 个 `UNSUPPORTED` |
| 未支持文件策略 | `unsupported/padding.bin` 默认不进入结果包 |
| 状态 | 正确进入 `AWAITING_CONFIRMATION`；验收程序随后取消任务 |

该用例验证接收、容器识别、清单分类和二次确认门，没有选择“原样保留”。

## 5. 本轮实现与修复

1. DOCX/XLSX/PPTX 的处理、预览、人工复核和输出残留扫描统一使用 OOXML 包级事件流，不再构建完整 Office 对象模型。
2. ZIP 包限制为最多 100,000 个部件、单部件 2 GiB、声明展开总量 8 GiB；重复部件、路径穿越、未知大小、DTD/外部实体均失败关闭。
3. 单个段落、单元格或其他语义单元最多缓冲 16 MiB 文本和 250,000 个 XML 事件。合法大文件按语义单元扩展；异常的单个超大段落不会被无界接收。
4. OLE、ActiveX、VBA、Office 数字签名部件失败关闭；内嵌位图逐项走本地 OCR；包缩略图替换为空白缩略图。
5. Excel 数字单元格命中后转换为字符串；公式内含敏感值时失败关闭，不静默改变计算逻辑。
6. 输出残留复扫不再受人工复核 5,000 项列表限制；不可逆模式检查是否仍含未遮罩字符，一致替换模式全量比对原值集合。
7. 同一语义单元最多执行 8 轮定点脱敏，防止第一次遮罩改变边界后形成新的有效标识符。
8. Office 默认工作进程时限从 30 分钟调整为 120 分钟；默认堆仍为 2 GiB，默认并发仍为 1。

## 6. 证据文件

- 512 MiB：`stress-results/stream-{docx,xlsx,pptx}-512-final.json`
- 1 GiB：`audit/evidence/stream-{docx,xlsx,pptx}-1gib-final.json`
- 精确 1 GiB TAR：`audit/evidence/archive-1gib-final-20260812.json`
- 精确 1 GiB PDF（含 Job Object 遥测）：`audit/evidence/pdf-1gib-jobobject-telemetry-20260814.json`
- 自动测试报告：`app/build/reports/tests/test/index.html`

大型语料、隔离运行目录和 JSON 结果由 `.gitignore` 排除，不应提交到 GitHub。公开仓库保留生成器源码、测试和本报告即可；复核人应在本机重新生成并运行，而不是依赖本机私有压力文件。

## 7. 仍未完成

- 真实业务卷宗或经合法去标识化的金标语料；本轮只有合成数据。
- 1 GiB OFD、复杂 Office 对象的多阅读器兼容、宏或嵌入对象的安全转换；当前相应危险部件失败关闭。
- 10,000 文件卷宗、8 GiB 展开量、暂停恢复、断点续传和故障注入。
- 峰值 RSS、I/O、临时磁盘与 CPU 的连续采样；本报告只有进程私有内存抽样。
- macOS 13 Intel/Apple Silicon 实机、代码签名、公证、NFC/NFD 中文路径与断网验收。
- 真实低清、旋转、印章覆盖、表格与复杂版式 OCR 金标召回率。
