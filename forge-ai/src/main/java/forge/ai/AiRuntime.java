package forge.ai;

public final class AiRuntime {
    public static final boolean INLINE_EVALUATION = Boolean.getBoolean("forge.ai.inlineEvaluation");
    public static final boolean SEQUENTIAL_ATTACKS = Boolean.getBoolean("forge.ai.sequentialAttacks");

    private AiRuntime() {
    }
}
