package mvdicarlo.crabmanmode.loot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.collect.ImmutableSet;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Acquisition-by-possession: detects clog items gained in the inventory
 * outside loot events (shops - including custom minigame shops like
 * Petrified Pete's - and direct-to-inventory rewards), EXCEPT in contexts
 * where a gain is a transfer, not an earning: trade windows, the GE,
 * bank/deposit/seed vault withdrawals, death reclaims, and ground pickups
 * (tracked per clicked item so distance cannot outlast the exclusion).
 * Surviving gains go to DropAttributionService, which is deny-by-default:
 * only shop-purchasable items or items claimed by a recent page-linked
 * kill actually unlock.
 */
@Slf4j
@Singleton
public class ShopAcquisitionService {
    /** Interfaces whose presence means inventory gains are transfers, not earnings. */
    private static final Set<Integer> EXCLUDED_INTERFACES = ImmutableSet.of(
            InterfaceID.TRADEMAIN, InterfaceID.TRADESIDE, InterfaceID.TRADECONFIRM,
            InterfaceID.GE_OFFERS, InterfaceID.GE_OFFERS_SIDE,
            InterfaceID.BANKMAIN, InterfaceID.BANK_DEPOSITBOX,
            InterfaceID.SEED_VAULT, InterfaceID.SEED_VAULT_DEPOSIT,
            InterfaceID.GRAVESTONE_RETRIEVAL, InterfaceID.DEATH_OFFICE, InterfaceID.DEATHKEEP);

    /**
     * How long a clicked ground item stays excluded from possession gains.
     * Tracked per item id (not a global window), so walking to a distant
     * drop cannot outlast the exclusion.
     */
    private static final int GROUND_TAKE_TICKS = 100;

    private final Client client;
    private final ItemManager itemManager;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final DropAttributionService attribution;
    private final net.runelite.client.callback.ClientThread clientThread;

    private final Map<Integer, Integer> inventorySnapshot = new HashMap<>();
    private final Set<Integer> openExcludedInterfaces = new HashSet<>();
    /** Canonical item id -> tick of a ground-item click on it (Take or telegrab). */
    private final Map<Integer, Integer> pendingGroundTakes = new HashMap<>();
    private boolean snapshotValid;

    @Inject
    public ShopAcquisitionService(Client client, ItemManager itemManager, SessionState sessionState,
            ClogDataService clogData, DropAttributionService attribution,
            net.runelite.client.callback.ClientThread clientThread) {
        this.client = client;
        this.itemManager = itemManager;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.attribution = attribution;
        this.clientThread = clientThread;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        switch (event.getGameState()) {
            case LOGIN_SCREEN:
            case HOPPING:
            case CONNECTION_LOST:
                // Never diff across a gap we did not observe. NOTE: LOADING
                // fires on every region crossing and must NOT reset the
                // baseline, or walking to a shop wipes it.
                snapshotValid = false;
                openExcludedInterfaces.clear();
                break;
            case LOGGED_IN:
                clientThread.invokeLater(this::baseline);
                break;
            default:
                break;
        }
    }

    /** Proactive baseline so the first inventory change after login is diffable. */
    private void baseline() {
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null) {
            inventorySnapshot.clear();
            inventorySnapshot.putAll(countInventory(inventory));
            snapshotValid = true;
            log.info("Inventory baseline established: {} item stacks", inventorySnapshot.size());
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (EXCLUDED_INTERFACES.contains(event.getGroupId())) {
            openExcludedInterfaces.add(event.getGroupId());
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (openExcludedInterfaces.remove(event.getGroupId())) {
            // Re-baseline: gains made while excluded must not count afterwards.
            snapshotValid = false;
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        switch (event.getMenuAction()) {
            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION: // "Take"
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
            case WIDGET_TARGET_ON_GROUND_ITEM: // telegrab
                pendingGroundTakes.put(itemManager.canonicalize(event.getId()), client.getTickCount());
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INV) {
            return;
        }
        Map<Integer, Integer> current = countInventory(event.getItemContainer());
        boolean hadBaseline = snapshotValid;
        int tick = client.getTickCount();
        pendingGroundTakes.values().removeIf(t -> tick - t > GROUND_TAKE_TICKS);

        if (hadBaseline && openExcludedInterfaces.isEmpty()
                && sessionState.isActive() && clogData.isLoaded()) {
            Set<Integer> gained = new LinkedHashSet<>();
            for (Map.Entry<Integer, Integer> entry : current.entrySet()) {
                int itemId = entry.getKey();
                if (entry.getValue() <= inventorySnapshot.getOrDefault(itemId, 0)
                        || !clogData.isClogItem(itemId)) {
                    continue;
                }
                if (pendingGroundTakes.remove(itemId) != null) {
                    // A pickup, not an earning; ground loot from own kills is
                    // already handled by the loot-event path.
                    log.debug("Ignoring ground pickup of clog item {}", itemId);
                    continue;
                }
                gained.add(itemId);
            }
            if (!gained.isEmpty()) {
                log.info("Inventory acquisition of clog items {} (no excluded context)", gained);
                attribution.onExternalAcquisition(DropAttributionService.ACQUISITION_SOURCE, gained);
            }
        }

        inventorySnapshot.clear();
        inventorySnapshot.putAll(current);
        snapshotValid = true;
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
