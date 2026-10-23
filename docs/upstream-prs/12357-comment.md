Comment for #12357 (MostCromulent, AI: use Shadowmoor filter lands). Post before opening the follow-up.

Nice to see this one, I ran into the same lands from a goldfish harness I've been building on a fork (lots of AI vs passive opponent games with a Signet and filter land heavy Commander deck). Two things showed up that are still there on this branch, both with a repro test:

- the "one filter can't pay for another" case you listed costs real games, and it's a legal line (Plains into Azorius Signet, one of its two mana into Dimir Signet, Divination with the rest). The dry run already plans it that way, the payment can't follow, so the cast fails halfway with "Didn't find what to pay for {1}". Sol Ring with two Signets does it too. I have prepayFilterManaAbility feeding one filter from another when the lands left would come up short, and planning going through the same code so the plan and the payment can't disagree
- setComboManaChoice starts from whatever express choice is already on the ability. If an earlier plan left a full "W U" on Mystic Gate, the next plan builds on it and predicts {W}{U}{W} out of Amount 2, so the dry run comes up one mana rich (Grave Titan with five sources that make five mana reads as payable)

Over 1000 seeded goldfish games of that deck, this branch has 100 failed casts and kills the table by its 10th turn in 77.7% of games. With both fixes it's 0 failed casts and 81.0% (paired by seed, McNemar p < 0.0001).

I have them as two commits on top of this branch. Happy to open a PR after this merges, or you can fold them in here if that's easier.

Written with Claude Code.
