"""Reproducible, source-separated built-in packages and reversible CSR overlays.

These archives are trusted only through the application asset index. They are not
the untrusted local-import format and cannot be imported to replace built-ins.
"""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
DATA = ROOT / "data/official-packages"
ORDER = ("manual", "aia", "debug", "spmp")


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def key(value, kind):
    if kind == "fields":
        return value["name"]
    if kind == "sources":
        return value["namespace"]
    return "|".join([value.get("view", ""), value["label"], ",".join(f"{b['xlen']}:{b['msb']}:{b['lsb']}" for b in value["layouts"])])


def diff(before, after, name=""):
    if before == after:
        return None
    if isinstance(before, dict) and isinstance(after, dict):
        changes = {k: diff(before[k], v, k) if k in before else diff([], v, k) if k in ("sources", "extensions", "fields", "regions") else {"set": v} for k, v in after.items()}
        return {"object": {k: v for k, v in changes.items() if v is not None}, "remove": sorted(before.keys() - after.keys())}
    if name in ("fields", "regions", "sources") and isinstance(before, list) and isinstance(after, list):
        left, right = ({key(v, name): v for v in items} for items in (before, after))
        assert len(left) == len(before) and len(right) == len(after), name
        return {"list": name, "remove": [k for k in left if k not in right],
                "items": [{"key": k, "patch": diff(left[k], v) if k in left else {"set": v}} for k, v in right.items() if k not in left or left[k] != v]}
    if name == "extensions" and isinstance(before, list) and isinstance(after, list):
        return {"add": [v for v in after if v not in before], "remove": [v for v in before if v not in after]}
    if isinstance(before, str) and isinstance(after, str) and after.startswith(before) and (before or name in ("notes", "typeRule")):
        return {"append": after[len(before):], "join": name in ("notes", "typeRule")}
    return {"set": after}


def apply(value, patch):
    if "set" in patch:
        return deepcopy(patch["set"])
    if "append" in patch:
        if patch.get("join"):
            return " ".join(part.strip() for part in ((value or ""), patch["append"]) if part.strip())
        return (value or "") + patch["append"]
    if "object" in patch:
        result = deepcopy(value or {})
        for k in patch["remove"]:
            result.pop(k, None)
        for k, change in patch["object"].items():
            result[k] = apply(result.get(k), change)
        return result
    if "list" in patch:
        result = {key(v, patch["list"]): deepcopy(v) for v in value or []}
        for k in patch["remove"]:
            result.pop(k, None)
        for item in patch["items"]:
            result[item["key"]] = apply(result.get(item["key"]), item["patch"])
        return list(result.values())
    return [v for v in value if v not in patch["remove"]] + [v for v in patch["add"] if v not in value]


def archive(source, destination):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(source).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                z.writestr(info, path.read_bytes())


def refresh():
    entries = []
    (ASSETS / "packages").mkdir(exist_ok=True)
    for ns in ORDER:
        folder = DATA / ns
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
        target = ASSETS / "packages" / f"{ns}.zip"
        archive(folder, target)
        entries.append(dict(id=ns, name=manifest["name"], version=manifest["version"], source=manifest["source"],
                            license=manifest["license"], required=ns == "manual", csrCount=len(manifest["csr"]),
                            supplementCount=len(manifest["overlays"]), file=f"packages/{ns}.zip",
                            sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    (ASSETS / "riscv-packages.json").write_bytes(encoded(dict(schemaVersion=1, packages=entries)))


def export(metadata, base, layers):
    from import_aia import ATTRIBUTION as aia_source
    from import_debug import ATTRIBUTION as debug_source
    from import_spmp import ATTRIBUTION as spmp_source
    sources = {"manual": dict(namespace="manual", **metadata["source"], license="CC-BY-4.0"),
               "aia": aia_source, "debug": debug_source, "spmp": spmp_source}
    versions = dict(manual="20250508", aia="20250312", debug="1.0", spmp="1.0")
    base_map = {r["id"]: r for r in base}
    for ns in ORDER:
        folder = DATA / ns
        folder.mkdir(parents=True, exist_ok=True)
        # Only replace files generated by the previous package manifest.
        previous = folder / "manifest.json"
        if previous.exists():
            old = json.loads(previous.read_text(encoding="utf-8"))
            for name in old["csr"] + old["overlays"]:
                path = (folder / name).resolve()
                assert path.is_relative_to(folder.resolve())
                path.unlink(missing_ok=True)
        regs = base if ns == "manual" else layers[ns]
        additions, overlays = [], []
        for reg in regs:
            if ns == "manual" or reg["id"] not in base_map:
                name = f"csr/{reg['namespace']}-{reg['name']}.json"
                value = reg
                additions.append(name)
            else:
                patch = diff(base_map[reg["id"]], reg)
                if patch is None:
                    continue
                name = f"overlays/{reg['name']}.json"
                value = dict(id=reg["id"], patch=patch)
                overlays.append(name)
            path = folder / name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(encoded(value))
        manifest = dict(schemaVersion=1, id=ns, name=sources[ns]["title"], version=versions[ns],
                        source=sources[ns], license="CC-BY-4.0", csr=additions, overlays=overlays)
        if ns == "manual":
            manifest["catalog"] = {k: v for k, v in metadata.items() if k not in ("registers", "files")}
        (folder / "manifest.json").write_bytes(encoded(manifest))
        (folder / "LICENSE.txt").write_bytes((ASSETS / "licenses/CC-BY-4.0.txt").read_bytes())
    refresh()


def read_catalog(assets=ASSETS, enabled=("aia", "debug", "spmp")):
    index = json.loads((Path(assets) / "riscv-packages.json").read_text(encoding="utf-8"))
    regs, metadata = {}, None
    for entry in index["packages"]:
        if not entry["required"] and entry["id"] not in enabled:
            continue
        path = Path(assets) / entry["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"]
        with zipfile.ZipFile(path) as z:
            m = json.loads(z.read("manifest.json"))
            if entry["required"]:
                metadata = m["catalog"]
            for name in m["csr"]:
                r = json.loads(z.read(name))
                assert r["id"] not in regs
                regs[r["id"]] = r
            for name in m["overlays"]:
                overlay = json.loads(z.read(name))
                regs[overlay["id"]] = apply(regs[overlay["id"]], overlay["patch"])
    return dict(metadata, registers=sorted(regs.values(), key=lambda r: (r["namespace"], r["locator"]["address"], r["name"])))


if __name__ == "__main__":
    refresh()
