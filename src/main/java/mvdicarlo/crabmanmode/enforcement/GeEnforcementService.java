package mvdicarlo.crabmanmode.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.clog.ClogCacheIds;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.Subscribe;

/**
 * Hard GE block for locked collection log items: search results are greyed
 * out/hidden, and buy-offer confirmation is consumed as a backstop for any
 * path that still reaches the offer screen.
 */
@Slf4j
@Singleton
public class GeEnforcementService {
    private static final int GE_OFFER_TYPE_BUY = 0;

    private final Client client;
    private final CrabmanModeConfig config;
    private final LockedItemHelper locked;
    private final TrialboundChat chat;

    /** Item id we already warned about on the current offer screen. */
    private int warnedOfferItem = -1;

    @Inject
    public GeEnforcementService(Client client, CrabmanModeConfig config, LockedItemHelper locked,
            TrialboundChat chat) {
        this.client = client;
        this.config = config;
        this.locked = locked;
        this.chat = chat;
    }

    private boolean active() {
        return config.enforceGeBlock() && locked.enforcementActive();
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ClogCacheIds.SCRIPT_GE_SEARCH_BUILD_LEGACY
                || event.getScriptId() == ScriptID.GE_ITEM_SEARCH) {
            filterSearchResults();
        } else if (event.getScriptId() == ScriptID.GE_OFFERS_SETUP_BUILD) {
            checkOfferScreen();
        }
    }

    /**
     * GE search results render as widget triples (clickable row, name, item).
     * Locked items get their row hidden and the rest greyed. Idempotent; runs
     * for both the legacy bronzeman script id and the named one.
     */
    private void filterSearchResults() {
        if (!active()) {
            return;
        }
        Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (results == null) {
            return;
        }
        Widget[] children = results.getDynamicChildren();
        if (children == null || children.length < 2 || children.length % 3 != 0) {
            return;
        }
        for (int i = 0; i < children.length; i += 3) {
            // isLocked also catches ornament-kit variants of locked clog items.
            if (locked.isLocked(children[i + 2].getItemId())) {
                children[i].setHidden(true);
                // 0 = opaque, 255 = invisible; fade hard so locked items are
                // unmistakable at a glance but still identifiable.
                children[i + 1].setOpacity(180);
                children[i + 2].setOpacity(180);
            }
        }
    }

    private void checkOfferScreen() {
        if (!active()) {
            warnedOfferItem = -1;
            return;
        }
        int offerItem = currentBuyOfferItem();
        if (offerItem <= 0 || !locked.isLocked(offerItem)) {
            warnedOfferItem = -1;
            return;
        }
        if (warnedOfferItem != offerItem) {
            warnedOfferItem = offerItem;
            chat.send("Trialbound: " + client.getItemDefinition(offerItem).getName()
                    + " is locked - obtain it as a drop or buy the unlock with Grit.");
        }
    }

    /** The item in the current NEW BUY offer, or -1. */
    private int currentBuyOfferItem() {
        if (client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) != GE_OFFER_TYPE_BUY) {
            return -1;
        }
        return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
    }

    /** Backstop: consume Confirm on a locked buy offer and clicks on locked search rows. */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!active()) {
            return;
        }
        Widget widget = event.getMenuEntry().getWidget();
        if (widget == null) {
            return;
        }
        int interfaceId = WidgetUtil.componentToInterface(widget.getId());
        if (interfaceId == InterfaceID.GE_OFFERS && "Confirm".equals(event.getMenuOption())) {
            int offerItem = currentBuyOfferItem();
            if (offerItem > 0 && locked.isLocked(offerItem)) {
                event.consume();
                chat.send("Trialbound blocked that purchase: "
                        + client.getItemDefinition(offerItem).getName() + " is locked.");
            }
            return;
        }
        // Clicks inside the GE search result list on a locked item.
        int itemId = event.getMenuEntry().getItemId();
        if (itemId > 0 && widget.getId() == InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS
                && locked.isLocked(itemId)) {
            event.consume();
        }
    }
}
