package forge.ai;

import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public interface AiPlayPolicy {
    SpellAbility choose(AiController ai, Player player, List<SpellAbility> candidates);

    boolean holds(SpellAbility sa);

    boolean allowsRemovedCard(Card card);
}
