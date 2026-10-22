# 官方 ISA 手册数据接入

当前来源为 https://github.com/riscv/riscv-isa-manual ，通过 upstream/riscv-isa-manual submodule 固定到发布标签 20250508、commit 584390d9d872835b1bc45ab9ea946b56db3ad485。上游采用 CC-BY-4.0，逐条数据保留固定版本来源，应用中附带署名、修改说明和许可证全文。

## 结构调查与转换

本版本没有统一的 CSR JSON/YAML schema。src/priv-csrs.adoc 含多行 AsciiDoc CSR 编号表，寄存器族以省略号表示；字段分布在正文、外部 WaveDrom .edn 和 Bytefield .edn 中。Bytefield 的 span 是显示比例，不是实际位宽，必须读取独立的数字/XLEN 位宽标注。导入器只解析数据，不运行上游 EDN、AsciiDoc 宏或脚本。

当前提取 377 条主编号表记录，按 Sstc 正文补入 4 条定时比较 CSR，共 381 条手册来源（manual），1,570 个字段。其中 370 条有已适配布局，11 条仅地址索引；不宣称已覆盖所有扩展章节。1,570 个字段均有中英文摘要，1,327 个有枚举说明。原有 1 条 thead 数据及其许可证保持不变，合计 382 条、1,571 个字段。逐项复核与边界见 field-review.md。

已从生产 csr/ 目录和索引删除 460 条 standard、91 条 andes；旧测试样本由手册生成样本替换，旧导入入口转发至官方手册适配器，不会再导入 UDB、opcodes 或 Andes 数据。上游旧缓存不参与生成。

## 保留域与访问规则

显式 WPRI 位图直接转为 regions；显式 WARL/WLRL 保留相应访问类型。正文明确的规则补充位图，例如 misa.MXL 根据正文修正为 RO、mstatus 的 xPP/SXL/UXL/MDT 根据正文标 WARL，SD 标 RO。fcsr 保留域依据正文的写忽略/读零/软件保留语义标 WPRI；窄 CSR 扩展读取的高位标 RO 零。Vector 位表明确的保留/写零区域单独标记，未将所有 Reserved 或未解析区域一律改成 WPRI。

未适配的字段布局继续标记仅地址，未明确的字段访问规则链接至原文。包含图示局部定义或平台相关区域的 CSR 不声称所有 bit 均已描述；这些缺口不能由其他来源的数据填充。

## 再生成

```text
git submodule update --init upstream/riscv-isa-manual
python tools/import_isa_manual.py
python tools/review_manual_fields.py
python -m unittest discover -s tools -p "test_*csr*.py"
```

只使用 Python 3.10+ 标准库。脚本校验上游 commit 和工作区未修改，输出 assets/csr/*.json、riscv-catalog.json 索引、许可证、手册样本及 docs/data/manual-import-report.json。重复生成按统一多文件指纹校验。CSR JSON、来源锁定、许可证和转换工具纳入版本控制；生成的复核报告与临时截图保持忽略。
