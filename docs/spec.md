# forge-goldfish: spec

Draft, 2026-10-22. Goldfish Commander decks on Forge's rules engine fast enough to screen card swaps, with
output that Python can analyse, and with AI play the deck's owner can steer.

Forge does the rules and the cards. This project adds a fast headless runner, deterministic seeds, a data
recorder and a policy layer on top of it. Engine changes are a small series of patches to Forge itself, each
one written so it could go upstream.

## Goals

| # | Goal | Measured by |
|---|---|---|
| G1 | One goldfish game in 1 s or less | mean wall time per game on one core, reference deck, to the round horizon |
| G2 | Game data that is easy to parse and learn from | one JSON row per game with the fields the Python prototype records; optional full event log |
| G3 | Many games in parallel across cores | games per second against worker count |
| G4 | Re-run a batch exactly, or with one card swapped (control experiments) | same seed and deck: identical event stream; swapped deck: identical until the swapped card matters |
| G5 | Steer how the AI plays the deck | a policy file (everyone) and policy code (advanced users) |
| G6 | Tools a goldfisher needs beyond the above | section "Other tools" |

Reference deck for every measurement: `multiverse-reforged-wip.json` (Jace, Multiverse Architect) in mtg-tools.

## Non-goals

- A new rules engine or a port of Forge.
- A GUI. Output is files; viewing is a CLI or the mtg-tools reports.
- Interactive opponents in the first version. Opponents are goldfish (see "Other tools" for later models).
- Deck search or optimisation. mtg-tools proposes swaps; this tool measures them.

## Baseline (measured 2026-10-22)

