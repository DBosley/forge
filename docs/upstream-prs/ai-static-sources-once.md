Branch: pr/ai-static-sources-once (2 commits on master, squash is fine)

Title: AI: collect static ability sources once per ability list

When the AI lists what it can play, it asks for the alternative costs of every ability of every card, and each ask rebuilt the list of every card in the static source zones, twice for a spell (alternative cost statics, then the statics that change abilities on the stack). In a long Commander game that's a lot of copies of the same list inside one pass where nothing moves.

- getSpellAbilities and getOriginalAndAltCostAbilities build the list once and pass it down through getAllPossibleAbilities and getAlternativeCosts. Callers that don't pass one get the old behaviour, one list per call
- nothing in that enumeration moves a card between zones (the lki checks work on copies), so every ask sees the same list it would have built
- second commit: hasStaticAbilityAffectingZone checks mode, layer and AffectedZone before checkConditions, same answer since checkConditions only reads state

Numbers, sim mode, master vs this branch, same seeds, interleaved, 3 rounds:
- 4 player Commander, 8 tables of 4 precons from commanderprecons: -3.0% wall per game (95% -3.8 to -2.3)
- constructed, 10 pairs from geneticaidecks, 3 games each: -1.7% (95% -2.5 to -1.0)
- game logs identical between the two builds in every game (one commander table had master itself declare attackers in a different order on one of its runs, probably the forced attacker thread pool, not this)
- on my goldfish fork it's -5.6% cpu per game and the same 1000/1000 games

No new test, it doesn't change a result so there's nothing that fails on master. GrantedCastTest, CastFromOwnZoneTest and BeamMeUpTest go through getAllPossibleAbilities into getAlternativeCosts, 442/442 pass.

The nullable list parameter on the public methods is the part I'm least sure you'll like, happy to change the shape.

Written with Claude Code.
