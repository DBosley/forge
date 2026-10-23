package forge.goldfish.policy;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.game.cost.Cost;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.spellability.SpellAbility;

record Selector(boolean spell, CardFilter card, Integer loyalty, String text) {
    private static final Pattern LOYALTY = Pattern.compile("^(.*):\\s*([+\\-−]?\\d+)$");

    static Selector play(String text, Map<String, Set<String>> roles) {
        return new Selector(true, CardFilter.parse(text, roles), null, "play " + text);
    }

    static Selector activate(String text, Map<String, Set<String>> roles) {
        Matcher m = LOYALTY.matcher(text.trim());
        if (m.matches()) {
            int amount = Integer.parseInt(m.group(2).replace("−", "-").replace("+", ""));
            return new Selector(false, CardFilter.parse(m.group(1), roles), amount, "activate " + text);
        }
        return new Selector(false, CardFilter.parse(text, roles), null, "activate " + text);
    }

    boolean matches(SpellAbility sa) {
        if (sa.getHostCard() == null || !card.test(sa.getHostCard())) {
            return false;
        }
        if (spell) {
            return sa.isSpell();
        }
        if (sa.isSpell() || sa.isManaAbility() || sa.isLandAbility()) {
            return false;
        }
        return loyalty == null || (sa.isPwAbility() && loyaltyChange(sa) == loyalty);
    }

    static int loyaltyChange(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) {
            return 0;
        }
        CostRemoveCounter remove = cost.getCostPartByType(CostRemoveCounter.class);
        if (remove != null) {
            return -remove.getAbilityAmount(sa);
        }
        CostPutCounter put = cost.getCostPartByType(CostPutCounter.class);
        return put == null ? 0 : put.getAbilityAmount(sa);
    }
}
