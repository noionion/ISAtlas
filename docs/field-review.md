# CSR 字段说明补齐与复核

基础复核日期：2026-10-23；AIA / Debug / SPMP 补充复核：2026-10-27。基础来源固定为 `riscv/riscv-isa-manual` 标签 `20250508`、提交 `584390d9d872835b1bc45ab9ea946b56db3ad485`；AIA 固定版本见下文。

## 完成范围

当前目录内 391 个 manual 来源 CSR 均具有已适配布局。经 AIA、Debug 和 SPMP 补充后，已录入的 **1,766 个 manual 字段全部具有中文 summary 和英文 summaryEn**，缺失描述为 0。另有 32 个 aia CSR、139 个字段，3 个 debug CSR、5 个字段，3 个 spmp CSR、97 个字段，同样具有中英文摘要。保留的 T-head CSR 及其 JSON 的 SHA-256 与转换前一致。总包为 430 个 CSR、2,008 个字段定义，包含互斥触发器和间接窗口格式。

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

原来仅有地址的 `dcsr`、`dpc`、`dscratch0`、`dscratch1`、`hcontext`、`mcontext`、`scontext`、`tdata1`、`tdata2`、`tdata3`、`tselect` 现已依据 Debug 1.0 补齐。基础手册的地址身份与主来源保留，新增字段按固定版本 Debug XML 定位，不将其冒充为基础手册的字段定义。

AIA 补充后，mie/mip、sie/sip、hvip、vsie/vsip 已按 AIA 增加标准本地与自定义中断范围，以及 RAS 原因 35/43。hie/hip 的基础图仍只画 bit 15:0，不能因 AIA 扩宽其他寄存器而推断它们也有对应高半 CSR。相关缺口继续留空，不统一标为 WPRI。

## AIA 补充复核

接入 AIA 20250312 时，新增 32 个直接 CSR，补充 19 个现有寄存器；总包为 414 个 CSR、1,773 个字段。manual 身份下现有 1,635 个字段，新增 aia 身份下 137 个字段，另有 1 个保留厂商字段。AIA 审核覆盖 51 个条目的 294 个字段，包含全部 14 个 RV32 高半 CSR。`data/aia-review.json` 保存逐段来源、行号及 SHA-256；生成报告和截图继续保持忽略。

重点检查：TOPI 的 IID 为 27:16、IPRIO 为 7:0；TOPEI 的身份为 26:16、优先级为 10:0，写入值忽略且写操作领取当前报告中断；hviprio1 的中断 13 位于 47:40，因此其 RV32 高半为 15:8；mvien/hvien 的虚拟可见控制不等同于普通中断使能；sip.SEIP 与 mip.SEIP 的可写性取决于 AIA 虚拟注入路径；state-enable 禁止 IMSIC 访问不意味着强制 hgeip/hgeie/VGEIN 读零。

## Debug / Sdtrig 补充复核

Debug 1.0 固定到 `5695c0aaa7a3c228e14702e1268eb5a5e50e3cb8`。复核 14 个直接 hart CSR 与 4 个 state-enable 补充条目的 150 个字段定义，逐个格式、逐个 XLEN 对照 XML 的字段范围及显式只读零位。补入 3 个新 CSR，补充 15 个现有 CSR；不同 TDATA 格式不形成重复 CSR 地址。`data/debug-review.json` 保存来源段落、SHA-256、边界及原文差异。

关键检查包括：DCSR 的调试原因、Smdbltrp/Zicfilp/H 条件；DPC 恢复地址；DMODE 对调试动作的限制；SCONTEXT/HCONTEXT 别名及 CONTEXT 访问控制；TYPE=2 的 SIZEHI/SIZELO 组合大小；TYPE=6 不连续 HIT1/HIT0 的组合状态；RV32 不存在 SIZEHI；textra 的 RV32/RV64 不同字段范围；trace 动作及保留编码。

