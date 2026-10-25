"""Import CSR facts from the pinned official manual; do not execute EDN or AsciiDoc.

Address tables, WaveDrom bit counts, and Bytefield's explicit width labels are
parsed separately. Bytefield :span is drawing geometry, never a bit width.
Unsupported figures remain address-only and are listed in the local report.
"""
import ast
from collections import Counter
import html
import json
from pathlib import Path
import re
import subprocess

from csr_storage import read_catalog, write_catalog, catalog_sha256
from manual_field_semantics import enrich
from manual_extension_dependencies import dependencies

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
UPSTREAM = ROOT / "upstream/riscv-isa-manual"
REVISION = "584390d9d872835b1bc45ab9ea946b56db3ad485"
REPOSITORY = "https://github.com/riscv/riscv-isa-manual"
SOURCE = UPSTREAM / "src"


def clean(text):
    text = html.unescape(text).replace("`", "").replace("*", "")
    return re.sub(r"\s+", " ", text).strip().rstrip(".")


def url(path):
    return f"{REPOSITORY}/blob/{REVISION}/src/{path}"


def addresses(text):
    """Read the five 4-column CSR allocation tables, including printed ellipses."""
    text = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("//"))
    rows = []
    for table in re.findall(r"\|===\n(.*?)\n\|===", text, re.S):
        if not table.startswith("|Number |Privilege |Name |Description"):
            continue
        table = re.sub(r"^4\+.*$", "", table, flags=re.M)
        cells = [cell.strip() for cell in table.split("|")[1:]]
        assert len(cells) % 4 == 0
        for i in range(4, len(cells), 4):
            columns = [[line.strip().rstrip("+").strip() for line in cell.splitlines() if line.strip()]
                       for cell in cells[i:i + 4]]
            assert len(set(map(len, columns))) == 1, columns
            entries = list(zip(*columns))
            for j, (address, privilege, name, description) in enumerate(entries):
                if not re.fullmatch(r"`0x[0-9A-Fa-f]{3}`", address):
                    assert "8942" in name or "8943" in name, entries[j]
                    before, after = entries[j - 1], entries[j + 1]
                    left = re.fullmatch(r"`(.*?)(\d+)(h?)`", before[2])
                    right = re.fullmatch(r"`(.*?)(\d+)(h?)`", after[2])
                    assert left and right and (left[1], left[3]) == (right[1], right[3])
                    start, stop = int(left[2]), int(right[2])
                    start_address, stop_address = int(before[0][1:-1], 16), int(after[0][1:-1], 16)
                    assert stop_address - start_address == stop - start
                    for number in range(start + 1, stop):
                        expanded_name = f"{left[1]}{number}{left[3]}"
                        description = clean(before[3]).replace(before[2].strip("`"), expanded_name)
                        rows.append((start_address + number - start, before[1], expanded_name, description))
                    continue
                rows.append((int(address[1:-1], 16), privilege, name.strip("`"), clean(description)))
    assert len({name for _, _, name, _ in rows}) == len(rows)
    return rows


def arithmetic(text, xlen):
    text = re.sub(r"(?:[MSHV]*XLEN)", str(xlen), text.strip())
    def value(node):
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub)):
            return value(node.left) + (value(node.right) if isinstance(node.op, ast.Add) else -value(node.right))
        raise ValueError(f"Unsupported width expression: {text}")
    return value(ast.parse(text, mode="eval").body)


def wave_fields(text):
    start = re.search(r"\breg\s*:\s*\[", text)
    if not start:
        return []
    depth, quote, end = 1, None, start.end()
    while depth:
        char = text[end]
        if quote:
            if char == quote and text[end - 1] != "\\":
                quote = None
        elif char in ("'", '"'):
            quote = char
        elif char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
        end += 1
    body = text[start.end():end - 1]
    fields, offset = [], 0
    for item in re.findall(r"\{([^{}]*)\}", body):
        width = re.search(r"\bbits\s*:\s*(\d+)", item)
        name = re.search(r"\bname\s*:\s*['\"]([^'\"]*)['\"]", item)
        if not width:
            raise ValueError("WaveDrom field lacks literal bit count")
        bits = int(width[1])
        fields.append((name[1] if name else "", offset + bits - 1, offset))
        offset += bits
    return fields


