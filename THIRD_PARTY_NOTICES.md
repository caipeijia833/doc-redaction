# 第三方组件说明

本文件用于当前本地 Alpha 构建的许可证审查，不替代各组件原始许可证文本。构建生成的 `SBOM.cdx.json` 覆盖 Java 组件；Windows 包内 `ocr/OCR_COMPONENTS.json`、`media/MEDIA_COMPONENTS.json`、`vlm/VLM_COMPONENTS.json` 及对应 `licenses/` 覆盖原生 OCR、音视频、多模态组件和模型。公开发布前仍需完成人工许可证复核。

核心直接依赖：

| 组件 | 锁定版本 | 用途 | 上游许可证/来源 |
|---|---:|---|---|
| Apache POI | 5.5.1 | DOCX/XLSX/PPTX | Apache-2.0；<https://poi.apache.org/> |
| Apache PDFBox | 3.0.8 | PDF 提取、渲染、重建 | Apache-2.0；<https://pdfbox.apache.org/> |
| Apache Commons Compress | 1.28.0 | ZIP/TAR/7Z | Apache-2.0；<https://commons.apache.org/proper/commons-compress/> |
| XZ for Java | 1.10 | 7Z/XZ 支持 | Public Domain；<https://tukaani.org/xz/java.html> |
| Xerial SQLite JDBC | 3.53.1.0 | 单机持久任务索引 | Apache-2.0；<https://github.com/xerial/sqlite-jdbc> |
| Jackson Databind | 2.22.1 | 严格解析 ffprobe 与 whisper.cpp JSON | Apache-2.0；<https://github.com/FasterXML/jackson-databind> |
| JNA / JNA Platform | 5.19.1 | Windows Job Object 资源约束与工作进程遥测 | Apache-2.0 或 LGPL-2.1-or-later 双许可证，本项目按 Apache-2.0 使用；<https://github.com/java-native-access/jna> |
| OpenCV official Java runtime | 4.13.0 | Windows x64 OpenCV Java 绑定与官方 JNI 本地库 | Apache-2.0；固定官方发布资产、JAR 与 DLL SHA-256；<https://github.com/opencv/opencv/releases/tag/4.13.0> |
| RE2/J | 1.8 | 用户自定义规则的线性时间正则引擎 | BSD-3-Clause；<https://github.com/google/re2j> |
| Log4j API | 2.25.5 | 覆盖 Office 组件的传递版本，不启用 Log4j 日志后端 | Apache-2.0；<https://logging.apache.org/log4j/2.x/> |
| OFDRW Converter | 2.4.0 | 只在隔离 OFD worker 中使用图像导出与栅格重建；构建显式排除全部 `com.itextpdf` 传递依赖 | Apache-2.0；<https://gitee.com/ofdrw/ofdrw> |
| Apache PDFBox 2.x | 2.0.37 | 仅供 OFD worker 满足 OFDRW 二进制兼容；不进入主程序类路径 | Apache-2.0；<https://pdfbox.apache.org/> |
| JSON-java | 20260719 | 覆盖 OFDRW worker 的旧版传递依赖 | Public Domain；<https://github.com/stleary/JSON-java> |
| SLF4J NOP | 1.7.36 | 禁止第三方库向控制台输出文档相关日志 | MIT；<https://www.slf4j.org/> |
| JUnit Jupiter | 5.11.4 | 测试，不进入运行时交付 | EPL-2.0；<https://junit.org/junit5/> |
| Administrative-divisions-of-China | 2.7.0（数据基线 2023-06-30） | 中国大陆省、地市、区县、乡镇街道离线词典；构建时哈希校验并过滤通用名称 | WTFPL；<https://github.com/modood/Administrative-divisions-of-China> |

Windows 随包本地 OCR：

