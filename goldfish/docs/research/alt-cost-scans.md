# Alternative cost scans and LKI copies (2026-10-23)

> Merged: the 4 commits are on `goldfish-v2` (1, 2 and 4 with the upstream candidates, 3 with the fork-only flags). `altcosts/upstream` is now `pr/ai-static-sources-once` (draft in `docs/upstream-prs/`). The scratch worktrees and `~/src/gf-altcosts` are removed; the LKI counting patch is in `docs/research/patches/lki_instr.py`, the sim A/B tool in `prototype/simab.py`.

Branch `altcosts/scan-once` in `~/src/forge-altcosts`: 4 commits on `goldfish-v2` as it was at 767d083b762, where
every number below except the last section was measured. `goldfish-v2` was rebuilt while this ran (statics list cache,
duplicate-free zone joins), so the same 4 commits are also on `altcosts/scan-once-v2` (`~/src/forge-altcosts-v2`),
cherry-picked onto 4e3b8d36273 without conflicts and measured there too (see "On the rebuilt goldfish-v2").
Upstream-based copies for ordinary-game timing: `altcosts/upstream` (`upstream-master` + commits 1 and 2) and
`altcosts/upstream-cost` (+ commit 4). Runner copy `~/src/gf-altcosts`.

## Result

| # | Commit | Kind | Goldfish cpu per game | Ordinary games (wall per game) |
|---|---|---|---|---|
| 1 | AI: collect static ability sources once per ability list | upstream candidate | −5.6% (95% −6.6..−4.3) | commits 1+2 together: commander −3.0% (95% −3.8..−2.3), constructed −1.7% (−2.5..−1.0) |
| 2 | Check layer and zone before conditions in hasStaticAbilityAffectingZone | upstream candidate | −0.8% (−1.6..0.0) | see row 1 |
| 3 | perf: memo alternative cost and stack ability sources behind a flag | fork only, `-Dforge.game.memoAltCostSources=true`, off by default | −5.6% (−5.9..−5.0) | not measured (fork only) |
| 4 | Build the cost adjustment source list in one pass | upstream candidate | −1.4% (−1.8..−0.5) | within noise: commander −0.3% (95% −0.9..+0.7), constructed −0.4% (−1.8..+0.3 paired) |

Whole branch against `goldfish-v2`, same 50 seeds, 3 interleaved rounds: 772 → 673 ms cpu per game (−12.8%, 95%
−14.3..−12.1) with the flag, 756 → 694 ms (−8.1%, 95% −9.1..−7.1) without it (commits 1, 2 and 4 only).

Accuracy: every `log_hash` and every other output field identical to `final-1000` on seeds 1-1000 (`--threads 4`),
standard flags, both without and with the memo flag. Forge tests 451/451 with no flags, with the standard flags, and
with the standard flags plus the memo flag.

Method: goldfish rows are policy games, deck and policy as in the brief, standard flags, single thread, seeds 1-50,
A and B as two processes started together, launch order swapped every round, 3 rounds, mean `cpu_ms` per game with
the first 3 games of each process dropped (JIT), 95% interval of the paired per-seed log ratio. Each row is measured
against the commit before it. Ordinary games: `forge sim` from the worktree's `forge-gui`, own profile dir per arm,
commander = 8 tables of 4 random precons from `res/quest/commanderprecons` (one game each, seeds 1000-1007),
constructed = 10 random pairs from `res/geneticaidecks` (3 games each), A and B of a table started together, order
alternating, 3 rounds, game wall time from the "Game Result" line. Full game logs compared between arms: all
identical except one commander game where `upstream-master` itself declared the same attackers in a different order
in one of its three rounds (the other five runs of that table matched), master's own nondeterminism.

## On the rebuilt goldfish-v2

