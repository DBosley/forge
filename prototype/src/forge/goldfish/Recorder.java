package forge.goldfish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class Recorder {
    private final Game game;
    private final Player me;
    private final int horizon;
    private int ourTurns;
    private int turns;
    private boolean horizonReached;
    private final Map<String, Integer> plays = new TreeMap<>();
    private final List<Map<String, Object>> rounds = new ArrayList<>();

    public Recorder(Game game, Player me, int horizon) {
        this.game = game;
        this.me = me;
        this.horizon = horizon;
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
            }
            return;
        }
        if (event instanceof GameEventTurnPhase phase) {
            if (phase.phase() == PhaseType.END_OF_TURN && phase.playerTurn().getId() == me.getId()) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("round", ourTurns);
                snap.put("hand", me.getCardsIn(ZoneType.Hand).stream().map(c -> c.getName() + " (" + c.getCMC() + ")").sorted().toList());
                snap.put("mana", forge.ai.ComputerUtilMana.getAvailableManaEstimate(me, false));
                snap.put("lands", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand()).count());
                snap.put("permanents", me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> !c.isLand())
                        .map(c -> c.getName()).sorted().toList());
                snap.put("opponents_life", game.getRegisteredPlayers().stream().filter(p -> p != me).map(Player::getLife).toList());
                rounds.add(snap);
            }
            return;
        }
        if (!(event instanceof GameEventTurnBegan began)) {
            return;
        }
        turns = began.turnNumber();
        if (began.turnOwner().getId() != me.getId()) {
            return;
        }
        ourTurns++;
        if (horizon > 0 && ourTurns > horizon) {
            horizonReached = true;
            game.setGameOver(GameEndReason.Draw);
        }
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
}
