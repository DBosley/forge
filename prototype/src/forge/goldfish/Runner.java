package forge.goldfish;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.gson.Gson;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.PlayerControllerAi;
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
import forge.goldfish.policy.Policy;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

public final class Runner {
    private static final String DECK_PLAYER = "Deck";

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
        String outPath = required(opts, "out");

        System.setProperty("java.awt.headless", "true");
        long start = System.nanoTime();
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Policy policy = opts.containsKey("policy") ? Policy.load(Path.of(opts.get("policy"))) : null;
        Deck ours = DeckSerializer.fromFile(new File(deckPath));
        Deck fish = DeckSerializer.fromFile(new File(fishPath));
        if (policy != null) {
            Set<String> inDeck = new HashSet<>();
            ours.getAllCardsInASinglePool().forEach(e -> inDeck.add(e.getKey().getName()));
            List<String> missing = policy.cardNames().stream().filter(n -> !inDeck.contains(n)).toList();
            if (!missing.isEmpty()) {
                System.err.println("policy names cards that aren't in the deck: " + missing);
                System.exit(2);
            }
        }
        System.err.println("META {\"startup_ms\":" + (System.nanoTime() - start) / 1_000_000 + "}");

        GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer me = RegisteredPlayer.forCommander(ours);
        String stopsOpt = opts.getOrDefault("opponent-stops", policy != null && policy.opponentTurnStops() != null ? policy.opponentTurnStops() : "all");
        DeckLobbyPlayer deckPlayer = new DeckLobbyPlayer(DECK_PLAYER, parseStops(stopsOpt));
        deckPlayer.setAiProfile("Default");
        me.setPlayer(deckPlayer);
        players.add(me);
        for (int i = 1; i <= 3; i++) {
            RegisteredPlayer opponent = RegisteredPlayer.forCommander(fish);
            LobbyPlayer lobby = passive ? new PassiveLobbyPlayer("Goldfish " + i) : GamePlayerUtil.createAiPlayer("Goldfish " + i, i);
            opponent.setPlayer(lobby);
            players.add(opponent);
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
            for (int g = 0; g < games; g++) {
                out.println(gson.toJson(play(rules, players, seed + g, g, horizon, timeoutSeconds, watchdog, hung, policy,
                        skipOpponentTurns)));
                out.flush();
            }
        }
        System.exit(0);
    }

    private static Map<String, Object> play(GameRules rules, List<RegisteredPlayer> players, long seed, int index,
                                            int horizon, int timeoutSeconds, ScheduledExecutorService watchdog,
                                            Consumer<Map<String, Object>> hung, Policy policy,
                                            boolean skipOpponentTurns) throws Exception {
        MyRandom.setRandom(new Random(seed));
        Match match = new Match(rules, players, "Goldfish");
        Game game = match.createGame();
        game.setNoGUIUser();
        Player me = game.getPlayers().stream().filter(p -> p.getName().equals(DECK_PLAYER)).findFirst().orElseThrow();
        if (policy != null) {
            ((PlayerControllerAi) me.getController()).getAi().setPlayPolicy(policy);
        }
        if (skipOpponentTurns) {
            game.getPhaseHandler().setTurnSkipper(p -> p != me);
        }
        Recorder recorder = new Recorder(game, me, horizon);
        game.subscribeToEvents(recorder);

        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        long start = System.nanoTime();
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
        stop.cancel(false);
        kill.cancel(false);
        long ms = (System.nanoTime() - start) / 1_000_000;

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
        row.put("status", status);
        row.put("turns", recorder.turns());
        row.put("our_turns", recorder.ourTurns());
        row.put("table_dead_round", tableDead ? recorder.ourTurns() : null);
        row.put("opponents_life", opponents.stream().map(Player::getLife).toList());
        row.put("plays", recorder.plays());
        row.put("log_hash", logHash(game));
        String logDir = System.getProperty("goldfish.logDir");
        if (logDir != null) {
            List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
            java.util.Collections.reverse(entries);
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

    private static String required(Map<String, String> opts, String name) {
        String value = opts.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }
}
