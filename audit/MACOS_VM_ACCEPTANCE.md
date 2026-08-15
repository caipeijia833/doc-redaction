# macOS 13+ 虚拟机验收清单

本清单只把虚拟机中的实测结果视为证据。Windows 构建成功、脚本语法检查或 Rosetta 转译运行都不能替代对应架构验收。

## 准备

1. 确认虚拟机架构：Apple Silicon 使用 `arm64` 包；Intel/AMD 主机虚拟机使用 `x86_64` 包。不要用 Rosetta 结果冒充原生架构通过。
2. 在同一架构 macOS 13 或更高版本准备 Java 21、Tesseract、FFmpeg/FFprobe、whisper.cpp、OpenCV 4.13 Java JNI、llama.cpp Metal 与固定模型目录。
3. 设置 `JAVA_HOME`、`DOC_REDACTION_OCR_ROOT`、`DOC_REDACTION_MEDIA_ROOT`、`DOC_REDACTION_VLM_ROOT`，执行 `./package-macos.sh`。
4. 将生成的 tar.gz 复制到一个全新目录并解压，避免直接验收构建目录。

## 自动验收

把 `audit/macos-acceptance.sh` 复制到虚拟机后执行：

```sh
chmod +x ./macos-acceptance.sh
./macos-acceptance.sh /absolute/path/to/doc-redaction-poc-macos-arm64
```

脚本会验证组件哈希与同架构原生代码、OpenCV JNI、首次启动、回环监听、Host 拒绝、单实例锁、未观察到的非回环已建立连接、中英文 NFC/NFD 路径、DOCX 脱敏和正常退出。证据位于包内 `data/diagnostics/macos-acceptance/`。

## 必须人工补充的项目

- 在虚拟机设置为断网后，分别处理 DOCX、XLSX、PPTX、PDF、OFD、ZIP、音频和视频；记录输出文件、耗时、峰值内存和肉眼版式检查。
- 用 Preview、Microsoft Office/WPS、Adobe Acrobat 和至少两个 OFD 阅读器检查对应格式；OFD 原签章失效必须按重签流程确认。
- 验证拖拽上传、批量压缩包确认、不支持文件二次确认、五标签规则库、黑白名单、还原中心及错误恢复。
- Apple Silicon 与 Intel 必须分别执行；如果项目只支持其中一个架构，应在发布说明中明确，不得写“双架构已通过”。
- 正式发布包完成 Developer ID 签名和公证后，以 `DOC_REDACTION_REQUIRE_SIGNED=1` 重跑自动脚本，并保留 `codesign`、`spctl` 和 notarization 日志。
- 使用至少一份真实脱敏后的业务金标副本验证，不把原始敏感材料放进代码仓库或测试报告。

## 结果判定

- 自动脚本全部 PASS，且人工格式/业务项目全部有证据：该架构可进入受控候选发布。
- 任一原生组件通过 Rosetta 才能运行、OpenCV JNI 缺失、非回环监听、断网失败或文档残留敏感信息：阻断该架构发布。
- 未完成签名/公证时，只能标记为开发测试包，不能标记为正式 macOS 分发包。
