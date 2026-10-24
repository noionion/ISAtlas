"""Vector table expansion; bit positions come from the manual's format diagrams."""
from copy import deepcopy
import re

from instruction_sources import SOURCE, field, encoding, evidence, waves


def arithmetic_fields(funct6, funct3, vm=None, vs1=None, vs2=None):
    return [field("opcode",6,0,0x57),field("vd",11,7),field("funct3",14,12,funct3),
            field("simm[4:0]" if funct3 == 3 else "rs1" if funct3 in {4,6} else "fs1" if funct3 == 5 else "vs1",19,15,vs1),
            field("vs2",24,20,vs2),field("vm",25,25,vm),field("funct6",31,26,funct6)]


def vector_instructions(add, source_for):
    table_path="images/wavedrom/v-inst-table.edn"
    table=(SOURCE/table_path).read_text(encoding="utf-8")
    format_source=source_for("images/wavedrom/valu-format.edn",r"reg:","vector-arithmetic-format")
    def emit(name, fields, src, constraints=None):
        if name=="vid.v":
            vs2=next(f for f in fields if f["msb"]==24 and f["lsb"]==20)
            vs2["value"]=0;vs2.pop("operand",None)
        if name in {"vmv.x.s","vcpop.m","vfirst.m","vfmv.f.s"}:
            dest=next(f for f in fields if f["msb"]==11 and f["lsb"]==7)
            dest["name"]=dest["operand"]="fd" if name=="vfmv.f.s" else "rd"
        if name.split('.')[0].startswith(("vsll","vsrl","vsra","vssrl","vssra","vnsrl","vnsra","vnclip","vslide","vrgather")):
            for f in fields:
                if f.get("operand")=="simm":
                    f["name"]="uimm[4:0]";f["operand"]="uimm"
        v=encoding(fields,[32,64],[["V"]],src,constraints)
        v["sources"].append(format_source)
        if name=="vid.v":
            v["sources"].append(source_for("v-st-ext.adoc",r"The `vs2` field of the instruction must be set to `v0`","reserved-operand-rule"))
        v["constraints"].append("Requires a legal current vtype/SEW/LMUL and register-group alignment/overlap; vm=0 uses v0.t where permitted")
        try:
            sem=source_for("v-st-ext.adoc",re.escape(name),"vector-semantics")
        except ValueError:
            try:
                sem=source_for("v-st-ext.adoc",re.escape(name.split('.')[0]),"vector-family-semantics")
            except ValueError:
                if "seg" not in name:
                    raise
                sem=source_for("v-st-ext.adoc",r"Vector Load/Store Segment Instructions","vector-segment-family")
        add(name,v,sem)
    # Each table row has independent integer, multiply/mask, and FP columns.
    previous=[None,None,None]
    for m in re.finditer(r"(?m)^\|.*$",table):
        cells=[x.strip() for x in m[0].split("|")[1:]]
        if len(cells)!=13:
            continue
        groups=[cells[:5],cells[5:9],cells[9:13]]
        for group_index,g in enumerate(groups):
            code=g[0]
            if re.fullmatch("[01]{6}",code):
                previous[group_index]=int(code,2)
            elif code:
                continue
            root=g[-1]
            if not root or not root.startswith('v') or root.upper()==root:
                continue
            funct6=previous[group_index]
            src=evidence(table_path,m.start(),m.end(),"vector-funct6-table")
            if root=="vmv<nr>r":
                for n in [1,2,4,8]:
                    emit(f"vmv{n}r.v",arithmetic_fields(funct6,3,vm=1,vs1=n-1),src,["Source and destination base registers must align to the register-group size"])
                continue
            original_root=root
            for flag,mode in zip(g[1:-1],([('vv',0),('vx',4),('vi',3)] if group_index==0 else [('vv',2),('vx',6)] if group_index==1 else [('vv',1),('vf',5)])):
                root=original_root
                if not flag:
                    continue
                suffix,funct3=mode
                if root in {"vmerge/vmv","vfmerge/vfmv"}:
                    base="vf" if group_index==2 else "v"
                    merge_suffix={"vv":"vvm","vx":"vxm","vi":"vim","vf":"vfm"}[suffix]
                    emit(base+"merge."+merge_suffix,arithmetic_fields(funct6,funct3,vm=0),src)
                    # Floating merge is scalar only; the vv column is blank.
                    move_suffix={"vv":"v.v","vx":"v.x","vi":"v.i","vf":"v.f"}[suffix]
                    emit(base+"mv."+move_suffix,arithmetic_fields(funct6,funct3,vm=1,vs2=0),src)
                    continue
                if root in {"vadc","vsbc"}:
                    emit(root+"."+suffix+"m",arithmetic_fields(funct6,funct3,vm=0),src)
                    continue
                if root in {"vmadc","vmsbc"}:
                    emit(root+"."+suffix+"m",arithmetic_fields(funct6,funct3,vm=0),src)
                    emit(root+"."+suffix,arithmetic_fields(funct6,funct3,vm=1),src)
                    continue
                actual_suffix=suffix
                if root.startswith(("vred","vwred","vfred","vfwred")):
                    actual_suffix="vs"
                elif root in {"vmandn","vmand","vmor","vmxor","vmorn","vmnand","vmnor","vmxnor"}:
                    actual_suffix="mm"
                elif root=="vcompress":
                    actual_suffix="vm"
                elif root.endswith(".w"):
                    root=root[:-2]
                    actual_suffix="w"+suffix[-1]
                elif root in {"vnsrl","vnsra","vnclip","vnclipu"}:
                    actual_suffix="w"+suffix[-1]
                emit(root+"."+actual_suffix,arithmetic_fields(funct6,funct3,vm=1 if actual_suffix in {"mm","vm"} else None),src)
    # Unary subspaces fix vs1 or vs2, in addition to the parent table's funct6.
    unary={"VWXUNARY0":(0b010000,2,"vs1"),"VRXUNARY0":(0b010000,6,"vs2"),
           "VXUNARY0":(0b010010,2,"vs1"),"VRFUNARY0":(0b010000,5,"vs2"),
           "VWFUNARY0":(0b010000,1,"vs1"),"VFUNARY0":(0b010010,1,"vs1"),
           "VFUNARY1":(0b010011,1,"vs1"),"VMUNARY0":(0b010100,2,"vs1")}
    for space,(funct6,funct3,fixed_operand) in unary.items():
        m=re.search(r"(?m)^\."+space+r" encoding space\s*\n(.*?)(?=^\.[A-Z]|\Z)",table,re.S)
        if not m:
            raise ValueError(f"Missing unary table {space}")
        for row in re.finditer(r"(?m)^\|\s*([01]{5})\s*\|\s*([a-z][a-z0-9.]+)\s*$",m[1]):
            name=row[2]
            suffix={"vcpop":".m","vfirst":".m","vmsbf":".m","vmsof":".m","vmsif":".m","viota":".m","vid":".v"}.get(name,"")
            name+=suffix
            fixed=int(row[1],2)
            fields=arithmetic_fields(funct6,funct3,vm=1 if name in {"vmv.x.s","vmv.s.x","vfmv.f.s","vfmv.s.f"} else None,
                                     vs1=fixed if fixed_operand=="vs1" else None,vs2=fixed if fixed_operand=="vs2" else None)
            emit(name,fields,evidence(table_path,m.start(1)+row.start(),m.start(1)+row.end(),"vector-unary-table"))
    cfg_path="images/wavedrom/vcfg-format.edn"
    cfg=(SOURCE/cfg_path).read_text(encoding="utf-8")
    for name,(m,fields) in zip(["vsetvli","vsetivli","vsetvl"],waves(cfg)):
        emit(name,fields,evidence(cfg_path,m.start(),m.end(),"literal-vector-config"))
    memory_instructions(emit,source_for)


