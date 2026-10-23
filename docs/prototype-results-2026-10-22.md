# Prototype results, 2026-10-22

Forge 2.0.15 built from the `goldfish` branch of `DBosley/forge` (uncommitted). Deck: Multiverse Reforged
(`prototype/decks/multiverse-reforged.dck`) against three goldfish (99 Wastes, commander Isamaru). Seeds 1–30,
horizon 10 of our turns unless noted, first 3 games of each run dropped as JIT warm-up. Machine: Ryzen 9
7950X3D (16 cores, 32 threads), 61 GB; Claude's processes run at `SCHED_IDLE`, so other desktop load slows them.

## Each lever alone

Waves A to C ran 5 to 7 variants at once on the same seeds, so every variant in a wave shared the same machine
load. Compare within a wave.

| Lever | Kind | Mean s/game | Against | Change | Games changed? |
|---|---|---:|---|---:|---|
| Horizon at round 10 | runner | 3.94 | stock 6.34 (no horizon) | −38% | n/a (shorter games) |
| Passive goldfish opponents | runner | 2.59 | base 3.94–4.01 (A) | −35% | yes: opponents no longer use the random stream |
| P2 inline AI evaluation | patch | 3.72 | base 3.94–4.01 (A) | −6% | no (30/30 identical, wave C) |
| P3 sequential attack evaluation | patch | 4.15 | base 3.94–4.01 (A) | none (noise) | no |
| Forge `PERFORMANCE_MODE` preference | existing setting | 2.97 | base 3.84 (B) | −23% | no (30/30 identical) |
| Lazy card loading | existing setting | 3.60 | base 3.84 (B) | −6% per game; startup 5.2 s → 1.6 s | no |
| Skip library cards in the replacement scan | experiment, not rules-safe | 1.85 | all levers 1.99 (C) | −7% | no |

## All levers together

Passive opponents, horizon, P2, P3, `PERFORMANCE_MODE`, lazy loading.

- One JVM, idle machine: **1.63 s mean, 1.52 s median, 1.02 s fastest**, startup 1.1 s. Stock was 6.3–6.5 s.
- 24 workers × 20 games: 3.43 games/s, 1,000 games in 4.9 min. Per game 6.3 s (contention). About 1 GB RSS per
  worker.
- 16 workers × 20 games, `-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:CICompilerCount=2 -Xmx2g`:
  3.76 games/s, 1,000 games in 4.4 min. Per game 3.9 s.

Parallel scaling is about 6× over one worker, not 16×. Likely memory and cache pressure from 16 heaps of about
1 GB each; not yet measured.

## Determinism

- Forge writes the wall-clock match duration into the game log ("Match Duration 0m : 03s"). The runner's game hash
  leaves that line out.
- Forge's play depends on Java's default object hash codes. Burning one extra `hashCode()` on the main thread before
  the games changed all 8 of 8 games. Some engine or AI code iterates hash sets keyed on objects without their own
  `hashCode`, so any change in what ran before (runner code, JVM flags, a profiler, a different card loaded) reorders
  that iteration and changes AI tie-breaks. Same code and flags back to back: 8/8 identical. Different flags or
  runner code: 1 to 11 of 30 identical.
- `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (every default hash code the same) removes it: 8/8 identical with
  and without the extra hash code, about 4% slower. `run.sh` now always passes it. The real fix is in Forge: find the
  hash sets whose order reaches a decision and make them insertion-ordered.
- P2, P3, `PERFORMANCE_MODE` and the library skip were compared against the baseline in the same wave, same runner
  code, only system properties or preferences changed: 30/30 identical each. They don't change play.
- Not yet done: P5 (library slot order). Forge builds the library from a hash map keyed on cards, so a swapped card
  can still reorder the pre-shuffle library.

## Where the time goes now (all levers, one JVM, Java Flight Recorder)

| Share | Cost |
|---:|---|
| 44% | `GameAction.checkStaticAbilities`: about 4,000 full rebuilds of static effects per game |
| 25% | `CardProperty.cardHasProperty`: property strings such as `Card.IsCommander+YouOwn` matched card by card, re-parsed each time (mostly inside the rebuilds) |
| 26% | AI choosing plays |
| 20% | `ReplacementHandler.getReplacementList`: every card in every zone visited per check |

A probe on 10 games: the four commander-rule effects (one per player, `Affected$ Card.IsCommander+YouOwn`) ran
155,000 times; Ichormoon Gauntlet's static 17,000 times; Chromatic Lantern's 11,000 times.

## Next levers toward 1 s

1. Rebuild static effects only when the game state changed since the last rebuild. Largest remaining cost;
   needs a reliable change signal in `forge-game`.
2. Parse property restrictions once per ability instead of per card per check.
3. Cache each card's replacement-effect list (ceiling about 7%, measured above).
4. For batches: games on threads inside one JVM (needs a per-game random source and the `Game.maxId` and
   `Trigger.maxId` counters made per game), so the card data and JIT work are shared.

## Play quality

Table dead by round 10: 3–9% of games in Forge with its own AI, against 66–85% from the Python model of the same
list. Forge's AI does not play the deck's plan, and it marks ten cards in the list as cards it cannot play. Speed
is no longer the main gap; the deck policy layer is.

## Running it

```
cd ~/src/forge && mvn -q -o -pl forge-core,forge-game,forge-ai -am -DskipTests -Dcheckstyle.skip=true package
cd ~/src/forge-goldfish/prototype && ./build.sh
./run.sh lazyperf -Dforge.ai.inlineEvaluation=true -Dforge.ai.sequentialAttacks=true -- \
  --deck $PWD/decks/multiverse-reforged.dck --fish $PWD/decks/goldfish.dck \
  --games 30 --seed 1 --horizon 10 --opponents passive --out $PWD/out/run.jsonl
python3 bench.py out/<wave> 30 base perf 'all*2'
```

Patch switches on the `goldfish` branch: `forge.ai.inlineEvaluation`, `forge.ai.sequentialAttacks` (P2, P3),
`forge.experiment.skipLibraryReplacements` and `forge.experiment.staticProbe` (measurement only).
