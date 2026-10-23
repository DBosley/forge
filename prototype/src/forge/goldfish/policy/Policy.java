package forge.goldfish.policy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.AiPlayPolicy;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.GameEntity;
import forge.game.cost.CostPayment;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;

public final class Policy implements AiPlayPolicy {
    private static final boolean DEBUG = Boolean.getBoolean("goldfish.policyDebug");

    private record Rule(Selector selector, Condition when, boolean force, List<CardFilter> targets, String text, int minLoyalty,
                        int maxLoyalty) {
    }

    private record Hold(CardFilter card, Condition unless, boolean activations) {
    }

    private final Set<String> cardNames;
    private final String opponentTurnStops;
    private final Condition keepIf;
    private final Map<String, List<CardFilter>> triggerTargets;
    private final boolean payLifeForUntappedLands;
    private final Map<String, List<CardFilter>> copyChoices;
    private final boolean worstFirst;
    private final List<Rule> rules;
    private final List<Hold> holds;
    private final boolean allowRemoved;
    private final AttackPlan attackPlan;
    private final Map<String, List<String>> charmModes;

    private Policy(Set<String> cardNames, List<Rule> rules, List<Hold> holds, boolean allowRemoved, String opponentTurnStops,
                   Condition keepIf, Map<String, List<CardFilter>> triggerTargets, boolean payLifeForUntappedLands,
                   Map<String, List<CardFilter>> copyChoices, boolean worstFirst, AttackPlan attackPlan,
                   Map<String, List<String>> charmModes) {
        this.attackPlan = attackPlan;
        this.charmModes = charmModes;
        this.worstFirst = worstFirst;
        this.payLifeForUntappedLands = payLifeForUntappedLands;
        this.copyChoices = copyChoices;
        this.cardNames = cardNames;
        this.keepIf = keepIf;
        this.triggerTargets = triggerTargets;
        this.opponentTurnStops = opponentTurnStops;
        this.rules = rules;
        this.holds = holds;
        this.allowRemoved = allowRemoved;
    }

    @SuppressWarnings("unchecked")
    public static Policy load(Path path) throws IOException {
        Map<String, Object> doc;
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            doc = new Yaml().load(in);
        }
        Map<String, Set<String>> roles = new HashMap<>();
        ((Map<String, List<String>>) doc.getOrDefault("roles", Map.of()))
                .forEach((role, cards) -> roles.put(role, new LinkedHashSet<>(cards)));

