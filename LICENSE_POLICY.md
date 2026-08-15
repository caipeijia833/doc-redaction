# License policy / 许可证策略

## Project license / 项目许可证

本项目源码采用单一的 **Apache License 2.0**，根目录 [`LICENSE`](./LICENSE) 保留 Apache 官方完整文本。选择 Apache-2.0 的原因是它同时提供宽松使用、修改和再分发权，并包含明确的专利授权与专利诉讼终止条款，适合安全与文档处理工具。

项目所有者已确认以公开 GitHub 账号 `caipeijia833` 作为本项目的版权主体标识；对应版权声明见根目录 [`NOTICE`](./NOTICE)。主要维护者信息单独记录在 [`MAINTAINERS.md`](./MAINTAINERS.md)，但维护者身份本身不能自动推定版权归属。Apache 模板末尾的 `Copyright [yyyy] [name of copyright owner]` 是官方示例占位符，根目录 `LICENSE` 保持 Apache-2.0 官方全文，不以该附录模板替代 `NOTICE` 中的版权声明。

## Why there is no second root MIT license / 为什么不再添加根 MIT

MIT 和 Apache-2.0 都是宽松开源许可证，但在根目录同时放置两份许可证而不声明“二选一”或适用范围，会使接收者无法判断项目究竟是双许可还是文件分许可。因此本项目不添加第二份根 MIT 许可证。

第三方 MIT 组件仍按其各自 MIT 条款分发；其许可证原文、版本和适用目录由 [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md)、SBOM 和离线包内各组件 `licenses/` 目录记录。这不会把本项目自身许可证改成 MIT，也不代表删除或重许可第三方权利。

## Contributions / 贡献

除非贡献者明确书面声明“Not a Contribution”，向本项目提交并被接收的贡献按 Apache-2.0 第 5 条处理。贡献者只能提交其有权许可的代码、文档和数据；来源不明或许可证不兼容的材料不合并。

## Binary distribution / 二进制分发

二进制包必须同时包含根 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md`、CycloneDX SBOM 和相关第三方许可证。LGPL/GPL/带运行库例外组件还必须满足其源代码、替换/重新链接和通知义务；项目 Release 脚本对尚未完成的 FFmpeg 对应源代码闭包实行强制阻断。
