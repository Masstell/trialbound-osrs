package mvdicarlo.crabmanmode.clog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.events.ClogDataLoaded;
import mvdicarlo.crabmanmode.trial.BossTierRegistry;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.StructComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;

/**
 * Reads the collection log structure (tabs, pages, item ids) from the game
 * cache and provides the lookup indices every other service uses. Loaded once
 * per client run on the client thread; contents are static per game build.
 */
@Slf4j
@Singleton
public class ClogDataService {
    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final EventBus eventBus;
    private final BossTierRegistry tierRegistry;

    private volatile boolean loaded;
    private volatile Set<Integer> allClogItemIds = Collections.emptySet();
    private volatile Map<String, ClogPage> pagesByNormalizedName = Collections.emptyMap();
    private volatile Map<Integer, Set<ClogPage>> itemToPages = Collections.emptyMap();
    private volatile Map<String, List<Integer>> idsByNormalizedItemName = Collections.emptyMap();

    @Inject
    public ClogDataService(Client client, ClientThread clientThread, ItemManager itemManager, EventBus eventBus,
            BossTierRegistry tierRegistry) {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.eventBus = eventBus;
        this.tierRegistry = tierRegistry;
    }

    /** Queues the cache read; retries until the client is past the login screen. */
    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        clientThread.invoke(() -> {
            if (loaded) {
                return true;
            }
            if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState()) {
                return false; // cache not ready yet; invoke() retries next tick
            }
            try {
                loadFromCache();
            } catch (Exception e) {
                log.error("Failed to read collection log data from cache", e);
                return true; // don't retry-loop on a hard failure
            }
            return true;
        });
    }

    private void loadFromCache() {
        Map<Integer, Integer> replacements = new HashMap<>();
        EnumComposition replacementEnum = client.getEnum(ClogCacheIds.ENUM_ITEM_REPLACEMENTS);
        int[] keys = replacementEnum.getKeys();
        int[] vals = replacementEnum.getIntVals();
        for (int i = 0; i < keys.length; i++) {
            replacements.put(keys[i], vals[i]);
        }

        Set<Integer> itemIds = new HashSet<>();
        Map<String, ClogPage> pages = new HashMap<>();
        Map<Integer, Set<ClogPage>> byItem = new HashMap<>();

        EnumComposition tabs = client.getEnum(ClogCacheIds.ENUM_TOP_LEVEL_TABS);
        int[] tabStructs = tabs.getIntVals();
        for (int tabIndex = 0; tabIndex < tabStructs.length; tabIndex++) {
            ClogTab tab = ClogTab.byIndex(tabIndex);
            StructComposition tabStruct = client.getStructComposition(tabStructs[tabIndex]);
            EnumComposition pageEnum = client.getEnum(tabStruct.getIntValue(ClogCacheIds.PARAM_TAB_PAGES_ENUM));
            for (int pageStructId : pageEnum.getIntVals()) {
                StructComposition pageStruct = client.getStructComposition(pageStructId);
                String pageName = pageStruct.getStringValue(ClogCacheIds.PARAM_PAGE_NAME);
                EnumComposition itemEnum = client.getEnum(pageStruct.getIntValue(ClogCacheIds.PARAM_PAGE_ITEMS_ENUM));

                Set<Integer> pageItems = new LinkedHashSet<>();
                for (int itemId : itemEnum.getIntVals()) {
                    pageItems.add(replacements.getOrDefault(itemId, itemId));
                }
                ClogPage page = new ClogPage(pageName, ClogText.normalize(pageName), tab,
                        Collections.unmodifiableSet(pageItems));
                pages.put(page.getNormalizedName(), page);
                for (int itemId : page.getItemIds()) {
                    itemIds.add(itemId);
                    byItem.computeIfAbsent(itemId, k -> new HashSet<>()).add(page);
                }
            }
        }

        Map<String, List<Integer>> byName = new HashMap<>();
        for (int itemId : itemIds) {
            String itemName = ClogText.normalize(itemManager.getItemComposition(itemId).getName());
            byName.computeIfAbsent(itemName, k -> new ArrayList<>(1)).add(itemId);
        }
        byName.values().forEach(Collections::sort);

        allClogItemIds = Collections.unmodifiableSet(itemIds);
        pagesByNormalizedName = Collections.unmodifiableMap(pages);
        itemToPages = Collections.unmodifiableMap(byItem);
        idsByNormalizedItemName = Collections.unmodifiableMap(byName);
        loaded = true;
        log.info("Collection log data loaded: {} pages, {} items", pages.size(), itemIds.size());

        tierRegistry.validateAgainstPages(name -> pagesByNormalizedName.containsKey(ClogText.normalize(name)));
        eventBus.post(new ClogDataLoaded());
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isClogItem(int canonicalItemId) {
        return allClogItemIds.contains(canonicalItemId);
    }

    public Set<Integer> getAllClogItemIds() {
        return allClogItemIds;
    }

    public Collection<ClogPage> getAllPages() {
        return pagesByNormalizedName.values();
    }

    public Optional<ClogPage> getPage(String pageName) {
        return Optional.ofNullable(pagesByNormalizedName.get(ClogText.normalize(pageName)));
    }

    public Set<ClogPage> getPagesForItem(int canonicalItemId) {
        return itemToPages.getOrDefault(canonicalItemId, Collections.emptySet());
    }

    /**
     * Resolves an NPC or loot-event source name to a page: exact page-name
     * match first, then the tier file's alias table. Empty when unattributable.
     */
    public Optional<ClogPage> resolveSourceName(String npcOrEventName) {
        String normalized = ClogText.normalize(npcOrEventName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        ClogPage direct = pagesByNormalizedName.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        return tierRegistry.resolveAlias(normalized)
                .map(page -> pagesByNormalizedName.get(ClogText.normalize(page)));
    }

    /** Clog item ids whose item name matches (normalized); ascending id order. */
    public List<Integer> getIdsForItemName(String itemName) {
        return idsByNormalizedItemName.getOrDefault(ClogText.normalize(itemName), Collections.emptyList());
    }
}
