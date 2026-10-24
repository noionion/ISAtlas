"""Read literal instruction encodings from the pinned ISA manual (no evaluation)."""
from __future__ import annotations

import ast
from dataclasses import dataclass
import hashlib
import html
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "upstream/riscv-isa-manual/src"
REVISION = "584390d9d872835b1bc45ab9ea946b56db3ad485"
REPOSITORY = "https://github.com/riscv/riscv-isa-manual"


def clean(text):
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    text = re.sub(r"cite:\[[^]]*\]", "", text)
    text = re.sub(r"<<([^>,]+)(?:,([^>]+))?>>", lambda m: m[2] or m[1], text)
    return re.sub(r"\s+", " ", html.unescape(text).replace("`", "").replace("_", "")).strip()


def evidence(path, start, end=None, method="literal"):
    """Offsets are characters in the original UTF-8 source, never local paths."""
    text = (SOURCE / path).read_text(encoding="utf-8")
    first = text.count("\n", 0, start) + 1
    last = text.count("\n", 0, end if end is not None else start) + 1
    lines = text.splitlines()
    last=min(last,len(lines))
    excerpt = "\n".join(lines[first - 1:last])
    return {"path": path, "lineStart": first, "lineEnd": last,
            "url": f"{REPOSITORY}/blob/{REVISION}/src/{path}#L{first}-L{last}",
            "sha256": hashlib.sha256(excerpt.encode()).hexdigest(), "method": method}


def field(name, msb, lsb, value=None):
    result = {"name": str(name), "msb": msb, "lsb": lsb}
    if value is not None:
        if not 0 <= value < 1 << (msb - lsb + 1):
            raise ValueError(f"Fixed field overflow: {name} {msb}:{lsb} = {value}")
        result["value"] = value
    else:
        operand = re.sub(r"\[.*", "", str(name)).strip().replace("′", "'").replace("ʹ", "'")
        operand = re.split(r"≠|̸=", operand)[0]
        result["operand"] = operand
        bits = re.search(r"\[([0-9:|]+)\]", str(name))
        if bits:
            positions = []
            for chunk in bits[1].split("|"):
                if ":" in chunk:
                    high, low = map(int, chunk.split(":"))
                    positions.extend(range(high, low - 1, -1))
                else:
                    positions.append(int(chunk))
            if len(positions) != msb - lsb + 1:
                raise ValueError(f"Immediate mapping width mismatch: {name}")
            result["operandBits"] = positions
        if "'" in operand:
            result["registerOffset"] = 8
    return result


def encoding(fields, xlens, requires, source, constraints=None):
    length = max(f["msb"] for f in fields) + 1
    mask = match = occupied = 0
    for f in fields:
        bits = ((1 << (f["msb"] - f["lsb"] + 1)) - 1) << f["lsb"]
        if occupied & bits:
            raise ValueError("Overlapping encoding fields")
        occupied |= bits
        if "value" in f:
            mask |= bits
            match |= f["value"] << f["lsb"]
    if occupied != (1 << length) - 1 or length not in (16, 32):
        raise ValueError(f"Incomplete instruction layout ({length} bits)")
    return {"length": length, "xlens": sorted(xlens), "requires": requires,
            "mask": f"0x{mask:0{length // 4}x}", "match": f"0x{match:0{length // 4}x}",
            "fields": fields, "constraints": constraints or [], "sources": [source]}


def wave_fields(text):
    """Parse JS-like WaveDrom data, retaining number vs string distinctions."""
    start = re.search(r"\breg\s*:\s*\[", text)
    if not start:
        raise ValueError("Not a register diagram")
    output, offset = [], 0
    # Field objects contain only literal values. Quoted strings may escape apostrophes.
    for m in re.finditer(r"\{\s*bits\s*:\s*(\d+)\s*,\s*name\s*:\s*((?:'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|0x[0-9a-fA-F]+|\d+))([^{}]*?)\}", text[start.end():]):
        width = int(m[1])
        literal = m[2]
        quoted = literal.startswith(("'", '"'))
        label = ast.literal_eval(re.sub(r"\\([\[\]])", r"\1", literal)) if quoted else int(literal, 0) if literal.startswith("0x") else int(literal)
        if isinstance(label,str) and re.fullmatch(r"[01]+`",label):
            # VAESDF.VS has a stray backtick; its allocation table independently
            # confirms funct6=101001 (recorded by the adapter below).
            label=label.rstrip("`")
        value = label if isinstance(label, int) else int(label, 2) if re.fullmatch(r"[01]+", label) and len(label) == width else None
        if value is not None:
            label = f"fixed[{offset + width - 1}:{offset}]"
        attrs = re.search(r"\battr\s*:\s*(\[(?:'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|[^\]])*\]|'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\")", m[3])
        attributes = ast.literal_eval(re.sub(r"\\([\[\]])", r"\1", attrs[1])) if attrs else []
        f = field(label, offset + width - 1, offset, value)
        if attributes:
            f["sourceLabels"] = attributes if isinstance(attributes, list) else [attributes]
        output.append(f)
        offset += width
    if not output:
        raise ValueError("No supported literal fields")
    return output