上游 `tmexttrigger.TYPE` 的 XML reset=4 与正文/类型表的 TYPE=7 不一致，格式按正文选择 7，并保留差异说明。DCSR 位表没有定义 RV64 bits 63:32，明确标为本版未规定，不推定为 WPRI/RO0。DM/DMI、JTAG、调试器虚拟寄存器和独立 Trace 规范的控制寄存器不纳入 hart CSR 编号空间。

本次完整补齐的是当前目录中可由该手册定义说明的字段，不宣称已提取仓库所有扩展章节中的全部 CSR，也不是 ISA 配置合法性求解器。

## SPMP 补充复核

固定批准的 v1.0、提交 `4ce228207193c55247200110e3d5b6881a17c367`。新增三个 SPMP CSR 及十个来自 manual `indirect-csr.adoc` 的间接窗口，补充 90 个已有寄存器。独立检查全部 103 个受影响条目、721 个字段：直接编号、selector 空间分离、RV32/RV64、间接配置的 U/SHARED/L/A/R/W/X、WPRI、高位零、WARL 与组合权限。AIA 及 Debug 的原有数据继续复核；AIA 所关联条目现含 296 个字段，新增的两个字段为 SPMP 地址视图。

特别核对共享 RWX=110 时 U 仅读、RWX=111 时 U 仅执行；SHARED=1/U=0 和 RWX=010/011 保留。锁定 SPMP 与锁定 PMP 对重新分配的约束不同；spmpen 不决定配置可写性，TOR 下界不受前一项使能位影响。对 mstatus/sstatus、satp/hgatp、cause 的补充均限定为 SPMP/Shbare 条件。

保留 RV32 mpmpdeleg 图的 PMP 边界 bits 6:2 和低两位 0 (WARL)，注明正文没有解释粒度差异。虚拟化 0.2 草案的未编号寄存器不猜地址；SPMP 间接条目不生成独立 CSR。spmpen 未实现位不猜读回值。中文和英文摘要/枚举全部非空，生成报告与来源段落哈希保存在被忽略的 `data/spmp-review.json`。

## 复现

```text
python tools/import_isa_manual.py
python tools/review_manual_fields.py
python tools/review_aia.py
python tools/review_debug.py
python tools/review_spmp.py
python -m unittest discover -s tools -p "test_*.py"
```

字段语义适配位于 `tools/manual_field_semantics.py`；独立输出检查位于 `tools/review_manual_fields.py`。CSR JSON、来源锁定与转换工具一并版本控制；生成的 JSON 复核报告、临时截图和构建缓存保持忽略。

## English summary

The current catalog contains 430 CSRs and 2,008 field definitions, including mutually exclusive trigger formats. All 1,766 fields under preserved ISA-manual identities, 139 fields under AIA identities, five new Debug fields, 97 SPMP fields, and the retained vendor field have concise Chinese and English summaries. Finite encodings include localized value descriptions. The application displays Chinese by default and retains English data for future localization.

AIA adds 32 CSRs and supplements 19 existing entries. Debug 1.0 adds three CSRs and supplements 15 existing entries, completing the eleven formerly address-only debug/trigger registers. Reviews check every format and XLEN for bit ranges, overlaps, source evidence, and explicit read-only-zero regions. Composite SIZE and HIT encodings preserve their physical fields and document combined meanings. The upstream tmexttrigger TYPE reset discrepancy is recorded; its explicit prose and type table define type 7. Unspecified upper DCSR bits and platform interrupt regions remain explicitly unexpanded. Trace trigger actions are covered; independent trace encoder/control registers and non-CSR debug address spaces are excluded. The retained vendor record is unchanged.

Ratified SPMP 1.0 adds three numbered CSRs and ten indirect-access windows defined by the pinned ISA manual, while supplementing 90 existing entries. All 103 affected entries and 721 fields are audited. Indirect SPMP entries are represented as window views, preserving selector/CSR namespace separation. Shared-region permissions, delegation/locking, enable aliases, SUM/Bare conditions and fault codes are recorded. The RV32 mpmpdeleg diagram/prose granularity discrepancy is disclosed; unnumbered hypervisor 0.2 draft CSRs are excluded.
