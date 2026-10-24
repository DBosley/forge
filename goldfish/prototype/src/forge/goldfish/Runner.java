package forge.goldfish;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.Gson;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.trigger.Trigger;
import forge.goldfish.policy.Policy;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

public final class Runner {
    private static final String DECK_PLAYER = "Deck";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String deckPath = required(opts, "deck");
        String fishPath = required(opts, "fish");
        int games = Integer.parseInt(opts.getOrDefault("games", "10"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "1"));
        int horizon = Integer.parseInt(opts.getOrDefault("horizon", "10"));
        int timeoutSeconds = Integer.parseInt(opts.getOrDefault("timeout", "120"));
        boolean passive = opts.getOrDefault("opponents", "passive").equals("passive");
        boolean skipOpponentTurns = opts.getOrDefault("opponent-turns", "play").equals("skip");
        Map<Long, List<String>> libraryOrders = new HashMap<>();
        if (opts.containsKey("library-file")) {
            List<Map<String, Object>> entries = new Gson().fromJson(java.nio.file.Files.readString(Path.of(opts.get("library-file"))),
                    new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>() { }.getType());
            for (Map<String, Object> e : entries) {
                libraryOrders.put(((Number) e.get("seed")).longValue(), (List<String>) e.get("order"));
            }
        }
        Map<String, String> standIns = opts.containsKey("paired") ? parseStandIns(opts.get("paired")) : null;
        String outPath = required(opts, "out");

        System.setProperty("java.awt.headless", "true");
        long start = System.nanoTime();
        GuiBase.setInterface(new GuiDesktop());
        for (String dir : ForgeConstants.PROFILE_DIRS) {
            java.nio.file.Files.createDirectories(Path.of(dir));
        }
        FModel.initialize(null, null);
        Policy policy = opts.containsKey("policy") ? Policy.load(Path.of(opts.get("policy"))) : null;
        Deck ours = DeckSerializer.fromFile(new File(deckPath));
        Deck fish = DeckSerializer.fromFile(new File(fishPath));
        if (policy != null) {
            Set<String> inDeck = new HashSet<>();
            ours.getAllCardsInASinglePool().forEach(e -> inDeck.add(e.getKey().getName()));
            List<String> missing = policy.cardNames().stream().filter(n -> !inDeck.contains(n)).toList();
            if (!missing.isEmpty()) {
                System.err.println("policy names cards that aren't in the deck, their rules never fire: " + missing);
            }
        }
        System.err.println("META {\"startup_ms\":" + (System.nanoTime() - start) / 1_000_000 + "}");

        GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        if (passive && opts.getOrDefault("fast", "on").equals("on")) {
            rules.setGoldfish(true);
        }
        String stopsOpt = opts.getOrDefault("opponent-stops", policy != null && policy.opponentTurnStops() != null ? policy.opponentTurnStops() : "all");
        Set<PhaseType> stops = parseStops(stopsOpt);
        int threads = Integer.parseInt(opts.getOrDefault("threads", "1"));
        if (threads > 1) {
            // a card first named mid-game would load while other games read the card database
            FModel.getMagicDb().ensureAllCardsLoaded();
        }

