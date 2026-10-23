#!/usr/bin/env python3
"""Run runner variants on the same seeds, concurrently, and compare speed and outcomes.

usage: bench.py [--shards N] <out-dir> <games> <variant>... (variant names from VARIANTS; "name*2" runs it twice)

--shards splits each variant's seeds across N processes, for outcomes. Leave it at 1 for timing: every shard
pays its own JVM startup and warmup.
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
    "nopol": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "opponent-stops": "upkeep,end_of_turn"}),
    "nopol_basics": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "opponent-stops": "upkeep,end_of_turn", "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "pol": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "multiverse-reforged.yaml")}),
    "pol_basics": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "multiverse-reforged.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "pol_nolife": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "multiverse-reforged-nolife.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "floor_a": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "jace-floor-a.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "floor_b": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "jace-floor-b.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "floor_c": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "jace-floor-c.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "floor4now": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE, SKIP_STATICS, CACHE_REPL, MEMO_REPL], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "jace-floor4-now.yaml"), "fish": str(HERE / "decks" / "goldfish-basics.dck")}),
    "policy": ("lazyperf", [INLINE, SEQUENTIAL, SKIP_STATE], {"opponents": "passive", "horizon": "10", "policy": str(HERE / "policies" / "multiverse-reforged.yaml")}),
}


def launch(out: Path, label: str, name: str, games: int, extra_jvm: list[str], shards: int = 1):
    home, jvm, opts = VARIANTS[name]
    procs = []
    per = -(-games // shards)
    for shard in range(shards):
        first, count = shard * per, min(per, games - shard * per)
        if count <= 0:
            break
        part = label if shards == 1 else f"{label}.{shard}"
        args = [str(HERE / "run.sh"), home, *jvm, *extra_jvm, "--", "--deck", str(DECK), "--fish", str(FISH),
                "--games", str(count), "--seed", str(1 + first), "--out", str(out / f"{part}.jsonl")]
        for key, value in opts.items():
            args += [f"--{key}", value]
        procs.append((part, subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=open(out / f"{part}.stderr", "w"))))
    return procs


def merge(out: Path, label: str, parts: list[str]):
    if parts == [label]:
        return
    with open(out / f"{label}.jsonl", "w") as rows, open(out / f"{label}.stderr", "w") as err:
        for part in parts:
            rows.write((out / f"{part}.jsonl").read_text())
            err.write((out / f"{part}.stderr").read_text())


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
    argv = sys.argv[1:]
    shards = 1
    if argv[:1] == ["--shards"]:
        shards, argv = int(argv[1]), argv[2:]
    out, games, names = Path(argv[0]).resolve(), int(argv[1]), argv[2:]
    out.mkdir(parents=True, exist_ok=True)
    runs = []
    for spec in names:
        name, _, times = spec.partition("*")
        for i in range(int(times or 1)):
            label = name if not times else f"{name}-{i + 1}"
            runs.append((label, launch(out, label, name, games, [], shards)))
    for label, procs in runs:
        for part, proc in procs:
            if proc.wait() != 0:
                print(f"{part}: exit {proc.returncode}", file=sys.stderr)
        merge(out, label, [part for part, _ in procs])
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
