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
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class Policy implements AiPlayPolicy {
    private record Rule(Selector selector, Condition when, boolean force, List<CardFilter> targets) {
    }

    private record Hold(CardFilter card, Condition unless) {
    }

    private final Set<String> cardNames;
    private final String opponentTurnStops;
    private final List<Rule> rules;
    private final List<Hold> holds;
    private final boolean allowRemoved;

    private Policy(Set<String> cardNames, List<Rule> rules, List<Hold> holds, boolean allowRemoved, String opponentTurnStops) {
        this.cardNames = cardNames;
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

        List<Hold> holds = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) doc.getOrDefault("hold", List.of())) {
            Hold hold = new Hold(CardFilter.parse((String) h.get("card"), roles), Condition.parse((String) h.get("unless"), roles));
            addName(names, hold.card());
            holds.add(hold);
        }
        return new Policy(Set.copyOf(names), List.copyOf(rules), List.copyOf(holds), Boolean.TRUE.equals(doc.get("allow_ai_removed")),
                doc.get("opponent_turn_stops") == null ? null : String.join(",", (List<String>) doc.get("opponent_turn_stops")));
    }

    private static void addName(Set<String> names, CardFilter filter) {
        if (filter.cardName() != null) {
            names.add(filter.cardName());
        }
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
                    if (ai.canPlayAndPayFor(sa) == AiPlayDecision.WillPlay) {
                        return sa;
                    }
                } else if (prepare(sa, player, rule.targets())) {
                    return sa;
                }
            }
        }
        return null;
    }

    private boolean prepare(SpellAbility sa, Player player, List<CardFilter> preferences) {
        if (!sa.canPlay()) {
            return false;
        }
        if (sa.usesTargeting()) {
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
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return false;
            }
        }
        if (!ComputerUtilCost.canPayCost(sa, player, false)) {
            sa.resetTargets();
            return false;
        }
        return true;
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
