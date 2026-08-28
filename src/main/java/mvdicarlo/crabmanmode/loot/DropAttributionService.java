package mvdicarlo.crabmanmode.loot;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.clog.ClogPage;
import mvdicarlo.crabmanmode.clog.ClogText;
import mvdicarlo.crabmanmode.clog.ObtainedSyncService;
import mvdicarlo.crabmanmode.events.ClogDropResolved;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Normalizes every loot source (server NPC loot, ground-loot fallback, reward
 * chests, Loot Tracker events) plus the name-only clog notifications into
 * {@link ClogDropResolved} events: exactly one per unique clog item id per
 * acquisition, attributed to a collection log page when the source resolves.
 */
@Slf4j
@Singleton
public class DropAttributionService {
    /** NpcLootReceived within this many ticks of ServerNpcLoot for the same NPC is a duplicate. */
    private static final int SERVER_LOOT_DEDUPE_TICKS = 2;
    /** Window in which a chat/popup notification is correlated against loot. */
    private static final int CORRELATION_WINDOW_TICKS = 4;
    /** Retention for the recently-resolved item map. */
    private static final int RESOLVED_RETENTION_TICKS = 10;
    /**
     * How long a page-linked kill can attribute possession gains of that
     * page's items. Generous, because causation is enforced structurally:
     * each kill is CONSUMED by the acquisition it attributes (one kill, one
     * reward), so a lingering kill cannot license endless crafted copies.
     */
    private static final int KILL_ATTRIBUTION_TICKS = 250;
    /** A chest reopened with the identical item multiset within this window is ignored. */
    private static final long CHEST_FINGERPRINT_RETENTION_MS = 10 * 60_000L;

    private final Client client;
    private final ItemManager itemManager;
    private final EventBus eventBus;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final ObtainedSyncService obtainedSync;

    private final Map<String, Integer> recentServerLootTicks = new HashMap<>();
    private final Map<Integer, Integer> recentlyResolved = new HashMap<>();
    /** Page name -> ticks of recent kills of that page's NPCs (one credit per kill). */
    private final Map<String, ArrayDeque<Integer>> recentKillPages = new HashMap<>();
    private final Map<String, ChestFingerprint> chestFingerprints = new HashMap<>();
    private final List<PendingNotification> pendingNotifications = new ArrayList<>();

    @Inject
    public DropAttributionService(Client client, ItemManager itemManager, EventBus eventBus,
            SessionState sessionState, ClogDataService clogData, ObtainedSyncService obtainedSync) {
        this.client = client;
        this.itemManager = itemManager;
        this.eventBus = eventBus;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.obtainedSync = obtainedSync;
    }

