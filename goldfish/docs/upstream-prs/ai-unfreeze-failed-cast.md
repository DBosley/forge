Branch: pr/ai-unfreeze-failed-cast (1 commit on master)
Title: AI: unfreeze the stack when a cast fails to pay

ComputerUtil.handlePlayingSpellAbility freezes the stack before it pays and never unfreezes it when the payment fails (the "AI failed to play" path, right under the FIXME). Everything the AI plays after that goes onto the frozen stack, and MagicStack.add returns before addAbilityActivatedThisTurn for a frozen stack. So the activation counts don't move, and a planeswalker can activate the same loyalty ability over and over in one turn.

What we saw: after a failed cast, Jace, Multiverse Architect's +1 was activated about a thousand times in one turn, until PhaseHandler's 999 loop guard stopped it, and the draws decked the table. A failed cast is supposed to be rare, but filter mana payments can still produce one on master (see the "plan filter payments" PR: Plains + Azorius Signet + Dimir Signet with Divination in hand fails halfway).

Fix: unfreeze the same way PlaySpellAbility does for a human after a failed payment, unless the stack was already frozen before this cast.

Tested: the ai, game and game simulation test sets pass (442). No unit test, the repro needs a payment that fails after the dry run said yes. Happy to add one on top of the filter repro if you want it.

Written with Claude Code.
