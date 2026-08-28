package mvdicarlo.crabmanmode.loot;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Buying a collection log item from a game shop (e.g. the Volcanic Mine
 * reward shop) produces no loot event, and no clog notification when the item
 * was already obtained before - so it was invisible to the drop pipeline.
 * While a shop interface is open, inventory gains count as acquisitions.
 */
@Slf4j
@Singleton
public class ShopAcquisitionService {
    private final Client client;
    private final ItemManager itemManager;
    private final SessionState sessionState;
    private final DropAttributionService attribution;

    private boolean shopOpen;
    private final Map<Integer, Integer> inventorySnapshot = new HashMap<>();

    @Inject
    public ShopAcquisitionService(Client client, ItemManager itemManager, SessionState sessionState,
            DropAttributionService attribution) {
        this.client = client;
        this.itemManager = itemManager;
        this.sessionState = sessionState;
        this.attribution = attribution;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != InterfaceID.SHOPMAIN) {
            return;
        }
        shopOpen = true;
        snapshotInventory();
        log.info("Shop opened; inventory snapshot of {} item stacks", inventorySnapshot.size());
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() != InterfaceID.SHOPMAIN) {
            return;
        }
        shopOpen = false;
        inventorySnapshot.clear();
        log.info("Shop closed");
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!shopOpen || event.getContainerId() != InventoryID.INV || !sessionState.isActive()) {
            return;
        }
        Map<Integer, Integer> current = countInventory(event.getItemContainer());
        Set<Integer> gained = new LinkedHashSet<>();
        for (Map.Entry<Integer, Integer> entry : current.entrySet()) {
            if (entry.getValue() > inventorySnapshot.getOrDefault(entry.getKey(), 0)) {
                gained.add(entry.getKey());
            }
        }
        inventorySnapshot.clear();
        inventorySnapshot.putAll(current);
        if (!gained.isEmpty()) {
            log.info("Shop acquisition: gained item ids {}", gained);
            attribution.onExternalAcquisition("Shop", gained);
        }
    }

    private void snapshotInventory() {
        inventorySnapshot.clear();
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null) {
            inventorySnapshot.putAll(countInventory(inventory));
        }
    }

    private Map<Integer, Integer> countInventory(ItemContainer container) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Item item : container.getItems()) {
            if (item.getId() > 0 && item.getQuantity() > 0) {
                counts.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
            }
        }
        return counts;
    }
}