Forge 2.0.15, 8 games in one JVM, Java Flight Recorder, Multiverse Reforged against three goldfish players
(99 Wastes, commander Isamaru, run by Forge's full AI).

- Per game: 3.3 to 15.9 s, mean 6.5 s. Game 8 still took 5 s, so JIT warm-up is not the cause.
- AI choosing plays: 71% of samples. Within that, `Card.getAllPossibleAbilities` (listing every ability of
  every card, redone each time an AI gets priority): 27.5%.
- `GameAction.checkStaticAbilities` (rebuilding all static effects from scratch): 19.5%.
- Garbage collection: 1.35 s of about 52 s. JVM start: about 4 s once per process.
- The goldfish opponents run the full AI at every priority stop.

G1 needs about 6.5 times less work per game. The cost is in Forge's algorithms, not in Java.

## Architecture

```
mtg-tools (Python)                    forge-goldfish (Java, GPL-3.0)              Forge (patched fork)
  deck JSON, swaps, reports  ──CLI──▶   coordinator ──▶ worker JVMs                forge-core / forge-game / forge-ai
  reads games.jsonl         ◀─files──   runner, recorder, policy, opponents  ──▶   + patches P1..P5
```

- **Forge fork.** A branch on a fork of Card-Forge/forge at tag `forge-2.0.15`, carrying patches P1 to P5
  below. Only `forge-core`, `forge-game` and `forge-ai` are used; nothing from the GUI modules.
- **forge-goldfish.** A Maven project that depends on the patched Forge jars. It holds the runner, the
  goldfish opponent, the policy controller, the recorder and the CLI. Forge is GPL-3.0, so anything released
  from this repo that links Forge is GPL-3.0 too.
- **mtg-tools.** Writes the deck file, calls the CLI, reads the output and builds the reports it builds today
  (the `goldfish` skill, `quick.py` statistics).

### Engine patches

Each patch is small, off by default where it changes behaviour, and covered by a test.

- **P1 Deterministic random streams.** Today one global `MyRandom` (a `SecureRandom` unless replaced) serves
  everything, and 72 files call it. The patch gives each game its own random source, split into streams:
  - a library stream per player, used for every library shuffle (opening, mulligans, in-game shuffles,
    "bottom in a random order");
  - a rules stream (coin flips, die rolls, random discard, random choices of effects);
  - an AI stream per player.
  All streams derive from one game seed. With libraries on their own streams, an AI choice that consumes a
  random number can no longer move every later shuffle.
- **P1b Hash order.** Forge's play depends on Java's default object hash codes (prototype, 2026-10-22: one extra
  `hashCode()` before the games changed 8 of 8 games). The hash sets whose iteration reaches a decision become
  insertion-ordered. Until then the runner passes `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`.
- **P2 Inline AI evaluation.** `AiController.chooseSpellAbilityToPlayFromList` starts a new thread for every
  decision and waits on it with a wall-clock timeout (`MATCH_AI_TIMEOUT`, 5 s). A timeout under load changes the
  play, so results depend on machine load. In deterministic mode the evaluation runs on the calling thread with
  no timeout. This also removes a thread start per decision.
- **P3 Deterministic attack evaluation.** `AiAttackController` evaluates forced attackers as parallel tasks
  that add to a shared combat object, so attacker order can vary. In deterministic mode the tasks run in order.
- **P4 Performance.** Driven by the profiler, one change at a time, each with before/after numbers on the
  reference deck. First candidates: cache the playable-ability list within a priority window; rebuild static
  effects only when the board changed. Results must stay identical (the determinism test checks this).
- **P5 Library slot order.** Forge builds the library from a `CardPool`, whose `ConcurrentHashMap` iteration
  order depends on the cards themselves. Swapping one card can reorder the whole pre-shuffle library, so the
  same seed shuffles to a different library. The runner sets the library to the deck file's slot order before
  the first shuffle. This may need no Forge change (`Zone.setCards` is public); it is listed here because G4
  depends on it.

If a game ever needs to run on threads inside one JVM, two more global counters need patching: `Game.maxId`
and `Trigger.maxId`. `Trigger.resetIDs()` runs at every match start, so one game starting would reset another's
trigger ids. The first version avoids this with one game at a time per process.

## Game setup

- Four-player Commander under Forge's rules: 40 life, commander tax, commander damage, the free first
  mulligan.
- Our seat: random per game seed by default, drawn from the rules stream, so going first or last shows up in
  the results as it does at a real table. `--seat 1..4` fixes it. Paired runs keep the seat.
- Opponents: the goldfish controller. Each opponent passes priority at once, plays no lands, casts nothing,
  never blocks and discards at random at cleanup. Its library is 99 basic lands, more than a game at the horizon
  draws. Forge's AI never runs for them.
- Horizon: the game stops at the end of round `R` (default 10, matching the Python prototype), or as soon as
  every opponent has lost. A round is one turn for each player.
- Guard: a game that runs over its wall-clock limit (default 30 s) or throws an exception stops and is recorded
  with its seed and stack trace. The batch continues.

## Determinism and control experiments

**Contract.** These inputs fix a game: Forge build id, hash of the card scripts used, deck file (with slot
order), policy file hash, opponent model, seat rule, horizon and game seed. Running them again produces the
same event stream, byte for byte, alone or under full parallel load. `fg verify` runs a set of seeds twice
and compares hashes of the event streams; it is a required test before any release and after every Forge
update.

**Seeds.** A batch seed expands into game seeds (SplitMix64). Full deck checks use a fresh batch seed from the
operating system; quick swap screens reuse a fixed one so both arms of a comparison see the same games
(the rule in mtg-tools' memory: fresh random shuffles for verification, reused paired seeds for quick
comparisons).

**Swaps.** The deck file is an ordered list of 99 slots plus the commander. A swap writes the new card into the
cut card's slot. With P1 and P5, both arms of a pair get the same library order, with the new card where the
cut card was. The two games stay identical until the card is drawn, revealed or searched, and from then on may
differ for good reasons (the AI weighs its whole hand, so a card can change play without being cast).

**Divergence.** The paired runner records the first event at which the two arms differ, per game. This is
`quick.py`'s "touched" flag made exact: the share of games a card touched, and the effect when it did.

**Replay.** Every batch writes a manifest of all its inputs. `fg replay <manifest>` reruns the batch;
`fg replay <manifest> --swap "Cut=Add"` reruns it with one swap; `fg replay <manifest> --game <id> --trace`
reruns one game with the full event log and decision trace.

**Forced card in hand.** `--in-hand <card>` puts a card into the opening hand of both arms, as
`autopilot.force_into_hand` does today: what the card does when you have it.

## Data output

One directory per batch:

| File | Content |
|---|---|
| `manifest.json` | every input and its hash, versions, batch seed, game seeds, worker count, timing |
| `games.jsonl` | one summary row per game (below) |
| `events/<game>.jsonl.zst` | full event log; off by default for speed, on for `--trace` and replays |
| `decisions/<game>.jsonl.zst` | policy decision trace; only with `--trace` |
| `errors.jsonl` | timeouts and exceptions with seed and stack trace |

### Game row

Field names follow the Python prototype where one exists, so mtg-tools' aggregation keeps working.

- **Identity:** `id`, `seed`, `seat`, `mulligans`, `kept_hand`, `rounds_played`, `status` (`dead`, `horizon`,
  `timeout`, `error`).
- **Result:** `table_dead_round` (round the last opponent lost, or null), `opponents_life_by_round`,
  `damage_by_round`, `life_left_at_horizon`.
- **Per round, our player:** `lands`, `mana_available`, `mana_spent`, `cards_cast` (names), `hand_size`,
  `library_size`, creature and planeswalker counts, total power on board, `missed_land_drop`.
- **Commander:** rounds cast, times cast, tax paid, commander damage dealt.
- **Per card:** round drawn or first seen, round first cast, times cast, rounds stuck in hand while castable,
  zone at end, `touched`.
- **Policy metrics:** counters the policy file defines (for Jace: −3 activations, polymorph hits, extra turns).

### Event log

Recorded from Forge's game event bus (`Game.subscribeToEvents`), which already fires typed events:
`GameEventTurnBegan`, `GameEventTurnPhase`, `GameEventLandPlayed`, `GameEventSpellAbilityCast`,
`GameEventSpellResolved`, `GameEventCardChangeZone`, `GameEventTokenCreated`, `GameEventPlayerDamaged`,
`GameEventPlayerLivesChanged`, `GameEventCardCounters`, `GameEventAttackersDeclared`, `GameEventMulligan`,
`GameEventShuffle`, `GameEventGameOutcome` and others. Each record carries a sequence number, round, turn,
phase, active player and the event's own fields (card names, players, amounts, zones).

### Reports (mtg-tools)

The tables the `goldfish` skill prints today, built from `games.jsonl`: table dead by round N, mean and
**earliest round win** (always a column), cast rates, missed land drops, mana by round, per-card use. Paired
screens use `quick.py`'s statistics and stopping rule (clear at 2.5 standard errors, "small" when the 95%
interval sits within 0.1 rounds of zero).

