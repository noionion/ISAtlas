# 本地 CSR / 指令包

在“关于 ISAtlas → 本地数据包”中，通过系统文件选择器选择 ZIP，查看预览后确认导入。支持 CSR 包、指令包和组合包；不需要网络、存储权限或官方签名。仅复制用户选中的文件，不保留文件选择器 URI，也不改写原文件。

导入和校验在后台执行，原有查询数据继续可用。确认成功后，切换到包含已启用扩展的内存目录。管理页支持启用、停用、同包升级/替换和卸载。替换可选择任意版本，预览会显示新旧版本；保留原来的启停设置。失败时不切换启用索引。卸载应用会删除已导入副本，请自行保留原 ZIP。

## 公开测试包

- [玄铁 CSR 包](../examples/local-packages/thead-csr.zip)：从内置数据中移出的 `th.sxstatus`，仅 RV64；保留固定提交来源、Apache-2.0 许可、低 22 位的原文歧义和 THEADISAEE 不应清零的约束。提供中文摘要和中英文枚举，未将未知位猜成 WPRI。
- [虚构组合包](../examples/local-packages/demo-combined.zip)：包含 `demo.control`、custom-0 编码 `demo.add` 和 `xdemo → i` 前置关系，仅用于功能演示。

包中声明的发布者和来源并不等于身份认证。所有本地包均明确标注“本地导入 / 来源未经官方验证”，不被当作内置官方数据。

## ZIP 结构

```text
manifest.json
csr/one.json
inst/one.json
```

`csr` 或 `instructions` 文件数组可以单独为空，但不能同时为空。不支持可执行脚本、外部资源、嵌套 ZIP、目录遍历或未声明的额外文件。每个 CSR / 指令一个 JSON。JSON 使用 UTF-8，不接受重复键或超过 32 层的嵌套。

清单示例：

```json
{
  "schemaVersion": 1,
  "id": "com.example.internal",
  "namespace": "example",
  "name": "内部扩展",
  "version": "1.0.0",
  "publisher": "示例团队",
  "architecture": "riscv",
  "minAppVersionCode": 9,
  "description": "离线查询用的数据包",
  "license": "内部使用",
  "licenseText": "许可正文（可选）",
  "csr": ["csr/one.json"],
  "instructions": ["inst/one.json"],
  "hashes": {},
  "dependencies": [],
  "extensions": {"xexample": ["i"]}
}
```

`id` 长度 3–80，由小写字母、数字、点、连字符组成；`namespace` 以小写字母开头，最长 64，只包含小写字母和数字。名称、版本、发布者、许可必填。当前只支持 `riscv`；`minAppVersionCode` 为应用构建号而非显示版本。

`hashes` 在源模板中可以为空，制作工具会按原始文件字节填写所有 SHA-256；实际导入时必须完整匹配。哈希用于检查内容一致性，不是来源认证。

`dependencies` 使用精确版本：`[{"id":"com.example.base","version":"1.0.0"}]`。缺失、停用、版本不符或循环依赖会使包暂停启用；管理页展示原因。恢复依赖后重新计算启用状态，不会把私有依赖拿到网络下载。

`extensions` 只声明新增的 `x` 前缀扩展，不重定义内置编译器词表。前置项必须是内置 token 或本包声明的扩展，不接受循环。启用后扩展进入统一筛选；选择时自动勾选其必需前置项。跨包依赖是数据包版本依赖，自定义扩展前置项本版不支持跨包引用。

## CSR 表达

沿用 [CSR 数据模型](data-format.md)，最小示例见 [demo-control.json](../examples/local-packages/demo/csr/demo-control.json)。必填名称、中文摘要、locator、privilege、access、xlens、fields。描述推荐保留 `summaryEn` 和 `valuesEn`；未提供英文摘要时沿用中文，枚举有值时中英文编码集合必须一致。

`locator` 使用 `{"type":"riscv-csr","address":1984}`。`privilege` 支持 M/S/H/HS/VS/U/D，寄存器 `access` 为 RW/RO；字段可以表达 WARL、WLRL、WPRI 等更细属性。layout 保存 xlen、msb、lsb，端点均包含。支持字段枚举、别名、条件、互斥 `view` 和保留区域。未知位可以留空，不能猜测为 WPRI。

可省略 `id`、`namespace`、`title`、`notes`、`sourceRevision`、`license`、`sourceUrl`；由包清单生成默认值。显式提供 ID 时必须为 `riscv/<namespace>/csr/<name>`。sourceUrl 未提供时使用 `local://<包ID>`，不会伪造网址。可显式保留 HTTPS 原文来源。没有字段时默认为仅地址条目。

