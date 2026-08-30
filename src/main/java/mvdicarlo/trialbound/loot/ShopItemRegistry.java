package mvdicarlo.trialbound.loot;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads shop_items.json: the clog items purchasable from game shops (including
 * minigame reward shops). These are the ONLY items the possession-gain path
 * may unlock without a correlated kill; every other clog item requires a real
 * loot event, a kill credit, or the first-time clog notification. Generated
 * by tools/generate_shop_items.py - fix coverage there, never by hand.
 */
@Slf4j
@Singleton
public class ShopItemRegistry {
    private static final String RESOURCE = "/shop_items.json";

    private final Set<Integer> shopItemIds = new HashSet<>();

    @Inject
    public ShopItemRegistry(Gson gson) {
        ShopFile file;
        try (InputStream in = ShopItemRegistry.class.getResourceAsStream(RESOURCE)) {
            file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ShopFile.class);
        } catch (Exception e) {
            log.error("Failed to load {}", RESOURCE, e);
            file = new ShopFile();
        }
        for (ShopEntry entry : file.items) {
            shopItemIds.add(entry.id);
        }
        log.debug("Loaded {} shop-purchasable clog items", shopItemIds.size());
    }

    /** True if this canonical item id is sold in some game shop. */
    public boolean isShopItem(int canonicalItemId) {
        return shopItemIds.contains(canonicalItemId);
    }

    private static class ShopFile {
        List<ShopEntry> items = new ArrayList<>();
    }

    private static class ShopEntry {
        int id;
    }
}