def waves(text):
    for m in re.finditer(r"(?m)^\[wavedrom[^\n]*\]\s*\n\.{4}\s*\n(.*?)^\.{4}\s*$", text, re.S | re.M):
        yield m, wave_fields(m[1])


def table_rows():
    """Use the printed bit-column boundaries, not AsciiDoc display spans."""
    path = "rv-32-64g.adoc"
    text = (SOURCE / path).read_text(encoding="utf-8")
    boundaries, section = None, ""
    for m in re.finditer(r"(?m)^.*$", text):
        line = m[0]
        if re.search(r"\|31\s*\|27", line):
            boundaries = [31, 27, 26, 25, 24, 20, 19, 15, 14, 12, 11, 7, 6, 0]
        if re.search(r"\|31\s*\|\s*26\s*\|", line):
            boundaries = [31, 26, 25, 20, 19, 15, 14, 12, 11, 7, 6, 0]
        if re.search(r"\|\*?RV(?:32|64).*?(?:Standard Extension|Base Instruction Set)|\|Zawrs Standard", line):
            section = clean(line)
        # The later compact Zfh table has seven physical fields (8 with mnemonic).
        if "RV32Zfh" in line or "RV64Zfh" in line:
            boundaries = [31, 26, 24, 19, 14, 11, 6]
        if "Zawrs Standard" in line:
            boundaries = [31, 27, 26, 25, 24, 20, 19, 15, 14, 12, 11, 7, 6, 0]
        if "|" not in line or not re.search(r"\|[A-Z][A-Z0-9.]+\s*$", line):
            continue
        # Split escaped immediate bit separators only after cell extraction.
        separators = list(re.finditer(r"(?<![\\\d])(?:(\d+)\+)?[<^>]*\|", line))
        cells = [(s[1], line[s.end():separators[i + 1].start() if i + 1 < len(separators) else len(line)]) for i, s in enumerate(separators)]
        if not cells or not re.fullmatch(r"[A-Z][A-Z0-9.]+", cells[-1][1].strip()):
            continue
        name = cells[-1][1].strip().lower()
        if name in {"lui", "auipc"} or re.fullmatch(r"[a-z][a-z0-9.]+", name):
            if not boundaries:
                raise ValueError("Missing printed table bit header")
            # Tables after the main listing (CSR map) are not instruction rows.
            if cells[0][1].strip().startswith("0x"):
                continue
            if name == "flq":
                # FLQ's label spans an empty following printed cell (quad-ls.edn).
                cells = [(str(int(cells[0][0]) + int(cells[1][0])), cells[0][1])] + cells[2:]
            index, fields = 0, []
            for span, label in cells[:-1]:
                count = int(span or 1)
                high = boundaries[index]
                low = (boundaries[index + count] + 1 if index + count < len(boundaries) else 0) if len(boundaries) == 7 else boundaries[index + count - 1]
                label = clean(label).replace("\\|", "|")
                value = int(label, 2) if re.fullmatch(r"[01]+", label) else None
                if name not in {"fence", "fence.tso", "pause"}:
                    fields.append(field(label, high, low, value))
                index += count
            if name in {"fence", "fence.tso", "pause"}:
                # This printed table's fm/pred/succ display spans do not follow
                # its numeric header. mem-order.edn explicitly gives 4/4/4 bits.
                fields, offset = [], 32
                for (_, label), width in zip(cells[:-1], [4, 4, 4, 5, 3, 5, 7]):
                    offset -= width
                    label = clean(label)
                    fields.append(field(label, offset + width - 1, offset, int(label, 2) if re.fullmatch(r"[01]+", label) else None))
            if index != len(boundaries):
                raise ValueError(f"Unexpected printed row: {name}: {index}/{len(boundaries)}")
            xlen = [64] if section.startswith(("15+^|*RV64", "8+|RV64")) else [32, 64]
            # Avoid accidental dependence on formatting of section prefix.
            if "RV" in section and re.search(r"RV(32|64)", section)[1] == "64":
                xlen = [64]
            ext_match = re.search(r"RV(?:32|64)(Zfh|[IMAFDQ])\b", section)
            ext = ext_match[1] if ext_match else "Zicsr" if "Zicsr" in section else "Zifencei" if "Zifencei" in section else "Zawrs" if "Zawrs" in section else None
            if not ext:
                raise ValueError(f"Unknown listing section: {section}")
            if name in {"slli", "srli", "srai"} and any(f["msb"] == 31 and f["lsb"] == 25 for f in fields):
                xlen = [32]
            if name == "pause":
                ext = "Zihintpause"
            if name == "fence.tso":
                ext = "Ztso"
            requirements = [[ext]]
            if ext == "A":
                requirements = [["Zalrsc"]] if name.startswith(("lr.", "sc.")) else [["Zaamo"]]
            if ext in {"F", "D", "Q", "Zfh"}:
                # Cross-format conversion instructions require both formats.
                formats = {"s": "F", "d": "D", "q": "Q", "h": "Zfh"}
                for part in name.split(".")[1:]:
                    if part in formats and formats[part] not in requirements[0]:
                        requirements[0].append(formats[part])
            yield name, encoding(fields, xlen, requirements, evidence(path, m.start(), m.end(), "printed-bit-table")), section


