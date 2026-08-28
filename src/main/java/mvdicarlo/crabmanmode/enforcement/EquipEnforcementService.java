package mvdicarlo.crabmanmode.enforcement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.TrialboundChat;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * You can hold a locked clog item, but you cannot use it: the
 * Wield/Wear/Equip options are removed from the menu, with a click-consume
 * backstop (e.g. for menus built before an unlock state change).
 */
@Singleton
public class EquipEnforcementService {
    private static final Set<String> EQUIP_OPTIONS = new HashSet<>(Arrays.asList("Wield", "Wear", "Equip"));
    private static final long WARN_INTERVAL_MS = 5_000;

    private final Client client;
    private final ItemManager itemManager;
    private final CrabmanModeConfig config;
    private final LockedItemHelper locked;
    private final TrialboundChat chat;

    private long lastWarnMs;

    @Inject
    public EquipEnforcementService(Client client, ItemManager itemManager, CrabmanModeConfig config,
            LockedItemHelper locked, TrialboundChat chat) {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.locked = locked;
        this.chat = chat;
    }

    private boolean active() {
        return config.enforceEquipBlock() && locked.enforcementActive();
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (!active() || !EQUIP_OPTIONS.contains(event.getOption())) {
            return;
        }
        int itemId = event.getMenuEntry().getItemId();
        if (itemId <= 0 || !locked.isLocked(itemId)) {
            return;
        }
        MenuEntry[] entries = client.getMenuEntries();
        List<MenuEntry> kept = new ArrayList<>(entries.length);
        for (MenuEntry entry : entries) {
            if (entry != event.getMenuEntry()) {
                kept.add(entry);
            }
        }
        client.setMenuEntries(kept.toArray(new MenuEntry[0]));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!active() || !EQUIP_OPTIONS.contains(event.getMenuOption())) {
            return;
        }
        int itemId = event.getMenuEntry().getItemId();
        if (itemId <= 0 || !locked.isLocked(itemId)) {
            return;
        }
        event.consume();
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > WARN_INTERVAL_MS) {
            lastWarnMs = now;
            chat.send("Trialbound: you can't equip "
                    + client.getItemDefinition(itemManager.canonicalize(itemId)).getName()
                    + " - it isn't unlocked yet.");
        }
    }
}
