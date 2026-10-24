Branch: pr/static-list-without-copy (1 commit on master)
Before opening: this overlaps MostCromulent's #11964 (skip statics and triggers when every source is empty) and the statics cache in kevlahnota's #12297. Probably wait for #11964, or mention it there first. On master it also covers cards with no statics at all, which #11964 does too.

Title: Return a card's own static list when nothing adds to it

getStaticAbilities copies the state's list and runs the full trait walk on every call, and the static ability scans call it for every card in play, graveyard, exile and command zone, many times per priority.

- no trait changes, split faces or removed intrinsics: return the state's list as is, or a copy plus the keyword statics when a keyword carries one (Flying, Vigilance, Protection etc)
- CardFactory removes CDA statics from that list while looping, so it loops over a copy now. I didn't find any other caller that changes the list it gets back

Sim mode, master vs this branch, same seeds, interleaved, pinned to 4 cores:
- 4 player Commander precons: -1.9% wall per game (95% -2.4 to -1.3, 24 games), same winner and turn count in every game
- constructed pairs: within noise (-0.9%, 180 games)

No new test, nothing changes so nothing fails on master. 442/442 pass.

Written with Claude Code.
