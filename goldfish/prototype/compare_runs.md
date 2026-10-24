# compare_runs: what a goldfish run must keep

A speedup to the runner is accepted only if the data the owner reads from a goldfish run stays the same.
`compare_runs.py` checks that on two runs over the same seeds and answers in one command.

```
python3 compare_runs.py <baseline> <candidate> [--logs-a DIR --logs-b DIR] [--curves] [--json out.json]
```

`<baseline>` and `<candidate>` are row files from `Runner` (`--out`), a directory of them (shards are merged), or
several paths joined with commas. Run both arms with `-Dgoldfish.rounds=true` and `-Dgoldfish.logDir=<dir>` to get
every metric; with rows alone the per-round metrics show as unavailable.

Exit status: 0 identical or equivalent (accept), 1 changed (reject), 3 inconclusive (run more seeds), 2 bad input.
Tests: `python3 -m unittest test_compare_runs` in this directory.

## What the owner reads from a goldfish run

Sources: Rebell Lily's goldfish method (`mtg-tools/docs/deckbuilding.md`, "Goldfishing"), the `goldfish` skill,
`docs/spec.md` (game row), `docs/agent-brief.md` (accuracy), and the Python model's metrics
(`mtg-tools/mtg/goldfish/autopilot.py`, `jace.py`).

- Does the plan work: the round the commander lands, the round the plan comes online, enablers feeding payoffs.
- Keepable openers: mulligans.
- Mana available and spent, and spells cast, per turn, over the first six turns (the rough goldfish).
- Outcome: table dead by round, damage by round, earliest round win (always shown).
- Deck engine counts compared with the Python model: for Multiverse Reforged, Jace −3 and +1 per game and
  Displacer Kitten triggers.

## The contract

Every metric is a per-game number, compared game by game on the shared seeds (B − A).

| Metric | Per game | Source today |
|---|---|---|
| Table dead by round 10 | 1 if all three opponents lost by round 10 | row `table_dead_round` |
| Table dead round | round the table died, 11 if it didn't | row |
| Damage dealt | Σ over opponents of 40 − max(life, 0) at game end | row `opponents_life` |
| Commander first cast round | 11 if never cast | snapshots `cast`, or logs |
| Plan online | first round of `Jace, Multiverse Architect: -3`, 11 if never | snapshots `activations` |
| Jace −3, Jace +1 | activations per game (Spark Double copies count as Jace) | row `plays` |
| Displacer Kitten triggers | Kitten's cast trigger put on the stack, about one flicker each | row `plays` |
| Mana spent per round, rounds 1-6 | logs: mana made by our mana abilities, net of their mana costs | logs |
| Mana available per round, rounds 1-6 | AI estimate of every mana source we control, at our end step | snapshots `mana` |
| Spells cast per round, rounds 1-6 | | snapshots, or logs |
| Mulligans | including the free first one | logs |

`--curves` adds the same comparison per round for: table dead by round 1-10, damage by end of round, mana spent,
mana available, spells cast and land drops. These are for reading, not for the verdict. The header also prints
the share of games with the same `log_hash`, the earliest round win of each arm and, with logs, the round of the
first log line that differs between the arms.

The engine counters and the commander are flags (`--commander`, `--plan`, `--count LABEL=PLAYS_KEY`, repeatable);
the defaults are the reference deck's.

## Verdict

1. **Identical**: every shared seed has the same `log_hash`. Same games, so every number is the same.
2. **Changed**: B errored, hung or timed out on a seed A finished, or any contract metric differs significantly.
   Rates use McNemar's exact test; means use a paired bootstrap (2,000 resamples over seeds) with a normal
   interval from its standard error. The level is Bonferroni-corrected over the metrics tested: 0.05 / 12
   (two-sided z = 2.87) for the reference deck. Status `other` is a game we lost (every one in the validation
   runs drew from an empty library); it counts as data, not a failure.
3. **Equivalent**: nothing changed, and every metric's 95% paired-bootstrap interval for B − A lies inside its
   margin.
