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
    private final DerivedItemRegistry derived;

    @Inject
    public LockedItemHelper(SessionState sessionState, ClogDataService clogData, GroupStateService groupState,
            ItemManager itemManager, DerivedItemRegistry derived) {
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.itemManager = itemManager;
        this.derived = derived;
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

    /**
     * True when the item is locked: it (or its variation base) is a clog item
     * the group has not unlocked, or it is crafted from clog items of which
     * any is still locked (blowpipe from Tanzanite fang etc.).
     */
    public boolean isLocked(int itemId) {
        int id = lockCheckId(itemId);
        if (clogData.isClogItem(id)) {
            return !groupState.isUnlocked(id);
        }
        for (int required : derived.getRequirements(id)) {
            if (!groupState.isUnlocked(required)) {
                return true;
            }
        }
        return false;
    }
}
