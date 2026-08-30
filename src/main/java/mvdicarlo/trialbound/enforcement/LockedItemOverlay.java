package mvdicarlo.trialbound.enforcement;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.trialbound.TrialboundConfig;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Dims locked collection log items wherever they appear in your possession
 * (inventory, bank, equipment): the item sprite is redrawn as a lightly
 * grayed, red-tinted copy - GE-search-style dimming instead of a box overlay.
 */
@Singleton
public class LockedItemOverlay extends WidgetItemOverlay {
    /** Brightness of the dimmed copy (0 = black, 1 = unchanged). */
    private static final float DIM_LUMINANCE = 0.8f;
    /** Strength of the red blend applied on top of the dimmed copy (0 = none, 1 = full red). */
    private static final float RED_TINT_STRENGTH = 0.15f;
    private static final int TINT_R = 255;
    private static final int TINT_G = 40;
    private static final int TINT_B = 40;

    private final TrialboundConfig config;
    private final LockedItemHelper locked;
    private final ItemManager itemManager;
    private final Map<Integer, BufferedImage> dimmedCache = new ConcurrentHashMap<>();

    @Inject
    public LockedItemOverlay(TrialboundConfig config, LockedItemHelper locked, ItemManager itemManager) {
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
                redTint(ImageUtil.luminanceScale(ImageUtil.grayscaleImage(image), DIM_LUMINANCE))));
        return dimmedCache.get(itemId);
    }

    /**
     * Blends each opaque pixel toward a red hue, leaving alpha untouched so
     * the sprite's silhouette and transparency are preserved.
     */
    private static BufferedImage redTint(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage tinted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = src.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                r = Math.round(r + (TINT_R - r) * RED_TINT_STRENGTH);
                g = Math.round(g + (TINT_G - g) * RED_TINT_STRENGTH);
                b = Math.round(b + (TINT_B - b) * RED_TINT_STRENGTH);
                tinted.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }
}