## 指令表达

沿用 [指令数据模型](instruction-data.md)，示例见 [demo-add.json](../examples/local-packages/demo/inst/demo-add.json)。支持 16/32 位编码、RV32/RV64、执行特权态、扩展 AND/OR 条件、mask/match、操作数位映射、寄存器类型/偏移/映射、静态编码约束和 eq 操作数关系。`kind` 为 instruction/alias/hint；aliasOf 必须指向同包内的基础指令。本版不导入新的伪指令定义。

ID 默认为 `riscv/<namespace>/inst/<name>`；元数据与 CSR 类似，可从清单补齐。`mask`、`match` 必须带 `0x` 前缀且最多 8 位；字段需覆盖全部指令位，固定字段与 mask/match 一致，可变字段声明 operand。`requires` 的外层为 OR、内层为 AND，例如 `[["i","xexample"]]`。本地指令不会根据与标准指令同名就推断其功能或汇编语法。

`assemblyOperands` 可以按名称列出操作数顺序，例如 `["rd","rs1","rs2"]`，用于汇编展示和使用示例；未声明时展示解码字段，不猜语法。`operandBits` 长度与物理字段位宽一致，位置不能重复；寄存器映射范围为 0–31。

静态编码约束最多 8 组、每组最多 32 个值，支持 in/notIn；每组最多涉及 12 个可变位，多个约束不能重复覆盖可变位。操作数 eq 关系最多 4 组。每个 XLEN 必须存在满足所有静态约束的编码。约束更复杂的定义应先拆分编码变体。

## 冲突与生命周期

内置命名空间保留。不同本地包也不能占用相同命名空间或自定义扩展名。包内重复 ID、重复 CSR 地址、互相重叠的物理字段、越界枚举或不合法编码直接拒绝，并标明文件 / 字段 / 编码位置。

不同来源可以有同地址 CSR 或重叠指令编码。预览显示冲突数量，用户明确确认后共存；内置数据不会被覆盖。潜在编码冲突按固定 mask/match 和 XLEN 检测，可能还需核对扩展或运行条件。相同包 ID 的导入属于显式替换，只替换该本地包。

包原始 ZIP 存在应用私有 `noBackupFilesDir`，文件名使用 SHA-256，不包含发布者的文件路径。索引通过 AtomicFile 提交，旧索引在失败时保留。启动重新校验本地包；损坏、不兼容或依赖不满足的包隔离并提供卸载/替换入口，内置目录继续可用。应用升级保留私有目录，卸载应用不保留。

限制：每个 ZIP 最大 16 MiB，单文件 1 MiB、清单 256 KiB，展开合计 8 MiB、最多 2,048 个数据文件；最多安装 8 个包，ZIP 合计 32 MiB、展开数据合计 16 MiB。限制用于控制手机内存和校验开销。

## 制作与离线校验

复制一个示例目录，修改 JSON 和 manifest.json 后，在仓库根目录执行：

```powershell
python tools/package_local.py examples/local-packages/thead my-thead.zip
./gradlew.bat :core:validateLocalPackage '-PlocalPackage=my-thead.zip'
```

只生成指令包：

```powershell
python tools/package_local.py examples/local-packages/demo my-instructions.zip --kind instructions
./gradlew.bat :core:validateLocalPackage '-PlocalPackage=my-instructions.zip'
```

`--kind csr`、`instructions`、`combined` 分别保留相应内容。Python 工具生成确定性的 ZIP 和哈希；Gradle 校验任务直接调用 Android 共用的 Kotlin 核心校验器，避免两套规则产生差异。依赖已缓存后可添加 `--offline`；工具不读取 GitHub、上传内容或请求官方签名。

## 保密边界

导入模块没有网络调用，不上传包内容、查询、名称或索引，不生成公共 Issue，不自动更新官方数据库；错误只显示在本机。现有应用更新检查只访问公开版本渠道，与本地包无关。用户主动点击包内 HTTPS 原文链接时才打开浏览器。没有诊断导出功能，也不将私有数据写入日志。

应用原有 `allowBackup=false` 保持不变，本地包额外放在系统排除备份的 `noBackupFilesDir` 中。系统文件选择器的定向授权和无额外存储权限机制见 [Android SAF 文档](https://developer.android.com/training/data-storage/shared/documents-files)；备份排除目录见 [Android Auto Backup 文档](https://developer.android.com/identity/data/autobackup)。
