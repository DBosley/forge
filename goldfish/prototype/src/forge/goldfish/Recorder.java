package forge.goldfish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

public final class Recorder {
    private final Game game;
    private final Player me;
    private final int horizon;
    private final boolean threaded;
    private final boolean snapshots = Boolean.getBoolean("goldfish.rounds");
    private int ourTurns;
    private int turns;
    private boolean horizonReached;
    private int seat;
    private int mulligans;
    private int keptHand = -1;
    private int closedRounds;
    private final Map<String, Integer> plays = new TreeMap<>();
    private final Map<String, Integer> firstRound = new TreeMap<>();
    private final List<Integer> lands = new ArrayList<>();
    private final List<Integer> manaAvailable = new ArrayList<>();
    private final List<Integer> manaSpent = new ArrayList<>();
    private final List<Integer> spells = new ArrayList<>();
    private final List<Integer> landDrops = new ArrayList<>();
    private final List<Integer> damage = new ArrayList<>();
    private final List<Map<String, Object>> rounds = new ArrayList<>();
    private Map<String, Object> mainStart = Map.of();
    private List<String> castThisRound = new ArrayList<>();
    private List<String> landsThisRound = new ArrayList<>();
    private List<String> activationsThisRound = new ArrayList<>();

    public Recorder(Game game, Player me, int horizon, boolean threaded) {
        this.game = game;
        this.me = me;
        this.horizon = horizon;
        this.threaded = threaded;
    }