        Set<String> names = new LinkedHashSet<>();
        roles.values().forEach(names::addAll);
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) doc.getOrDefault("plays", List.of())) {
            Selector selector = r.containsKey("play") ? Selector.play((String) r.get("play"), roles)
                    : r.containsKey("activate") ? Selector.activate((String) r.get("activate"), roles)
                    : null;
            if (selector == null) {
                throw new IllegalArgumentException("a play rule needs play: or activate: " + r);
            }
            List<CardFilter> targets = ((List<String>) r.getOrDefault("target", List.of())).stream()
                    .map(t -> CardFilter.parse(t, roles)).toList();
            addName(names, selector.card());
            targets.forEach(t -> addName(names, t));
            rules.add(new Rule(selector, Condition.parse((String) r.get("when"), roles),
                    Boolean.TRUE.equals(r.get("force")), targets, (String) r.get("text"),
                    ((Number) r.getOrDefault("min_loyalty", 0)).intValue(),
                    ((Number) r.getOrDefault("max_loyalty", Integer.MAX_VALUE)).intValue()));
        }

        Map<String, List<CardFilter>> copyChoices = new HashMap<>();
        ((Map<String, List<String>>) doc.getOrDefault("copy_choices", Map.of())).forEach((card, prefs) -> {
            names.add(card);
            copyChoices.put(card, prefs.stream().map(t -> CardFilter.parse(t, roles)).toList());
        });
        Map<String, List<CardFilter>> triggerTargets = new HashMap<>();
        ((Map<String, List<String>>) doc.getOrDefault("trigger_targets", Map.of())).forEach((card, prefs) -> {
            names.add(card);
            triggerTargets.put(card, prefs.stream().map(t -> CardFilter.parse(t, roles)).toList());
        });

        List<Hold> holds = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) doc.getOrDefault("hold", List.of())) {
            boolean activations = h.containsKey("activate");
            Hold hold = new Hold(CardFilter.parse((String) h.get(activations ? "activate" : "card"), roles),
                    Condition.parse((String) h.get("unless"), roles), activations);
            addName(names, hold.card());
            holds.add(hold);
        }
        return new Policy(Set.copyOf(names), List.copyOf(rules), List.copyOf(holds), Boolean.TRUE.equals(doc.get("allow_ai_removed")),
                doc.get("opponent_turn_stops") == null ? null : String.join(",", (List<String>) doc.get("opponent_turn_stops")),
                Condition.parse((String) ((Map<String, Object>) doc.getOrDefault("mulligan", Map.of())).get("keep_if"), roles),
                Map.copyOf(triggerTargets), Boolean.TRUE.equals(doc.get("pay_life_for_untapped_lands")), Map.copyOf(copyChoices),
                "worst_first".equals(doc.get("hand_disposal")), AttackPlan.parse(doc.get("attack")),
                Map.copyOf((Map<String, List<String>>) doc.getOrDefault("charm_modes", Map.of())));
    }

    private static void addName(Set<String> names, CardFilter filter) {
        if (filter.cardName() != null) {
            names.add(filter.cardName());
        }
    }

    public Card chooseCopy(Card host, List<Card> options) {
        List<CardFilter> preferences = copyChoices.get(host.getName());
        if (preferences == null) {
            return null;
        }
        return options.stream().filter(c -> rank(c, preferences) != Integer.MAX_VALUE)
                .min(Comparator.comparingInt((Card c) -> rank(c, preferences)).thenComparingInt(c -> -value(c))).orElse(null);
    }

    public List<Card> worstCards(Player player, List<Card> options, int count) {
        if (!worstFirst) {
            return null;
        }
        long landsOut = player.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().filter(Card::isLand).count();
        List<Card> pool = new ArrayList<>(options);
        List<Card> picked = new ArrayList<>();
        while (picked.size() < count && !pool.isEmpty()) {
            List<Card> lands = pool.stream().filter(Card::isLand).toList();
            Card pick;
            if (lands.size() >= 2 || (!lands.isEmpty() && landsOut >= 7)) {
                pick = lands.get(lands.size() - 1);
            } else {
                pick = pool.stream().filter(c -> !c.isLand()).max(Comparator.comparingInt(Card::getCMC))
                        .orElse(lands.isEmpty() ? null : lands.get(lands.size() - 1));
            }
            if (pick == null) {
                break;
            }
            picked.add(pick);
            pool.remove(pick);
        }
        return picked;
    }

    public boolean paysLifeForUntappedLands() {
        return payLifeForUntappedLands;
    }

    public boolean keeps(Player player) {
        return keepIf.test(player);
    }

    public AttackPlan attackPlan() {
        return attackPlan;
    }

    public String opponentTurnStops() {
        return opponentTurnStops;
    }

    public Set<String> cardNames() {
        return cardNames;
    }

    @Override
    public SpellAbility choose(AiController ai, Player player, List<SpellAbility> candidates) {
        if (!player.getGame().getStack().isEmpty()) {
            return null;
        }
        for (Rule rule : rules) {
            if (!rule.when().test(player)) {
                continue;
            }
            List<SpellAbility> matching = new ArrayList<>();
            for (SpellAbility sa : candidates) {
                if (rule.selector().matches(sa) && (sa.isActivatedAbility() || !holds(sa))
                        && (rule.text() == null || String.valueOf(sa.getDescription()).contains(rule.text()))) {
                    matching.add(sa);
                }
            }
            matching.sort(Comparator.comparingInt(Policy::manaPaid).reversed());
            for (SpellAbility sa : matching) {
                sa.setActivatingPlayer(player);
                if (rule.targets().isEmpty() && !rule.force()) {
                    AiPlayDecision decision = ai.canPlayAndPayFor(sa);
                    debug(player, rule, sa, "ai " + decision);
                    if (decision == AiPlayDecision.WillPlay) {
                        return sa;
                    }
                } else if (rule.minLoyalty() > 0 && sa.getHostCard().getCurrentLoyalty() < rule.minLoyalty()) {
                    debug(player, rule, sa, "loyalty below " + rule.minLoyalty());
                } else if (sa.getHostCard().getCurrentLoyalty() > rule.maxLoyalty()) {
                    debug(player, rule, sa, "loyalty above " + rule.maxLoyalty());
                } else {
                    String why = prepare(sa, player, rule.targets());
                    debug(player, rule, sa, why == null ? "play" : why);
                    if (why == null) {
                        return sa;
                    }
                }
            }
        }
        return null;
    }

    private String prepare(SpellAbility sa, Player player, List<CardFilter> preferences) {
        if (!sa.canPlay()) {
            return "can't play now";
        }
        if (sa.usesTargeting() && !setTargets(sa, preferences)) {
            return "no target";
        }
        if (sa.costHasManaX()) {
            int x = ComputerUtilMana.determineLeftoverMana(sa, player, false);
            if (x < 1) {
                sa.resetTargets();
                return "x below 1";
            }
            sa.setXManaCostPaid(x);
        }
        boolean payable = sa.isPwAbility() ? CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false)
                : ComputerUtilCost.canPayCost(sa, player, false);
        if (!payable) {
            sa.resetTargets();
            return "can't pay";
        }
        return null;
    }

    private static void debug(Player player, Rule rule, SpellAbility sa, String outcome) {
        if (DEBUG) {
            System.err.printf("POLICY turn %d mana %d phase %s %s | %s | %s%n", player.getTurn(),
                    ComputerUtilMana.getAvailableManaEstimate(player, false), player.getGame().getPhaseHandler().getPhase(),
                    rule.selector().text(), sa.getHostCard().getName(), outcome);
        }
    }

    private static boolean setTargets(SpellAbility sa, List<CardFilter> preferences) {
        sa.resetTargets();
        List<Card> options = new ArrayList<>();
        for (GameEntity e : sa.getTargetRestrictions().getAllCandidates(sa)) {
            if (e instanceof Card c && sa.canTarget(c)) {
                options.add(c);
            }
        }
        Map<Card, Integer> ranks = new HashMap<>();
        options.forEach(c -> ranks.put(c, rank(c, preferences)));
        options.removeIf(c -> ranks.get(c) == Integer.MAX_VALUE);
        Map<Card, Integer> values = new HashMap<>();
        options.forEach(c -> values.put(c, value(c)));
        options.sort(Comparator.comparingInt((Card c) -> ranks.get(c)).thenComparingInt(values::get));
        for (Card c : options) {
            if (sa.getTargets().size() >= sa.getMaxTargets()) {
                break;
            }
            sa.getTargets().add(c);
        }
        if (sa.getTargets().isEmpty() && sa.getMinTargets() == 0) {
            return true;
        }
        if (sa.getTargets().isEmpty() || !sa.isTargetNumberValid()) {
            sa.resetTargets();
            return false;
        }
        return true;
    }

    public List<AbilitySub> chooseModes(SpellAbility sa, List<AbilitySub> possible, int min, int num) {
        List<String> wanted = charmModes.get(sa.getHostCard().getName());
        if (wanted == null) {
            return null;
        }
        List<AbilitySub> chosen = new ArrayList<>();
        for (String text : wanted) {
            possible.stream().filter(m -> m.getDescription().contains(text) && !chosen.contains(m)).findFirst()
                    .ifPresent(chosen::add);
            if (chosen.size() >= num) {
                break;
            }
        }
        return chosen.size() >= min ? chosen : null;
    }

    public boolean retargetTrigger(Card host, SpellAbility sa) {
        List<CardFilter> preferences = triggerTargets.get(host.getName());
        if (preferences == null || !sa.usesTargeting()) {
            return false;
        }
        return setTargets(sa, preferences);
    }

    private static int manaPaid(SpellAbility sa) {
        return sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null ? 0 : sa.getPayCosts().getTotalMana().getCMC();
    }

    private static int rank(Card c, List<CardFilter> preferences) {
        if (preferences.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < preferences.size(); i++) {
            if (preferences.get(i).test(c)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int value(Card c) {
        return c.isCreature() ? ComputerUtilCard.evaluateCreature(c) : c.getCMC() * 100;
    }

    @Override
    public boolean holds(SpellAbility sa) {
        Card host = sa.getHostCard();
        boolean activation = sa.isActivatedAbility() && !sa.isManaAbility();
        if (!(sa.isSpell() || activation) || host == null) {
            return false;
        }
        Player controller = sa.getActivatingPlayer() != null ? sa.getActivatingPlayer() : host.getController();
        for (Hold hold : holds) {
            if (hold.activations() == activation && hold.card().test(host) && !hold.unless().test(controller)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean allowsRemovedCard(Card card) {
        return allowRemoved;
    }
}
