Branch: pr/ai-filter-chain-on-12357, 2 commits on top of MostCromulent's #12357 (bc3cf955526). Open after #12357 merges (rebase onto master first), or offer it to MostCromulent to fold in; the comment in 12357-comment.md covers both. If #12357 stalls, the chain commit needs porting to master, none of it exists there yet.

Title: AI: let one filter pay for another

Follow-up to #12357.

Paying one filter's cost with another filter's mana is a legal line (Plains into Azorius Signet, one of its two mana into Dimir Signet, Divination with the rest), and the dry run already plans it, since it adds a filter's own cost back onto the cost for the other sources to cover. prepayFilterManaAbility only fed a filter from ordinary sources though, so the payment ran dry halfway with "Didn't find what to pay for {1}". Sol Ring with two Signets did the same, and so did Plains with two Mystic Gates for {U}{U}{U}, the case listed in #12357.

Fix:
- when the sources left after a land look short of the rest of the cost, the filter's cost comes from another filter instead, fed the same way first
- whatever else that filter made goes to the cost right away, so the next filter's own cost can't spend a color the cost still needs
- planning goes through prepayFilterManaAbility too and sets the chosen sources aside instead of tapping them, so the plan and the payment pick the same sources. A board where the payment would feed a filter from the wrong land now plans as unpayable instead of failing halfway
- the card being paid for never feeds a filter, same as in chooseManaAbility
- second commit: setComboManaChoice drops an express choice with more than one mana in it. That's a whole choice left from an earlier plan, and starting from it made Mystic Gate predict {W}{U}{W} out of Amount 2

Measured on 1000 seeded AI goldfish games with a Signet and filter land heavy Commander deck. On #12357 there were 100 failed casts and the table was dead by round 10 in 77.7% of games. With this it's 0 failed casts and 81.0%.

Tested: four cases added to ComputerUtilManaFilterTest, all fail on #12357. The ai, game and game simulation test sets pass (455).

Written with Claude Code.
