package mvdicarlo.crabmanmode.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.grit.GritService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Marks group unlock state on every item inside the collection log interface
 * (green = unlocked, red = locked) and shows a price tooltip on hover. The
 * right-click "Unlock (N Grit)" entry comes from ClogMenuService.
 */
@Singleton
public class ClogHighlightOverlay extends Overlay {
    private static final Color UNLOCKED_MARK = new Color(0, 255, 0, 200);
    private static final Color LOCKED_MARK = new Color(255, 40, 40, 220);
    private static final Color LOCKED_WASH = new Color(255, 0, 0, 45);

    private final Client client;
    private final ItemManager itemManager;
    private final CrabmanModeConfig config;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final GroupStateService groupState;
    private final GritService gritService;
    private final TooltipManager tooltipManager;

    @Inject
    public ClogHighlightOverlay(Client client, ItemManager itemManager, CrabmanModeConfig config,
            SessionState sessionState, ClogDataService clogData, GroupStateService groupState, GritService gritService,
            TooltipManager tooltipManager) {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.groupState = groupState;
        this.gritService = gritService;
        this.tooltipManager = tooltipManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showClogOverlay() || !sessionState.isActive() || !clogData.isLoaded()) {
            return null;
        }
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            return null; // someone else's log via the POH adventure log
        }
        Widget container = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (container == null || container.isHidden()) {
            return null;
        }
        Widget[] children = container.getDynamicChildren();
        if (children == null) {
            return null;
        }

        Map<Integer, TbEventRecord> unlocked = groupState.getUnlockedItems();
        Point mouse = client.getMouseCanvasPosition();

        for (Widget child : children) {
            if (child == null || child.isHidden() || child.getItemId() <= 0) {
                continue;
            }
            int itemId = itemManager.canonicalize(child.getItemId());
            if (!clogData.isClogItem(itemId)) {
                continue;
            }
            Rectangle bounds = child.getBounds();
            TbEventRecord unlock = unlocked.get(itemId);
            if (unlock != null) {
                graphics.setColor(UNLOCKED_MARK);
                graphics.fillRect(bounds.x + bounds.width - 7, bounds.y + 2, 5, 5);
            } else {
                graphics.setColor(LOCKED_WASH);
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                // Outline the item sprite itself in red (quantity 1 so stack
                // digits are not outlined too).
                graphics.drawImage(itemManager.getItemOutline(itemId, 1, LOCKED_MARK),
                        bounds.x, bounds.y, null);
            }

            if (bounds.contains(mouse.getX(), mouse.getY())) {
                String name = clogData.getItemName(itemId);
                if (unlock != null) {
                    tooltipManager.add(new Tooltip(name + "</br>Unlocked by " + unlock.getPlayer()));
                } else {
                    tooltipManager.add(new Tooltip(name + "</br>Locked - right-click to unlock for "
                            + gritService.getPrice(itemId) + " Grit</br>Pooled Grit: " + groupState.getPooledGrit()));
                }
            }
        }
        return null;
    }
}
