package forge.goldfish;

import java.util.EnumSet;
import java.util.Set;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.goldfish.policy.Policy;

public final class DeckLobbyPlayer extends LobbyPlayerAi {
    private final Set<PhaseType> opponentTurnStops;
    private final Policy policy;

    public DeckLobbyPlayer(String name, Set<PhaseType> opponentTurnStops, Policy policy) {
        super(name, EnumSet.noneOf(AIOption.class));
        this.opponentTurnStops = opponentTurnStops;
        this.policy = policy;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new DeckController(game, player, this, opponentTurnStops, policy));
        return player;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new DeckController(slave.getGame(), slave, this, opponentTurnStops, policy);
    }
}
