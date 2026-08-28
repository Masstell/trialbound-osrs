package mvdicarlo.crabmanmode.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/** Shared "is this item locked for us right now" predicate. */
@Singleton
public class LockedItemHelper {
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final GroupStateService groupState;
    private final ItemManager itemManager;

    @Inject
    public LockedItemHelper(SessionState sessionState, ClogDataService clogData, GroupStateService groupState,
            ItemManager itemManager) {
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.itemManager = itemManager;
    }

    /** True when enforcement applies at all (active character, data loaded). */
    public boolean enforcementActive() {
        return sessionState.isActive() && clogData.isLoaded();
    }

    /**
     * The id lock state is judged by: the canonical item itself if it is a
     * clog item, else its variation base (so ornament-kit and degraded
     * variants - e.g. Twisted ancestral - inherit the base item's lock).
     */
    public int lockCheckId(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        if (clogData.isClogItem(canonical)) {
            return canonical;
        }
        int base = ItemVariationMapping.map(canonical);
        if (base != canonical && clogData.isClogItem(base)) {
            return base;
        }
        return canonical;
    }

    /** True when the item (or its variation base) is a clog item the group has not unlocked. */
    public boolean isLocked(int itemId) {
        int id = lockCheckId(itemId);
        return clogData.isClogItem(id) && !groupState.isUnlocked(id);
    }
}
