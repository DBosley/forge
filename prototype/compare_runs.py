#!/usr/bin/env python3
"""Compare two goldfish runs on the same seeds: identical, equivalent within noise, or changed.

usage: compare_runs.py A B [--logs-a DIR] [--logs-b DIR] [options]

A and B are runner outputs: a row .jsonl file, a directory of .jsonl files (merged), or several paths joined
with commas. B is the candidate, A the baseline; differences are B - A. Rows may carry per-round snapshots
(-Dgoldfish.rounds=true); --logs-a/--logs-b point at -Dgoldfish.logDir directories (<seed>.log).

Exit status: 0 identical or equivalent, 1 changed, 3 inconclusive (more seeds needed), 2 bad input.
The contract (metrics, sources, thresholds) is in compare_runs.md.
"""

import argparse
import json
import math
import random
import re
import statistics
import sys
from pathlib import Path

OK_STATUSES = {"dead", "horizon", "other"}
DEFAULT_COMMANDER = "Jace, Multiverse Architect"
DEFAULT_COUNTS = ["jace_minus3=Jace, Multiverse Architect: -3", "jace_plus1=Jace, Multiverse Architect: +1",
                  "kitten_triggers=Displacer Kitten: ability"]
WORD_AMOUNTS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5}
SYMBOL = re.compile(r"\{([^}]+)\}")
LIFE = re.compile(r"^Life: (.+) (-?\d+) > (-?\d+)$")


