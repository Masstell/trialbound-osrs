package mvdicarlo.crabmanmode.enforcement;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Dims locked collection log items wherever they appear in your possession
 * (inventory, bank, equipment): the item sprite is redrawn as a darkened
 * grayscale copy - GE-search-style dimming instead of a box overlay.
 */
@Singleton
public class LockedItemOverlay extends WidgetItemOverlay {
    /** Brightness of the dimmed copy (0 = black, 1 = unchanged). */
    private static final float DIM_LUMINANCE = 0.45f;

    private final CrabmanModeConfig config;
    private final LockedItemHelper locked;
    private final ItemManager itemManager;
    private final Map<Integer, BufferedImage> dimmedCache = new ConcurrentHashMap<>();

    @Inject
    public LockedItemOverlay(CrabmanModeConfig config, LockedItemHelper locked, ItemManager itemManager) {
        this.config = config;
        this.locked = locked;
        this.itemManager = itemManager;
        showOnInventory();
        showOnBank();
        showOnEquipment();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (!config.greyLockedItems() || !locked.enforcementActive() || !locked.isLocked(itemId)) {
            return;
        }
        if (widgetItem.getQuantity() <= 0) {
            return; // bank placeholder - already ghosted by the game
        }
        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }
        BufferedImage dimmed = dimmed(itemId);
        if (dimmed != null) {
            graphics.drawImage(dimmed, bounds.x, bounds.y, null);
        }
    }

    /**
     * Darkened grayscale copy of the item sprite, cached per item. The sprite
     * loads asynchronously; until it's ready the item renders undimmed for a
     * frame or two. Quantity text is not part of the copy, so stack counts
     * stay bright and readable on top of the dimmed item.
     */
    private BufferedImage dimmed(int itemId) {
        BufferedImage cached = dimmedCache.get(itemId);
        if (cached != null) {
            return cached;
        }
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> dimmedCache.put(itemId,
                ImageUtil.luminanceScale(ImageUtil.grayscaleImage(image), DIM_LUMINANCE)));
        return dimmedCache.get(itemId);
    }
}
