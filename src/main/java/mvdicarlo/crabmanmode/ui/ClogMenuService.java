package mvdicarlo.crabmanmode.ui;

import java.awt.Color;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.grit.GritService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.PurchaseResult;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;

/**
 * The collection log is the storefront: right-clicking a locked item inside
 * it offers "Unlock (N Grit)" with a confirm dialog.
 */
@Slf4j
@Singleton
public class ClogMenuService {
    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final GroupStateService groupState;
    private final GritService gritService;
    private final TrialboundChat chat;
    private final mvdicarlo.crabmanmode.enforcement.LockedItemHelper locked;

    @Inject
    public ClogMenuService(Client client, ClientThread clientThread, ItemManager itemManager,
            SessionState sessionState, ClogDataService clogData, GroupStateService groupState, GritService gritService,
            TrialboundChat chat, mvdicarlo.crabmanmode.enforcement.LockedItemHelper locked) {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.gritService = gritService;
        this.chat = chat;
        this.locked = locked;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        logClogMenuDiagnostic(event);
        if (!sessionState.isActive() || !clogData.isLoaded()
                || client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            return;
        }
        for (MenuEntry entry : event.getMenuEntries()) {
            int itemId = lockedClogItemFor(entry.getWidget());
            if (itemId <= 0) {
                continue;
            }
            int price = gritService.getPrice(itemId);
            String name = clogData.getItemName(itemId);
            log.debug("Adding unlock menu entry for {} ({})", name, itemId);
            client.getMenu().createMenuEntry(-1)
                    .setOption("Unlock (" + price + " Grit)")
                    .setTarget(ColorUtil.wrapWithColorTag(name, new Color(0xff9040)))
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> confirmPurchase(itemId, name, price));
            return;
        }
    }

    /**
     * Diagnostic: whenever a right-click menu opens while the collection log
     * is up, log every entry so menu-structure issues are visible in the log.
     */
    private void logClogMenuDiagnostic(MenuOpened event) {
        if (client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS) == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("Clog menu entries:");
        for (MenuEntry entry : event.getMenuEntries()) {
            Widget widget = entry.getWidget();
            sb.append(" [").append(entry.getOption())
                    .append(" widget=").append(widget == null ? "none" : widget.getId())
                    .append(" item=").append(widget == null ? -1 : widget.getItemId())
                    .append(" type=").append(entry.getType()).append(']');
        }
        log.info("{}", sb);
    }

    /** The locked clog item a menu widget points at, or -1. */
    private int lockedClogItemFor(Widget widget) {
        if (widget == null || widget.getId() != InterfaceID.Collection.ITEMS_CONTENTS) {
            return -1;
        }
        int itemId = itemManager.canonicalize(widget.getItemId());
        // Family-aware: an unlock on any charge state (Uncharged trident vs
        // the (full) clog entry) must suppress the purchase option.
        if (itemId <= 0 || !clogData.isClogItem(itemId) || !locked.isLocked(itemId)) {
            return -1;
        }
        return itemId;
    }

    private void confirmPurchase(int itemId, String name, int price) {
        int pooled = groupState.getPooledGrit();
        // Grab the game window on the client thread so the dialog parents to
        // it - an orphan dialog can open behind the client ("nothing happens").
        final java.awt.Component parent = client.getCanvas();
        SwingUtilities.invokeLater(() -> {
            java.awt.Window window = SwingUtilities.getWindowAncestor(parent);
            int choice = JOptionPane.showConfirmDialog(window,
                    "Unlock " + name + " for " + price + " Grit?\nPooled Grit: " + pooled,
                    "Trialbound unlock", JOptionPane.OK_CANCEL_OPTION);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            PurchaseResult result = gritService.purchaseUnlock(itemId, name);
            log.info("In-clog purchase of {} ({}) -> {}", name, itemId, result);
            clientThread.invokeLater(() -> reportResult(result, name, price));
        });
    }

    private void reportResult(PurchaseResult result, String name, int price) {
        switch (result) {
            case SUCCESS:
                // The group state listener announces the unlock itself.
                break;
            case ALREADY_UNLOCKED:
                chat.send(name + " is already unlocked.");
                break;
            case INSUFFICIENT_GRIT:
                chat.send("Not enough pooled Grit for " + name + " (need " + price + ", have "
                        + groupState.getPooledGrit() + ").");
                break;
            default:
                chat.send("Trialbound isn't ready - purchase failed.");
                break;
        }
    }
}
