package forge.goldfish;

import java.util.List;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class DeckController extends PlayerControllerAi {
    private final Set<PhaseType> opponentTurnStops;

    public DeckController(Game game, Player player, LobbyPlayer lobbyPlayer, Set<PhaseType> opponentTurnStops) {
        super(game, player, lobbyPlayer);
        this.opponentTurnStops = opponentTurnStops;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Game game = getGame();
        if (game.getPhaseHandler().getPlayerTurn() != player && !opponentTurnStops.contains(game.getPhaseHandler().getPhase())) {
            return null;
        }
        return super.chooseSpellAbilityToPlay();
    }
}
