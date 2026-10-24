Superseded by MostCromulent's #12357 (open, under review), which covers this and more. Kept for reference, do not open.

Branch: superseded/ai-hybrid-filter-lands (on top of pr/ai-filter-plan-matches-payment)
Title: AI: pay for filter lands with a hybrid cost (Fetid Heath and the other Shadowmoor filters)

#12149 left out the Shadowmoor/Eventide filter lands, "{W/B}, {T}: Add {W}{W}, {W}{B}, or {B}{B}", because the cost isn't pure generic and the output is Combo. The ten lands still carry AI:RemoveDeck:All and the AI only ever taps them for {C}.

Fix:
- isFilterManaAbility takes two-colour hybrid shards and Combo output (counted as Amount)
- getGenericCostToActivate becomes getManaCostToActivate and counts the whole cost, so a hybrid filter sorts, plans and keeps its reservations like a Signet
- prepayFilterManaAbility picks a source that can pay the hybrid shard and sets its colour through chooseManaAbility, and groupAndOrderToPayShards lists sources for that shard
- setComboManaChoice drops an express choice with more than one mana in it. That's a whole choice left over from an earlier plan, and starting from it made Mystic Gate predict {W}{U}{W} out of Amount 2, so the dry run came up one mana rich
- the ten scripts lose AI:RemoveDeck:All

Tested: two cases added to ComputerUtilManaFilterTest. Swamp + Fetid Heath paying {W}{B} fails on master, and the leftover choice case guards the Combo change. The ai, game and game simulation test sets pass (446). Measured in 100 AI goldfish games with a deck running Fetid Heath, Mystic Gate and Sunken Ruins: the filter ability got used 60 times out of 315 taps (it was 0 before, the lands only made {C}), with no failed casts in 400 games.

Written with Claude Code.
