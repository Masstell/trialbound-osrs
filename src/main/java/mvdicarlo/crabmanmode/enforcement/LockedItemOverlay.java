package mvdicarlo.crabmanmode.enforcement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Greys out locked collection log items wherever they appear in your
 * possession (inventory, bank, equipment) so it's obvious at a glance what
 * you're not allowed to use yet.
 */
@Singleton
public class LockedItemOverlay extends WidgetItemOverlay {
    private static final Color WASH = new Color(0, 0, 0, 140);
    private static final Color MARK = new Color(255, 40, 40, 220);

    private final CrabmanModeConfig config;
    private final LockedItemHelper locked;

    @Inject
    public LockedItemOverlay(CrabmanModeConfig config, LockedItemHelper locked) {
        this.config = config;
        this.locked = locked;
        showOnInventory();
        showOnBank();
        showOnEquipment();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (!config.greyLockedItems() || !locked.enforcementActive() || !locked.isLocked(itemId)) {
            return;
        }
        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }
        graphics.setColor(WASH);
        graphics.fill(bounds);
        graphics.setColor(MARK);
        graphics.fillRect(bounds.x + bounds.width - 6, bounds.y + 1, 5, 5);
    }
}