def edn_forms(text):
    """Read only EDN literals and unevaluated lists, never run upstream forms."""
    text = re.sub(r"(?m)^\[bytefield\].*\n|^----\s*$", "", text)
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|[()\[\]{}]|[^\s,()\[\]{}]+', text)
    position = 0
    def read():
        nonlocal position
        token = tokens[position]
        position += 1
        if token in ("(", "[", "{"):
            end = {"(": ")", "[": "]", "{": "}"}[token]
            children = []
            while tokens[position] != end:
                children.append(read())
            position += 1
            return dict(zip(children[::2], children[1::2])) if token == "{" else children
        if token.startswith('"'):
            return json.loads(token)
        if token == "nil":
            return None
        return int(token) if token.isdigit() else token
    forms = []
    while position < len(tokens):
        forms.append(read())
    return forms


def byte_fields(text, xlen):
    forms = edn_forms(text)
    row_size = next(form[2] for form in forms if isinstance(form, list) and form[:2] == ["def", "boxes-per-row"])
    rows, current, position = [], [], 0
    for form in forms:
        if not isinstance(form, list) or not form or form[0] != "draw-box":
            continue
        label = form[1]
        if isinstance(label, list):
            if label[0] != "text" or not isinstance(label[1], str):
                raise ValueError("Unsupported Bytefield label")
            label = label[1]
        style = form[2] if len(form) > 2 and isinstance(form[2], dict) else {}
        span = style.get(":span", 1)
        if not isinstance(span, int):
            raise ValueError("Nonliteral drawing span")
        current.append((position, position + span, label, style))
        position += span
        if position == row_size:
            rows.append(current)
            current, position = [], 0
        elif position > row_size:
            raise ValueError("Bytefield drawing row overflow")
    output = []
    for fields, widths in zip(rows, rows[1:]):
        # A number of figures pad each side with empty drawing cells.
        fields = list(fields)
        widths = list(widths)
        for row in (fields, widths):
            while row and row[0][2] is None:
                row.pop(0)
            while row and row[-1][2] is None:
                row.pop()
        if not any(label and style.get(":borders") != {} for _, _, label, style in fields):
            continue
        if not widths or not all(label and style.get(":borders") == {} for _, _, label, style in widths):
            continue
        try:
            bit_widths = [arithmetic(label, xlen) for _, _, label, _ in widths]
        except (ValueError, SyntaxError):
            continue
        if any(width <= 0 for width in bit_widths) or sum(bit_widths) > xlen:
            continue
        # Match actual width labels to visual field extents. Split annotation boxes
        # (e.g. BASE and WARL) share one width cell; :span alone is never decoded.
        high = sum(bit_widths) - 1
        decoded = []
        for (start, end, _, _), width in zip(widths, bit_widths):
            members = [cell for cell in fields if cell[0] >= start and cell[1] <= end]
            if not members or members[0][0] != start or members[-1][1] != end:
                decoded = []
                break
            labels = [str(label).strip() for _, _, label, _ in members if label is not None]
            label = " ".join(labels)
            if label == "..." and any(str(cell[2]).startswith("HPM") for cell in fields):
                decoded.extend((f"HPM{bit}", bit, bit) for bit in range(high, high - width, -1))
            else:
                decoded.append((label, high, high - width + 1))
            high -= width
        output.extend(decoded)
    return output


def field_name(label, csr):
    name = re.sub(r"\([^)]*\)|\[[^]]*\]", "", label).strip()
    name = re.sub(r"[^A-Za-z0-9]", "", name).upper()
    if name == "INTERRUPT":
        return "INT", ["Interrupt"]
    if name == "EXCEPTIONCODE":
        return "CODE", ["ExceptionCode"]
    if name == csr.upper() and name.endswith("SCRATCH"):
        return "SCRATCH", ["Scratch", csr]
    return name, []