def fail(message: str):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def load_rows(spec: str) -> tuple[dict[int, dict], list[str]]:
    files: list[Path] = []
    for part in spec.split(","):
        path = Path(part)
        if path.is_dir():
            files.extend(sorted(path.glob("*.jsonl")))
        elif path.is_file():
            files.append(path)
        else:
            fail(f"no such file or directory: {part}")
    if not files:
        fail(f"no .jsonl rows in {spec}")
    rows: dict[int, dict] = {}
    for f in files:
        for n, line in enumerate(f.read_text().splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as e:
                fail(f"{f}:{n}: {e}")
            seed = row.get("seed")
            if seed is None:
                fail(f"{f}:{n}: row has no seed")
            if seed in rows:
                fail(f"seed {seed} appears twice in {spec}")
            rows[seed] = row
    return rows, [str(f) for f in files]


def mana_net(message: str) -> int | None:
    at = message.find(": Add ")
    if at < 0:
        return None
    head, add = message[:at], message[at + len(": Add "):]
    cost = head.rsplit(" - ", 1)[-1]
    paid = 0
    for s in SYMBOL.findall(cost):
        if s.isdigit():
            paid += int(s)
        elif s not in ("T", "Q", "X"):
            paid += 1
    first = re.split(r",? or |, ", add.split(".")[0])[0]
    symbols = SYMBOL.findall(first)
    if symbols:
        made = len(symbols)
    else:
        word = re.match(r"(\w+) mana", first)
        made = WORD_AMOUNTS.get(word.group(1), 1) if word else 1
    return made - paid


def card_id(message: str) -> str | None:
    head = message.split(": Add ", 1)[0].rsplit(" - ", 1)[0]
    m = re.search(r"\((\d+)\)$", head)
    return m.group(1) if m else None


def parse_log(path: Path, player: str, commander: str, horizon: int, life: int) -> dict:
    """Per-round series from a game log. Mana attribution assumes opponents never tap mana (passive goldfish)."""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    rnd = 0
    opponent_lands: set[str] = set()
    for line in lines:
        kind, _, msg = line.partition("|")
        if kind == "LAND" and not msg.startswith(player + " played "):
            m = re.search(r"\((\d+)\)$", msg)
            if m:
                opponent_lands.add(m.group(1))
    lives: dict[str, int] = {}
    spells = [0] * horizon
    mana = [0] * horizon
    drops = [0] * horizon
    damage = [None] * horizon
    first_cast = None
    mulligans = 0
    unparsed = 0

    def dealt() -> int:
        return sum(life - max(v, 0) for v in lives.values())

    for line in lines:
        kind, _, msg = line.partition("|")
        if kind == "TURN" and msg.endswith(f"({player})"):
            if 1 <= rnd <= horizon:
                damage[rnd - 1] = dealt()
            rnd += 1
            continue
        if rnd > horizon:
            break
        idx = max(rnd, 1) - 1
        if kind == "MULLIGAN" and msg.startswith(player + " has mulliganed"):
            mulligans += 1
        elif kind == "STACK_ADD" and msg.startswith(player + " cast "):
            name = msg[len(player) + 6:].split(" targeting ", 1)[0].strip()
            spells[idx] += 1
            if name == commander and first_cast is None:
                first_cast = idx + 1
        elif kind == "MANA":
            if card_id(msg) in opponent_lands:
                continue
            net = mana_net(msg)
            if net is None:
                unparsed += 1
            else:
                mana[idx] += net
        elif kind == "LAND" and msg.startswith(player + " played "):
            drops[idx] += 1
        elif kind == "LIFE":
            m = LIFE.match(msg)
            if m and m.group(1) != player:
                lives[m.group(1)] = int(m.group(3))
    if 1 <= rnd <= horizon and damage[rnd - 1] is None:
        damage[rnd - 1] = dealt()
    played = min(rnd, horizon)
    final = dealt()
    damage = [d if d is not None else (final if i >= played - 1 else 0) for i, d in enumerate(damage)]
    return {"spells": spells, "mana_spent": mana, "land_drops": drops, "damage": damage,
            "first_cast": first_cast, "mulligans": mulligans, "rounds": played, "unparsed_mana": unparsed}


def opp_damage(lives: list[int], life: int) -> int:
    return sum(life - max(v, 0) for v in lives)


def fill(series: list, horizon: int, after) -> list:
    out = list(series[:horizon])
    while len(out) < horizon:
        out.append(after(out))
    return out


def game_values(row: dict, log: dict | None, cfg) -> dict[str, dict[str, float]]:
    """metric -> {source: value} for one game."""
    h, life, cmd, plan = cfg.horizon, cfg.life, cfg.commander, cfg.plan
    early = min(cfg.early, h)
    censor = h + 1
    tdr = row.get("table_dead_round")
    dead = tdr is not None and tdr <= h
    plays = row.get("plays") or {}
    final_damage = opp_damage(row.get("opponents_life") or [], life)
    v: dict[str, dict[str, float]] = {}

    def put(metric, source, value):
        if value is not None:
            v.setdefault(metric, {})[source] = float(value)

    put(f"dead_by_{h}", "rows", 1 if dead else 0)
    for r in range(1, h + 1):
        put(f"dead_by_{r}", "rows", 1 if tdr is not None and tdr <= r else 0)
    put("kill_round", "rows", tdr if dead else censor)
    put("damage", "rows", final_damage)
    put("commander_casts", "rows", plays.get(cmd, 0))
    for label, key in cfg.counts:
        put(f"count:{label}", "rows", plays.get(key, 0))

    def last_round_or_censor(count_key, seen):
        if seen is not None:
            return seen
        return (tdr if tdr is not None else row.get("our_turns", censor)) if plays.get(count_key, 0) > 0 else censor

    def per_round(metric, source, series):
        put(f"{metric}_early", source, sum(series[:early]) / early)
        for r in range(h):
            put(f"{metric}@{r + 1}", source, series[r])

    if "damage_by_round" in row:
        first = row.get("first_round") or {}
        put("commander_first_cast", "recorder", first.get(cmd, censor))
        put("plan_online", "recorder", first.get(plan, censor))
        put("mulligans", "recorder", row.get("mulligans"))
        put("kept_hand", "recorder", row.get("kept_hand"))
        per_round("mana_spent", "recorder", fill(row.get("mana_spent_by_round", []), h, lambda s: 0))
        per_round("mana_available", "recorder",
                  fill(row.get("mana_available_by_round", []), h, lambda s: s[-1] if s else 0))
        per_round("spells", "recorder", fill(row.get("spells_by_round", []), h, lambda s: 0))
        per_round("land_drops", "recorder", fill(row.get("land_drops_by_round", []), h, lambda s: 0))
        per_round("damage", "recorder", fill(row["damage_by_round"], h, lambda s: final_damage))

    snaps = row.get("rounds")
    if snaps is not None:
        by = {s["round"]: s for s in snaps if 1 <= s.get("round", 0) <= h}
        cast_seen = next((r for r in sorted(by) if cmd in by[r].get("cast", [])), None)
        put("commander_first_cast", "snapshots", last_round_or_censor(cmd, cast_seen))
        plan_seen = next((r for r in sorted(by)
                          if any(re.sub(r"#\d+:", ":", a) == plan for a in by[r].get("activations", []))), None)
        put("plan_online", "snapshots", last_round_or_censor(plan, plan_seen))
        played = max(by) if by else 0
        mana_av, spells, drops, dmg = [], [], [], []
        for r in range(1, h + 1):
            s = by.get(r)
            if s is None:
                mana_av.append(mana_av[-1] if mana_av else 0)
                spells.append(0)
                drops.append(0)
                dmg.append(final_damage if r > played else (dmg[-1] if dmg else 0))
            else:
                mana_av.append(s.get("mana", 0))
                spells.append(len(s.get("cast", [])))
                drops.append(len(s.get("land_played", [])))
                dmg.append(opp_damage(s.get("opponents_life", []), life))
        per_round("mana_available", "snapshots", mana_av)
        per_round("spells", "snapshots", spells)
        per_round("land_drops", "snapshots", drops)
        per_round("damage", "snapshots", dmg)

    if log is not None:
        put("commander_first_cast", "logs", log["first_cast"] if log["first_cast"] is not None else censor)
        put("mulligans", "logs", log["mulligans"])
        per_round("mana_spent", "logs", log["mana_spent"])
        per_round("spells", "logs", log["spells"])
        per_round("land_drops", "logs", log["land_drops"])
        per_round("damage", "logs", log["damage"])
    return v


SOURCE_ORDER = ["rows", "recorder", "snapshots", "logs"]


def pick_source(metric, seeds, va, vb) -> tuple[str | None, list[int]]:
    for source in SOURCE_ORDER:
        covered = [s for s in seeds if source in va[s].get(metric, {}) and source in vb[s].get(metric, {})]
        if covered and len(covered) >= 0.9 * len(seeds):
            return source, covered
    return None, []


def bootstrap(diffs: list[float], resamples: int, rng: random.Random) -> list[float]:
    n = len(diffs)
    if n == 0 or all(d == diffs[0] for d in diffs):
        return [diffs[0] if diffs else 0.0] * 2
    return sorted(sum(rng.choices(diffs, k=n)) / n for _ in range(resamples))


def percentile(sorted_values: list[float], q: float) -> float:
    if not sorted_values:
        return 0.0
    pos = q * (len(sorted_values) - 1)
    lo, hi = math.floor(pos), math.ceil(pos)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)


