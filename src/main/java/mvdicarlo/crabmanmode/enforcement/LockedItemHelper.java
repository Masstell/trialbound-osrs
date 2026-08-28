package mvdicarlo.crabmanmode.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import net.runelite.client.game.ItemManager;

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

    /** True when the (canonicalized) item is a clog item the group has not unlocked. */
    public boolean isLocked(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        return clogData.isClogItem(canonical) && !groupState.isUnlocked(canonical);
    }
}