`altcosts/scan-once-v2` against `goldfish-v2` 4e3b8d36273, standard flags now including `cacheStaticLists`, same
method: 556 → 536 ms cpu per game without the memo flag (−3.4%, paired 95% −4.2..−3.5), 559 → 511 ms with it (−8.7%,
95% −9.5..−8.5). The statics cache and the duplicate-free zone join took part of what commits 1 and 4 removed, the
memo still pays. Commit 4 passes a `List` of zones, not an `EnumSet`, so it doesn't get the goldfish duplicate-free
join: an `EnumSet` iterates Battlefield, Command, Stack (ordinal order) and the raise/reduce statics must stay in the
old Battlefield, Stack, Command order. Seeds 1-1000 identical to `final-1000` (every field) with and without the memo flag, Forge tests
451/451 with no flags, standard flags, and standard flags plus the memo flag.

## Where the time went

The AI enumerates its options once per priority (`AiController.getSpellAbilityToPlay` →
`ComputerUtilAbility.getSpellAbilities`): every ability of every card in hand, graveyard, exile, command zone, the top
of each library and every battlefield card of every player (each opponent's lands included). For each ability
`Card.getAllPossibleAbilities` calls `GameActionUtil.getAlternativeCosts`, which:

- for an activated ability, scans every card in the static source zones for `AlternativeCost` statics
  (`StaticAbilityAlternativeCost.alternativeCosts`)
- for a basic spell, does the same scan, then `hasStaticAbilityAffectingZone(Stack, ABILITIES)`, a second scan over
  the same cards that evaluated `checkConditions` on every continuous static before its cheap filters

Each scan started from `game.getCardsIn(STATIC_ABILITIES_SOURCE_ZONES)` (a fresh hashed `CardCollection`) and the
alternative cost scan then copied it again into a second hashed collection to put the source first. Building the
lists was 5.1% of all samples, `getStaticAbilities` on each card 2.8%, the condition checks 0.8%.

The only `AlternativeCost` static in the goldfish deck is Flawless Maneuver's own, and nothing in it changes
abilities on the stack, so nearly all of this work finds nothing.

JFR, 40 policy games each, share of all samples:

| Inclusive | goldfish-v2 | commits 1+2 | all 4, flag on |
|---|---:|---:|---:|
| `getAlternativeCosts` | 9.5% | 5.3% | 1.0% |
| `StaticAbilityAlternativeCost.alternativeCosts` | 6.5% | 3.3% | 0.5% |
| `hasStaticAbilityAffectingZone` | 2.6% | 1.6% | 0.0% |
| `ComputerUtilAbility.getSpellAbilities` | 14.5% | 10.2% | 7.1% |
| `AiController.getSpellAbilityToPlay` | 19.5% | 15.7% | 12.7% |

## The commits

### 1. Collect static ability sources once per ability list (upstream candidate)

`getSpellAbilities` and `getOriginalAndAltCostAbilities` build the static source list once and pass it down
(`Card.getAllPossibleAbilities`, `GameActionUtil.getAlternativeCosts`, `StaticAbilityAlternativeCost.alternativeCosts`
and `GameAction.hasStaticAbilityAffectingZone` gained an overload that takes it). `alternativeCosts` checks the source
first and skips it in the list (`equals` is by id, as the old `CardCollection` dedupe was) instead of copying the list.
Other callers pass nothing and get one list per call, which is still one build fewer than before for a basic spell.

Why it can't change a decision: the list is the cards in battlefield, graveyard, exile, command and stack. Nothing in
the enumeration moves a card between zones or phases one out. The LKI checks inside it (`getAlternateHost`, the stack
copy, the morph-up restriction check) reapply statics on copies, they don't move cards. So every call gets the same
cards in the same order it would have built itself. The statics are still read fresh from each card on every call.

Shape upstream may object to: a nullable list parameter threaded through four public methods. A caller that passes a
list built before a zone change gets a stale answer silently. Only `ComputerUtilAbility` builds one, inside one
enumeration.

### 2. Layer and zone before conditions (upstream candidate)

`hasStaticAbilityAffectingZone` called `checkConditions(Continuous)` first, then checked the layer and the
`AffectedZone` param. Now mode, layer, zone, then conditions. `checkConditions` only reads state (no continuous static
in the card pool uses a `Count$Random` SVar in its conditions, checked with grep). Small on its own, it fits the
"cheap conditions before expensive ones" PRs that upstream merges (#11916, #11925).

### 3. Memo alternative cost and stack ability sources (fork only, flag)

`GameAction` keeps two lists: static source cards with an `AlternativeCost` static, and those with a continuous
ABILITIES static whose `AffectedZone` includes the stack. They're rebuilt when `stateEventCount` or `staticsVersion`
moved, the same invalidation `memoReplacementCandidates` uses. `getAlternativeCosts` scans only those cards (usually
none), the source itself is still checked every call, conditions and `apply` are still evaluated every call. The LKI
checks inside `getAlternativeCosts` reapply statics, which bumps `staticsVersion`, so the memo rebuilds inside the
window and again after the reset.

Fork only because it is a cache across decisions resting on "a card's statics change only with a game event or a
static rebuild". Known holes: an effect that grants a static without firing a counted event, read by an AI decision
before the next rebuild; and a timed-out AI evaluation thread (inline evaluation off) racing the game thread on the
memo fields. Neither happens in the runner's games (inline evaluation is on).

### 4. Cost adjustment source list in one pass (upstream candidate)

Both `CostAdjustment.adjust` methods (raise costs on `Cost`, reduce/set on `ManaCostBeingPaid`) built battlefield,
stack and command lists separately and merged them into a third collection, for every cost check of every ability the
AI looks at (`canPlay` → `CostPayment.canPayAdditionalCosts`). Now one `game.getCardsIn` over the three zones in the
same order, same dedupe, host still appended last when it's not in them. Same shape as #11956 (multi-zone lists in
one pass).

## Second target: LKI copies

Counted with temporary instrumentation (not committed), 20 policy games:

- 238 `copyLastState` calls per game, 37 cards copied per call on average. Callers: `resolveStack` before and after
  each resolution (2 × 67 per game), `mainLoopStep` after each play (60), `Player.playLand` (34), combat damage (2 × 5)
- plus per-zone copies from effects: `GameAction.getLastState` fallback (30 per game per zone), token creation (15),
  `DigUntilEffect` (9), cost payment (6)
- 41% of copied cards are passive opponents' plain basic lands (`hasNoTraitEffects`)
- only 3% of `copyLastState` calls had no counted game event since the previous copy, and none had neither an event
  nor a static rebuild in between. A skip keyed on the fork's counters would save nothing
- cost is the copy itself: `CardState.copyFrom` (deep copies of abilities, triggers, replacements, statics) is 2.9% of
  samples, `Card.<init>` 0.7%, `Card.copyFrom` 0.5%. The zone wrapper and its event are 0.1%

Not done. No exact cut found: the copies are taken at rules-defined points, the state changes between nearly all of
them, and making one cheaper means sharing ability objects between a card and its LKI copy, which is #11409 territory.
A goldfish-only option would be to share or skip copies of passive opponents' plain lands (up to ~40% of the copy
work), but a land that leaves the battlefield (Ashen Rider can exile one) needs its LKI, so it
would need a copy-on-change scheme. Not attempted.