def byte_rows(path, widths=None):
    # Import the existing safe, non-evaluating EDN reader.
    from import_isa_manual import edn_forms
    text = (SOURCE / path).read_text(encoding="utf-8")
    forms = edn_forms(text)
    row_size = next(f[2] for f in forms if isinstance(f, list) and f[:2] == ["def", "boxes-per-row"])
    position, row = 0, []
    for f in forms:
        if not isinstance(f, list) or f[:1] != ["draw-box"]:
            continue
        label = f[1]
        if isinstance(label, list):
            label = label[1]
        style = f[2] if len(f) > 2 else {}
        span = style.get(":span", 1)
        row.append((label, span, f[1]))
        position += span
        if position != row_size:
            if position > row_size:
                raise ValueError("Bytefield row overflow")
            continue
        position = 0
        current, row = row, []
        name = current[-1][0]
        if not isinstance(name, str) or not re.fullmatch(r"(?:C\.|[A-Z])[A-Z0-9.]+", name.strip()):
            continue
        name = name.strip().lower()
        data = current[:-1]
        # C rows use real 16-bit drawing columns. Privileged listing deliberately
        # enlarges fields: its printed numeric header gives [7,5,5,3,5,7].
        bit_widths = widths or [span for _, span, _ in data]
        if len(bit_widths) != len(data):
            raise ValueError(f"Unexpected Bytefield format {name}")
        offset = sum(bit_widths)
        fields = []
        for (label, _, _), width in zip(data, bit_widths):
            offset -= width
            value = int(label, 2) if re.fullmatch(r"[01]+", label or "") else int(label) if label and label.isdecimal() else None
            fields.append(field(label, offset + width - 1, offset, value))
        # Each encoding row can be located unambiguously using its mnemonic.
        search = re.search(r'\(text\s+"\s*' + re.escape(name.upper()) + r'"|\(draw-box\s+"\s*' + re.escape(name.upper()) + r'"', text)
        box_starts=[m.start() for m in re.finditer(r"\(draw-box\b",text[:search.end()])]
        row_start=box_starts[-len(current)]
        yield name, fields, repr(current[-1][2]), evidence(path, row_start, search.end(), "bytefield-printed-bits")


@dataclass
class Section:
    path: str
    name: str
    text: str
    offset: int
    synopsis: str


def named_sections():
    for path in ("b-st-ext.adoc", "scalar-crypto.adoc", "vector-crypto.adoc", "zc.adoc", "bfloat16.adoc", "zilsd.adoc", "zicond.adoc"):
        text = (SOURCE / path).read_text(encoding="utf-8")
        for m in re.finditer(r"(?m)^==== ([a-z][a-z0-9.\[\],]+)\s*\n(.*?)(?=^={2,4} |\Z)", text, re.S | re.M):
            synopsis = re.search(r"(?m)^Synopsis::?\s*\n(.*?)(?=\n\s*\n)", m[2], re.S)
            if synopsis:
                yield Section(path, m[1], m[2], m.start(2), clean(synopsis[1]))
