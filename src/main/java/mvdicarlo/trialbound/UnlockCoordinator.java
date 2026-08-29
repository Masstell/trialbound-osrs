package mvdicarlo.trialbound;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.trialbound.events.ClogDropResolved;
import mvdicarlo.trialbound.store.GroupStateService;
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
        // Any drop of a locked clog item unlocks it - including duplicates of
        // items obtained before the group started (the slot is still locked
        // even though the personal clog already has it).
        if (!sessionState.isActive() || groupState.isUnlocked(event.getItemId())) {
            return;
        }
        String itemName = itemManager.getItemComposition(event.getItemId()).getName();
        groupState.unlockDrop(event.getItemId(), itemName);
    }
}
