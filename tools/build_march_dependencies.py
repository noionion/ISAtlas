"""Regenerate extension prerequisites without rewriting CSR/instruction data."""
import json
from pathlib import Path

from manual_extension_dependencies import dependencies


def run():
    path = Path(__file__).resolve().parents[1] / "app/src/main/assets/march-extensions.json"
    options = json.loads(path.read_text(encoding="utf-8"))
    options["dependencies"] = dependencies(options["tokens"])
    path.write_text(json.dumps(options, indent=2) + "\n", encoding="utf-8", newline="\n")

if __name__ == "__main__":
    run()
