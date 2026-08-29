package mvdicarlo.trialbound.loot;

import java.util.HashMap;
import java.util.Map;

import lombok.Value;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;

/**
 * Reward-chest interfaces whose loot never fires NPC loot events. Handled with
 * our own WidgetLoaded subscription so raid attribution does not depend on the
 * Loot Tracker plugin being enabled. Source names are chosen to match the
 * collection log page (or a tier-file alias) exactly.
 */
public final class ChestLootSource {
    @Value
    public static class Source {
        String sourceName;
        int containerId;
    }

    private static final Map<Integer, Source> BY_GROUP_ID = new HashMap<>();

    static {
        // Barrows reward container has no gameval constant; 141 is its long-stable id.
        register(InterfaceID.BARROWS_REWARD, "Barrows Chests", 141);
        register(InterfaceID.RAIDS_REWARDS, "Chambers of Xeric", InventoryID.RAIDS_REWARDS);
        register(InterfaceID.TOB_CHESTS, "Theatre of Blood", InventoryID.TOB_CHESTS);
        register(InterfaceID.TOA_CHESTS, "Tombs of Amascut", InventoryID.TOA_CHESTS);
        register(InterfaceID.PMOON_REWARD, "Moons of Peril", InventoryID.PMOON_REWARDINV);
        register(InterfaceID.COLOSSEUM_REWARD_CHEST, "Fortis Colosseum", InventoryID.COLOSSEUM_REWARDS);
        register(InterfaceID.COLOSSEUM_REWARD_CHEST_2, "Fortis Colosseum", InventoryID.COLOSSEUM_REWARDS);
    }

    private static void register(int groupId, String sourceName, int containerId) {
        BY_GROUP_ID.put(groupId, new Source(sourceName, containerId));
    }

    public static Source byGroupId(int groupId) {
        return BY_GROUP_ID.get(groupId);
    }

    public static boolean coversSourceName(String name) {
        for (Source source : BY_GROUP_ID.values()) {
            if (source.sourceName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private ChestLootSource() {
    }
}