        for (int i = Integer.getInteger("goldfish.hashBurn", 0); i > 0; i--) {
            new Object().hashCode();
        }
        Gson gson = new Gson();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "goldfish-watchdog");
            t.setDaemon(true);
            return t;
        });
        try (PrintWriter out = new PrintWriter(new FileWriter(outPath, StandardCharsets.UTF_8))) {
            Consumer<Map<String, Object>> hung = row -> {
                out.println(gson.toJson(row));
                out.flush();
                System.err.println("seed " + row.get("seed") + " did not stop after its timeout; exiting");
                Runtime.getRuntime().halt(3);
            };
            if (threads <= 1) {
                List<RegisteredPlayer> players = buildPlayers(ours, fish, stops, policy, passive);
                for (int g = 0; g < games; g++) {
                    out.println(gson.toJson(play(rules, players, seed + g, g, horizon, timeoutSeconds, watchdog, hung, policy,
                            skipOpponentTurns, libraryOrders.get(seed + g), standIns, false)));
                    out.flush();
                }
            } else {
                ThreadLocal<List<RegisteredPlayer>> players = ThreadLocal.withInitial(() -> buildPlayers(ours, fish, stops, policy, passive));
                AtomicInteger counter = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(threads, r -> new Thread(() -> {
                    Trigger.useThreadIds();
                    r.run();
                }, "goldfish-" + counter.incrementAndGet()));
                List<Future<?>> done = new ArrayList<>();
                for (int g = 0; g < games; g++) {
                    final int index = g;
                    done.add(pool.submit(() -> {
                        String line = gson.toJson(play(rules, players.get(), seed + index, index, horizon, timeoutSeconds, watchdog, hung,
                                policy, skipOpponentTurns, libraryOrders.get(seed + index), standIns, true));
                        synchronized (out) {
                            out.println(line);
                            out.flush();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : done) {
                    f.get();
                }
                pool.shutdown();
            }
        }
        System.exit(0);
    }

    private static List<RegisteredPlayer> buildPlayers(Deck ours, Deck fish, Set<PhaseType> stops, Policy policy, boolean passive) {
        List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer me = RegisteredPlayer.forCommander(ours);
        DeckLobbyPlayer deckPlayer = new DeckLobbyPlayer(DECK_PLAYER, stops, policy);
        deckPlayer.setAiProfile("Default");
        me.setPlayer(deckPlayer);
        players.add(me);
        for (int i = 1; i <= 3; i++) {
            RegisteredPlayer opponent = RegisteredPlayer.forCommander(fish);
            LobbyPlayer lobby = passive ? new PassiveLobbyPlayer("Goldfish " + i) : GamePlayerUtil.createAiPlayer("Goldfish " + i, i);
            opponent.setPlayer(lobby);
            players.add(opponent);
        }
        return players;
    }

    private static Map<String, Object> play(GameRules rules, List<RegisteredPlayer> players, long seed, int index,
                                            int horizon, int timeoutSeconds, ScheduledExecutorService watchdog,
                                            Consumer<Map<String, Object>> hung, Policy policy,
                                            boolean skipOpponentTurns, List<String> libraryOrder, Map<String, String> standIns,
                                            boolean threaded) throws Exception {
        if (threaded) {
            MyRandom.setThreadRandom(new Random(seed));
        } else {
            MyRandom.setRandom(new Random(seed));
        }
        Match match = new Match(rules, players, "Goldfish");
        Game game = match.createGame();
        game.AI_TIMEOUT = Integer.getInteger("goldfish.aiTimeout", 3600);
        game.setNoGUIUser();
        Player me = game.getPlayers().stream().filter(p -> p.getName().equals(DECK_PLAYER)).findFirst().orElseThrow();
        if (libraryOrder != null) {
            ((DeckController) me.getController()).setLibraryOrder(libraryOrder, true);
        }
        if (standIns != null) {
            ((DeckController) me.getController()).pairShuffles(standIns);
        }
        if (skipOpponentTurns) {
            game.getPhaseHandler().setTurnSkipper(p -> p != me);
        }
        Recorder recorder = new Recorder(game, me, horizon, threaded);
        game.subscribeToEvents(recorder);

        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        long start = System.nanoTime();
        long cpuStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        ScheduledFuture<?> stop = watchdog.schedule(() -> {
            timedOut.set(true);
            game.setGameOver(GameEndReason.Draw);
        }, timeoutSeconds, TimeUnit.SECONDS);
        ScheduledFuture<?> kill = watchdog.schedule(() -> {
            if (!finished.get()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("game", index);
                row.put("seed", seed);
                row.put("ms", (System.nanoTime() - start) / 1_000_000);
                row.put("status", "hung");
                hung.accept(row);
            }
        }, timeoutSeconds + 5L, TimeUnit.SECONDS);
        String error = null;
        try {
            match.startGame(game);
        } catch (Throwable e) {
            error = e.toString();
        }
        finished.set(true);
        recorder.finish();
        stop.cancel(false);
        kill.cancel(false);
        long ms = (System.nanoTime() - start) / 1_000_000;
        long cpuMs = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuStart) / 1_000_000;

        List<Player> opponents = game.getRegisteredPlayers().stream().filter(p -> p != me).toList();
        boolean tableDead = opponents.stream().allMatch(Player::hasLost);
        String status = error != null ? "error"
                : timedOut.get() ? "timeout"
                : tableDead ? "dead"
                : recorder.horizonReached() ? "horizon"
                : "other";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("game", index);
        row.put("seed", seed);
        row.put("ms", ms);
        row.put("cpu_ms", cpuMs);
        row.put("status", status);
        row.put("turns", recorder.turns());
        row.put("our_turns", recorder.ourTurns());
        row.put("table_dead_round", tableDead ? recorder.ourTurns() : null);
        row.put("opponents_life", opponents.stream().map(Player::getLife).toList());
        row.put("plays", recorder.plays());
        row.putAll(recorder.contract());
        if (Boolean.getBoolean("goldfish.rounds")) {
            row.put("rounds", recorder.rounds());
        }
        row.put("log_hash", logHash(game));
        String logDir = System.getProperty("goldfish.logDir");
        if (logDir != null) {
            List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
            java.util.Collections.reverse(entries);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(logDir));
            java.nio.file.Files.write(java.nio.file.Path.of(logDir, seed + ".log"),
                    entries.stream().map(e -> e.type() + "|" + e.message()).toList(), StandardCharsets.UTF_8);
        }
        if (error != null) {
            row.put("error", error);
        }
        return row;
    }

    private static String logHash(Game game) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (GameLogEntry entry : game.getGameLog().getLogEntries(null)) {
            if (entry.message().startsWith("Match Duration")) {
                continue;
            }
            digest.update((entry.type() + "|" + entry.message() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest(), 0, 8);
    }

    private static Set<PhaseType> parseStops(String text) {
        if (text.equals("all")) {
            return EnumSet.allOf(PhaseType.class);
        }
        Set<PhaseType> stops = EnumSet.noneOf(PhaseType.class);
        if (!text.equals("none")) {
            for (String s : text.split(",")) {
                stops.add(PhaseType.valueOf(s.trim().toUpperCase()));
            }
        }
        return stops;
    }

        private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("every option needs a value: " + String.join(" ", args));
        }
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --name value, got " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }

    // "on", or "New card=Card it replaces;..." for the swapped version
    private static Map<String, String> parseStandIns(String value) {
        Map<String, String> standIns = new HashMap<>();
        for (String pair : value.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                standIns.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return standIns;
    }

    private static String required(Map<String, String> opts, String name) {
        String value = opts.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }
}
