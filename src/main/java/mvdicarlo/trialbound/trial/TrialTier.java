package mvdicarlo.trialbound.trial;

/**
 * Difficulty tier of a collection log source. Rank orders tiers so an item
 * appearing on several pages is priced by its hardest source.
 */
public enum TrialTier {
    NON_BOSS(0, "Non-boss"),
    EASY(1, "Easy"),
    MEDIUM(2, "Medium"),
    HARD(3, "Hard"),
    RAID(4, "Raid");

    private final int rank;
    private final String displayName;

    TrialTier(int rank, String displayName) {
        this.rank = rank;
        this.displayName = displayName;
    }

    public int getRank() {
        return rank;
    }

    public String getDisplayName() {
        return displayName;
    }
}
