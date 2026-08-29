package mvdicarlo.crabmanmode.enforcement;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.clog.ClogText;
import mvdicarlo.crabmanmode.events.ClogDataLoaded;
import net.runelite.api.Client;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Items that are not collection log entries but are crafted FROM them (Toxic
 * blowpipe from Tanzanite fang, godswords from hilts, DT2 rings from
 * vestiges...). Loaded from derived_items.json; product names are resolved to
 * item ids by scanning the item cache once after clog data loads, so the file
 * stays id-free. A product is locked while any required clog item is locked.
 */
@Slf4j
@Singleton
public class DerivedItemRegistry {
    private static final String RESOURCE = "/derived_items.json";

    private final Client client;
    private final ItemManager itemManager;
    private final ClogDataService clogData;
    private final DerivedFile file;

    /** Product item id -> required clog item ids. Built on the client thread. */
    private volatile Map<Integer, List<Integer>> requirementsByProduct = Collections.emptyMap();

    @Inject
    public DerivedItemRegistry(Client client, ItemManager itemManager, ClogDataService clogData, Gson gson) {
        this.client = client;
        this.itemManager = itemManager;
        this.clogData = clogData;
        DerivedFile parsed;
        try (InputStream in = DerivedItemRegistry.class.getResourceAsStream(RESOURCE)) {
            parsed = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), DerivedFile.class);
        } catch (Exception e) {
            log.error("Failed to load {}", RESOURCE, e);
            parsed = new DerivedFile();
        }
        this.file = parsed;
    }

    /** Required clog item ids for a product (canonical id), or empty. */
    public List<Integer> getRequirements(int canonicalItemId) {
        return requirementsByProduct.getOrDefault(canonicalItemId, Collections.emptyList());
    }

    @Subscribe
    public void onClogDataLoaded(ClogDataLoaded event) {
        // Client thread: resolve requirement names against the clog and scan
        // the item cache once for product-name matches.
        Map<String, List<Integer>> requiresByProductName = new HashMap<>();
        for (DerivedEntry entry : file.derived) {
            if (entry == null || entry.product == null || entry.requires == null) {
                continue;
            }
            List<Integer> required = new ArrayList<>();
            for (String name : entry.requires) {
                List<Integer> ids = clogData.getIdsForItemName(name);
                if (ids.isEmpty()) {
                    log.warn("derived_items.json requirement '{}' matches no collection log item", name);
                } else {
                    required.add(ids.get(0));
                }
            }
            if (!required.isEmpty()) {
                requiresByProductName.put(ClogText.normalize(entry.product), Collections.unmodifiableList(required));
            }
        }

        Map<Integer, List<Integer>> byProduct = new HashMap<>();
        int itemCount = client.getItemCount();
        for (int id = 0; id < itemCount; id++) {
            String name = itemManager.getItemComposition(id).getName();
            if (name == null || name.equals("null")) {
                continue;
            }
            String normalized = ClogText.normalize(name);
            List<Integer> requires = requiresByProductName.get(normalized);
            if (requires == null) {
                // Also match parenthesised variants: "Toxic blowpipe (empty)".
                int paren = name.indexOf(" (");
                if (paren > 0) {
                    requires = requiresByProductName.get(ClogText.normalize(name.substring(0, paren)));
                }
            }
            // Clog-item products are included too: a refined clog identity
            // (Onyx) carries the recipe that lets crafting it from unlocked
            // clog items (Uncut onyx) count as unlocked in LockedItemHelper.
            if (requires != null) {
                // A single-ingredient "recipe" whose ingredient shares the
                // product's variation family is a charge transition (Uncharged
                // trident -> Trident of the seas), not a real derivation; the
                // family scan in LockedItemHelper covers those. Multi-
                // ingredient recipes keep their same-family requirement (the
                // (e) tridents still need the Kraken tentacle AND a trident).
                if (requires.size() == 1
                        && ItemVariationMapping.map(requires.get(0)) == ItemVariationMapping.map(id)) {
                    continue;
                }
                byProduct.put(id, requires);
            }
        }
        requirementsByProduct = Collections.unmodifiableMap(byProduct);
        log.info("Derived item mapping: {} product ids across {} recipes", byProduct.size(),
                requiresByProductName.size());
    }

    private static class DerivedFile {
        int version;
        List<DerivedEntry> derived = new ArrayList<>();
    }

    private static class DerivedEntry {
        String product;
        List<String> requires;
    }
}
