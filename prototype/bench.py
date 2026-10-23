#!/usr/bin/env python3
"""Run runner variants on the same seeds, concurrently, and compare speed and outcomes.

usage: bench.py <out-dir> <games> <variant>... (variant names from VARIANTS; "name*2" runs it twice)
"""

import json
import statistics
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DECK = HERE / "decks" / "multiverse-reforged.dck"
FISH = HERE / "decks" / "goldfish.dck"
WARMUP = 3

INLINE = "-Dforge.ai.inlineEvaluation=true"
SEQUENTIAL = "-Dforge.ai.sequentialAttacks=true"
SKIP_LIBRARY = "-Dforge.experiment.skipLibraryReplacements=true"
SKIP_STATE = "-Dforge.game.skipRedundantStateChecks=true"
SKIP_STATICS = "-Dforge.game.skipUnchangedStatics=true"
CACHE_REPL = "-Dforge.game.cacheReplacementLists=true"
MEMO_REPL = "-Dforge.game.memoReplacementCandidates=true"

VARIANTS = {
    "stock": ("eager", [], {"opponents": "ai", "horizon": "0"}),
    "base": ("eager", [], {"opponents": "ai", "horizon": "10"}),
    "passive": ("eager", [], {"opponents": "passive", "horizon": "10"}),
    "p2": ("eager", [INLINE], {"opponents": "ai", "horizon": "10"}),
    "p3": ("eager", [SEQUENTIAL], {"opponents": "ai", "horizon": "10"}),
    "lazy": ("lazy", [], {"opponents": "ai", "horizon": "10"}),
    "perf": ("perf", [], {"opponents": "ai", "horizon": "10"}),
    "all": ("lazyperf", [INLINE, SEQUENTIAL], {"opponents": "passive", "horizon": "10"}),
    "det": ("eager", [INLINE, SEQUENTIAL], {"opponents": "ai", "horizon": "10"}),
    "detperf": ("perf", [INLINE, SEQUENTIAL], {"opponents": "ai", "horizon": "10"}),
    "skip": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_LIBRARY], {"opponents": "passive", "horizon": "10"}),
    "allstate": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE], {"opponents": "passive", "horizon": "10"}),
    "detstate": ("eager", [INLINE, SEQUENTIAL, SKIP_STATE], {"opponents": "ai", "horizon": "10"}),
    "statics": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS], {"opponents": "passive", "horizon": "10"}),
    "turns": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE], {"opponents": "passive", "horizon": "10", "opponent-turns": "skip"}),
    "both": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS], {"opponents": "passive", "horizon": "10", "opponent-turns": "skip"}),
    "detstatics": ("eager", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS], {"opponents": "ai", "horizon": "10"}),
    "stops": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS], {"opponents": "passive", "horizon": "10", "opponent-stops": "upkeep,end_of_turn"}),
    "nostops": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS], {"opponents": "passive", "horizon": "10", "opponent-stops": "none"}),
    "cache": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL], {"opponents": "passive", "horizon": "10", "opponent-stops": "upkeep,end_of_turn"}),
    "detcache": ("eager", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL], {"opponents": "ai", "horizon": "10"}),
    "memo": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "opponent-stops": "upkeep,end_of_turn"}),
    "detmemo": ("eager", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "ai", "horizon": "10"}),
    "policy": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "multiverse-reforged.yaml")}),
}


def launch(out: Path, label: str, name: str, games: int, extra_jvm: list[str]):
    home, jvm, opts = VARIANTS[name]
    args = [str(HERE / "run.sh"), home, *jvm, *extra_jvm, "--", "--deck", str(DECK), "--fish", str(FISH),
            "--games", str(games), "--seed", "1", "--out", str(out / f"{label}.jsonl")]
    for key, value in opts.items():
        args += [f"--{key}", value]
    return subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=open(out / f"{label}.stderr", "w"))


def summary(out: Path, label: str) -> dict:
    rows = [json.loads(line) for line in (out / f"{label}.jsonl").read_text().splitlines()]
    meta = next(json.loads(line[5:]) for line in (out / f"{label}.stderr").read_text().splitlines()
                if line.startswith("META "))
    timed = rows[WARMUP:] if len(rows) > WARMUP else rows
    ms = [r["ms"] for r in timed]
    per_turn = [r["ms"] / max(1, r["our_turns"]) for r in timed]
    dead = [r["table_dead_round"] for r in rows if r.get("table_dead_round")]
    return {
        "label": label, "games": len(rows), "startup_ms": meta["startup_ms"],
        "mean_ms": statistics.mean(ms), "median_ms": statistics.median(ms), "max_ms": max(ms),
        "ms_per_our_turn": statistics.mean(per_turn),
        "dead_by_10": sum(1 for d in dead if d <= 10) / len(rows),
        "earliest_win": min(dead, default=None),
        "mean_dead_round": statistics.mean(dead) if dead else None,
        "statuses": {s: sum(1 for r in rows if r["status"] == s) for s in {r["status"] for r in rows}},
        "hashes": [r["log_hash"] for r in rows],
    }


def main():
    out, games, names = Path(sys.argv[1]).resolve(), int(sys.argv[2]), sys.argv[3:]
    out.mkdir(parents=True, exist_ok=True)
    runs = []
    for spec in names:
        name, _, times = spec.partition("*")
        for i in range(int(times or 1)):
            label = name if not times else f"{name}-{i + 1}"
            runs.append((label, launch(out, label, name, games, [])))
    for label, proc in runs:
        if proc.wait() != 0:
            print(f"{label}: exit {proc.returncode}", file=sys.stderr)
    results = [summary(out, label) for label, _ in runs]
    (out / "summary.json").write_text(json.dumps(results, indent=1))
    print(f"{'variant':<12}{'startup':>9}{'mean ms':>9}{'median':>8}{'max':>8}{'ms/turn':>9}"
          f"{'dead<=10':>9}{'earliest':>9}{'mean rnd':>9}  statuses")
    for r in results:
        mean_round = f"{r['mean_dead_round']:.2f}" if r["mean_dead_round"] else "-"
        print(f"{r['label']:<12}{r['startup_ms']:>9}{r['mean_ms']:>9.0f}{r['median_ms']:>8.0f}{r['max_ms']:>8}"
              f"{r['ms_per_our_turn']:>9.0f}{r['dead_by_10']:>9.0%}{str(r['earliest_win'] or '-'):>9}"
              f"{mean_round:>9}  {r['statuses']}")
    by_name = {}
    for r in results:
        by_name.setdefault(r["label"].rsplit("-", 1)[0], []).append(r)
    for name, group in by_name.items():
        if len(group) > 1:
            same = sum(a == b for a, b in zip(group[0]["hashes"], group[1]["hashes"]))
            print(f"determinism {name}: {same}/{len(group[0]['hashes'])} games identical across repeats")


if __name__ == "__main__":
    main()
