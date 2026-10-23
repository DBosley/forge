package forge.goldfish;

import java.util.List;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.goldfish.policy.Policy;

public final class DeckController extends PlayerControllerAi {
    private final Set<PhaseType> opponentTurnStops;
    private final Policy policy;

    public DeckController(Game game, Player player, LobbyPlayer lobbyPlayer, Set<PhaseType> opponentTurnStops, Policy policy) {
        super(game, player, lobbyPlayer);
        this.opponentTurnStops = opponentTurnStops;
        this.policy = policy;
        if (policy != null) {
            getAi().setPlayPolicy(policy);
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (policy == null || cardsToReturn >= 2) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        return policy.keeps(player);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapper, boolean isMandatory) {
        if (policy != null && policy.retargetTrigger(host, wrapper.getWrappedAbility())) {
            return ComputerUtil.playNoStack(wrapper.getActivatingPlayer(), wrapper, getGame(), true);
        }
        return super.playTrigger(host, wrapper, isMandatory);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (policy == null) {
            super.orderAndPlaySimultaneousSa(activePlayerSAs);
            return;
        }
        for (SpellAbility sa : orderSimultaneousSa(activePlayerSAs)) {
            SpellAbility inner = sa instanceof WrappedAbility w ? w.getWrappedAbility() : sa;
            if (sa.isTrigger() && !sa.isCopied() && policy.retargetTrigger(sa.getHostCard(), inner)) {
                ComputerUtil.playStack(sa, player, getGame());
            } else {
                super.orderAndPlaySimultaneousSa(List.of(sa));
            }
        }
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
