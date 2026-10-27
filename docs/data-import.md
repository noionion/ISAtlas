# 官方 ISA 手册数据接入

基础来源为 https://github.com/riscv/riscv-isa-manual ，通过 upstream/riscv-isa-manual submodule 固定到发布标签 20250508、commit 584390d9d872835b1bc45ab9ea946b56db3ad485。上游采用 CC-BY-4.0，逐条数据保留固定版本来源，应用中附带署名、修改说明和许可证全文。

## 结构调查与转换

本版本没有统一的 CSR JSON/YAML schema。src/priv-csrs.adoc 含多行 AsciiDoc CSR 编号表，寄存器族以省略号表示；字段分布在正文、外部 WaveDrom .edn 和 Bytefield .edn 中。Bytefield 的 span 是显示比例，不是实际位宽，必须读取独立的数字/XLEN 位宽标注。导入器只解析数据，不运行上游 EDN、AsciiDoc 宏或脚本。

基础转换提取 377 条主编号表记录，按 Sstc 正文补入 4 条定时比较 CSR，共 381 条手册来源（manual）、1,570 个字段。其中 370 条有已适配布局，11 条仅地址索引；不宣称已覆盖所有扩展章节。AIA 接入后新增 32 条 CSR 并补充原有字段；随后 Debug 接入新增 3 条并补齐相关布局。SPMP 接入后当前合计 430 条 CSR、2,008 个字段定义（含互斥触发器与间接窗口格式）；原有 1 条 thead 数据及其许可证保持不变。逐项复核与边界见 field-review.md。

已从生产 csr/ 目录和索引删除 460 条 standard、91 条 andes；旧测试样本由手册生成样本替换，旧导入入口转发至官方手册适配器，不会再导入 UDB、opcodes 或 Andes 数据。上游旧缓存不参与生成。

## 保留域与访问规则

显式 WPRI 位图直接转为 regions；显式 WARL/WLRL 保留相应访问类型。正文明确的规则补充位图，例如 misa.MXL 根据正文修正为 RO、mstatus 的 xPP/SXL/UXL/MDT 根据正文标 WARL，SD 标 RO。fcsr 保留域依据正文的写忽略/读零/软件保留语义标 WPRI；窄 CSR 扩展读取的高位标 RO 零。Vector 位表明确的保留/写零区域单独标记，未将所有 Reserved 或未解析区域一律改成 WPRI。

未适配的字段布局继续标记仅地址，未明确的字段访问规则链接至原文。包含图示局部定义或平台相关区域的 CSR 不声称所有 bit 均已描述；补充其他规范时必须明确记录来源，不能无依据填充。

## 再生成

### AIA 补充

`upstream/riscv-aia` submodule 固定到标签 `20250312`，commit `20318d1b03f0ac8253458b82f8244d0d0db074e9`，许可 CC-BY-4.0。`CSRs.adoc` 的三张编号表共 41 个新增或扩宽条目，其中 9 个复用现有 manual 身份，32 个作为 aia 新条目保存；另补充 cause、state-enable、guest interrupt file 关联，共修订 19 个原有寄存器。接入时合计 414 个 CSR、1,773 个字段，数据库版本 0.5.1。

`tools/import_aia.py` 根据固定版本正文和位表整理 TOPI/TOPEI、虚拟注入、优先级、选择窗口和 RV32 高半布局。`tools/review_aia.py` 独立检查 51 个受影响条目的 294 个字段，逐 XLEN 检查完整覆盖、无重叠、枚举及中英文摘要，核对所有 14 个高半 CSR 和固定提交证据。普通手册再生成也调用同一 AIA 补充步骤；单独补充与完整再生成的数据库指纹一致。

IMSIC 的 `eidelivery`、`eithreshold`、`eip*`、`eie*` 通过窗口条目的选择号、摘要和规则说明保存；`iprio*` 同理，不将其 selector 伪装为 CSR 编号。APLIC 的内存映射寄存器不属于本次直接 CSR 目录。受实现参数影响的 WARL/WLRL 和读零位按正文标注，未知位不推断为 WPRI。

### Debug / trigger / trace 动作补充

`upstream/riscv-debug-spec` 固定到正式批准的 `1.0` 标签，commit `5695c0aaa7a3c228e14702e1268eb5a5e50e3cb8`。选择 CC-BY-4.0 许可使用；XML 另外提供 BSD-2-Clause 选项，未采用该选项。只解析 `xml/core_registers.xml` 与 `xml/hwbp_registers.xml`，不运行上游生成脚本、构建或 XML 外部实体。

规范包含 14 个不同 hart CSR 地址。新增 `tinfo`、`tcontrol`、`mscontext`，补全原来仅地址的 11 个 manual 条目，并修订 4 个 state-enable 寄存器的 CONTEXT 说明。现有 manual 身份及主来源保留，字段和补充来源指向 Debug 1.0。接入时数据库更新为 0.5.2，共 417 个 CSR、1,885 个字段定义，包含互斥视图。

