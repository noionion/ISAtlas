"""Source-backed static restrictions and explicit runtime-context dependencies.

This is data enrichment and auditing, not an instruction execution simulator.
Unknown runtime state is reported as unresolved rather than assumed legal.
"""
from collections import Counter
import json
from pathlib import Path
import re

GPR_OPERANDS={"rd","rd'","rd/rs1","rd'/rs1'","rs","rs1","rs1'","rs2","rs2'","rs3","r1s'","r2s'"}
VECTOR_OPERANDS={"vd","vs1","vs2","vs3"}
FPR_OPERANDS={"fd","fs1"}


def register_class(name,v,f):
    operand=f.get('operand')
    if operand in VECTOR_OPERANDS:return 'vector'
    if operand in FPR_OPERANDS:return 'float'
    if operand not in GPR_OPERANDS:return None
    if v.get('registerFile')=='integer':return 'integer'
    if name.startswith('v'):
        return 'float' if name.endswith('.vf') and operand=='rs1' else 'integer'
    n=name.removeprefix('c.')
    if re.fullmatch(r'f[ls][hwdq](?:sp)?',n):
        return 'integer' if operand.startswith('rs1') else 'float'
    if not name.startswith('f') or name.startswith('fence'):return 'integer'
    op=name.split('.')[0]
    if op in {'feq','flt','fle','fltq','fleq','fclass'}:
        return 'integer' if operand.startswith('rd') else 'float'
    if op in {'fcvt','fcvtmod'}:
        parts=name.split('.')
        integer_formats={'w','wu','l','lu'}
        return 'integer' if (operand.startswith('rd') and parts[1] in integer_formats) or (operand.startswith('rs1') and parts[2] in integer_formats) else 'float'
    if op in {'fmv','fmvh','fmvp'}:
        parts=name.split('.')
        if operand.startswith('rd'):return 'integer' if parts[1]=='x' else 'float'
        return 'integer' if parts[-1]=='x' else 'float'
    return 'float'


