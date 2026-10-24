"""Independently audit every stored instruction field and its source evidence."""
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from itertools import combinations,product

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app/src/main/assets"
SOURCE=ROOT/"upstream/riscv-isa-manual/src"
REVISION="584390d9d872835b1bc45ab9ea946b56db3ad485"
REPOSITORY="https://github.com/riscv/riscv-isa-manual"
EXTENSIONS=set("I E M A F D Q C B H S V".split())|set("Smrnmi Svinval Zaamo Zabha Zacas Zalrsc Zawrs Zba Zbb Zbc Zbkb Zbkc Zbkx Zbs Zca Zcb Zcd Zcf Zclsd Zcmop Zcmp Zcmt Zdinx Zfa Zfbfmin Zfh Zfhmin Zfinx Zhinx Zhinxmin Zicbom Zicbop Zicboz Zicfilp Zicfiss Zicntr Zicond Zicsr Zifencei Zihintntl Zihintpause Zilsd Zimop Zk Zkn Zknd Zkne Zknh Zks Zksed Zksh Zmmul Ztso Zvbb Zvbc Zvfbfmin Zvfbfwma Zvfh Zvfhmin Zvkb Zvkg Zvkn Zvknc Zvkned Zvkng Zvknha Zvknhb Zvks Zvksc Zvksed Zvksg Zvksh Zve32x Zve32f Zve64x Zve64f Zve64d".split())
EXTENSIONS.update({"Smctr","Ssctr"})
OPERANDS=set("LPL aq bs constantIndex csr fd fm fs1 i5 imm index pred rd rd' rd/rs1 rd'/rs1' rl rlist rm rnum rs rs1 rs1' rs2 rs2' rs3 shamt simm spimm succ uimm vd vm vs1 vs2 vs3 vtypei r1s' r2s'".split())


def require(condition,message):
    if not condition:
        raise ValueError(message)


def source_check(s,cache):
    require(set(s)=={"path","lineStart","lineEnd","url","sha256","method"},"Unexpected source fields")
    path=PurePosixPath(s["path"])
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in s["path"],"Unsafe source path")
    if s["path"] not in cache:
        cache[s["path"]]=(SOURCE/s["path"]).read_text(encoding="utf-8").splitlines()
    lines=cache[s["path"]]
    first,last=s["lineStart"],s["lineEnd"]
    require(type(first) is int and type(last) is int and 1<=first<=last<=len(lines),"Invalid source range")
    excerpt="\n".join(lines[first-1:last])
    require(hashlib.sha256(excerpt.encode()).hexdigest()==s["sha256"],"Changed source evidence")
    require(s["url"]==f"{REPOSITORY}/blob/{REVISION}/src/{s['path']}#L{first}-L{last}","Unpinned source link")
    require(s["method"] and type(s["method"]) is str,"Missing source method")


def bilingual(a):
    for key in ["summary","summaryEn"]:
        value=a[key]
        require(isinstance(value,str) and 0<len(value.strip())<=300,"Empty or excessive summary")
        require(not re.search(r"未描述|TODO|TBD|待补|unknown instruction",value,re.I),"Placeholder summary")
    require(not re.search(r"[\u4e00-\u9fff]",a["summaryEn"]),"Chinese in English summary")
    if a["summary"]==a["summaryEn"]:
        require(any(x in a["summary"] for x in ["=","PC +=","sp +="]),"Untranslated prose is not a formula")
    else:
        require(re.search(r"[\u4e00-\u9fff]",a["summary"]),"Chinese summary is untranslated")


