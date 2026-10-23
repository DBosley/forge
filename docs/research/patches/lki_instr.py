import sys
p = sys.argv[1] + '/forge-game/src/main/java/forge/game/Game.java'
s = open(p).read()
old = '''    public void copyLastState() {
        lastStateBattlefield.clear();
        lastStateGraveyard.clear();
        Map<Integer, Card> cachedMap = Maps.newHashMap();
        for (final Player p : getPlayers()) {
            lastStateBattlefield.addAll(p.getZone(ZoneType.Battlefield).getLKICopy(cachedMap));
            lastStateGraveyard.addAll(p.getZone(ZoneType.Graveyard).getLKICopy(cachedMap));
        }
    }'''
new = '''    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> LKI_STATS = new java.util.concurrent.ConcurrentHashMap<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> new java.util.TreeMap<>(LKI_STATS).forEach((k, v) -> System.err.println("LKISTAT " + k + " " + v))));
    }
    private static void lkiStat(String k, long n) { LKI_STATS.computeIfAbsent(k, x -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(n); }
    private long lkiEv = -1, lkiSt = -1;
    private boolean lkiSettled = false;
    public long fullRebuildEvents = -1, fullRebuildStatics = -1;
    public void copyLastState() {
        boolean settled = fullRebuildEvents == stateEventCount && fullRebuildStatics == action.getStaticsVersion();
        String caller = StackWalker.getInstance().walk(st -> st.skip(1).findFirst().map(f -> f.getClassName().replaceAll(".*\\\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?"));
        boolean noEv = stateEventCount == lkiEv;
        boolean noSt = action.getStaticsVersion() == lkiSt;
        lkiStat("calls", 1);
        lkiStat("calls." + caller, 1);
        if (noEv) { lkiStat("noEvents", 1); lkiStat("noEvents." + caller, 1); }
        if (noEv && noSt) { lkiStat("noEventsNoStatics", 1); lkiStat("noEventsNoStatics." + caller, 1); }
        boolean red = noEv && (noSt || (lkiSettled && settled));
        if (red) { lkiStat("redundant", 1); lkiStat("redundant." + caller, 1); }
        lastStateBattlefield.clear();
        lastStateGraveyard.clear();
        Map<Integer, Card> cachedMap = Maps.newHashMap();
        for (final Player p : getPlayers()) {
            lastStateBattlefield.addAll(p.getZone(ZoneType.Battlefield).getLKICopy(cachedMap));
            lastStateGraveyard.addAll(p.getZone(ZoneType.Graveyard).getLKICopy(cachedMap));
        }
        lkiStat("cards", lastStateBattlefield.size() + lastStateGraveyard.size());
        if (red) lkiStat("cardsRedundant", lastStateBattlefield.size() + lastStateGraveyard.size());
        lkiSettled = settled || (noSt && lkiSettled);
        lkiEv = stateEventCount;
        lkiSt = action.getStaticsVersion();
    }'''
assert old in s
s = s.replace(old, new)
old2 = '''    public CardCollectionView copyLastState(ZoneType type) {
        CardCollection result = new CardCollection();'''
new2 = '''    public CardCollectionView copyLastState(ZoneType type) {
        lkiStat("zoneCopy." + type + "." + StackWalker.getInstance().walk(st -> st.skip(2).findFirst().map(f -> f.getClassName().replaceAll(".*\\\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?")), 1);
        CardCollection result = new CardCollection();'''
assert old2 in s
s = s.replace(old2, new2)
open(p, 'w').write(s)

p = sys.argv[1] + '/forge-game/src/main/java/forge/game/GameAction.java'
s = open(p).read()
old = """        staticsVersion++;
        if (skipPassiveHidden && preList.isEmpty()"""
new = """        staticsVersion++;
        if (preList.isEmpty()) { game.fullRebuildEvents = game.getStateEventCount(); game.fullRebuildStatics = staticsVersion; }
        if (skipPassiveHidden && preList.isEmpty()"""
assert old in s
s = s.replace(old, new)
open(p, 'w').write(s)