def add_context(instructions,source_for):
    for name,inst in instructions.items():
        for v in inst['encodings']:
            if name in {'flh','fsh','fmv.x.h','fmv.h.x'} and v.get('registerFile')!='integer':
                if ['Zfbfmin'] not in v['requires']:v['requires'].append(['Zfbfmin'])
                v['sources'].append(source_for('bfloat16.adoc',r'This extension depends upon the single-precision floating-point','bf16-shared-transfers'))
            for f in v['fields']:
                if 'value' not in f:
                    cls=register_class(name,v,f)
                    if cls:f['registerClass']=cls
            rules=[]
            def rule(identifier,category,zh,en,path,needle,check='external',**extra):
                source=source_for(path,needle,'context-rule')
                rules.append({'id':identifier,'category':category,'summary':zh,'summaryEn':en,
                              'check':check,'source':source,**extra})
            tests=v.setdefault('encodingConstraints',[])
            def bits(mask,operator,values,reason,xlens=None):
                item={'mask':hex(mask),'operator':operator,'values':values,'reason':reason}
                if xlens:item['xlens']=xlens
                if item not in tests:tests.append(item)
            # E restricts integer registers in extensions as well as base I/E;
            # floating-point/vector register numbers are separate name spaces.
            if any(f.get('registerClass')=='integer' for f in v['fields']):
                rule('base.e.registers','registers','E 基础架构只允许 x0–x15；浮点与向量寄存器不受此编号限制。',
                     'An E base permits only x0–x15; FP/vector register numbers are independent.',
                     'rv32e.adoc',r'All\s+encodings specifying the other registers',check='e-registers')
            rm=next((f for f in v['fields'] if f.get('operand')=='rm'),None)
            if rm:
                bits(0x7000,'in',[0,0x1000,0x2000,0x3000,0x4000,0x7000],'Rounding-mode encodings 5 and 6 are reserved')
                rule('fp.dynamic-rounding','rounding','rm=7 时使用 frm；frm 的保留值不能作为有效舍入模式。',
                     'rm=7 selects frm; reserved frm values are not valid rounding modes.',
                     'f-st-ext.adoc',r'Rounding Mode',check='dynamic-rounding')
            uses_fp=any(f.get('registerClass')=='float' for f in v['fields']) or name.startswith(('vf','vmf'))
            scalar_fp=name.startswith('f') and not name.startswith('fence')
            if (uses_fp or scalar_fp) and v.get('registerFile')!='integer':
                rule('fp.state','state','浮点状态、相关精度支持及虚拟化控制仍需满足当前执行环境。',
                     'FP state, supported precisions and virtualization controls must permit execution.',
                     'machine.adoc',r'FS\[1:0\]',check='fp-state')
            # RV32 load/store-pair and CAS operands must use aligned pairs.
            if any('Zilsd' in req or 'Zclsd' in req for req in v['requires']):
                load=name.removeprefix('c.').startswith('l')
                target=next(f for f in v['fields'] if f.get('operand') in ({'rd',"rd'"} if load else {'rs2',"rs2'"}))
                bits(1<<target['lsb'],'in',[0],'Register-pair base must be even',[32])
                rule('pair.zilsd','registers','64 位传送使用偶数起始的整数寄存器对；x0 与异常处理有专门规则。',
                     '64-bit transfers use even-based integer pairs; x0 and exception rules also apply.',
                     'zilsd.adoc',r'Operands containing',check='encoded')
            if name in {'amocas.d','amocas.q'}:
                width_xlen=[32] if name.endswith('.d') else [64]
                bits((1<<7)|(1<<20),'in',[0],'CAS pair bases rd and rs2 must both be even',width_xlen)
                rule('pair.cas','registers','跨寄存器宽度的 CAS 要求 rd、rs2 为偶数起始寄存器对。',
                     'CAS operands wider than XLEN require even rd and rs2 pair bases.',
                     'zacas.adoc',r'odd numbered registers specified',check='encoded')
            if v.get('registerFile')=='integer' and any('Zdinx' in req for req in v['requires']):
                # Only FP operands whose format is D form pairs on RV32.
                ordinary={**v,'registerFile':'float'}
                for f in v['fields']:
                    if 'value' in f or register_class(name,ordinary,f)!='float':continue
                    if name.startswith('fcvt.'):
                        fmt=name.split('.')[1] if f['operand'].startswith('rd') else name.split('.')[2]
                        if fmt!='d':continue
                    if 32 in v['xlens']:
                        bits(1<<f['lsb'],'in',[0],'RV32Zdinx double-precision operand must start at an even register',[32])
                rule('pair.zdinx','registers','RV32Zdinx 的双精度操作数使用偶数起始寄存器对，x0 对按规范处理。',
                     'RV32Zdinx double operands use even-based pairs with special x0-pair semantics.',
                     'zfinx.adoc',r'Double-precision operands in RV32Zdinx',check='encoded')
            if name in {'cm.mvsa01','cm.mva01s'}:
                if name=='cm.mvsa01':
                    bits(0x39c,'notIn',[(i<<7)|(i<<2) for i in range(8)],'CM.MVSA01 requires distinct destination saved registers')
                rule('zcmp.saved-registers','registers','保存寄存器按 UABI 非连续映射；CM.MVSA01 的两个目的寄存器必须不同。',
                     'Saved registers use the noncontiguous UABI map; CM.MVSA01 destinations must differ.',
                     'zc.adoc',r'For the encoding to be legal',check='saved-registers')
            if name.startswith(('cm.push','cm.pop')):
                rule('zcmp.rlist','registers','rlist=0..3 保留；RV32E 仅支持 rlist=4..6，栈调整按寄存器列表计算。',
                     'rlist=0..3 is reserved; RV32E permits 4..6, with list-dependent stack adjustment.',
                     'zc.adoc',r'RV32E:',check='rlist')
            if name.startswith('v'):
                vector_context(name,v,rule,source_for)
            if name.startswith('csr'):
                rule('csr.access','csr','CSR 的存在性、最低权限、只读编码、读写抑制及虚拟化拦截共同决定访问结果。',
                     'CSR existence, privilege, read-only encoding, read/write suppression and virtualization govern access.',
                     'zicsr.adoc',r'CSRRW',check='external')
            if v.get('privilege'):
                rule('privileged.controls','privilege','最低特权态以外，还需检查虚拟化及 TW/TVM/TSR、state-enable 等相关控制。',
                     'Minimum privilege is insufficient without the relevant virtualization and trap/state-enable controls.',
                     'machine.adoc',r'Trap-Return Instructions',check='external')
            if any(x in {'Zicfiss','Zicfilp'} for req in v['requires'] for x in req):
                if name in {'c.sspush','c.sspopchk'}:
                    v['requires']=[sorted(set(req+['Zcmop'])) for req in v['requires']]
                if name!='lpad':
                    v['constraints'].append('Zicfiss use in M-mode is not supported; U-mode use requires S-mode; SSE controls and shadow-stack memory permissions apply')
                rule('cfi.controls','cfi','CFI 的使能、当前特权态、影子栈页权限或着陆垫状态决定实际行为；不能只按扩展名判定。',
                     'CFI enablement, privilege, shadow-stack permissions or landing-pad state determine behavior.',
                     'unpriv-cfi.adoc',r'The Zicfiss extension depends' if name!='lpad' else r'The Zicfilp extension depends',check='external')
            if memory_instruction(name):
                rule('memory.environment','memory','有效地址、对齐、页表/PMP/PMA 权限及实现支持可能导致访问异常，不能由指令字独立确定。',
                     'Effective address, alignment, translation/PMP/PMA permissions and implementation support may cause access faults.',
                     'rv32.adoc',r'Load and Store Instructions',check='external')
            if name in {'jal','jalr','j','jr','ret','beq','bne','blt','bge','bltu','bgeu','c.j','c.jal','c.jr','c.jalr','c.beqz','c.bnez'}:
                rule('control.target','control','控制转移目标的对齐与权限取决于 IALIGN、寄存器值、PC 和分支是否实际跳转。',
                     'Target alignment and permissions depend on IALIGN, registers, PC and whether a branch is taken.',
                     'rv32.adoc',r'instruction-address-misaligned exception',check='external')
            if name in {'wfi','wrs.nto','wrs.sto'}:
                rule('wait.controls','state','等待操作受特权/虚拟化超时控制约束，并允许按规范提前恢复。',
                     'Wait operations depend on privileged/virtualized timeout controls and may resume early.',
                     'zawrs.adoc' if name.startswith('wrs') else 'machine.adoc',r'`TW`|Timeout Wait',check='external')
            if not tests:v.pop('encodingConstraints',None)
            v['requires'].sort()
            # An explicit empty list means the scan found no additional context
            # category, not that executing the instruction cannot trap.
            v['contextRules']=rules


