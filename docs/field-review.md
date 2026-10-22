# CSR 字段说明补齐与复核

日期：2026-10-23。来源固定为 `riscv/riscv-isa-manual` 发布标签 `20250508`、提交 `584390d9d872835b1bc45ab9ea946b56db3ad485`。

## 完成范围

当前目录内 381 个 manual 来源 CSR：370 个具有已适配布局，11 个仅地址索引。已录入的 **1,570 个字段全部具有中文 summary 和英文 summaryEn**，缺失描述为 0；其中 **1,327 个字段有枚举说明**。保留的 T-head CSR 不属于本轮手册转换，其 JSON 的 SHA-256 与转换前一致。总包为 382 个 CSR、1,571 个字段。

中文说明默认显示；英文说明和英文枚举保存在数据模型中，供后续 i18n 使用。功能说明优先一句话；较长约束放在 typeRule。数值、地址、计数值和位掩码按实际语义说明，不伪造为有限模式枚举。有限模式列出所有编码，保留编码也标明。cause.CODE 同一编号按 INT=0/1 分别说明；H 等扩展专属原因附带条件。

## 复核内容与修正

- 对输出的每个字段检查：中英文非空、摘要长度、枚举两种语言编码集合一致、枚举不超字段宽度、逐 XLEN 枚举仅用于实际存在的布局，以及来源路径和可定位的手册文字证据。
- 对所有布局检查有效位范围、重叠、CSR 稳定身份重复及未覆盖区间。保留域区分 WPRI、WARL、RO 零和 Reserved，不根据缺少数据推断 WPRI。
- 对照正文和编码表复核状态、地址转换、浮点、向量、PMP、中断、state-enable、熵源、指针屏蔽和计数器家族的说明；重复家族按索引展开并逐条执行一致性检查。
- 浮点 frm 的 7 是保留编码；不能套用指令 rm=7 的动态舍入含义。
- RV32/RV64 的 satp/vsatp/hgatp 模式和指针屏蔽编码分别保存；RV32 高半 CSR 的字段使用实际高半部语义。
- mhpmevent 的事件编码在 RV32 需要合并高低半部；单个半部为零不等于整个事件编码为零。Sscofpmf 的 OF、各模式 INH 及 WPRI 区域独立保存。
- mip.MEIP/MTIP/MSIP、hip.SGEIP/VSEIP/VSTIP 为只读；hvip 注入位及 hip.VSSIP 的可写别名分别说明。mip.SEIP 的软件位与硬件信号 OR、STIP 的 Sstc 条件写入规则保留在访问规则中。
- PMP 的权限枚举明确为基础 PMP 解释，并保留 R/W/X 组合约束、锁定联动及 Smepmp 的重新解释；不会把组合 WARL 当成无约束的独立开关。
- vsstatus 按 VS/VU 上下文说明，不使用无功能信息的统一占位摘要；GVA/MPV、mtinst/mtval2 等来源指向实际定义它们的 Hypervisor 章节。
- pmpcfg 的偶数编号同时适用于 RV32/RV64；奇数编号仅 RV32。导入编号表的省略族时不传播奇数条目的 RV32-only 限制。

逐字段证据、摘要和布局见 `data/field-review.json`；生成报告见 `data/manual-import-report.json`。文字证据定位属于可追溯性检查，不能单独证明翻译语义正确，因此同时进行了上述编码表和家族语义复核。

## 明确保留的边界

11 个仅地址条目为 `dcsr`、`dpc`、`dscratch0`、`dscratch1`、`hcontext`、`mcontext`、`scontext`、`tdata1`、`tdata2`、`tdata3`、`tselect`。本手册提供地址或说明其访问关系，具体调试/触发器定义属于外部 Debug/Sdtrig 规范；本轮不从其他来源补造字段。相关说明可查 `src/priv-intro.adoc` 的独立 Debug 规范引用、`src/priv-csrs.adoc` 编号表以及 `src/smstateen.adoc` 的 CONTEXT 说明。

mie/mip、sie/sip、hie/hip/hvip、vsie/vsip 的已导入标准图只画 bit 15:0。高于 bit 15 的平台中断布局继续明确留空，不统一标为 WPRI。具体清单记录在复核 JSON 的 partialPlatformLayouts 中。

本次完整补齐的是当前目录中可由该手册定义说明的字段，不宣称已提取仓库所有扩展章节中的全部 CSR，也不是 ISA 配置合法性求解器。

## 复现

```text
python tools/import_isa_manual.py
python tools/review_manual_fields.py
python -m unittest discover -s tools -p "test_*csr*.py"
```

字段语义适配位于 `tools/manual_field_semantics.py`；独立输出检查位于 `tools/review_manual_fields.py`。CSR JSON、来源锁定与转换工具一并版本控制；生成的 JSON 复核报告、临时截图和构建缓存保持忽略。

## English summary

All 1,570 fields in the currently indexed ISA-manual CSR catalog now have concise Chinese and English summaries. Of these, 1,327 fields include localized encoding descriptions. The application displays Chinese by default and retains English data for future localization. XLEN-specific encodings, interrupt/exception cause contexts, reserved encodings, access restrictions, and RV32 high-half semantics are preserved. Every emitted record was checked for completeness, enum consistency, bit-range validity, overlap, and source traceability, alongside a review of the relevant architectural tables and repeated register families. Eleven debug-related CSRs remain address-only because their field definitions belong to an external specification. Platform interrupt regions outside the imported standard diagrams remain explicitly unexpanded. The retained vendor record is unchanged.
