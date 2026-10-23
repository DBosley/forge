# Goldfish-only fast paths (2026-10-23)

Speedups that only make sense in a headless game against passive opponents, behind one switch the runner sets.
Branch `fast/goldfish-mode` in `~/src/forge-fast` (10 commits on `goldfish-v2`), runner copy in `~/src/gf-fast`.

> Ported: the 10 commits are on the master based `goldfish-v2` as 8 (the two review fixes are squashed into the threading and LKI commits), and the runner changes are in the main runner (`--fast`, `--threads`, `cpu_ms`, ParallelGC). Commit hashes below are from the old branch, which is removed along with `~/src/forge-fast`. `prof.py` moved to `prototype/`; the other tools in `~/src/gf-fast` go when the last agents using it finish.

## Result

Same 100 seeds, same deck and policy, standard `-D` flags, interleaved in one quiet window (load 4-6). Baseline is
`goldfish-v2` HEAD with the runner as it was (G1). Final is the branch HEAD with `--fast on` and parallel GC.

| | Baseline wall | Final wall | Change | Game thread CPU | Process CPU, 100 games |
|---|---:|---:|---:|---:|---:|
| Policy | 1.217 s | 0.772 s | −36.5% | 1.082 → 0.758 s (−29.9%) | 223 → 155 s (−31%) |
| No policy | 0.892 s | 0.594 s | −33.4% | 0.823 → 0.585 s (−28.9%) | 194 → 129 s (−34%) |