def memory_instruction(name):
    n=name.removeprefix('c.')
    if n in {'lb','lbu','lh','lhu','lw','lwu','ld','sb','sh','sw','sd','lwsp','ldsp','swsp','sdsp'}:return True
    return bool(re.match(r'^(?:f[ls][hwdq]|amo|lr\.|sc\.|hlv|hsv|ssamo|cbo\.|prefetch\.|(?:vl|vs)(?:seg|sseg|ux|ox|e\d|se\d|[1248]r|m\.))',n))


def vector_context(name,v,rule,source_for):
    config=name.startswith('vset')
    whole_memory=bool(re.match(r'^v(?:l[1248]re(?:8|16|32|64)|s[1248]r)\.v$',name))
    if config or whole_memory:
        v['constraints']=[c for c in v['constraints'] if not c.startswith('Requires a legal current vtype/')]
    rule('vector.state','vector','向量指令需要可用的 VS 状态和实现支持，虚拟化状态也可能触发异常。',
         'Vector instructions require available VS state and implementation support, including virtualization controls.',
         'v-st-ext.adoc',r'Vector Context Status',check='vector-state')
    if not config and not whole_memory:
        rule('vector.vtype','vector','依赖 vtype 的指令在 vill=1 时非法；还需检查 SEW、LMUL、寄存器组及掩码条件。',
             'vtype-dependent instructions are illegal when vill=1; SEW, LMUL, groups and masks also constrain execution.',
             'v-st-ext.adoc',r'If the `vill` bit is set',check='vtype')
    if config:
        rule('vector.config','vector','不支持的 vtype 通常设置 vill；不能把请求的保留配置一概当成该指令编码非法。',
             'An unsupported requested vtype normally sets vill rather than necessarily making the instruction encoding illegal.',
             'v-st-ext.adoc',r'If the `vtype` value is not supported',check='external')
    elif whole_memory:
        n=int(re.search('[1248]',name)[0])
        fields=['vs3'] if name.startswith('vs') else ['vd']
        for f in v['fields']:
            if f.get('operand') in fields:
                mask=(n-1)<<f['lsb']
                if mask:v.setdefault('encodingConstraints',[]).append({'mask':hex(mask),'operator':'in','values':[0],'reason':'Whole-register group base must align to its size'})
        rule('vector.whole-memory','vector','整寄存器访存不依赖 vtype/vill；仍需满足寄存器组对齐、EEW 支持和地址要求。',
             'Whole-register memory transfers ignore vtype/vill but require group alignment, EEW support and valid memory access.',
             'v-st-ext.adoc',r'whole register loads and stores do not depend',check='external')
    if re.match(r'^vmv[1248]r\.v$',name):
        n=int(name[3])
        for f in v['fields']:
            if f.get('operand') in {'vd','vs2'} and n>1:
                v.setdefault('encodingConstraints',[]).append({'mask':hex((n-1)<<f['lsb']),'operator':'in','values':[0],'reason':'Whole-register move group bases must align to NREG'})
    zero_start=name.startswith(('vred','vwred','vfred','vfwred')) or name in {'vcpop.m','vfirst.m','vmsbf.m','vmsof.m','vmsif.m','viota.m','vcompress.vm'}
    if zero_start:
        rule('vector.vstart-zero','vector','该指令要求 vstart=0，非零时触发非法指令异常。',
             'This instruction requires vstart=0; a nonzero value raises illegal instruction.',
             'v-st-ext.adoc',r'non-zero `vstart`|`vstart` is non-zero',check='vstart-zero')
    if name.startswith(('vslideup.','vrgather.','vrgatherei16.')) or name in {'vcompress.vm','vmsbf.m','vmsof.m','vmsif.m','viota.m'}:
        rule('vector.overlap','vector','目的寄存器与源组/掩码的重叠限制依指令和当前寄存器组大小判断。',
             'Destination/source/mask overlap restrictions depend on the instruction and current group sizes.',
             'v-st-ext.adoc',r'Any instruction encoding that violates the overlap constraints',check='external')
    # Crypto element-group constraints are global rules, not always repeated in
    # each instruction's Reserved Encodings subsection.
    crypto={'vaesdf','vaesdm','vaesef','vaesem','vaeskf1','vaeskf2','vaesz','vghsh','vgmul','vsha2ch','vsha2cl','vsha2ms','vsm3c','vsm3me','vsm4k','vsm4r'}
    root=name.split('.')[0]
    if root in crypto:
        rule('crypto.element-groups','vector','需满足该算法的 SEW、EGW/EGS、vl/vstart 倍数、LMUL×VLEN 和寄存器组重叠约束。',
             'Algorithm-specific SEW, EGW/EGS, vl/vstart multiples, LMUL×VLEN and group overlap constraints apply.',
             'vector-crypto.adoc',r'LMUL constraints::',check='crypto-groups')
    if root in {'vclmul','vclmulh'}:
        rule('crypto.clmul-sew','vector','向量无进位乘法仅定义 SEW=64。',
             'Vector carry-less multiplication is defined only for SEW=64.',
             'vector-crypto.adoc',r'These instructions are only defined for `SEW`=64',check='sew64')
    if 'bf16' in name:
        rule('bf16.width','vector','BF16 转换/乘累加需使用规范指定的源/目的元素宽度及寄存器组限制。',
             'BF16 conversions/multiply-accumulate require the specified source/destination widths and group restrictions.',
             'bfloat16.adoc',r'The BF16 extensions defined in this specification',check='bf16-width')


