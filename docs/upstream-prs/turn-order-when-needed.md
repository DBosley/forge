Branch: pr/turn-order-when-needed (1 commit on master)

Title: Only look up the turn order when a static affects several players

getAffectedPlayers puts the players in turn order for every continuous static with an Affected param, and getting the turn order scans every static on every card for a reversal effect. Most of those statics hit one player or none, so the order can't matter.

- filter the players in seat order, reverse only when 2 or more match and the turn order is actually reversed, same list as before either way

Sim mode, master vs this branch, same seeds, interleaved, pinned to 4 cores:
- 4 player Commander precons: -1.7% wall per game (95% -2.3 to -1.1, 24 games), same winner and turn count in every game
- constructed pairs from geneticaidecks: no change outside noise (-0.2%, 180 games)

No new test, nothing changes so nothing fails on master. 442/442 pass.

Written with Claude Code.
