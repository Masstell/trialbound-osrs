package mvdicarlo.crabmanmode.enforcement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.TrialboundChat;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Warns (never blocks) when locked collection log items appear in a player
 * trade: an overlay lists them and accepting prints one chat warning per
 * trade window.
 */
@Singleton
public class TradeWarningService {
    /** Your trade offer container. */
    private static final int TRADE_SELF = 90;
    /** The other player's trade offer container. */
    private static final int TRADE_OTHER = 32858;

    private final Client client;
    private final ItemManager itemManager;
    private final CrabmanModeConfig config;
    private final LockedItemHelper locked;
    private final TrialboundChat chat;

    private final Set<String> lockedInTrade = new LinkedHashSet<>();
    private boolean warnedThisTrade;

    @Inject
    public TradeWarningService(Client client, ItemManager itemManager, CrabmanModeConfig config,
            LockedItemHelper locked, TrialboundChat chat) {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.locked = locked;
        this.chat = chat;
    }

    public boolean isWarningActive() {
        return config.tradeWarning() && !lockedInTrade.isEmpty();
    }

    public List<String> getLockedItemNames() {
        return Collections.unmodifiableList(new ArrayList<>(lockedInTrade));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != TRADE_SELF && event.getContainerId() != TRADE_OTHER) {
            return;
        }
        if (!config.tradeWarning() || !locked.enforcementActive()) {
            return;
        }
        rescanTrade();
    }

    private void rescanTrade() {
        lockedInTrade.clear();
        for (int containerId : new int[] { TRADE_SELF, TRADE_OTHER }) {
            ItemContainer container = client.getItemContainer(containerId);
            if (container == null) {
                continue;
            }
            for (Item item : container.getItems()) {
                if (item.getId() > 0 && locked.isLocked(item.getId())) {
                    lockedInTrade.add(client.getItemDefinition(itemManager.canonicalize(item.getId())).getName());
                }
            }
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() == InterfaceID.TRADEMAIN || event.getGroupId() == InterfaceID.TRADECONFIRM) {
            lockedInTrade.clear();
            warnedThisTrade = false;
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!isWarningActive() || warnedThisTrade || !"Accept".equals(event.getMenuOption())) {
            return;
        }
        if (event.getMenuEntry().getWidget() == null) {
            return;
        }
        int interfaceId = WidgetUtil.componentToInterface(event.getMenuEntry().getWidget().getId());
        if (interfaceId == InterfaceID.TRADEMAIN || interfaceId == InterfaceID.TRADECONFIRM) {
            warnedThisTrade = true;
            chat.send("Trialbound warning: this trade contains locked collection log items: "
                    + String.join(", ", lockedInTrade) + ".");
        }
    }
}