## Speed plan (G1)

`fg profile` runs a fixed set of seeds under Java Flight Recorder and prints the top callers, as in the
baseline. Each step below is measured on the reference deck before the next one starts. Expected effects are
estimates until measured.

1. Goldfish controller for opponents: removes three of the four AIs.
2. P2: no thread start and no timeout per decision.
3. Horizon stop: games past round 10 stop (one of the 8 baseline games ran 45 turns, 15.9 s).
4. Lazy card loading (`CardStorageReader` already supports it): load the deck's cards and the tokens they make,
   not all 34,010 scripts. Cuts start time and memory per worker.
5. P4 engine patches, one at a time.

If the mean is still above 1 s after step 5, report the numbers and decide then. Batch throughput (G3) does not
depend on reaching 1 s.

## Parallel batches (G3)

- Workers are separate JVM processes, each running games one after another from a seed queue. This avoids
  Forge's global state (P1's random source, the id counters) entirely.
- The coordinator hands out seeds, collects rows and merges them in seed order, so output does not depend on
  which worker ran which game.
- Default worker count: the lower of (cores − 2) and (free memory ÷ measured memory per worker). This machine:
  32 cores, 61 GB.
- Paired screens run both arms of a seed on the same worker and stream rows back, so the coordinator can stop
  a candidate early (the `quick.py` rule) without waiting for the batch.
- Target: throughput at least 0.8 × workers × single-worker rate.

## Deck policy (G5)

### Hook points

The policy is asked at the decisions that matter for a deck's game plan. Forge passes in its own legal options
(the rules engine has already filtered them); the policy returns one of: pick an option, rank options, veto
options, or "no opinion", in which case Forge's AI decides as usual.

- mulligan: keep or not, and which cards go to the bottom
- next play in each priority window: a land, a spell, an ability, or pass (holding a card is a pass)
- targets and choices for our own spells and abilities (for example the −3's target)
- attackers
- later: modes, X values, optional costs

The controller is a subclass of Forge's `PlayerControllerAi` (public, not final; `mulliganKeepHand`,
`chooseSpellAbilityToPlay`, `chooseTargetsFor`, `declareAttackers`, `chooseSingleEntityForEffect` and
`chooseCardsForEffect` are public), created by our own lobby player in place of `LobbyPlayerAi`. No Forge patch
is needed for the hooks.

### Layer 1: policy file (everyone)

A YAML file next to the deck. Rules are read top to bottom; the first rule whose condition holds and which
matches a legal play picks it.

```yaml
deck: multiverse-reforged
roles:                      # can be filled from the deck's mtg-tools tags
  ramp: [Sol Ring, Arcane Signet, Talisman of Progress]
  fodder: [Lingering Souls, Grand Crescendo, Skrelv's Hive]
  polymorph: [Identity Echo, Proteus Staff]
mulligan:
  keep_if: {lands: [3, 5], any_role: [ramp]}
plays:
  - play: role:ramp
    when: mana_available < 5
  - play: role:fodder
    when: count(battlefield, creature token) == 0
  - activate: "Jace, Multiverse Architect: -3"
  - play: role:polymorph
  - play: "Jace, Multiverse Architect"
    when: count(battlefield, creature token) >= 1
hold:
  - card: "Jace, Multiverse Architect"
    unless: count(battlefield, creature token) >= 1
targets:
  "Jace, Multiverse Architect: -3": [creature token, lowest value]
card_hints:                 # Forge's own AI hints, without editing cardsfolder.zip
  "Jace, Multiverse Architect": {AILogic: Polymorph}
metrics:
  jace_minus: count(activate "Jace, Multiverse Architect: -3")
```

