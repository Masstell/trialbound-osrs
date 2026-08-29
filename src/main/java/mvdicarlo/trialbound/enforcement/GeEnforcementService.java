package mvdicarlo.trialbound.enforcement;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.trialbound.TrialboundConfig;
import mvdicarlo.trialbound.TrialboundChat;
import mvdicarlo.trialbound.clog.ClogCacheIds;
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
 * out/hidden, sell offers can't be started from the side inventory, and
 * offer confirmation is consumed as a backstop for any path that still
 * reaches the offer screen (buy or sell).
 */
@Slf4j
@Singleton
public class GeEnforcementService {
    private static final int GE_OFFER_TYPE_BUY = 0;
    private static final int GE_OFFER_TYPE_SELL = 1;

    private final Client client;
    private final TrialboundConfig config;
    private final LockedItemHelper locked;
    private final TrialboundChat chat;

    /** Item id we already warned about on the current offer screen. */
    private int warnedOfferItem = -1;

    @Inject
    public GeEnforcementService(Client client, TrialboundConfig config, LockedItemHelper locked,
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
        int offerItem = currentOfferItem();
        if (offerItem <= 0 || !locked.isLocked(offerItem)) {
            warnedOfferItem = -1;
            return;
        }
        if (warnedOfferItem != offerItem) {
            warnedOfferItem = offerItem;
            String name = client.getItemDefinition(offerItem).getName();
            if (isSellOffer()) {
                chat.send("Trialbound: " + name + " is locked - you can't sell it until it's unlocked.");
            } else {
                chat.send("Trialbound: " + name
                        + " is locked - obtain it as a drop or buy the unlock with Grit.");
            }
        }
    }

    private boolean isSellOffer() {
        return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == GE_OFFER_TYPE_SELL;
    }

    /** The item in the current new offer (buy or sell), or -1. */
    private int currentOfferItem() {
        int type = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
        if (type != GE_OFFER_TYPE_BUY && type != GE_OFFER_TYPE_SELL) {
            return -1;
        }
        return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
    }

    /**
     * Backstop: consume Confirm on a locked offer, clicks on locked search
     * rows, and Offer clicks on locked items in the GE side inventory (the
     * path that starts a sell).
     */
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
            int offerItem = currentOfferItem();
            if (offerItem > 0 && locked.isLocked(offerItem)) {
                event.consume();
                chat.send("Trialbound blocked that " + (isSellOffer() ? "sale" : "purchase") + ": "
                        + client.getItemDefinition(offerItem).getName() + " is locked.");
            }
            return;
        }
        int itemId = event.getMenuEntry().getItemId();
        if (itemId <= 0 || !locked.isLocked(itemId)) {
            return;
        }
        // Clicks inside the GE search result list on a locked item.
        if (widget.getId() == InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS) {
            event.consume();
            return;
        }
        // Starting a sell offer from the GE side inventory.
        if (widget.getId() == InterfaceID.GeOffersSide.ITEMS
                && event.getMenuOption() != null && event.getMenuOption().startsWith("Offer")) {
            event.consume();
            chat.send("Trialbound: " + client.getItemDefinition(itemId).getName()
                    + " is locked - you can't sell it until it's unlocked.");
        }
    }
}
