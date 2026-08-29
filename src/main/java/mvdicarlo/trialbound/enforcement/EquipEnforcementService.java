package mvdicarlo.trialbound.enforcement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.trialbound.TrialboundConfig;
import mvdicarlo.trialbound.TrialboundChat;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * You can hold a locked clog item, but you cannot use it. Deny-by-default:
 * every menu option on a locked item is removed unless it is inert
 * housekeeping (move/bank/drop/trade/info). A verb blocklist can never keep
 * up with the game's vocabulary ("Last teleport", "Reminisce", "Play",
 * destination names...), so unknown verbs are treated as use and stripped,
 * with a click-consume backstop (e.g. for menus built before an unlock
 * state change).
 */
@Singleton
public class EquipEnforcementService {
    /**
     * Options that never USE the item: moving, storing, discarding, info.
     * "Use" is deliberately absent - using a rune ON a locked rune pouch is
     * how you fill it, so both directions of Use are stripped. "Empty" stays
     * so contents are never stranded inside a locked container (the only
     * teleport-on-Empty item, the ectophial, is not a clog item).
     */
    private static final Set<String> ALLOWED_OPTIONS = new HashSet<>(Arrays.asList(
            "Drop", "Destroy", "Examine", "Remove", "Check", "Value", "Take", "Empty"));
    /**
     * Allowed option families (bank, trade, shop, GE collection). "Buy",
     * "Select" and "Exchange" stay: purchasing a locked shop item is the
     * acquisition that UNLOCKS it (shop-whitelist possession path) - it
     * must never be blocked by its own lock.
     */
    private static final String[] ALLOWED_PREFIXES = {
            "Deposit", "Withdraw", "Offer", "Sell", "Store", "Collect",
            "Buy", "Select", "Exchange"};
    private static final long WARN_INTERVAL_MS = 5_000;

    private final Client client;
    private final ItemManager itemManager;
    private final TrialboundConfig config;
    private final LockedItemHelper locked;
    private final TrialboundChat chat;

    private long lastWarnMs;

    @Inject
    public EquipEnforcementService(Client client, ItemManager itemManager, TrialboundConfig config,
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

    /**
     * Widgets that merely DISPLAY an item as an icon without the player
     * holding or using it there. Bank tab icons carry the item id of the
     * first item in the tab - stripping their options would make whole
     * bank tabs unclickable whenever that item is locked.
     */
    private static boolean displayOnlyWidget(Widget widget) {
        return widget != null && widget.getId() == InterfaceID.Bankmain.TABS;
    }

    /** True for options that merely move/store/discard/inspect the item. */
    private static boolean allowedOption(String option) {
        if (option == null || ALLOWED_OPTIONS.contains(option)) {
            return true;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (option.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (!active() || allowedOption(event.getOption())
                || displayOnlyWidget(event.getMenuEntry().getWidget())) {
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
        if (!active() || allowedOption(event.getMenuOption())
                || displayOnlyWidget(event.getMenuEntry().getWidget())) {
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
            chat.send("Trialbound: you can't use "
                    + client.getItemDefinition(itemManager.canonicalize(itemId)).getName()
                    + " - it isn't unlocked yet.");
        }
    }
}
