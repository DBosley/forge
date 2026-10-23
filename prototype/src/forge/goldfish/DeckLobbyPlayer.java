package forge.goldfish;

import java.util.EnumSet;
import java.util.Set;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public final class DeckLobbyPlayer extends LobbyPlayerAi {
    private final Set<PhaseType> opponentTurnStops;

    public DeckLobbyPlayer(String name, Set<PhaseType> opponentTurnStops) {
        super(name, EnumSet.noneOf(AIOption.class));
        this.opponentTurnStops = opponentTurnStops;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new DeckController(game, player, this, opponentTurnStops));
        return player;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new DeckController(slave.getGame(), slave, this, opponentTurnStops);
    }
}
