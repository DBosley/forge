package forge.goldfish;

import java.util.List;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.LobbyPlayer;

public final class PassiveController extends PlayerControllerAi {
    public PassiveController(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Game game = getGame();
        if (game.getPhaseHandler().getPlayerTurn() != player || !game.getPhaseHandler().is(PhaseType.MAIN1)
                || !game.getStack().isEmpty()) {
            return null;
        }
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (!card.isLand()) {
                continue;
            }
            for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
                if (sa.isLandAbility() && sa.canPlay()) {
                    sa.setActivatingPlayer(player);
                    return List.of(sa);
                }
            }
        }
        return null;
    }

    @Override
    public boolean isPassive() {
        return true;
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
