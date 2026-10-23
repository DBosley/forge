package forge.goldfish;

import java.util.List;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.LobbyPlayer;

public final class PassiveController extends PlayerControllerAi {
    public PassiveController(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return null;
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return true;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
    }
}
