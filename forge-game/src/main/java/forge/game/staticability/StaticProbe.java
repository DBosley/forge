package forge.game.staticability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class StaticProbe {
    public static final boolean ENABLED = Boolean.getBoolean("forge.experiment.staticProbe");
    private static final Map<String, LongAdder[]> STATS = new ConcurrentHashMap<>();

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> STATS.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[1].sum(), a.getValue()[1].sum()))
                    .limit(25)
                    .forEach(e -> System.err.printf("PROBE calls=%d candidates=%d %s%n",
                            e.getValue()[0].sum(), e.getValue()[1].sum(), e.getKey()))));
        }
    }

    private StaticProbe() {
    }

    static void record(String key, int candidates) {
        LongAdder[] s = STATS.computeIfAbsent(key, k -> new LongAdder[] {new LongAdder(), new LongAdder()});
        s[0].increment();
        s[1].add(candidates);
    }
}
