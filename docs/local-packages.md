# 数据包管理与本地导入

在“关于 ISAtlas → 数据包”中管理内置规范包，或通过系统文件选择器导入 CSR 包、指令包及组合 ZIP。正式 APK 默认只随附 Manual 基础包，提供 CSR 与指令查询。AIA、Debug、SPMP 在独立数据仓库维护，通过“数据库更新”下载统一官方数据库后可独立启停，新增可选包默认关闭。详见[官方数据库独立更新](database-updates.md)。开关立即响应，连续点击以最后一次选择为准，失败时恢复已保存状态；重启后保留选择。

本地包仅在本机保存，不上传内容或原文件 URI，不覆盖内置官方包。确认导入前展示版本、来源、冲突与依赖；支持停用、同 ID 替换和卸载。切换在后台处理，不显示导入进度条或成功提示，不锁定其他按钮。

包格式、大小限制、依赖、隐私边界及制作工具的完整说明，统一维护在 [ISAtlas-data 本地包规范](https://github.com/noionion/ISAtlas-data/blob/dev/docs/local-packages.md)。[公开模板与示例 ZIP](https://github.com/noionion/ISAtlas-data/tree/dev/examples/local-packages) 同样由数据仓库维护。企业内部包不提交到任一公开仓库。

应用侧离线校验 CLI 继续可用：

```powershell
./gradlew.bat :core:validateLocalPackage -PlocalPackage=path/to/package.zip
```

应用仅保留两份固定公开 ZIP 作为 Android 回归夹具，不维护模板 JSON 的第二份副本。内置数据联调与同步见[数据维护](data-maintenance.md)。
