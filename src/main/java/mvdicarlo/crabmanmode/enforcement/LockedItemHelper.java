package mvdicarlo.crabmanmode.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.List;

import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

/** Shared "is this item locked for us right now" predicate. */
@Singleton
public class LockedItemHelper {
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final GroupStateService groupState;
    private final ItemManager itemManager;
    private final DerivedItemRegistry derived;
    private final Client client;

    @Inject
    public LockedItemHelper(SessionState sessionState, ClogDataService clogData, GroupStateService groupState,
            ItemManager itemManager, DerivedItemRegistry derived, Client client) {
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.itemManager = itemManager;
        this.derived = derived;
        this.client = client;
    }

    /** True when enforcement applies at all (active character, data loaded). */
    public boolean enforcementActive() {
        return sessionState.isActive() && clogData.isLoaded();
    }

    /**
     * The id lock state is judged by: the canonical item itself if it is a
     * clog item, else its variation base (so ornament-kit and degraded
     * variants - e.g. Twisted ancestral - inherit the base item's lock).
     */
    public int lockCheckId(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        if (clogData.isClogItem(canonical)) {
            return canonical;
        }
        int base = ItemVariationMapping.map(canonical);
        if (base != canonical && clogData.isClogItem(base)) {
            return base;
        }
        return canonical;
    }

    /**
     * True when the item is locked: it (or its variation base, or the clog
     * item its name derives from - ornament kits, trouver parchment) is a
     * clog item the group has not unlocked, or it is crafted from clog items
     * of which any is still locked (blowpipe from Tanzanite fang etc.).
     * Client thread only (reads item names).
     *
     * <p>Derived requirements are checked against the item's own canonical id
     * first, before falling back to {@link #lockCheckId}'s variation-mapping
     * collapse. Some crafted items (e.g. toxic trident/staff pairs) share a
     * RuneLite variation group with just one of their required ingredients -
     * checking that collapsed id alone would silently ignore the other
     * required ingredient (e.g. Magic fang) and under-lock the item.
     */
    public boolean isLocked(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        List<Integer> requirements = derived.getRequirements(canonical);
        if (!requirements.isEmpty()) {
            for (int required : requirements) {
                if (!groupState.isUnlocked(required)) {
                    return true;
                }
            }
            return false;
        }

        int id = lockCheckId(canonical);
        if (clogData.isClogItem(id)) {
            return !groupState.isUnlocked(id);
        }
        int named = clogItemByStrippedName(id);
        return named > 0 && !groupState.isUnlocked(named);
    }

    /**
     * Maps cosmetic/holdable variants to the clog item their name derives
     * from by stripping trailing parentheticals: "Dragon full helm (g)" ->
     * "Dragon full helm", "Avernic treads (pr)(et)" -> "Avernic treads".
     * Covers ornament kits and trouver-locked forms the variation mapping
     * misses. Returns the clog item id, or -1.
     */
    private int clogItemByStrippedName(int itemId) {
        if (itemId <= 0) {
            return -1;
        }
        String name = client.getItemDefinition(itemId).getName();
        int paren;
        while ((paren = name.lastIndexOf(" (")) > 0) {
            name = name.substring(0, paren);
            List<Integer> ids = clogData.getIdsForItemName(name);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        return -1;
    }
}
