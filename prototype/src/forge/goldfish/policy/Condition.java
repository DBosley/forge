package forge.goldfish.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.ai.ComputerUtilMana;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

final class Condition implements Predicate<Player> {
    static final Condition ALWAYS = new Condition("", List.of());

    private static final Pattern TERM = Pattern.compile(
            "^\\s*(count|hand)\\(([^)]*)\\)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+)\\s*$|^\\s*(sources|mana|round|life)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+)\\s*$");

    private final String text;
    private final List<Predicate<Player>> terms;

    private Condition(String text, List<Predicate<Player>> terms) {
        this.text = text;
        this.terms = terms;
    }

    static Condition parse(String text, Map<String, Set<String>> roles) {
        if (text == null || text.isBlank()) {
            return ALWAYS;
        }
        List<Predicate<Player>> terms = new ArrayList<>();
        for (String part : text.split("\\s+and\\s+")) {
            Matcher m = TERM.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("can't read condition \"" + part + "\" in \"" + text + "\"");
            }
            ToIntFunction<Player> value;
            String op;
            int bound;
            if (m.group(1) != null) {
                CardFilter filter = CardFilter.parse(m.group(2), roles);
                ZoneType zone = m.group(1).equals("hand") ? ZoneType.Hand : ZoneType.Battlefield;
                value = p -> (int) p.getCardsIn(zone).stream().filter(filter).count();
                op = m.group(3);
                bound = Integer.parseInt(m.group(4));
            } else {
                value = switch (m.group(5)) {
                    case "sources" -> p -> (int) p.getCardsIn(ZoneType.Battlefield).stream()
                            .filter(c -> !c.getManaAbilities().isEmpty()).count();
                    case "mana" -> p -> ComputerUtilMana.getAvailableManaEstimate(p, false);
                    case "round" -> Player::getTurn;
                    default -> Player::getLife;
                };
                op = m.group(6);
                bound = Integer.parseInt(m.group(7));
            }
            terms.add(p -> compare(value.applyAsInt(p), op, bound));
        }
        return new Condition(text, List.copyOf(terms));
    }

    private static boolean compare(int value, String op, int bound) {
        return switch (op) {
            case ">=" -> value >= bound;
            case "<=" -> value <= bound;
            case "==" -> value == bound;
            case "!=" -> value != bound;
            case ">" -> value > bound;
            default -> value < bound;
        };
    }

    @Override
    public boolean test(Player p) {
        return terms.stream().allMatch(t -> t.test(p));
    }

    @Override
    public String toString() {
        return text;
    }
}
