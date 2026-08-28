package mvdicarlo.crabmanmode;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.events.ClogDropResolved;
import mvdicarlo.crabmanmode.store.GroupStateService;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Turns resolved clog drops into group unlocks. Announcements are driven off
 * the GroupStateService listener in the plugin, so drops, purchases, and other
 * members' unlocks all announce through one path.
 */
@Singleton
public class UnlockCoordinator {
    private final SessionState sessionState;
    private final GroupStateService groupState;
    private final ItemManager itemManager;

    @Inject
    public UnlockCoordinator(SessionState sessionState, GroupStateService groupState, ItemManager itemManager) {
        this.sessionState = sessionState;
        this.groupState = groupState;
        this.itemManager = itemManager;
    }

    @Subscribe
    public void onClogDropResolved(ClogDropResolved event) {
        if (!sessionState.isActive() || !event.isNewlyObtained() || groupState.isUnlocked(event.getItemId())) {
            return;
        }
        String itemName = itemManager.getItemComposition(event.getItemId()).getName();
        groupState.unlockDrop(event.getItemId(), itemName);
    }
}
