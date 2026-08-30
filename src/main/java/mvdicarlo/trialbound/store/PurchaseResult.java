package mvdicarlo.trialbound.store;

public enum PurchaseResult {
    SUCCESS, ALREADY_UNLOCKED, INSUFFICIENT_GRIT, NOT_READY,
    /** The purchase events lost a deterministic merge and the item stayed locked. */
    CONFLICT
}
