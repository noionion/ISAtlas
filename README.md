<div align="center">

<img src="docs/images/icon.svg" alt="ISAtlas 应用图标" width="112" height="112">

# ISAtlas

**指令、寄存器与位字段，一搜即得**

Android 上的离线架构速查工具，支持 RISC-V 指令、CSR 与字段查询。

[下载安装](#下载安装) · [功能预览](#功能预览) · [开发看板](https://github.com/users/noionion/projects/5) · [使用文档](#文档) · [问题反馈](https://github.com/noionion/ISAtlas/issues)

</div>

---

输入指令名、机器码、寄存器名称、地址或字段名，快速查看编码、位布局、功能说明和取值含义。适合阅读底层代码、调试系统，以及学习 RISC-V 架构时随手查阅。

## 下载安装

- **[稳定版 0.5.0](https://github.com/noionion/ISAtlas/releases/latest)**：支持指令查询、完整／部分编码反查与伪指令参考。
- **[预览版 0.6.0-beta.1](https://github.com/noionion/ISAtlas/releases/tag/v0.6.0-beta.1)**：新增指令使用示例、本地数据包导入与数据库独立更新。
- **[全部版本](https://github.com/noionion/ISAtlas/releases)**：查看历史版本、预览版与更新日志。

在对应版本的 Assets 中下载 APK 安装；更新日志及 SHA-256 校验文件随版本提供。沿用现有应用签名，0.4.x 和 0.5.0 预览版用户可覆盖升级并保留应用数据。在「关于 → 更新通道」选择稳定版或预览版，应用会按所选通道检查更新。

下载在后台进行，查询不受影响；下载中的弹窗支持取消、暂停和继续。暂停后继续会重新下载当前安装包，下载完成并校验后可手动安装。

## 特性

| | 功能 | 说明 |
| --- | --- | --- |
| 🔎 | 快速搜索 | 搜索名称、地址、字段和描述，支持 `mstatus`、`0x300`、`mstatus.MIE` 等查询 |
| ⚙️ | 指令速查 | 按名称查看指令编码，输入 binary / hex 反查指令与操作数，CSR / INST 左右分页切换 |
| 🔢 | 部分编码查询 | 用 `?` 标记未知位，查找可能的指令；已知操作数与未确定项分别显示 |
| 🔗 | 别名与伪指令 | 查看别名对应的基础指令，查阅伪指令展开、适用条件与规范来源 |
| 💡 | 指令示例 | 在指令详情查看使用示例，结合操作数和简短语义理解用法 |
| 🧩 | 位域一目了然 | 图形化展示寄存器布局，点击或拖拽选择字段，查看对应功能 |
| 📖 | 取值有据可查 | 中文功能摘要、枚举值与访问规则，支持跳转到规范原文 |
| 🎛️ | 按需筛选 | 按 RV32 / RV64、访问权限、扩展和数据来源筛选 |
| 📱 | 横竖屏适配 | 横屏支持多列结果与更宽位图，返回列表保留浏览位置 |
| 📦 | 离线使用 | 数据随应用提供，日常查询无需联网 |
| 🗂️ | 本地数据包 | 导入自定义 CSR / 指令 ZIP，支持预览、启停、替换与卸载，数据仅保存在本机 |
| 🔄 | 数据库独立更新 | 无需更新 APK 即可更新官方数据，后台下载、校验通过后自动应用 |

## 数据包与数据库更新

应用默认内置 **RISC-V ISA Manual**，安装后即可离线查询 CSR、字段与指令。在「关于 → 检查数据库更新」下载独立发布的官方数据库，可获得 AIA、Debug / Trace、SPMP 等规范数据，并在数据包页按需启用。

数据库与软件分别维护版本，官方与镜像并行检查，支持更新日志、跳过版本和取消下载。下载期间可继续查询，校验通过后自动应用；更新保留本地包及官方包启停选择，新增加的可选包默认关闭。

企业或个人自定义数据可在「关于 → 数据包」选择本地 ZIP 导入。导入前展示内容、来源与冲突信息，按来源共存；本地包不上传，也不会被官方数据库更新覆盖。制作方法见[本地数据包文档](docs/local-packages.md)，官方数据与来源审核维护于 [ISAtlas-data](https://github.com/noionion/ISAtlas-data)。

## 搜索示例

搜索框下方切换 **CSR / INST**，也可以左右滑动。两页共用位宽、权限、扩展与来源筛选，各自保留浏览位置。

| 页面 | 输入 | 查看内容 |
| --- | --- | --- |
| CSR | `mstatus` / `0x300` | 寄存器说明与位布局 |
| CSR | `mstatus.MIE` | 对应字段的功能与取值 |
| INST | `add` / `ret` | 指令或已收录别名的编码规则、扩展与简短语义 |
| INST | `call` / `vmsge.vx` | 伪指令的参考展开、适用条件与改写寄存器 |
| INST | `csrr` / `fneg.s` | CSR、浮点等别名及对应基础指令 |
| INST | `0x00b50533` | 匹配 `add`，解码 `rd=x10`、`rs1=x10`、`rs2=x11` |
| INST | `0x00?50533` | 根据已知位查询候选，`rs2` 标为“未确定” |
| INST | `0b00000000101101010000010100110011` | 同一指令的二进制反查，支持空格或下划线分组 |

机器码反查可生成并复制汇编表示，支持切换 ABI / 数字寄存器名；CSR 操作数可直接跳转到寄存器详情。输入格式或筛选条件导致无结果时，会给出对应提示。

部分编码中的 `?` 在十六进制中代表 4 位，在二进制中代表 1 位。无前缀的 4 / 8 个字符按十六进制识别，16 / 32 个且仅含 `0/1/?` 的字符按二进制识别；带通配符时需提供完整长度，界面会显示识别结果。

完整指令名优先显示，指令名与描述匹配分组展开；同名的扩展与编码变体收在同一张卡片中。反查出现多个候选时，可查看别名、HINT 或扩展条件的差异，并在详情中切换候选。

指令详情可查看编码变体、16 / 32 位布局、操作数、执行条件与规范来源。编码匹配表示符合已收录的编码规则；扩展与运行状态要求可在详情中查看。

伪指令参考依据固定版本的 ISA Manual 整理，提供中英文摘要、条件化展开及基础指令跳转。`li`、`la` 等手册未定义完整展开的名称保留来源说明；目前支持查阅，不接受完整汇编语句或生成机器码。

## 功能预览

<table>
  <tr>
    <th>寄存器位布局</th>
    <th>字段说明与取值</th>
    <th>筛选面板</th>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/images/bit-layout.png" alt="mstatus 位布局，显示字段位置、位宽和 WPRI 区域" height="420"></td>
    <td align="center" valign="top"><img src="docs/images/field-details.png" alt="MIE 字段的中文说明、访问规则和 0/1 取值含义" height="420"></td>
    <td align="center" valign="top"><img src="docs/images/filters.png" alt="按架构位宽、访问权限、ISA 扩展和数据来源筛选" height="420"></td>
  </tr>
</table>

## 运行要求

| 项目 | 要求 |
| --- | --- |
| 系统版本 | Android 8.0 及以上（API 26） |
| 支持架构 | RISC-V RV32 / RV64 |
| 网络连接 | 日常查询与本地包导入无需联网；软件／数据库更新及打开外部规范原文时需要联网 |

## 开发计划

后续功能与开发进度可在 [ISAtlas Roadmap](https://github.com/users/noionion/projects/5) 公开查看。功能建议与问题反馈请通过 [Issues](https://github.com/noionion/ISAtlas/issues) 提交。

## 文档

| 文档 | 说明 |
| --- | --- |
| [构建指南](docs/build.md) | 从源码构建、安装与开发验证 |
| [看板维护规则](docs/project-maintenance.md) | 功能规划、任务清单、状态流转与 Issue 协作 |
| [独立数据仓库](https://github.com/noionion/ISAtlas-data) | CSR、指令与字段数据、来源和审核 |
| [数据维护](docs/data-maintenance.md) | 本地候选包联调与内置基线同步 |
| [数据库更新](docs/database-updates.md) | 独立版本、后台下载、校验与自动应用 |
| [本地数据包](docs/local-packages.md) | 导入与管理自定义 CSR / 指令包 |
| [覆盖范围](docs/field-review.md) | CSR 与字段的收录范围及复核说明 |
| [数据格式](docs/data-format.md) | CSR JSON 结构与字段约定 |
| [指令数据](docs/instruction-data.md) | 指令编码、双语摘要、提取与审核方式 |
| [伪指令与别名清单](docs/pseudoinstruction-coverage.md) | 已收录名称、展开参考与来源边界 |

## 反馈与致谢

欢迎通过 [Issues](https://github.com/noionion/ISAtlas/issues) 反馈问题或提出建议。数据问题请附上指令名、机器码或 CSR / 字段名称，以及规范出处，界面问题请附上复现步骤。

感谢 [RISC-V ISA Manual](https://github.com/riscv/riscv-isa-manual)、[AIA](https://github.com/riscv/riscv-aia)、[Debug Specification](https://github.com/riscv/riscv-debug-spec) 与 [SPMP](https://github.com/riscv/riscv-spmp) 提供规范资料，以及 [T-head Extension Specification](https://github.com/T-head-Semi/thead-extension-spec) 提供公开扩展示例。第三方数据的署名与许可证见[第三方声明](app/src/main/assets/THIRD_PARTY_NOTICES.txt)及应用的数据源与许可页。
