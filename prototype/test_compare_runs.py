import json
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import compare_runs as cr

HERE = Path(__file__).resolve().parent
MINUS = "Jace, Multiverse Architect: -3"


def rows(n, seed=1, minus_shift=0, rehash=False, redraw=False):
    rng = random.Random(seed)
    out = []
    for s in range(1, n + 1):
        game = random.Random(s * 7919 + (seed if redraw else 0))
        dead = game.random() < 0.75
        tdr = game.choice([8, 9, 10]) if dead else None
        out.append({"seed": s, "status": "dead" if dead else "horizon", "our_turns": tdr or 10,
                    "table_dead_round": tdr, "opponents_life": [0, 0, 0] if dead else [12, 0, 30],
                    "plays": {MINUS: max(0, game.randint(2, 6) + minus_shift)},
                    "log_hash": f"{s:x}{'b' if rehash or redraw else ''}{rng.random() if redraw else ''}"})
    return out


def run(a, b, *extra):
    with tempfile.TemporaryDirectory() as tmp:
        pa, pb = Path(tmp, "a.jsonl"), Path(tmp, "b.jsonl")
        pa.write_text("\n".join(json.dumps(r) for r in a))
        pb.write_text("\n".join(json.dumps(r) for r in b))
        done = subprocess.run([sys.executable, str(HERE / "compare_runs.py"), str(pa), str(pb), *extra],
                              capture_output=True, text=True)
    return done.returncode, done.stdout + done.stderr


class Parsing(unittest.TestCase):
    def test_mana_net(self):
        cases = {
            "Sol Ring (56) - {T}: Add {C}{C}.": 2,
            "Izzet Signet (5) - {1}, {T}: Add {U}{R}.": 1,
            "Mystic Gate (3) - {W/U}, {T}: Add {W}{W}, {W}{U}, or {U}{U}.": 1,
            "Drowned Catacomb (87) - {T}: Add {U} or {B}.": 1,
            "Command Tower (1) - {T}: Add one mana of any color in your commander's color identity.": 1,
            "Battlefield Forge (2) - {T}: Add {R} or {W}. Battlefield Forge deals 1 damage to you.": 1,
        }
        for message, net in cases.items():
            self.assertEqual(cr.mana_net(message), net, message)

    def test_mcnemar(self):
        self.assertAlmostEqual(cr.mcnemar([0] * 10, [1] * 10), 2 / 1024)
        self.assertEqual(cr.mcnemar([0] * 5 + [1] * 5, [1] * 5 + [0] * 5), 1.0)

    def test_parse_log(self):
        log = "\n".join([
            "MULLIGAN|Deck has mulliganed down to 7 cards.",
            "MULLIGAN|Deck has kept a hand of 7 cards",
            "TURN|Turn 1 (Goldfish 1)",
            "LAND|Goldfish 1 played Island (245)",
            "TURN|Turn 2 (Deck)",
            "LAND|Deck played Plains (93)",
            "MANA|Sol Ring (56) - {T}: Add {C}{C}.",
            "STACK_ADD|Deck cast Jace, Multiverse Architect",
            "TURN|Turn 3 (Goldfish 1)",
            "MANA|Island (245) - {T}: Add {U}.",
            "TURN|Turn 4 (Deck)",
            "LIFE|Life: Goldfish 1 40 > 33",
            "LIFE|Life: Deck 40 > 38",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "1.log")
            path.write_text(log)
            got = cr.parse_log(path, "Deck", "Jace, Multiverse Architect", 3, 40)
        self.assertEqual(got["mulligans"], 1)
        self.assertEqual(got["first_cast"], 1)
        self.assertEqual(got["spells"], [1, 0, 0])
        self.assertEqual(got["mana_spent"], [2, 0, 0])
        self.assertEqual(got["land_drops"], [1, 0, 0])
        self.assertEqual(got["damage"], [0, 7, 7])


class Verdicts(unittest.TestCase):
    def test_identical(self):
        code, out = run(rows(300), rows(300))
        self.assertEqual(code, 0, out)
        self.assertIn("VERDICT: IDENTICAL", out)

    def test_new_hashes_same_numbers_is_equivalent(self):
        code, out = run(rows(300), rows(300, rehash=True))
        self.assertEqual(code, 0, out)
        self.assertIn("VERDICT: EQUIVALENT", out)

    def test_engine_count_shift_is_changed(self):
        code, out = run(rows(300), rows(300, minus_shift=-1, rehash=True))
        self.assertEqual(code, 1, out)
        self.assertIn("VERDICT: CHANGED", out)
        self.assertIn(MINUS, out.split("VERDICT")[1])

    def test_redrawn_games_on_few_seeds_is_inconclusive(self):
        code, out = run(rows(300), rows(300, seed=2, redraw=True))
        self.assertEqual(code, 3, out)
        self.assertIn("VERDICT: INCONCLUSIVE", out)

    def test_error_only_in_candidate_is_changed(self):
        b = rows(300)
        b[5] = {**b[5], "status": "error", "log_hash": "x"}
        code, out = run(rows(300), b)
        self.assertEqual(code, 1, out)
        self.assertIn("timed out only in B", out)

    def test_game_we_lost_is_data_not_a_failure(self):
        b = rows(300, rehash=True)
        b[5] = {**b[5], "status": "other", "table_dead_round": None}
        code, out = run(rows(300), b)
        self.assertNotIn("only in B", out)
        self.assertIn("games we lost (status other) 1", out)

    def test_missing_file_is_bad_input(self):
        done = subprocess.run([sys.executable, str(HERE / "compare_runs.py"), "/nonexistent/a.jsonl",
                               "/nonexistent/b.jsonl"], capture_output=True, text=True)
        self.assertEqual(done.returncode, 2)


if __name__ == "__main__":
    unittest.main()
