package forge.goldfish.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import forge.game.card.Card;

record CardFilter(String text, List<String> words, Map<String, Set<String>> roles) implements Predicate<Card> {
    private static final Set<String> KINDS = Set.of("creature", "planeswalker", "land", "artifact", "enchantment",
            "instant", "sorcery", "token", "nontoken", "legendary", "card");

    static CardFilter parse(String text, Map<String, Set<String>> roles) {
        List<String> words = Arrays.asList(text.trim().split("\\s+"));
        boolean byKind = words.stream().allMatch(w -> KINDS.contains(w) || w.startsWith("role:"));
        if (byKind) {
            words.stream().filter(w -> w.startsWith("role:") && !roles.containsKey(w.substring(5))).findFirst()
                    .ifPresent(w -> { throw new IllegalArgumentException("unknown role in \"" + text + "\""); });
        }
        return new CardFilter(text.trim(), byKind ? List.copyOf(words) : List.of(), roles);
    }

    String cardName() {
        return words.isEmpty() ? text : null;
    }

    @Override
    public boolean test(Card c) {
        if (words.isEmpty()) {
            return c.getName().equals(text);
        }
        for (String w : words) {
            boolean ok = switch (w) {
                case "creature" -> c.isCreature();
                case "planeswalker" -> c.isPlaneswalker();
                case "land" -> c.isLand();
                case "artifact" -> c.isArtifact();
                case "enchantment" -> c.isEnchantment();
                case "instant" -> c.isInstant();
                case "sorcery" -> c.isSorcery();
                case "token" -> c.isToken();
                case "nontoken" -> !c.isToken();
                case "legendary" -> c.getType().isLegendary();
                case "card" -> true;
                default -> roles.get(w.substring(5)).contains(c.getName());
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
