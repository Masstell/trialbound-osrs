package mvdicarlo.crabmanmode.clog;

/** Top-level collection log tabs, in the order they appear in enum 2102. */
public enum ClogTab {
    BOSSES("Bosses"),
    RAIDS("Raids"),
    CLUES("Clues"),
    MINIGAMES("Minigames"),
    OTHER("Other");

    private final String displayName;

    ClogTab(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ClogTab byIndex(int index) {
        ClogTab[] tabs = values();
        return index >= 0 && index < tabs.length ? tabs[index] : OTHER;
    }
}