- Tesseract OCR 5.5.2：Apache-2.0；<https://github.com/tesseract-ocr/tesseract/tree/5.5.2>。
- `chi_sim` / `eng` 4.1.0：来自 `tessdata_fast`，Apache-2.0；<https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0>。
- Leptonica 1.87.0：BSD-2-Clause；<https://github.com/DanBloomberg/leptonica/tree/1.87.0>。
- libpng 1.6.58：libpng-2.0；zlib 1.3.2：Zlib。两者通过固定 vcpkg `2026.07.29` 基线以 `x64-windows-static` 构建。
- Windows 二进制由 MSVC 静态构建；关闭 Curl、LibArchive、TIFF、GIF、JPEG、WebP、OpenJPEG、OpenMP 和训练工具。`dumpbin /dependents` 实测仅依赖 Windows `KERNEL32.dll`。
- 当前 OCR 闭包没有随包 GPL、LGPL、AGPL 或 GCC Runtime Library Exception 组件。固定提交、可执行文件 SHA-256、已验证语言和原生文件哈希见随包 `ocr/OCR_COMPONENTS.json`、`ocr/SHA256SUMS.native.txt`；许可证原文位于 `ocr/licenses/`。

Windows 随包本地音视频组件：

- FFmpeg `n8.1.2-34-g9b6c8969e0-20260812`：来自 BtbN 固定 SHA-256 的 `win64-lgpl-shared` 资产；运行时启动检查要求 `--enable-shared`，一旦发现 `--enable-gpl` 或 `--enable-nonfree` 即拒绝启用。该构建启用了 `--enable-version3`，随包按 LGPL-3.0-or-later 处理并保留共享 DLL，便于替换与重新链接。程序默认使用 FFmpeg 内置 `mpeg4` 视频编码器，不依赖 GPL x264/x265。
- whisper.cpp `v1.9.2` CPU x64 运行时及 `ggml-base-q5_1.bin` 多语言量化模型：MIT。运行时不联网、不自动下载模型。
- OpenCV Zoo `face_detection_yunet_2023mar.onnx`：MIT；`license_plate_detection_lpd_yunet_2023mar.onnx`：Apache-2.0。两者锁定仓库提交 `47534e27c9851bb1128ccc0102f1145e27f23f98`。
- 固定下载地址、版本/提交、压缩包和模型 SHA-256、许可范围见 `media/MEDIA_COMPONENTS.json`。许可证原文位于 `media/ffmpeg/`、Fat JAR 的 `META-INF/LICENSE`、`media/licenses/` 与 `media/opencv/licenses/`。OpenCV JNI DLL 来自官方 4.13.0 Windows 资产，不在运行时解压或联网下载。
- 当前车牌视觉模型只对中国车牌有上游定位和本机样例证据；其他国家车牌仍可通过 OCR 文本规则命中，但不能宣称视觉车牌检测已覆盖全球。

## 本地多模态增强

- Qwen3-VL-2B-Instruct GGUF，Q4_K_M 主模型与 Q8_0 多模态投影：Apache-2.0，来源为 Qwen 官方 Hugging Face 仓库。
- llama.cpp `b10405` Windows CPU/Vulkan x64 运行时：MIT。程序只启动监听 `127.0.0.1` 的本地 `llama-server`，运行时不下载模型、不访问云 API。
- 固定 URL、文件大小与 SHA-256 见 `vlm/VLM_COMPONENTS.json`；许可证原文位于 `vlm/licenses/`。

OFD worker 内的 `org.ofdrw.converter.ColorConvert`、`FontLoader` 与 `utils.CommonUtil` 是本项目修改后的最小兼容实现，其非 iText 色彩换算、字体选择/解析流程和 PPM 公式派生自 OFDRW 2.4.0 Apache-2.0 源码；上游版权为 Copyright (c) 2020 Quan guanyu and OFDRW contributors。它们不实现或调用 OFD-to-PDF/iText 方法。源码头与 `NOTICE` 已保留“derived and modified”说明；二进制分发还必须保留 Apache-2.0 原文和本文件。
