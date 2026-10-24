<div align="center">

<img src="docs/images/icon.svg" alt="ISAtlas 应用图标" width="112" height="112">

# ISAtlas

**指令、寄存器与位字段，一搜即得**

Android 上的离线架构速查工具，支持 RISC-V CSR 与字段查询。

指令查询计划于 1.0.0 版本前支持。

[功能预览](#功能预览) · [开发看板](https://github.com/users/noionion/projects/5) · [使用文档](#文档) · [问题反馈](https://github.com/noionion/ISAtlas/issues)

</div>

---

输入寄存器名称、地址或字段名，快速查看位布局、功能说明和取值含义。适合阅读底层代码、调试系统，以及学习 RISC-V 架构时随手查阅。

## 特性

| | 功能 | 说明 |
| --- | --- | --- |
| 🔎 | 快速搜索 | 搜索名称、地址、字段和描述，支持 `mstatus`、`0x300`、`mstatus.MIE` 等查询 |
| 🧩 | 位域一目了然 | 图形化展示寄存器布局，点击或拖拽选择字段，查看对应功能 |
| 📖 | 取值有据可查 | 中文功能摘要、枚举值与访问规则，支持跳转到规范原文 |
| 🎛️ | 按需筛选 | 按 RV32 / RV64、访问权限、扩展和数据来源筛选 |
| 📱 | 横竖屏适配 | 横屏支持多列结果与更宽位图，返回列表保留浏览位置 |
| 📦 | 离线使用 | 数据随应用提供，日常查询无需联网 |

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
| 网络连接 | 离线查询无需联网；打开外部规范原文时需要联网 |

## 开发计划

后续功能与开发进度可在 [ISAtlas Roadmap](https://github.com/users/noionion/projects/5) 公开查看。功能建议与问题反馈请通过 [Issues](https://github.com/noionion/ISAtlas/issues) 提交。

## 文档

| 文档 | 说明 |
| --- | --- |
| [构建指南](docs/build.md) | 从源码构建、安装与开发验证 |
| [看板维护规则](docs/project-maintenance.md) | 功能规划、任务清单、状态流转与 Issue 协作 |
| [数据来源](docs/data-import.md) | 规范版本、数据维护与转换方式 |
| [覆盖范围](docs/field-review.md) | CSR 与字段的收录范围及复核说明 |
| [数据格式](docs/data-format.md) | CSR JSON 结构与字段约定 |

## 反馈与致谢

欢迎通过 [Issues](https://github.com/noionion/ISAtlas/issues) 反馈问题或提出建议。数据问题请附上 CSR / 字段名称及规范出处，界面问题请附上复现步骤。

感谢 [RISC-V ISA Manual](https://github.com/riscv/riscv-isa-manual) 与 [T-head Extension Specification](https://github.com/T-head-Semi/thead-extension-spec) 提供规范资料。第三方数据的署名与许可证见 [第三方声明](app/src/main/assets/THIRD_PARTY_NOTICES.txt)。
