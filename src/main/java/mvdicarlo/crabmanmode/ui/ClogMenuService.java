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

    @Inject
    public ClogMenuService(Client client, ClientThread clientThread, ItemManager itemManager,
            SessionState sessionState, ClogDataService clogData, GroupStateService groupState, GritService gritService,
            TrialboundChat chat) {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.gritService = gritService;
        this.chat = chat;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (!sessionState.isActive() || !clogData.isLoaded()
                || client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            return;
        }
        for (MenuEntry entry : event.getMenuEntries()) {
            Widget widget = entry.getWidget();
            if (widget == null || widget.getId() != InterfaceID.Collection.ITEMS_CONTENTS) {
                continue;
            }
            int itemId = itemManager.canonicalize(widget.getItemId());
            if (itemId <= 0 || !clogData.isClogItem(itemId) || groupState.isUnlocked(itemId)) {
                return;
            }
            int price = gritService.getPrice(itemId);
            String name = clogData.getItemName(itemId);
            client.getMenu().createMenuEntry(-1)
                    .setOption("Unlock (" + price + " Grit)")
                    .setTarget(ColorUtil.wrapWithColorTag(name, new Color(0xff9040)))
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> confirmPurchase(itemId, name, price));
            return;
        }
    }

    private void confirmPurchase(int itemId, String name, int price) {
        int pooled = groupState.getPooledGrit();
        SwingUtilities.invokeLater(() -> {
            int choice = JOptionPane.showConfirmDialog(null,
                    "Unlock " + name + " for " + price + " Grit?\nPooled Grit: " + pooled,
                    "Trialbound unlock", JOptionPane.OK_CANCEL_OPTION);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            PurchaseResult result = gritService.purchaseUnlock(itemId, name);
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
