# 资产与离线运行时获取策略

本仓库只保存可审查的源代码、构建脚本、组件清单、许可证和合成/公开样例；不保存模型权重、OCR/媒体运行时、发布 ZIP、真实文档、日志或用户数据。应用运行时不会自动联网下载任何组件。

## 唯一可信清单

下载或制作离线包时，必须以以下已提交清单中的 **文件名、来源 URL、SHA-256 和许可证** 为准：

- 本地多模态模型与 llama.cpp：[`audit/VLM_COMPONENTS.json`](./audit/VLM_COMPONENTS.json)
- OCR：[`audit/TESSERACT_WINDOWS_PROVENANCE.md`](./audit/TESSERACT_WINDOWS_PROVENANCE.md)
- 音视频、OpenCV、FFmpeg 与 whisper.cpp：[`audit/MEDIA_COMPONENTS.json`](./audit/MEDIA_COMPONENTS.json)
- Java/Maven 依赖：[`app/gradle.lockfile`](./app/gradle.lockfile)、[`app/gradle/verification-metadata.xml`](./app/gradle/verification-metadata.xml) 和构建产生的 CycloneDX SBOM

`example.com`、`REPLACE_ME`、未经固定版本的“最新下载链接”均不是可用来源，不能用于发布或离线部署。

## 手动下载与校验

仅在需要可选能力时下载对应组件。下载后先计算 SHA-256，与清单完全一致才可放入离线包或指定目录；不匹配时立即删除文件。

Windows PowerShell 示例（将清单中的 URL、目标文件名和 SHA-256 原样填入）：

```powershell
$url = '<复制 audit/VLM_COMPONENTS.json 中的固定 URL>'
$target = 'models\\<清单中的文件名>'
$expectedSha256 = '<清单中的 sha256，小写>'

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
Invoke-WebRequest -Uri $url -OutFile $target
$actualSha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
  Remove-Item -LiteralPath $target -Force
  throw "SHA-256 mismatch; the asset was removed."
}
```

macOS/Linux 等价示例：

```bash
url='<copy the fixed URL from the component manifest>'
target='models/<manifest file name>'
expected_sha256='<manifest sha256, lowercase>'
mkdir -p "$(dirname "$target")"
curl --fail --location --proto '=https' --tlsv1.2 "$url" -o "$target"
actual_sha256="$(shasum -a 256 "$target" | awk '{print $1}')"
[ "$actual_sha256" = "$expected_sha256" ] || { rm -f "$target"; echo 'SHA-256 mismatch; asset removed.' >&2; exit 1; }
```

下载行为应在受控的部署阶段完成，而非由应用后台执行。生产离线环境应从已验证的内网镜像或经签名的 Release 附件获取，并保留来源、哈希、时间和许可证记录。

## Git 与 Release 边界

提交源码仓库：`app/`、`audit/` 中的清单与公开证据、`docs/`、`design/`、`packaging/`、`tools/`、`.github/` 和根目录治理文件。

不提交：`models/`、`runtime/`、`tessdata/`、`dist/`、`release-assets/`、`data/`、`.env*`、日志、真实样例、压力测试输入/输出以及任何恢复密文或私钥。

模型与运行时即使需要 Git LFS，也不应进入本项目的源代码主分支；优先作为经哈希校验的 Release 附件或企业内网镜像分发。Windows 分卷包与合并校验见 [`packaging/GITHUB_RELEASE_README.md`](./packaging/GITHUB_RELEASE_README.md)。

## 公开样例白名单

`samples/` 仅允许当前三份合成 Office 样例和合成 PDF；`app/src/test/resources/public/ofdrw/` 仅允许 `PROVENANCE.md` 中列出的公开 OFDRW 样例及其许可证。`.gitignore` 已对此实施精确白名单，任何新增样例都必须先完成来源、许可和敏感信息审查，再显式更新白名单与说明文件。
