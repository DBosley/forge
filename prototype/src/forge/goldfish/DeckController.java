package forge.goldfish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
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
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import forge.goldfish.policy.Policy;

public final class DeckController extends PlayerControllerAi {
    private final Set<PhaseType> opponentTurnStops;
    private final Policy policy;
    private List<String> libraryOrder;
    private boolean keepAll;

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

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        if (libraryOrder == null) {
            return super.cheatShuffle(list);
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
