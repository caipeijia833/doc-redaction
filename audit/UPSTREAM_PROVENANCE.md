# 上游源码来源锁定记录

审计日期：2026-08-11

## 锁定结果

| 仓库 | 标签/基线 | 固定提交 | 初步用途 | 处理结论 |
|---|---|---|---|---|
| Microsoft Presidio | `2.2.364` | `779dbd286d5ef4d1fbe2514275fb1bce358f2417` | PII识别与匿名化接口参考 | 有条件使用 |
| Apache POI | `REL_5_5_1` | `094968cfc3d48224db08f0b7f0a6fc341b035114` | Office格式解析与回写 | 进入PoC |
| Apache PDFBox | `3.0.8` | `9286e47d89d6877005c9d2d0f2fd38793a62519a` | PDF解析、渲染、对象处理 | 进入PoC |
| qpdf | `v12.4.0` | `babad179ce5db9a21635c8d1ac17baa59637eada` | PDF结构重写和净化验证 | 进入PoC |
| OFDRW | `2.4.0` | `7459e35082170061efa6b399a6518dbc219f08ac` | OFD解析、写回及签章处理 | 仅允许白名单模块 |
| RapidOCR | `v3.9.2` | `095232a4c94f7f0e6600ba5bba1177010ad696d4` | 中文离线OCR | 有条件使用 |
| JPDFium | `v1.0.4` | `5864069ca835d041715d88e71dcb108b95a86d76` | PDF真删除能力验证 | 研究用途 |
| RapidOcrOnnxJvm | 未发布标签 | `09e44e21fa5e2067c7381ab5cbfae68c7a342e90` | 单Java运行时OCR可行性 | MVP拒绝 |

完整的远程地址、Git tree、许可证路径及 SHA-256 见 [upstream-lock.json](./upstream-lock.json)。

## 获取与验证方法

1. 所有仓库均从公开 GitHub HTTPS 地址浅克隆。
2. 有稳定发布标签的仓库切换到确定标签，工作区保持洁净。
3. 记录提交哈希、Git tree 和许可证文件 SHA-256。
4. 未安装任何依赖，未运行 Gradle、Maven、Python、CMake 或仓库脚本。
5. 本机没有 GPG，因此没有完成标签或提交签名验证。Git 提交哈希只能证明当前工作区与记录一致，不能替代发布者签名。

## 复核结果

`verify-upstream.ps1` 已在本机运行，八个仓库的远程地址、提交、Git tree、许可证哈希和工作区洁净状态全部通过，输出标志为：

`UPSTREAM_VERIFICATION_OK`

## 限制

- 本次是当前源码树静态审计，不包含完整 Git 历史。
- 已生成包含主运行时与 OFD worker 的 CycloneDX SBOM，并保留 Gradle 依赖锁及构件 SHA-256。2026-08-14 已对 66 个 Maven 组件执行一次 OSV Batch API 时点扫描，并对当前源码树执行强特征秘密扫描，结果均为 0；这不替代持续 CVE 门禁、完整 Git 历史扫描、SAST，也未接入 Trivy、Grype、OSV-Scanner、Gitleaks 或 Semgrep。
- OCR 训练数据许可证、发布二进制签名和跨平台可复现构建仍需继续验证。
