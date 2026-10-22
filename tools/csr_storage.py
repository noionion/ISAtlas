"""Store one CSR per JSON file, with a metadata manifest outside csr/."""
import hashlib
import json
import re
from pathlib import Path


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def deduplicate(registers):
    unique = {}
    for reg in registers:
        previous = unique.get(reg["id"])
        if previous is not None and previous != reg:
            raise ValueError(f"Conflicting CSR identity: {reg['id']}")
        unique[reg["id"]] = reg
    return list(unique.values())


def write_catalog(assets, pack):
    assets = Path(assets)
    unique = deduplicate(pack["registers"])
    payloads = {}
    for reg in sorted(unique, key=lambda r: (r.get("namespace", pack["namespace"]), r["locator"]["address"], r["name"])):
        name = f"{reg.get('namespace') or pack['namespace']}-{reg['name']}.json".lower()
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*\.json", name):
            raise ValueError(f"Unsafe CSR filename: {name}")
        if name in payloads:
            raise ValueError(f"CSR filename collision: {name}")
        payloads[name] = encoded(reg)
    manifest = {key: value for key, value in pack.items() if key != "registers"}
    manifest["files"] = list(payloads)
    manifest_path = assets / "riscv-catalog.json"
    previous_files = []
    if manifest_path.exists():
        previous_files = json.loads(manifest_path.read_text(encoding="utf-8")).get("files", [])
    directory = assets / "csr"
    directory.mkdir(parents=True, exist_ok=True)
    for name, content in payloads.items():
        (directory / name).write_bytes(content)
    manifest_path.write_bytes(encoded(manifest))
    # Remove only formerly indexed files, never arbitrary files in the directory.
    for name in set(previous_files) - payloads.keys():
        target = (directory / name).resolve()
        if target.parent != directory.resolve():
            raise ValueError(f"Unsafe previous CSR path: {name}")
        target.unlink(missing_ok=True)
    return len(pack["registers"]) - len(unique)


def read_catalog(assets):
    assets = Path(assets)
    manifest = json.loads((assets / "riscv-catalog.json").read_text(encoding="utf-8"))
    files = manifest.pop("files")
    manifest["registers"] = [json.loads((assets / "csr" / name).read_text(encoding="utf-8")) for name in files]
    return manifest


def catalog_sha256(assets):
    assets = Path(assets)
    manifest_bytes = (assets / "riscv-catalog.json").read_bytes()
    digest = hashlib.sha256(manifest_bytes)
    for name in json.loads(manifest_bytes)["files"]:
        digest.update(name.encode("utf-8") + b"\0")
        digest.update((assets / "csr" / name).read_bytes())
    return digest.hexdigest()


if __name__ == "__main__":
    assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
    pack = json.loads((assets / "riscv-catalog.json").read_text(encoding="utf-8"))
    if "files" in pack:
        pack = read_catalog(assets)
    removed = write_catalog(assets, pack)
    assert read_catalog(assets) == dict(pack, registers=sorted({r["id"]: r for r in pack["registers"]}.values(), key=lambda r: (r.get("namespace", pack["namespace"]), r["locator"]["address"], r["name"])))
    print(f"CSR files: {len(pack['registers']) - removed}; identical duplicates removed: {removed}; SHA-256: {catalog_sha256(assets)}")
