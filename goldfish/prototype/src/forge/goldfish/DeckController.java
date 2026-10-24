package forge.goldfish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.cost.Cost;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPayment;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import forge.goldfish.policy.AttackPlan;
import forge.goldfish.policy.Policy;

public final class DeckController extends PlayerControllerAi {
    private final Set<PhaseType> opponentTurnStops;
    private final Policy policy;
    private List<String> libraryOrder;
    private boolean keepAll;
    private Map<String, String> standIns;

    public DeckController(Game game, Player player, LobbyPlayer lobbyPlayer, Set<PhaseType> opponentTurnStops, Policy policy) {
        super(game, player, lobbyPlayer);
        this.opponentTurnStops = opponentTurnStops;
        this.policy = policy;
        if (policy != null) {
            getAi().setPlayPolicy(policy);
        }
    }

    public void setLibraryOrder(List<String> order, boolean keepAll) {
        this.libraryOrder = order;
        this.keepAll = keepAll;
    }

    /**
     * Shuffles a deck and a version of it with cards swapped the same way: each shuffle is applied to the library
     * sorted by name, a new card sorting under the name of the card it replaces, so it lands where that card would.
     */
    public void pairShuffles(Map<String, String> standIns) {
        this.standIns = standIns;
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        if (libraryOrder == null) {
            return standIns == null ? super.cheatShuffle(list) : pairedShuffle(list);
        }
        List<String> order = libraryOrder;
        libraryOrder = null;
        CardCollection pool = new CardCollection(list);
        CardCollection ordered = new CardCollection();
        for (String name : order) {
            Card match = pool.stream().filter(c -> c.getName().equals(name) || name.startsWith(c.getName() + " //")).findFirst().orElse(null);
            if (match == null) {
                System.err.println("library order: no card named " + name);
                continue;
            }
            pool.remove(match);
            ordered.add(match);
        }
        ordered.addAll(pool);
        return ordered;
    }

    private CardCollectionView pairedShuffle(CardCollectionView shuffled) {
        List<Card> before = new ArrayList<>(player.getCardsIn(ZoneType.Library));
        // one copy of a new card stands in for the card it replaces, the first by id so it stays the same copy
        Map<String, String> pending = new HashMap<>(standIns);
        Map<Card, String> key = new IdentityHashMap<>();
        before.stream().sorted(Comparator.comparingInt(Card::getId)).forEach(c -> {
            String standIn = pending.remove(c.getName());
            key.put(c, standIn != null ? standIn : c.getName());
        });
        List<Card> sorted = new ArrayList<>(before);
        sorted.sort(Comparator.comparing(key::get));
        Map<Card, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < before.size(); i++) {
            position.put(before.get(i), i);
        }
        CardCollection paired = new CardCollection();
        for (Card c : shuffled) {
            Integer i = position.get(c);
            if (i == null) {
                return shuffled;
            }
            paired.add(sorted.get(i));
        }
        return paired;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> chosen = policy == null ? null : policy.chooseModes(sa, possible, min, num);
        return chosen != null ? chosen : super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        AttackPlan plan = policy == null ? AttackPlan.FORGE : policy.attackPlan();
        if (plan == AttackPlan.FORGE) {
            super.declareAttackers(attacker, combat);
            return;
        }
        List<Player> defenders = combat.getDefenders().stream()
                .filter(d -> d instanceof Player p && !p.hasLost()).map(d -> (Player) d).toList();
        if (defenders.isEmpty()) {
            return;
        }
        Map<Player, Integer> lifeLeft = new HashMap<>();
        defenders.forEach(p -> lifeLeft.put(p, p.getLife()));
        Comparator<Player> order = plan.order(attacker, defenders, lifeLeft);
        List<Card> attackers = attacker.getCreaturesInPlay().stream().filter(CombatUtil::canAttack)
                .sorted(Comparator.comparingInt((Card c) -> -ComputerUtilCombat.damageIfUnblocked(c, defenders.get(0), combat, false)))
                .toList();
        for (Card c : attackers) {
            Player target = defenders.stream().filter(p -> lifeLeft.get(p) > 0 && CombatUtil.canAttack(c, p))
                    .min(order).orElse(null);
            if (target == null) {
                continue;
            }
            combat.addAttacker(c, target);
            lifeLeft.merge(target, -ComputerUtilCombat.damageIfUnblocked(c, target, combat, false), Integer::sum);
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (keepAll) {
            return true;
        }
        if (policy == null || cardsToReturn >= 2) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        return policy.keeps(player);
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
            DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        if (policy != null && policy.ordersLibraryTop() && destination == ZoneType.Library && origin.contains(ZoneType.Library)
                && drawsBeforeAttack() == 0 && !fetchList.isEmpty() && fetchList.stream().allMatch(c -> c.getOwner() == player)) {
            Card best = policy.orderForTop(new ArrayList<>(fetchList)).get(0);
            if (policy.wantedOnTop(best)) {
                return best;
            }
        }
        if (policy != null && !isOptional && origin.size() == 1 && origin.get(0) == ZoneType.Hand && destination != ZoneType.Battlefield
                && !fetchList.isEmpty() && fetchList.stream().allMatch(c -> c.getOwner() == player)) {
            List<Card> picked = policy.worstCards(player, new ArrayList<>(fetchList), 1);
            if (picked != null && !picked.isEmpty()) {
                return picked.get(0);
            }
        }
        return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        CardCollectionView ordered = super.orderMoveToZoneList(cards, destinationZone, source);
        if (policy == null || !policy.ordersLibraryTop() || destinationZone != ZoneType.Library || ordered.isEmpty()
                || ordered.getFirst().getOwner() != player || !orderedMoveToTopOfLibrary(destinationZone, source)) {
            return ordered;
        }
        // cards going to the top of the library come back bottom first
        List<Card> topFirst = new ArrayList<>(ordered);
        Collections.reverse(topFirst);
        List<Card> wanted = policy.orderForTop(topFirst, drawsBeforeAttack());
        Collections.reverse(wanted);
        return new CardCollection(wanted);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return keepWantedOnTop(topN, super.arrangeForScry(topN));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return keepWantedOnTop(topN, super.arrangeForSurveil(topN));
    }

