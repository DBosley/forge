#!/usr/bin/env bash
# Plays seeds 1..N of the baseline deck with the standard arguments and compares them with out/final-1000:
# IDENTICAL or EQUIVALENT unless the change means to alter play. Usage: ./check.sh [games, default 200]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
games="${1:-200}"
baseline="$here/out/final-1000/rows.jsonl"
if [[ ! -f "$baseline" ]]; then
  echo "no baseline at $baseline: play seeds 1-1000 with the standard arguments into out/final-1000 first" >&2
  exit 2
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
threads=$(( $(nproc) < 12 ? $(nproc) : 12 ))
"$here/run.sh" lazyperf -- --deck "$here/decks/multiverse-reforged.dck" --fish "$here/decks/goldfish-basics.dck" \
  --games "$games" --seed 1 --horizon 10 --opponents passive --opponent-stops upkeep,end_of_turn \
  --policy "$here/policies/multiverse-reforged.yaml" --threads "$threads" --out "$work/rows.jsonl" \
  > "$work/stdout.txt" 2> "$work/stderr.txt"
echo "failed casts: $(grep -c "Didn't find what to pay" "$work/stdout.txt" || true)"
python3 "$here/compare_runs.py" "$baseline" "$work/rows.jsonl"
