Comment for #12357 (MostCromulent, AI: use Shadowmoor filter lands). Post before opening the follow-up.

Nice to see this one, I ran into the same lands from a goldfish harness I've been building on a fork (lots of AI vs passive opponent games with a Signet and filter land heavy Commander deck). Two things showed up that are still there on this branch, both with a repro test:

- the known "one filter can't pay for another" case costs real games. The dry run adds the filter's cost back onto the cost being paid, so it plans to feed one filter from another filter's output, then the payment can't and the cast fails halfway ("Didn't find what to pay for {1}"). Plains + Azorius Signet + Dimir Signet with Divination in hand does it. The fix routes the dry run through prepayFilterManaAbility with the chosen sources set aside
- setComboManaChoice starts from whatever express choice is already on the ability. If an earlier plan left a full "W U" on Mystic Gate, the next plan builds on it and predicts {W}{U}{W} out of Amount 2, so the dry run comes up one mana rich (Grave Titan with five sources that make five mana reads as payable)

Over 1000 seeded goldfish games of a Commander deck with a lot of Signets and Talismans, this branch has 100 failed casts and the deck kills the table by its 10th turn a bit less often (77.7% vs 80.1%, paired by seed, McNemar p=0.0015). With the follow-up it's 0 failed casts.

I have both as one follow-up commit on top of this branch (plus skipping the card being activated as a feeder, which the new planning path needs for Kher Keep style abilities). Happy to open it after this merges, or you can fold it in here if that's easier.

Written with Claude Code.