def operand_value(f,word):
    raw=(word>>f['lsb'])&((1<<(f['msb']-f['lsb']+1))-1)
    if 'registerMap' in f:return f['registerMap'][raw]
    return raw+f.get('registerOffset',0)


def check_context(name,v,word,context):
    """Check available facts; missing or unimplemented runtime checks stay open.

    extensions, if provided, must contain the caller's expanded ISA capability
    set. This helper does not silently invent a complete extension solver.
    """
    from review_manual_instructions import matches
    rejected=[];pending=[];passed=[]
    def fail(reason):rejected.append(reason)
    xlen=context.get('xlen')
    if xlen not in {32,64}:
        return {'status':'needs-context','rejected':[],'pending':['xlen'],'passed':[]}
    if not matches(v,word,xlen):
        return {'status':'rejected','rejected':['encoding-or-xlen'],'pending':[],'passed':[]}
    passed.append('encoding-and-static-bit-constraints')
    extensions=context.get('extensions')
    if extensions is None:pending.append('expanded-isa-extensions')
    elif not any(set(req)<=set(extensions) for req in v['requires']):fail('required-extensions')
    else:passed.append('required-extensions')
    base=context.get('base')
    if base is None and extensions is not None:
        if 'E' in extensions and 'I' not in extensions:base='E'
        elif 'I' in extensions and 'E' not in extensions:base='I'
    if base not in {None,'I','E'}:fail('base-isa')
    if extensions is not None and {'I','E'}<=set(extensions):fail('conflicting-base-isas')
    registers={f['operand']:operand_value(f,word) for f in v['fields'] if 'registerClass' in f}
    def state(key,identifier):
        value=context.get(key)
        if value is None:pending.append(identifier);return None
        return value
    for rule in v['contextRules']:
        identifier=rule['id'];check=rule['check']
        if check=='external':pending.append(identifier)
        elif check=='encoded':passed.append(identifier)
        elif check=='e-registers':
            if base is None:pending.append('base-isa')
            elif base=='E' and any(operand_value(f,word)>15 for f in v['fields'] if f.get('registerClass')=='integer'):fail(identifier)
            else:passed.append(identifier)
        elif check=='dynamic-rounding':
            rm=(word>>12)&7
            if rm!=7:passed.append(identifier)
            else:
                frm=state('frm',identifier)
                if frm is not None:
                    if type(frm) is not int or frm not in range(5):fail(identifier)
                    else:passed.append(identifier)
        elif check in {'fp-state','vector-state'}:
            key='fs' if check=='fp-state' else 'vs'
            value=state(key,identifier)
            if value is not None:
                if value in {0,'Off'}:fail(identifier)
                elif value not in {1,2,3,'Initial','Clean','Dirty'}:fail(identifier+'.invalid-state-value')
                else:passed.append(identifier)
            # The implemented state bit test does not prove the full virtualized
            # execution environment permits the instruction.
            pending.append(identifier+'.environment-controls')
        elif check=='saved-registers':
            if name=='cm.mvsa01' and registers.get("r1s'")==registers.get("r2s'"):fail(identifier)
            else:passed.append(identifier)
        elif check=='rlist':
            rlist=(word>>4)&15
            if base is None:pending.append('base-isa')
            elif base=='E' and rlist not in {4,5,6}:fail(identifier)
            else:passed.append(identifier)
        elif check=='vtype':
            vill=state('vill',identifier)
            if vill is not None:
                if vill:fail(identifier)
                else:passed.append(identifier+'.vill')
            pending.append(identifier+'.sew-lmul-register-groups')
        elif check=='vstart-zero':
            vstart=state('vstart',identifier)
            if vstart is not None:
                if vstart!=0:fail(identifier)
                else:passed.append(identifier)
        elif check in {'sew64','bf16-width'}:
            sew=state('sew',identifier)
            if sew is not None:
                if sew!=(64 if check=='sew64' else 16):fail(identifier)
                else:passed.append(identifier)
        elif check=='crypto-groups':
            sew=state('sew',identifier+'.sew')
            root=name.split('.')[0]
            sha=root.startswith('vsha2')
            if sew is not None:
                allowed={32,64} if sha else {32}
                if sew not in allowed:fail(identifier+'.sew')
                if sha and sew==64:
                    if extensions is None:pending.append(identifier+'.sha512-extension')
                    elif 'Zvknhb' not in extensions:fail(identifier+'.sha512-extension')
            egs=8 if root.startswith('vsm3') else 4
            egw=(4*sew if sew in {32,64} else None) if sha else 256 if egs==8 else 128
            for key in ['vl','vstart']:
                value=state(key,identifier+'.'+key)
                if value is not None and (type(value) is not int or value<0 or value%egs):fail(identifier+'.'+key)
            lmul=state('lmul',identifier+'.lmul');vlen=state('vlen',identifier+'.vlen')
            if lmul is not None and vlen is not None and egw is not None:
                if lmul not in {0.125,0.25,0.5,1,2,4,8} or type(vlen) is not int or vlen<1 or vlen&(vlen-1):fail(identifier+'.invalid-vector-configuration')
                elif lmul*vlen<egw:fail(identifier+'.capacity')
                else:passed.append(identifier+'.capacity')
            pending.append(identifier+'.register-group-overlap')
        else:
            raise ValueError('Unhandled context rule '+check)
    return {'status':'rejected' if rejected else 'needs-context' if pending else 'checks-passed',
            'rejected':sorted(set(rejected)),'pending':sorted(set(pending)),'passed':sorted(set(passed))}


