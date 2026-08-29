package mvdicarlo.crabmanmode.store;

public enum GritReason {
    TRIAL_DROP, PURCHASE, ADMIN,
    /** Compensation for a purchase spend whose unlock lost a merge conflict. */
    REFUND
}
