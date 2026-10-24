"""Source-backed regression cases, rejection tests, and full-catalog review."""
from copy import deepcopy
import json
import unittest

import import_manual_instructions as importer
from instruction_sources import wave_fields,table_rows
import review_manual_instructions as auditor
from instruction_context import check_context,scan_catalog


class ManualInstructionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data={p.name[len('manual-'):-len('.json')]:json.loads(p.read_text(encoding='utf-8')) for p in (auditor.ASSETS/'inst').glob('manual-*.json')}

    def accepts(self,name,word,xlen=64):
        return any(auditor.matches(v,word,xlen) for v in self.data[name]['encodings'])

    def test_full_catalog_all_fields_and_source_hashes(self):
        # Git stores text as LF: published fingerprints must survive a fresh checkout.
        for path in [auditor.ASSETS/'riscv-instructions.json', *(auditor.ASSETS/'inst').glob('manual-*.json')]:
            with self.subTest(file=path.name):self.assertNotIn(b'\r',path.read_bytes())
        report=auditor.review(write_report=False)
        self.assertEqual([],report['errors'])
        self.assertGreaterEqual(report['instructions'],1200)
        self.assertGreater(report['encodingFields'],8000)
        self.assertEqual(68,len(report['overlaps']))

    def test_fixed_machine_words_from_manual_formats(self):
        # Words are specified independently of the generator; operands are zero
        # where legal. Compressed reserved-zero immediates use nonzero samples.
        cases=[('add',0x00b50533,64),('addi',0x00100093,32),('sub',0x40b50533,64),
               ('jal',0x0000006f,32),('jalr',0x00008067,64),('beq',0x00000063,32),
               ('ecall',0x00000073,32),('ebreak',0x00100073,64),('mret',0x30200073,64),
               ('sret',0x10200073,32),('wfi',0x10500073,64),('sctrclr',0x10400073,32),
               ('csrrw',0x300110f3,64),('fence.i',0x0000100f,32),('pause',0x0100000f,64),
               ('lr.w',0x1000202f,32),('amocas.w',0x2800202f,64),('amoadd.b',0x0000002f,64),
               ('fadd.s',0x00000053,32),('fli.s',0xf0100053,64),('fround.d',0x42400053,64),
               ('fcvtmod.w.d',0xc2801053,32),('sfence.vma',0x12000073,64),
               ('c.nop',0x0001,32),('c.ebreak',0x9002,64),('c.addi16sp',0x6141,32),
               ('cm.jt',0xa002,64),('cm.jalt',0xa082,64),('c.mop.5',0x6281,32),
               ('vadd.vv',0x02000057,32),('vadd.vx',0x02004057,64),('vid.v',0x5208a057,64),
               ('vle8.v',0x02050087,64),('vsetvl',0x80007057,32),('vaesdf.vs',0xa600a077,64)]
        for n,word,xlen in cases:
            with self.subTest(name=n):self.assertTrue(self.accepts(n,word,xlen))

    def test_rv64_integer_variants_do_not_leak_to_rv32(self):
        for n in ['addiw','slliw','addw','c.addiw','c.addw','c.subw']:
            with self.subTest(name=n):self.assertTrue(all(v['xlens']==[64] for v in self.data[n]['encodings']))
        variants=self.data['ld']['encodings']
        self.assertTrue(any(v['xlens']==[32] and ['Zilsd'] in v['requires'] for v in variants))
        self.assertTrue(any(v['xlens']==[64] and ['I'] in v['requires'] for v in variants))

    def test_shift_width_and_compressed_legality(self):
        self.assertFalse(self.accepts('slli',0x02001013,32))
        self.assertTrue(self.accepts('slli',0x02001013,64))
        self.assertFalse(self.accepts('c.slli',0x1082,32))
        self.assertTrue(self.accepts('c.slli',0x1082,64))
        self.assertFalse(self.accepts('c.addi16sp',0x6101))
        self.assertFalse(self.accepts('c.lui',0x6141))
        self.assertFalse(self.accepts('c.jr',0x8002))
        self.assertFalse(self.accepts('c.mv',0x8082))
        self.assertFalse(self.accepts('cm.jalt',0xa002))
        self.assertFalse(self.accepts('cm.jt',0xa082))

    def test_saved_register_mapping_is_not_compact_x8_to_x15(self):
        for name in ['cm.mvsa01','cm.mva01s']:
            for f in self.data[name]['encodings'][0]['fields']:
                if f.get('operand') in {"r1s'","r2s'"}:
                    self.assertEqual([8,9,18,19,20,21,22,23],f['registerMap'])
                    self.assertNotIn('registerOffset',f)

    def test_raw_manual_typo_is_corroborated_not_a_wildcard(self):
        v=self.data['vaesdf.vs']['encodings'][0]
        self.assertEqual(0xfe0ff07f,int(v['mask'],16))
        self.assertEqual(0xa600a077,int(v['match'],16))
        self.assertTrue(any(s['method']=='corroborate-stray-backtick' for s in v['sources']))
        self.assertFalse(self.accepts('vaesdf.vs',0xa200a077))

    def test_zfinx_has_distinct_register_semantics(self):
        variants=self.data['fadd.s']['encodings']
        self.assertTrue(any(v.get('registerFile')=='integer' and ['Zfinx'] in v['requires'] for v in variants))
        self.assertFalse(any(v.get('registerFile')=='integer' for v in self.data['flw']['encodings']))
        self.assertIn(['Zba','Zcb'],self.data['c.zext.w']['encodings'][0]['requires'])

    def test_vid_rejects_reserved_nonzero_vs2(self):
        self.assertFalse(self.accepts('vid.v',0x5218a057))

    def test_word_summaries_truncate_inputs_before_right_shift_and_division(self):
        for name in ['srlw','srliw']:
            self.assertIn('low32(X(rs1))',self.data[name]['summary'])
        for name in ['sraw','sraiw','divw','remw']:
            self.assertIn('signed32(X(rs1))',self.data[name]['summary'])
        for name in ['divuw','remuw']:
            self.assertIn('unsigned32(X(rs1))',self.data[name]['summary'])

    def test_masks_ranges_sources_and_translations_are_not_silently_accepted(self):
        for mutation in ['mask','range','source','extension','translation','operand']:
            a=deepcopy(self.data['add'])
            v=a['encodings'][0]
            if mutation=='mask':v['mask']='0x00000000'
            elif mutation=='range':v['fields'][0]['msb']=32
            elif mutation=='source':a['semanticSources'][0]['sha256']='0'*64
            elif mutation=='extension':v['requires']=[['Zeros']]
            elif mutation=='translation':a['summaryEn']='未翻译'
            else:next(f for f in v['fields'] if 'operand' in f)['operand']='101001`'
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):auditor.check_instruction(a,'manual-add.json',{})

    def test_untrusted_diagram_is_not_executed(self):
        with self.assertRaises(ValueError):wave_fields("{reg:[{bits:7,name:require('unsafe')}]}")

    def variant(self,name,extension=None,xlen=64):
        return next(v for v in self.data[name]['encodings'] if xlen in v['xlens'] and (extension is None or any(extension in req for req in v['requires'])))

    def test_context_scan_covers_every_variant_and_register_assignment(self):
        report=scan_catalog(write_report=False)
        self.assertEqual(1323,report['encodingVariants'])
        self.assertEqual(2497,report['xlenWitnesses'])
        self.assertGreater(report['registerAssignmentsChecked'],50000)
        self.assertGreater(report['reservedBitAssignmentsRejected'],500)
        self.assertEqual([],report['errors'])

    def test_e_base_applies_to_extension_gprs_but_not_fprs(self):
        for name,word in [('mul',0x02000833),('csrrw',0x30001873),('fcvt.w.s',0xc0000853)]:
            v=self.variant(name)
            result=check_context(name,v,word,{'xlen':64,'base':'E'})
            with self.subTest(name=name):self.assertIn('base.e.registers',result['rejected'])
        v=self.variant('fadd.s','F')
        result=check_context('fadd.s',v,0x00000fd3,{'xlen':64,'base':'E'})
        self.assertNotIn('base.e.registers',result['rejected'])
        v=self.variant('fli.s')
        self.assertEqual('float',next(f for f in v['fields'] if f.get('operand')=='rd')['registerClass'])
        self.assertEqual('float',next(f for f in self.variant('vfwmaccbf16.vf')['fields'] if f.get('operand')=='rs1')['registerClass'])

    def test_rounding_modes_and_unknown_frm_are_not_silently_accepted(self):
        v=self.variant('fadd.s','F')
        for rm in [5,6]:self.assertFalse(auditor.matches(v,0x53|(rm<<12),64))
        dynamic=0x7053
        self.assertIn('fp.dynamic-rounding',check_context('fadd.s',v,dynamic,{'xlen':64})['pending'])
        self.assertIn('fp.dynamic-rounding',check_context('fadd.s',v,dynamic,{'xlen':64,'frm':7})['rejected'])
        self.assertNotIn('fp.dynamic-rounding',check_context('fadd.s',v,dynamic,{'xlen':64,'frm':0})['pending'])

    def test_pair_restrictions_have_correct_width_and_operand_scope(self):
        self.assertFalse(auditor.matches(self.variant('ld','Zilsd',32),0x3083,32))
        self.assertTrue(auditor.matches(self.variant('ld','I',64),0x3083,64))
        self.assertFalse(auditor.matches(self.variant('amocas.d',xlen=32),0x280030af,32))
        self.assertTrue(auditor.matches(self.variant('amocas.d'),0x280030af,64))
        self.assertFalse(auditor.matches(self.variant('fadd.d','Zdinx',32),0x020000d3,32))
        self.assertTrue(auditor.matches(self.variant('fadd.d','D',32),0x020000d3,32))
        # FCVT.S.D has a scalar single-width destination and a double source.
        v=self.variant('fcvt.s.d','Zdinx',32)
        self.assertTrue(auditor.matches(v,0x401000d3,32))
        self.assertFalse(auditor.matches(v,0x40108053,32))

    def test_whole_register_transfers_and_vset_ignore_old_vill(self):
        for name,word in [('vsetvli',0x7057),('vl2re8.v',0x22800007)]:
            v=self.variant(name)
            result=check_context(name,v,word,{'xlen':64,'vill':True})
            with self.subTest(name=name):self.assertNotIn('vector.vtype',result['rejected'])
            self.assertFalse(any(r['id']=='vector.vtype' for r in v['contextRules']))
        result=check_context('vadd.vv',self.variant('vadd.vv'),0x2000057,{'xlen':64,'vill':True})
        self.assertIn('vector.vtype',result['rejected'])
        self.assertFalse(auditor.matches(self.variant('vl2re8.v'),0x22800087,64))
        self.assertFalse(auditor.matches(self.variant('vmv2r.v'),0x9e00b0d7,64))

    def test_vector_vstart_crypto_groups_and_bf16_width(self):
        name='vredsum.vs';v=self.variant(name)
        self.assertIn('vector.vstart-zero',check_context(name,v,int(v['match'],16),{'xlen':64,'vstart':1})['rejected'])
        name='vaesdf.vs';v=self.variant(name)
        result=check_context(name,v,0xa600a077,{'xlen':64,'sew':32,'vl':6,'vstart':0,'lmul':1,'vlen':64})
        self.assertIn('crypto.element-groups.vl',result['rejected'])
        self.assertIn('crypto.element-groups.capacity',result['rejected'])
        name='vsha2ch.vv';v=self.variant(name)
        result=check_context(name,v,int(v['match'],16),{'xlen':64,'sew':64,'extensions':{'Zvknha'}})
        self.assertIn('crypto.element-groups.sha512-extension',result['rejected'])
        name='vfwmaccbf16.vf';v=self.variant(name)
        self.assertIn('bf16.width',check_context(name,v,int(v['match'],16),{'xlen':64,'sew':32})['rejected'])

    def test_context_dependencies_cannot_be_mistaken_for_a_full_legal_result(self):
        self.assertIn(['Zcmop','Zicfiss'],self.variant('c.sspush')['requires'])
        self.assertTrue(any('Zfbfmin' in req for req in self.variant('flh')['requires']))
        v=self.variant('mret')
        result=check_context('mret',v,0x30200073,{'xlen':64,'extensions':{'I'},'base':'I'})
        self.assertEqual('needs-context',result['status'])
        self.assertIn('privileged.controls',result['pending'])
        self.assertFalse(auditor.matches(self.variant('add'),0x100000033,64))

    def test_rebuild_matches_every_stored_instruction(self):
        rebuilt=importer.build()
        self.assertEqual(set(self.data),set(rebuilt))
        for name,a in rebuilt.items():
            a['encodings'].sort(key=lambda v:(v['length'],v['xlens'],v['match'],v['requires']))
            with self.subTest(name=name):self.assertEqual(self.data[name],a)


if __name__=='__main__':unittest.main()