    @Subscribe
    public void receive(GameEvent event) {
        if (event instanceof GameEventSpellAbilityCast cast) {
            CardView host = cast.sa().getHostCard();
            if (host != null && host.getController() != null && host.getController().getId() == me.getId()) {
                String text = String.valueOf(cast.sa().getDescription());
                int colon = text.indexOf(':');
                String kind = cast.sa().isSpell() ? "" : colon > 0 && colon <= 4 ? ": " + text.substring(0, colon) : ": ability";
                plays.merge(host.getName() + kind, 1, Integer::sum);
                firstRound.putIfAbsent(host.getName() + kind, round());
                SpellAbilityStackInstance top = game.getStack().peek();
                if (top != null && top.getId() == cast.si().getId()) {
                    add(manaSpent, top.getSpellAbility().getTotalManaSpent());
                }
                if (cast.sa().isSpell()) {
                    add(spells, 1);
                    castThisRound.add(host.getName());
                } else if (!kind.equals(": ability")) {
                    activationsThisRound.add(host.getName() + "#" + host.getId() + kind);
                }
            }
            return;
        }
        if (event instanceof forge.game.event.GameEventCardChangeZone move) {
            if (move.card() != null && move.from() != null && move.to() != null
                    && move.from().zoneType() == ZoneType.Battlefield && move.to().zoneType() != ZoneType.Battlefield
                    && move.card().getController() != null && move.card().getController().getId() == me.getId()
                    && move.card().getName().startsWith("Jace, Multiverse Architect")) {
                plays.merge("Jace, Multiverse Architect: left battlefield to " + move.to().zoneType(), 1, Integer::sum);
            }
            return;
        }
        if (event instanceof forge.game.event.GameEventLandPlayed land) {
            if (land.player().getId() == me.getId()) {
                add(landDrops, 1);
                landsThisRound.add(land.land().getName() + (land.land().isTapped() ? " (tapped)" : ""));
            }
            return;
        }
        if (event instanceof GameEventMulligan mulligan) {
            if (mulligan.player().getId() == me.getId()) {
                mulligans++;
            }
            return;
        }
        if (event instanceof GameEventTurnPhase phase) {
            if (!snapshots) {
                return;
            }
            if (phase.phase() == PhaseType.MAIN1 && phase.playerTurn().getId() == me.getId()) {
                mainStart = new LinkedHashMap<>();
                for (forge.game.card.Card c : me.getCardsIn(ZoneType.Command)) {
                    if (c.isCommander()) {
                        boolean payable = c.getSpells().stream().anyMatch(this::payable);
                        mainStart.put("commander_payable", payable);
                    }
                }
                mainStart.put("creatures", me.getCreaturesInPlay().size());
                mainStart.put("lands", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand()).map(c -> c.getName()).sorted().toList());
            }
            if (phase.phase() == PhaseType.END_OF_TURN && phase.playerTurn().getId() == me.getId()) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("round", ourTurns);
                snap.put("hand", me.getCardsIn(ZoneType.Hand).stream().map(c -> c.getName() + " (" + c.getCMC() + ")").sorted().toList());
                snap.put("mana", forge.ai.ComputerUtilMana.getAvailableManaEstimate(me, false));
                snap.put("lands", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand()).count());
                snap.put("permanents", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> !c.isLand())
                        .map(c -> c.getName()).sorted().toList());
                snap.put("opponents_life", game.getRegisteredPlayers().stream().filter(p -> p != me).map(Player::getLife).toList());
                snap.put("untapped_sources", me.getCardsIn(ZoneType.Battlefield).stream()
                        .filter(c -> !c.isTapped() && !c.getManaAbilities().isEmpty()).count());
                List<String> payable = new ArrayList<>();
                for (forge.game.card.Card c : me.getCardsIn(ZoneType.Hand)) {
                    if (c.isLand()) {
                        continue;
                    }
                    for (SpellAbility sa : c.getSpells()) {
                        if (payable(sa)) {
                            payable.add(c.getName());
                            break;
                        }
                    }
                }
                snap.put("payable_left", payable);
                snap.put("main1", mainStart);
                snap.put("cast", castThisRound);
                castThisRound = new ArrayList<>();
                snap.put("land_played", landsThisRound);
                landsThisRound = new ArrayList<>();
                snap.put("activations", activationsThisRound);
                activationsThisRound = new ArrayList<>();
                snap.put("walkers", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isPlaneswalker())
                        .map(c -> c.getName() + "#" + c.getId() + " L" + c.getCurrentLoyalty() + " used" + c.getPlaneswalkerAbilityActivated()).toList());
                rounds.add(snap);
            }
            return;
        }
        if (!(event instanceof GameEventTurnBegan began)) {
            return;
        }
        turns = began.turnNumber();
        if (keptHand < 0) {
            keptHand = me.getCardsIn(ZoneType.Hand).size();
        }
        if (began.turnOwner().getId() != me.getId()) {
            return;
        }
        if (ourTurns > 0) {
            closeRound();
        }
        ourTurns++;
        if (seat == 0) {
            seat = began.turnNumber();
        }
        if (horizon > 0 && ourTurns > horizon) {
            horizonReached = true;
            game.setGameOver(GameEndReason.Draw);
        }
    }

    private boolean payable(SpellAbility sa) {
        Random saved = MyRandom.getRandom();
        setRandom(new Random(0));
        try {
            sa.setActivatingPlayer(me);
            return forge.ai.ComputerUtilMana.canPayManaCost(sa, me, 0, false);
        } finally {
            setRandom(saved);
        }
    }

    private void setRandom(Random random) {
        if (threaded) {
            MyRandom.setThreadRandom(random);
        } else {
            MyRandom.setRandom(random);
        }
    }

    public void finish() {
        if (ourTurns() > closedRounds) {
            closeRound();
        }
    }

    private void closeRound() {
        closedRounds = ourTurns;
        set(damage, game.getRegisteredPlayers().stream().filter(p -> p != me)
                .mapToInt(p -> p.getStartingLife() - Math.max(p.getLife(), 0)).sum());
        set(lands, (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand()).count());
        set(manaAvailable, forge.ai.ComputerUtilMana.getAvailableManaEstimate(me, false));
        add(manaSpent, 0);
        add(spells, 0);
        add(landDrops, 0);
    }

    private int round() {
        return Math.max(ourTurns, 1);
    }

    private void add(List<Integer> perRound, int amount) {
        set(perRound, (perRound.size() >= round() ? perRound.get(round() - 1) : 0) + amount);
    }

    private void set(List<Integer> perRound, int value) {
        while (perRound.size() < round()) {
            perRound.add(0);
        }
        perRound.set(round() - 1, value);
    }

    public int ourTurns() {
        return horizonReached ? ourTurns - 1 : ourTurns;
    }

    public int turns() {
        return turns;
    }

    public List<Map<String, Object>> rounds() {
        return rounds;
    }

    public Map<String, Integer> plays() {
        return plays;
    }

    public boolean horizonReached() {
        return horizonReached;
    }

    public Map<String, Object> contract() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seat", seat);
        out.put("mulligans", mulligans);
        out.put("kept_hand", keptHand);
        out.put("first_round", firstRound);
        out.put("damage_by_round", damage);
        out.put("lands_by_round", lands);
        out.put("mana_available_by_round", manaAvailable);
        out.put("mana_spent_by_round", manaSpent);
        out.put("spells_by_round", spells);
        out.put("land_drops_by_round", landDrops);
        return out;
    }
}