def mcnemar(a: list[float], b: list[float]) -> float:
    up = sum(1 for x, y in zip(a, b) if x == 0 and y == 1)
    down = sum(1 for x, y in zip(a, b) if x == 1 and y == 0)
    n = up + down
    if n == 0:
        return 1.0
    k = min(up, down)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def z_quantile(p: float) -> float:
    return statistics.NormalDist().inv_cdf(p)


PRIMARY = [
    ("dead_by_H", "table dead by round {h}", "rate"),
    ("kill_round", "table dead round (no kill = {c})", "mean"),
    ("damage", "damage dealt by game end", "mean"),
    ("commander_first_cast", "commander first cast round (never = {c})", "mean"),
    ("plan_online", "plan online: first {plan} (never = {c})", "mean"),
    ("COUNTS", "", "mean"),
    ("mana_spent_early", "mana spent per round, rounds 1-{e}", "mean"),
    ("mana_available_early", "mana available per round, rounds 1-{e}", "mean"),
    ("spells_early", "spells cast per round, rounds 1-{e}", "mean"),
    ("mulligans", "mulligans per game", "mean"),
]

CURVES = [
    ("dead_by_{r}", "table dead by round", "rate"),
    ("damage@{r}", "damage dealt by end of round", "mean"),
    ("mana_spent@{r}", "mana spent in round", "mean"),
    ("mana_available@{r}", "mana available in round", "mean"),
    ("spells@{r}", "spells cast in round", "mean"),
    ("land_drops@{r}", "land drops in round", "mean"),
]


