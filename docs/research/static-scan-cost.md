# Static ability scan cost (2026-10-23)

> Merged: the 4 commits are on `goldfish-v2` (1 and 2 with the upstream candidates, 3 with the fork-only flags, 4 after the goldfish-only commits). The upstream branches are now `pr/static-list-without-copy` and `pr/turn-order-when-needed` (drafts in `docs/upstream-prs/`). The scratch worktrees and `~/src/gf-statics` are removed; `simab.py` moved to `prototype/`.

Branch `statics/scan-cost` in `~/src/forge-statics` (4 commits on `goldfish-v2`, commit 4 amended to be goldfish-only), runner copy and tools in
`~/src/gf-statics` (`go.sh`, `ab.sh`, `abstats.py`, `hashcmp.py`, `snap.sh`, `prof.py`).

## Result

Policy games, standard flags, single thread, interleaved A/B on seeds 1-50, 3 rounds, 141 paired games:

| | `goldfish-v2` | Branch head | Change (95%) |
|---|---:|---:|---|
| Head with `-Dforge.game.cacheStaticLists=true` | 771 ms | 602 ms | −21.9% (−22.8..−20.8) |
| Head without it (commits 1, 2, 4 only) | 759 ms | 633 ms | −16.6% (−17.3..−15.5) |

`cpu_ms` is game thread CPU. Wall time moved the same way (−22.0% / −16.6%).

Data: every `log_hash` identical to `final-1000` on seeds 1-1000 (`--threads 4`), with the cache flag on and with it
off; status counts identical (801 dead, 198 horizon, 1 other). Forge tests 451/451 with no flags, with the standard
flags, and with the standard flags plus `cacheStaticLists` (through `JAVA_TOOL_OPTIONS`). These numbers were taken
before commit 4 was gated on the goldfish switch; after gating: 100/100 hashes with the cache on, tests 451/451 with
the standard flags plus `cacheStaticLists`, and the same −12% from commit 4 in the runner.

## Where the time went

Policy games, standard flags, single thread, JFR 40 games. Share of samples, and the implied ms per game.

| Inclusive | `goldfish-v2` | Branch head, cache on |
|---|---:|---:|
| `CardState.getStaticAbilities` | 18.0% / 129 ms | 12.0% / 73 ms |
| `Game.getCardsIn` | 17.1% / 123 ms | 6.1% / 37 ms |
| Turn order reversal scan (`StaticAbilityTurnPhaseReversed`) | 4.2% / 30 ms | 1.1% / 7 ms |
| `StaticAbilityContinuous.getAffectedPlayers` | 2.6% / 19 ms | 0.3% / 2 ms |
| Static rebuilds (`checkStaticAbilities`) | 26.6% / 191 ms | 27.4% / 167 ms |

(JFR runs: 719 and 609 ms game thread CPU per game under the recorder.)

Who calls `getStaticAbilities`: about 50 `StaticAbilityXxx` mode scans (alternative costs, cost adjustment, devotion,
cast with flash, combat damage checks, turn order...), the static rebuild, and the AI. Each scan walks every card in
`STATIC_ABILITIES_SOURCE_ZONES`. That is about 4.6 million `getStaticAbilities` calls per game.

Call mix on 20 games (instrumented build, not committed):

