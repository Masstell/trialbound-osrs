package mvdicarlo.crabmanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CrabmanModePlugin.CONFIG_GROUP)
public interface CrabmanModeConfig extends Config {
    @ConfigSection(name = "General", description = "Core Trialbound settings", position = 0)
    String generalSection = "general";

    @ConfigSection(name = "Group sync", description = "Party-based syncing with your group", position = 1)
    String syncSection = "sync";

    @ConfigSection(name = "Enforcement", description = "How strictly locks are enforced in the client", position = 4)
    String enforcementSection = "enforcement";

    @ConfigSection(name = "Notifications & overlays", description = "Toasts, overlays and reminders", position = 5)
    String notificationSection = "notifications";

    // --- General ---

    @ConfigItem(keyName = "enableCrabman", name = "Character name", position = 1, description = "Enables Trialbound for the provided character name.", section = generalSection)
    default String enableCrabman() {
        return "";
    }

    @ConfigItem(keyName = "namesBronzeman", name = "Trialbound names", position = 2, description = "Names of fellow Trialbound players to mark with a chat icon. Format: (name), (name)", section = generalSection)
    default String namesBronzeman() {
        return "";
    }

    // --- Group sync ---

    @ConfigItem(secret = true, keyName = "partyPassphrase", name = "Party passphrase", position = 1, description = "Dedicated party passphrase for your Trialbound group. The plugin joins this party automatically to sync unlocks and Grit.", section = syncSection)
    default String partyPassphrase() {
        return "";
    }

    @ConfigItem(secret = true, keyName = "groupPassword", name = "Group password", position = 2, description = "Shared group password. Sync messages are authenticated with it so only your group can contribute unlocks and Grit.", section = syncSection)
    default String groupPassword() {
        return "";
    }

    // --- Enforcement ---

    @ConfigItem(keyName = "enforceGeBlock", name = "Block GE for locked items", position = 1, description = "Grey locked collection log items out of GE search and block buy offers for them.", section = enforcementSection)
    default boolean enforceGeBlock() {
        return true;
    }

    @ConfigItem(keyName = "enforceEquipBlock", name = "Block using locked items", position = 2, description = "Remove equip and consume options (wield, wear, break, eat, invoke...) on collection log items you have not unlocked.", section = enforcementSection)
    default boolean enforceEquipBlock() {
        return true;
    }

    @ConfigItem(keyName = "greyLockedItems", name = "Grey out locked items", position = 4, description = "Darken locked collection log items in your inventory, bank and equipment.", section = enforcementSection)
    default boolean greyLockedItems() {
        return true;
    }

    @ConfigItem(keyName = "tradeWarning", name = "Warn on locked trade items", position = 3, description = "Show a warning when a locked collection log item appears in a player trade. Never blocks the trade.", section = enforcementSection)
    default boolean tradeWarning() {
        return true;
    }

    // --- Notifications & overlays ---

    @ConfigItem(keyName = "showClogOverlay", name = "Collection log markers", position = 1, description = "Mark unlocked/locked state (and prices) on items inside the collection log interface.", section = notificationSection)
    default boolean showClogOverlay() {
        return true;
    }

    @ConfigItem(keyName = "showGritToasts", name = "Grit gain toasts", position = 2, description = "Show an on-screen toast when you earn Grit.", section = notificationSection)
    default boolean showGritToasts() {
        return true;
    }

    @ConfigItem(keyName = "showTrialsOverlay", name = "Trials overlay", position = 3, description = "Show the active trials as an on-screen overlay.", section = notificationSection)
    default boolean showTrialsOverlay() {
        return false;
    }

    @ConfigItem(keyName = "nudgeClogSync", name = "Remind to sync collection log", position = 4, description = "Remind you to open your collection log so Trialbound can read which items you have obtained.", section = notificationSection)
    default boolean nudgeClogSync() {
        return true;
    }

    @ConfigItem(keyName = "nudgeClogSetting", name = "Remind about game setting", position = 5, description = "Remind you to enable the in-game 'Collection log - New addition notification' setting.", section = notificationSection)
    default boolean nudgeClogSetting() {
        return true;
    }

    // Legacy Azure storage key; removed along with the database layer.
    @ConfigItem(hidden = true, secret = true, keyName = "databaseString", name = "Azure Storage Account SAS URL", description = "Legacy group storage SAS URL.")
    default String databaseString() {
        return "";
    }
}