def primary_metrics(cfg) -> list[tuple[str, str, str]]:
    out = []
    for key, label, kind in PRIMARY:
        if key == "dead_by_H":
            out.append((f"dead_by_{cfg.horizon}", label.format(h=cfg.horizon), kind))
        elif key == "COUNTS":
            for label_key, plays_key in cfg.counts:
                out.append((f"count:{label_key}", f"{plays_key} per game", kind))
        else:
            out.append((key, label.format(c=cfg.horizon + 1, plan=cfg.plan, e=min(cfg.early, cfg.horizon)), kind))
    return out


def compare_metric(metric, kind, seeds, va, vb, cfg, rng, k_tests) -> dict | None:
    source, covered = pick_source(metric, seeds, va, vb)
    if source is None:
        return None
    a = [va[s][metric][source] for s in covered]
    b = [vb[s][metric][source] for s in covered]
    diffs = [y - x for x, y in zip(a, b)]
    n = len(diffs)
    mean_a, mean_b = sum(a) / n, sum(b) / n
    diff = sum(diffs) / n
    boot = bootstrap(diffs, cfg.resamples, rng)
    lo, hi = percentile(boot, cfg.alpha / 2), percentile(boot, 1 - cfg.alpha / 2)
    se = statistics.pstdev(boot) if len(set(boot)) > 1 else 0.0
    z_adj = z_quantile(1 - cfg.alpha / (2 * k_tests))
    sd_a = statistics.pstdev(a) if n > 1 else 0.0
    sd_ref = sd_a if sd_a > 0 else (statistics.pstdev(b) if n > 1 else 0.0)
    margin = z_quantile(1 - cfg.alpha / 2) * sd_ref / math.sqrt(cfg.ref_games)
    result = {"metric": metric, "source": source, "n": n, "a": mean_a, "b": mean_b, "diff": diff,
              "ci": [lo, hi], "se": se, "margin": margin, "changed_games": sum(1 for d in diffs if d != 0)}
    if kind == "rate":
        result["mcnemar_p"] = mcnemar(a, b)
        significant = result["mcnemar_p"] < cfg.alpha / k_tests
    else:
        significant = abs(diff) > z_adj * se if se > 0 else diff != 0
    if significant:
        result["verdict"] = "changed"
    elif lo >= -margin and hi <= margin:
        result["verdict"] = "equivalent"
    else:
        result["verdict"] = "inconclusive"
        half = max(hi - diff, diff - lo)
        room = margin - abs(diff)
        result["seeds_needed"] = math.ceil(n * (half / room) ** 2) if room > 0 and half > 0 else None
    return result