4. **Inconclusive**: nothing changed, but some interval is wider than its margin. The report gives the number of
   paired seeds that would settle it, assuming the estimate stays where it is.

**Margin.** A change is within noise when it is smaller than the sampling noise of the 1,000-game batch the owner
reads results from: margin = 1.96 × σ / √1000, with σ the per-game standard deviation of the metric in A
(`--ref-games` changes 1000). For table dead by round 10 at 78% that is ±2.6 points, the "about ±2.5 points at
1000 games" in the brief. Margins for the reference deck, from the 1,000-seed baseline:

| Metric | Baseline (seeds 1-1000) | Margin |
|---|---:|---:|
| Table dead by round 10 | 77.8% | ±2.6 pt |
| Table dead round | 9.50 | ±0.067 |
| Damage dealt | 110.0 | ±1.40 |
| Commander first cast round | 5.71 | ±0.121 |
| Plan online (first Jace −3) | 6.16 | ±0.119 |
| Jace −3 per game | 4.20 | ±0.153 |
| Jace +1 per game | 3.39 | ±0.135 |
| Displacer Kitten triggers per game | 1.99 | ±0.164 |
| Mana spent per round, rounds 1-6 (log) | 3.18 | ±0.058 |
| Mana available per round, rounds 1-6 | 4.48 | ±0.055 |
| Spells cast per round, rounds 1-6 | 0.87 | ±0.019 |
| Mulligans per game | 0.73 | ±0.059 |

**Seeds needed.** When play diverges, the paired difference of two games is at worst as noisy as two independent
games, so its variance is up to twice one run's in every game that changed. The interval of B − A then fits inside
the margin only with more seeds than the reference batch: at most about 2,000 × (share of games that changed). A
change that re-deals every game needs about 2,000 seeds; one that touches a third of them, about 700. In practice
it is less, because diverged games stay correlated: the attack-order change below diverged 72% of games and was
equivalent at 200 seeds, the recorder change diverged 70% and needed about 1,300.

## Validation (2026-10-23, forge `goldfish-v2` jars, runner as of today, standard flags, policy, passive opponents)

Baseline A: `policies/multiverse-reforged.yaml`, seeds 1-200 or 1-1000, rows, snapshots and logs.

| Case | Seeds | Same `log_hash` | Verdict (exit) | Detail |
|---|---:|---:|---|---|
| Same build, run twice at once | 200 | 200 | IDENTICAL (0) | |
| Same build, separate processes | 200 | 200 | IDENTICAL (0) | seeds 1-200 of the 1,000-seed run against the 200-seed run |
| Recorder with timers added | 200 | 200 | IDENTICAL (0) | the cost measurement below |
| Known change: `multiverse-reforged-pymatch.yaml` | 200 | 10 | CHANGED (1) | table dead round +0.195 [+0.110, +0.285]; Jace −3 −1.36; Jace +1 +1.18. Dead by 10 −4.5 pt, McNemar p = 0.064: not yet significant alone |
| Known change, same | 1,000 | 60 | CHANGED (1) | dead by 10 77.8% → 73.9%, −3.9 pt [−5.9, −1.9], p = 0.0004; table dead round +0.195; Jace −3 −1.40; Jace +1 +1.29. Mana, spells, mulligans, commander and plan timing equivalent |
| Near null: `attack: threat` against `focus_lowest` | 200 | 61 | EQUIVALENT (0) | largest: Kitten −0.06 [−0.14, −0.005] inside ±0.17 |
| Near null, same | 1,000 | 279 | EQUIVALENT (0) | dead by 10 −0.1 pt [−0.4, +0.2]; table dead round +0.009 [+0.002, +0.016]; logs first differ in rounds 2-10 |
| Recorder change: snapshots off (the change below) against today's recorder | 1,000 | 297 | INCONCLUSIVE (3) | all within noise (dead by 10 +0.1 pt [−1.8, +2.1]); Jace −3 and Kitten intervals just over their margins, about 1,050 and 1,270 seeds would settle them; plan online and mana available unavailable (B had no snapshots) |
| Proposal, snapshots on against off | 200 | 165 | EQUIVALENT (0) | see below |

