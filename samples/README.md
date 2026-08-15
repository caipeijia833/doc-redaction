# 合成验收样例

本目录内文档由项目自带生成器创建，所有姓名、号码、机构、地址和案件信息均为合成测试数据，不对应真实主体。

样例覆盖 DOCX、XLSX、PPTX 和文本型 PDF。它们只用于验证基础处理链路，不代表复杂 Office 对象、扫描 PDF 或 1 GB 文件已经通过验收。

生成命令（Windows PowerShell）：

```powershell
$env:JAVA_HOME = (Resolve-Path '..\.tools\jdk-dist\jdk-21.0.12+8').Path
& "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' -jar '..\app\build\libs\doc-redaction-poc-0.1.0-poc-all.jar' --generate-samples '.'
```

禁止把真实涉密、个人敏感或业务生产文档用于当前 PoC 验收。
