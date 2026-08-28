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
 * Acquisition-by-possession: gaining a clog item in your inventory unlocks it
 * (shops - including custom minigame shops like Petrified Pete's - reward
 * claims, combining pieces), EXCEPT in contexts where a gain is not "earning
 * it": trade windows, the GE, bank/deposit/seed vault withdrawals, death
 * reclaims, and ground pickups (drop-trading). Exclusions fail open: a missed
 * one unlocks too eagerly rather than blocking play.
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

    /** Gains within this many ticks of a ground "Take" are pickups, not earnings. */
    private static final int TAKE_WINDOW_TICKS = 3;

    private final Client client;
    private final ItemManager itemManager;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final DropAttributionService attribution;
    private final net.runelite.client.callback.ClientThread clientThread;

    private final Map<Integer, Integer> inventorySnapshot = new HashMap<>();
    private final Set<Integer> openExcludedInterfaces = new HashSet<>();
    private boolean snapshotValid;
    private int lastTakeTick = -100;

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
        if ("Take".equals(event.getMenuOption())) {
            lastTakeTick = client.getTickCount();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INV) {
            return;
        }
        Map<Integer, Integer> current = countInventory(event.getItemContainer());
        boolean hadBaseline = snapshotValid;
        boolean excluded = !openExcludedInterfaces.isEmpty()
                || client.getTickCount() - lastTakeTick <= TAKE_WINDOW_TICKS;

        if (hadBaseline && !excluded && sessionState.isActive() && clogData.isLoaded()) {
            Set<Integer> gained = new LinkedHashSet<>();
            for (Map.Entry<Integer, Integer> entry : current.entrySet()) {
                int itemId = entry.getKey();
                if (entry.getValue() > inventorySnapshot.getOrDefault(itemId, 0)
                        && clogData.isClogItem(itemId)) {
                    gained.add(itemId);
                }
            }
            if (!gained.isEmpty()) {
                log.info("Inventory acquisition of clog items {} (no excluded context)", gained);
                attribution.onExternalAcquisition("Acquisition", gained);
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