def add_figure(reg, triples, xlen, path):
    used = {bit for field in reg["fields"] + reg["regions"] for loc in field["layouts"] if loc["xlen"] == xlen for bit in range(loc["lsb"], loc["msb"] + 1)}
    for label, high, low in triples:
        if not label or "..." in label or (label.strip().lower() == "reserved" and reg["name"] == "fcsr"):
            continue
        if not (0 <= low <= high < xlen):
            raise ValueError(f"Invalid field: {reg['name']} {label} RV{xlen} {high}:{low}")
        bits = set(range(low, high + 1))
        if bits & used:
            raise ValueError(f"Overlapping field: {reg['name']} {label} RV{xlen}")
        is_region = label.startswith("WPRI") or label.strip().lower() == "reserved" or re.fullmatch(r"0(?:\s+\(WARL\))?", label)
        if is_region:
            name = "WPRI" if label.startswith("WPRI") else "Reserved" if label.strip().lower() == "reserved" else "0"
            access = name if name in ("WPRI", "Reserved") else "WARL" if "WARL" in label else "图示固定零；访问规则见原文"
            item = next((item for item in reg["regions"] if item["label"] == name and item["access"] == access and all(loc["xlen"] != xlen for loc in item["layouts"])), None)
            if item is None:
                item = {"label": name, "access": access, "summary": "手册位图明确标注的区域。", "layouts": [], "sourceUrl": url(path)}
                reg["regions"].append(item)
        else:
            name, aliases = field_name(label, reg["name"])
            if not name:
                continue
            item = next((item for item in reg["fields"] if item["name"] == name), None)
            access = "WARL" if "WARL" in label else "WLRL" if "WLRL" in label else "未在位图中声明；见原文"
            if item is None:
                item = {"name": name, "summary": clean(label), "access": access, "layouts": [], "aliases": aliases, "sourceUrl": url(path)}
                reg["fields"].append(item)
            if any(loc["xlen"] == xlen for loc in item["layouts"]):
                raise ValueError(f"Duplicate field layout: {reg['name']}.{name}")
        item["layouts"].append({"xlen": xlen, "msb": high, "lsb": low})
        used |= bits


def add_region(reg, xlen, high, low, label, access, summary, path):
    if high < low:
        return
    assert 0 <= low <= high < xlen
    bits = set(range(low, high + 1))
    for item in reg["fields"] + reg["regions"]:
        for loc in item["layouts"]:
            if loc["xlen"] == xlen:
                assert not bits & set(range(loc["lsb"], loc["msb"] + 1)), (reg["name"], label)
    reg["regions"].append({"label": label, "access": access, "summary": summary,
                           "layouts": [{"xlen": xlen, "msb": high, "lsb": low}], "sourceUrl": url(path)})


# Explicit correspondence between normative CSR figures and address-table names.
BYTE_FIGURES = {
    "misa": "misareg", "mvendorid": "mvendorid", "marchid": "marchid", "mimpid": "mimpid", "mhartid": "mhartid", "mconfigptr": "mconfigptrreg",
    "mtvec": "mtvec", "medeleg": "medeleg", "mie": "miereg-standard", "mip": "mipreg-standard",
    "mideleg": "mideleg",
    "mcounteren": "counteren", "mcountinhibit": "counterinh", "mscratch": "mscratch", "mepc": "mepcreg", "mcause": "mcausereg", "mtval": "mtvalreg", "mtinst": "mtinstreg", "mtval2": "mtval2reg",
    "stvec": "stvec", "scounteren": "scounteren", "sscratch": "sscratch", "sepc": "epcreg", "scause": "scausereg", "stval": "stvalreg", "sie": "siereg-standard", "sip": "sipreg-standard",
    "hedeleg": "hedelegreg", "hideleg": "hidelegreg", "hcounteren": "hcounterenreg", "hgeie": "hgeiereg", "hgeip": "hgeipreg", "htval": "htvalreg", "htinst": "htinstreg", "hie": "hiereg-standard", "hip": "hipreg-standard", "hvip": "hvipreg-standard",
    "vsscratch": "vsscratchreg", "vsepc": "vsepcreg", "vscause": "vscausereg", "vstval": "vstvalreg", "vstvec": "vstvecreg", "vsie": "vsiereg-standard", "vsip": "vsipreg-standard",
    "mnscratch": "mnscratch", "mnepc": "mnepc", "mncause": "mncause", "mnstatus": "mnstatus",
}


def extension_tags(name, privilege):
    tags = [] if privilege == "M" else ["H" if privilege in ("H", "VS") else privilege]
    if name in ("fflags", "frm", "fcsr"):
        return tags + ["F"]
    if name in ("vstart", "vxsat", "vxrm", "vcsr", "vl", "vtype", "vlenb"):
        return tags + ["V"]
    if name == "ssp":
        return tags + ["Zicfiss"]
    if name == "seed":
        return tags + ["Zkr"]
    if name == "jvt":
        return tags + ["Zcmt"]
    if "stateen" in name:
        tags += ["Smstateen"]
    if name.startswith("mn"):
        tags += ["Smrnmi"]
    if name.startswith("mseccfg"):
        tags += ["Smepmp"]
    if name.startswith(("cycle", "time", "instret")):
        return tags + ["Zicntr"]
    if name.startswith("hpmcounter"):
        return tags + ["Zihpm"]
    if name == "scountovf":
        tags += ["Sscofpmf"]
    if name in ("mcyclecfg", "minstretcfg", "mcyclecfgh", "minstretcfgh"):
        tags += ["Smcntrpmf"]
    if "stimecmp" in name:
        tags += ["Sstc"]
    return sorted(set(tags))


