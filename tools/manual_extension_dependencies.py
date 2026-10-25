"""Mandatory prerequisites and included subsets from the pinned ISA manual.

These are capability implications, not a prefix rule or an ISA validity solver.
In particular, Zve is sufficient for many Zv extensions; full V is not required.
"""
import re


def dependencies(tokens):
    result = {
        "f": ["zicsr"], "d": ["f"], "q": ["d"],
        "s": ["u"], "h": ["i", "s"],
        "g": ["i", "m", "a", "f", "d", "zicsr", "zifencei"],
        "zicfiss": ["zicsr", "zimop"], "zicfilp": ["zicsr"],
        # zfa.adoc, zfh.adoc, bfloat16.adoc: scalar FP and included subsets.
        "zfa": ["f"], "zfbfmin": ["f"],
        "zfh": ["zfhmin"], "zfhmin": ["f"],
        # zfinx.adoc: integer-register FP does NOT imply F.
        "zfinx": ["zicsr"], "zdinx": ["zfinx"],
        "zhinx": ["zhinxmin"], "zhinxmin": ["zfinx"],
        # v-st-ext.adoc: Zve/V hierarchy and minimum vector lengths.
        "zve32x": ["zicsr", "zvl32b"],
        "zve32f": ["zve32x", "f"],
        "zve64x": ["zve32x", "zvl64b"],
        "zve64f": ["zve32f", "zve64x"],
        "zve64d": ["zve64f", "d"],
        "v": ["zve64d", "zvl128b"],
        "zvfhmin": ["zve32f"],
        "zvfh": ["zvfhmin", "zfhmin"],
        # bfloat16.adoc: V OR Zve32f; V already includes Zve32f.
        "zvfbfmin": ["zve32f"],
        "zvfbfwma": ["zvfbfmin", "zfbfmin"],
        # vector-crypto.adoc: base requirements, subsets and suite tables.
        "zvbb": ["zvkb"], "zvkb": ["zve32x"],
        "zvbc": ["zve64x"], "zvkg": ["zve32x"],
        "zvkned": ["zve32x"], "zvknha": ["zve32x"],
        "zvknhb": ["zve64x"],
        "zvksed": ["zve32x"], "zvksh": ["zve32x"], "zvkt": ["zve32x"],
        "zvkn": ["zvkned", "zvknhb", "zvkb", "zvkt"],
        "zvknc": ["zvkn", "zvbc"], "zvkng": ["zvkn", "zvkg"],
        "zvks": ["zvksed", "zvksh", "zvkb", "zvkt"],
        "zvksc": ["zvks", "zvbc"], "zvksg": ["zvks", "zvkg"],
    }
    # v-st-ext.adoc: every longer Zvl includes all shorter Zvl capabilities.
    lengths = sorted(int(match[1]) for token in tokens
                     if (match := re.fullmatch(r"zvl(\d+)b", token)))
    for smaller, larger in zip(lengths, lengths[1:]):
        result[f"zvl{larger}b"] = [f"zvl{smaller}b"]
    return result