| Case | Calls | Share of non-empty |
|---|---:|---:|
| Empty shortcut (#11964: no statics, no keywords, no trait changes) | 69.1 M | |
| No trait changes, no keyword carries a static | 9.6 M | 40% |
| No trait changes, a keyword carries a static | 10.1 M | 42% |
| Trait changes on the card | 4.2 M | 18% |
| Split cards, removed intrinsics | 0 | |

The keyword case is common because Forge turns Flying, Vigilance and Protection into statics: tokens with granted
Protection (Serra's Emissary), Insect tokens, Ovika, Sun Titan, Ashen Rider. The trait case is the planeswalkers
(Jace, Elspeth, Nicol Bolas) and the "Keyword Effects" command card.

## Commits

| # | Commit | Kind | What | `cpu_ms` per game | Hashes |
|---|---|---|---|---|---|
| 1 | `d48bf18b88a` | no cache, upstream candidate | Card without trait changes, split faces or removed intrinsics: add keyword statics directly instead of the generic trait walk; when no keyword adds one, return the state's own list. The copy effect that removes CDA statics while looping now loops over a copy | 776 → 754 (−2.8%, 95% −3.3..−2.3) | 100/100 |
| 2 | `245191c412e` | no cache, upstream candidate | `getAffectedPlayers` filters players in seat order and reverses only when 2+ match and the turn order is reversed, instead of asking for the turn order (a full card scan) for every continuous static with `Affected$` | 717 → 694 (−3.3%, 95% −3.9..−2.4) | 100/100 |
| 3 | `0912d46ae38` | fork-only cache, `-Dforge.game.cacheStaticLists=true`, off by default | Caches the list from commit 1's path. Key: a version bumped on every change to the state's own static list, the keyword cache's identity (`updateKeywordsCache` always builds a new one), and a per-card counter bumped whenever `KeywordInstance.createTraits` runs for that card. Trait changes, split faces and removed intrinsics are checked live on every call and bypass the cache | 729 → 680 (−6.7%, 95% −7.5..−5.7) | 100/100 |
| 4 | `fd899b8c9f4` | no cache, goldfish-only (`GameRules.setGoldfish`), fork stopgap (#12297 rewrites these methods) | In goldfish games, `Game.getCardsIn(EnumSet)` and `PlayerCollection.getCardsIn(zone)` append each zone's list whole instead of adding card by card with a uniqueness check (which builds a hash set past 8 cards). Normal games, the Flashback pseudo-zone and zone lists (which may repeat a zone) keep the checked add. `PlayerCollection` reads the switch from its first player; an empty collection takes the checked path | 680 → 600 (−11.7%, 95% −13.0..−10.6) before gating; gated: 682 → 599 (−12.1%, one round, 47 games) | 100/100 |

Each row is an interleaved A/B against the previous commit: A and B side by side, one game thread each, seeds 1-50,
three rounds, first 3 games of each run dropped (141 paired games). Absolute numbers drift with machine load between
rows; the paired ratio does not.

## Upstream

- Commit 1 is the smallest no-cache fix for the allocation and could be a PR. It overlaps #12297's statics cache and
  #11409; upstream would ask for a focused test. The live list it returns is a contract change: any caller that adds or
  removes base statics while iterating would now throw. I found one (`CardFactory`, fixed in the commit), nothing in
  forge-gui, mobile or desktop.
- Commit 2 is a self-contained correctness-neutral fix (`filter(reverse(L)) == reverse(filter(L))`). Good PR candidate.
- Commit 3 is the kind of invalidation-dependent cache upstream distrusts (#11931, #11366); keep fork-only and drop
  when #12297 or a #11409 implementation lands.
- Commit 4 collides with #12297, which rewrites `Game.getCardsIn` and FCollection. Keep as a fork stopgap. It is gated
  on the goldfish switch, so normal Forge games keep the checked add.

## Risks

- Commit 4 (goldfish games only) relies on no card being in two zones at once. Every move path read (`changeZone`, controller change,
  mutate, command-zone effect hand-off) removes before it adds. Not audited: `GameSnapshot` and `GameCopier` restore
  paths. If a card were ever in two zones during a scan, its statics would be counted twice (a turn order reversal
  would cancel itself); the old dedup hid that.
- Commit 3 misses if a keyword's static list changes outside `createTraits`, or `createTraits` runs with a host other
  than the card holding the keyword. Neither happens in current code (grep), but nothing enforces it.
- Results with more than about 100 cards now come back without the hash set; `contains` on them is a linear scan until
  the next `add`. Same as collections built by the `FCollection(FCollection)` copy constructor today.

## What is left

- `getStaticAbilities` is still about 12% from call volume alone: about 4.6 M calls a game, ~9 ns each, mostly the
  emptiness checks on passive opponents' basic lands and our own. Cutting that needs fewer calls: a memo of the cards in
  the source zones that have any statics (the #11301 idea upstream rejected; possible fork-only with the
  `memoReplacementCandidates` invalidation), or per-site iteration that does not copy the zone lists.
- `Game.getCardsIn` still copies ~100 references per scan (6%).
- Trait-changed cards (18% of non-empty calls) are not cached; `CardTraitChanges` carries a removal predicate and
  `LosePerpetualEffect` mutates the table directly, so a version key there is fragile.
- `getTriggers` has the same shape (2.2%); the commit 1 pattern would apply.
- Observation outside this task: `-XX:hashCode=2` gives every identity hash the value 1, so any `HashMap` keyed on an
  object without its own `hashCode` (enums included) degrades to a tree bin. `HashMap$TreeNode.getTreeNode` is 3-4% self.
  Changing it would change iteration orders, so hashes would move.

## Upstream branches

Each commit alone on `upstream-master` (9a6f776b848): `statics/upstream-own-list` (`a36b0659d00`, worktree
`~/src/forge-statics-own`) and `statics/upstream-turn-order` (`014086c3b24`, `~/src/forge-statics-turn`). Commit 1
needed a small port: master has no #11964 empty shortcut, so the fast path there also covers cards with no statics at
all (it returns the empty own list).

Master test set, no flags: 442/442 on both.

Ordinary games: `forge sim -c 900 -q` from each worktree's `forge-gui`, own profile dir per arm, base and both
branches of a table started together (launch order rotated), all three pinned to 4 cores (`taskset`), same seeds.
Commander: 8 tables of 4 random precons from `res/quest/commanderprecons`, one game each, seeds 1000-1007, 3 rounds.
Constructed: 6 random pairs from `res/geneticaidecks`, `-n 10`, seeds 2000-2005, 3 rounds. Tool:
`~/src/gf-statics/simab.py`, output in `~/src/gf-statics/out/sim/{cmd,con}` and `out/sim_{cmd,con}.txt`.

| | Commander wall per game (24 games) | Constructed (180 games) | Results (winner, turn) |
|---|---|---|---|
| own list | 51.75 → 50.91 s, paired −1.9% (95% −2.4..−1.3) | 2.34 → 2.35 s, paired −0.9% (−3.2..+1.5), noise | identical in every run |
| turn order | 51.75 → 50.72 s, paired −1.7% (95% −2.3..−1.1) | 2.34 → 2.34 s, paired −0.2% (−2.4..+2.0), noise | identical in every run |

Focused tests: neither change alters behaviour, so no test can fail on master. A test of the reversed turn order with
several affected players would only guard against a future regression.
