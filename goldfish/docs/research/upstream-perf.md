# Upstream Forge performance work and our goldfish patches

Research date 2026-10-23. Sources: `gh` against Card-Forge/forge (PR bodies, reviews, inline review comments, issues), `git log` on `~/src/forge-pr` `upstream-master`, and `git show` of our commits in `~/src/forge-v2` (`forge-2.0.15..goldfish-v2`).

Note on the base: `forge-2.0.15` in `forge-pr` is a shallow graft (1 commit). Its real parent is `17c1ba92149` (2026-09-29, #12065). "Merged since the release" below means `17c1ba92149..upstream-master` (291 commits).

## Summary

| Our change | Closest upstream work | Likely reception | Recommendation | Safe as default? |
|---|---|---|---|---|
| `inlineEvaluation` (3b2c89647fe) | #12265 AITimeoutTracker (merged 10-16), #11895, #11861, #11718 | Contested area between tool4ever and kevlahnota; a no-thread default removes the AI-hang escape hatch | Keep fork-only. First try raising `game.AI_TIMEOUT` in the runner instead; keep inline only if the thread costs measurable time | No |
| `sequentialAttacks` (6221d832a8f) | #12265 rewrote the pool, #11161, #11360, MostCromulent serialises it for his own measurements | Fits tool4ever's "no threads" line and the determinism work; kevlahnota built and defends the pool | Rewrite on master as a plain loop over the `withDeadline` tasks, measure, ask on Discord, then propose as default | Yes (rules-neutral) |
| `skipRedundantStateChecks` (ecd2424009c) | #12189 (priority checkpoint, `isRedrawHint`), #8675, #8916, #11328 | Skeptical: event-driven invalidation is the "detect the overhead" pattern tool4ever rejects | Keep fork-only. If ever offered: rewrite on master's `isRedrawHint`/checkpoint, behind `PERFORMANCE_MODE`, with a validation run | No |
| `skipUnchangedStatics` (25d64fc617c + part of 781bc67e2d3) | #11964, #11366, #12297, #11409, #9358 | Same as above, plus the Storied subtlety shows the fragility | Keep fork-only; only ever as part of the same `PERFORMANCE_MODE` proposal | No |
| `cacheReplacementLists` (b6cdac5e770) | #12297 (RE cache, open), #11931, #11409/#11631/#11686 | Would be called the fifth attempt at #11409 | Keep as a stopgap; drop when #12297 or a #11409 implementation lands. Do not propose | Not as written |
| `memoReplacementCandidates` (b9d66dbb348) | #11335 (index idea), #11328 (closed), #11058, #12297's empty-list skip | "Wait for the RE cache" | Keep fork-only; re-measure after an RE cache lands; then offer on #11335 | Not yet |
| `-XX:-DontCompileHugeMethods` | #11933 | tool4ever rejected method splitting but called the problem distribution-specific; a launcher flag fits that | Propose upstream as a one-line launcher change, with numbers on ordinary games | Yes |

Top changes to adopt: cherry-pick #11933, #11964 and #11975 (all data-neutral, all apply together on `goldfish-v2`), then test #12189's flashback-event change, which should let our static skip fire more often.

## 1. Maintainer preferences

### Who does what

- **tool4ever** merges most engine and AI PRs and reviews nearly all perf work. He wants root-cause fixes, small diffs and one shared caching mechanism (`AiCache`). He dislikes threads and timeouts. He often cherry-picks the good part of a large PR himself: #11431 took FCollection out of #11366; #11920 took the fixes out of #11832/#11845 without their tests.
- **Hanmac** owns the trait and caching architecture: #11409 (CardTrait caching), #11058 (streams for `forEachCardInGame`), #11631, #11686, #10097 (StaticAbility unification) and #9574 (dependency layer). He prefers Streams and structural refactors over shortcuts.
- **MostCromulent** writes netplay changes and a steady run of measured perf PRs, generated with Claude Code. He also profiles other people's PRs (#12297) and has the most rigorous benchmarking method in the repo.
- **kevlahnota** focuses on Android/mobile and owns the AI timeout code. The timeout dates from Nov 2024 (e2c37d11e71 "AI Attack Timeout"). He self-merged #12265 with no approving review. He also opened #12297 (FCollection rewrite plus caches) and #12308 (Flat MonteCarlo).
- **Agetian** works on AI and merges sometimes. On #10099 he was keen on perf work: "This is actually very important (due to some calculations taking a lot of time, especially on Android), but needs thorough testing."

### What gets merged

Small changes that provably return the same result, with logs shown identical and numbers from more than one workload. tool4ever merges them within a day, usually with no comment:

| PR | Change | Size | Merged |
|---|---|---|---|
| [#11916](https://github.com/Card-Forge/forge/pull/11916) | cheap conditions before expensive ones, two AI paths | +20/−6 | same day |
| [#11925](https://github.com/Card-Forge/forge/pull/11925) | trigger mode before requirements in combat prediction | +8/−1 | same day |
| [#11946](https://github.com/Card-Forge/forge/pull/11946) | wide token cascades (Scute Swarm) | +23/−2 | same day |
| [#11951](https://github.com/Card-Forge/forge/pull/11951) | player outcome before win replacement scan | +3/−2 | same day |
| [#11956](https://github.com/Card-Forge/forge/pull/11956) | multi-zone lists in one pass | +9/−2 | 1 day |
| [#11961](https://github.com/Card-Forge/forge/pull/11961) | two guards before board scans | +19/−1 | 1 day |
| [#11963](https://github.com/Card-Forge/forge/pull/11963) | timing before flash scan | +5/−15 | same day |
| [#11974](https://github.com/Card-Forge/forge/pull/11974) | stop rescanning replacements on empty-library draws | +11/−2 | 1 day |
| [#11318](https://github.com/Card-Forge/forge/pull/11318) | hoist loop-invariant life-loss check | +5/−1 | same day ("good find, thanks") |
| [#11341](https://github.com/Card-Forge/forge/pull/11341) | identity fast path in `hasCreatureType` | +7 | same day |
| [#11360](https://github.com/Card-Forge/forge/pull/11360) | AI-vs-AI determinism at a fixed seed | +31/−7 | same day ("yea this will help on bug hunts") |
| [#11157](https://github.com/Card-Forge/forge/pull/11157) | fog scan limited to static source zones | +13/−16 | same day |
| [#11949](https://github.com/Card-Forge/forge/pull/11949) | lure check before block cost | +3/−2 | 2 weeks ("may not survive in the future since Hanmac wants to refactor them into statics too anyway: #3307 — but I guess we can still take it for now") |

Bodies follow one shape: problem, cause, why the result is unchanged, measurements, and a Claude disclosure. MostCromulent's #11961 is the template. It bundles two guards because "split into separate pull requests, neither could be shown to do anything".

### What gets closed or redirected

- [#11328](https://github.com/Card-Forge/forge/pull/11328) (decouple the zone skip from `PERFORMANCE_MODE`): "interesting idea, but at the same time pretty over-engineered … the right approach is just getting rid of the overhead instead of trying to detect it: #11058". Closed after: "assuming user has no card where it functions from some exotic zone should not be the default … the block is only meant as a bandaid".
- [#11931](https://github.com/Card-Forge/forge/pull/11931) (version-keyed trait caches): "I think this is now the fourth (AI) attempt for #11409". The author offered to close it in favour of #11409.
- [#11301](https://github.com/Card-Forge/forge/pull/11301) (zone-mutation-counter cache of static source cards): "seems a bit messy, imo the right approach is outlined in #11058 … we should have methods that let us iterate arbitrarily without too much overhead", then "imo both areas should just be fixed properly". Closed.
- [#10099](https://github.com/Card-Forge/forge/pull/10099) (combat prediction cache): "hate that "your" AI floods us with 30+ tests yet confidently claims it would cache these when the call order would obviously invalidate previous results … should extend/unify that approach by a more flexible helper class instead of creating parallel systems". tool4ever then wrote [#10149](https://github.com/Card-Forge/forge/pull/10149) `AiCache`.
- [#12026](https://github.com/Card-Forge/forge/pull/12026): "future work should be towards a common approach, not managing more trade-offs". Closed.
- [#10647](https://github.com/Card-Forge/forge/pull/10647) and [#9339](https://github.com/Card-Forge/forge/pull/9339): closed as drive-bys with no benchmarks.
- [#11895](https://github.com/Card-Forge/forge/pull/11895) (custom ThreadPoolExecutor): "I'm only seeing this evolved into more of a giant mess". Replaced by #12265.
- [#10507](https://github.com/Card-Forge/forge/pull/10507): the trait parts were superseded; the author closed the rest over a rules issue.

### Caches and invalidation

The consistent line: a cache whose correctness depends on hooking every mutation will be challenged. tool4ever prefers removing the work, or a scoped AI cache cleared at a known point.

- On [#8916](https://github.com/Card-Forge/forge/pull/8916), about invalidating on zone change: "your assumption of same string = same value is on shaky ground", and "by the time you're done hooking this up to all possible places I'd expect you end up with way more than 100 calls".
- On [#11160](https://github.com/Card-Forge/forge/pull/11160), Blackvipe99's fog check was cached per `game.getTimestamp()`. tool4ever answered "use `AiCache.getCached` (might need to move some methods to AI module so engine stays free of dependency)". Master now caches it in `ComputerUtilCombat` through `AiCache`, cleared per AI decision. **An engine-wide version stamp was swapped for a decision-scoped AI cache.** This is the precedent for our event counter.
- On [#11981](https://github.com/Card-Forge/forge/pull/11981): "I'd prefer if all future caching efforts connect with / extend `AiCache`, instead of each class attempting their own thing". He also named the hidden cost of equivalence classes: "trying to determine all variables involved in some AI heuristic (+ keeping them in sync) … what I definitely don't want is more heuristics to manage our heuristics".
- On [#10559](https://github.com/Card-Forge/forge/pull/10559): "such caching will always be fragile".
- On [#11314](https://github.com/Card-Forge/forge/pull/11314): "I just hate this approach of trying to guard expensive methods with redundant logic"; on the same PR, "mixing AI/GUI into engine".
- On [#11975](https://github.com/Card-Forge/forge/pull/11975): "not the biggest fan of manually adding shortcuts for library work everywhere". He approved the general version, a lazy table wrapper ("B seems safer with clearer architecture imo"). Hanmac was unsure.
- On [#9358](https://github.com/Card-Forge/forge/pull/9358), about skipping work per layer: "you can't only check the new affected / old ones (removal) might also need to be updated / that's why it will get ugly fast".
- Trait caching is wanted, but in Hanmac's shape. [#11409](https://github.com/Card-Forge/forge/issues/11409) plans a `CardTraitCache` record in three layers: base state, layer changes, then omen/adventure. tool4ever on liamiak's [#11366](https://github.com/Card-Forge/forge/pull/11366): "Some interesting ideas here and the code doesn't look too messy … probably superior to the similar part from #11314 since it wouldn't be limited to AI". On MostCromulent's fast paths: "looks a bit annoying to maintain, maybe the PR I've mentioned above which just caches everything instead is a better direction".
- Replacement effects are harder. Hanmac on [#11335](https://github.com/Card-Forge/forge/issues/11335): "we probably can't do too much of caching there, because some RE have looking forward stuff (especially ETB-RE)". tool4ever: "well yes `Moved` needs fresh cache but it also needs Library checked anyway, most other can hopefully reuse it", and "after we have RE cache this is hopefully no longer relevant". tool4ever wants the rules-host extras (Adventure/Omen/shield/stun…) moved out of `getReplacementEffects` first: on #11366, "these should not need caching if we have a PR add them differently first". On #11631: "just have `getReplacementList()` add them on demand".
- Invalidation holes found in practice:
  - liamiak's `clearCounters()` bypassed his hook, so a card kept shield/stun effects after its counters were gone (#11366).
  - tool4ever found a Spark Double endless loop that only stayed hidden because `getAbilityText()` happened to warm a lazy RE field ([#11780](https://github.com/Card-Forge/forge/pull/11780)).

### Flags and preferences

- There is no `System.getProperty`/`Boolean.getBoolean` switch in `forge-core`, `forge-game` or `forge-ai` main code on master (checked: the only hit is a Javadoc line in `Base64Coder`). liamiak dropped one on purpose (#11366): "The validation mode would have been the first `Boolean.getBoolean("forge.…")` switch in forge-game or forge-core, and adding a configuration mechanism as a side effect of a perf fix seemed like the wrong trade."
- `PERFORMANCE_MODE` (`Spell.setPerformanceMode`, set from the pref in `FModel`) is where tool4ever puts shortcuts that rest on an assumption about card scripts:
  - #11160: "let's just guard it by `Spell.performanceMode` on the off chance some custom card needs it to work".
  - #11327: "ok as temporary workaround".
  - #11328: "the block is only meant as a bandaid".

  The pref text is "Disables additional static abilities checks to speed up the game engine". Hanmac disagrees with using it for the replacement scan (#11335: "this is for something else and should not be used for this"). It defaults to false, so these shortcuts never run in CI. Our runner's `lazyperf` home sets it to true.
- AI shortcuts should go through an AI profile property. On #11981 tool4ever suggested "a profile property which the code may further scale dynamically", and MostCromulent added `SHORTCUT_MIN_CREATURES`.
- `Game.setNoGUIUser()` (#11780) is the engine's own headless switch, set by `SimulateMatch` and `GameCopier`. Our runner already calls it. tool4ever: "this can be restricted further for developers as needed".
- `GameRules` carries per-game knobs such as `simTimeout`. `Game.AI_TIMEOUT` is a public field, set from `FPref.MATCH_AI_TIMEOUT` in `HostedMatch`.

### Tests

- CONTRIBUTING.md: "Agents have a tendency to add unnecessary new unit or wiring tests to the CI suite. This should be avoided unless necessary to catch potential future integration regressions."
- On #11832: "such changes are welcome / just remove this test class, it's way too specific". On #11845: "fix is good, please just drop the test class again since it's too arbitrary". He then cherry-picked both fixes without tests in #11920.
- On #11198: "tests will in fact be needed however **148 is a completely unreasonable amount** … they need to be written around code knowledge".
- A test is accepted when the PR argues for it (#11761 kept a forked-JVM headless test). Tests that fail on master are what reviewers value. liamiak rewrote #11328's test because the old one "passes on master too".

### AI-written PRs

- Disclosure is required (CONTRIBUTING.md). Most perf PRs merged in the last two months were AI-assisted and say so.
- What gets pushback is size and bloat, not AI use. tool4ever on #11436: the AI "rarely leaves code comments / doesn't split changes up better / has little regard for technical debt instead of adding logic at inappropriate places / starts introducing weird bloat the larger the PR". Other examples:
  - #10559: "smells like AI with all these tests you've pumped out".
  - #11752: "this sounds a bit hallucinated".
  - #11671: "this AI quality isn't very impressive".
  - Jetz72 on #11360: comments "way too specific to fit in with the codebase".
- Several attempts at the same tracking issue get named as such (#11931: "the fourth (AI) attempt").

### Measurement

- tool4ever on #11933: "I need measurements on average games too, not only from a seed constructed for extreme gains of such shortcut".
- MostCromulent on #10647: "I've had AI agents recommend performance optimisations in the past based on code analysis alone which don't bear out when you actually profile the results."
- The accepted method (MostCromulent, liamiak):
  - Use three workloads: two-player token mirror, ordinary constructed, four-player commander precons.
  - Run four repetitions per arm, interleaved, with the order reversed between repetitions.
  - Show identical log hashes or turn counts between arms.
  - Report "slowest new run beat fastest old run" or say the result is within noise.
- Raise the AI timeout so neither arm abandons a decision the other finishes. On #10507 MostCromulent noted wall times drift "because a faster algorithm means AI has more time before it hits its thinking time cap".
- MostCromulent also serialised the parallel attacker evaluation and froze the prediction RNG to get replayable arms (#11975, #11981).
- liamiak on #11328: batched runs "quietly credit that drift to whichever build ran last".
- The upstream harness is `forge sim -d deck1 deck2 -n N -s seed` (seed option from #11365; headless since #11761).

## 2. WIP map (2026-10-23)

### Static abilities and CardTrait caching (#11409)

| Item | Owner | State | Notes |
|---|---|---|---|
| [#11409](https://github.com/Card-Forge/forge/issues/11409) CardTrait caching | Hanmac | open issue, stale-marked | Design: `CardTraitCache` record, three layers. liamiak counted 1.99M hits/8.3k misses on `getStaticAbilities` per normal game, and found 82,239 of 82,347 sliver-board invalidations re-added identical trait objects |
| [#11366](https://github.com/Card-Forge/forge/pull/11366) cache trigger and static lists | liamiak | open, stale-marked 10-10 | +62 lines in two files; replacement cache dropped on purpose; 1.24x ordinary, 1.09x dense; 4M verified hits |
| [#11931](https://github.com/Card-Forge/forge/pull/11931) version-keyed trait caches + static source list | khaliostr | open | "fourth (AI) attempt"; author offered to close |
| [#11964](https://github.com/Card-Forge/forge/pull/11964) empty-source shortcut for `getStaticAbilities`/`getTriggers` | MostCromulent | open, no review | +8 lines; tokens −14.6%, ordinary −15.7%, commander −2.5%; 564M calls checked, no wrong decision |
| [#12297](https://github.com/Card-Forge/forge/pull/12297) FCollection rewrite, `FSnapshotTable`, statics + RE caches, `isValid` parse cache, RE-scan empty skip | kevlahnota | open, updated 10-22 | +3442/−720. Profiled by MostCromulent: as posted +17–55% slower on wide boards. Hash threshold now 8: −20% tokens, −20% constructed, −15% commander. `getStaticAbilities` 17% of samples → ~0 |
| [#11631](https://github.com/Card-Forge/forge/pull/11631), [#11686](https://github.com/Card-Forge/forge/issues/11686) | Hanmac | open | Move rules-host replacements out of `getReplacementEffects` so the cache is simple |
| [#10097](https://github.com/Card-Forge/forge/issues/10097), [#9574](https://github.com/Card-Forge/forge/issues/9574), [#3307](https://github.com/Card-Forge/forge/issues/3307) | Hanmac | open | StaticAbility unification, dependency layer, hidden keywords to statics. Long-term; tool4ever expects #11949 to be undone by #3307 |
| [#11933](https://github.com/Card-Forge/forge/pull/11933) bare type name before the `cardHasProperty` chain | khaliostr | open | +6 lines. tool4ever asked for ordinary-game numbers; author posted 616 s → 585 s on 12 ordinary tables and offered an `IsCommander` reorder (55 ms → 47 ms median decision) |

### Card iteration (#11058)

| Item | Owner | State | Notes |
|---|---|---|---|
| [#11058](https://github.com/Card-Forge/forge/issues/11058) Streams for `forEachCardInGame` | Hanmac | open issue, stale-marked | tool4ever's "right approach" for #11328 and #11301. liamiak measured the traversal at ~1% of a replacement scan; the per-card RE rebuild is 79% |
| [#11323](https://github.com/Card-Forge/forge/pull/11323) Stream card zones | Hanmac | open | Partial; tool4ever "need to try and see if they would work / be faster". On 10-19 Hanmac pointed kevlahnota at it for #12297 |
| [#11431](https://github.com/Card-Forge/forge/pull/11431) FCollection from #11366 | tool4ever | merged 2026-07-28 | In our base |
| [#11975](https://github.com/Card-Forge/forge/pull/11975) lazy `Table` fields on `Card` | MostCromulent | open, approved by tool4ever, Hanmac unsure | −5.4% token games, identical hashes |

### Replacement effects

| Item | Owner | State | Notes |
|---|---|---|---|
| [#11335](https://github.com/Card-Forge/forge/issues/11335) ReplacementType + zones | Hanmac | open issue | liamiak: 81% of scan visits are library cards; only `Moved` ever matched. He proposed an RE-derived mode index; maintainers deferred it to the RE cache |
| [#11327](https://github.com/Card-Forge/forge/pull/11327) / [#11160](https://github.com/Card-Forge/forge/pull/11160) Tap/Untap/ProduceMana zone skip | liamiak / Blackvipe99 | merged, behind `PERFORMANCE_MODE` | In our base; our runner enables it |
| [#11328](https://github.com/Card-Forge/forge/pull/11328) | liamiak | closed | Table: master 127.5 s, PR 79.3 s, #11366 53.5 s, both 50.1 s |
| #12297 RE cache + empty-list skip in `getReplacementList` | kevlahnota | open | Direct competitor to our `cacheReplacementLists`; skips cards with no REs before the zone lookup |

### AI timeout and threads

| Item | Owner | State | Notes |
|---|---|---|---|
| [#12265](https://github.com/Card-Forge/forge/pull/12265) `AITimeoutTracker` | kevlahnota | merged 2026-10-16 (self-merged) | Shared deadline per AI think; cooperative `check()`; deadline now also cuts `declareAttackers`/`declareBlockersFor`; "Game AI Eval" thread and the must-attack pool both remain |
| [#11895](https://github.com/Card-Forge/forge/pull/11895) | kevlahnota | closed | tool4ever: "get rid of any arbitrary exit attemps with Threads and reduce it down to the timeout checks at safe points" |
| [#11718](https://github.com/Card-Forge/forge/pull/11718) single-thread engine access | MostCromulent | open | tool4ever: "that whole timeout mess was introduced by another dev against my concerns - it eventually needs a way better implementation anyway" |
| [#11981](https://github.com/Card-Forge/forge/pull/11981) blocker grouping behind `SHORTCUT_MIN_CREATURES` | MostCromulent | open, changes requested | tool4ever 10-21: "I'll want to consider the implications from the broad usage of `AITimeoutTracker` on how we approach this and related optimizations" |
| [#11314](https://github.com/Card-Forge/forge/pull/11314) Improve AI play speed | Madwand99 | open | Body still describes a "reuse an unchanged completed priority pass" cache that is no longer in the diff. Remaining: combat matrix, thread-local trait views, playable-first SA discovery. tool4ever is lukewarm on the structure |

### `PERFORMANCE_MODE`

Gates two things on master: the `Spell.canPlay` LKI re-control skip, and the Tap/Untap/ProduceMana replacement zone skip. #11328 (split it) was closed. Hanmac wants the RE-scan use removed (#11335). No open PR changes it.

### Determinism

- [#11360](https://github.com/Card-Forge/forge/pull/11360) merged: identity-hash iteration order. It is in our base, alongside our `-XX:hashCode=2`.
- [#11285](https://github.com/Card-Forge/forge/pull/11285) open: thread-local `MyRandom`, sorted zone prep. tool4ever worries about the OS scheduler as a new source of nondeterminism.
- [#11260](https://github.com/Card-Forge/forge/issues/11260) open: per-game id counters.
- [#12180](https://github.com/Card-Forge/forge/pull/12180) open: opt-in AI decision log.

### Merged on master since our base that overlaps our patches

- **#12265** rewrote both blocks our AI commits patch: the eval `FutureTask` in `AiController.getSpellAbilityToPlay` and the must-attack pool in `AiAttackController.declareAttackers`. Both of our commits conflict textually. The deadline also applies on the game thread now, so determinism under load needs `game.AI_TIMEOUT` raised.
- **[#12189](https://github.com/Card-Forge/forge/pull/12189)** made three changes that touch our event counter:
  - It moved `GameEventPlayerPriority` into the loop after `checkStateBasedEffects()`, which conflicts with our `mainLoopStep` hunk.
  - It added `GameEvent.isRedrawHint()` for `CardStatsChanged`, `Zone` and `PlayerStatsChanged`. That is the same idea as our `fireDerivedEvent`, already in upstream's vocabulary.
  - It made `Player.updateFlashbackForView` fire `GameEventZone` only when the zone changed. In our fork that event is counted, and after any rebuild that touched a card it bumps the counter, so the next rebuild never qualifies as stable.
- **#12171/#12188/#12121** (simultaneous entry): `checkStaticAbilities` gained a 4-argument overload with `enteringWith`. Our early return has to move into that body and treat a non-null `enteringWith` like a non-empty `preList`. These are rules changes, so `log_hash` will move on rebase.
- **#11949** (lure before block cost) and **#12226** (UndoManager): the UndoManager only checkpoints for human prompts, so AI-only games pay nothing.

## 3. Per-patch recommendations

### 3.1 `forge.ai.inlineEvaluation` — 3b2c89647fe

**What it does.** Runs the play-evaluation `FutureTask` on the game thread instead of a new "Game AI Eval" thread, so there is no timeout.

**Upstream overlap.** #12265 (shared deadline, cooperative `Expired`, `join(2000)`, `Thread.stop()` fallback), #11861, #11895, #11161, [#8302](https://github.com/Card-Forge/forge/pull/8302), #11718.

**Reception.** This is the most politically loaded file in the AI:
- tool4ever wants threads gone ("Only cooperative exit should be used … to keep the engine stable", #11895).
- He also refused to drop the `stop()` safety net: "removing is not an option, see #8302 / the loop exit may be reached too late or even never" (#11161).
- kevlahnota just self-merged the tracker, and tool4ever has said he will revisit it.

A PR making inline the default would land in the middle of that.

**Default safety: no.** Mana payment, threat prediction and simulation contain no `check()` (MostCromulent's review of #12265). An AI stuck there would hang the game, where today the AI passes after the timeout. That is real on Java 17 desktop; on Java 20+ and Android `stop()` already fails.

**Recommendation: keep fork-only, and try to make it unnecessary.**
- The runner can set `game.AI_TIMEOUT` (public field, default 5) to a large value right after `match.createGame()`. The threaded path is then deterministic: the game thread waits on `future.get`, and `MyRandom` is drawn in program order.
- Measure inline against threaded with the same seeds; `log_hash` should be identical. One `Thread` start per evaluation could be a few percent.
- If the thread costs time, keep inline, but move the switch off `-D`, for example to a `GameRules` flag the runner sets.
- On master, `future.run()` goes after `AITimeoutTracker.begin(...)`, and `end(aiId)` stays in the `finally`.

### 3.2 `forge.ai.sequentialAttacks` — 6221d832a8f

**What it does.** Runs the per-attacker must-attack checks in order on the calling thread instead of on `newFixedThreadPool(availableProcessors())`.

**Upstream overlap.** #12265 now wraps each task in `withDeadline` and adds a `combatClosed` guard against late workers. #11161 had to synchronize `combat.addAttacker` after CMEs that silently dropped attackers. MostCromulent ran this block serially to get replayable arms (#11975, #11981). The pool came with kevlahnota's 2024 timeout work, and he still frames declare-attackers as the place where parallel compute is "needed" (#11895).

**Reception.** It fits tool4ever's position: no threads, cooperative checks, determinism that "will help on bug hunts". On master the change is a deletion: call the `withDeadline` callables in a loop, and `combatClosed`/`synchronized` go away. kevlahnota is the one to persuade. The pool is also created for every predicted combat (`getPredictedCombat` → `declareAttackers` with `nextTurn=false`), so serial may be faster as well. That is untested; measure it.

**Default safety: yes.**
- Rules are unchanged.
- Attackers get added in `this.attackers` order instead of completion order, which is deterministic.
- The deadline still applies through `withDeadline`.
- The only loss is parallelism when many attackers have expensive requirement checks.

**Recommendation: rewrite on master and propose as default, after a Discord heads-up.**
- Measure on `sim -s` with ordinary, token and commander decks, AI timeout raised, four interleaved reps.
- Title: `AI: evaluate forced attackers in order`.
- Until it lands, keep the fork version.

### 3.3 `forge.game.skipRedundantStateChecks` — ecd2424009c

**What it does.** A `Game` counter of every event except `GameEventPlayerPriority`, plus Zone add/remove/setCards/shuffle. After a plain pass with no change in the count, the next state-based check is skipped.

**Upstream overlap.** #12189 and [#8675](https://github.com/Card-Forge/forge/issues/8675). Hanmac on #8675, about Yavimaya-style statics: "cards like `Yavimaya Cradle of Growth` can create dependencies. Forge needs to calculate them each time priority is passed." Also #8916 (zone-change invalidation rejected), #11160 (engine version stamp swapped for `AiCache`), #11328/#11058 ("getting rid of the overhead instead of trying to detect it").

**Reception.** Low odds as default.
- It is a convention-based invalidation ("every state change fires an event") of the kind tool4ever expects to leak.
- The extra `noteStateChange` hooks in `Zone` show the convention already has holes (tests and dev setup fire no events).
- If offered at all, frame it as `PERFORMANCE_MODE` ("Disables additional static abilities checks…", which describes it).
- Rebuild it on master's own signal. #12189 already cuts network batches on "anything that is not `isRedrawHint()` since the last priority checkpoint", so the SBA skip can reuse exactly that test instead of a new `fireDerivedEvent`.
- Bring a validation run (run the check anyway and assert it changes nothing, as liamiak did with 4M verified hits) and ordinary-game numbers.

**Default safety: no.** The failure is a state change with no event. The missed SBA is then delayed until the next event (usually the next phase change). That is rare, but it is a silent rules bug.

**Recommendation: keep fork-only.** It is our biggest single win: 1.79 s → 1.31 s per game. When rebasing:
- Move the hunk around the relocated priority event.
- Consider swapping `fireDerivedEvent` for `isRedrawHint()`.

### 3.4 `forge.game.skipUnchangedStatics` — 25d64fc617c (plus the `fireDerivedEvent` line in 781bc67e2d3)

**What it does.** A full static rebuild returns early once a previous rebuild started from the last one's end state and changed nothing. A partial (`preList`) rebuild resets this.

**Upstream overlap.**
- #11964, #11366, #12297 and #11409 make each rebuild cheaper by caching or short-circuiting `getStaticAbilities`.
- #11933 and the JIT flag cut the `getAffectedCards` → `cardHasProperty` cost, which is our 18.6% self time.
- #11932 (`AffectedDefined$` for Self/Enchanted/Equipped) is already in our base.
- #9358 shows how tool4ever reads skip-by-diff logic in this method.

None of these skip the rebuild; they are complementary.

**Reception.** As for 3.3, and weaker: the Storied fix (a rebuild that adds keyword triggers acting on the next one) is exactly the subtlety reviewers fear.

**Default safety: no.**

**Recommendation: keep fork-only.**
- If 3.3 is ever proposed under `PERFORMANCE_MODE`, propose this in the same PR.
- Housekeeping: the `PhaseHandler` `fireDerivedEvent` change belongs in this commit, not in "turn skipper hook" (781bc67e2d3).
- On master, put the early return in the 4-argument `checkStaticAbilities` and treat `enteringWith != null` as partial.
- #12189's flashback change should make this skip fire after rebuilds that touch cards, which today it never does. Expect a gain after rebase; measure it.

### 3.5 `forge.game.cacheReplacementLists` — b6cdac5e770

**What it does.** Caches `CardState.getReplacementEffects(true)` for cards with no text, type, keyword or trait changes and no counters. Invalidation is a version bump on add/clear/copy plus keyword-cache identity, which is valid because `updateKeywordsCache` allocates a new collection.

**Upstream overlap.**
- #12297 does the same for every card: it re-validates on read against table versions, keywords, split versions and a type/counter flag mask, and returns `shareCopy()`.
- #11931 does it on versions.
- #11366 dropped its RE cache on purpose.
- #11409, #11631 and #11686 want the rules-host extras moved out first.

**Reception.** It would be redirected to #11409 as one more attempt. It is narrower than #12297.

**Default safety: not as written.**
- It returns the cached `FCollection` itself. No caller mutates it today, but #12297 copies.
- "Unchanged" ignores `changedCardTypesCharacterDefining`. That is harmless today, since no CDA grants Planeswalker/Battle/Saga/Adventure.
- Its correctness rests on every RE-list mutation going through the three bumped methods. liamiak's `clearCounters` bug is how such holes look. We avoid that one by refusing to cache any card with counters.

**Recommendation: keep as a fork stopgap, don't propose, drop it when #12297 or a #11409 implementation lands.** Then re-measure; liamiak's numbers suggest a general cache beats ours.

### 3.6 `forge.game.memoReplacementCandidates` — b9d66dbb348

**What it does.** `getReplacementList` walks a memoized list of cards that have any RE. The list is rebuilt on any counted event or static reapply. It falls back to the full walk for `preList`, sideboard, and `Moved` with LKI.

**Upstream overlap.**
- #11335 proposes an RE-derived index. tool4ever's "`Moved` needs fresh cache … most other can hopefully reuse it" is close to what we built.
- #11328 was closed.
- #11058 is the preferred direction.
- #12297 skips empty-RE cards cheaply once lists are cached.
- liamiak measured that a zone skip on top of a trait cache adds only 6.7% (#11328), so our marginal gain will shrink once an RE cache lands.

**Reception.** "After we have RE cache this is hopefully no longer relevant."

**Default safety: not yet.** The hole is a card gaining its first RE with no counted event before the next scan. Animate and Pump fire `GameEventCardStatsChanged`, so known paths are covered; the guarantee is still by convention.

**Recommendation: keep fork-only.**
- Re-measure after #12297 or #11366 is in the fork.
- If it still pays, offer it as the index follow-up in a comment on #11335 before any PR, probably gated on `PERFORMANCE_MODE`.

### 3.7 JVM flag `-XX:-DontCompileHugeMethods`

**Upstream overlap.** #11933. khaliostr found that `cardHasProperty` is above HotSpot's 8,000-byte `HugeMethodLimit`, so a stock JVM never JIT-compiles it. On his token board the flag alone took 252 s → 181 s. tool4ever rejected method splitting ("I'd hate arbitrary method splitting … this being distribution specific changes things, I don't think that's a good constraint for code tailoring"). He floated GraalVM for end users instead ("I've been meaning to potentially recommend that as default for end users anyway").

**Facts checked here.**
- Parsing the `goldfish-v2` jars, five methods exceed 8,000 bytes:
  - `CardProperty.cardHasProperty` 14,082
  - `NameGenerator.<clinit>` 12,164
  - `AbilityUtils.xCount` 10,761 (hot: every `Count$`)
  - `CardFactoryUtil.addTriggerAbility` 9,043
  - `CardFactoryUtil.addSpellAbility` 8,391
- `DontCompileHugeMethods` is a `{product}` flag; JDK 26 accepts it without unlocking options.
- Desktop launch args come from `<mandatory.java.args>` in `forge-gui-desktop/pom.xml`, which feeds `forge.sh`, `forge.command`, `forge.cmd` and the launch4j `<opt>`. `forge-gui-mobile-dev/pom.xml` is the equivalent for the adventure launcher.

**Reception.** Good chance. It changes no code, it addresses the distribution-specific objection directly, and it helps both hot methods.

**Default safety: yes.** There is no behaviour change. The cost is C2 compile time and code cache for five methods. Android is unaffected. Verify that OpenJ9 ignores the option rather than failing to start.

**Recommendation: propose upstream as is.**
1. Comment on #11933 first with ordinary-game numbers, as tool4ever asked: commander precons and constructed, flag on and off, four interleaved reps, identical logs.
2. Then open a one-line PR adding the flag to `mandatory.java.args` in both poms.
3. Keep it in `run.sh` regardless.

## 4. Upstream changes to adopt

The goal is speeding up headless AI-vs-passive goldfish. Every row was dry-run with `git apply` against `goldfish-v2`.

| # | Change | Expected effect for us | Data risk | How |
|---|---|---|---|---|
| 1 | [#11933](https://github.com/Card-Forge/forge/pull/11933) bare core type/supertype resolved first in `cardHasProperty` | Hits our top self-time method. 163 s vs 181 s with the JIT flag on a token board; 5% on ordinary tables | None: pure reorder, identical decisions upstream | `gh pr diff 11933 -R Card-Forge/forge \| git apply -3` on a worktree from `goldfish-v2`; check `log_hash` identical. Applies cleanly |
| 2 | [#11964](https://github.com/Card-Forge/forge/pull/11964) empty-source shortcut for `getStaticAbilities`/`getTriggers` | Static rebuilds are 41% of our time, and most cards have no statics: tokens −14.6%, commander −2.5% | None: identical logs, 564M calls checked | Same; applies cleanly. Conflicts with #11366, so pick one, this one first |
| 3 | [#11975](https://github.com/Card-Forge/forge/pull/11975) lazy `Table` fields on `Card` | −5.4% token games; our policy games build big token boards | None: identical hashes | Same; applies cleanly together with 1 and 2 |
| 4 | #12189's `Player.updateFlashbackForView` change (fire only on change) | Lets `skipUnchangedStatics` count a rebuild as stable after it touched cards. Gain unmeasured | None expected | `git -C ~/src/forge-pr show a2ba8ce9ad5 -- forge-game/src/main/java/forge/game/player/Player.java \| git apply`; applies cleanly |
| 5 | Runner sets `game.AI_TIMEOUT` high | Needed once we rebase onto #12265, where the deadline also cuts attack/block declaration; may let us drop `inlineEvaluation` | None | One line in `Runner.play` after `createGame()` |
| 6 | [#11366](https://github.com/Card-Forge/forge/pull/11366) trigger/static list cache | 1.24x ordinary upstream, but most of its gain overlaps #11964 on token boards | Low (4M verified hits) | Only if #11964 leaves `getStaticAbilities` hot in JFR |
| 7 | #12297 pieces | `Card.isValid` parse cache (in our `getAffectedCards` path), RE-scan empty skip, statics/RE caches | Medium: large diff, still moving | Wait for merge, or cherry-pick the `ParsedRestriction` hunk alone and check hashes |
| 8 | Rebase onto master | Brings #11949, #12189, #12265 and the AI fixes | Hashes change: simultaneous-entry rules (#12171/#12188/#12121) and AI play fixes | Once, with the 1000-seed accuracy check from the brief |

Not useful for this workload: #11981 and #10507 (blocking; passive opponents have no attackers), and #12308 (search AI, not speed).

## 5. Side findings

- 781bc67e2d3 ("turn skipper hook") also reroutes the post-SBA stats event to `fireDerivedEvent`. That line belongs with 25d64fc617c; split it before anything leaves the fork.
- MostCromulent's open [#12357](https://github.com/Card-Forge/forge/pull/12357) ("AI: use Shadowmoor filter lands, and correct the AI's mana estimate") covers the same ground as our `pr/ai-hybrid-filter-lands` and `pr/ai-filter-plan-matches-payment` branches. Compare before opening ours.
- #11314's body still claims a priority-pass reuse cache (1,070x) that is no longer in its diff; don't cite it.
- `MostCromulent` and `liamiak` both serialised or seeded around the same nondeterminism we hit. If we propose 3.2, link their measurement notes (#11975, #11981) as evidence that maintainers already need it.