The two attack plans change which opponent each creature hits, which moves overkill but rarely the round the table
dies: 72% of logs differ, 3 of 1,000 games change their dead-by-10 outcome.

## Sources and their cost

- **Row fields** (`table_dead_round`, `opponents_life`, `plays`): free.
- **Game log**: Forge builds it in memory for every game (the runner hashes it); `-Dgoldfish.logDir` only adds a
  file write of about 43 KB per game. Gives mulligans, casts, mana made, land drops and life by round. Mana from
  the log is mana *made* (mana that floats unused still counts): 0.65% above the mana paid that the
  recorder sees, over 1,000 games. The parser credits every MANA line to our player except
  lands an opponent played, which holds for passive opponents only.
- **Per-round snapshots** (`Recorder`, END_OF_TURN and MAIN1 of our turns). Measured on 200 seeds with timers in
  a copy of the recorder: 7.8 ms per game in all, 0.5% of a 1.5 s game. The hand payability check is 5.1 ms, the
  commander payability check at main 1 is 1.7 ms, the mana estimate 0.1 ms.

**The snapshots changed the games.** Before 173a0b1 the recorder computed them in every run, not only with
`-Dgoldfish.rounds=true` (that flag only added them to the row). The two payability checks call
`ComputerUtilMana.canPayManaCost`, which draws from the game's shared random stream (`MyRandom`). Skipping the
commander check changed 3 of 3 test games, skipping the hand check 2 of 3; skipping the mana estimate changed none.
Turning the snapshot off changes 703 of 1,000 games (row above). Running the checks against a scratch `Random`
removes most of it: 165 of 200 games then match the snapshot-off run. The other 35 diverge in which land the AI
taps for a later payment; the likely cause is the AI card memory (`PAYS_TAP_COST`) that the test payment leaves
set (`ComputerUtilMana`, not confirmed). So:

- every row recorded between 29a8cee (which added the checks) and 173a0b1 comes from games the recorder perturbed. It
  does so the same way every time, so hash comparisons between builds still hold;
- a Forge change that alters how often `canPayManaCost` draws a random number changes every later shuffle and AI
  roll, and so the hashes, even if it changes no decision;
- removing the snapshot was a one-time hash break, so baselines from before 173a0b1 don't compare by hash. Keep `-Dgoldfish.rounds` the same in both arms of
  any later comparison.

## Recorder change (applied in 173a0b1)

- The snapshot (hand, payability, permanents) runs only with `-Dgoldfish.rounds=true`, and its two payability
  checks run against a scratch `Random`.
- Every row gets the contract fields, from game events and two cheap reads, with no AI payment calls: `seat` (turn
  number of our first turn), `mulligans`, `kept_hand`, `first_round` (first round of every `plays` key, so commander
  cast and first −3), and per round `damage_by_round`, `lands_by_round`, `mana_available_by_round`
  (`getAvailableManaEstimate`, 0.1 ms a game), `mana_spent_by_round` (`getTotalManaSpent` of each spell or ability
  we put on the stack), `spells_by_round`, `land_drops_by_round`. A round closes when our next turn begins or the
  game ends, matching `table_dead_round`.
- Checked on 1,000 games against the log parser: spells, land drops, damage, commander first cast and mulligans
  match in every game. Mana spent differs in 161 games: the log counts mana made, 0.65% more than mana paid.
  Against snapshots (200 games): first cast and first −3 match in every game.

`compare_runs.py` reads these fields when present (source `recorder`) and prefers them to snapshots and logs.

## Limits

- Kitten triggers count the trigger, not a completed flicker ("up to one target").
- Snapshot damage is read at our end step; the row's `table_dead_round` counts a table that dies during the
  opponents' turns after it. Log and recorder damage are read when our next turn starts.
- Plan online and mana available have no log source: a run without snapshots compares on them only against
  another run with the recorder fields.
- Metrics from different sources are never mixed: each metric uses the first source both arms have
  (rows, recorder fields, snapshots, logs) for at least 90% of the shared seeds.
