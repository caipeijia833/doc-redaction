# Windows 本地 OCR 来源与构建边界

## 当前结论

Windows x64 运行包内置项目级静态 Tesseract 5.5.2、`chi_sim`、`eng` 和 `osd` 数据，不修改系统 PATH、注册表或 Windows 服务。Java 主程序只以受限子进程调用 OCR，不把原生库加载到 JVM。

当前 MSVC 静态可执行文件 SHA-256 为 `112effb15d42b9beac0bdc06fdfe4c8d9f8bd43b142afc9da6dad56a1d0ba366`。`dumpbin /dependents` 实测仅列出 Windows `KERNEL32.dll`；运行目录不包含第三方 DLL。

## 固定输入

| 输入 | 版本/标签 | 实际代码提交 | 许可证 |
|---|---|---|---|
| Tesseract | 5.5.2 | `6e1d56a847e697de07b38619356550e5cf4e8633` | Apache-2.0 |
| Leptonica | 1.87.0 | `13275a278eb55b5746e33f95fbf5a2c8f604b3ab` | BSD-2-Clause |
| tessdata_fast | 4.1.0 | `65727574dfcd264acbb0c3e07860e4e9e9b22185` | Apache-2.0 |
| vcpkg | 2026.07.29 | `9e593bb18ea69cc5095e012465dcd675a822ed0d` | MIT |
| libpng | 1.6.58 | 由固定 vcpkg baseline 解析 | libpng-2.0 |
| zlib | 1.3.2 | 由固定 vcpkg baseline 解析 | Zlib |

这里记录的是 annotated tag 解析后的实际代码提交，而不是标签对象哈希，避免标签存在时仍发生源码漂移。

## 构建配置与门禁

`audit/build-minimal-tesseract-windows.ps1` 使用 Visual Studio 2022 C++ Build Tools、CMake、Ninja 和 `x64-windows-static`：

1. 固定并校验四个上游实际提交；
2. vcpkg manifest 只允许 `libpng` 与 `zlib`；
3. Leptonica 仅启用 PNG/Zlib，关闭 GIF、JPEG、TIFF、WebP 和 OpenJPEG；
4. Tesseract 关闭 Curl、LibArchive、TIFF、OpenMP、训练工具、ScrollView 和 LTO；运行调用固定使用 `--oem 1`；
5. Tesseract、Leptonica、libpng、zlib 和 MSVC CRT 静态链接；
6. `dumpbin` 检查动态依赖，并拒绝 libgcc、libstdc++、libwinpthread、libarchive、libcurl、libiconv、libintl、libunistring；
7. 执行 `--version`、`--list-langs`，必须同时发现 `chi_sim`、`eng`、`osd`；
8. 生成确定性的 `OCR_COMPONENTS.json` 与 `SHA256SUMS.native.txt`，不写入本机构建路径；
9. Java 全量测试必须执行真实 PNG OCR、TSV 坐标映射和不可逆遮挡。

Tesseract 在无 TIFF 的 Leptonica 上执行 `--list-langs` 时，会尝试加载仅供调试字体生成的内置 TIFF 资源并输出非致命诊断；命令退出码仍为 0、三种语言均正常列出，真实 PNG OCR 集成测试通过。该诊断不构成重新引入 TIFF 及其依赖的理由。

## 许可证结论边界

当前 OCR 随包闭包由 Apache-2.0、BSD-2-Clause、libpng-2.0、Zlib 和 Windows 系统库组成，不再随包携带此前 MSYS2 方案中的 LGPL/GPL/GCC Runtime Library Exception 组件。该结论仅覆盖本次固定源码和实际产物；未来升级版本或启用其他图像编解码器时必须重新生成依赖证据，不能沿用本结论。

旧的 `audit/prepare-tesseract-windows.ps1` 仅保留为历史迁移证据，不再被 Windows 正式构建和打包脚本调用。

## macOS 边界

Windows 可执行文件不能用于 macOS。`package-macos.sh` 仅在构建机显式提供 `DOC_REDACTION_OCR_ROOT` 时捆绑同架构 macOS OCR 运行时；Intel 与 Apple Silicon 包仍须分别在 macOS 13+ 构建、签名、公证并做离线实机验收。
