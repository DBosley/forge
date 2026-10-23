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
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class Policy implements AiPlayPolicy {
    private static final boolean DEBUG = Boolean.getBoolean("goldfish.policyDebug");

    private record Rule(Selector selector, Condition when, boolean force, List<CardFilter> targets) {
    }

    private record Hold(CardFilter card, Condition unless) {
    }

    private final Set<String> cardNames;
    private final String opponentTurnStops;
    private final Condition keepIf;
    private final Map<String, List<CardFilter>> triggerTargets;
    private final List<Rule> rules;
    private final List<Hold> holds;
    private final boolean allowRemoved;

    private Policy(Set<String> cardNames, List<Rule> rules, List<Hold> holds, boolean allowRemoved, String opponentTurnStops,
                   Condition keepIf, Map<String, List<CardFilter>> triggerTargets) {
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
                    Boolean.TRUE.equals(r.get("force")), targets));
        }

        Map<String, List<CardFilter>> triggerTargets = new HashMap<>();
        ((Map<String, List<String>>) doc.getOrDefault("trigger_targets", Map.of())).forEach((card, prefs) -> {
            names.add(card);
            triggerTargets.put(card, prefs.stream().map(t -> CardFilter.parse(t, roles)).toList());
        });

        List<Hold> holds = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) doc.getOrDefault("hold", List.of())) {
            Hold hold = new Hold(CardFilter.parse((String) h.get("card"), roles), Condition.parse((String) h.get("unless"), roles));
            addName(names, hold.card());
            holds.add(hold);
        }
        return new Policy(Set.copyOf(names), List.copyOf(rules), List.copyOf(holds), Boolean.TRUE.equals(doc.get("allow_ai_removed")),
                doc.get("opponent_turn_stops") == null ? null : String.join(",", (List<String>) doc.get("opponent_turn_stops")),
                Condition.parse((String) ((Map<String, Object>) doc.getOrDefault("mulligan", Map.of())).get("keep_if"), roles),
                Map.copyOf(triggerTargets));
    }

    private static void addName(Set<String> names, CardFilter filter) {
        if (filter.cardName() != null) {
            names.add(filter.cardName());
        }
    }

    public boolean keeps(Player player) {
        return keepIf.test(player);
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
            for (SpellAbility sa : candidates) {
                if (!rule.selector().matches(sa) || holds(sa)) {
                    continue;
                }
                sa.setActivatingPlayer(player);
                if (rule.targets().isEmpty() && !rule.force()) {
                    AiPlayDecision decision = ai.canPlayAndPayFor(sa);
                    debug(player, rule, sa, "ai " + decision);
                    if (decision == AiPlayDecision.WillPlay) {
                        return sa;
                    }
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
        if (!ComputerUtilCost.canPayCost(sa, player, false)) {
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
        options.sort(Comparator.comparingInt((Card c) -> rank(c, preferences)).thenComparingInt(Policy::value));
        for (Card c : options) {
            if (sa.getTargets().size() >= sa.getMaxTargets() || rank(c, preferences) == Integer.MAX_VALUE) {
                break;
            }
            sa.getTargets().add(c);
        }
        if (sa.getTargets().isEmpty() || !sa.isTargetNumberValid()) {
            sa.resetTargets();
            return false;
        }
        return true;
    }

    public boolean retargetTrigger(Card host, SpellAbility sa) {
        List<CardFilter> preferences = triggerTargets.get(host.getName());
        if (preferences == null || !sa.usesTargeting()) {
            return false;
        }
        return setTargets(sa, preferences);
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
        if (!sa.isSpell() || host == null) {
            return false;
        }
        Player controller = sa.getActivatingPlayer() != null ? sa.getActivatingPlayer() : host.getController();
        for (Hold hold : holds) {
            if (hold.card().test(host) && !hold.unless().test(controller)) {
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
