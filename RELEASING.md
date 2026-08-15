# Release process / 发布流程

本文件描述维护者操作，不代表当前版本已发布。任何 Git 提交、推送、Release、签名和公开操作都必须由项目所有者另行确认并执行。

## 1. Freeze scope / 冻结范围

1. 更新 `VERSION` 和 `CHANGELOG.md`，确认 Alpha/RC/正式版语义一致。
2. 检查 `MAINTAINERS.md` 的维护者和签名责任人已经由真实人员填写。
3. 确认实现状态、格式矩阵、README 中的能力表和已知限制一致。
4. 只使用合成数据或有明确许可、固定来源和哈希的公开测试材料。

## 2. Verify source / 验证源码

Windows 维护机运行：

```powershell
.\build-windows.ps1
.\build-windows.ps1 -WithNativeTests
.\audit\run-local-security-gate.ps1 -RefreshVulnerabilityDatabase
```

macOS 13+ 目标架构机器运行：

```bash
./build-macos.sh
```

macOS 运行包还必须执行 `audit/macos-acceptance.sh`。没有真实机器证据时只能标记“未验证”，不能沿用 Windows 结论。

## 3. Build release assets / 生成附件

1. 在受控维护机从固定来源准备 JRE、OCR、FFmpeg、whisper.cpp、OpenCV 和可选 VLM。
2. 生成 Windows/macOS 离线包、`SBOM.cdx.json`、`SHA256SUMS.txt` 和组件清单。
3. 对 LGPL/GPL/带例外组件随包保留许可证、对应源代码或有效书面源代码获取方式，以及允许替换/重新链接所需材料。
4. 使用 `packaging/split-release-assets.ps1` 或平台等价脚本仅拆分 GitHub Release 附件；不要把大运行包提交进 Git 历史。
5. 按 [OPEN_SOURCE_PUBLISHING.md](./OPEN_SOURCE_PUBLISHING.md) 复核分卷连续性、合并后哈希和 GitHub 附件上限。

## 4. Sign and publish / 签名与发布

发布签名私钥必须位于仓库和构建目录之外。签名责任人复核构件哈希后签名，另一名实际复核者（若尚无第二名维护者，则由项目所有者本人二次核对）验证签名、公钥、版本号和下载说明。

GitHub Release 说明必须列出：支持平台、未验证平台、安全边界、升级/回滚方式、组件许可证、分卷合并命令、SHA-256 和已知问题。发布后不得用同一版本号静默替换附件；任何变化都发布新版本。

## 5. Post-release / 发布后

验证公开仓库从干净克隆可构建，检查 Actions、CodeQL、Dependency Review、Private Vulnerability Reporting 和分支保护。抽样下载 Release 附件，重新合并并验证哈希。发现安全或合规阻断时立即撤下受影响附件并发布说明，不改写既有证据。