TDATA1 保存通用格式及 `mcontrol`、`mcontrol6`、`icount`、`itrigger`、`etrigger`、`tmexttrigger` 六种格式；TDATA3 保存通用格式及 RV32/RV64 的 textra 上下文格式。它们复用原 CSR 地址，字段搜索可定位对应格式。SIZEHI/SIZELO 和不连续的 HIT1/HIT0 保留为物理字段，组合值说明存放在 typeRule，避免将超宽枚举塞进窄字段。

记录 ACTION 的 breakpoint、Debug Mode、trace on/off/notify、外部信号及保留编码；ACTION=1 要求 DMODE=1 且实现 Sdext。独立 trace 编码器/控制寄存器不属于该仓库的 hart CSR 定义，DM/DMI、JTAG 和虚拟调试器寄存器也不按 CSR 导入。`mscontext` 是旧版兼容别名，保留其 S/HS 可访问但编码不遵循通常地址映射的特例。

`tmexttrigger.TYPE` 的 XML reset=4 与正文及 TDATA1 类型表的 type=7 存在差异，按正文展示格式 7，并在字段 typeRule 与复核报告中保留差异。DCSR 位表仅定义 31:0；RV64 上方区域明确标为本版未规定，不推断为 WPRI 或只读零。

### Sspmp / Sspmpen / Smpmpdeleg 补充

`upstream/riscv-spmp` 固定批准的 `v1.0`，commit `4ce228207193c55247200110e3d5b6881a17c367`；以 `sspmp/header.adoc` 的 Ratified 状态为准，仓库 readme 中的 working draft 字样未同步。许可 CC-BY-4.0。仅解析 AsciiDoc/Bytefield 和 SVG 证据，不执行上游构建或依赖代码。

新增三个明确编号的 CSR：`mpmpdeleg` (0x316)、`spmpen` (0x183)、RV32 `spmpenh` (0x193)。另从现有 ISA manual 的 `indirect-csr.adoc` 补入 10 个 `mireg2–6` / `sireg2–6` 直接窗口，主身份和来源为 manual；编号不是连续的，必须跳过 x54。其余 90 个已有条目补充 SPMP 来源或规则，保留稳定身份。数据库版本为 0.5.3，共 430 个 CSR、2,008 个字段定义，应用版本保持 0.5.0。

`spmpaddr0–63` / `spmpcfg0–63` 没有独立 CSR 编号。selector 0x100+i 通过 `sireg/mireg` 访问地址、`sireg2/mireg2` 访问配置；这两种布局以 `spmpaddr` / `spmpcfg` view 保存。越界实际条目读零写忽略；ireg3–6 在此范围保留，不能概括为其他 selector 范围也保留。M 侧通过 miselect 才能清除 SPMP 的 L。保留原有 AIA selector 和通用格式；字段别名允许搜索条目家族名称。

逐项录入 WARL/WPRI、地址位 PA[33:2]/PA[55:2]、共享权限表（RWX=110 时共享 U 仅读，111 时共享 U 仅执行）、U/SUM 条件、锁定 TOR 联动、PMP 委托边界与重新分配、使能位锁定和内存管理 fence。仅补充 INT=0 的 SPMP 错误原因，同号 AIA 中断不变。SPMP 与 satp 分页互斥；Shbare 相关 hgatp 条件按正文记录。

`mpmpdeleg` RV32 图将 PMP 边界放在 bits 6:2、低两位为 `0 (WARL)`，RV64 则为 bits 6:0；正文没有单独解释粒度差异。保留原图并在规则/复核报告注明，不能根据 Bytefield 的视觉 span 猜位宽。spmpen 超出实现条目的位只明确写忽略，不推定读零。

`spmp-for-hyp/header.adoc` 仍为 0.2 development；`vspmpen[h]`、`hspmpdeleg`、`hspmpen[h]` 未分配具体 CSR 编号，暂不作为直接 CSR 导入；也不把草案 selector 的 `#0` 猜成 0x100。草案保留在固定 submodule 以供后续适配。当前 GCC 支持列表尚无 sspmp/sspmpen/smpmpdeleg，不扩大编译器 ISA 词表；可通过 SPMP 数据来源或现有 Sscsrind/Smcsrind 关联窗口筛选，实际扩展名在数据中保留。

`tools/review_spmp.py` 复核全部 103 个受影响条目的 721 个字段，逐格式、逐 XLEN 检查覆盖、互斥、枚举和中英文说明；独立核对直接编号、间接窗口编号、关键图表范围、锁定条件和来源 SHA-256。报告位于被忽略的 `docs/data/spmp-review.json`。

```text
git submodule update --init upstream/riscv-isa-manual upstream/riscv-aia upstream/riscv-debug-spec upstream/riscv-spmp
python tools/import_isa_manual.py
python tools/review_manual_fields.py
python tools/review_aia.py
python tools/review_debug.py
python tools/review_spmp.py
python -m unittest discover -s tools -p "test_import_debug.py"
python -m unittest discover -s tools -p "test_import_aia.py"
python -m unittest discover -s tools -p "test_*.py"
```

只使用 Python 3.10+ 标准库。脚本校验上游 commit 和工作区未修改，输出 assets/csr/*.json、riscv-catalog.json 索引、许可证、手册样本及 docs/data/manual-import-report.json。重复生成按统一多文件指纹校验。CSR JSON、来源锁定、许可证和转换工具纳入版本控制；生成的复核报告与临时截图保持忽略。
