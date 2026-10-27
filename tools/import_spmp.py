"""Adapt ratified Sspmp 1.0; selector values never become CSR addresses.

Only parse text/bytefield evidence. Do not execute upstream build dependencies.
The independent hypervisor document is a 0.2 draft without assigned CSR numbers.
"""
from copy import deepcopy
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
UPSTREAM = ROOT / "upstream/riscv-spmp"
REPOSITORY = "https://github.com/riscv/riscv-spmp"
REVISION = "4ce228207193c55247200110e3d5b6881a17c367"
TAG = "v1.0"
MANUAL_REVISION = "584390d9d872835b1bc45ab9ea946b56db3ad485"
ATTRIBUTION = dict(namespace="spmp", title="RISC-V S-level Physical Memory Protection 1.0",
                   revision=REVISION, url=f"{REPOSITORY}/tree/{REVISION}", license="CC-BY-4.0")


def url(path):
    lines = (UPSTREAM / path).read_text(encoding="utf-8").splitlines()
    return f"{REPOSITORY}/blob/{REVISION}/{path}#L1-L{len(lines)}"


def field(name, zh, en, high, low, *, widths=(32, 64), access="WARL", view="", path="sspmp/spmp_spec.adoc", values=None, rule="", aliases=()):
    values = values or {}
    return dict(name=f"{view}.{name}" if view else name, summary=zh, summaryEn=en, access=access,
                layouts=[dict(xlen=w, msb=min(high, w-1), lsb=low) for w in widths if low < w],
                values={str(k): v[0] for k,v in values.items()}, valuesEn={str(k): v[1] for k,v in values.items()},
                valuesByXlen={}, valuesEnByXlen={}, aliases=list(aliases), typeRule=rule, condition="", view=view, sourceUrl=url(path))


def region(label, access, high, low, widths, path, view=""):
    return dict(label=label, access=access, summary="保留，写入时保留原值，读取时忽略。" if access == "WPRI" else "规范位图规定为零。",
                summaryEn="Reserved; preserve on writes and ignore on reads." if access == "WPRI" else "Zero in the normative diagram.",
                layouts=[dict(xlen=w, msb=min(high,w-1), lsb=low) for w in widths], view=view, sourceUrl=url(path))


def direct_rows():
    """Take only explicitly numbered SPMP CSRs, independently of selector tables."""
    result = {}
    for path in ("sspmp/Resource_Sharing.adoc", "sspmp/spmpswitch.adoc"):
        text = (UPSTREAM/path).read_text(encoding="utf-8")
        for name, number in re.findall(r"`(mpmpdeleg|spmpenh?)` \(CSR number (0x[0-9A-Fa-f]+)\)", text):
            result[name] = (int(number,16),path)
    assert set(result) == {"mpmpdeleg", "spmpen", "spmpenh"}
    return result


def base_reg(name, address, namespace, privilege, summary, summary_en, source, extensions, widths=(32,64)):
    return dict(id=f"riscv/{namespace}/csr/{name}", name=name, namespace=namespace, title=summary_en,
                summary=summary, summaryEn=summary_en, locator=dict(type="riscv-csr", address=address), privilege=privilege,
                access="RW", xlens=list(widths), condition=" ".join(extensions), extensions=extensions, notes="",
                sourceUrl=source, sourceRevision=MANUAL_REVISION if namespace == "manual" else REVISION,
                license="CC-BY-4.0", coverage="fields", fields=[], regions=[])


def attach(reg):
    reg["sources"] = [s for s in reg.get("sources",[]) if s["namespace"] != "spmp"] + [ATTRIBUTION.copy()]


def append_once(obj, key, text):
    if text not in obj.get(key, ""):
        obj[key] = (obj.get(key, "") + " " + text).strip()


