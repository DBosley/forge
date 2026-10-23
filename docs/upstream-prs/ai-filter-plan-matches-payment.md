Branches, pick one depending on #12357:
- pr/ai-filter-plan-matches-payment-on-12357: 1 commit on top of MostCromulent's #12357 (open). Use this if #12357 merges first, which looks likely. It also carries the leftover Combo choice fix, which only matters once Combo filters are allowed.
- pr/ai-filter-plan-matches-payment: 1 commit on master, Signets only. Use this if #12357 stalls.
Suggested order: post the comment in 12357-comment.md first, then open whichever branch fits once #12357 settles.

Title: AI: plan filter payments the way they get paid

Since #12149 the dry run (canPayManaCost) counts a Signet's own {1} as generic mana added back to the cost, so a plan can pay one filter's input out of another filter's output. The real payment can't do that. prepayFilterManaAbility only floats ordinary sources for a filter's input. So the plan says yes, the cast starts, and the payment fails halfway with "Didn't find what to pay for {1}". Example: Plains + Azorius Signet + Dimir Signet, the AI thinks it can cast Divination and can't.

Fix:
- planning also goes through prepayFilterManaAbility and sets the chosen sources aside instead of tapping them, so the plan and the payment pick the same sources
- the generic mana the dry run added back to the cost is gone, the chosen sources pay for it
- the card being paid for is never picked to feed a filter (Kher Keep's {1}{R}, {T} would otherwise plan on tapping the Keep for a Signet too)

Measured: in AI goldfish games with a Signet heavy Commander deck (a 2.0.15 fork with #12149 and #12179 cherry-picked), failed casts went from 31 in 200 games to 0 in 400.

Tested: two cases added to ComputerUtilManaFilterTest. The plan-matches-payment one fails on master, the Kher Keep one guards the new planning path. The ai, game and game simulation test sets pass (444).

Written with Claude Code.