Every `log_hash` and every other output field (plays, opponents' life, turns, status) is identical on all 100 seeds
in both modes. Forge tests: 389/389 on the branch with no flags.

Batch throughput, 200 policy games on 4 cores (2 repetitions each):

| Setup | Games/s | CPU-s per game |
|---|---:|---:|
| Baseline: 4 processes × 50 games, G1 | 2.63-2.71 | 2.92 |
| Final: 4 processes, parallel GC with 2 GC threads | 4.02-4.05 | 2.10-2.13 |
| Final: 1 process, 4 game threads (`--threads 4`, `-Xmx6g -Xmn3g`) | 4.04-4.05 | 1.15-1.17 |

No policy: 3.74 → 5.35 (processes) / 5.63 (threads) games/s, 2.46 → 1.82 / 0.89 CPU-s per game. With spare cores the
thread run and the process run finish together, because each process's JIT (50-70 CPU-s per JVM) runs on idle cores.
On a saturated machine (16 workers on 16 cores) CPU is the limit, so threads should give close to 1.8× the throughput of
processes. That was not measured: the brief caps me at 4 processes.

## The switch

`GameRules.setGoldfish(true)`: a headless game against opponents that never act. Normal Forge never sets it. The runner
sets it when `--opponents passive` (default on, `--fast off` turns it off for A/B runs). Two cuts also need to know which
players are passive: `PlayerController.isPassive()` defaults to false, and the runner's `PassiveController` returns true.

## Profile before and after

JFR, 40 games each, share of samples and the implied ms per game. Before: baseline jars, G1, no switch (1.716 s/game
policy, 0.984 s no policy under JFR in a busy window). After: final jars, parallel GC, switch on (0.926 s and 0.613 s).

| Inclusive | Policy before | Policy after | No policy before | No policy after |
|---|---:|---:|---:|---:|
| AI choosing plays (`getSpellAbilityToPlay`) | 32% / 544 ms | 40% / 368 ms | 32% / 312 ms | 40% / 244 ms |
| Static rebuilds (`checkStaticAbilities`) | 32% / 547 ms | 28% / 262 ms | 30% / 292 ms | 22% / 137 ms |
| Stack resolution | 30% / 517 ms | 26% / 241 ms | 17% / 166 ms | 15% / 91 ms |
| Replacement scans | 20% / 350 ms | 18% / 169 ms | 19% / 182 ms | 17% / 105 ms |
| Walks over every card (`forEachCardInGame`) | 14% / 247 ms | 9% / 83 ms | 18% / 179 ms | 10% / 62 ms |
| LKI copies | 14% / 235 ms | 10% / 88 ms | 11% / 107 ms | 9% / 56 ms |
| State-based checks | 14% / 237 ms | 11% / 104 ms | 18% / 172 ms | 12% / 70 ms |
| GUI views (`forge.trackable`, `*View`) | 13% / 218 ms | 4% / 34 ms | 11% / 108 ms | 4% / 25 ms |

The AI share went up because everything around it shrank. Opponents' turns went from 21% to 19% of policy time and
from 38% to 35% of no-policy time.

The view work still left (4%) is needed or too spread out: `CardStateView.updateKeywords` still refreshes the keyword
cache and ability text (stack descriptions compare against the card's view text), `setStackDescription` reads
`CardView.getText`, and `Card.java` reads the oracle text from the view.

## Cuts kept

Each A/B: same jars with and without the cut (or the previous cut as A), 100 seeds per mode, A and B running at the
same time, paired per-seed comparison of the game thread's CPU (`cpu_ms`, new runner field). The 95% intervals were
within ±1.5 points in quiet windows.

| # | Commit | What it skips | Hashes | CPU per game, policy / no policy |
|---|---|---|---|---|
| 1 | `133e0576fbe` | keyword list, mana colours, annihilator/ward/divide flags on every keyword view refresh (only a GUI draws them) | 100/100 both | −5.2% / −2.7% |
| 2 | `07f6ddf7471` | player zone views except the hand (the mulligan log line counts it), flashback view, counter and P/T views; spell copies build their view on first `getView` | 100/100 | −7.4% / −1.4% |
| 3 | `70f74a513ba` | `System.gc()` at the end of `Match.startGame` (frees memory for the GUI between games) | 100/100 | wall −3.0% / −1.6%, process CPU −5% / −8% |
| 4 | `35814773696` | the 100 ms `Thread.sleep` in `AbstractMulligan.mulligan` (0.9 mulligans a game with the policy) | 100/100 | wall −3.9% / −2.3% |
| 5 | `614a300a2d7` | a passive opponent's commander may-play static (they never cast it). It was 3 of the 4 commander effects rebuilt on every static pass | 100/100, all fields | −6.5% / −9.5% |
| 6 | `315300f27e1` + `2292fc6a5c0` | full view rebuild on LKI copies in their original face-up state, player keyword view | 100/100 | −2.1% / −1.3% |
| 7 | `26dc9414626` | passive opponents' hands and libraries in static rebuilds, trigger resets and replacement scans, while they hold only plain basic lands | 100/100, all fields | −9.3% / −10.1% |
| 8 | `b5bed0a357a` + `fbc3deac277` | not a skip: lets one JVM play games on N threads | single thread 100/100, 4 threads 200/200 vs single thread, all fields | per game unchanged, batch CPU −45% |

Runner-side, hash-neutral:

| Change | Hashes | Effect |
|---|---|---|
| `-XX:+UseParallelGC` in `run.sh` (G1's barriers cost the game thread too) | 100/100 | wall −7.2% / −9.6%, game CPU −6.3% / −7.4%, process CPU −13% / −14% |
| `-XX:ParallelGCThreads=2` when running several worker processes | 100/100 | process CPU −8.5% vs the default thread count, wall +6% |
| `--threads N` | 200/200 | see batch table |

### How cut 7 stays exact

The win came from not walking about 280 of the ~370 cards in the game on every scan (6,000 scans a game). Checking
each card on every visit cost as much as it saved (each check touches ~10 objects per card: +6.5% with a naive check,
−2.5% with a tight one). So the check runs per zone and is reused:

- `Card.hasNoTraitEffects()`: printed basic land, no own statics, triggers, replacements or keywords, nothing granted
  (all six trait, keyword and type change tables empty), no counters, not suspected, no static commands, one state.
  Such a card adds nothing to any of the three scans.
- `Game.canSkipPassiveHiddenZones()` checks every passive player's hand and library and reuses the answer until a game
  event fires or a static rebuild runs. A rebuild that affected no card in those zones and fired no event keeps it
  (`GameAction.touchesPassiveHidden`). This is the same invalidation `memoReplacementCandidates` relies on: a card
  changes only with a game event or a static rebuild.
- ETB previews of a passive player's own card (their land drop) still visit everything.
- Lookups (`getCardState`, `findById`) and the AI's `getCardsInGame` (colour choices count opponents' library cards)
  still see every card.

The remaining risk is an effect that grants abilities to a card in an opponent's hand or library without firing an
event before the next scan. None of the runner's goldfish decks can be affected that way, and the deck under test
would need such a card.

### Threads in one JVM

Forge state that games share, fixed in `b5bed0a357a` and `fbc3deac277`:

- `MyRandom.setThreadRandom`: the runner seeds each game on its own thread. `getRandom` falls back to the shared one,
  so nothing changes unless a runner opts in.
- Trigger ids: every game start resets them (`Match.prepareAllZones` → `Trigger.resetIDs`), so a game starting on one
  thread handed out ids another game was using, and triggers with equal ids were treated as the same trigger (7-10 of
  100 games differed from the single-threaded run, different seeds each time). `Trigger.useThreadIds()` gives the calling thread its own counter;
  the runner's workers call it. Everything else keeps one counter, now atomic. My first version made ids per thread for
  everyone, which would have broken normal Forge: the AI evaluation thread and the game thread share triggers.
- Other id counters are atomic. Lazily filled static caches are synchronized: `FileSection.parseToMap`, `TokenDb`
  loading, counter types, keyword sets, `getCardForUi`, collector numbers.
- Intuition's AI picks moved from a static list to the `AiController` (the pick is made on the AI evaluation thread and
  read on the game thread when inline evaluation is off, so a thread-local would not do).
- The runner preloads every card (`ensureAllCardsLoaded`) so no card script loads mid-game, gives each thread its own
  players, and needs about 1.5 GB of heap per thread (`-Xmx6g -Xmn3g` for 4; at `-Xmx3g` old-gen collections ate the
  gain).

The audit was grep and review. A shared cache I missed would show up as a hash difference between a threaded run and a
single-threaded run, so rerun that comparison (100 seeds is enough) when switching to a new deck.

## Rejected or not done

| Idea | What happened |
|---|---|
| Skip the runner's per-round snapshots when `goldfish.rounds` is off | Changed 76/96 policy and 99/100 no-policy hashes. `ComputerUtilMana.canPayManaCost` reaches the AI's mana reservation check, which draws from `MyRandom` (`RESERVE_MANA_FOR_MAIN2_CHANCE`), so the snapshot was part of the game. The current prototype `Recorder` (changed after I copied it) gates snapshots and swaps the random around them, which fixes this. In threaded mode its swap has to use `MyRandom.setThreadRandom`, or the snapshot draws from the game's stream. |
| Smaller opponent decks | ~2% and changes every shuffle |
| `-XX:+UseCompactObjectHeaders` | 1-2% slower |
| Serial GC | lowest process CPU, worst wall (pauses on the game thread) |
| `-XX:CompileThresholdScaling=3` / `10` | JIT CPU per JVM 68 → 46 / 31 s, but +4% / +8% per game. Worth it only for workers that play fewer than ~400 games |
| Skip AI work that only matters against blockers (`doesSpecifiedCreatureAttackAI`, `shouldPumpCard`, attack simulation) | Those paths draw from `MyRandom` (`Aggregates.random` for the defender, aggression chances). Any skip shifts the stream |
| Skip state checks and static rebuilds in opponents' empty steps | When a creature dies changes which step its triggers resolve in. Not provably the same |
| Event bus, game log formatting, undo stack, Sentry breadcrumbs | each under 0.5% |
| Skip passive opponents' battlefield lands and commander replacement in scans | estimated under 1.5%, not built |
| Card setup per game, reusing objects across games | setup is about 1% of a game |

## What to attack next

Ranked by size in the final profile. None of these are goldfish-only, which is why I stopped here.

1. Building static ability lists: `CardState.getStaticAbilities` allocates and fills a new `FCollection` for every card
   on every scan, 25% of policy time, and every `StaticAbilityXxx` mode scan (`alternativeCosts` 13%, turn-order
   reversal, cost adjustment, devotion) repeats it. A P4c-style cache for statics and triggers on unchanged cards
   would remove most of it. Upstream: #11409, #12297.
2. `GameActionUtil.getAlternativeCosts` (12-13%): the AI asks for every ability of every card on every priority and
   each one rescans every card for alternative-cost statics. One scan per decision would do.
3. `Game.copyLastState` / LKI copies (9-10%): a full LKI copy of every battlefield and graveyard card after every
   action, with big token boards.
4. `StaticAbilityTurnPhaseReversed.anyTurnPhaseReversed` (~5%): called from `getAffectedPlayers` inside every static
   rebuild, it scans every card for a turn-order reversal each time.
5. Throughput: games on threads by default for batches, with the worker count set by cores, not processes.

## Porting to the current runner

The prototype changed after I copied it (Runner, Recorder, DeckController, the policy, run.sh, build.sh), so port these
by hand rather than copying files from `~/src/gf-fast`:

- `rules.setGoldfish(true)` when opponents are passive, `--fast off` to turn it off.
- `PassiveController.isPassive()` returns true.
- `--threads N`: per-thread players (`ThreadLocal.withInitial(() -> buildPlayers(...))`), `MyRandom.setThreadRandom(new
  Random(seed))`, worker threads call `Trigger.useThreadIds()` before running, preload all cards, write rows as they
  finish. The `Recorder` random swap needs the thread variant.
- `run.sh`: `-XX:+UseParallelGC`; with many worker processes add `-XX:ParallelGCThreads=2`.
- `cpu_ms` in each row (game thread CPU from `ThreadMXBean`), which holds up far better than wall time when other
  agents load the machine.

Then check `log_hash` on 100 seeds with `--fast off` against `--fast on` before using the numbers.

Tools in `~/src/gf-fast`: `ab.sh` (interleaved A/B), `batch.sh` (throughput), `cmp.py` (paired per-seed comparison),
`fields.py` (every output field), `prof.py` (JFR queries: top, under, callers, incl), `phprof.py` (JFR samples by
game phase, needs `-Dgoldfish.phaseTiming=true`, single-threaded only), `snap.sh` (build and snapshot jars).
