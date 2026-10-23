package forge.goldfish;

import java.util.EnumSet;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

public final class PassiveLobbyPlayer extends LobbyPlayerAi {
    public PassiveLobbyPlayer(String name) {
        super(name, EnumSet.noneOf(AIOption.class));
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PassiveController(game, player, this));
        return player;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PassiveController(slave.getGame(), slave, this);
    }
}
