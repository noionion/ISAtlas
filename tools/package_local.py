"""Build reproducible offline packages. No network, credentials or upstream execution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def build(folder, output, kind="combined"):
    folder=Path(folder).resolve()
    manifest=json.loads((folder/"manifest.json").read_text(encoding="utf-8"))
    if kind=="csr":
        manifest["instructions"]=[]
    elif kind=="instructions":
        manifest["csr"]=[]
    payload={}
    for path in manifest.get("csr",[])+manifest.get("instructions",[]):
        if not re.fullmatch(r"(?:csr|inst)/[a-z0-9][a-z0-9._-]{0,95}\.json",path):
            raise ValueError("Invalid package entry path")
        source=(folder/path).resolve()
        if not source.is_relative_to(folder):
            raise ValueError("Package files must stay inside the source folder")
        raw=source.read_bytes()
        if len(raw)>1024*1024:
            raise ValueError("Package entry exceeds 1 MiB")
        json.loads(raw.decode("utf-8"))
        payload[path]=raw
    if not payload or len(payload)>2048 or sum(map(len,payload.values()))>8*1024*1024:
        raise ValueError("Empty or oversized package")
    manifest["hashes"]={name:hashlib.sha256(raw).hexdigest() for name,raw in sorted(payload.items())}
    payload["manifest.json"]=(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n").encode("utf-8")
    output=Path(output)
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,"w",compression=zipfile.ZIP_DEFLATED) as archive:
        for name,raw in sorted(payload.items()):
            info=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o100644<<16
            archive.writestr(info,raw)
    if output.stat().st_size>16*1024*1024:
        raise ValueError("ZIP exceeds 16 MiB")
    return output


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder",type=Path)
    parser.add_argument("output",type=Path)
    parser.add_argument("--kind",choices=("csr","instructions","combined"),default="combined")
    args=parser.parse_args()
    print(build(args.folder,args.output,args.kind))