def enrich(input_registers):
    registers = deepcopy([r for r in input_registers if r["namespace"] != "spmp"])
    existing = {r["name"]: r for r in registers}
    added, supplemented = [], set()
    # CSR numbers for these windows come from the already pinned ISA manual.
    indirect = ROOT / "upstream/riscv-isa-manual/src/indirect-csr.adoc"
    manual_url = f"https://github.com/riscv/riscv-isa-manual/blob/{MANUAL_REVISION}/src/indirect-csr.adoc"
    table = re.findall(r"\|(0x[0-9A-Fa-f]+) \|([MS])RW \|XLEN \|`([ms]ireg[2-6])`", indirect.read_text(encoding="utf-8"))
    assert len(table) == 10
    for number, mode, name in table:
        if name not in existing:
            reg = base_reg(name,int(number,16),"manual",mode,"访问 selector 选中的间接寄存器。",
                           "Access the indirectly selected register.",manual_url,["Smcsrind" if mode=="M" else "Sscsrind"])
            reg["fields"] = [field("DATA","访问 selector 选中的寄存器，权限由所选扩展定义。","Access the selected register; its extension defines access semantics.",63,0,access="依 selector",path="sspmp/spmp_spec.adoc")]
            reg["fields"][0]["sourceUrl"] = manual_url
            registers.append(reg)
            existing[name] = reg
            added.append(name)
    for name,(address,path) in direct_rows().items():
        reg = existing.get(name)
        if reg is None:
            reg = base_reg(name,address,"spmp","M" if name=="mpmpdeleg" else "S",
                "划分 PMP 与 SPMP 条目。" if name=="mpmpdeleg" else "逐位控制 SPMP 条目是否参与匹配。",
                "Partition PMP and SPMP entries." if name=="mpmpdeleg" else "Enable SPMP entry matching with a bitmap.",url(path),
                ["Smpmpdeleg"] if name=="mpmpdeleg" else ["Sspmpen"],(32,) if name=="spmpenh" else (32,64))
            registers.append(reg)
            existing[name]=reg
            added.append(name)
        assert reg["locator"]["address"] == address
        attach(reg)
        reg["fields"],reg["regions"] = [],[]
        if name=="mpmpdeleg":
            rule="完整 PMP 边界按 CSR 低 7 位解释；0 委托全部，写入超过可写条目总数时读回总数。不得小于等于任何已锁定 PMP 的索引；锁定 SPMP 不阻止 M 态调整。重分配会继承原配置，应预先初始化。RV32 位图将低 2 位标为 0 (WARL)，PMPNUM 为 bits 6:2；本版正文未单独解释该粒度差异。"
            f=field("PMPNUM","设置保留给 PMP 的条目边界，其后条目委托为 SPMP。","Set the PMP boundary; later entries are delegated to SPMP.",6,0,widths=(64,),path="sspmp/images/bytefield/mpmpdeleg_format_rv64.adoc",rule=rule)
            f["layouts"].insert(0,dict(xlen=32,msb=6,lsb=2))
            reg["fields"]=[f]
            reg["regions"]=[region("0","WARL",1,0,(32,),"sspmp/images/bytefield/mpmpdeleg_format_rv32.adoc"),region("WPRI","WPRI",63,7,(32,64),"sspmp/images/bytefield/mpmpdeleg_format_rv64.adoc")]
            reg["notes"]=rule+" Sspmp 与 Sm1p13 同时实现时要求 Smpmpdeleg；Smpmpdeleg 要求 Smcsrind。"
        else:
            shift=32 if name=="spmpenh" else 0
            for i in range(32 if shift else 64):
                number=i+shift
                f=field(f"EN{number}",f"控制 SPMP 条目 {number} 是否参与匹配；还需 A≠OFF。",f"Enable SPMP entry {number}; A must also be nonzero.",i,i,
                    widths=(32,) if shift or i<32 else (64,),path=path,
                    values={0:("不参与匹配","Inactive"),1:("A≠OFF 时参与匹配","Active if A is nonzero")},
                    rule="条目 L=1 时对应位只读；不控制配置/地址可写性。超出实现条目的位写忽略。更新后需要适用的 SFENCE.VMA/HFENCE.GVMA 同步；TOR 下界与前一条目的使能状态无关。")
                if not shift and i<32:
                    f["layouts"].append(dict(xlen=64,msb=i,lsb=i))
                reg["fields"].append(f)
            reg["notes"]="Sspmpen；RV32 spmpenh 别名到 spmpen 的高 32 位。超出实现条目的位写忽略；正文未明确其读回值，不推断为 RO0。"
    # Two indirect formats, attached to physical windows rather than fake spmpaddr/cfg CSRs.
    for mode in ("s","m"):
        select,addr,cfg = (existing[mode+suffix] for suffix in ("iselect","ireg","ireg2"))
        for r in (select,addr,cfg):
            attach(r)
            supplemented.add(r["name"])
        ext="Sspmp" if mode=="s" else "Smpmpdeleg"
        for r in (select,addr,cfg):
            r["extensions"]=list(dict.fromkeys(r.get("extensions",[])+[ext,"Sscsrind" if mode=="s" else "Smcsrind"]))
            r["condition"]=("Sscsrind" if mode=="s" else "Smcsrind")+" / "+ext+"（依 selector 对应扩展）"
        for r in (addr,cfg):
            r["fields"]=[f for f in r["fields"] if f.get("view","") not in ("spmpaddr","spmpcfg")]
            r["regions"]=[f for f in r["regions"] if f.get("view","") not in ("spmpaddr","spmpcfg")]
        select_field=select["fields"][0]
        for i in range(64):
            select_field["values"][str(0x100+i)]=f"SPMP[{i}]：ireg→spmpaddr，ireg2→spmpcfg"
            select_field["valuesEn"][str(0x100+i)]=f"SPMP[{i}]: ireg accesses spmpaddr, ireg2 accesses spmpcfg"
        note="SPMP selector 0x100–0x13F：ireg 访问 spmpaddr[i]，ireg2 访问 spmpcfg[i]；越过实际条目数读零、写忽略。ireg3–6 在此 selector 范围保留。"
        append_once(select_field,"typeRule",note)
        for r in (select,addr,cfg):
            append_once(r,"notes",note+" 不同间接写及后续访存之间不自动排序；S/U 使用 SFENCE.VMA x0,x0，适用的 VS/VU 使用 HFENCE.GVMA x0,x0。")
        for width in (32,64):
            f=field("ADDRESS","编码区域物理地址高位；TOR/NAPOT 的解释与 PMP 相同。","Encode upper physical address bits; interpret TOR/NAPOT as for PMP.",31 if width==32 else 53,0,widths=(width,),view="spmpaddr",
                    path="sspmp/spmp_spec.adoc",aliases=("spmpaddr",)+tuple(f"spmpaddr{i}" for i in range(64)),
                    rule=f"RV{width} 编码 PA[{33 if width==32 else 55}:2]；未实现地址位及最小粒度受 WARL 约束。S 侧锁定条目或下一条锁定 TOR 的地址写入被忽略；M 可经 miselect 解除 L。")
            previous=next((old for old in addr["fields"] if old["name"]==f["name"]),None)
            if previous:
                previous["layouts"]+=f["layouts"]
                previous["typeRule"]+=" "+f["typeRule"]
            else:
                addr["fields"].append(f)
        addr["regions"].append(region("0","RO",63,54,(64,),"sspmp/images/bytefield/spmpaddr-rv64.adoc","spmpaddr"))
        rule="R/W/X 共同构成 WARL 字段，按 RWX（R 为高位）编码：000 无权限、001 执行、010/011 保留、100 读、101 读执行、110 读写、111 读写执行。SHARED=1,U=0 保留。SHARED=0,U=0 仅 S；SHARED=0,U=1 时 U 按 RWX，S 数据访问需 SUM=1 且永不授予 S 执行。SHARED=U=1 时 SUM 无关：000/001/100/101 对 S/U 均按 RWX，110 为 S 读写/U 仅读，111 为 S 读写执行/U 仅执行。Combined RWX: 000 none, 001 X, 010/011 reserved, 100 R, 101 RX, 110 RW, 111 RWX. With SHARED=U=1, 110 gives S RW/U R and 111 gives S RWX/U X; other legal codes apply equally to S/U."
        binary={0:("清除（含义见组合规则）","Clear; see combined rules"),1:("置位（含义见组合规则）","Set; see combined rules")}
        definitions=[("SHARED",9,9,"结合 U 标识共享区域规则。","Identify shared-region rules together with U."),
                     ("U",8,8,"区分 S-only、U-mode 与共享区域规则。","Select S-only, U-mode or shared rules."),
                     ("L",7,7,"锁定本条目；仅 M 可通过 miselect 清除。","Lock this entry; only M via miselect can clear it."),
                     ("A",4,3,"选择地址匹配方式。","Select the address-matching mode."),
                     ("X",2,2,"参与执行权限的组合编码。","Contribute to execute permission encoding."),
                     ("W",1,1,"参与写权限的组合编码。","Contribute to write permission encoding."),
                     ("R",0,0,"参与读权限的组合编码。","Contribute to read permission encoding.")]
        for name,hi,lo,zh,en in definitions:
            vals={0:("OFF：禁用","OFF: disabled"),1:("TOR：前项地址至本项地址","TOR: previous to current address"),2:("NA4：自然对齐 4 字节","NA4: aligned four bytes"),3:("NAPOT：自然对齐 2 的幂，至少 8 字节","NAPOT: aligned power of two, at least eight bytes")} if name=="A" else {
                "L":{0:("未锁定","Unlocked"),1:("锁定；S 侧写忽略，仅 M 经 miselect 可清除","Locked; S writes ignored; only M via miselect can clear")},
                "U":{0:("SHARED=0 时为 S-only；SHARED=1 时保留","S-only with SHARED=0; reserved with SHARED=1"),1:("SHARED=0 时为 U-mode；SHARED=1 时为共享区域","U-mode with SHARED=0; shared region with SHARED=1")},
                "SHARED":{0:("由 U 选择 S-only 或 U-mode","U selects S-only or U-mode"),1:("U=1 为共享区域；U=0 保留","Shared region if U=1; reserved if U=0")},
            }.get(name,binary)
            f=field(name,zh,en,hi,lo,view="spmpcfg",path="sspmp/images/bytefield/spmpcfg.adoc",values=vals,rule=rule,aliases=("spmpcfg",))
            f["condition"]=ext+"；selector=0x100+i"
            f["aliases"] += [f"spmpcfg{i}" for i in range(64)]
            if name=="L":
                f["typeRule"]+=" L 即使 A=OFF 也锁定配置和地址；锁定 TOR 还禁止 S 侧改写前一条目的地址。"
            if name=="A":
                f["typeRule"]+=" TOR 为 [spmpaddr[i-1]<<2, spmpaddr[i]<<2)，i=0 时下界为 0；下界不小于上界时规则无效。NA4/NAPOT 的可用编码受最小粒度限制；条目优先级按索引从低到高，匹配条目必须覆盖访问的全部字节，无匹配则拒绝。TOR entry zero uses lower bound zero; granularity constrains NA4/NAPOT; the lowest matching entry must cover the entire access, and no match denies access."
            cfg["fields"].append(f)
        cfg["regions"] += [region("WPRI","WPRI",6,5,(32,64),"sspmp/images/bytefield/spmpcfg.adoc","spmpcfg"),region("WPRI","WPRI",63,10,(32,64),"sspmp/images/bytefield/spmpcfg.adoc","spmpcfg")]
        for suffix in ("ireg3","ireg4","ireg5","ireg6"):
            r=existing[mode+suffix]
            attach(r)
            supplemented.add(r["name"])
            append_once(r,"notes",note)
            r["extensions"]=list(dict.fromkeys(r["extensions"]+[ext]))
    # Preserve base identities and semantics while documenting conditional sharing.
    sharing="Smpmpdeleg：索引 ≥ mpmpdeleg.PMPNUM 的物理条目委托给 SPMP；超出当前 PMP 范围读零、写忽略。spmpcfg 低 8 位别名到对应 PMP 配置；重新划分继承原值，须预初始化。锁定 PMP 条目限制边界，锁定 SPMP 不阻止 M 态重新划分。"
    for r in registers:
        if re.fullmatch(r"pmp(?:cfg|addr)\d+",r["name"]):
            attach(r)
            supplemented.add(r["name"])
            append_once(r,"notes",sharing)
            for f in r["fields"]:
                append_once(f,"typeRule",sharing)
    # Explicit field supplements retain their original source and attribution.
    updates={
        "mstatus":{"SUM":("Sspmp 中同样控制 S 访问 U 区域；不允许 S 执行 U 区域。","Also govern S data access to U regions under Sspmp; never grant S execution."),"MXR":("Sspmp 要求 MXR 可写；权限细节以 SPMP 权限表为准。","Sspmp requires writable MXR; see its permission table.")},
        "sstatus":{"SUM":("Sspmp 中同样控制 S 访问 U 区域；不允许 S 执行 U 区域。","Also govern S data access to U regions under Sspmp; never grant S execution."),"MXR":("Sspmp 要求 MXR 可写；权限细节以 SPMP 权限表为准。","Sspmp requires writable MXR; see its permission table.")},
        "satp":{"MODE":("Sspmp 仅在 MODE=Bare 时启用，与分页互斥。","Sspmp is active only with MODE=Bare, mutually exclusive with paging.")},
        "hgatp":{"MODE":("实现 Shbare 时，V=1 且 MODE=Bare 可对 VS/VU 应用 SPMP。","With Shbare, V=1 and MODE=Bare permit SPMP enforcement for VS/VU.")}}
    for name,fields in updates.items():
        r=existing[name]
        attach(r)
        supplemented.add(name)
        for f in r["fields"]:
            if f["name"] in fields:
                zh,en=fields[f["name"]]
                append_once(f,"typeRule",zh+" "+en)
                append_once(f,"condition","Sspmp"+ (" Shbare" if name=="hgatp" else ""))
    for name in ("mcause","scause"):
        r=existing[name]
        attach(r)
        supplemented.add(name)
        f=next(f for f in r["fields"] if f["name"]=="CODE")
        for code in ("12","13","15","20","21","23"):
            if code in f["values"]:
                append_once(f["values"],code,"Sspmp：INT=0 时也用于"+("Shbare 下 VS/VU SPMP 失败。" if int(code)>=20 else "SPMP 失败。"))
                append_once(f["valuesEn"],code,"Sspmp: also an SPMP violation with INT=0"+(" under Shbare for VS/VU." if int(code)>=20 else "."))
        append_once(f,"typeRule","Sspmp 复用异常码 12/13/15；Shbare 下 VS/VU 复用 20/21/23。只补充异常含义，INT=1 的同号中断保持原义。")
    return registers,dict(new=sorted(added),supplemented=sorted(supplemented-set(added)),
                          excludedDraftCsrs=["vspmpen","vspmpenh","hspmpdeleg","hspmpen","hspmpenh"])


