#!/usr/bin/env python3
"""Summarise a Java Flight Recorder file: hottest methods and the Forge call paths that reach them.

usage: profile_summary.py <recording.jfr> [frames-to-show]
"""

import collections
import json
import subprocess
import sys


def samples(path: str) -> list[list[str]]:
    raw = subprocess.run(["jfr", "print", "--json", "--stack-depth", "200", "--events", "jdk.ExecutionSample", path],
                         capture_output=True, text=True, check=True).stdout
    return [[f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}"
             for f in e["values"]["stackTrace"]["frames"]]
            for e in json.loads(raw)["recording"]["events"]]


def main():
    stacks = samples(sys.argv[1])
    depth = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    total = len(stacks)
    print(f"{total} samples")

    print("\n== top frames (self time)")
    for name, n in collections.Counter(s[0] for s in stacks if s).most_common(depth):
        print(f"{n / total:6.1%}  {name}")

    print("\n== inclusive time, forge methods")
    inclusive = collections.Counter()
    for s in stacks:
        inclusive.update({f for f in s if f.startswith("forge.")})
    for name, n in inclusive.most_common(depth * 2):
        print(f"{n / total:6.1%}  {name}")


if __name__ == "__main__":
    main()