def representative_word(v,xlen):
    """Find a witness for every static encoding rule without searching operands."""
    from review_manual_instructions import matches
    word=int(v['match'],16);fixed=int(v['mask'],16)
    if matches(v,word,xlen):return word
    union=0
    for c in v.get('encodingConstraints',[]):
        if 'xlens' not in c or xlen in c['xlens']:union|=int(c['mask'],16)
    positions=[bit for bit in range(v['length']) if (union&~fixed)>>bit&1]
    if len(positions)>16:raise ValueError('Constraint witness search unexpectedly large')
    for value in range(1<<len(positions)):
        candidate=word|sum(((value>>j)&1)<<bit for j,bit in enumerate(positions))
        if matches(v,candidate,xlen):return candidate
    raise ValueError('Unsatisfiable static constraints')


def scan_catalog(write_report=True):
    root=Path(__file__).resolve().parents[1]
    directory=root/'app/src/main/assets/inst'
    rows=[];categories=Counter();checks=Counter();samples=0;register_probes=0;invalid_bit_probes=0
    for path in sorted(directory.glob('manual-*.json')):
        a=json.loads(path.read_text(encoding='utf-8'))
        for index,v in enumerate(a['encodings']):
            for rule in v['contextRules']:
                categories[rule['category']]+=1;checks[rule['check']]+=1
            states=[]
            for xlen in v['xlens']:
                word=representative_word(v,xlen)
                result=check_context(a['name'],v,word,{'xlen':xlen})
                if result['status']=='rejected':raise ValueError('Context-free witness was incorrectly rejected: '+a['name'])
                from review_manual_instructions import matches
                for f in v['fields']:
                    if 'registerClass' not in f:continue
                    mask=((1<<(f['msb']-f['lsb']+1))-1)<<f['lsb']
                    for raw in range(1<<(f['msb']-f['lsb']+1)):
                        probe=(word&~mask)|(raw<<f['lsb'])
                        if not matches(v,probe,xlen):continue
                        outcome=check_context(a['name'],v,probe,{'xlen':xlen,'base':'E'})
                        if f['registerClass']=='integer' and operand_value(f,probe)>15 and 'base.e.registers' not in outcome['rejected']:
                            raise ValueError('Missing E-register rejection: '+a['name'])
                        if f['registerClass']!='integer' and 'base.e.registers' in outcome['rejected']:
                            raise ValueError('FP/vector register incorrectly limited by E: '+a['name'])
                        register_probes+=1
                for c in v.get('encodingConstraints',[]):
                    if 'xlens' in c and xlen not in c['xlens']:continue
                    mask=int(c['mask'],16)
                    positions=[bit for bit in range(v['length']) if mask>>bit&1]
                    for raw in range(1<<len(positions)):
                        value=sum(((raw>>j)&1)<<bit for j,bit in enumerate(positions))
                        if (value in c['values'])==(c['operator']=='in'):continue
                        probe=(word&~mask)|value
                        if check_context(a['name'],v,probe,{'xlen':xlen})['status']!='rejected':
                            raise ValueError('Reserved encoding accepted: '+a['name'])
                        invalid_bit_probes+=1
                states.append({'xlen':xlen,'word':f'0x{word:0{v["length"]//4}x}','result':result})
                samples+=1
            rows.append({'name':a['name'],'encodingIndex':index,'rules':[r['id'] for r in v['contextRules']],
                         'encodedConstraints':len(v.get('encodingConstraints',[])),'samples':states})
    report={'instructions':len(list(directory.glob('manual-*.json'))),'encodingVariants':len(rows),
            'xlenWitnesses':samples,'registerAssignmentsChecked':register_probes,'reservedBitAssignmentsRejected':invalid_bit_probes,
            'rulesByCategory':dict(categories),'rulesByChecker':dict(checks),
            'errors':[],'scope':'Data and contextual-condition audit; unresolved runtime checks remain explicit.',
            'records':rows}
    if write_report:
        output=root/'docs/data/instruction-context-review.json'
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return report


if __name__=='__main__':
    report=scan_catalog()
    print(json.dumps({k:v for k,v in report.items() if k!='records'},ensure_ascii=False))
