import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_local import build


class LocalPackageBuilderTests(unittest.TestCase):
    def test_reproducible_and_kind_specific_outputs(self):
        root=Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            a,b=Path(directory)/'a.zip',Path(directory)/'b.zip'
            build(root/'examples/local-packages/demo',a)
            build(root/'examples/local-packages/demo',b)
            self.assertEqual(a.read_bytes(),b.read_bytes())
            build(root/'examples/local-packages/demo',b,'instructions')
            with zipfile.ZipFile(b) as z:
                m=json.loads(z.read('manifest.json'))
                self.assertEqual([],m['csr'])
                self.assertEqual(set(z.namelist())-{'manifest.json'},set(m['hashes']))

    def test_rejects_paths_outside_source_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'manifest.json').write_text(json.dumps(dict(csr=['../outside.json'])),encoding='utf-8')
            with self.assertRaises(ValueError):
                build(root,root/'bad.zip')


if __name__=='__main__':
    unittest.main()
