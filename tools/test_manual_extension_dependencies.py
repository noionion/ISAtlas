"""Keep the shipped dependency graph in sync with its manual-backed generator."""
import json
from pathlib import Path
import unittest

from manual_extension_dependencies import dependencies


class ExtensionDependencyTests(unittest.TestCase):
    def test_shipped_graph_matches_generator_and_is_acyclic(self):
        path = Path(__file__).resolve().parents[1] / "app/src/main/assets/march-extensions.json"
        options = json.loads(path.read_text(encoding="utf-8"))
        graph = dependencies(options["tokens"])
        self.assertEqual(options["dependencies"], graph)
        allowed = set(options["tokens"]) | {"s", "u"}

        def visit(token, ancestors):
            self.assertNotIn(token, ancestors)
            for child in graph.get(token, []):
                self.assertIn(child, allowed)
                visit(child, ancestors | {token})

        for token in graph:
            visit(token, set())


if __name__ == "__main__":
    unittest.main()
