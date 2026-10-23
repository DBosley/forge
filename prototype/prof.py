#!/usr/bin/env python3
"""JFR stack queries. usage:
  prof.py <f.jfr> top [n]            self + inclusive forge
  prof.py <f.jfr> under <substr> [n] children of the first matching frame (inclusive, % of all samples)
  prof.py <f.jfr> callers <substr> [n]
  prof.py <f.jfr> incl <substr>...   inclusive % for each substring
"""
import collections, json, os, pickle, subprocess, sys

def load(path):
    cache = path + ".pkl"
    if os.path.exists(cache) and os.path.getmtime(cache) > os.path.getmtime(path):
        return pickle.load(open(cache, "rb"))
    raw = subprocess.run(["jfr", "print", "--json", "--stack-depth", "400", "--events", "jdk.ExecutionSample", path],
                         capture_output=True, text=True, check=True).stdout
    stacks = []
    for e in json.loads(raw)["recording"]["events"]:
        st = e["values"]["stackTrace"]
        if not st:
            continue
        thread = (e["values"].get("sampledThread") or {}).get("javaName", "?")
        stacks.append((thread, tuple(f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}" for f in st["frames"])))
    pickle.dump(stacks, open(cache, "wb"))
    return stacks

def main():
    path, cmd = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    stacks = load(path)
    total = len(stacks)
    print(f"{total} samples; threads: {collections.Counter(t for t, _ in stacks).most_common(5)}")
    if cmd == "top":
        n = int(rest[0]) if rest else 30
        print("== self")
        for name, c in collections.Counter(s[0] for _, s in stacks).most_common(n):
            print(f"{c/total:6.1%}  {name}")
        print("== inclusive forge")
        inc = collections.Counter()
        for _, s in stacks:
            inc.update({f for f in s if f.startswith("forge.")})
        for name, c in inc.most_common(n * 2):
            print(f"{c/total:6.1%}  {name}")
    elif cmd == "under":
        key = rest[0]; n = int(rest[1]) if len(rest) > 1 else 30
        kids = collections.Counter(); hit = 0
        for _, s in stacks:
            # outermost matching frame
            idx = [i for i, f in enumerate(s) if key in f]
            if not idx:
                continue
            hit += 1
            i = idx[-1]
            kids[s[i - 1] if i > 0 else "<self>"] += 1
        print(f"{hit/total:6.1%} under {key}")
        for name, c in kids.most_common(n):
            print(f"{c/total:6.1%}  {name}")
    elif cmd == "callers":
        key = rest[0]; n = int(rest[1]) if len(rest) > 1 else 30
        par = collections.Counter(); hit = 0
        for _, s in stacks:
            idx = [i for i, f in enumerate(s) if key in f]
            if not idx:
                continue
            hit += 1
            i = idx[-1]
            par[s[i + 1] if i + 1 < len(s) else "<root>"] += 1
        print(f"{hit/total:6.1%} in {key}")
        for name, c in par.most_common(n):
            print(f"{c/total:6.1%}  {name}")
    elif cmd == "incl":
        for key in rest:
            c = sum(1 for _, s in stacks if any(key in f for f in s))
            print(f"{c/total:6.1%}  {key}")

main()