def memory_instructions(emit,source_for):
    path="images/wavedrom/vmem-format.edn"
    text=(SOURCE/path).read_text(encoding="utf-8")
    templates=list(waves(text))
    # width=000/101/110/111 denotes EEW=8/16/32/64, mew=0.
    width_source=source_for("v-st-ext.adoc",r"Vector Load/Store Width Encoding", "vector-memory-width-table")
    for store in [False,True]:
        for mode,mop,template_index in [("unit",0,0),("strided",2,1),("unordered",1,2),("ordered",3,2)]:
            m,base=templates[template_index+(3 if store else 0)]
            for bits,width in [(8,0),(16,5),(32,6),(64,7)]:
                for nf in range(8):
                    if mode=="unit":
                        root=("vs" if store else "vl")+(f"seg{nf+1}" if nf else "")+f"e{bits}"
                    elif mode=="strided":
                        root=("vss" if store else "vls")+(f"seg{nf+1}" if nf else "")+f"e{bits}"
                    else:
                        root=("vs" if store else "vl")+("ox" if mode=="ordered" else "ux")+(f"seg{nf+1}" if nf else "")+f"ei{bits}"
                    fields=deepcopy(base)
                    for f in fields:
                        if f["name"] in {"width","mop","mew","nf","lumop","sumop"}:
                            f["value"]={"width":width,"mop":mop,"mew":0,"nf":nf,"lumop":0,"sumop":0}[f["name"]]
                            f.pop("operand",None)
                    src=evidence(path,m.start(),m.end(),"vector-memory-template")
                    constraints=["nf encodes segment count minus one; EMUL*(nf+1) <= 8; legal EEW/EMUL and register groups required"]
                    emit(root+".v",fields,src,constraints)
                    # A fault-only-first variant exists only for unit-stride loads.
                    if not store and mode=="unit":
                        ff=deepcopy(fields)
                        next(f for f in ff if f["name"]=="lumop")["value"]=16
                        emit(root+"ff.v",ff,src,constraints)
        # Packed mask transfers and whole-register transfers are unit-stride,
        # unmasked, but have their own lumop/sumop values.
        m,base=templates[3 if store else 0]
        fields=deepcopy(base)
        for f in fields:
            if f["name"] in {"width","vm","mop","mew","nf","lumop","sumop"}:
                f["value"]={"width":0,"vm":1,"mop":0,"mew":0,"nf":0,"lumop":11,"sumop":11}[f["name"]];f.pop("operand",None)
        src=evidence(path,m.start(),m.end(),"vector-mask-memory")
        emit("vsm.v" if store else "vlm.v",fields,src)
        for n in [1,2,4,8]:
            for bits,width in ([(8,0)] if store else [(8,0),(16,5),(32,6),(64,7)]):
                fields=deepcopy(base)
                for f in fields:
                    if f["name"] in {"width","vm","mop","mew","nf","lumop","sumop"}:
                        f["value"]={"width":width,"vm":1,"mop":0,"mew":0,"nf":n-1,"lumop":8,"sumop":8}[f["name"]];f.pop("operand",None)
                emit(f"vs{n}r.v" if store else f"vl{n}re{bits}.v",fields,src,["Base register must align to the whole-register group size"])
