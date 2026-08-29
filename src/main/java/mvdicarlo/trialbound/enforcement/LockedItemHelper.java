package mvdicarlo.trialbound.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

import mvdicarlo.trialbound.SessionState;
import mvdicarlo.trialbound.clog.ClogDataService;
import mvdicarlo.trialbound.store.GroupStateService;
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
     * The clog item ids in the item's variation group that are the SAME
     * logical unlock as the item, the item itself included. Charge-state
     * families can carry several clog identities (Trident of the seas
     * (full) 11905 AND Uncharged trident 11908), and the clog identity is
     * not always the group's base id (Pharaoh's sceptre's group base is a
     * legacy pre-rework id; the clog item is the mid-group "(uncharged)"
     * form) - so the whole group is scanned, never just the base.
     *
     * <p>Not every family member qualifies: the variation mapping also
     * groups distinct unlockables - plain "Rune platebody" with the clue
     * rewards "Rune platebody (g)/(t)/(h1..h5)", "Godsword shard 1" with
     * shards 2 and 3, Shayzien tiers... {@link ItemIdentity} keeps charge
     * and degrade states together while separating those.
     * Client thread only (reads item names).
     */
    public List<Integer> clogGroupMembers(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        boolean itemIsClog = clogData.isClogItem(canonical);
        String itemName = itemIsClog ? clogData.getItemName(canonical)
                : client.getItemDefinition(canonical).getName();
        List<Integer> members = new ArrayList<>();
        if (itemIsClog) {
            members.add(canonical);
        }
        for (int variant : ItemVariationMapping.getVariations(ItemVariationMapping.map(canonical))) {
            if (variant != canonical && clogData.isClogItem(variant)
                    && ItemIdentity.sharesIdentity(itemName, clogData.getItemName(variant), itemIsClog)) {
                members.add(variant);
            }
        }
        return members;
    }

    /** Max recipe hops between clog items (Amulet of fury -> Onyx -> Uncut onyx). */
    private static final int MAX_DERIVATION_DEPTH = 4;

    /**
     * A clog identity counts as unlocked when ANY clog member of its
     * variation family is - an unlock event lands on one specific id, but
     * charging/degrading swaps which id you hold.
     */
    private boolean familyUnlocked(int clogItemId) {
        for (int member : clogGroupMembers(clogItemId)) {
            if (groupState.isUnlocked(member)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Family-aware unlock check that also follows derived recipes between
     * clog items: a refined clog item crafted from unlocked clog items is
     * itself unlocked (cutting an unlocked Uncut onyx must not yield a
     * locked Onyx, and everything requiring Onyx must unlock with it).
     */
    private boolean clogUnlocked(int clogItemId) {
        return clogUnlocked(clogItemId, 0);
    }

    private boolean clogUnlocked(int clogItemId, int depth) {
        if (familyUnlocked(clogItemId)) {
            return true;
        }
        if (depth >= MAX_DERIVATION_DEPTH) {
            return false;
        }
        List<Integer> requirements = derived.getRequirements(itemManager.canonicalize(clogItemId));
        if (requirements.isEmpty()) {
            return false;
        }
        for (int required : requirements) {
            if (!clogUnlocked(required, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the item is locked: its variation family holds a clog
     * identity of the same logical item the group has not unlocked (any
     * unlocked charge state frees the item; cosmetic clue variants like
     * (g)/(t) are separate unlocks), the clog item its name derives from is locked (ornament
     * kits, trouver parchment), or it is crafted from clog items of which
     * any is still locked (blowpipe from Tanzanite fang etc.).
     * Client thread only (reads item names).
     *
     * <p>Derived requirements are checked against the item's own canonical id
     * first, before the variation-group scan. Some crafted items (e.g. toxic
     * trident/staff pairs) share a RuneLite variation group with just one of
     * their required ingredients - the group scan alone would silently ignore
     * the other required ingredient (e.g. Magic fang) and under-lock the
     * item. Requirement checks are themselves family-aware via
     * {@link #clogUnlocked}.
     */
    public boolean isLocked(int itemId) {
        int canonical = itemManager.canonicalize(itemId);
        List<Integer> requirements = derived.getRequirements(canonical);
        if (!requirements.isEmpty()) {
            // A derived product that is ITSELF a clog identity (refined gems:
            // Onyx from Uncut onyx) is also freed by a direct unlock of its
            // own family. Non-clog products deliberately skip this - some
            // share a variation family with one ingredient (toxic staff) and
            // must not escape their other requirements through it.
            if (clogData.isClogItem(canonical) && familyUnlocked(canonical)) {
                return false;
            }
            for (int required : requirements) {
                if (!clogUnlocked(required)) {
                    return true;
                }
            }
            return false;
        }

        List<Integer> clogMembers = clogGroupMembers(canonical);
        if (!clogMembers.isEmpty()) {
            for (int member : clogMembers) {
                if (groupState.isUnlocked(member)) {
                    return false;
                }
            }
            return true;
        }
        int named = clogItemByStrippedName(canonical);
        return named > 0 && !clogUnlocked(named);
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
