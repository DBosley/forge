Branch: pr/ai-filter-chain-on-12357, one commit (95c4bfdf382) on top of MostCromulent's #12357 (bc3cf955526). Two ways to open it:
- now, as a PR into MostCromulent's branch, so he can fold it into #12357: https://github.com/MostCromulent/forge/compare/shadowmoor-filter-lands...DBosley:forge:pr/ai-filter-chain-on-12357
- after #12357 merges, against Card-Forge master (rebase onto master first, or the PR carries #12357's four commits)
The comment in 12357-comment.md covers both. If #12357 stalls, the chain commit needs porting to master, none of it exists there yet.

Title: AI: let one filter pay for another

Follow-up to #12357.

Paying one filter's cost with another filter's mana is a legal line (Plains into Azorius Signet, one of its two mana into Dimir Signet, Divination with the rest), and the dry run already plans it, since it adds a filter's own cost back onto the cost for the other sources to cover. prepayFilterManaAbility only fed a filter from ordinary sources though, so the payment ran dry halfway with "Didn't find what to pay for {1}". Sol Ring with two Signets did the same, and so did Plains with two Mystic Gates for {U}{U}{U}, the case listed in #12357.

Fix:
- when the sources left after a land look short of the rest of the cost, the filter's cost comes from another filter instead, fed the same way first
- that search only runs when something left that costs no mana could pay some filter's cost. Without the check a Plains and six Mystic Gates spent half of every canPayManaCost call on dead ends
- whatever else that filter made goes to the cost right away, so the next filter's own cost can't spend a color the cost still needs
- planning goes through prepayFilterManaAbility too and sets the chosen sources aside instead of tapping them, so the plan and the payment pick the same sources. A board where the payment would feed a filter from the wrong land now plans as unpayable instead of failing halfway
- the card being paid for never feeds a filter, same as in chooseManaAbility
- setComboManaChoice drops an express choice with more than one mana in it. That's a whole choice left from an earlier plan, and starting from it made Mystic Gate predict {W}{U}{W} out of Amount 2

Measured on 1000 seeded AI goldfish games with a Signet and filter land heavy Commander deck. On #12357 there were 100 failed casts and the table was dead by round 10 in 77.7% of games. With this it's 0 failed casts and 80.7%.

Speed: on those games the time per game matches #12357 within noise and the chain code is under 0.3% of CPU. In ordinary sim games (8 tables of 4 random Commander precons, 3 rounds) 6 tables played the same game on both builds and took the same time, -0.3% (95% -1.1% to +0.5%), the other 2 played differently. The worst case is a board stacked with filters. One canPayManaCost call with 2 Plains and 10 Signets goes from 142-184 µs to 160-262 µs, with a Plains and 6 Mystic Gates from 106-136 µs to 100-186 µs. It grows with the number of filters but doesn't blow up, a filter that fails isn't tried again in the same chain.

Tested: three cases added to ComputerUtilManaFilterTest, all fail on #12357. The ai, game and game simulation test sets pass (454).

Written with Claude Code.
