Superseded by MostCromulent's #12357 (open, under review), which covers this and more. Kept for reference, do not open.

Branch: superseded/ai-combo-mana-count (1 commit on master)
Title: AI: count a Combo mana ability as one mana

getAvailableManaEstimate and ManaAi work out how much mana an ability makes by splitting its Produced$ param on spaces. For "Produced$ Combo U B" that gives 3, so a Talisman or a pain land counts as three mana, and a Shadowmoor filter land ("Combo W B" with Amount 2) counts as six. 540 card scripts use Produced$ Combo.

The estimate feeds about twenty AI decisions: how much free mana it has when attacking and blocking (AiAttackController, AiBlockController), what it thinks it can still cast this turn (AiController, ComputerUtil), X sizing, and a few card-specific checks. A board of Island + Talisman of Dominance reads as 4 mana instead of 2.

Fix: a Combo ability counts as one mana per Amount, using the existing AbilityManaPart.isComboMana(). The ComputerUtilMana side guards against a mana part that sits on a sub-ability.

Tested: the ai, game and game simulation test sets pass (442). No new test, since the estimate has none today and the change is two lines. Happy to add one if you'd like.

Written with Claude Code.
