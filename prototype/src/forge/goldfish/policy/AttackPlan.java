package forge.goldfish.policy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiAttackController;
import forge.game.player.Player;

public enum AttackPlan {
    FORGE("forge"),
    FOCUS_LOWEST("focus_lowest"),
    THREAT("threat"),
    SPREAD("spread");

    private final String key;

    AttackPlan(String key) {
        this.key = key;
    }

    static AttackPlan parse(Object value) {
        if (value == null) {
            return FORGE;
        }
        for (AttackPlan plan : values()) {
            if (plan.key.equals(value)) {
                return plan;
            }
        }
        throw new IllegalArgumentException("attack: is one of forge, focus_lowest, threat, spread, not " + value);
    }

    /**
     * Who the next attacker goes at, given the life each defender would have left after the attackers already sent.
     * A defender sent lethal damage is skipped, so the rest spill over instead of piling on.
     */
    public Comparator<Player> order(Player attacker, List<Player> defenders, Map<Player, Integer> lifeLeft) {
        return switch (this) {
            case FOCUS_LOWEST -> Comparator.comparingInt(lifeLeft::get);
            case SPREAD -> Comparator.comparingInt((Player p) -> -lifeLeft.get(p));
            case THREAT -> {
                Map<Player, Integer> threat = new HashMap<>();
                defenders.forEach(p -> threat.put(p, AiAttackController.threatScore(attacker, p, true)));
                yield Comparator.comparingInt((Player p) -> -threat.get(p));
            }
            case FORGE -> throw new IllegalStateException("forge plans its own attacks");
        };
    }
}
