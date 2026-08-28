package mvdicarlo.crabmanmode.trial;

/**
 * The five concurrent trial slots. Multipliers are fixed constants (not
 * config) so every group member computes identical grit with zero
 * coordination.
 */
public enum TrialType {
    DAILY(300, "daily", "Daily"),
    WEEKLY_EASY(200, "weekly-easy", "Weekly (Easy)"),
    WEEKLY_MEDIUM(200, "weekly-medium", "Weekly (Medium)"),
    WEEKLY_HARD(200, "weekly-hard", "Weekly (Hard)"),
    MONTHLY(150, "monthly", "Monthly");

    private final int multiplierPercent;
    private final String slotSalt;
    private final String displayName;

    TrialType(int multiplierPercent, String slotSalt, String displayName) {
        this.multiplierPercent = multiplierPercent;
        this.slotSalt = slotSalt;
        this.displayName = displayName;
    }

    public int getMultiplierPercent() {
        return multiplierPercent;
    }

    public String getSlotSalt() {
        return slotSalt;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** "3x", "2x", "1.5x" */
    public String getMultiplierLabel() {
        return multiplierPercent % 100 == 0
                ? (multiplierPercent / 100) + "x"
                : (multiplierPercent / 100.0) + "x";
    }
}