- The condition vocabulary is fixed and small: counts of cards by name, role or type in a zone, mana available,
  life totals, round, phase, whose turn. A policy that needs more moves to layer 2. A config format that keeps
  growing conditions turns into a poor programming language.
- `card_hints` sets Forge's existing per-card AI parameters (`AILogic`, `AIPreference`, `PlayMain1` and the
  other SVars the AI reads) for this run only.
- Forge's AI profiles (`res/ai/*.ai`, the `AiProps` settings such as aggression) can be chosen per deck.

### Layer 2: policy code (advanced users)

The same hooks, answered by code.

- **External process over JSON lines (recommended).** The worker sends each decision (options plus the game
  state the policy asked for) to a user process and reads the answer. Python first, with a small SDK in
  mtg-tools. Users never touch Forge's Java, and Forge upgrades do not break their code as long as the protocol
  holds. Cost: our player makes roughly 200 decisions a game (estimate); at 0.2 ms a round trip that is 40 ms,
  inside the 1 s budget if state is sent on request, not as a full snapshot every time.
- **Java class (option).** Fastest, but ties users to the JVM and to Forge's internal classes.

The policy file is itself one implementation of the same interface, so both layers go through one code path.

### Policy debugging

`--trace` writes, for each decision: the legal options, the rule or code that answered, the choice, and what
Forge's AI would have chosen. This answers "why did it play that" without reading Forge source.

## Other tools (G6)

1. **Preflight.** Before a batch: every card exists in Forge; cards Forge marks `AI:RemoveDeck:All` (Forge's
   AI does not know how to play them) are listed. In the Jace list today that is ten cards: Azorius, Dimir,
   Izzet and Rakdos Signet; Fetid Heath, Mystic Gate, Sunken Ruins; Proteus Staff, Mass Polymorph, Synthetic
   Destiny. Each one needs a policy rule or the results understate it. A post-batch check flags any card never
   cast while castable.
2. **Game viewer.** `fg show <batch> <game>` prints one game turn by turn from its event log. `fg find <batch>
   <query>` lists games by condition (fastest wins, games where the commander was never cast, games where a card
   sat in hand).
3. **Swap screen and full check.** The paired screen with early stopping, and the 1,000-game check on fresh
   seeds before a swap is recommended.
4. **Card report.** Per card: drawn, cast, stuck in hand, and its value from leave-one-out screens.
5. **Baselines and diff.** Results stored per deck version; `fg diff <batchA> <batchB>` prints the change in
   every reported number with its standard error.
6. **Scenario start.** Begin a game from a given board (Forge's `GameState` format, the one its puzzles use) to
   test one line or one policy rule in seconds.
7. **Opponent models (later).** Goldfish plus: a board wipe in round N, removal aimed at the commander, a
   blocker every turn. These test resilience, the deck's third priority after consistency and a victory state.
8. **Version pinning.** The manifest records the Forge build and a hash of the card scripts used, so results
   from before and after a Forge update are never mixed without notice.

## Testing

- Determinism: `fg verify` on 50 seeds, alone and under full load.
- A golden game: one seed's event log stored; a change to it fails the test until reviewed.
- Policy rules: scenario start, then assert the choice.
- Performance: median seconds per game on 50 fixed seeds, recorded per commit.
- Swap pairing: a swap of a card that is never drawn by round R in a seed gives identical games.

## Risks

- **1 s per game is unproven.** Steps 1 to 3 of the speed plan are cheap; step 5 means engine changes whose gain
  is unknown until measured.
- **Forge's AI on unusual cards.** Ten cards in the reference deck are marked as cards the AI cannot play.
  Policy rules cover them, but each needs writing.
- **Determinism holes** beyond those found (P1 to P3). `fg verify` finds them; each becomes a patch.
- **Upstream drift.** Forge changes often. A short patch series rebases easily; upstreaming P1 to P4 removes the
  cost.

## Decisions open

1. Policy code language: JSON over stdio with a Python SDK (recommended), or a Java plugin.
2. Fork hosting: a GitHub fork of Card-Forge/forge with the patch branch (recommended), or patch files kept in
   this repo and applied at build time.
3. Seat: random per seed (recommended) or always first.
4. Coordinator and reports: Python in mtg-tools (recommended; reuses `quick.py`), or Java in this repo.
5. Python prototype: keep the generic mana autopilot (`auto`), retire the per-deck policies (`jace.py`,
   `grimgrin.py`, `yennett.py`) once each deck has a policy file.
