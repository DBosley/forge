Branch: pr/launcher-compile-huge-methods (1 commit on master)
Before opening: maybe post the numbers on #11933 first, since that PR works around the same 8000 byte limit from the code side.

Title: Compile huge methods in the desktop and mobile-dev launchers

HotSpot won't JIT compile a method bigger than 8000 bytes of bytecode, those stay in the interpreter for the whole run. CardProperty.cardHasProperty is about 14 KB and AbilityUtils.xCount about 10.7 KB, and cardHasProperty is near the top of every profile I've taken (static ability rebuilds call it for every card on the battlefield). #11933 gets around it by shortcutting the common case before the big method, this just lets the JIT compile it.

- adds -XX:-DontCompileHugeMethods to mandatory.java.args in forge-gui-desktop and forge-gui-mobile-dev, which fill in forge.sh, forge.command, forge.cmd and the launch4j exe
- it's a product flag, no unlock needed. OpenJ9 ignores unknown -XX options by default, so it won't stop the game starting there

Numbers, 2.0.15 release jar, JDK 26, sim mode, same seed with and without the flag:
- 4 player Commander precons (Prismari Performance, Entropic Uprising, Heavenly Inferno, Mind Flayarrrs), seeds 11 to 16: 400 s total without, 333 s with (-17%). Same winner and same turn count on every seed
- 2 player constructed (Slivers vs Burn from geneticaidecks), 10 games: 7.7 s without, 7.0 s with (-10%), same turn counts
- the machine was busy so single games are noisy (2 of the 6 Commander seeds came out slower), the totals are the useful part

Written with Claude Code.
