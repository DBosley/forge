#!/usr/bin/env python3
"""simab.py <tag> <rounds> <cmd|con> <ntables> [games_per_table]
Ordinary Forge sim games (-q -c 900): base arm plus candidate arms, same decks and seeds, all arms of a table run
together, launch order rotates. Reports game wall per game vs base (paired, 95%) and result-line identity.
Arms: SIM_ARMS="base=~/src/forge-pr other=~/src/forge-<worktree> ..." (first is the base, each needs its desktop jar built)."""
import hashlib, math, os, random, re, statistics, subprocess, sys
tag, rounds, wl, ntab = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])
ngames = int(sys.argv[5]) if len(sys.argv) > 5 else 1
H = os.path.expanduser("~")
arms = dict(a.split("=") for a in os.environ["SIM_ARMS"].split())
names = list(arms)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "sim", tag); os.makedirs(out, exist_ok=True)
rng = random.Random(4242 if wl == "cmd" else 777)
ddir, per, fmt, seed0 = ("res/quest/commanderprecons", 4, "Commander", 1000) if wl == "cmd" else ("res/geneticaidecks", 2, "Constructed", 2000)
decks = sorted(os.listdir(os.path.join(arms[names[0]], "forge-gui", ddir)))
tables = [(rng.sample(decks, per), seed0 + i) for i in range(ntab)]
def cmd(arm, t):
    ds, seed = t
    wd = os.path.join(arms[arm], "forge-gui")
    jar = os.path.join(arms[arm], "forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar")
    return wd, ["taskset", "-c", os.environ.get("SIM_CPUS", "12-15"), "java", "-Xmx3g", "-jar", jar, "sim", "-D", os.path.join(wd, ddir), "-d", *ds, "-f", fmt,
                "-n", str(ngames), "-s", str(seed), "-c", "900", "-q"]
res = {}
for r in range(1, rounds + 1):
    for ti, t in enumerate(tables):
        k = (r + ti) % len(names); order = names[k:] + names[:k]
        procs = {}
        for arm in order:
            wd, c = cmd(arm, t)
            procs[arm] = subprocess.Popen(c, cwd=wd, stdout=open(f"{out}/{arm}_{r}_{ti}.log", "w"), stderr=subprocess.STDOUT)
        for p in procs.values():
            p.wait()
        line = []
        for arm in names:
            txt = open(f"{out}/{arm}_{r}_{ti}.log", errors="ignore").read()
            ms = [int(x) for x in re.findall(r"Game Result: Game \d+ ended in (?:a Draw! Took )?(\d+) ms", txt)]
            body = txt[txt.find("Simulation mode"):] if "Simulation mode" in txt else txt
            body = "\n".join(re.sub(r"\d+ ms", "", l) for l in body.splitlines()
                             if not l.startswith(("WARNING", "Warning", "SLF4J")) and "Match Duration" not in l)
            res[(arm, r, ti)] = (ms, hashlib.md5(body.encode()).hexdigest(), "Stopping slow match" in txt)
            line.append(f"{arm} {sum(ms)/1000:.1f}s/{len(ms)}")
        same = all(res[(a, r, ti)][1] == res[(names[0], r, ti)][1] for a in names)
        print(f"round {r} table {ti}: {'  '.join(line)} same_results={same}", flush=True)
for arm in names[1:]:
    lr = []; sa = sb = 0; same = n = 0; to = 0
    for (a, r, ti), v in res.items():
        if a != names[0]: continue
        b = res[(arm, r, ti)]
        n += 1; same += v[1] == b[1]; to += v[2] or b[2]
        for x, y in zip(v[0], b[0]):
            if x > 0 and y > 0:
                lr.append(math.log(y / x)); sa += x; sb += y
    m = statistics.mean(lr); se = statistics.stdev(lr) / math.sqrt(len(lr)) if len(lr) > 1 else 0
    print(f"{wl} {arm} vs {names[0]}: {len(lr)} games, mean {sa/len(lr)/1000:.2f}s -> {sb/len(lr)/1000:.2f}s ({sb/sa-1:+.1%}), "
          f"paired geo {math.exp(m)-1:+.1%} (95% {math.exp(m-1.96*se)-1:+.1%}..{math.exp(m+1.96*se)-1:+.1%}), "
          f"identical result logs {same}/{n} runs, timeouts {to}")
