# Deck policies

A policy is a YAML file that steers the deck's AI in the goldfish runner (`--policy <file>`). `goldfish.py forge`,
`swap` and `deck_check.py --forge` in mtg-tools pick up `prototype/policies/<deck slug>.yaml` on their own.
Everything a policy doesn't cover is left to Forge's AI. Examples: `multiverse-reforged.yaml` (Jace, the
token-and-polymorph engine) and `odds-and-ends.yaml` (Yennett, top-of-library control).

A policy that names a card the list doesn't play gets a warning, and the rules for that card never fire, so one
policy covers a deck and its in-progress list.

## Keys

| Key | What it does |
|---|---|
| `mulligan: {keep_if: <condition>}` | keeps the opening 7 (and the first mulligan) when the condition holds; smaller hands go to Forge's AI |
| `roles: {name: [cards]}` | named card lists, used as `role:name` in filters |
| `plays: [rules]` | tried in order whenever the deck has priority with an empty stack; the first rule with a playable match is played (below) |
| `hold: [{card: <filter>, unless: <condition>}]` | holds those spells back unless the condition holds; `activate:` instead of `card:` holds activations |
| `library_top: [filters]` | cards wanted on top of the library, for an attack trigger that reveals it (Yennett). Steers every reorder of the top (Sensei's Divining Top, Ponder, Brainstorm), scry, surveil, and a tutor to the top: the wanted cards go on top, the earliest matching filter then the highest mana value first, and the rest keep the AI's choice. When a draw comes before the next attack, the top card goes to one the policy doesn't want, so the wanted one is the card revealed |
| `attack` | `forge` (default), `focus_lowest` (each attacker at the defender with the least life left after the attackers already sent), `spread`, `threat` |
| `hand_disposal: worst_first` | discards and put-backs from hand take a land when there are 2+ in hand or 7+ out, else the highest mana value |
| `copy_choices: {card: [filters]}` | what a clone copies, best first |
| `trigger_targets: {card: [filters]}` | targets for that card's triggers, best first |
| `charm_modes: {card: [mode text]}` | modes to pick, by text |
| `pay_life_for_untapped_lands: true` | shocklands enter untapped |
| `allow_ai_removed: true` | lets the AI play cards Forge marks as not for AI decks |
| `opponent_turn_stops: [phases]` | where the deck gets priority on the opponents' turns (`--opponent-stops` wins over it) |

## Play rules

```yaml
- play: role:ramp            # or a card name or a filter; activate: for abilities
  when: sources < 5          # condition, optional
  force: true                # play it whenever it's legal and payable, without asking the AI
  target: [creature token]   # targets, best first; a targeted rule also skips the AI's judgement
  text: Look at the top      # only abilities whose description contains this
- activate: "Jace, Multiverse Architect: -3"   # a loyalty ability by its cost
  max_loyalty: 2             # and min_loyalty
```

A rule without `force` or `target` only suggests: the AI's own check decides. A forced or targeted rule fires every
time it's legal, so give it a `when` that turns off: an ability that costs {0} or {1} forced in the main phase
repeats until the mana runs out, ahead of every spell after it.

## Filters and conditions

A filter is a card name, or words that all have to match: `creature planeswalker land artifact enchantment instant
sorcery token nontoken legendary nonland odd even used card role:<name>` (`odd`/`even` is the mana value, `used`
a planeswalker that activated this turn).

A condition is terms joined by `and`: `count(<filter>)` (battlefield) or `hand(<filter>)`, `sources`, `mana`,
`round`, `life`, each with `>= <= == != > <` and a number, and `main1` / `main2`.
