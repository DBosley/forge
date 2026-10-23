# Moving goldfish-v2 from forge-2.0.15 onto upstream master (2026-10-23)

Question: should the fork move from forge-2.0.15 (2026-09-28) plus 16 commits onto Card-Forge master
(`upstream-master`, 9a6f776b848, 2026-10-23), and what does that buy?

Answer: it buys no speed. It changes every game, but the outcome metrics stay within noise. The rebase itself is done and
passes every test. The reason to move is alignment (PR branches and fork on the same code, perf measurements on the
code upstream reviews), not speed. If you move, also set `Game.AI_TIMEOUT` in the runner (see "Determinism").

## Where things are

| Path | What |
|---|---|
| `~/src/forge-master` | worktree, branch `rebase/goldfish-on-master` at `007b96b9e87`: master + 15 commits. Not pushed. |
| `~/src/gf-rebase` | runner copy. `APP=release\|v2\|master\|rebased` picks the jars and res. `go.sh`, `wave.py`, `analyze.py` drive the measurements. |
| `~/src/gf-rebase/apps/` | frozen jars: `release` (2.0.15 desktop jar + res), `v2` (goldfish-v2 core/game/ai), `master` (master desktop jar + res snapshot), `rebased` (rebased core/game/ai) |
| `~/src/gf-rebase/out/` | raw rows: `w1`–`w4` timing waves, `o1`/`o2` seeds 101–1000, `prof` JFR, `leak` (see the inline commit) |

## Build recipe: desktop jar from master

```
git -C ~/src/forge worktree add ~/src/forge-master -b rebase/goldfish-on-master upstream-master
cd ~/src/forge-master
mvn -B -pl forge-gui-desktop -am -DskipTests -Dcheckstyle.skip=true package     # 73 s wall; first run downloads deps
# forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar (31.7 MB)
# also forge-{core,game,ai,gui}/target/*-2.0.16-SNAPSHOT.jar
```

- The default build already runs `maven-assembly-plugin` (`make-assembly`, custom descriptor
  `src/main/assembly/jar-with-dependencies.xml`) in `package`. No profile is needed. launch4j and the git-changelog
  plugin also run in `package`. Both worked on Linux in the shallow clone.
- The jar needs a matching `res`. Master's card scripts differ from 2.0.15's, so don't point it at the release `res`.
  Snapshot it: `git archive upstream-master forge-gui/res | tar -x`. I also zipped `cardsfolder/*/*.txt` into
  `cardsfolder.zip` to match the release layout. `zip` isn't installed, so I used Python `zipfile`. Forge also reads
  loose files, so this step is probably optional.
- Fork jars: `mvn -q -B -o -pl forge-core,forge-game,forge-ai -am -DskipTests -Dcheckstyle.skip=true package` (7 s
  incremental). On the classpath they go before the desktop jar, which bundles master's own core/game/ai.
- `env.sh` hard-codes `*-2.0.15.jar` names. In `gf-rebase` it builds the classpath from `apps/$APP/`, and work homes
  are `gf-rebase/work/$APP-<home>`, so 2.0.15 and master `res` never share a home.

## The rebase

`upstream-master..rebase/goldfish-on-master`, oldest first:

| New | From | Result |
|---|---|---|
| 5122ecb23a4 | eac176a AI: count a Combo mana ability as one mana | clean |
| 605bfd1b854 | e3be1b6 AI: unfreeze the stack when a cast fails to pay | clean |
| — | d2c2bea (#12149), 8ec580e (#12179) | **dropped**: on master as 8c4a3ee, e9fa54a |
| 897ad0605b3 | 50ca7b5 AI: plan filter payments the way they get paid | conflict in `ComputerUtilMana`, see 1 |
| 254254aab4a | b200e11 AI: pay for filter lands with a hybrid cost | took `pr/ai-hybrid-filter-lands` (0a9bd7c), clean |
| bbab1c615c6 | 3b2c896 option to run ai play evaluation on the game thread | applied clean but **broken**, fixed, see 2 |
| 5428f7364e5 | 6221d83 option to evaluate forced attackers in order | conflict, see 3 |
| 9ab44e95d22 | ecd2424 skip the state-based check after a plain priority pass | conflict, see 4 |
| beb19fe5639 | 25d64fc skip static ability rebuilds when nothing changed | clean; lands in master's new 4-argument `checkStaticAbilities(..., enteringWith)` |
| 658de4c9260 | b6cdac5 cache replacement effect lists on unchanged cards | clean; master's new `vengeanceRep` depends only on the printed type, which the "unchanged card" condition already covers |
| b26700b4230 | b9d66db memoize which cards have replacement effects | clean |
| 070015c6cd7 | f14fecd threat score for one opponent as its own method | clean (the extracted loop body is identical on master) |
| 00ccbd0e0b0 | 781bc67 turn skipper hook on PhaseHandler | conflict, see 5 |
| 28ef8e94db2 | 615fe45 play policy hook for the ai | conflict, see 6 |
| bbe0d880666 | 9799751 measurement switches | clean |
| 007b96b9e87 | new | perf: count a sticker as a state change for the static skip, see "Forge tests" |

Conflicts and how I resolved them:

1. **Plan filter payments.** Master merged #12149 in its reviewed form: the helper is `getGenericCostToActivate`,
   the parameter is `Multimap` rather than `ListMultimap`, and `prepayFilterManaAbility` records `PAYS_TAP_COST` in
   AI memory. `pr/ai-filter-plan-matches-payment` (dfe2eec) is the same change on master by the same author, so I
   cherry-picked that. It applied clean on top of the two earlier fixes. Same for the hybrid filter lands commit
   (0a9bd7c, which renames the helper to `getManaCostToActivate`).
2. **Inline evaluation.** Master did not make this obsolete. #12265 (`AITimeoutTracker`) still starts a
   "Game AI Eval" thread for every decision. It only adds a shared, cooperative deadline. The cherry-pick applied
   textually clean, but the inline `return` skipped master's `AITimeoutTracker.end(aiId)`. That leaks the tracker
   session. Five seconds into a game the player's deadline expires, every `check()` throws, and the AI passes
   priority until the session goes stale two seconds later. Fix: run the inline branch in its own
   `try ... finally { AITimeoutTracker.end(aiId); }`. The commit message now says the deadline still applies inline.
   Check: a jar with the leak put back differed on 1 of 15 seeds (seed 1, the only game over 5 s). The fixed jar
   matches the no-inline run on 100/100. Kept because it still saves 5–7% (see the timing table).
3. **Sequential attacks.** Master wrapped `declareAttackers` in a tracker `try`, re-indented the whole method, and
   now wraps each forced-attacker task in `withDeadline(...)` behind a `combatClosed` guard. I took master's method
   and re-applied the option. The pool is created only when the flag is off. With the flag on, the already-wrapped
   tasks run in order on the game thread. Master still adds forced attackers in thread-finish order, so the option
   is still needed for determinism.
4. **State-check skip.** Master moved `GameEventPlayerPriority` from before the priority loop to inside it, after each
   state check (CR 117.3). I kept master's event. `stateEventsAtLastCheck` is recorded just before that event fires.
   The counter ignores priority events anyway. Recording first means a subscriber that reacts to the priority event
   can only cause an extra check, never a skipped one.
5. **Turn skipper.** The commit also changes the post-check `GameEventCardStatsChanged` to `fireDerivedEvent`. Master
   made `allAffectedCards` a per-call local and dropped `allAffectedCards.clear()`. I kept `fireDerivedEvent` and
   left out the `clear()`.
6. **Policy hook.** Master added `AITimeoutTracker.check()` as the first line of `canPlayAndPayFor`. I kept it and made
   the method public.

The runner compiled against the rebased jars without changes, so there is no API drift. Two runner-side findings:

- Stock jars can't load the runner. `Policy` implements the fork's `forge.ai.AiPlayPolicy`, so linking
  `DeckController` throws `NoClassDefFoundError`. For the two stock rows I put a one-class shim jar
  (`apps/{release,master}/shim-AiPlayPolicy.jar`) on the classpath. It contains the interface only. Stock runs have
  no policy, because the hook doesn't exist there.
- See "Determinism" for `Game.AI_TIMEOUT`.

## Timing

Seeds 1–100, horizon 10, passive opponents, stops at upkeep and end step, `lazyperf` home, `-XX:hashCode=2`. Each wave
ran its four configurations at the same time, so they shared the machine's load. Other agents had 4–8 runner JVMs
going throughout, with a load average around 16 on 32 threads, so absolute numbers are 15–20% above the brief's
idle 1.34 s. Compare within a wave. "mean" and "median" are s/game over games 4–100. "CPU" is user+sys of the whole
JVM divided by games, including JIT and GC threads.

With the policy:

| Wave | Config | mean s | median s | CPU s/game |
|---|---|---:|---:|---:|
| w1 | goldfish-v2, no flags | 2.94 | 2.44 | 3.99 |
| w1 | rebased, no flags | 2.99 | 2.70 | 4.10 |
| w1 | goldfish-v2, all flags | 1.81 | 1.46 | 2.83 |
| w1 | rebased, all flags | 1.91 | 1.54 | 2.97 |
| w3 | goldfish-v2, all flags | 1.58 | 1.26 | 2.65 |
| w3 | rebased, all flags | 1.57 | 1.41 | 2.71 |
| w3 | goldfish-v2, all flags but inline | 1.65 | 1.33 | 2.74 |
| w3 | rebased, all flags but inline | 1.69 | 1.50 | 2.83 |
| w4 | goldfish-v2, no flags | 2.11 | 1.75 | 3.29 |
| w4 | rebased, no flags | 2.15 | 1.98 | 3.40 |
| o1+o2 | goldfish-v2, all flags, seeds 101–1000 | 1.60 | 1.37 | 2.26 |
| o1+o2 | rebased, all flags, seeds 101–1000 | 1.60 | 1.39 | 2.27 |

Without the policy:

| Wave | Config | mean s | median s | CPU s/game |
|---|---|---:|---:|---:|
| w2 | stock 2.0.15 release | 2.29 | 2.02 | 3.76 |
| w2 | stock master | 2.49 | 2.16 | 3.92 |
| w2 | goldfish-v2, no flags | 2.28 | 2.01 | 3.72 |
| w2 | rebased, no flags | 2.54 | 2.14 | 4.03 |
| w4 | goldfish-v2, all flags | 0.95 | 0.88 | 2.04 |
| w4 | rebased, all flags | 1.18 | 0.89 | 2.30 |

What this says:

- **No free speedup.** At the production setting (policy, all flags) the two bases are equal over 900 games
  (1.60 vs 1.60 s). Over 100 games they trade places: +5% in w1, −1% in w3. No-flag policy runs are +2% on master.
- **Master plays more per game, not slower per play.** Plays per game with the policy are 64.1 on goldfish-v2 and
  67.5 rebased. Without the policy they are 45.4 and 52.8. Per play the time is the same: −5% to +6% across the
  seven paired runs, and median paired ms/play ratios are 0.89–1.01. The no-policy runs look 9–24% slower per game
  for that reason. JFR on 40 policy games gives the same profile on both bases: static rebuilds 34% vs 32%, AI
  choice 32% vs 31%, replacement scans 20% vs 19%. Master's new machinery costs nothing measurable:
  `AITimeoutTracker`, `UndoManager` and the event bus are 0%, 0% and 1.2%.
- The premise that master carries AI and perf work we'd get for free mostly doesn't hold. #11946 (token cascades,
  2026-09-18) and #11925 are ancestors of the 2.0.15 release commit, so goldfish-v2 already has them. There are no
  changes to `forge-core/.../util/collect` (FCollection) or `CardTraitBase` between 2.0.15 and master. The engine
  changes since 2.0.15 are rules fixes, simultaneous entry (#12121, #12171, #12188), Undo, stickers and
  `AITimeoutTracker`.
- The perf flags buy the same on master: −34% per game with the policy on both bases (median paired ratio
  0.66 rebased, 0.67 goldfish-v2). Inline evaluation alone is −4% on goldfish-v2 and −7% rebased.

## Outcomes and determinism

Every game changes. Rebased vs goldfish-v2 log hashes match on 0/100 seeds, with and without the policy. Stock master
vs rebased match on 13/100 and stock 2.0.15 vs goldfish-v2 on 33/100; the difference is the AI mana fixes. The
metrics, seeds 1–1000, policy, all flags:

| | goldfish-v2 | rebased |
|---|---:|---:|
| table dead by round 10 | 77.8% ± 2.6 | 80.1% ± 2.5 |
| cumulative dead by round 7 / 8 / 9 / 10 | 2 / 19 / 51 / 78% | 2 / 16 / 49 / 80% |
| mean round the table dies | 9.07 | 9.16 |
| opponents' life left (sum, floored at 0) | 10.0 | 9.2 |
| Jace −3 per game | 4.38 | 4.50 |
| Jace +1 per game | 3.39 | 3.37 |
| statuses | 778 dead, 221 horizon, 1 other | 801 dead, 198 horizon, 1 other |

The difference is within noise. Paired by seed, 157 games are dead only on goldfish-v2 and 180 only rebased
(McNemar χ² 1.57, p ≈ 0.21). Both are inside the brief's 75–79% ± 2.5 band, or just above it. The one `other` game
on each side is our deck decking itself: on seed 91 without the policy, Memnarch draws a card per artifact.

Determinism holds on the rebased branch:
- Same jars, seed and flags give the same hashes: 100/100 for each of the four repeated configurations (w1 vs w3/w4).
  Seed 91 run alone in a fresh JVM matches its in-batch hash.
- The flags don't change play on master either. No flags vs all flags: 100/100 with the policy and 100/100 without.
  All flags vs all but inline: 100/100.

**One new risk.** On master, `chooseSpellAbilityToPlay`, `declareAttackers` and `declareBlockersFor` each arm a
`Game.AI_TIMEOUT` (5 s) deadline on the game thread. A decision that runs past it passes priority, or keeps a partial
attack or block. With inline evaluation and sequential attacks on, 2.0.15 had no wall-clock dependence; master has it
again. That includes the policy, since `canPlayAndPayFor` now calls `check()`. It didn't fire here: repeats matched
and there were no timeout traces in the logs. At 16–24 workers with 4–6 s games it can. Fix in the runner, after
`match.createGame()`: `game.AI_TIMEOUT = 3600;`. Don't use `Integer.MAX_VALUE`: `startNanos + timeoutNanos` overflows
and every check expires at once.

## Forge tests (brief's pattern, on `007b96b9e87`)

| | forge-game | forge-gui-desktop |
|---|---:|---:|
| flags off | 3/3 | 446/446 |
| all flags via `JAVA_TOOL_OPTIONS` | 3/3 | 446/446 |

Before 007b96b, flags on failed one test: master's new `StickerTicketCardTest.testStickerAbilitiesCarryToAnotherObject`.
`skipUnchangedStatics` alone broke it. The test calls `Card.addSticker` directly. That fires no game event, so the
next static rebuild was skipped and the equipped creature never got flying. In a real game the sticker comes from
`PutStickerEffect` resolving, which fires events. This is the same tests-only gap the fork already closes for direct
zone changes. Fix: `addSticker` calls `Game.noteStateChange()`. The wider lesson: the skip trusts that every state
change fires an event. Master keeps adding mutators, so each rebase needs this flags-on test run to catch the next
one.

## Recommendation

**Move, but for alignment, and don't expect speed.** What it costs:

- **Data.** Every game hash changes. The outcome metrics stay inside noise on 1000 seeds, but any stored per-seed
  baseline (logs, hashes, earlier result tables) has to be regenerated on the new jars.
- **Tooling.** Build the desktop jar from source (73 s) and keep a `res` snapshot next to it. Switch `env.sh` to the
  `APP`/`apps/` layout, or point `FORGE_APP` at a dir holding `forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar`
  plus `res` and update the hard-coded jar names. Add `game.AI_TIMEOUT = 3600` to the runner.
- **Upkeep.** Master moves daily. This rebase hit 5 conflicts in 14 commits and two semantic bugs a clean cherry-pick
  would have hidden (the tracker leak and the sticker test). Rebase at upstream releases (2.0.16) or when a PR lands,
  not continuously. Always rerun the flags-on test pattern and the same-seed hash check.
- **What it buys.** `pr/*` branches and the fork are the same code: the four AI fixes are the PR commits verbatim.
  Perf work gets measured on the code upstream reviews, which #11933 asks for. We also get a month of upstream rules
  and AI fixes; the deck makes 5–16% more plays per game.

If the next goldfish batch needs to stay comparable with existing results, stay on goldfish-v2 for that batch and
switch at the next planned re-baseline. The branch is ready either way.