    private ImmutablePair<CardCollection, CardCollection> keepWantedOnTop(CardCollection topN,
            ImmutablePair<CardCollection, CardCollection> stock) {
        if (policy == null || !policy.ordersLibraryTop()) {
            return stock;
        }
        List<Card> kept = new ArrayList<>(topN.stream().filter(policy::wantedOnTop).toList());
        if (stock.getLeft() != null) {
            stock.getLeft().stream().filter(c -> !kept.contains(c)).forEach(kept::add);
        }
        CardCollection away = new CardCollection(topN.stream().filter(c -> !kept.contains(c)).toList());
        return ImmutablePair.of(new CardCollection(policy.orderForTop(kept, drawsBeforeAttack())), away);
    }

    // 0 between the deck's own draw step and its attack, when an attack trigger sees the top card first
    private int drawsBeforeAttack() {
        PhaseHandler ph = getGame().getPhaseHandler();
        PhaseType phase = ph.getPhase();
        return ph.isPlayerTurn(player) && phase != null && !phase.isBefore(PhaseType.DRAW)
                && !phase.isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS) ? 0 : 1;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max,
            boolean isOptional, Map<String, Object> params) {
        if (policy != null && sa != null && max > 0 && !sourceList.isEmpty()
                && sourceList.stream().allMatch(c -> c.isInZone(ZoneType.Hand) && c.getOwner() == player)
                && (sa.getApi() == ApiType.ChangeZone || sa.getApi() == ApiType.Discard)) {
            List<Card> picked = policy.worstCards(player, new ArrayList<>(sourceList), min == 0 ? 0 : max);
            if (picked != null && picked.size() >= min) {
                return new CardCollection(picked);
            }
        }
        return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (policy != null && sa != null && !isOptional && (sa.getApi() == ApiType.ChangeZone || sa.getApi() == ApiType.Discard)) {
            List<Card> hand = new ArrayList<>();
            for (T option : optionList) {
                if (option instanceof Card c && c.isInZone(ZoneType.Hand) && c.getOwner() == player) {
                    hand.add(c);
                }
            }
            if (!hand.isEmpty() && hand.size() == optionList.size()) {
                List<Card> picked = policy.worstCards(player, hand, 1);
                if (picked != null && !picked.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    T chosen = (T) picked.get(0);
                    return chosen;
                }
            }
        }
        if (policy != null && sa != null && sa.getApi() == ApiType.Clone && sa.getHostCard() != null) {
            List<Card> cards = new ArrayList<>();
            for (T option : optionList) {
                if (option instanceof Card c) {
                    cards.add(c);
                }
            }
            Card pick = policy.chooseCopy(sa.getHostCard(), cards);
            if (pick != null) {
                @SuppressWarnings("unchecked")
                T chosen = (T) pick;
                return chosen;
            }
        }
        return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        if (policy != null && policy.paysLifeForUntappedLands() && sa.getApi() == ApiType.Tap && sa.hasParam("ETB")
                && sa.getHostCard() != null && sa.getHostCard().isLand()
                && cost.getCostParts().stream().allMatch(part -> part instanceof CostPayLife)
                && ComputerUtilCost.canPayCost(cost, sa, player, true)) {
            return new CostPayment(cost, sa).payComputerCosts(new AiCostDecision(player, sa, true));
        }
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
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