def check_instruction(a,filename,cache):
    allowed={"schemaVersion","id","architecture","namespace","name","summary","summaryEn","kind","aliasOf","sourceRevision","license","semanticSources","encodings"}
    require(set(a)<=allowed,"Unexpected instruction metadata")
    n=a["name"]
    require(re.fullmatch("[a-z][a-z0-9.]*",n),"Invalid mnemonic")
    require(filename=="manual-"+n+".json" and a["id"]=="riscv/manual/inst/"+n,"Identity/filename mismatch")
    require(a["schemaVersion"]==1 and a["architecture"]=="riscv" and a["namespace"]=="manual","Wrong schema or architecture")
    require(a["sourceRevision"]==REVISION and a["license"]=="CC-BY-4.0","Wrong source or license")
    require(a["kind"] in {"instruction","alias","hint"},"Invalid kind")
    require((a["kind"]=="alias")==bool(a.get("aliasOf")),"Missing or spurious alias target")
    bilingual(a)
    require(a["semanticSources"] and a["encodings"],"Missing semantics/encoding")
    for s in a["semanticSources"]:
        source_check(s,cache)
    fields_count=0
    for v in a["encodings"]:
        require(set(v)<={"length","xlens","requires","mask","match","fields","constraints","sources","sourceSynopsis","summary","summaryEn","registerFile","privilege","encodingConstraints","contextRules"},"Unexpected variant fields")
        bits=v["length"]
        require(bits in {16,32} and v["xlens"] in [[32],[64],[32,64]],"Invalid instruction length/XLEN")
        require(isinstance(v["requires"],list) and v["requires"],"Missing requirements")
        for req in v["requires"]:
            require(isinstance(req,list) and set(req)<=EXTENSIONS and len(req)==len(set(req)),"Unknown/duplicate ISA extension")
        mask,match=int(v["mask"],16),int(v["match"],16)
        require(re.fullmatch(r"0x[0-9a-f]{"+str(bits//4)+"}",v["mask"]) and re.fullmatch(r"0x[0-9a-f]{"+str(bits//4)+"}",v["match"]),"Noncanonical bit encoding")
        require(not match&~mask and mask <1<<bits,"Match/mask inconsistent")
        occupied=fixed=actual=0
        operand_bits={}
        for f in v["fields"]:
            fields_count+=1
            require(set(f)<={"name","msb","lsb","value","operand","operandBits","operandSigned","registerOffset","registerMap","registerClass","sourceLabels","values"},"Unexpected encoding field attributes")
            hi,lo=f["msb"],f["lsb"]
            require(type(hi) is int and type(lo) is int and 0<=lo<=hi<bits,"Field out of bounds")
            word=((1<<(hi-lo+1))-1)<<lo
            require(not occupied&word,"Overlapping fields")
            occupied|=word
            require(isinstance(f["name"],str) and f["name"],"Missing field label")
            if "value" in f:
                value=f["value"]
                require(type(value) is int and 0<=value<1<<(hi-lo+1),"Fixed value overflow")
                require("operand" not in f,"Constant marked as operand")
                fixed|=word;actual|=value<<lo
            else:
                require(f.get("operand") in OPERANDS,"Unreviewed variable field: "+str(f.get("operand")))
                if "registerClass" in f:
                    require(f['registerClass'] in {'integer','float','vector'},'Invalid register class')
                if "operandSigned" in f:
                    require(type(f["operandSigned"]) is bool,"Invalid operand signedness")
                if "operandBits" in f:
                    mapping=f["operandBits"]
                    require(len(mapping)==hi-lo+1 and len(set(mapping))==len(mapping) and all(type(x) is int and 0<=x<64 for x in mapping),"Invalid immediate mapping")
                    previous=operand_bits.setdefault(f["operand"],set())
                    require(not previous&set(mapping),"Repeated decoded operand bits")
                    previous.update(mapping)
                if "registerOffset" in f:
                    require(f["registerOffset"]==8 and hi-lo==2,"Invalid compact register offset")
                if "registerMap" in f:
                    require(f["registerMap"]==[8,9,18,19,20,21,22,23] and hi-lo==2,"Invalid saved-register map")
                if "values" in f:
                    require(all(k.isdecimal() and int(k)<1<<(hi-lo+1) and isinstance(x,str) and x for k,x in f["values"].items()),"Invalid value enumeration")
        require(occupied==(1<<bits)-1 and fixed==mask and actual==match,"Mask/match is not exactly derived from fields")
        require(mask&3==3 and (match&3!=3 if bits==16 else match&3==3),"Wrong instruction parcel length")
        if bits==32:
            require(mask&127==127 and (match&31)!=31,"Missing opcode or long-instruction encoding")
        if "summary" in v or "summaryEn" in v:
            bilingual(v)
        require(isinstance(v["constraints"],list) and all(isinstance(x,str) and x for x in v["constraints"]),"Malformed contextual constraints")
        for c in v.get("encodingConstraints",[]):
            require(c["operator"] in {"in","notIn"} and c["values"] and c["reason"],"Invalid bit constraint")
            cm=int(c["mask"],16)
            require(cm<(1<<bits) and all(type(x) is int and x&~cm==0 for x in c["values"]),"Constraint value exceeds its mask")
            if "xlens" in c:
                require(set(c["xlens"])<=set(v["xlens"]),"Invalid conditional XLEN")
        require(v["sources"],"Missing encoding evidence")
        for s in v["sources"]:
            source_check(s,cache)
        require(isinstance(v.get('contextRules'),list),'Missing context scan result')
        context_ids=[]
        for rule in v['contextRules']:
            require(set(rule)=={'id','category','summary','summaryEn','check','source'},'Unexpected context-rule fields')
            bilingual(rule)
            source_check(rule['source'],cache)
            require(rule['category'] in {'registers','rounding','state','vector','csr','privilege','cfi','memory','control'},'Invalid context category')
            require(rule['check'] in {'external','e-registers','dynamic-rounding','fp-state','encoded','saved-registers','rlist','vector-state','vtype','vstart-zero','crypto-groups','sew64','bf16-width'},'Unknown context checker')
            context_ids.append(rule['id'])
        require(len(context_ids)==len(set(context_ids)),'Duplicate context rule')
    return fields_count


def matches(v,word,xlen):
    if type(word) is not int or not 0<=word<1<<v['length']:
        return False
    if xlen not in v["xlens"] or word&int(v["mask"],16)!=int(v["match"],16):
        return False
    for c in v.get("encodingConstraints",[]):
        if "xlens" in c and xlen not in c["xlens"]:
            continue
        present=word&int(c["mask"],16) in c["values"]
        if present!=(c["operator"]=="in"):
            return False
    return True


def overlap_category(a,b):
    names={a["name"],b["name"]}
    if a["kind"]=="alias" or b["kind"]=="alias":
        return "documented-alias-specialization"
    if any(x.startswith(("mop.","c.mop.")) for x in names):
        return "may-be-operation-redefinition"
    if "lpad" in names:
        return "landing-pad-environment-controls"
    if names&{"pack","packw"} and "zext.h" in names:
        return "extension-dependent-specialization"
    if names&{"fence","fence.tso","pause"} and len(names-{"fence","fence.tso","pause"})==0:
        return "documented-fence-hint-specialization"
    if any(x.startswith("prefetch.") for x in names) and "ori" in names:
        return "documented-prefetch-hint"
    if any(x.startswith(("ntl.","c.ntl.")) for x in names):
        return "documented-non-temporal-hint"
    if "c.fsdsp" in names and any(x.startswith("cm.") for x in names):
        return "incompatible-Zcd-and-Zcmp-or-Zcmt"
    if names in [{"c.flw","c.ld"},{"c.flwsp","c.ldsp"},{"c.fsw","c.sd"},{"c.fswsp","c.sdsp"}]:
        return "incompatible-Zcf-and-Zclsd"
    if names in [{"c.add","c.ebreak"},{"c.add","c.jalr"},{"c.addi","c.nop"},{"c.addi16sp","c.lui"},{"c.ebreak","c.jalr"},{"c.jr","c.mv"},{"cm.jalt","cm.jt"}]:
        return "partitioned-by-encoding-constraints"
    if "c.lui" in names and names&{"c.sspopchk","c.sspush"}:
        return "zero-immediate-space-redefinition"
    raise ValueError("Unreviewed encoding overlap: "+", ".join(sorted(names)))


def reviewed_overlaps(records):
    overlaps=[]
    for a,b in combinations(records.values(),2):
        for x,y in product(a["encodings"],b["encodings"]):
            if x["length"]!=y["length"] or not set(x["xlens"])&set(y["xlens"]):
                continue
            if (int(x["match"],16)^int(y["match"],16))&(int(x["mask"],16)&int(y["mask"],16))==0:
                overlaps.append({"names":sorted([a["name"],b["name"]]),"reason":overlap_category(a,b)})
                break
    return overlaps


def review(write_report=True):
    catalog=json.loads((ASSETS/"riscv-instructions.json").read_text(encoding="utf-8"))
    names=catalog["files"]
    require(len(names)==len(set(names)) and names==sorted(names),"Duplicate or unsorted file index")
    require(set(names)=={p.name for p in (ASSETS/"inst").glob('manual-*.json')},"Unindexed or missing instruction files")
    require(set(catalog["fileHashes"])==set(names),"Missing hashes")
    records,rows,cache={},[],{}
    fields_total=0
    digest=hashlib.sha256()
    for filename in names:
        require(PurePosixPath(filename).name==filename,"Unsafe indexed path")
        raw=(ASSETS/"inst"/filename).read_bytes()
        require(hashlib.sha256(raw).hexdigest()==catalog["fileHashes"][filename],"Changed data fingerprint")
        a=json.loads(raw)
        count=check_instruction(a,filename,cache)
        fields_total+=count
        require(a["id"] not in records,"Duplicate identity")
        records[a["id"]]=a
        digest.update(filename.encode()+b'\0'+raw)
        rows.append({"name":a["name"],"encodings":len(a["encodings"]),"fields":count,"result":"pass"})
    for a in records.values():
        if a["kind"]=="alias":
            target=records.get("riscv/manual/inst/"+a["aliasOf"])
            require(target,"Missing alias target")
            for v in a["encodings"]:
                require(any(int(v["mask"],16)&int(b["mask"],16)==int(b["mask"],16) and int(v["match"],16)&int(b["mask"],16)==int(b["match"],16) for b in target["encodings"]),"Alias is not a specialization of its base")
    overlaps=reviewed_overlaps(records)
    report={"sourceRevision":REVISION,"instructions":len(names),"encodings":sum(len(a["encodings"]) for a in records.values()),"encodingFields":fields_total,"sourceFilesVerified":len(cache),"dataSha256":digest.hexdigest(),"errors":[],"overlaps":overlaps,"records":rows}
    if write_report:
        path=ROOT/"docs/data/instruction-review.json"
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return report


if __name__=="__main__":
    report=review()
    print(json.dumps({k:v for k,v in report.items() if k not in {'records','overlaps'}}|{"reviewedOverlapPairs":len(report['overlaps'])}))