def verify_checkout():
    actual=subprocess.check_output(["git","-C",str(UPSTREAM),"rev-parse","HEAD"],text=True).strip()
    assert actual==REVISION,"Expected ratified SPMP v1.0"
    assert not subprocess.check_output(["git","-C",str(UPSTREAM),"status","--porcelain","--untracked-files=no"],text=True).strip(),"SPMP checkout has local modifications"


def update_metadata():
    path=ROOT/"tools/data-sources.lock.json"
    obj=json.loads(path.read_text(encoding="utf-8"))
    obj["sources"]=[s for s in obj["sources"] if s["namespace"]!="spmp"]+[dict(namespace="spmp",directory="upstream/riscv-spmp",repository=REPOSITORY,revision=REVISION,tag=TAG,license="CC-BY-4.0")]
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    notice=ASSETS/"THIRD_PARTY_NOTICES.txt"
    text=notice.read_text(encoding="utf-8")
    if f"SPMP source: {REPOSITORY}" not in text:
        text+=f"\nRISC-V S-level Physical Memory Protection 1.0; RISC-V International and contributors.\nSPMP source: {REPOSITORY}\nTag: {TAG}\nCommit: {REVISION}\nLicense: CC-BY-4.0\nChanges: extract numbered CSRs, indirect register format views and conditional manual supplements; summarize in Chinese and English; retain selector/CSR namespace separation; exclude unnumbered hypervisor draft CSRs.\n"
        notice.write_text(text,encoding="utf-8",newline="\n")


def run():
    from import_isa_manual import run as regenerate
    regenerate()


if __name__=="__main__":
    run()