## Leads for others

`Game.getCardsIn(Iterable<ZoneType>)` is 12-13% inclusive after these commits, almost all of it a fresh hashed
`CardCollection` per `StaticAbilityXxx` scan: `getDevotionMod` 2.0%, `anyTurnPhaseReversed` 1.6%,
`Continuous.getAffectedCards` 1.3%, `CostAdjustment.adjust` 1.1%, `CastWithFlash` 1.1%, a dozen combat and targeting
scans at 0.2-0.5% each. Passed to the statics agent. `CostPayment.canPayAdditionalCosts` → `Card.canTap` →
replacement `cantHappenCheck` is another per-ability rescan, 2.4%.

## Files

- `~/src/gf-altcosts/ab.sh`, `abstat.py`: interleaved goldfish A/B (`AFLAGS`/`BFLAGS` per side)
- `~/src/gf-altcosts/simab.py`, `simsame.py`: ordinary-game A/B (`SIM_A`/`SIM_B` worktrees) and game log identity
- `~/src/gf-altcosts/hashrun.sh`, `hashcheck.py`: hash and field identity against `final-1000`
- `~/src/gf-altcosts/prof/{base,c2,final}.jfr`, `prof_lines.py`, `prof_path.py`: profiles and line-level queries
- `~/src/gf-altcosts/patches/lki_instr.py`: the LKI counting patch