def build_registers(*, include_audit=False):
    rows = addresses((SOURCE / "priv-csrs.adoc").read_text(encoding="utf-8"))
    # These CSR numbers are specified in extension prose outside the main table.
    extra = [(0x14D, "SRW", "stimecmp", "Supervisor timer compare"), (0x15D, "SRW", "stimecmph", "Upper 32 bits of stimecmp, RV32 only"),
             (0x24D, "HRW", "vstimecmp", "Virtual supervisor timer compare"), (0x25D, "HRW", "vstimecmph", "Upper 32 bits of vstimecmp, RV32 only")]
    timer_text = (SOURCE / "sstc.adoc").read_text(encoding="utf-8")
    for address, _, name, _ in extra:
        assert name in timer_text and f"0x{address:03X}".lower() in timer_text.lower()
    rows.extend(extra)
    registers = {}
    for address, access, name, description in rows:
        privilege = "VS" if name.startswith("vs") and (address >> 8) & 3 == 2 else access[:-2]
        widths = [32] if "RV32 only" in description or name == "mstatush" else [32, 64]
        if re.fullmatch(r"pmpcfg\d+", name):
            widths = [32] if int(name[6:]) % 2 else [32, 64]
            if len(widths) == 2:
                description = description.replace(", RV32 only", "")
        path = "sstc.adoc" if name in {entry[2] for entry in extra} else "priv-csrs.adoc"
        reg = {"id": f"riscv/manual/csr/{name}", "namespace": "manual", "name": name, "title": description,
               "summary": description, "locator": {"type": "riscv-csr", "address": address}, "privilege": privilege,
               "access": access[-2:], "xlens": widths, "condition": "", "extensions": extension_tags(name, privilege),
               "notes": "从官方手册地址表及明确位图提取；未解析的布局和访问规则保留缺失，不沿用旧数据库。", "sourceUrl": url(path),
               "sourceRevision": REVISION, "license": "CC-BY-4.0", "coverage": "address-only", "fields": [], "regions": []}
        registers[name] = reg
    for name, stem in BYTE_FIGURES.items():
        if name not in registers:
            continue
        path = f"images/bytefield/{stem}.edn"
        for width in registers[name]["xlens"]:
            add_figure(registers[name], byte_fields((SOURCE / path).read_text(encoding="utf-8"), width), width, path)
    for name in ("medeleg", "hedeleg"):
        stem = BYTE_FIGURES[name]
        path = f"images/bytefield/{stem}.edn"
        full = byte_fields((SOURCE / path).read_text(encoding="utf-8"), 64)
        for part_name, shift in ((name, 0), (name + "h", 32)):
            if part_name in registers:
                clipped = [(label, min(high, shift + 31) - shift, max(low, shift) - shift)
                           for label, high, low in full if high >= shift and low < shift + 32]
                add_figure(registers[part_name], clipped, 32, path)
    for name, files in {"mstatus": [(32, "mstatusreg-rv321"), (64, "mstatusreg")], "mstatush": [(32, "mstatushreg")]}.items():
        for width, stem in files:
            path = f"images/wavedrom/{stem}.edn"
            add_figure(registers[name], wave_fields((SOURCE / path).read_text(encoding="utf-8")), width, path)
    for name, stem in (("menvcfg", "menvcfgreg"), ("mseccfg", "mseccfg")):
        path = f"images/wavedrom/{stem}.edn"
        triples = wave_fields((SOURCE / path).read_text(encoding="utf-8"))
        add_figure(registers[name], triples, 64, path)
        for part_name, shift in ((name, 0), (name + "h", 32)):
            clipped = [(label, min(high, shift + 31) - shift, max(low, shift) - shift)
                       for label, high, low in triples if high >= shift and low < shift + 32]
            add_figure(registers[part_name], clipped, 32, path)
    for chapter in ("supervisor", "hypervisor", "smstateen"):
        path = f"{chapter}.adoc"
        text = (SOURCE / path).read_text(encoding="utf-8")
        for match in re.finditer(r"^\[wavedrom[^\n]*\]\n\.\.\.\.\n(.*?)\n\.\.\.\.", text, re.M | re.S):
            caption = next((line for line in reversed(text[:match.start()].splitlines()) if line.startswith(".") and not line.startswith("..")), "")
            names = re.findall(r"`([^`]+)`", caption)
            if len(names) != 1 or names[0] not in registers:
                continue
            triples = wave_fields(match[1])
            if not triples:
                continue
            width = max(high for _, high, _ in triples) + 1
            if width not in (32, 64):
                continue
            reg = registers[names[0]]
            if width in reg["xlens"]:
                add_figure(reg, triples, width, path)
                if reg["name"] == "sstateen0" and width == 32:
                    add_figure(reg, triples, 64, path)
                    add_region(reg, 64, 63, 32, "0", "RO", "规范将 supervisor state-enable 的高 32 位定义为零。", path)
                # State-enable diagrams define a 64-bit logical CSR; RV32 uses
                # separate high-half addresses. Partition only explicit bits.
                if chapter == "smstateen" and width == 64:
                    for part_name, shift in ((reg["name"], 0), (reg["name"] + "h", 32)):
                        if part_name in registers:
                            clipped = [(label, min(high, shift + 31) - shift, max(low, shift) - shift)
                                       for label, high, low in triples if high >= shift and low < shift + 32]
                            add_figure(registers[part_name], clipped, 32, path)
                if reg["name"] == "henvcfg" and width == 64:
                    for part_name, shift in (("henvcfg", 0), ("henvcfgh", 32)):
                        clipped = [(label, min(high, shift + 31) - shift, max(low, shift) - shift)
                                   for label, high, low in triples if high >= shift and low < shift + 32]
                        add_figure(registers[part_name], clipped, 32, path)
    for name, stem in (("satp", "satp"), ("hgatp", "hgatp"), ("vsatp", "vsatpreg")):
        for width in (32, 64):
            path = f"images/bytefield/rv{width}{stem}.edn"
            add_figure(registers[name], byte_fields((SOURCE / path).read_text(encoding="utf-8"), width), width, path)
    # Floating-point CSR bit positions are explicit in float-csr.edn and f-st-ext.adoc.
    path = "images/wavedrom/float-csr.edn"
    triples = wave_fields((SOURCE / path).read_text(encoding="utf-8"))
    for width in (32, 64):
        add_figure(registers["fcsr"], triples, width, path)
        add_figure(registers["fflags"], [(label, high, low) for label, high, low in triples if high < 5], width, path)
        add_figure(registers["frm"], [("Rounding Mode", 2, 0)], width, "f-st-ext.adoc")
    # Register families explicitly described in machine.adoc and counters.adoc.
    # RV32 CSR accesses expose 32-bit halves of a logical 64-bit counter/event.
    for reg in registers.values():
        name = reg["name"]
        counter = re.fullmatch(r"(?:m?(?:cycle|instret)|time|m?hpmcounter\d+|mhpmevent\d+)(h?)", name)
        if counter:
            path = "machine.adoc" if name.startswith("m") else "counters.adoc"
            for width in reg["xlens"]:
                add_figure(reg, [(name, width - 1, 0)], width, path)
            for field in reg["fields"]:
                field["access"] = "RO" if reg["access"] == "RO" else "WARL" if "hpm" in name else "RW"
        if re.fullmatch(r"pmpaddr\d+", name):
            for width in reg["xlens"]:
                path = f"images/bytefield/pmpaddr-rv{width}.edn"
                add_figure(reg, byte_fields((SOURCE / path).read_text(encoding="utf-8"), width), width, path)
        if re.fullmatch(r"pmpcfg\d+", name):
            path = "images/bytefield/pmpcfg.edn"
            entry_fields = byte_fields((SOURCE / path).read_text(encoding="utf-8"), 32)
            first = int(name[6:]) * 4
            for width in reg["xlens"]:
                for entry in range(width // 8):
                    triples = [(label if label.startswith("0") else f"PMP{first + entry}_{label}", high + entry * 8, low + entry * 8)
                               for label, high, low in entry_fields]
                    add_figure(reg, triples, width, path)
    # vtype and vcsr contain explicit bit-number tables; fixed-point sub-CSRs
    # and the other XLEN-sized vector CSRs are described in the same chapter.
    for width in (32, 64):
        add_figure(registers["vtype"], [("vill", width - 1, width - 1), ("vma", 7, 7), ("vta", 6, 6), ("vsew", 5, 3), ("vlmul", 2, 0)], width, "v-st-ext.adoc")
        add_figure(registers["vcsr"], [("vxrm", 2, 1), ("vxsat", 0, 0)], width, "v-st-ext.adoc")
        add_figure(registers["vxrm"], [("vxrm", 1, 0)], width, "v-st-ext.adoc")
        add_figure(registers["vxsat"], [("vxsat", 0, 0)], width, "v-st-ext.adoc")
        for name in ("vl", "vlenb", "vstart"):
            add_figure(registers[name], [(name, width - 1, 0)], width, "v-st-ext.adoc")
        for name in ("stimecmp", "vstimecmp"):
            add_figure(registers[name], [(name, width - 1, 0)], width, "sstc.adoc")
        add_figure(registers["ssp"], [("ssp", width - 1, 0)], width, "unpriv-cfi.adoc")
    for name in ("stimecmph", "vstimecmph"):
        add_figure(registers[name], [(name, 31, 0)], 32, "sstc.adoc")
    jvt_text = (SOURCE / "zc.adoc").read_text(encoding="utf-8").split("Format (RV32):", 1)[1]
    for width in (32, 64):
        # Select a single format block with its matching explicit width config.
        candidates = re.findall(r"\{reg:\[.*?\],config:\{bits:\d+\}\}", jvt_text, re.S)
        selected = next(item for item in candidates if item.endswith(f"bits:{width}}}}}"))
        add_figure(registers["jvt"], wave_fields(selected), width, "zc.adoc")
        add_figure(registers["seed"], [("OPST", 31, 30), ("custom", 23, 16), ("entropy", 15, 0)], width, "scalar-crypto.adoc")
        # Rules stated in prose, not visible as named fields in the figures.
        add_region(registers["fcsr"], width, 31, 8, "WPRI", "WPRI",
                   "未被其他扩展复用时写入忽略、读取为零；软件应保留这些位。", "f-st-ext.adoc")
        add_region(registers["fflags"], width, width - 1, 5, "0", "RO",
                   "CSR 读取结果扩展到 XLEN 时，高位补零。", "zicsr.adoc")
        add_region(registers["frm"], width, width - 1, 3, "0", "RO",
                   "CSR 读取结果扩展到 XLEN 时，高位补零。", "zicsr.adoc")
        add_region(registers["vtype"], width, width - 2, 8, "0", "WARL",
                   "保留编码应写零；不支持的 vtype 配置置 vill，其余位读零。", "v-st-ext.adoc")
        add_region(registers["vcsr"], width, width - 1, 3, "Reserved", "Reserved",
                   "规范 vcsr 位表标为保留；未将其推断为 WPRI。", "v-st-ext.adoc")
        for name, low in (("vxrm", 2), ("vxsat", 1)):
            add_region(registers[name], width, width - 1, low, "0", "保留（应写零）",
                       "规范正文要求这些高位写零。", "v-st-ext.adoc")
        add_region(registers["seed"], width, 29, 24, "Reserved", "Reserved",
                   "规范为未来用途保留；seed 写值整体忽略。", "scalar-crypto.adoc")
        if width == 64:
            for name, path in (("mvendorid", "machine.adoc"), ("fcsr", "f-st-ext.adoc"), ("seed", "scalar-crypto.adoc")):
                add_region(registers[name], width, 63, 32, "0", "RO",
                           "32 位 CSR 的读取结果扩展到 XLEN，高位补零（Zicsr）。", path)
            for name in ("mcounteren", "mcountinhibit", "scounteren", "hcounteren"):
                add_region(registers[name], width, 63, 32, "0", "RO",
                           "计数器使能/禁止 CSR 是 32 位宽，读取扩展到 XLEN 时高位补零。", "machine.adoc" if name.startswith("m") else "supervisor.adoc" if name.startswith("s") else "hypervisor.adoc")
    # Finish address-table entries whose fields are defined in chapter prose.
    for name in ("hgeip", "hgeie"):
        reg = registers[name]
        for width in reg["xlens"]:
            add_figure(reg, [("GEIP" if name == "hgeip" else "GEIE", width - 1, 1)], width, "hypervisor.adoc")
            add_region(reg, width, 0, 0, "0", "RO", "访客外部中断编号从 1 开始，bit 0 固定为零。", "hypervisor.adoc")
    for name in ("htimedelta", "htimedeltah"):
        for width in registers[name]["xlens"]:
            add_figure(registers[name], [(name, width - 1, 0)], width, "hypervisor.adoc")
    for number in range(1, 4):
        for prefix in ("m", "h"):
            for high_half in (False, True):
                name = f"{prefix}stateen{number}" + ("h" if high_half else "")
                for width in registers[name]["xlens"]:
                    has_control = width == 64 or high_half
                    if has_control:
                        add_figure(registers[name], [(f"SE{number}", width - 1, width - 1)], width, "smstateen.adoc")
                    add_region(registers[name], width, width - 2 if has_control else width - 1, 0,
                               "0", "RO", "尚未分配用途的 state-enable 位按规范为只读零。", "smstateen.adoc")
        for width in registers[f"sstateen{number}"]["xlens"]:
            add_region(registers[f"sstateen{number}"], width, width - 1, 0, "0", "RO",
                       "本版本未分配用途的 supervisor state-enable 寄存器按规范读零。", "smstateen.adoc")
    for width in (32, 64):
        inhibit = registers["mcountinhibit"]
        triples = [(field["name"], loc["msb"], loc["lsb"]) for field in inhibit["fields"] for loc in field["layouts"] if loc["xlen"] == 32]
        add_figure(registers["scountinhibit"], triples, width, "smcdeleg.adoc")
        add_region(registers["scountinhibit"], width, 1, 1, "0", "RO", "没有 time 计数禁止位。", "smcdeleg.adoc")
        add_figure(registers["scountovf"], [(f"OF{bit}", bit, bit) for bit in range(3, 32)], width, "sscofpmf.adoc")
        add_region(registers["scountovf"], width, 2, 0, "0", "RO", "只有 HPM3 到 HPM31 的溢出影子位。", "sscofpmf.adoc")
        if width == 64:
            for name in ("scountinhibit", "scountovf"):
                add_region(registers[name], width, 63, 32, "0", "RO", "32 位 CSR 扩展读取时高位补零。", "zicsr.adoc")
    for reg in registers.values():
        if not reg["name"].startswith("mhpmevent"):
            continue
        reg["fields"], reg["regions"] = [], []
        high_half = reg["name"].endswith("h")
        for width in reg["xlens"]:
            shift = 32 if high_half else 0
            logical = [("EVENT", 55, 0), ("WPRI", 57, 56), ("VUINH", 58, 58), ("VSINH", 59, 59),
                       ("UINH", 60, 60), ("SINH", 61, 61), ("MINH", 62, 62), ("OF", 63, 63)]
            clipped = [(label, min(high, shift + width - 1) - shift, max(low, shift) - shift)
                       for label, high, low in logical if high >= shift and low < shift + width]
            add_figure(reg, clipped, width, "sscofpmf.adoc")
        for field in reg["fields"]:
            if field["name"] != "EVENT":
                field["condition"] = "Sscofpmf"
    # Associate only fields explicitly assigned to these extensions by the text.
    field_extensions = {"FS": "F D Q", "VS": "V", "SD": "F V", "MPV": "H", "GVA": "H",
                        "SPELP": "Zicfilp", "MPELP": "Zicfilp", "SSE": "Zicfiss", "MDT": "Smdbltrp", "SDT": "Ssdbltrp",
                        "STCE": "Sstc", "PBMTE": "Svpbmt", "ADUE": "Svadu", "CBZE": "Zicboz", "CBCFE": "Zicbom", "CBIE": "Zicbom"}
    for reg in registers.values():
        for field in reg["fields"]:
            if reg["access"] == "RO":
                field["access"] = "RO"
            if reg["name"] == "misa" and field["name"] == "MXL":
                field["access"] = "RO"  # machine.adoc prose overrides its schematic WARL label.
            if reg["name"] in ("mstatus", "mstatush", "sstatus", "vsstatus") and field["name"] in ("MPP", "SPP", "SXL", "UXL", "MDT"):
                field["access"] = "WARL"
            if reg["name"] in ("mstatus", "sstatus", "vsstatus") and field["name"] == "SD":
                field["access"] = "RO"
            if field["name"] in field_extensions:
                field["condition"] = field_extensions[field["name"]] + "（适用关联，完整条件见手册）"
        if reg["fields"] or reg["regions"]:
            reg["coverage"] = "fields"
    result = list(registers.values())
    audit = enrich(result, SOURCE, url)
    return (result, audit) if include_audit else result


def run():
    actual = subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise SystemExit(f"Expected official manual {REVISION}, found {actual}")
    if subprocess.check_output(["git", "-C", str(UPSTREAM), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
        raise SystemExit("Official manual checkout has local modifications")
    old = read_catalog(ASSETS)
    retained = [reg for reg in old["registers"] if reg["namespace"] not in ("standard", "andes", "manual")]
    manual_registers, semantic_audit = build_registers(include_audit=True)
    registers = manual_registers + retained
    fields = sum(len(reg["fields"]) for reg in registers)
    old.update(version="0.5.0", registers=registers,
               coverage=f"{len(registers)} 个 CSR / {fields} 个字段；官方手册 20250508 转换，未解析布局明确标记；保留其他来源。",
               source={"title": "RISC-V ISA Manual 20250508", "revision": REVISION, "url": REPOSITORY})
    write_catalog(ASSETS, old)
    sample_names = ["mstatus", "mstatush", "misa", "mvendorid", "marchid", "mimpid", "mhartid", "mtvec", "mepc", "mcause", "mtval", "sstatus", "sscratch", "satp", "mie"]
    sample = dict(old, id="riscv-manual-sample", namespace="manual",
                  registers=[next(reg for reg in registers if reg["namespace"] == "manual" and reg["name"] == name) for name in sample_names])
    (ROOT / "core/src/test/resources/riscv-manual-sample.json").write_text(json.dumps(sample, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ROOT / "core/src/test/resources/riscv-standard-sample.json").unlink(missing_ok=True)
    report = {"source": old["source"], "tag": "20250508", "counts": dict(Counter(reg["namespace"] for reg in registers)),
              "fields": fields, "sha256": catalog_sha256(ASSETS),
              "fieldSemantics": semantic_audit,
              "addressOnly": [reg["id"] for reg in registers if reg["coverage"] == "address-only"],
              "limitations": ["Only explicitly supported address tables and figures are imported; extension chapters are not exhaustively parsed.",
                              "Bytefield visual spans are not bit widths; only explicit width labels are decoded.",
                              "Field access rules absent from figures are not guessed; consult the linked manual."]}
    notices = ["ISAtlas data attribution\n\nOfficial RISC-V Instruction Set Manual; RISC-V International and contributors.",
               f"Source: {REPOSITORY}\nTag: 20250508\nCommit: {REVISION}\nChanges: extract CSR tables/figures and instruction encodings into JSON; normalize labels; translate and summarize CSR fields and instructions in Chinese and English; annotate encodings and XLEN-specific rules; omit unsupported layouts.\n",
               (UPSTREAM / "LICENSE").read_text(encoding="utf-8")]
    if retained:
        notices += ["\nRetained T-head data: https://github.com/T-head-Semi/thead-extension-spec\nRevision: 5a75db2696881a812a04e6e00347d3920dae03f2\n",
                    (ROOT / "tools/licenses/thead-Apache-2.0.txt").read_text(encoding="utf-8")]
    (ASSETS / "THIRD_PARTY_NOTICES.txt").write_text("\n".join(notices), encoding="utf-8")
    licenses = ASSETS / "licenses"
    licenses.mkdir(exist_ok=True)
    (licenses / "CC-BY-4.0.txt").write_text((UPSTREAM / "LICENSE").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    if retained:
        (licenses / "Apache-2.0.txt").write_text((ROOT / "tools/licenses/thead-Apache-2.0.txt").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    options_path = ASSETS / "march-extensions.json"
    options = json.loads(options_path.read_text(encoding="utf-8"))
    # Keep the compiler vocabulary and the reviewed manual capability graph.
    options["dependencies"] = dependencies(options["tokens"])
    options["dependencySource"] = f"{REPOSITORY}/tree/{REVISION}/src"
    options["dependencyScope"] = "Explicitly adapted mandatory prerequisites only; not a complete ISA configuration solver."
    # Retain the existing JSON schema: the scope belongs in the local report.
    report["dependencyScope"] = options.pop("dependencyScope")
    options_path.write_text(json.dumps(options, indent=2) + "\n", encoding="utf-8")
    report_path = ROOT / "docs/data/manual-import-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    compact = {key: value for key, value in report.items() if key not in ("addressOnly", "fieldSemantics")}
    compact["fieldSemantics"] = {key: value for key, value in semantic_audit.items() if key != "sources"}
    print(json.dumps(compact, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