    private boolean ready() {
        return sessionState.isActive() && clogData.isLoaded();
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (!ready() || event.getComposition() == null) {
            return;
        }
        String name = event.getComposition().getName();
        recentServerLootTicks.put(ClogText.normalize(name), client.getTickCount());
        processLoot(name, canonicalizeStacks(event.getItems()));
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        if (!ready() || event.getNpc() == null) {
            return;
        }
        String name = event.getNpc().getName();
        Integer serverTick = recentServerLootTicks.get(ClogText.normalize(name));
        if (serverTick != null && client.getTickCount() - serverTick <= SERVER_LOOT_DEDUPE_TICKS) {
            return; // already handled server-authoritatively
        }
        processLoot(name, canonicalizeStacks(event.getItems()));
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        ChestLootSource.Source source = ChestLootSource.byGroupId(event.getGroupId());
        if (source == null || !ready()) {
            return;
        }
        ItemContainer container = client.getItemContainer(source.getContainerId());
        if (container == null) {
            return;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        List<Integer> fingerprintParts = new ArrayList<>();
        for (Item item : container.getItems()) {
            if (item.getId() > 0 && item.getQuantity() > 0) {
                ids.add(itemManager.canonicalize(item.getId()));
                fingerprintParts.add(item.getId());
                fingerprintParts.add(item.getQuantity());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int hash = Arrays.hashCode(fingerprintParts.stream().mapToInt(Integer::intValue).toArray());
        long now = System.currentTimeMillis();
        ChestFingerprint previous = chestFingerprints.get(source.getSourceName());
        if (previous != null && previous.hash == hash && now - previous.timeMs < CHEST_FINGERPRINT_RETENTION_MS) {
            return; // same chest reopened
        }
        chestFingerprints.put(source.getSourceName(), new ChestFingerprint(hash, now));
        processLoot(source.getSourceName(), ids);
    }

    /**
     * Loot Tracker events cover non-NPC, non-chest sources (Wintertodt crates,
     * Tempoross pool, the Gauntlet, pickpockets...). NPC records are excluded
     * (handled server-authoritatively) as are chests we read ourselves.
     */
    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!ready() || event.getType() == LootRecordType.NPC) {
            return;
        }
        if (ChestLootSource.coversSourceName(event.getName())) {
            return;
        }
        processLoot(event.getName(), canonicalizeStacks(event.getItems()));
    }

    /**
     * Acquisitions detected outside loot events (game shop purchases). Runs
     * the standard pipeline: clog filter, dedupe, unlock; no page = no grit.
     */
    public void onExternalAcquisition(String sourceName, Collection<Integer> canonicalItemIds) {
        if (!ready()) {
            return;
        }
        processLoot(sourceName, canonicalItemIds);
    }

    /** Name-only detection from the clog chat message or popup. */
    public void onClogNotification(String itemName) {
        if (!ready()) {
            return;
        }
        int tick = client.getTickCount();
        List<Integer> ids = clogData.getIdsForItemName(itemName);
        for (int id : ids) {
            Integer resolvedTick = recentlyResolved.get(id);
            if (resolvedTick != null && tick - resolvedTick <= CORRELATION_WINDOW_TICKS) {
                return; // the loot path already emitted this acquisition
            }
        }
        for (PendingNotification pending : pendingNotifications) {
            if (pending.itemName.equalsIgnoreCase(itemName)) {
                return; // chat + popup double-fire
            }
        }
        pendingNotifications.add(new PendingNotification(itemName, tick));
    }

    /**
     * Rewards placed directly in the inventory (Shayzien ring armour) have no
     * loot event; remembering page-linked kills lets the possession path
     * attribute them (and pay trial grit) via recency.
     */
    @Subscribe
    public void onActorDeath(net.runelite.api.events.ActorDeath event) {
        if (!(event.getActor() instanceof net.runelite.api.NPC) || !ready()) {
            return;
        }
        String name = event.getActor().getName();
        clogData.resolveSourceName(name).ifPresent(page -> recentKillPages
                .computeIfAbsent(page.getName(), k -> new ArrayDeque<>())
                .addLast(client.getTickCount()));
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        int tick = client.getTickCount();
        recentlyResolved.values().removeIf(t -> tick - t > RESOLVED_RETENTION_TICKS);
        recentServerLootTicks.values().removeIf(t -> tick - t > RESOLVED_RETENTION_TICKS);
        for (ArrayDeque<Integer> ticks : recentKillPages.values()) {
            while (!ticks.isEmpty() && tick - ticks.peekFirst() > KILL_ATTRIBUTION_TICKS) {
                ticks.removeFirst();
            }
        }
        recentKillPages.values().removeIf(ArrayDeque::isEmpty);

        Iterator<PendingNotification> it = pendingNotifications.iterator();
        while (it.hasNext()) {
            PendingNotification pending = it.next();
            if (tick - pending.tick < CORRELATION_WINDOW_TICKS) {
                continue;
            }
            it.remove();
            resolvePendingNotification(pending, tick);
        }
    }

    private void resolvePendingNotification(PendingNotification pending, int tick) {
        List<Integer> ids = clogData.getIdsForItemName(pending.itemName);
        if (ids.isEmpty()) {
            log.warn("Clog notification for '{}' matches no collection log item", pending.itemName);
            return;
        }
        for (int id : ids) {
            if (recentlyResolved.containsKey(id)) {
                return; // loot arrived inside the window and already emitted
            }
        }
        int itemId = ids.stream().filter(id -> !obtainedSync.isObtained(id)).findFirst().orElse(ids.get(0));
        if (ids.size() > 1) {
            log.debug("Ambiguous clog item name '{}' -> ids {}; using {}", pending.itemName, ids, itemId);
        }
        emit(itemId, null, null, tick);
    }

    /** Source name for inventory-gain acquisitions (shops, direct rewards). */
    public static final String ACQUISITION_SOURCE = "Acquisition";

    private void processLoot(String sourceName, Collection<Integer> itemIds) {
        Optional<ClogPage> page = clogData.resolveSourceName(sourceName);
        int tick = client.getTickCount();
        for (int itemId : itemIds) {
            if (!clogData.isClogItem(itemId)) {
                continue;
            }
            Integer resolvedTick = recentlyResolved.get(itemId);
            if (resolvedTick != null && tick - resolvedTick <= SERVER_LOOT_DEDUPE_TICKS) {
                continue; // duplicate event for the same acquisition
            }
            String pageName = page.map(ClogPage::getName).orElse(null);
            if (pageName == null) {
                if (ACQUISITION_SOURCE.equals(sourceName)) {
                    // Possession gains attribute to a recent page-linked kill,
                    // but only when the item belongs to that page (so smithing
                    // Shayzien armour near the ring cannot print grit).
                    pageName = recentKillPageContaining(itemId, tick);
                } else {
                    // A real loot event containing an item unique to ONE page
                    // is self-attributing - no alias needed.
                    Set<ClogPage> itemPages = clogData.getPagesForItem(itemId);
                    if (itemPages.size() == 1) {
                        pageName = itemPages.iterator().next().getName();
                    }
                }
            }
            emit(itemId, pageName, sourceName, tick);
        }
        if (!page.isPresent()) {
            log.debug("Loot source '{}' does not resolve to a collection log page", sourceName);
        }
    }

    /**
     * The page of a recent kill whose item set contains this item, or null.
     * A match CONSUMES one kill credit - one kill attributes one reward, so
     * lingering kills cannot license endless crafted copies.
     */
    private String recentKillPageContaining(int itemId, int tick) {
        for (Map.Entry<String, ArrayDeque<Integer>> entry : recentKillPages.entrySet()) {
            ArrayDeque<Integer> ticks = entry.getValue();
            if (ticks.isEmpty() || tick - ticks.peekLast() > KILL_ATTRIBUTION_TICKS) {
                continue;
            }
            Optional<ClogPage> page = clogData.getPage(entry.getKey());
            if (page.isPresent() && page.get().getItemIds().contains(itemId)) {
                ticks.removeLast();
                return entry.getKey();
            }
        }
        return null;
    }

    private void emit(int itemId, String pageName, String sourceName, int tick) {
        boolean newlyObtained = !obtainedSync.isObtained(itemId);
        recentlyResolved.put(itemId, tick);
        if (newlyObtained) {
            obtainedSync.markObtainedLocally(itemId);
        }
        eventBus.post(new ClogDropResolved(itemId, pageName, sourceName, newlyObtained, tick));
    }

    private Set<Integer> canonicalizeStacks(Collection<ItemStack> stacks) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (ItemStack stack : stacks) {
            if (stack.getId() > 0 && stack.getQuantity() > 0) {
                ids.add(itemManager.canonicalize(stack.getId()));
            }
        }
        return ids;
    }

    @Value
    private static class ChestFingerprint {
        int hash;
        long timeMs;
    }

    @Value
    private static class PendingNotification {
        String itemName;
        int tick;
    }
}
