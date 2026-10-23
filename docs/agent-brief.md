# Goldfish runner: brief for agents (updated 2026-10-23 morning)

Goal: run Commander goldfish games (one deck under test vs three passive opponents) as fast as possible on a forked
Forge, without changing the data the games produce, and in a shape upstream Forge maintainers would accept where it
touches Forge itself.

## Repos and branches

| Path | What | Rules |
|---|---|---|
| `~/src/forge` | main Forge checkout, branch `goldfish` (old, pushed to fork `DBosley/forge`), plus uncommitted old work | read only; never edit, build or switch branch here |
| `~/src/forge-v2` | worktree, branch `goldfish-v2`: upstream master (9a6f776b848) + 36 commits (`git log upstream-master..goldfish-v2`) | the fork the runner uses by default; integrate here only when told |
| `~/src/forge-pr` | worktree on `upstream-master` and `pr/*` branches | PR candidates; don't rewrite them |
| `~/src/forge-goldfish/prototype` | the goldfish runner (Java, compiled against the Forge jars of `$FORGE_SRC`) | read only; to change it, copy it: `rsync -a --exclude out --exclude build ~/src/forge-goldfish/prototype/ ~/src/gf-<you>/` |

`goldfish-v2` layout, oldest first: MostCromulent's #12357 (4), the open upstream perf PRs #11933, #11964, #11975 (4),
our PR candidates (filter chain follow-up, unfreeze, forced attackers in order, then five no-cache scan cuts), fork-only
flags (inline AI evaluation, state-check skip, replacement list cache and memo, static list cache, alternative-cost
source memo), runner hooks and measurement switches, then the goldfish-only fast paths behind `GameRules.setGoldfish`.

Make your own worktree for any Forge change: `git -C ~/src/forge worktree add ~/src/forge-<you> -b <you>/<topic> goldfish-v2`.
Never push, never force anything, never touch another agent's worktree.

## Build, run, test

- Build in a worktree (the runner needs the desktop jar too, it uses the worktree's own jar and `forge-gui/res`):
  `mvn -q -B -o -pl forge-core,forge-game,forge-ai,forge-gui-desktop -am -DskipTests -Dcheckstyle.skip=true package`
- Runner against a worktree: `cd ~/src/gf-<you> && FORGE_SRC=$HOME/src/forge-<you> ./run.sh lazyperf <jvm -D flags> -- <runner args>`.
  It compiles itself per worktree (`build/<worktree>`), uses ParallelGC, `-XX:hashCode=2` and `-XX:-DontCompileHugeMethods`.
- Standard flags: run.sh adds them by default (`-Dforge.ai.inlineEvaluation=true -Dforge.game.skipRedundantStateChecks=true -Dforge.game.cacheReplacementLists=true -Dforge.game.memoReplacementCandidates=true -Dforge.game.cacheStaticLists=true -Dforge.game.memoAltCostSources=true`); `FORGE_FLAGS=` runs without them. None of them change a game
- Standard args: `--deck $PWD/decks/multiverse-reforged.dck --fish $PWD/decks/goldfish-basics.dck --games N --seed S --horizon 10 --opponents passive --opponent-stops upkeep,end_of_turn --policy $PWD/policies/multiverse-reforged.yaml --out <file.jsonl>`
  Goldfish fast mode is on by default with passive opponents (`--fast off` to compare). `--threads N` plays N games at once
  in one JVM (heap sized automatically); 12 is the sweet spot on this machine.
  Add `-Dgoldfish.logDir=<dir>` for full game logs.
- Each output row has `seed, ms, cpu_ms, status, turns, our_turns, opponents_life, plays, log_hash` plus per-round
  metrics. `cpu_ms` (game thread CPU) is the timing number to trust when the machine is busy. Games are deterministic:
  same jars + seed + flags gives the same `log_hash`. **A change that keeps every `log_hash` identical cannot have
  changed the data.** Otherwise run `python3 compare_runs.py <baseline> <candidate>` (see `compare_runs.md`).
- Baseline: `~/src/forge-goldfish/prototype/out/final-1000/rows.jsonl` (+ `logs/`), seeds 1-1000 on `goldfish-v2`.
- Forge tests (in a worktree): `mvn -B -o -pl forge-gui-desktop -am -Dcheckstyle.skip=true -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dmaven.test.failure.ignore=true '-Dtest=forge/ai/**/*Test,forge/ai/**/*Tests,forge/gamesimulationtests/**/*Test,forge/gamesimulationtests/**/ComprehensiveRules*,forge/game/**/*Test' test`
  `goldfish-v2` passes 455/455 with no flags and with the standard flags set through `JAVA_TOOL_OPTIONS`.
- Profiling: add `-XX:StartFlightRecording=filename=<f>.jfr,settings=profile`, then `python3 profile_summary.py <f>.jfr 40`
  (single thread runs are easiest to read).
- **Timing hygiene:** other agents may share this 16-core machine. Compare A and B interleaved, same seeds, at most
  4 runner processes of your own at once, and report `cpu_ms` beside wall time.

## Where the time goes now

About 0.55 s of game-thread CPU per policy game single-threaded, 0.58 s per game on 12 threads (1000 games in 61 s).
What is left after the morning round: `getStaticAbilities` call volume (about 12%, mostly emptiness checks on basic
lands), `Game.getCardsIn` copies (about 6%), last known info copies, and `-XX:hashCode=2` turning identity-hashed maps
into tree searches (3-4%, kept for determinism). Details: `docs/research/static-scan-cost.md`,
`docs/research/alt-cost-scans.md`, `docs/research/goldfish-fast-paths.md`.

## What upstream wants (from their PRs and review threads)

- Small, targeted PRs, `AI: ...` titles for AI fixes, squash merged; body = problem, cause, fix, tests; say Claude
  was used (CONTRIBUTING.md). Few tests: one focused class whose cases fail on master; a flood of AI-written tests
  was called out (#10099).
- No `System.getProperty` switches anywhere in forge-ai/game/core. A user preference `PERFORMANCE_MODE` exists and
  gates some shortcuts (#11327 merged).
- Caching gets skepticism about invalidation ("your assumption of same string = same value is on shaky ground", #8916)
  and is pointed at tracking issues: #11409 (CardTrait caching), #11058 (stream-based `forEachCardInGame`, "get rid of
  the overhead instead of trying to detect it"). kevlahnota's open #12297 (FCollection rewrite plus statics and
  replacement caches) is the active work in that area, don't compete with it upstream.
- Measure on ordinary games, not seeds chosen to flatter (#11933). tool4ever (rules/AI), Hanmac (caching),
  MostCromulent and kevlahnota review most of this. More in `docs/research/upstream-perf.md`.

## Accuracy: the data that has to survive

Rebell Lily's goldfish method (`~/src/mtg-tools/docs/deckbuilding.md`, "Goldfishing"): does the game plan work (the
turn the commander lands, enablers teeing up payoffs, the turn the plan comes online), keepable openers
(mulligans), mana and cards cast per turn, and for this deck table dead by round 10 (81.0% on `goldfish-v2`, about
±2.5 points at 1000 games), Jace −3 and +1 per game, damage curve by round. A speedup must keep `log_hash` identical,
or, if it changes play, keep these metrics within noise on the same 1000 seeds (`compare_runs.py`).
