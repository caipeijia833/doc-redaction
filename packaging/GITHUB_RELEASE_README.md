# Windows x64 离线包分卷说明

此 Release 使用原始 ZIP 的二进制分卷，原因是 GitHub Release 要求每个附件小于 2 GiB。必须下载本 Release 的全部 `partNNN` 文件和校验/合并文件，不能单独解压某个分卷。

## Windows 合并

把所有附件放在同一目录，然后运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\join-windows.ps1
```

脚本先逐个验证分卷大小和 SHA-256，按固定顺序流式合并，再验证最终 ZIP 的大小和 SHA-256。已有同名但校验不一致的文件不会被覆盖。

## macOS / Linux 合并

把所有附件放在同一目录，然后运行：

```bash
chmod +x join-unix.sh
./join-unix.sh
```

macOS 使用系统自带 `shasum`，Linux 优先使用 `sha256sum`。合并成功后得到 `doc-redaction-poc-windows-x64.zip`；这个运行包本身只能在 Windows x64 上运行。

## 必须下载的附件

- `doc-redaction-poc-windows-x64.zip.part001` 起的全部连续分卷；
- `RELEASE_ASSETS.json`；
- `PARTS.txt`；
- `PART_SHA256SUMS.txt`；
- `FINAL_SHA256.txt`；
- `join-windows.ps1` 或 `join-unix.sh`；
- 建议同时下载 `RELEASE_SHA256SUMS.txt` 复核其他发布附件。

无需下载完整 3.6 GB 离线包即可先行审查的独立附件：

- `SBOM.cdx.json`：Java 运行依赖 CycloneDX SBOM；
- `BUILD_MANIFEST.json`：构建、测试、安全门禁及尚未验证范围；
- `SECURITY_GATE.json`：最近一次本地秘密扫描、离线依赖漏洞扫描及策略门禁摘要；
- `THIRD_PARTY_NOTICES.md`：第三方组件、模型和许可证边界；
- `LICENSE`：项目 Apache-2.0 许可证。

本包是单机离线 Alpha/受控试点候选，不是已经完成真实法律卷宗和多平台生产验收的正式产品。处理真实唯一原件前，必须阅读包内 README 的限制说明。
