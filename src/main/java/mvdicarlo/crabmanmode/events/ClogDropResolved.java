package mvdicarlo.crabmanmode.events;

import javax.annotation.Nullable;

import lombok.Value;

/**
 * Posted on the client thread when a collection log item has entered the
 * player's possession, after loot attribution and chat/popup correlation.
 * Exactly one event fires per unique clog item id per acquisition.
 */
@Value
public class ClogDropResolved {
    /** Canonical item id. */
    int itemId;

    /**
     * Collection log page (boss) the drop is attributed to, or null when the
     * source could not be attributed (no grit can be awarded then).
     */
    @Nullable
    String pageName;

    /** Raw source name (NPC or event) the loot came from, or null. */
    @Nullable
    String sourceName;

    /** True when the item was not in the player's obtained set before this. */
    boolean newlyObtained;

    /** Client tick the acquisition was resolved on. */
    int tick;
}