def divergence(seeds, rows_a, rows_b, logs_a, logs_b, player, horizon) -> dict[str, int]:
    hist: dict[str, int] = {}
    for s in seeds:
        if rows_a[s].get("log_hash") == rows_b[s].get("log_hash"):
            continue
        pa, pb = logs_a / f"{s}.log", logs_b / f"{s}.log"
        if not (pa.is_file() and pb.is_file()):
            hist["no log"] = hist.get("no log", 0) + 1
            continue
        la = pa.read_text(encoding="utf-8", errors="replace").splitlines()
        lb = pb.read_text(encoding="utf-8", errors="replace").splitlines()
        rnd = 0
        where = "same log text"
        for x, y in zip(la, lb):
            if x != y:
                where = "pre-game" if rnd == 0 else f"round {min(rnd, horizon)}"
                break
            if x.startswith("TURN|") and x.endswith(f"({player})"):
                rnd += 1
        else:
            if len(la) != len(lb):
                where = "game length"
        hist[where] = hist.get(where, 0) + 1
    return hist


def fmt(value: float, kind: str) -> str:
    return f"{100 * value:.1f}%" if kind == "rate" else f"{value:.3f}"


def fmt_diff(value: float, kind: str) -> str:
    return f"{100 * value:+.1f}pt" if kind == "rate" else f"{value:+.3f}"


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("a", help="baseline rows: .jsonl, directory of .jsonl, or comma-separated")
    p.add_argument("b", help="candidate rows, same seeds")
    p.add_argument("--logs-a", type=Path, help="baseline -Dgoldfish.logDir directory")
    p.add_argument("--logs-b", type=Path, help="candidate -Dgoldfish.logDir directory")
    p.add_argument("--commander", default=DEFAULT_COMMANDER)
    p.add_argument("--plan", help="plays key whose first round is 'plan online' (default: '<commander>: -3')")
    p.add_argument("--count", action="append", metavar="LABEL=PLAYS_KEY",
                   help="deck engine counter from the row's plays; repeat for several (default: the reference "
                        "deck's Jace -3, Jace +1 and Displacer Kitten triggers)")
    p.add_argument("--player", default="Deck", help="our player's name in the game log")
    p.add_argument("--horizon", type=int, default=10)
    p.add_argument("--early", type=int, default=6, help="rounds averaged for the per-round mana and spell metrics")
    p.add_argument("--life", type=int, default=40, help="opponents' starting life")
    p.add_argument("--ref-games", type=int, default=1000,
                   help="batch size whose sampling noise sets each margin (default 1000)")
    p.add_argument("--alpha", type=float, default=0.05)
    p.add_argument("--resamples", type=int, default=2000)
    p.add_argument("--curves", action="store_true", help="also print per-round curves")
    p.add_argument("--json", type=Path, help="write the full result as JSON")
    cfg = p.parse_args()
    cfg.plan = cfg.plan or f"{cfg.commander}: -3"
    counts = cfg.count or DEFAULT_COUNTS
    cfg.counts = []
    for c in counts:
        label, sep, key = c.partition("=")
        if not sep or not label or not key:
            p.error(f"--count wants LABEL=PLAYS_KEY, got {c!r}")
        cfg.counts.append((label, key))
    if (cfg.logs_a is None) != (cfg.logs_b is None):
        p.error("give both --logs-a and --logs-b, or neither")
    for logs in (cfg.logs_a, cfg.logs_b):
        if logs is not None and not logs.is_dir():
            p.error(f"not a directory: {logs}")

    rows_a, files_a = load_rows(cfg.a)
    rows_b, files_b = load_rows(cfg.b)
    seeds = sorted(set(rows_a) & set(rows_b))
    if not seeds:
        fail("the two runs share no seeds")
    only_a, only_b = sorted(set(rows_a) - set(rows_b)), sorted(set(rows_b) - set(rows_a))

    broken = {s for s in seeds if rows_b[s].get("status") not in OK_STATUSES
              and rows_a[s].get("status") in OK_STATUSES}
    abnormal = {s for s in seeds if rows_a[s].get("status") not in OK_STATUSES
                or rows_b[s].get("status") not in OK_STATUSES}
    same_hash = [s for s in seeds if rows_a[s].get("log_hash") is not None
                 and rows_a[s].get("log_hash") == rows_b[s].get("log_hash")]
    usable = [s for s in seeds if s not in abnormal]

    def log_for(logs: Path | None, s: int) -> dict | None:
        if logs is None or not (logs / f"{s}.log").is_file():
            return None
        return parse_log(logs / f"{s}.log", cfg.player, cfg.commander, cfg.horizon, cfg.life)

    va = {s: game_values(rows_a[s], log_for(cfg.logs_a, s), cfg) for s in usable}
    vb = {s: game_values(rows_b[s], log_for(cfg.logs_b, s), cfg) for s in usable}

    rng = random.Random(20261023)
    metrics = primary_metrics(cfg)
    k_tests = max(1, sum(1 for m, _, _ in metrics if pick_source(m, usable, va, vb)[0]))
    results = []
    for metric, label, kind in metrics:
        r = compare_metric(metric, kind, usable, va, vb, cfg, rng, k_tests)
        if r is None:
            results.append({"metric": metric, "label": label, "kind": kind, "verdict": "unavailable"})
        else:
            results.append({**r, "label": label, "kind": kind})

    curves = []
    if cfg.curves:
        for template, label, kind in CURVES:
            for r in range(1, cfg.horizon + 1):
                c = compare_metric(template.format(r=r), kind, usable, va, vb, cfg, rng, k_tests)
                if c is not None:
                    curves.append({**c, "label": f"{label} {r}", "kind": kind})

    div = divergence(seeds, rows_a, rows_b, cfg.logs_a, cfg.logs_b, cfg.player, cfg.horizon) \
        if cfg.logs_a and cfg.logs_b else None

    tested = [r for r in results if r["verdict"] != "unavailable"]
    changed = [r for r in tested if r["verdict"] == "changed"]
    inconclusive = [r for r in tested if r["verdict"] == "inconclusive"]
    if broken:
        verdict = "CHANGED"
    elif len(same_hash) == len(seeds):
        verdict = "IDENTICAL"
    elif changed:
        verdict = "CHANGED"
    elif inconclusive:
        verdict = "INCONCLUSIVE"
    else:
        verdict = "EQUIVALENT"

    print(f"A: {', '.join(files_a)} ({len(rows_a)} rows)")
    print(f"B: {', '.join(files_b)} ({len(rows_b)} rows)")
    if only_a or only_b:
        print(f"seeds only in A: {len(only_a)}, only in B: {len(only_b)} (compared on the {len(seeds)} shared)")
    print(f"paired seeds: {len(seeds)}   identical log_hash: {len(same_hash)}/{len(seeds)} "
          f"({100 * len(same_hash) / len(seeds):.1f}%)")
    if abnormal:
        statuses = sorted({rows_a[s].get('status') for s in abnormal} | {rows_b[s].get('status') for s in abnormal})
        print(f"games left out for status {statuses}: {len(abnormal)} (seeds {sorted(abnormal)[:10]}"
              f"{' ...' if len(abnormal) > 10 else ''})")
    if broken:
        print(f"B errored, hung or timed out where A finished: seeds {sorted(broken)}")
    if div is not None and div:
        order = ["pre-game"] + [f"round {r}" for r in range(1, cfg.horizon + 1)] + ["game length", "same log text",
                                                                                   "no log"]
        print("first log difference: " + ", ".join(f"{k} {div[k]}" for k in order if k in div))
    for side, rows in (("A", rows_a), ("B", rows_b)):
        wins = [rows[s]["table_dead_round"] for s in usable if rows[s].get("table_dead_round") is not None]
        lost = sum(1 for s in usable if rows[s].get("status") == "other")
        print(f"{side}: earliest round win {min(wins) if wins else '-'}, games we lost (status other) {lost}")
    w = max(len(r["label"]) for r in results + curves)
    print()
    print(f"{'metric':<{w}} {'source':<9} {'n':>5} {'A':>8} {'B':>8} {'B-A':>9} {'95% CI':>19} "
          f"{'margin':>8}  verdict")
    for r in results:
        if r["verdict"] == "unavailable":
            print(f"{r['label']:<{w}} {'-':<9} {'':>5} {'':>8} {'':>8} {'':>9} {'':>19} {'':>8}  "
                  f"unavailable (needs snapshots, logs or recorder fields)")
            continue
        kind = r["kind"]
        ci = f"[{fmt_diff(r['ci'][0], kind)}, {fmt_diff(r['ci'][1], kind)}]"
        margin = f"±{100 * r['margin']:.1f}pt" if kind == "rate" else f"±{r['margin']:.3f}"
        note = r["verdict"]
        if r["verdict"] == "inconclusive":
            note += f" (about {r['seeds_needed']} seeds)" if r.get("seeds_needed") else " (estimate beyond margin)"
        if "mcnemar_p" in r:
            note += f", McNemar p={r['mcnemar_p']:.3g}"
        print(f"{r['label']:<{w}} {r['source']:<9} {r['n']:>5} {fmt(r['a'], kind):>8} {fmt(r['b'], kind):>8} "
              f"{fmt_diff(r['diff'], kind):>9} {ci:>19} {margin:>8}  {note}")
    if curves:
        print()
        print(f"{'per round':<{w}} {'source':<9} {'n':>5} {'A':>8} {'B':>8} {'B-A':>9} {'95% CI':>19}")
        for r in curves:
            kind = r["kind"]
            ci = f"[{fmt_diff(r['ci'][0], kind)}, {fmt_diff(r['ci'][1], kind)}]"
            flag = "  *" if r["verdict"] == "changed" else ""
            print(f"{r['label']:<{w}} {r['source']:<9} {r['n']:>5} {fmt(r['a'], kind):>8} {fmt(r['b'], kind):>8} "
                  f"{fmt_diff(r['diff'], kind):>9} {ci:>19}{flag}")
    print()
    if verdict == "IDENTICAL":
        print("VERDICT: IDENTICAL: every paired game has the same log_hash, so no recorded number can differ.")
        if len(seeds) < 200:
            print(f"(only {len(seeds)} seeds: rare code paths may not have run; 200 or more is the norm)")
    elif verdict == "CHANGED":
        parts = [f"{r['label']} {fmt_diff(r['diff'], r['kind'])} "
                 f"(95% CI {fmt_diff(r['ci'][0], r['kind'])} to {fmt_diff(r['ci'][1], r['kind'])})" for r in changed]
        if broken:
            parts.insert(0, f"{len(broken)} games errored, hung or timed out only in B")
        print("VERDICT: CHANGED: " + "; ".join(parts))
    elif verdict == "INCONCLUSIVE":
        need = [r["seeds_needed"] for r in inconclusive if r.get("seeds_needed")]
        beyond = [r["label"] for r in inconclusive if not r.get("seeds_needed")]
        msg = (f"VERDICT: INCONCLUSIVE: no metric changed significantly, but {len(inconclusive)} intervals are wider "
               f"than their margins")
        if need:
            msg += f"; about {max(need)} paired seeds would settle them"
        if beyond:
            msg += f"; point estimate outside the margin: {', '.join(beyond)}"
        print(msg)
    else:
        print("VERDICT: EQUIVALENT: no metric changed, and every interval lies within its margin.")

    if cfg.json:
        cfg.json.write_text(json.dumps({
            "verdict": verdict, "a": files_a, "b": files_b, "paired_seeds": len(seeds),
            "identical_hashes": len(same_hash), "seeds_only_in_a": only_a, "seeds_only_in_b": only_b,
            "abnormal_seeds": sorted(abnormal), "broken_in_b": sorted(broken), "divergence": div,
            "tests": k_tests, "metrics": results, "curves": curves,
        }, indent=1))
    return {"IDENTICAL": 0, "EQUIVALENT": 0, "CHANGED": 1, "INCONCLUSIVE": 3}[verdict]


if __name__ == "__main__":
    sys.exit(main())
