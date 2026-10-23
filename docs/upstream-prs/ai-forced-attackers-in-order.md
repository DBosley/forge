Branch: pr/ai-forced-attackers-in-order (1 commit on master)
Before opening: mention it to kevlahnota on Discord first, the pool came with his timeout work (#11895, #12265) and he's the one who'd push back.

Title: AI: check forced attackers in order instead of on a thread pool

declareAttackers builds a new fixed thread pool the size of the cpu count every time it runs (that includes every getPredictedCombat call), and forced attackers get added in whatever order the threads finish. So the same game can declare attackers in a different order run to run, and #11161 had to add the synchronized block after it dropped attackers.

This runs the same withDeadline tasks one after another on the calling thread instead
- the AITimeoutTracker deadline still applies, arm/disarm nest fine on one thread
- no executor, so combatClosed and the synchronized block go away
- an exception in a check gets printed instead of sitting in a Future nobody reads

Timing, sim mode, a master jar against this branch on the same seeds, run side by side (busy machine, so only big differences would show):
- 2 player, Elves vs Goblins from geneticaidecks, 4 seeds of 10 games: 110.1 s vs 108.2 s, same turn counts in every game
- 4 player Commander, 4 stock precons and 4 token precons, 4 seeds each: the 4 games that played out the same took the same time (within 1 s each). The other 4 ended on different turns so their times don't compare

So it's not a speedup, it's one less thread pool per combat and the same attack order every run.

I've been running the same change on a fork for a goldfish harness (lots of seeded AI games that have to replay exactly). The order only matters when a creature is actually forced to attack, so most games come out the same either way, but the ones with MustAttack or attack requirements can replay differently.

Written with Claude Code.
