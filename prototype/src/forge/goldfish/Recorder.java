package forge.goldfish;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;

public final class Recorder {
    private final Game game;
    private final Player me;
    private final int horizon;
    private int ourTurns;
    private int turns;
    private boolean horizonReached;

    public Recorder(Game game, Player me, int horizon) {
        this.game = game;
        this.me = me;
        this.horizon = horizon;
    }

    @Subscribe
    public void receive(GameEvent event) {
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

    public boolean horizonReached() {
        return horizonReached;
    }
}
