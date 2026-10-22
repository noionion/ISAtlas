# 离线 CSR 数据格式

当前数据包版本为 0.5.0；应用版本为 0.2.0，两者独立。schemaVersion 仍为 1，新增可选属性带有默认值。

## 文件结构

`app/src/main/assets/riscv-catalog.json` 保存包元数据和 `files` 数组；数组中的每个文件名对应 `csr/` 下一个完整的 RegisterDefinition。文件名为小写的 `{namespace}-{name}.json`，例如 `manual-mstatus.json`。RV32/RV64 布局在同一文件中保存。

应用通过 Catalog.decodeFiles 读取索引，合并为 DataPack 后校验。拒绝重复文件引用、不安全路径、重复稳定身份、位段重叠和越界编码。稳定身份为 architecture / namespace / csr / name；不同来源的同址 CSR 并存，不按地址去重。tools/csr_storage.py 对同 id、相同内容去重；同 id、不同内容报错，并保留原数据。

数据指纹为 SHA-256：先加入索引原始字节，再按 files 顺序追加文件名 UTF-8、NUL 和对应 JSON 原始字节。

## 包与寄存器

包包含 schemaVersion、id、architecture、namespace、version、title、coverage、source；存储索引用 files，内存 DataPack 用 registers。当前 architecture 仅支持 riscv。source 保存 title、revision 和 url。

寄存器保存 id、namespace、name、title、summary、summaryEn、locator、privilege、access、xlens、condition、extensions、notes、sourceUrl、sourceRevision、license、coverage、fields 和 regions。locator 为 type=riscv-csr 与数值 address。字段声明的 XLEN 必须属于寄存器适用范围。

coverage=fields 表示有已适配布局，可能包含手册明确的局部图；coverage=address-only 时 fields 和 regions 必须为空。未收录位段不得自动视为 WPRI。

## 字段与保留区域

字段包含 name、summary、summaryEn、access、layouts，以及可选 values、valuesEn、valuesByXlen、valuesEnByXlen、condition、aliases、typeRule、sourceUrl。每个布局为 xlen、msb、lsb，端点均包含在内。别名用于查询，界面显示 canonical 名称。

summary 为默认中文的一句话功能说明，summaryEn 为英文摘要；较长约束放在 typeRule。sourceUrl 指向固定提交的手册正文或位图。condition 和 typeRule 仅作说明，不是可执行的扩展或实现参数求解器。

values / valuesEn 是通用中英文编码表，编码键为无符号十进制字符串。valuesByXlen / valuesEnByXlen 以 JSON 键 "32"、"64" 保存特定位宽编码表。界面优先使用当前 XLEN 的映射，没有时回退通用表。例如 satp.MODE 在 RV32 为 0=Bare、1=Sv32；RV64 为 0=Bare、8=Sv39、9=Sv48、10=Sv57 及保留编码。

已提供的英文映射必须与中文编码键一致；位宽映射只能引用存在的字段布局，编码不得超出位宽。有限模式显式记录保留编码；地址、计数值、实现定义编码和独立位掩码通过摘要及规则说明，不伪造为有限枚举。cause.CODE 的同一编号分别解释 INT=0/1 的含义。

regions 保存非命名区域，每项包含 label、access、summary、summaryEn、layouts 和可选 sourceUrl。WPRI、WARL、WLRL、只读零和 Reserved 各按规范语义保留，不能互相替代。区域与命名字段不可重叠。

## 兼容性与范围

summaryEn、英文编码表、按位宽编码表及字段/区域 sourceUrl 均为可选属性，兼容保留的厂商条目。当前界面显示中文，英文摘要参与搜索，为后续 i18n 留存。

当前目录包含 381 个 manual CSR（1,570 个字段）和 1 个 thead CSR（1 个字段）。具体来源与边界见 data-import.md 和 field-review.md。未来的条件互斥布局、其他架构及指令定义需要扩展对应模型、解析、搜索和显示能力，不能直接复用 CSR 编号语义。
