# 伪指令与别名覆盖清单

依据固定的 [RISC-V ISA Manual 20250508](https://github.com/riscv/riscv-isa-manual/tree/584390d9d872835b1bc45ab9ea946b56db3ad485)。本清单表示该版本手册中已审核的命名形式，不等同于 GNU / LLVM 等汇编器的全部伪指令。

共审核 105 个名称：88 个带编码别名、8 个展开参考、9 个仅名称参考。全部 72 个 AsciiDoc 源文件的 77 处相关声明／提及及 980 行汇编形态示例均已归类；复现命令见[数据审核说明](instruction-data.md)。

## 名称清单

### 整数与分支

`j`、`jr`、`mv`、`nop`、`not`、`ret`、`seqz`、`sext.w`、`snez`、`zext.w`、`bgt`、`bgtu`、`ble`、`bleu`、`neg`、`beqz`、`bnez`、`scall`、`sbreak`、`c.sext.w`。

### 计数器读取

`rdcycle`、`rdcycleh`、`rdtime`、`rdtimeh`、`rdinstret`、`rdinstreth`。

### CSR 与浮点控制寄存器

`csrr`、`csrw`、`csrwi`、`csrs`、`csrc`、`csrsi`、`csrci`、`frcsr`、`fscsr`、`frrm`、`fsrm`、`frflags`、`fsflags`。

### 标量浮点

`fmv.s`、`fneg.s`、`fabs.s`、`fmv.d`、`fneg.d`、`fabs.d`、`fmv.q`、`fneg.q`、`fabs.q`、`fmv.h`、`fneg.h`、`fabs.h`、`fmv.x.s`、`fmv.s.x`。

### 向量

`vneg.v`、`vnot.v`、`vwcvt.x.x.v`、`vwcvtu.x.x.v`、`vncvt.x.x.w`、`vfneg.v`、`vfabs.v`、`vmmv.m`、`vmclr.m`、`vmset.m`、`vmnot.m`、`vmsgt.vv`、`vmsgtu.vv`、`vmsge.vv`、`vmsgeu.vv`、`vmfgt.vv`、`vmfge.vv`、`vl1r.v`、`vl2r.v`、`vl4r.v`、`vl8r.v`。

### 向量兼容名称

`vle1.v`、`vse1.v`、`vfrsqrte7.v`、`vfrece7.v`、`vfredsum.vs`、`vfwredsum.vs`、`vmandnot.mm`、`vmornot.mm`、`vpopc.m`、`vmcpy.m`、`vsxei8.v`、`vsxei16.v`、`vsxei32.v`、`vsxei64.v`。

### 展开参考

`call`、`tail`、`vmsge.vx`、`vmsgeu.vx`、`vmslt.vi`、`vmsltu.vi`、`vmsge.vi`、`vmsgeu.vi`。

### 仅名称参考

`li`、`la`、`rev`、`mftx.s`、`mftx.d`、`mxtf.s`、`mxtf.d`、`mffsr`、`mtfsr`。

## 数据边界

- 单指令别名保留基础指令关系、所需扩展、固定值与操作数等式；反查同时显示实际匹配的基础指令。
- 展开参考共 18 种序列：call / tail 各 2 种，vmsge.vx / vmsgeu.vx 各 5 种，4 个立即数比较名称各 1 种符号变换。必须遵守详情中的条件，不以示例代替完整汇编器规则。
- li / la 只在手册示例中使用，未定义任意常量／地址的通用展开。rev 及旧浮点名称保留历史说明，不推定当前机器码。
- mtinst / htinst 中的特殊伪指令记录值不属于可执行的汇编别名。
- 未从其他汇编器手册补入本固定来源未定义的通用展开规则；例如 sext.b 已作为 Zbb 机器指令收录，但不额外推定其在基础 ISA 下的汇编器展开。后续如扩展来源，应单独记录来源与许可。

## 逐条来源

每个名称的 JSON 均保存固定提交链接、行号、片段指纹与双语摘要。以下链接直接定位本仓库数据，可进一步打开 semanticSources 中的规范链接。

| 名称 | 类型 | 数据 |
| --- | --- | --- |
| `j` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-j.json) |
| `jr` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-jr.json) |
| `mv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-mv.json) |
| `nop` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-nop.json) |
| `not` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-not.json) |
| `ret` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-ret.json) |
| `seqz` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-seqz.json) |
| `sext.w` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-sext.w.json) |
| `snez` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-snez.json) |
| `zext.w` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-zext.w.json) |
| `bgt` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-bgt.json) |
| `bgtu` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-bgtu.json) |
| `ble` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-ble.json) |
| `bleu` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-bleu.json) |
| `neg` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-neg.json) |
| `beqz` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-beqz.json) |
| `bnez` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-bnez.json) |
| `scall` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-scall.json) |
| `sbreak` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-sbreak.json) |
| `c.sext.w` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-c.sext.w.json) |
| `rdcycle` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdcycle.json) |
| `rdcycleh` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdcycleh.json) |
| `rdtime` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdtime.json) |
| `rdtimeh` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdtimeh.json) |
| `rdinstret` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdinstret.json) |
| `rdinstreth` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-rdinstreth.json) |
| `csrr` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrr.json) |
| `csrw` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrw.json) |
| `csrwi` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrwi.json) |
| `csrs` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrs.json) |
| `csrc` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrc.json) |
| `csrsi` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrsi.json) |
| `csrci` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-csrci.json) |
| `frcsr` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-frcsr.json) |
| `fscsr` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fscsr.json) |
| `frrm` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-frrm.json) |
| `fsrm` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fsrm.json) |
| `frflags` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-frflags.json) |
| `fsflags` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fsflags.json) |
| `fmv.s` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.s.json) |
| `fneg.s` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fneg.s.json) |
| `fabs.s` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fabs.s.json) |
| `fmv.d` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.d.json) |
| `fneg.d` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fneg.d.json) |
| `fabs.d` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fabs.d.json) |
| `fmv.q` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.q.json) |
| `fneg.q` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fneg.q.json) |
| `fabs.q` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fabs.q.json) |
| `fmv.h` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.h.json) |
| `fneg.h` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fneg.h.json) |
| `fabs.h` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fabs.h.json) |
| `fmv.x.s` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.x.s.json) |
| `fmv.s.x` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-fmv.s.x.json) |
| `vneg.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vneg.v.json) |
| `vnot.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vnot.v.json) |
| `vwcvt.x.x.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vwcvt.x.x.v.json) |
| `vwcvtu.x.x.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vwcvtu.x.x.v.json) |
| `vncvt.x.x.w` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vncvt.x.x.w.json) |
| `vfneg.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfneg.v.json) |
| `vfabs.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfabs.v.json) |
| `vmmv.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmmv.m.json) |
| `vmclr.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmclr.m.json) |
| `vmset.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmset.m.json) |
| `vmnot.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmnot.m.json) |
| `vmsgt.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmsgt.vv.json) |
| `vmsgtu.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmsgtu.vv.json) |
| `vmsge.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmsge.vv.json) |
| `vmsgeu.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmsgeu.vv.json) |
| `vmfgt.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmfgt.vv.json) |
| `vmfge.vv` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmfge.vv.json) |
| `vl1r.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vl1r.v.json) |
| `vl2r.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vl2r.v.json) |
| `vl4r.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vl4r.v.json) |
| `vl8r.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vl8r.v.json) |
| `vle1.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vle1.v.json) |
| `vse1.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vse1.v.json) |
| `vfrsqrte7.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfrsqrte7.v.json) |
| `vfrece7.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfrece7.v.json) |
| `vfredsum.vs` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfredsum.vs.json) |
| `vfwredsum.vs` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vfwredsum.vs.json) |
| `vmandnot.mm` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmandnot.mm.json) |
| `vmornot.mm` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmornot.mm.json) |
| `vpopc.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vpopc.m.json) |
| `vmcpy.m` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vmcpy.m.json) |
| `vsxei8.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vsxei8.v.json) |
| `vsxei16.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vsxei16.v.json) |
| `vsxei32.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vsxei32.v.json) |
| `vsxei64.v` | 编码别名 | [JSON](../app/src/main/assets/inst/manual-vsxei64.v.json) |
| `call` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-call.json) |
| `tail` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-tail.json) |
| `vmsge.vx` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmsge.vx.json) |
| `vmsgeu.vx` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmsgeu.vx.json) |
| `vmslt.vi` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmslt.vi.json) |
| `vmsltu.vi` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmsltu.vi.json) |
| `vmsge.vi` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmsge.vi.json) |
| `vmsgeu.vi` | 展开参考 | [JSON](../app/src/main/assets/inst/pseudo-vmsgeu.vi.json) |
| `li` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-li.json) |
| `la` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-la.json) |
| `rev` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-rev.json) |
| `mftx.s` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mftx.s.json) |
| `mftx.d` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mftx.d.json) |
| `mxtf.s` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mxtf.s.json) |
| `mxtf.d` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mxtf.d.json) |
| `mffsr` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mffsr.json) |
| `mtfsr` | 仅名称参考 | [JSON](../app/src/main/assets/inst/pseudo-mtfsr.json) |
